use crate::model::*;
use crate::selection::{mix, Selection};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct Tone {
    pub brightness: f32,
    pub contrast: f32,
    pub saturation: f32,
}

pub struct ToneKernel {
    lut: [f32; 256],
    saturation: f32,
}

impl ToneKernel {
    pub fn new(settings: Tone) -> Result<Self, String> {
        if [settings.brightness, settings.contrast, settings.saturation]
            .iter()
            .any(|v| !v.is_finite() || !(-1.0..=1.0).contains(v))
        {
            return Err("色彩参数必须在 -1 到 1 之间".into());
        }
        let gain = (1.0 + settings.contrast) / (1.0 - settings.contrast).max(0.001);
        Ok(Self {
            lut: std::array::from_fn(|i| {
                (((i as f32 / 255.0 - 0.5) * gain + 0.5) + settings.brightness).clamp(0.0, 1.0)
            }),
            saturation: settings.saturation,
        })
    }

    pub fn mapped(&self, pixel: [u8; 4], coverage: u8) -> [u8; 4] {
        let alpha = u32::from(pixel[3]);
        if alpha == 0 || coverage == 0 {
            return pixel;
        }
        let rgb: [f32; 3] = std::array::from_fn(|c| {
            self.lut[((u32::from(pixel[c]) * 255 + alpha / 2) / alpha).min(255) as usize]
        });
        let luma = rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
        let mut result = pixel;
        for c in 0..3 {
            let adjusted = ((luma + (rgb[c] - luma) * (self.saturation + 1.0)).clamp(0.0, 1.0)
                * alpha as f32)
                .round() as u8;
            result[c] = mix(pixel[c], adjusted, coverage);
        }
        result
    }
}

pub fn tone(
    layer: &mut Layer,
    region: Rect,
    selection: Option<&Selection>,
    settings: Tone,
) -> Result<(), String> {
    let raster = layer.raster_mut()?;
    if raster.is_indexed() {
        return Err("索引色调整尚未支持，请先转换为 RGBA".into());
    }
    let kernel = ToneKernel::new(settings)?;
    for (&(tx, ty), tile) in raster.tiles_mut() {
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
                let mapped = kernel.mapped(pixels[i..i + 4].try_into().unwrap(), coverage);
                pixels[i..i + 4].copy_from_slice(&mapped);
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
    pub fn read(layer: &Layer, region: Rect) -> Result<Self, String> {
        let raster = layer.raster()?;
        if raster.is_indexed() {
            return Err("索引色调整尚未支持，请先转换为 RGBA".into());
        }
        let width = (region.right - region.left) as usize;
        let mut pixels = vec![0; width * (region.bottom - region.top) as usize * 4];
        for (&(tx, ty), tile) in raster.tiles() {
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
        Ok(Self { region, pixels })
    }

    pub fn write(
        &self,
        layer: &mut Layer,
        area: Rect,
        selection: Option<&Selection>,
    ) -> Result<(), String> {
        let alpha_locked = layer.alpha_locked;
        let raster = layer.raster_mut()?;
        if raster.is_indexed() {
            return Err("索引色调整尚未支持，请先转换为 RGBA".into());
        }
        let width = (self.region.right - self.region.left) as usize;
        for ty in area.top / TILE_SIZE..=(area.bottom - 1) / TILE_SIZE {
            for tx in area.left / TILE_SIZE..=(area.right - 1) / TILE_SIZE {
                if alpha_locked && !raster.tiles().contains_key(&(tx, ty)) {
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
                let mut tile = raster
                    .tiles()
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
                    if alpha_locked || mask.is_some() {
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
                                let adjusted = if alpha_locked {
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
                    raster.tiles_mut().insert((tx, ty), Arc::new(tile));
                } else {
                    raster.tiles_mut().remove(&(tx, ty));
                }
            }
        }
        Ok(())
    }
}

pub fn blur(
    layer: &mut Layer,
    bounds: Rect,
    region: Rect,
    selection: Option<&Selection>,
    sigma: f32,
) -> Result<(), String> {
    let raster = layer.raster()?;
    if raster.is_indexed() {
        return Err("索引色调整尚未支持，请先转换为 RGBA".into());
    }
    if !sigma.is_finite() || !(0.0..=32.0).contains(&sigma) {
        return Err("模糊半径必须在 0 到 32 之间".into());
    }
    if sigma == 0.0 || raster.tiles().is_empty() {
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
        left: raster
            .tiles()
            .keys()
            .map(|k| k.0 * TILE_SIZE)
            .min()
            .unwrap()
            .saturating_sub(halo),
        top: raster
            .tiles()
            .keys()
            .map(|k| k.1 * TILE_SIZE)
            .min()
            .unwrap()
            .saturating_sub(halo),
        right: raster
            .tiles()
            .keys()
            .map(|k| (k.0 + 1) * TILE_SIZE)
            .max()
            .unwrap()
            .saturating_add(halo),
        bottom: raster
            .tiles()
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
    let mut surface = Surface::read(layer, region)?;
    let width = (region.right - region.left) as usize;
    let height = (region.bottom - region.top) as usize;
    let mut scratch = vec![0; surface.pixels.len()];
    for radius in radii {
        box_pass(&surface.pixels, &mut scratch, width, height, radius, false);
        box_pass(&scratch, &mut surface.pixels, width, height, radius, true);
    }
    surface.write(layer, output, selection)
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
