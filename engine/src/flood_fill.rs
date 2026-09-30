use crate::{
    color_selection::{select_clipped, ColorSelection},
    model::*,
    selection::{mix, Selection},
};
use std::{
    collections::{BTreeMap, BTreeSet, HashSet},
    sync::Arc,
};

pub fn prepare(
    document: &Document,
    selection: Option<&Selection>,
    settings: ColorSelection,
    color: [u8; 4],
) -> Result<(BTreeMap<TileKey, Tile>, Vec<TileKey>), String> {
    let index = crate::groups::check_editable(document, document.active, true)?;
    let layer = &document.layers[index];
    let raster = layer.raster()?;
    if raster.is_indexed() {
        return Err("索引色填充请使用索引色填充工具".into());
    }
    if !document.bounds().contains(settings.x, settings.y) {
        return Err("填充位置超出画布".into());
    }
    let unchanged = || (raster.tiles().clone(), Vec::new());
    if selection.is_some_and(Selection::is_empty) {
        return Ok(unchanged());
    }
    if selection.is_some_and(|selection| selection.coverage(settings.x, settings.y) == 0) {
        return Err("填充位置不在选区内".into());
    }
    if layer.alpha_locked
        && (color[3] == 0
            || read(
                raster
                    .tiles()
                    .get(&(settings.x / TILE_SIZE, settings.y / TILE_SIZE)),
                settings.x,
                settings.y,
            )[3] == 0)
    {
        return Ok(unchanged());
    }
    let mask = select_clipped(document, settings, selection, layer.alpha_locked)?;
    if mask.is_empty() {
        return Ok(unchanged());
    }
    let bounds = mask.bounds();
    let source_alpha = u32::from(color[3]);
    let replacement = [
        ((u32::from(color[0]) * source_alpha + 127) / 255) as u8,
        ((u32::from(color[1]) * source_alpha + 127) / 255) as u8,
        ((u32::from(color[2]) * source_alpha + 127) / 255) as u8,
        color[3],
    ];
    let edit = |mut pixel: [u8; 4], x, y| {
        let coverage = mask.coverage(x, y);
        if coverage == 0 {
            return pixel;
        }
        let adjusted = if layer.alpha_locked {
            let mut adjusted = pixel;
            crate::blending::paint_preserving_alpha(
                &mut adjusted,
                color[..3].try_into().unwrap(),
                source_alpha,
            );
            adjusted
        } else {
            replacement
        };
        for (value, new) in pixel.iter_mut().zip(adjusted) {
            *value = mix(*value, new, coverage);
        }
        pixel
    };
    let mut changed = BTreeSet::new();
    let mut added = 0;
    let mut retained = HashSet::new();
    for ty in bounds.top / TILE_SIZE..bounds.bottom.div_ceil(TILE_SIZE) {
        for tx in bounds.left / TILE_SIZE..bounds.right.div_ceil(TILE_SIZE) {
            let key = (tx, ty);
            let old = raster.tiles().get(&key);
            if layer.alpha_locked && old.is_none() {
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
        return Err("填色会超出撤销内存限制".into());
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
        if tile.iter().all(|&value| value == 0) {
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
