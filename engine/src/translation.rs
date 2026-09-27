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
) -> Result<BTreeMap<TileKey, Tile>, String> {
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
        return Ok(layer.tiles.clone());
    }
    let budget = MAX_DOCUMENT_BYTES / TILE_BYTES - (doc.tile_count() - layer.tiles.len());
    let mut tiles = BTreeMap::<TileKey, Tile>::new();
    for (&(tx, ty), pixels) in &layer.tiles {
        let left = tx * TILE_SIZE;
        let top = ty * TILE_SIZE;
        let target_left = i64::from(left) + i64::from(dx);
        let target_top = i64::from(top) + i64::from(dy);
        if dx % TILE_SIZE as i32 == 0
            && dy % TILE_SIZE as i32 == 0
            && left + TILE_SIZE <= doc.width
            && top + TILE_SIZE <= doc.height
            && target_left >= 0
            && target_top >= 0
            && target_left + i64::from(TILE_SIZE) <= i64::from(doc.width)
            && target_top + i64::from(TILE_SIZE) <= i64::from(doc.height)
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
        for row in 0..TILE_SIZE.min(doc.height - top) {
            let y = i64::from(top + row) + i64::from(dy);
            if y < 0 || y >= i64::from(doc.height) {
                continue;
            }
            let mut x = 0;
            while x < TILE_SIZE.min(doc.width - left) {
                let target_x = i64::from(left + x) + i64::from(dx);
                if target_x < 0 {
                    x += (-target_x).min(i64::from(TILE_SIZE - x)) as u32;
                    continue;
                }
                if target_x >= i64::from(doc.width) {
                    break;
                }
                let target_x = target_x as u32;
                let y = y as u32;
                let count = (TILE_SIZE - x)
                    .min(TILE_SIZE - target_x % TILE_SIZE)
                    .min(doc.width - left - x)
                    .min(doc.width - target_x);
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
    let live: HashSet<_> = doc
        .layers
        .iter()
        .filter(|other| other.id != id)
        .flat_map(|other| other.tiles.values())
        .chain(tiles.values())
        .map(Arc::as_ptr)
        .collect();
    let retained: HashSet<_> = layer
        .tiles
        .values()
        .map(Arc::as_ptr)
        .filter(|tile| !live.contains(tile))
        .collect();
    if retained.len() * TILE_BYTES > MAX_HISTORY_BYTES {
        return Err("图层内容过多，移动会超出撤销内存限制".into());
    }
    Ok(tiles)
}

pub fn frame(doc: &Document) -> Vec<u8> {
    let layers: Vec<_> = doc
        .layers
        .iter()
        .filter(|layer| layer.visible && layer.opacity > 0.0)
        .collect();
    let mut output = Vec::with_capacity(
        16 + layers
            .iter()
            .map(|layer| 8 + layer.tiles.len() * (8 + TILE_BYTES))
            .sum::<usize>(),
    );
    for value in [doc.width, doc.height, TILE_SIZE, layers.len() as u32] {
        output.extend(value.to_le_bytes());
    }
    for layer in layers {
        output.extend(layer.id.to_le_bytes());
        output.extend((layer.tiles.len() as u32).to_le_bytes());
        for (&(x, y), tile) in &layer.tiles {
            output.extend(x.to_le_bytes());
            output.extend(y.to_le_bytes());
            output.extend_from_slice(tile);
        }
    }
    output
}
