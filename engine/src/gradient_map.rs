use crate::{
    model::*,
    selection::{mix, Selection},
};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeSet, HashMap, HashSet},
    sync::Arc,
};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct GradientMapStop {
    pub position: f64,
    pub color: [u8; 3],
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct GradientMap {
    pub stops: Vec<GradientMapStop>,
}

impl Default for GradientMap {
    fn default() -> Self {
        Self {
            stops: vec![
                GradientMapStop {
                    position: 0.0,
                    color: [0; 3],
                },
                GradientMapStop {
                    position: 1.0,
                    color: [255; 3],
                },
            ],
        }
    }
}

pub(crate) fn lookup(settings: &GradientMap) -> Result<[[u8; 3]; 256], String> {
    let stops = &settings.stops;
    if !(2..=MAX_GRADIENT_MAP_STOPS).contains(&stops.len())
        || stops
            .iter()
            .any(|stop| !stop.position.is_finite() || !(0.0..=1.0).contains(&stop.position))
        || stops
            .windows(2)
            .any(|pair| pair[0].position >= pair[1].position)
    {
        return Err("渐变映射色标无效".into());
    }
    let mut output = [[0; 3]; 256];
    let mut segment = 0;
    for (index, color) in output.iter_mut().enumerate() {
        let position = index as f64 / 255.0;
        if position <= stops[0].position {
            *color = stops[0].color;
        } else if position >= stops[stops.len() - 1].position {
            *color = stops[stops.len() - 1].color;
        } else {
            while stops[segment + 1].position < position {
                segment += 1;
            }
            let left = &stops[segment];
            let right = &stops[segment + 1];
            let amount = (position - left.position) / (right.position - left.position);
            *color = std::array::from_fn(|channel| {
                (f64::from(left.color[channel]) * (1.0 - amount)
                    + f64::from(right.color[channel]) * amount)
                    .round() as u8
            });
        }
    }
    Ok(output)
}

pub(crate) fn mapped(pixel: [u8; 4], coverage: u8, table: &[[u8; 3]; 256]) -> [u8; 4] {
    let alpha = u32::from(pixel[3]);
    if alpha == 0 || coverage == 0 {
        return pixel;
    }
    let straight: [u32; 3] = std::array::from_fn(|channel| {
        ((u32::from(pixel[channel]) * 255 + alpha / 2) / alpha).min(255)
    });
    let luma =
        ((straight[0] * 2126 + straight[1] * 7152 + straight[2] * 722 + 5000) / 10_000) as usize;
    let mut result = pixel;
    for channel in 0..3 {
        let adjusted = ((u32::from(table[luma][channel]) * alpha + 127) / 255) as u8;
        result[channel] = mix(pixel[channel], adjusted, coverage);
    }
    result
}

fn area(region: Rect, key: TileKey) -> Option<Rect> {
    region.intersect(Rect {
        left: key.0 * TILE_SIZE,
        top: key.1 * TILE_SIZE,
        right: (key.0 + 1) * TILE_SIZE,
        bottom: (key.1 + 1) * TILE_SIZE,
    })
}

pub fn apply(
    document: &mut Document,
    region: Rect,
    selection: Option<&Selection>,
    settings: &GradientMap,
) -> Result<(), String> {
    let index = crate::groups::check_editable(document, document.active, true)?;
    if document.layers[index].raster()?.is_indexed() {
        return Err("索引色渐变映射尚未支持，请先转换为 RGBA".into());
    }
    let table = lookup(settings)?;
    if selection.is_some_and(Selection::is_empty) {
        return Ok(());
    }
    let layer = &document.layers[index];
    let mut changed = BTreeSet::new();
    for (&key, tile) in layer.raster()?.tiles() {
        let Some(area) = area(region, key) else {
            continue;
        };
        if selection.is_some_and(|selection| !selection.intersects(area)) {
            continue;
        }
        if (area.top..area.bottom).any(|y| {
            (area.left..area.right).any(|x| {
                let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                let pixel = tile[offset..offset + 4].try_into().unwrap();
                let coverage = selection.map_or(255, |selection| selection.coverage(x, y));
                mapped(pixel, coverage, &table) != pixel
            })
        }) {
            changed.insert(key);
        }
    }
    if changed.is_empty() {
        return Ok(());
    }
    let mut live = HashSet::new();
    for layer in &document.layers {
        if let Some(raster) = layer.raster_opt() {
            for (key, tile) in raster.tiles() {
                if layer.id != document.active || !changed.contains(key) {
                    live.insert(Arc::as_ptr(tile));
                }
            }
        }
        live.extend(layer.mask_buffers().map(Arc::as_ptr));
    }
    let retained: HashMap<_, _> = layer
        .raster()?
        .tiles()
        .iter()
        .filter(|(key, tile)| changed.contains(*key) && !live.contains(&Arc::as_ptr(tile)))
        .map(|(_, tile)| (Arc::as_ptr(tile), tile.len()))
        .collect();
    if retained.values().sum::<usize>() > MAX_HISTORY_BYTES {
        return Err("操作会超出撤销内存限制".into());
    }
    let layer = document.active_mut();
    for key in changed {
        let area = area(region, key).unwrap();
        let pixels = Arc::make_mut(layer.raster_mut()?.tiles_mut().get_mut(&key).unwrap());
        for y in area.top..area.bottom {
            for x in area.left..area.right {
                let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                let pixel = pixels[offset..offset + 4].try_into().unwrap();
                let coverage = selection.map_or(255, |selection| selection.coverage(x, y));
                pixels[offset..offset + 4].copy_from_slice(&mapped(pixel, coverage, &table));
            }
        }
    }
    Ok(())
}
