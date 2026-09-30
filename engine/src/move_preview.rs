use crate::{model::*, selection::Selection};
use std::{collections::BTreeMap, sync::Arc};

pub fn frame(
    doc: &Document,
    selection: Option<&Selection>,
    mask_editing: bool,
) -> Result<Vec<u8>, String> {
    if doc.layers.iter().any(|layer| layer.masks.len() > 1) {
        return Err("多蒙版移动请使用完整合成预览".into());
    }
    if doc
        .layers
        .iter()
        .any(|layer| layer.is_group() || layer.is_adjustment() || layer.is_vector())
    {
        return Err("图层组或调整图层移动请使用完整合成预览".into());
    }
    if doc.palette.is_some()
        && !mask_editing
        && selection.is_some_and(|selection| !selection.is_empty())
    {
        return Err("索引色选区移动预览尚未支持，请先取消选区或转换为 RGBA".into());
    }
    let active = doc
        .layers
        .iter()
        .find(|layer| layer.id == doc.active)
        .unwrap();
    let mut bytes = 20
        + doc
            .layers
            .iter()
            .filter(|layer| layer.visible && layer.opacity > 0.0)
            .map(|layer| 8 + layer.raster_keys().count() * (8 + TILE_BYTES))
            .sum::<usize>();
    if !mask_editing && selection.is_some_and(|selection| !selection.is_empty()) {
        bytes += 8 + active.raster()?.tiles().len() * (8 + TILE_BYTES);
    }
    if let Some(mask) = active.first_mask() {
        let count = mask.tiles.len();
        if mask_editing && selection.is_some_and(Selection::is_empty) {
            bytes += 36 + count * (8 + TILE_BYTES);
        } else if mask_editing && selection.is_some() {
            let region = selection.unwrap().bounds();
            let selected_tiles = (region.right.div_ceil(TILE_SIZE) - region.left / TILE_SIZE)
                as usize
                * (region.bottom.div_ceil(TILE_SIZE) - region.top / TILE_SIZE) as usize;
            let left = mask.bounds.left.min(region.left as i32);
            let top = mask.bounds.top.min(region.top as i32);
            let width = (mask.bounds.right.max(region.right as i32) - left) as u32;
            let height = (mask.bounds.bottom.max(region.bottom as i32) - top) as u32;
            let selected_local = (((region.right as i32 - left) as u32).div_ceil(TILE_SIZE)
                - (region.left as i32 - left) as u32 / TILE_SIZE)
                as usize
                * (((region.bottom as i32 - top) as u32).div_ceil(TILE_SIZE)
                    - (region.top as i32 - top) as u32 / TILE_SIZE) as usize;
            let reframed = if (mask.bounds.left - left) % TILE_SIZE as i32 == 0
                && (mask.bounds.top - top) % TILE_SIZE as i32 == 0
            {
                count
            } else {
                count * 4
            };
            let stationary = (reframed + selected_local)
                .min(width.div_ceil(TILE_SIZE) as usize * height.div_ceil(TILE_SIZE) as usize);
            bytes += 36 + (stationary + selected_tiles) * (8 + TILE_BYTES);
        } else {
            bytes += 32 + count * (8 + MASK_TILE_BYTES);
        }
    }
    if bytes > MAX_LAYER_PREVIEW_BYTES {
        return Err("图层预览超出内存限制".into());
    }
    let split = if !mask_editing {
        selection
            .map(|selected| crate::translation::split(doc, selected))
            .transpose()?
    } else {
        None
    };
    let layers: Vec<_> = doc
        .layers
        .iter()
        .filter(|layer| layer.visible && layer.opacity > 0.0)
        .collect();
    let count = layers.len()
        + usize::from(split.is_some() && layers.iter().any(|layer| layer.id == doc.active));
    let mut output = Vec::new();
    for value in [doc.width, doc.height, TILE_SIZE, count as u32] {
        output.extend(value.to_le_bytes());
    }
    for layer in layers {
        if layer.id == doc.active {
            if let Some((stationary, selected)) = &split {
                write_layer(&mut output, 0, stationary, doc.palette.as_ref(), false);
                write_layer(
                    &mut output,
                    selected.id,
                    selected,
                    doc.palette.as_ref(),
                    false,
                );
            } else {
                write_layer(&mut output, layer.id, layer, doc.palette.as_ref(), false);
            }
        } else {
            write_layer(&mut output, layer.id, layer, doc.palette.as_ref(), true);
        }
    }
    let Some(mask) = active.first_mask() else {
        output.extend(0u32.to_le_bytes());
        return Ok(output);
    };
    if mask_editing {
        if let Some(selection) = selection {
            let (stationary, selected) = split_mask(mask, selection)?;
            output.extend(2u32.to_le_bytes());
            write_metadata(&mut output, &stationary);
            output.extend((stationary.tiles.len() as u32).to_le_bytes());
            for (&(x, y), tile) in &stationary.tiles {
                output.extend(x.to_le_bytes());
                output.extend(y.to_le_bytes());
                for &value in tile.as_ref() {
                    output.extend([value, value, value, 255]);
                }
            }
            output.extend((selected.len() as u32).to_le_bytes());
            for ((x, y), tile) in selected {
                output.extend(x.to_le_bytes());
                output.extend(y.to_le_bytes());
                output.extend(tile);
            }
            return Ok(output);
        }
    }
    output.extend(1u32.to_le_bytes());
    write_metadata(&mut output, mask);
    output.extend((mask.tiles.len() as u32).to_le_bytes());
    for (&(x, y), tile) in &mask.tiles {
        output.extend(x.to_le_bytes());
        output.extend(y.to_le_bytes());
        output.extend_from_slice(tile);
    }
    Ok(output)
}

fn write_layer(
    output: &mut Vec<u8>,
    id: u32,
    layer: &Layer,
    palette: Option<&IndexedPalette>,
    masked: bool,
) {
    output.extend(id.to_le_bytes());
    output.extend((layer.raster_keys().count() as u32).to_le_bytes());
    for (x, y) in layer.raster_keys() {
        output.extend(x.to_le_bytes());
        output.extend(y.to_le_bytes());
        if masked {
            output.extend(crate::raster::masked_tile(layer, palette, (x, y)));
        } else {
            output.extend_from_slice(&layer.rgba_tile(palette, (x, y)).unwrap());
        }
    }
}

fn write_metadata(output: &mut Vec<u8>, mask: &LayerMask) {
    for value in [
        i32::from(mask.default),
        i32::from(mask.enabled),
        i32::from(mask.linked),
        mask.bounds.left,
        mask.bounds.top,
        mask.bounds.right,
        mask.bounds.bottom,
    ] {
        output.extend(value.to_le_bytes());
    }
}

fn split_mask(
    mask: &LayerMask,
    selection: &Selection,
) -> Result<(LayerMask, BTreeMap<TileKey, Vec<u8>>), String> {
    if selection.is_empty() {
        return Ok((mask.clone(), BTreeMap::new()));
    }
    let region = selection.bounds();
    let bounds = MaskBounds {
        left: mask.bounds.left.min(region.left as i32),
        top: mask.bounds.top.min(region.top as i32),
        right: mask.bounds.right.max(region.right as i32),
        bottom: mask.bounds.bottom.max(region.bottom as i32),
    };
    let mut stationary = crate::masks::reframe(mask, bounds)?;
    let mut selected = BTreeMap::new();
    for y in region.top..region.bottom {
        for x in region.left..region.right {
            let coverage = selection.coverage(x, y);
            if coverage == 0 {
                continue;
            }
            let value = mask.sample(x as i32, y as i32);
            let remaining = crate::selection::mix(value, mask.default, coverage);
            if remaining != stationary.sample(x as i32, y as i32) {
                let mx = (x as i32 - bounds.left) as u32;
                let my = (y as i32 - bounds.top) as u32;
                let tile = stationary
                    .tiles
                    .entry((mx / TILE_SIZE, my / TILE_SIZE))
                    .or_insert_with(|| Arc::new(vec![mask.default; MASK_TILE_BYTES]));
                Arc::make_mut(tile)[(my % TILE_SIZE * TILE_SIZE + mx % TILE_SIZE) as usize] =
                    remaining;
            }
            let tile = selected
                .entry((x / TILE_SIZE, y / TILE_SIZE))
                .or_insert_with(|| vec![0; TILE_BYTES]);
            let start = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            let premult = ((u32::from(value) * u32::from(coverage) + 127) / 255) as u8;
            tile[start..start + 4].copy_from_slice(&[premult, premult, premult, coverage]);
        }
    }
    stationary
        .tiles
        .retain(|_, tile| tile.iter().any(|&value| value != mask.default));
    Ok((stationary, selected))
}
