use crate::model::*;
use std::{collections::BTreeSet, sync::Arc};

pub fn stamp(
    doc: &mut Document,
    selection: Option<Rect>,
    brush: Brush,
    point: Sample,
    dirty: &mut BTreeSet<TileKey>,
    remaining: &mut usize,
) -> Result<(), String> {
    if brush.tip == BrushTip::Round && brush.aspect == 1.0 && brush.grain == 0.0 {
        stamp_impl::<true>(doc, selection, brush, point, dirty, remaining)
    } else {
        stamp_impl::<false>(doc, selection, brush, point, dirty, remaining)
    }
}

fn stamp_impl<const SIMPLE: bool>(
    doc: &mut Document,
    selection: Option<Rect>,
    brush: Brush,
    point: Sample,
    dirty: &mut BTreeSet<TileKey>,
    remaining: &mut usize,
) -> Result<(), String> {
    let radius = (brush.size * point.pressure.clamp(0.05, 1.0) * 0.5).max(0.5);
    let extent = if brush.tip == BrushTip::Flat {
        radius * std::f32::consts::SQRT_2
    } else {
        radius
    };
    let (sin, cos) = brush.angle.to_radians().sin_cos();
    let circular = brush.tip == BrushTip::Round && brush.aspect == 1.0;
    let inverse_aspect = 1.0 / brush.aspect;
    let region = selection.unwrap_or(doc.bounds());
    let left = ((point.x - extent).floor().max(0.0) as u32).max(region.left);
    let top = ((point.y - extent).floor().max(0.0) as u32).max(region.top);
    let right = ((point.x + extent).ceil().max(0.0) as u32).min(region.right);
    let bottom = ((point.y + extent).ceil().max(0.0) as u32).min(region.bottom);
    if left >= right || top >= bottom {
        return Ok(());
    }
    let layer = doc.active_mut();
    let inner = radius * brush.hardness;
    let feather = (radius - inner).max(0.75);
    for ty in top / TILE_SIZE..=(bottom - 1) / TILE_SIZE {
        for tx in left / TILE_SIZE..=(right - 1) / TILE_SIZE {
            let key = (tx, ty);
            if brush.eraser && !layer.tiles.contains_key(&key) {
                continue;
            }
            if !layer.tiles.contains_key(&key) {
                if *remaining == 0 {
                    return Err("当前工程已达到像素内存上限".into());
                }
                *remaining -= 1;
            }
            let pixels = Arc::make_mut(
                layer
                    .tiles
                    .entry(key)
                    .or_insert_with(|| Arc::new(vec![0; TILE_BYTES])),
            );
            let mut changed = false;
            for y in top.max(ty * TILE_SIZE)..bottom.min((ty + 1) * TILE_SIZE) {
                for x in left.max(tx * TILE_SIZE)..right.min((tx + 1) * TILE_SIZE) {
                    let dx = x as f32 + 0.5 - point.x;
                    let dy = y as f32 + 0.5 - point.y;
                    let squared = if SIMPLE || circular {
                        dx * dx + dy * dy
                    } else {
                        let rx = dx * cos + dy * sin;
                        let ry = (-dx * sin + dy * cos) * inverse_aspect;
                        if brush.tip == BrushTip::Flat {
                            rx.abs().max(ry.abs()).powi(2)
                        } else {
                            rx * rx + ry * ry
                        }
                    };
                    if squared >= radius * radius {
                        continue;
                    }
                    let mut coverage = ((radius - squared.sqrt()) / feather).clamp(0.0, 1.0);
                    if !SIMPLE && brush.grain > 0.0 {
                        let mut hash = x.wrapping_mul(374_761_393) ^ y.wrapping_mul(668_265_263);
                        hash = (hash ^ (hash >> 13)).wrapping_mul(1_274_126_177);
                        let noise = ((hash ^ (hash >> 16)) & 65535) as f32 / 65535.0;
                        coverage *= (1.0 - brush.grain) + brush.grain * noise.powi(3);
                    }
                    let alpha = (coverage * brush.opacity * 255.0).round() as u32;
                    if alpha == 0 {
                        continue;
                    }
                    let offset = (((y % TILE_SIZE) * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                    let pixel = &mut pixels[offset..offset + 4];
                    let old = [pixel[0], pixel[1], pixel[2], pixel[3]];
                    let inverse = 255 - alpha;
                    if brush.eraser {
                        for value in pixel.iter_mut() {
                            *value = (u32::from(*value) * inverse / 255) as u8;
                        }
                    } else {
                        for (channel, value) in pixel.iter_mut().enumerate().take(3) {
                            *value = ((u32::from(brush.color[channel]) * alpha
                                + u32::from(*value) * inverse
                                + 127)
                                / 255) as u8;
                        }
                        pixel[3] = (alpha + (u32::from(pixel[3]) * inverse + 127) / 255) as u8;
                    }
                    changed |= pixel != old;
                }
            }
            if changed {
                dirty.insert(key);
            }
        }
    }
    Ok(())
}

pub fn composite_tile(doc: &Document, key: TileKey) -> Vec<u8> {
    composite_tile_background(doc, key, false)
}

pub fn composite_tile_background(doc: &Document, key: TileKey, transparent: bool) -> Vec<u8> {
    let opaque = !transparent
        && doc.layers.iter().all(|layer| {
            !layer.visible || layer.opacity == 0.0 || layer.blend == BlendMode::Normal
        });
    let mut result = vec![if opaque { 255 } else { 0 }; TILE_BYTES];
    for layer in &doc.layers {
        if !layer.visible || layer.opacity == 0.0 {
            continue;
        }
        let Some(source) = layer.tiles.get(&key) else {
            continue;
        };
        let opacity = (layer.opacity * 255.0).round() as u32;
        crate::blending::composite(&mut result, source, opacity, layer.blend, opaque);
    }
    if !transparent && !opaque {
        for pixel in result.as_chunks_mut::<4>().0 {
            let white = 255 - pixel[3];
            for channel in &mut pixel[..3] {
                *channel = channel.saturating_add(white);
            }
            pixel[3] = 255;
        }
    }
    result
}
