use crate::{model::*, raster::composite_tile_background};
use std::collections::{BTreeSet, HashSet};
use std::sync::Arc;

pub fn duplicate(doc: &mut Document, source: u32) -> Result<(), String> {
    let index = doc
        .layers
        .iter()
        .position(|layer| layer.id == source)
        .ok_or("图层不存在")?;
    if doc.layers.len() >= MAX_LAYERS {
        return Err("已达到图层上限".into());
    }
    if doc.tile_count() + doc.layers[index].tiles.len() > MAX_DOCUMENT_BYTES / TILE_BYTES {
        return Err("当前工程已达到像素内存上限".into());
    }
    let id = doc.next_id;
    let next_id = id.checked_add(1).ok_or("图层编号超出限制")?;
    let mut copy = doc.layers[index].clone();
    let suffix = format!(" · {id}");
    let mut end = copy.name.len().min(MAX_LAYER_NAME_BYTES - suffix.len());
    while !copy.name.is_char_boundary(end) {
        end -= 1;
    }
    copy.name.truncate(end);
    copy.name.push_str(&suffix);
    copy.id = id;
    doc.layers.insert(index + 1, copy);
    doc.next_id = next_id;
    doc.active = id;
    Ok(())
}

pub fn merge_visible(doc: &mut Document) -> Result<(), String> {
    if doc.layers.iter().filter(|layer| layer.visible).count() < 2 {
        return Err("至少需要两个可见图层".into());
    }
    let hidden: HashSet<_> = doc
        .layers
        .iter()
        .filter(|layer| !layer.visible)
        .flat_map(|layer| layer.tiles.values().map(Arc::as_ptr))
        .collect();
    let retained: HashSet<_> = doc
        .layers
        .iter()
        .filter(|layer| layer.visible)
        .flat_map(|layer| layer.tiles.values().map(Arc::as_ptr))
        .filter(|tile| !hidden.contains(tile))
        .collect();
    if retained.len() > MAX_HISTORY_BYTES / TILE_BYTES {
        return Err("图层内容过多，合并会超出撤销内存限制".into());
    }
    let id = doc.next_id;
    let next_id = id.checked_add(1).ok_or("图层编号超出限制")?;
    let keys: BTreeSet<_> = doc
        .layers
        .iter()
        .filter(|layer| layer.visible && layer.opacity > 0.0)
        .flat_map(|layer| layer.tiles.keys().copied())
        .collect();
    let mut merged = Layer::new(id, "合并图层".into());
    for key in keys {
        let pixels = composite_tile_background(doc, key, true);
        if pixels.as_chunks::<4>().0.iter().any(|pixel| pixel[3] != 0) {
            merged.tiles.insert(key, Arc::new(pixels));
        }
    }
    let top = doc.layers.iter().rposition(|layer| layer.visible).unwrap();
    let insertion = doc.layers[..top]
        .iter()
        .filter(|layer| !layer.visible)
        .count();
    doc.layers.retain(|layer| !layer.visible);
    doc.layers.insert(insertion, merged);
    doc.active = id;
    doc.next_id = next_id;
    Ok(())
}
