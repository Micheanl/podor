use crate::{model::*, selection::Selection};
use serde::Deserialize;
use std::{
    collections::{BTreeMap, HashSet},
    sync::Arc,
};

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GradientShape {
    Linear,
    Radial,
}

#[derive(Clone, Copy, Deserialize)]
pub struct Gradient {
    pub start: [f64; 2],
    pub end: [f64; 2],
    pub from: [u8; 4],
    pub to: [u8; 4],
    pub opacity: f64,
    pub shape: GradientShape,
}

pub fn prepare(
    doc: &Document,
    selection: Option<&Selection>,
    value: Gradient,
) -> Result<BTreeMap<TileKey, Tile>, String> {
    let layer = doc.layers.iter().find(|l| l.id == doc.active).unwrap();
    if layer.locked {
        return Err("图层已锁定，请先解锁".into());
    }
    if !layer.visible {
        return Err("请先显示当前图层".into());
    }
    let dx = value.end[0] - value.start[0];
    let dy = value.end[1] - value.start[1];
    let length_squared = dx * dx + dy * dy;
    if value
        .start
        .iter()
        .chain(value.end.iter())
        .any(|v| !v.is_finite() || v.abs() > MAX_GRADIENT_COORDINATE)
        || !value.opacity.is_finite()
        || !(0.0..=1.0).contains(&value.opacity)
        || length_squared < 1e-6
    {
        return Err("渐变参数无效".into());
    }
    let mut output = layer.tiles.clone();
    if value.opacity == 0.0 || (value.from[3] == 0 && value.to[3] == 0) {
        return Ok(output);
    }
    let region = selection.map_or(doc.bounds(), Selection::bounds);
    let budget = MAX_DOCUMENT_BYTES / TILE_BYTES - (doc.tile_count() - layer.tiles.len());
    let mut retained = HashSet::new();
    let radius = length_squared.sqrt();
    for ty in region.top / TILE_SIZE..region.bottom.div_ceil(TILE_SIZE) {
        for tx in region.left / TILE_SIZE..region.right.div_ceil(TILE_SIZE) {
            let old = layer.tiles.get(&(tx, ty));
            if layer.alpha_locked && old.is_none() {
                continue;
            }
            let mut edited: Option<Vec<u8>> = None;
            for y in region.top.max(ty * TILE_SIZE)..region.bottom.min((ty + 1) * TILE_SIZE) {
                for x in region.left.max(tx * TILE_SIZE)..region.right.min((tx + 1) * TILE_SIZE) {
                    let coverage = selection.map_or(255, |s| s.coverage(x, y));
                    if coverage == 0 {
                        continue;
                    }
                    let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                    let pixel: [u8; 4] =
                        old.map_or([0; 4], |tile| tile[i..i + 4].try_into().unwrap());
                    if layer.alpha_locked && pixel[3] == 0 {
                        continue;
                    }
                    let px = f64::from(x) + 0.5 - value.start[0];
                    let py = f64::from(y) + 0.5 - value.start[1];
                    let t = match value.shape {
                        GradientShape::Linear => (px * dx + py * dy) / length_squared,
                        GradientShape::Radial => px.hypot(py) / radius,
                    }
                    .clamp(0.0, 1.0);
                    let color: [f64; 4] = std::array::from_fn(|c| {
                        f64::from(value.from[c]) * (1.0 - t) + f64::from(value.to[c]) * t
                    });
                    let alpha = color[3] / 255.0 * value.opacity * f64::from(coverage) / 255.0;
                    let a = if layer.alpha_locked {
                        f64::from(pixel[3])
                    } else {
                        255.0 * alpha + f64::from(pixel[3]) * (1.0 - alpha)
                    };
                    let out_alpha = a.round() as u8;
                    let source_alpha = alpha
                        * if layer.alpha_locked {
                            f64::from(pixel[3]) / 255.0
                        } else {
                            1.0
                        };
                    let result: [u8; 4] = std::array::from_fn(|c| {
                        if c == 3 {
                            out_alpha
                        } else {
                            (color[c] * source_alpha + f64::from(pixel[c]) * (1.0 - alpha))
                                .round()
                                .clamp(0.0, f64::from(out_alpha)) as u8
                        }
                    });
                    if result != pixel {
                        let tile = edited.get_or_insert_with(|| {
                            old.map_or_else(|| vec![0; TILE_BYTES], |t| t.as_ref().clone())
                        });
                        tile[i..i + 4].copy_from_slice(&result);
                    }
                }
            }
            if let Some(tile) = edited {
                if old.is_none() && output.len() >= budget {
                    return Err("工程像素超过内存限制".into());
                }
                if let Some(old) = old {
                    retained.insert(Arc::as_ptr(old));
                }
                output.insert((tx, ty), Arc::new(tile));
            }
        }
    }
    for tile in doc
        .layers
        .iter()
        .filter(|other| other.id != layer.id)
        .flat_map(|l| l.tiles.values())
        .chain(output.values())
    {
        retained.remove(&Arc::as_ptr(tile));
    }
    if retained.len() * TILE_BYTES > MAX_HISTORY_BYTES {
        return Err("渐变会超出撤销内存限制".into());
    }
    Ok(output)
}

pub fn selection_frame(selection: Option<&Selection>) -> Vec<u8> {
    let mut bytes = vec![0; 8];
    bytes[..4].copy_from_slice(&TILE_SIZE.to_le_bytes());
    let Some(selection) = selection else {
        return bytes;
    };
    let bounds = selection.bounds();
    if selection.row(bounds.top).is_none() {
        return bytes;
    }
    let mut count = 0u32;
    for ty in bounds.top / TILE_SIZE..bounds.bottom.div_ceil(TILE_SIZE) {
        for tx in bounds.left / TILE_SIZE..bounds.right.div_ceil(TILE_SIZE) {
            let mut tile = vec![0u8; TILE_BYTES];
            let mut nonzero = false;
            for y in bounds.top.max(ty * TILE_SIZE)..bounds.bottom.min((ty + 1) * TILE_SIZE) {
                for x in bounds.left.max(tx * TILE_SIZE)..bounds.right.min((tx + 1) * TILE_SIZE) {
                    let alpha = selection.coverage(x, y);
                    if alpha != 0 {
                        nonzero = true;
                        let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                        tile[i..i + 4].fill(alpha);
                    }
                }
            }
            if nonzero {
                bytes.extend(tx.to_le_bytes());
                bytes.extend(ty.to_le_bytes());
                bytes.extend(tile);
                count += 1;
            }
        }
    }
    bytes[4..8].copy_from_slice(&count.to_le_bytes());
    bytes
}
