use crate::model::*;
use std::{
    collections::{BTreeMap, HashSet},
    sync::Arc,
};

pub fn translate(
    doc: &Document,
    id: u32,
    dx: i32,
    dy: i32,
    selection: Option<&crate::selection::Selection>,
) -> Result<BTreeMap<TileKey, Tile>, String> {
    if doc.palette.is_some() {
        return crate::indexed_geometry::translate(doc, id, dx, dy, selection);
    }
    let layer = doc
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .ok_or("图层不存在")?;
    if layer.locked {
        return Err("图层已锁定，请先解锁".into());
    }
    if layer.alpha_locked {
        return Err("请先解除透明度锁定".into());
    }
    if !layer.visible {
        return Err("请先显示当前图层".into());
    }
    if dx.unsigned_abs() > doc.width || dy.unsigned_abs() > doc.height {
        return Err("移动距离超出画布尺寸".into());
    }
    if dx == 0 && dy == 0 {
        return Ok(layer.raster()?.tiles().clone());
    }
    let budget =
        MAX_DOCUMENT_BYTES / TILE_BYTES - (doc.tile_count() - layer.raster()?.tiles().len());
    let tiles = if let Some(selection) = selection {
        let (stationary, selected) = split(doc, selection)?;
        let shifted = remap(&selected, doc.bounds(), doc.bounds(), dx, dy, budget)?;
        let mut tiles = stationary.raster()?.tiles().clone();
        for (key, source) in shifted {
            if !tiles.contains_key(&key) && tiles.len() == budget {
                return Err("工程像素超过内存限制".into());
            }
            let target = tiles
                .entry(key)
                .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
            for (dst, src) in Arc::make_mut(target)
                .as_chunks_mut::<4>()
                .0
                .iter_mut()
                .zip(source.as_chunks::<4>().0)
            {
                let inverse = 255 - u32::from(src[3]);
                for c in 0..4 {
                    dst[c] = (u32::from(src[c]) + (u32::from(dst[c]) * inverse + 127) / 255)
                        .min(255) as u8;
                }
            }
        }
        tiles
    } else {
        remap(layer, doc.bounds(), doc.bounds(), dx, dy, budget)?
    };
    let live: HashSet<_> = doc
        .layers
        .iter()
        .filter(|other| other.id != id)
        .flat_map(|other| other.raster_buffers())
        .chain(tiles.values())
        .map(Arc::as_ptr)
        .collect();
    let retained: HashSet<_> = layer
        .raster()?
        .tiles()
        .values()
        .map(Arc::as_ptr)
        .filter(|tile| !live.contains(tile))
        .collect();
    if retained.len() * TILE_BYTES > MAX_HISTORY_BYTES {
        return Err("图层内容过多，移动会超出撤销内存限制".into());
    }
    Ok(tiles)
}

pub fn remap(
    layer: &Layer,
    source: Rect,
    target: Rect,
    dx: i32,
    dy: i32,
    budget: usize,
) -> Result<BTreeMap<TileKey, Tile>, String> {
    let mut tiles = BTreeMap::<TileKey, Tile>::new();
    for (&(tx, ty), pixels) in layer.raster()?.tiles() {
        let left = tx * TILE_SIZE;
        let top = ty * TILE_SIZE;
        let target_left = i64::from(left) + i64::from(dx);
        let target_top = i64::from(top) + i64::from(dy);
        if dx % TILE_SIZE as i32 == 0
            && dy % TILE_SIZE as i32 == 0
            && left + TILE_SIZE <= source.right
            && top + TILE_SIZE <= source.bottom
            && target_left >= 0
            && target_top >= 0
            && target_left + i64::from(TILE_SIZE) <= i64::from(target.right)
            && target_top + i64::from(TILE_SIZE) <= i64::from(target.bottom)
        {
            if tiles.len() == budget {
                return Err("工程像素超过内存限制".into());
            }
            tiles.insert(
                (
                    target_left as u32 / TILE_SIZE,
                    target_top as u32 / TILE_SIZE,
                ),
                pixels.clone(),
            );
            continue;
        }
        for row in 0..TILE_SIZE.min(source.bottom - top) {
            let y = i64::from(top + row) + i64::from(dy);
            if y < 0 || y >= i64::from(target.bottom) {
                continue;
            }
            let mut x = 0;
            while x < TILE_SIZE.min(source.right - left) {
                let target_x = i64::from(left + x) + i64::from(dx);
                if target_x < 0 {
                    x += (-target_x).min(i64::from(TILE_SIZE - x)) as u32;
                    continue;
                }
                if target_x >= i64::from(target.right) {
                    break;
                }
                let target_x = target_x as u32;
                let y = y as u32;
                let count = (TILE_SIZE - x)
                    .min(TILE_SIZE - target_x % TILE_SIZE)
                    .min(source.right - left - x)
                    .min(target.right - target_x);
                let source = ((row * TILE_SIZE + x) * 4) as usize;
                let bytes = &pixels[source..source + (count * 4) as usize];
                if bytes.as_chunks::<4>().0.iter().any(|pixel| pixel[3] != 0) {
                    let key = (target_x / TILE_SIZE, y / TILE_SIZE);
                    if !tiles.contains_key(&key) && tiles.len() == budget {
                        return Err("工程像素超过内存限制".into());
                    }
                    let target = tiles
                        .entry(key)
                        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
                    let start = ((y % TILE_SIZE * TILE_SIZE + target_x % TILE_SIZE) * 4) as usize;
                    Arc::make_mut(target)[start..start + bytes.len()].copy_from_slice(bytes);
                }
                x += count;
            }
        }
    }
    Ok(tiles)
}

pub fn frame(doc: &Document) -> Result<Vec<u8>, String> {
    if doc
        .layers
        .iter()
        .any(|layer| layer.is_group() || layer.is_adjustment() || layer.is_vector())
    {
        return Err("图层组或调整图层移动请使用完整合成预览".into());
    }
    let layers: Vec<_> = doc
        .layers
        .iter()
        .filter(|layer| layer.visible && layer.opacity > 0.0)
        .collect();
    let bytes = 16
        + layers
            .iter()
            .map(|layer| 8 + layer.raster_keys().count() * (8 + TILE_BYTES))
            .sum::<usize>();
    if bytes > MAX_LAYER_PREVIEW_BYTES {
        return Err("图层预览超出内存限制".into());
    }
    let mut output = Vec::with_capacity(bytes);
    for value in [doc.width, doc.height, TILE_SIZE, layers.len() as u32] {
        output.extend(value.to_le_bytes());
    }
    for layer in layers {
        output.extend(layer.id.to_le_bytes());
        output.extend((layer.raster()?.tiles().len() as u32).to_le_bytes());
        for &(x, y) in layer.raster()?.tiles().keys() {
            output.extend(x.to_le_bytes());
            output.extend(y.to_le_bytes());
            output.extend_from_slice(&crate::raster::masked_tile(
                layer,
                doc.palette.as_ref(),
                (x, y),
            ));
        }
    }
    Ok(output)
}

pub fn split(
    doc: &Document,
    selection: &crate::selection::Selection,
) -> Result<(Layer, Layer), String> {
    if doc.palette.is_some() {
        return Err("索引色选区移动尚未支持，请先取消选区或转换为 RGBA".into());
    }
    let (stationary, _) = crate::clipboard::cut(doc, Some(selection))?;
    let source = doc
        .layers
        .iter()
        .find(|layer| layer.id == doc.active)
        .ok_or("图层不存在")?;
    let mut selected = source.clone();
    selected.raster_mut()?.tiles_mut().clear();
    for (&key, tile) in source.raster()?.tiles() {
        let Some(area) = (Rect {
            left: key.0 * TILE_SIZE,
            top: key.1 * TILE_SIZE,
            right: (key.0 + 1) * TILE_SIZE,
            bottom: (key.1 + 1) * TILE_SIZE,
        })
        .intersect(selection.bounds()) else {
            continue;
        };
        let mut pixels = vec![0; TILE_BYTES];
        let mut nonempty = false;
        for gy in area.top..area.bottom {
            for gx in area.left..area.right {
                let x = gx % TILE_SIZE;
                let y = gy % TILE_SIZE;
                let coverage = u32::from(selection.coverage(gx, gy));
                if coverage == 0 {
                    continue;
                }
                let offset = ((y * TILE_SIZE + x) * 4) as usize;
                for c in 0..4 {
                    pixels[offset + c] =
                        ((u32::from(tile[offset + c]) * coverage + 127) / 255) as u8;
                }
                nonempty |= pixels[offset + 3] != 0;
            }
        }
        if nonempty {
            selected
                .raster_mut()?
                .tiles_mut()
                .insert(key, Arc::new(pixels));
        }
    }
    Ok((stationary, selected))
}

pub fn selection_frame(
    doc: &Document,
    selection: &crate::selection::Selection,
) -> Result<Vec<u8>, String> {
    let (mut stationary, selected) = split(doc, selection)?;
    stationary.id = 0;
    let mut preview = doc.clone();
    let index = preview
        .layers
        .iter()
        .position(|layer| layer.id == doc.active)
        .ok_or("图层不存在")?;
    preview.layers[index] = selected;
    preview.layers.insert(index, stationary);
    frame(&preview)
}
