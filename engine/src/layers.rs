use crate::{model::*, raster::FrameCompositor};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::sync::Arc;

pub fn duplicate(doc: &mut Document, source: u32) -> Result<(), String> {
    let plan = crate::groups::Hierarchy::new(doc)?;
    let index = doc
        .layers
        .iter()
        .position(|layer| layer.id == source)
        .ok_or("图层不存在")?;
    let end = plan.end[index];
    let mut copies = doc.layers[index..end].to_vec();
    let source_bytes: usize = copies
        .iter()
        .map(|layer| {
            layer
                .raster_opt()
                .map_or(0, |raster| raster.tiles().len() * raster.tile_bytes())
                + layer.mask_bytes()
        })
        .sum();
    if doc.pixel_bytes() + source_bytes > MAX_DOCUMENT_BYTES {
        return Err("当前工程已达到像素内存上限".into());
    }
    let next_id = doc
        .next_id
        .checked_add(copies.len() as u32)
        .ok_or("图层编号超出限制")?;
    let mapping: HashMap<_, _> = copies
        .iter()
        .enumerate()
        .map(|(offset, layer)| (layer.id, doc.next_id + offset as u32))
        .collect();
    for copy in &mut copies {
        copy.id = mapping[&copy.id];
        for mask in &mut copy.masks {
            mask.id = 0;
        }
        if let Some(parent) = copy
            .parent_id
            .and_then(|parent| mapping.get(&parent).copied())
        {
            copy.parent_id = Some(parent);
        }
    }
    let id = copies[0].id;
    let suffix = format!(" · {id}");
    let mut name_end = copies[0]
        .name
        .len()
        .min(MAX_LAYER_NAME_BYTES - suffix.len());
    while !copies[0].name.is_char_boundary(name_end) {
        name_end -= 1;
    }
    copies[0].name.truncate(name_end);
    copies[0].name.push_str(&suffix);
    let mut next = doc.clone();
    next.layers.splice(end..end, copies);
    next.next_id = next_id;
    next.active = id;
    next.assign_mask_ids()?;
    next.validate()?;
    *doc = next;
    Ok(())
}

pub fn merge_visible(doc: &mut Document) -> Result<(), String> {
    if doc
        .layers
        .iter()
        .any(|layer| layer.is_group() || layer.is_adjustment())
    {
        return merge_visible_groups(doc);
    }
    if doc.layers.iter().enumerate().any(|(index, layer)| {
        layer.clipping
            && layer.visible != doc.layers[crate::clipping::base_index(&doc.layers, index)].visible
    }) {
        return Err("请先统一剪贴蒙版与底层的可见性，再合并可见图层".into());
    }
    if doc.layers.iter().any(|layer| layer.visible && layer.locked) {
        return Err("请先解锁要合并的图层".into());
    }
    if doc.layers.iter().filter(|layer| layer.visible).count() < 2 {
        return Err("至少需要两个可见图层".into());
    }
    let hidden: HashSet<_> = doc
        .layers
        .iter()
        .filter(|layer| !layer.visible)
        .flat_map(|layer| {
            layer
                .raster_buffers()
                .chain(layer.mask_buffers())
                .map(Arc::as_ptr)
        })
        .collect();
    let retained: HashMap<_, _> = doc
        .layers
        .iter()
        .filter(|layer| layer.visible)
        .flat_map(|layer| layer.raster_buffers().chain(layer.mask_buffers()))
        .filter(|tile| !hidden.contains(&Arc::as_ptr(tile)))
        .map(|tile| (Arc::as_ptr(tile), tile.len()))
        .collect();
    if retained.values().sum::<usize>() > MAX_HISTORY_BYTES {
        return Err("图层内容过多，合并会超出撤销内存限制".into());
    }
    let id = doc.next_id;
    let next_id = id.checked_add(1).ok_or("图层编号超出限制")?;
    let keys: BTreeSet<_> = doc
        .layers
        .iter()
        .filter(|layer| layer.visible && layer.opacity > 0.0)
        .flat_map(|layer| layer.content_keys(doc.bounds()))
        .collect();
    let mut merged = Layer::new(id, "合并图层".into());
    let mut compositor = FrameCompositor::new(doc, true);
    for key in keys {
        let pixels = compositor.tile(doc, key);
        if pixels.as_chunks::<4>().0.iter().any(|pixel| pixel[3] != 0) {
            merged
                .raster_mut()?
                .tiles_mut()
                .insert(key, Arc::new(pixels));
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

fn merge_visible_groups(doc: &mut Document) -> Result<(), String> {
    let plan = crate::groups::Hierarchy::new(doc)?;
    let visible: Vec<_> = plan
        .roots
        .iter()
        .copied()
        .filter(|&index| doc.layers[index].visible)
        .collect();
    if visible.is_empty() || visible.len() == 1 && !doc.layers[visible[0]].is_group() {
        return Err("至少需要两个可见图层".into());
    }
    for &root in &visible {
        if doc.layers[root..plan.end[root]]
            .iter()
            .enumerate()
            .any(|(offset, _)| !plan.visible[root + offset] || plan.locked[root + offset])
        {
            return Err("请先显示并解锁所有待合并组内图层".into());
        }
    }
    if doc.layers.iter().enumerate().any(|(index, layer)| {
        layer.clipping
            && plan.visible[index] != plan.visible[crate::clipping::base_index(&doc.layers, index)]
    }) {
        return Err("请先统一剪贴蒙版与底层的可见性".into());
    }
    let keys: BTreeSet<_> = visible
        .iter()
        .flat_map(|&index| {
            doc.layers[index..plan.end[index]]
                .iter()
                .flat_map(|layer| layer.content_keys(doc.bounds()))
        })
        .collect();
    let id = doc.next_id;
    let next_id = id.checked_add(1).ok_or("图层编号超出限制")?;
    let mut merged = Layer::new(id, "合并图层".into());
    let mut compositor = FrameCompositor::new(doc, true);
    for key in keys {
        let pixels = compositor.tile(doc, key);
        if pixels.as_chunks::<4>().0.iter().any(|pixel| pixel[3] != 0) {
            merged
                .raster_mut()?
                .tiles_mut()
                .insert(key, Arc::new(pixels));
        }
    }
    let top = *visible.last().unwrap();
    let mut output = Vec::new();
    for &root in &plan.roots {
        if !doc.layers[root].visible {
            output.extend_from_slice(&doc.layers[root..plan.end[root]]);
        }
        if root == top {
            output.push(merged.clone());
        }
    }
    let mut next = doc.clone();
    next.layers = output;
    next.active = id;
    next.next_id = next_id;
    next.normalize_mask_target();
    crate::masks::check_transaction(doc, &next)?;
    *doc = next;
    Ok(())
}
