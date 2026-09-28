use crate::model::*;
use crate::selection::{mix, Selection};
use serde::Deserialize;
use std::sync::Arc;

#[derive(Clone, Copy, Deserialize)]
pub struct Tone {
    pub brightness: f32,
    pub contrast: f32,
    pub saturation: f32,
}

pub fn tone(
    layer: &mut Layer,
    region: Rect,
    selection: Option<&Selection>,
    settings: Tone,
) -> Result<(), String> {
    if [settings.brightness, settings.contrast, settings.saturation]
        .iter()
        .any(|v| !v.is_finite() || !(-1.0..=1.0).contains(v))
    {
        return Err("色彩参数必须在 -1 到 1 之间".into());
    }
    let gain = (1.0 + settings.contrast) / (1.0 - settings.contrast).max(0.001);
    let mut lut = [0.0_f32; 256];
    for (i, value) in lut.iter_mut().enumerate() {
        *value = (((i as f32 / 255.0 - 0.5) * gain + 0.5) + settings.brightness).clamp(0.0, 1.0);
    }
    for (&(tx, ty), tile) in &mut layer.tiles {
        let bounds = Rect {
            left: tx * TILE_SIZE,
            top: ty * TILE_SIZE,
            right: (tx + 1) * TILE_SIZE,
            bottom: (ty + 1) * TILE_SIZE,
        };
        let Some(area) = bounds.intersect(region) else {
            continue;
        };
        let pixels = Arc::make_mut(tile);
        for y in area.top..area.bottom {
            for x in area.left..area.right {
                let coverage = selection.map_or(255, |selection| selection.coverage(x, y));
                if coverage == 0 {
                    continue;
                }
                let i = (((y % TILE_SIZE) * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                let alpha = u32::from(pixels[i + 3]);
                if alpha == 0 {
                    continue;
                }
                let rgb: [f32; 3] = std::array::from_fn(|c| {
                    lut[((u32::from(pixels[i + c]) * 255 + alpha / 2) / alpha).min(255) as usize]
                });
                let luma = rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
                for c in 0..3 {
                    let adjusted = ((luma + (rgb[c] - luma) * (settings.saturation + 1.0))
                        .clamp(0.0, 1.0)
                        * alpha as f32)
                        .round() as u8;
                    pixels[i + c] = mix(pixels[i + c], adjusted, coverage);
                }
            }
        }
    }
    Ok(())
}

pub struct Surface {
    pub region: Rect,
    pub pixels: Vec<u8>,
}

impl Surface {
    pub fn read(layer: &Layer, region: Rect) -> Self {
        let width = (region.right - region.left) as usize;
        let mut pixels = vec![0; width * (region.bottom - region.top) as usize * 4];
        for (&(tx, ty), tile) in &layer.tiles {
            let tile_bounds = Rect {
                left: tx * TILE_SIZE,
                top: ty * TILE_SIZE,
                right: (tx + 1) * TILE_SIZE,
                bottom: (ty + 1) * TILE_SIZE,
            };
            let Some(area) = region.intersect(tile_bounds) else {
                continue;
            };
            for y in area.top..area.bottom {
                let target =
                    ((y - region.top) as usize * width + (area.left - region.left) as usize) * 4;
                let source = (((y % TILE_SIZE) * TILE_SIZE + area.left % TILE_SIZE) * 4) as usize;
                let len = (area.right - area.left) as usize * 4;
                pixels[target..target + len].copy_from_slice(&tile[source..source + len]);
            }
        }
        Self { region, pixels }
    }

    pub fn write(&self, layer: &mut Layer, area: Rect, selection: Option<&Selection>) {
        let width = (self.region.right - self.region.left) as usize;
        for ty in area.top / TILE_SIZE..=(area.bottom - 1) / TILE_SIZE {
            for tx in area.left / TILE_SIZE..=(area.right - 1) / TILE_SIZE {
                if layer.alpha_locked && !layer.tiles.contains_key(&(tx, ty)) {
                    continue;
                }
                let region = area
                    .intersect(Rect {
                        left: tx * TILE_SIZE,
                        top: ty * TILE_SIZE,
                        right: (tx + 1) * TILE_SIZE,
                        bottom: (ty + 1) * TILE_SIZE,
                    })
                    .unwrap();
                let mut tile = layer
                    .tiles
                    .get(&(tx, ty))
                    .map_or_else(|| vec![0; TILE_BYTES], |old| old.as_ref().clone());
                for y in region.top..region.bottom {
                    let source = ((y - self.region.top) as usize * width
                        + (region.left - self.region.left) as usize)
                        * 4;
                    let target =
                        (((y % TILE_SIZE) * TILE_SIZE + region.left % TILE_SIZE) * 4) as usize;
                    let len = (region.right - region.left) as usize * 4;
                    let mask = selection.and_then(|selection| selection.row(y));
                    if layer.alpha_locked || mask.is_some() {
                        for (index, (old, new)) in tile[target..target + len]
                            .as_chunks_mut::<4>()
                            .0
                            .iter_mut()
                            .zip(self.pixels[source..source + len].as_chunks::<4>().0)
                            .enumerate()
                        {
                            let coverage = mask.map_or(255, |row| {
                                row[(region.left - selection.unwrap().bounds().left) as usize
                                    + index]
                            });
                            if coverage == 0 {
                                continue;
                            }
                            let alpha = u32::from(old[3]);
                            let new_alpha = u32::from(new[3]);
                            for channel in 0..4 {
                                let adjusted = if layer.alpha_locked {
                                    if channel == 3 {
                                        old[3]
                                    } else {
                                        (u32::from(new[channel]) * alpha + new_alpha / 2)
                                            .checked_div(new_alpha)
                                            .map(|value| value.min(alpha) as u8)
                                            .unwrap_or(old[channel])
                                    }
                                } else {
                                    new[channel]
                                };
                                old[channel] = mix(old[channel], adjusted, coverage);
                            }
                        }
                    } else {
                        tile[target..target + len]
                            .copy_from_slice(&self.pixels[source..source + len]);
                    }
                }
                if tile.as_chunks::<4>().0.iter().any(|p| p[3] != 0) {
                    layer.tiles.insert((tx, ty), Arc::new(tile));
                } else {
                    layer.tiles.remove(&(tx, ty));
                }
            }
        }
    }
}

pub fn fill(
    layer: &mut Layer,
    region: Rect,
    selection: Option<&Selection>,
    x: u32,
    y: u32,
    color: [u8; 4],
    tolerance: u8,
) -> Result<(), String> {
    if !region.contains(x, y) || selection.is_some_and(|selection| selection.coverage(x, y) == 0) {
        return Err("填充位置不在选区内".into());
    }
    if layer.alpha_locked
        && layer
            .tiles
            .get(&(x / TILE_SIZE, y / TILE_SIZE))
            .is_none_or(|tile| {
                tile[((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4 + 3) as usize] == 0
            })
    {
        return Ok(());
    }
    let mut surface = Surface::read(layer, region);
    let width = (region.right - region.left) as usize;
    let height = (region.bottom - region.top) as usize;
    let seed = (y - region.top) as usize * width + (x - region.left) as usize;
    let target: [u8; 4] = surface.pixels[seed * 4..seed * 4 + 4].try_into().unwrap();
    let alpha = u32::from(color[3]);
    let replacement = [
        ((u32::from(color[0]) * alpha + 127) / 255) as u8,
        ((u32::from(color[1]) * alpha + 127) / 255) as u8,
        ((u32::from(color[2]) * alpha + 127) / 255) as u8,
        color[3],
    ];
    if tolerance == 0 && target == replacement {
        return Ok(());
    }
    let mut visited = vec![false; width * height];
    let mut stack = vec![seed as u32];
    let matches = |pixels: &[u8], i: usize| {
        selection.is_none_or(|selection| {
            selection.coverage(
                region.left + (i % width) as u32,
                region.top + (i / width) as u32,
            ) != 0
        }) && (!layer.alpha_locked || pixels[i * 4 + 3] != 0)
            && (0..4).all(|c| pixels[i * 4 + c].abs_diff(target[c]) <= tolerance)
    };
    while let Some(seed) = stack.pop() {
        let seed = seed as usize;
        if visited[seed] || !matches(&surface.pixels, seed) {
            continue;
        }
        let row = seed / width;
        let mut left = seed % width;
        while left > 0
            && !visited[row * width + left - 1]
            && matches(&surface.pixels, row * width + left - 1)
        {
            left -= 1;
        }
        let mut right = seed % width;
        while right + 1 < width
            && !visited[row * width + right + 1]
            && matches(&surface.pixels, row * width + right + 1)
        {
            right += 1;
        }
        for col in left..=right {
            let i = row * width + col;
            visited[i] = true;
            let pixel = &mut surface.pixels[i * 4..i * 4 + 4];
            if layer.alpha_locked {
                crate::blending::paint_preserving_alpha(
                    pixel,
                    [color[0], color[1], color[2]],
                    alpha,
                );
            } else {
                pixel.copy_from_slice(&replacement);
            }
        }
        for next_row in [row.checked_sub(1), (row + 1 < height).then_some(row + 1)]
            .into_iter()
            .flatten()
        {
            let mut run = false;
            for col in left..=right {
                let i = next_row * width + col;
                let eligible = !visited[i] && matches(&surface.pixels, i);
                if eligible && !run {
                    stack.push(i as u32);
                }
                run = eligible;
            }
        }
    }
    surface.write(layer, region, selection);
    Ok(())
}

pub fn blur(
    layer: &mut Layer,
    bounds: Rect,
    region: Rect,
    selection: Option<&Selection>,
    sigma: f32,
) -> Result<(), String> {
    if !sigma.is_finite() || !(0.0..=32.0).contains(&sigma) {
        return Err("模糊半径必须在 0 到 32 之间".into());
    }
    if sigma == 0.0 || layer.tiles.is_empty() {
        return Ok(());
    }
    let ideal = (4.0 * sigma * sigma + 1.0).sqrt();
    let lower = (ideal.floor() as u32).max(1) | 1;
    let lower = if lower as f32 > ideal {
        lower.saturating_sub(2).max(1)
    } else {
        lower
    };
    let upper = lower + 2;
    let count = ((12.0 * sigma * sigma - 3.0 * (lower * lower) as f32 - 12.0 * lower as f32 - 9.0)
        / (-4.0 * lower as f32 - 4.0))
        .round()
        .clamp(0.0, 3.0) as usize;
    let radii: [usize; 3] =
        std::array::from_fn(|i| (if i < count { lower } else { upper }) as usize / 2);
    let halo = radii.iter().sum::<usize>() as u32;
    let occupied = Rect {
        left: layer
            .tiles
            .keys()
            .map(|k| k.0 * TILE_SIZE)
            .min()
            .unwrap()
            .saturating_sub(halo),
        top: layer
            .tiles
            .keys()
            .map(|k| k.1 * TILE_SIZE)
            .min()
            .unwrap()
            .saturating_sub(halo),
        right: layer
            .tiles
            .keys()
            .map(|k| (k.0 + 1) * TILE_SIZE)
            .max()
            .unwrap()
            .saturating_add(halo),
        bottom: layer
            .tiles
            .keys()
            .map(|k| (k.1 + 1) * TILE_SIZE)
            .max()
            .unwrap()
            .saturating_add(halo),
    };
    let Some(output) = occupied.intersect(region).and_then(|r| r.intersect(bounds)) else {
        return Ok(());
    };
    let region = Rect {
        left: output.left.saturating_sub(halo),
        top: output.top.saturating_sub(halo),
        right: output.right.saturating_add(halo),
        bottom: output.bottom.saturating_add(halo),
    }
    .intersect(bounds)
    .unwrap();
    let mut surface = Surface::read(layer, region);
    let width = (region.right - region.left) as usize;
    let height = (region.bottom - region.top) as usize;
    let mut scratch = vec![0; surface.pixels.len()];
    for radius in radii {
        box_pass(&surface.pixels, &mut scratch, width, height, radius, false);
        box_pass(&scratch, &mut surface.pixels, width, height, radius, true);
    }
    surface.write(layer, output, selection);
    Ok(())
}

fn box_pass(
    input: &[u8],
    output: &mut [u8],
    width: usize,
    height: usize,
    radius: usize,
    vertical: bool,
) {
    let (lines, length, step) = if vertical {
        (width, height, width)
    } else {
        (height, width, 1)
    };
    let divisor = (radius * 2 + 1) as u32;
    for line in 0..lines {
        let start = if vertical { line } else { line * width };
        let mut sum = [0_u32; 4];
        for p in 0..=radius.min(length - 1) {
            for c in 0..4 {
                sum[c] += u32::from(input[(start + p * step) * 4 + c]);
            }
        }
        for p in 0..length {
            for c in 0..4 {
                output[(start + p * step) * 4 + c] = ((sum[c] + divisor / 2) / divisor) as u8;
                if p >= radius {
                    sum[c] -= u32::from(input[(start + (p - radius) * step) * 4 + c]);
                }
                if p + radius + 1 < length {
                    sum[c] += u32::from(input[(start + (p + radius + 1) * step) * 4 + c]);
                }
            }
        }
    }
}
