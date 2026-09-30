use crate::{
    model::*,
    selection::{mix, Selection},
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Point {
    x: u8,
    y: u8,
}

fn identity() -> Vec<Point> {
    vec![Point { x: 0, y: 0 }, Point { x: 255, y: 255 }]
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
struct Curve {
    points: Vec<Point>,
}

impl Default for Curve {
    fn default() -> Self {
        Self { points: identity() }
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Curves {
    rgb: Curve,
    red: Curve,
    green: Curve,
    blue: Curve,
}

fn lookup(points: &[Point]) -> Result<[u8; 256], String> {
    if !(2..=MAX_CURVE_POINTS).contains(&points.len())
        || points[0].x != 0
        || points[points.len() - 1].x != 255
        || points.windows(2).any(|pair| pair[0].x >= pair[1].x)
    {
        return Err("曲线控制点无效".into());
    }
    let n = points.len();
    let mut h = [0.0; MAX_CURVE_POINTS];
    let mut d = [0.0; MAX_CURVE_POINTS];
    let mut slope = [0.0; MAX_CURVE_POINTS];
    for i in 0..n - 1 {
        h[i] = f64::from(points[i + 1].x) - f64::from(points[i].x);
        d[i] = (f64::from(points[i + 1].y) - f64::from(points[i].y)) / h[i];
    }
    slope[0] = d[0];
    slope[n - 1] = d[n - 2];
    if n > 2 {
        fn edge(h0: f64, h1: f64, d0: f64, d1: f64) -> f64 {
            let value = ((2.0 * h0 + h1) * d0 - h0 * d1) / (h0 + h1);
            if value * d0 <= 0.0 {
                0.0
            } else if d0 * d1 <= 0.0 && value.abs() > 3.0 * d0.abs() {
                3.0 * d0
            } else {
                value
            }
        }
        slope[0] = edge(h[0], h[1], d[0], d[1]);
        slope[n - 1] = edge(h[n - 2], h[n - 3], d[n - 2], d[n - 3]);
        for i in 1..n - 1 {
            if d[i - 1] * d[i] > 0.0 {
                let w1 = 2.0 * h[i] + h[i - 1];
                let w2 = h[i] + 2.0 * h[i - 1];
                slope[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i]);
            }
        }
    }
    let mut output = [0; 256];
    let mut segment = 0;
    for (x, value) in output.iter_mut().enumerate() {
        while segment + 1 < n - 1 && x > usize::from(points[segment + 1].x) {
            segment += 1;
        }
        let t = (x as f64 - f64::from(points[segment].x)) / h[segment];
        let t2 = t * t;
        let t3 = t2 * t;
        *value = ((2.0 * t3 - 3.0 * t2 + 1.0) * f64::from(points[segment].y)
            + (t3 - 2.0 * t2 + t) * h[segment] * slope[segment]
            + (-2.0 * t3 + 3.0 * t2) * f64::from(points[segment + 1].y)
            + (t3 - t2) * h[segment] * slope[segment + 1])
            .round()
            .clamp(0.0, 255.0) as u8;
    }
    Ok(output)
}

pub(crate) fn tables(curves: &Curves) -> Result<[[u8; 256]; 3], String> {
    let master = lookup(&curves.rgb.points)?;
    let channels = [
        &curves.red.points,
        &curves.green.points,
        &curves.blue.points,
    ];
    let mut tables = [[0; 256]; 3];
    for (table, points) in tables.iter_mut().zip(channels) {
        let channel = lookup(points)?;
        for (i, value) in table.iter_mut().enumerate() {
            *value = channel[usize::from(master[i])];
        }
    }
    Ok(tables)
}

pub(crate) fn mapped(pixel: [u8; 4], coverage: u8, tables: &[[u8; 256]; 3]) -> [u8; 4] {
    let alpha = u32::from(pixel[3]);
    if alpha == 0 || coverage == 0 {
        return pixel;
    }
    let mut result = pixel;
    for channel in 0..3 {
        let value = ((u32::from(pixel[channel]) * 255 + alpha / 2) / alpha).min(255) as usize;
        let adjusted = ((u32::from(tables[channel][value]) * alpha + 127) / 255) as u8;
        result[channel] = mix(pixel[channel], adjusted, coverage);
    }
    result
}

pub fn apply(
    layer: &mut Layer,
    region: Rect,
    selection: Option<&Selection>,
    curves: &Curves,
) -> Result<(), String> {
    let raster = layer.raster_mut()?;
    if raster.is_indexed() {
        return Err("索引色曲线调整尚未支持，请先转换为 RGBA".into());
    }
    let tables = tables(curves)?;
    if tables
        .iter()
        .all(|table| table.iter().enumerate().all(|(i, &v)| i == usize::from(v)))
        || selection.is_some_and(Selection::is_empty)
    {
        return Ok(());
    }
    for (&(tx, ty), tile) in raster.tiles_mut() {
        let Some(area) = region.intersect(Rect {
            left: tx * TILE_SIZE,
            top: ty * TILE_SIZE,
            right: (tx + 1) * TILE_SIZE,
            bottom: (ty + 1) * TILE_SIZE,
        }) else {
            continue;
        };
        if selection.is_some_and(|selection| !selection.intersects(area)) {
            continue;
        }
        let pixels = Arc::make_mut(tile);
        for y in area.top..area.bottom {
            let mask = selection.and_then(|selection| selection.row(y));
            for x in area.left..area.right {
                let coverage = mask.map_or(255, |row| {
                    row[(x - selection.unwrap().bounds().left) as usize]
                });
                if coverage == 0 {
                    continue;
                }
                let offset = (((y % TILE_SIZE) * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                let mapped = mapped(
                    pixels[offset..offset + 4].try_into().unwrap(),
                    coverage,
                    &tables,
                );
                pixels[offset..offset + 4].copy_from_slice(&mapped);
            }
        }
    }
    Ok(())
}

pub fn histogram(
    document: &Document,
    selection: Option<&Selection>,
) -> Result<Vec<Vec<f32>>, String> {
    let mut bins = [[0u64; 256]; 4];
    let region = selection.map_or(document.bounds(), Selection::bounds);
    let layer = document
        .layers
        .iter()
        .find(|layer| layer.id == document.active)
        .ok_or("图层不存在")?;
    for &(tx, ty) in layer.raster()?.tiles().keys() {
        let pixels = layer
            .rgba_tile(document.palette.as_ref(), (tx, ty))
            .unwrap();
        let Some(area) = region.intersect(Rect {
            left: tx * TILE_SIZE,
            top: ty * TILE_SIZE,
            right: (tx + 1) * TILE_SIZE,
            bottom: (ty + 1) * TILE_SIZE,
        }) else {
            continue;
        };
        for y in area.top..area.bottom {
            for x in area.left..area.right {
                let i = (((y % TILE_SIZE) * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                let alpha = u32::from(pixels[i + 3]);
                let coverage = selection.map_or(255, |selection| selection.coverage(x, y));
                if alpha == 0 || coverage == 0 {
                    continue;
                }
                let weight = u64::from(alpha) * u64::from(coverage);
                for c in 0..3 {
                    let value =
                        ((u32::from(pixels[i + c]) * 255 + alpha / 2) / alpha).min(255) as usize;
                    bins[c + 1][value] += weight;
                    bins[0][value] += weight;
                }
            }
        }
    }
    Ok(bins
        .iter()
        .map(|channel| {
            let peak = *channel.iter().max().unwrap();
            channel
                .iter()
                .map(|&value| {
                    if peak == 0 {
                        0.0
                    } else {
                        value as f32 / peak as f32
                    }
                })
                .collect()
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn smooth_curve_preserves_knots_extrema_identity_and_linear_inversion() {
        assert_eq!(
            lookup(&identity()).unwrap(),
            std::array::from_fn(|i| i as u8)
        );
        assert_eq!(
            lookup(&[Point { x: 0, y: 255 }, Point { x: 255, y: 0 }]).unwrap(),
            std::array::from_fn(|i| 255 - i as u8)
        );
        for ys in [[0, 30, 210, 255], [20, 200, 60, 160], [80, 80, 80, 80]] {
            let points: Vec<_> = [0, 64, 192, 255]
                .into_iter()
                .zip(ys)
                .map(|(x, y)| Point { x, y })
                .collect();
            let table = lookup(&points).unwrap();
            for point in &points {
                assert_eq!(table[usize::from(point.x)], point.y);
            }
            for pair in points.windows(2) {
                assert!(table[usize::from(pair[0].x)..=usize::from(pair[1].x)]
                    .iter()
                    .all(
                        |value| (pair[0].y.min(pair[1].y)..=pair[0].y.max(pair[1].y))
                            .contains(value)
                    ));
            }
        }
    }
}
