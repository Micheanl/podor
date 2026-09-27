use crate::{model::*, translation};
use std::{collections::HashSet, sync::Arc};

pub fn resize(doc: &Document, width: u32, height: u32, anchor: u8) -> Result<Document, String> {
    Document::new(width, height)?;
    if anchor >= 9 {
        return Err("画布定位无效".into());
    }
    let dx = (width as i32 - doc.width as i32) * i32::from(anchor % 3) / 2;
    let dy = (height as i32 - doc.height as i32) * i32::from(anchor / 3) / 2;
    let mut resized = doc.clone();
    resized.width = width;
    resized.height = height;
    let bounds = resized.bounds();
    let mut budget = MAX_DOCUMENT_BYTES / TILE_BYTES;
    for layer in &mut resized.layers {
        layer.tiles = translation::remap(layer, doc.bounds(), bounds, dx, dy, budget)?;
        budget -= layer.tiles.len();
    }
    let live: HashSet<_> = resized
        .layers
        .iter()
        .flat_map(|layer| layer.tiles.values())
        .map(Arc::as_ptr)
        .collect();
    let retained: HashSet<_> = doc
        .layers
        .iter()
        .flat_map(|layer| layer.tiles.values())
        .map(Arc::as_ptr)
        .filter(|tile| !live.contains(tile))
        .collect();
    if retained.len() * TILE_BYTES > MAX_HISTORY_BYTES {
        return Err("调整画布会超出撤销内存限制".into());
    }
    Ok(resized)
}
