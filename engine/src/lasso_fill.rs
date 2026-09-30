use crate::{
    blending::paint_preserving_alpha,
    model::*,
    selection::{Selection, SelectionPoint},
};
use std::{
    collections::{BTreeMap, BTreeSet, HashSet},
    sync::Arc,
};

pub fn prepare(
    document: &Document,
    selection: Option<&Selection>,
    points: Vec<SelectionPoint>,
    color: [u8; 3],
    opacity: f32,
    eraser: bool,
) -> Result<(BTreeMap<TileKey, Tile>, Vec<TileKey>), String> {
    let index = crate::groups::check_editable(document, document.active, true)?;
    let layer = &document.layers[index];
    let raster = layer.raster()?;
    if raster.is_indexed() {
        return Err("索引色套索填色尚未支持，请先转换为 RGBA".into());
    }
    if layer.alpha_locked && eraser {
        return Err("请先解除透明度锁定".into());
    }
    if !opacity.is_finite()
        || !(0.0..=1.0).contains(&opacity)
        || points.len() > MAX_SELECTION_POINTS
        || points.iter().any(|point| {
            !point.x.is_finite()
                || !point.y.is_finite()
                || point.x.abs().max(point.y.abs()) > MAX_DIMENSION as f32 * 2.0
        })
    {
        return Err("套索填色参数无效或路径过长".into());
    }
    let unchanged = || (raster.tiles().clone(), Vec::new());
    if opacity == 0.0 || points.len() < 3 || selection.is_some_and(Selection::is_empty) {
        return Ok(unchanged());
    }
    let extent = |horizontal: bool, minimum: bool| {
        points
            .iter()
            .map(|point| if horizontal { point.x } else { point.y })
            .reduce(if minimum { f32::min } else { f32::max })
            .unwrap()
    };
    let Some(bounds) = (Rect {
        left: extent(true, true).floor().max(0.0) as u32,
        top: extent(false, true).floor().max(0.0) as u32,
        right: extent(true, false).ceil().max(0.0) as u32,
        bottom: extent(false, false).ceil().max(0.0) as u32,
    })
    .intersect(selection.map_or(document.bounds(), Selection::bounds)) else {
        return Ok(unchanged());
    };
    let mask = Selection::lasso_mask(points, bounds);
    if mask.is_empty() {
        return Ok(unchanged());
    }
    let bounds = mask.bounds();
    let edit = |pixel, x, y| {
        let selected = selection.map_or(255, |selection| selection.coverage(x, y));
        let coverage = (u32::from(mask.coverage(x, y)) * u32::from(selected) + 127) / 255;
        let alpha = (opacity * coverage as f32).round() as u32;
        paint(pixel, color, alpha, eraser, layer.alpha_locked)
    };
    let mut changed = BTreeSet::new();
    let mut added = 0;
    let mut retained = HashSet::new();
    for ty in bounds.top / TILE_SIZE..bounds.bottom.div_ceil(TILE_SIZE) {
        for tx in bounds.left / TILE_SIZE..bounds.right.div_ceil(TILE_SIZE) {
            let key = (tx, ty);
            let old = raster.tiles().get(&key);
            if (eraser || layer.alpha_locked) && old.is_none() {
                continue;
            }
            let region = tile_bounds(bounds, key);
            let modifies = (region.top..region.bottom).any(|y| {
                (region.left..region.right).any(|x| {
                    let pixel = read(old, x, y);
                    edit(pixel, x, y) != pixel
                })
            });
            if modifies {
                changed.insert(key);
                if let Some(old) = old {
                    retained.insert(Arc::as_ptr(old));
                } else {
                    added += 1;
                }
            }
        }
    }
    if document.tile_count() + added > MAX_DOCUMENT_BYTES / TILE_BYTES {
        return Err("工程像素超过内存限制".into());
    }
    for other in &document.layers {
        let Some(raster) = other.raster_opt() else {
            continue;
        };
        for (key, tile) in raster.tiles() {
            if other.id != layer.id || !changed.contains(key) {
                retained.remove(&Arc::as_ptr(tile));
            }
        }
    }
    if retained.len() * TILE_BYTES > MAX_HISTORY_BYTES {
        return Err("套索填色会超出撤销内存限制".into());
    }
    let mut output = raster.tiles().clone();
    for &key in &changed {
        let old = raster.tiles().get(&key);
        let mut tile = old.map_or_else(|| vec![0; TILE_BYTES], |tile| tile.as_ref().clone());
        let region = tile_bounds(bounds, key);
        for y in region.top..region.bottom {
            for x in region.left..region.right {
                let offset = offset(x, y);
                let result = edit(tile[offset..offset + 4].try_into().unwrap(), x, y);
                tile[offset..offset + 4].copy_from_slice(&result);
            }
        }
        if eraser && tile.iter().all(|&value| value == 0) {
            output.remove(&key);
        } else {
            output.insert(key, Arc::new(tile));
        }
    }
    Ok((output, changed.into_iter().collect()))
}

fn tile_bounds(bounds: Rect, (tx, ty): TileKey) -> Rect {
    Rect {
        left: bounds.left.max(tx * TILE_SIZE),
        top: bounds.top.max(ty * TILE_SIZE),
        right: bounds.right.min((tx + 1) * TILE_SIZE),
        bottom: bounds.bottom.min((ty + 1) * TILE_SIZE),
    }
}

fn offset(x: u32, y: u32) -> usize {
    ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize
}

fn read(tile: Option<&Tile>, x: u32, y: u32) -> [u8; 4] {
    tile.map_or([0; 4], |tile| {
        let offset = offset(x, y);
        tile[offset..offset + 4].try_into().unwrap()
    })
}

fn paint(
    mut pixel: [u8; 4],
    color: [u8; 3],
    alpha: u32,
    eraser: bool,
    alpha_locked: bool,
) -> [u8; 4] {
    if alpha == 0 || (alpha_locked && pixel[3] == 0) {
        return pixel;
    }
    let inverse = 255 - alpha;
    if eraser {
        for value in &mut pixel {
            *value = ((u32::from(*value) * inverse + 127) / 255) as u8;
        }
    } else if alpha_locked {
        paint_preserving_alpha(&mut pixel, color, alpha);
    } else {
        for (channel, value) in pixel.iter_mut().enumerate().take(3) {
            *value = ((u32::from(color[channel]) * alpha + u32::from(*value) * inverse + 127) / 255)
                as u8;
        }
        pixel[3] = (alpha + (u32::from(pixel[3]) * inverse + 127) / 255) as u8;
    }
    pixel
}
