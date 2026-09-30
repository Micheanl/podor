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
    crate::aseprite::resize_grid(
        &mut resized.aseprite_metadata,
        i64::from(dx),
        i64::from(dy),
        1.0,
        1.0,
    )?;
    resized.assistants = doc
        .assistants
        .affine(1.0, 1.0, f64::from(dx), f64::from(dy))?;
    let bounds = resized.bounds();
    let tile_bytes = if doc.palette.is_some() {
        INDEX_TILE_BYTES
    } else {
        TILE_BYTES
    };
    let mask_bytes = doc
        .layers
        .iter()
        .map(|layer| layer.mask_bytes())
        .sum::<usize>();
    let mut budget = (MAX_DOCUMENT_BYTES - mask_bytes) / tile_bytes;
    for layer in &mut resized.layers {
        if let Ok(vector) = layer.vector() {
            layer.content = LayerContent::Vector(crate::vector::transformed(
                vector,
                tiny_skia::Transform::from_translate(dx as f32, dy as f32),
            )?);
        }
        if layer.raster_opt().is_some() {
            let tiles = if let Some(palette) = doc.palette.as_ref() {
                crate::indexed_geometry::remap(
                    layer,
                    palette,
                    doc.bounds(),
                    bounds,
                    dx,
                    dy,
                    budget,
                )?
            } else {
                translation::remap(layer, doc.bounds(), bounds, dx, dy, budget)?
            };
            layer.raster_mut()?.set_tiles(tiles);
            budget -= layer.raster()?.tiles().len();
        }
        for mask in layer.masks.iter_mut().filter(|mask| mask.plane.linked) {
            mask.plane = crate::masks::offset(&mask.plane, dx, dy)?;
        }
    }
    let live: HashSet<_> = resized
        .layers
        .iter()
        .flat_map(|layer| layer.raster_buffers())
        .map(Arc::as_ptr)
        .collect();
    let retained: HashSet<_> = doc
        .layers
        .iter()
        .flat_map(|layer| layer.raster_buffers())
        .map(Arc::as_ptr)
        .filter(|tile| !live.contains(tile))
        .collect();
    if retained.len() * tile_bytes > MAX_HISTORY_BYTES {
        return Err("调整画布会超出撤销内存限制".into());
    }
    crate::masks::check_transaction(doc, &resized)?;
    Ok(resized)
}
