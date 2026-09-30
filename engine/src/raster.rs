use crate::model::*;
use crate::selection::Selection;
use std::{
    cell::RefCell,
    collections::{BTreeMap, BTreeSet, VecDeque},
    sync::Arc,
};

pub fn stamp(
    doc: &mut Document,
    selection: Option<&Selection>,
    brush: Brush,
    point: Sample,
    dirty: &mut BTreeSet<TileKey>,
    remaining: &mut usize,
) -> Result<bool, String> {
    let simple = brush.tip == BrushTip::Round
        && brush.aspect == 1.0
        && brush.grain == 0.0
        && brush.paper == 0.0;
    match (simple, brush.symmetry.mode != SymmetryMode::Off) {
        (true, false) => stamp_impl::<true, false>(doc, selection, brush, point, dirty, remaining),
        (false, false) => {
            stamp_impl::<false, false>(doc, selection, brush, point, dirty, remaining)
        }
        (true, true) => stamp_impl::<true, true>(doc, selection, brush, point, dirty, remaining),
        (false, true) => stamp_impl::<false, true>(doc, selection, brush, point, dirty, remaining),
    }
}

fn stamp_impl<const SIMPLE: bool, const SYMMETRIC: bool>(
    doc: &mut Document,
    selection: Option<&Selection>,
    brush: Brush,
    point: Sample,
    dirty: &mut BTreeSet<TileKey>,
    remaining: &mut usize,
) -> Result<bool, String> {
    let opacity = brush.opacity_at_pressure(point.pressure);
    if opacity == 0.0 {
        return Ok(false);
    }
    let (dabs, count) = if SYMMETRIC {
        crate::dab::Dab::symmetric(doc.bounds(), selection, brush, point)
    } else {
        (
            [crate::dab::Dab::new(doc.bounds(), selection, brush, point); 4],
            1,
        )
    };
    let region = selection.map_or(doc.bounds(), Selection::bounds);
    let layer = doc.active_mut();
    let alpha_locked = layer.alpha_locked;
    let pixel = brush.raster != BrushRaster::Antialiased;
    let mut modified = false;
    for (index, dab) in dabs[..count].iter().enumerate() {
        let Rect {
            left,
            top,
            right,
            bottom,
        } = dab.bounds;
        if left >= right || top >= bottom {
            continue;
        }
        for ty in top / TILE_SIZE..=(bottom - 1) / TILE_SIZE {
            for tx in left / TILE_SIZE..=(right - 1) / TILE_SIZE {
                let key = (tx, ty);
                let mut bounds = Rect {
                    left: left.max(tx * TILE_SIZE),
                    top: top.max(ty * TILE_SIZE),
                    right: right.min((tx + 1) * TILE_SIZE),
                    bottom: bottom.min((ty + 1) * TILE_SIZE),
                };
                if SYMMETRIC {
                    let tile_bounds = Rect {
                        left: tx * TILE_SIZE,
                        top: ty * TILE_SIZE,
                        right: (tx + 1) * TILE_SIZE,
                        bottom: (ty + 1) * TILE_SIZE,
                    };
                    if dabs[..index]
                        .iter()
                        .any(|other| other.bounds.intersect(tile_bounds).is_some())
                    {
                        continue;
                    }
                    for other in &dabs[index + 1..count] {
                        if let Some(part) = other.bounds.intersect(tile_bounds) {
                            bounds.left = bounds.left.min(part.left);
                            bounds.top = bounds.top.min(part.top);
                            bounds.right = bounds.right.max(part.right);
                            bounds.bottom = bounds.bottom.max(part.bottom);
                        }
                    }
                }
                if selection.is_some_and(|selection| !selection.intersects(bounds)) {
                    continue;
                }
                if (brush.eraser || alpha_locked) && !layer.raster()?.tiles().contains_key(&key) {
                    continue;
                }
                let coverage_at = |x, y| {
                    if SYMMETRIC {
                        dabs[..count]
                            .iter()
                            .filter(|part| part.bounds.contains(x, y))
                            .fold(0.0f32, |coverage, part| {
                                coverage.max(part.coverage::<SIMPLE>(x, y))
                            })
                    } else {
                        dab.coverage::<SIMPLE>(x, y)
                    }
                };
                if pixel && !layer.raster()?.tiles().contains_key(&key) {
                    let painted = (bounds.top..bounds.bottom).any(|y| {
                        let mask = selection.and_then(|selection| selection.row(y));
                        (bounds.left..bounds.right).any(|x| {
                            let selected = mask.map_or(255, |row| row[(x - region.left) as usize]);
                            selected >= 128 && (coverage_at(x, y) * opacity * 255.0).round() > 0.0
                        })
                    });
                    if !painted {
                        continue;
                    }
                }
                if !layer.raster()?.tiles().contains_key(&key) {
                    if *remaining == 0 {
                        return Err("当前工程已达到像素内存上限".into());
                    }
                    *remaining -= 1;
                }
                let pixels = Arc::make_mut(
                    layer
                        .raster_mut()?
                        .tiles_mut()
                        .entry(key)
                        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES])),
                );
                let mut changed = false;
                for y in bounds.top..bounds.bottom {
                    let mask = selection.and_then(|selection| selection.row(y));
                    for x in bounds.left..bounds.right {
                        let selected = mask.map_or(255, |row| row[(x - region.left) as usize]);
                        let selected = if pixel {
                            u8::from(selected >= 128) * 255
                        } else {
                            selected
                        };
                        if selected == 0 {
                            continue;
                        }
                        let coverage = coverage_at(x, y);
                        let alpha = (coverage * opacity * f32::from(selected)).round() as u32;
                        if alpha == 0 {
                            continue;
                        }
                        let offset = (((y % TILE_SIZE) * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                        let pixel = &mut pixels[offset..offset + 4];
                        let old = [pixel[0], pixel[1], pixel[2], pixel[3]];
                        let inverse = 255 - alpha;
                        if brush.eraser {
                            for value in pixel.iter_mut() {
                                *value = (u32::from(*value) * inverse / 255) as u8;
                            }
                        } else if alpha_locked {
                            crate::blending::paint_preserving_alpha(pixel, brush.color, alpha);
                        } else {
                            for (channel, value) in pixel.iter_mut().enumerate().take(3) {
                                *value = ((u32::from(brush.color[channel]) * alpha
                                    + u32::from(*value) * inverse
                                    + 127)
                                    / 255) as u8;
                            }
                            pixel[3] = (alpha + (u32::from(pixel[3]) * inverse + 127) / 255) as u8;
                        }
                        changed |= pixel != old;
                    }
                }
                if changed {
                    dirty.insert(key);
                    modified = true;
                }
            }
        }
    }
    Ok(modified)
}

pub fn composite_tile(doc: &Document, key: TileKey) -> Vec<u8> {
    composite_tile_background(doc, key, false)
}

pub fn composite_tile_background(doc: &Document, key: TileKey, transparent: bool) -> Vec<u8> {
    FrameCompositor::new(doc, transparent).tile(doc, key)
}

pub(crate) struct FrameCompositor {
    plan: Option<RenderPlan>,
    transparent: bool,
    cache: crate::indexed::RgbaCache,
}

impl FrameCompositor {
    pub fn new(doc: &Document, transparent: bool) -> Self {
        Self {
            plan: Some(RenderPlan::new(doc)),
            transparent,
            cache: crate::indexed::RgbaCache::default(),
        }
    }

    pub fn with_vector_cache(
        doc: &Document,
        transparent: bool,
        cache: Arc<std::sync::Mutex<crate::vector::RenderCache>>,
    ) -> Self {
        if let Ok(mut entries) = cache.lock() {
            entries.discard_expired();
        }
        let mut frame = Self::new(doc, transparent);
        frame.plan.as_mut().unwrap().vectors = cache;
        frame
    }

    pub fn tile(&mut self, doc: &Document, key: TileKey) -> Vec<u8> {
        composite_prepared(
            doc,
            key,
            self.transparent,
            self.plan.as_ref(),
            &mut self.cache,
        )
    }

    pub fn tile_cached(
        &self,
        doc: &Document,
        key: TileKey,
        cache: &mut crate::indexed::RgbaCache,
    ) -> Vec<u8> {
        composite_prepared(doc, key, self.transparent, self.plan.as_ref(), cache)
    }
}

fn composite_prepared(
    doc: &Document,
    key: TileKey,
    transparent: bool,
    plan: Option<&RenderPlan>,
    cache: &mut crate::indexed::RgbaCache,
) -> Vec<u8> {
    let opaque = !transparent && normal_layers(doc);
    let mut result = vec![if opaque { 255 } else { 0 }; TILE_BYTES];
    let mut resolve = |layer: &Layer| match &layer.content {
        LayerContent::Vector(vector) => plan?.vectors.lock().ok()?.tile(vector, key),
        _ => match doc.palette.as_ref() {
            Some(palette) => cache.tile(layer, palette, key),
            None => resolve_tile(layer, None, key),
        },
    };
    if let Some(plan) = plan {
        composite_tree(
            &mut result,
            doc,
            plan,
            &plan.hierarchy.roots,
            key,
            opaque,
            &mut resolve,
        );
    } else if let Some(palette) = doc.palette.as_ref() {
        composite_groups(&mut result, &doc.layers, key, opaque, |layer| {
            cache.tile(layer, palette, key)
        });
    } else {
        composite_layers(&mut result, &doc.layers, doc.palette.as_ref(), key, opaque);
    }
    if !transparent && !opaque {
        white_background(&mut result);
    }
    result
}

fn normal_layers(doc: &Document) -> bool {
    if doc
        .layers
        .iter()
        .any(|layer| layer.is_group() || layer.is_adjustment())
    {
        return false;
    }
    doc.layers
        .iter()
        .all(|layer| !layer.visible || layer.opacity == 0.0 || layer.blend == BlendMode::Normal)
}

pub(crate) struct RenderPlan {
    pub hierarchy: crate::groups::Hierarchy,
    kernels: Vec<Option<crate::adjustment_layers::Kernel>>,
    coverage: RefCell<crate::mask_stack::CoverageCache>,
    vectors: Arc<std::sync::Mutex<crate::vector::RenderCache>>,
}

impl RenderPlan {
    pub fn new(doc: &Document) -> Self {
        Self {
            hierarchy: crate::groups::Hierarchy::new(doc).expect("无效的图层层级"),
            coverage: RefCell::default(),
            vectors: Default::default(),
            kernels: doc
                .layers
                .iter()
                .map(|layer| match &layer.content {
                    LayerContent::Adjustment { settings } => {
                        Some(settings.compile().expect("无效的调整参数"))
                    }
                    _ => None,
                })
                .collect(),
        }
    }
}

fn node_tile(
    doc: &Document,
    plan: &RenderPlan,
    index: usize,
    key: TileKey,
    resolve: &mut impl FnMut(&Layer) -> Option<Tile>,
) -> Option<Tile> {
    let layer = &doc.layers[index];
    match layer.content {
        LayerContent::Raster(_) | LayerContent::Vector(_) => resolve(layer),
        LayerContent::Group { .. } => {
            let mut pixels = vec![0; TILE_BYTES];
            composite_tree(
                &mut pixels,
                doc,
                plan,
                &plan.hierarchy.children[index],
                key,
                false,
                resolve,
            );
            pixels
                .as_chunks::<4>()
                .0
                .iter()
                .any(|pixel| pixel[3] != 0)
                .then(|| Arc::new(pixels))
        }
        LayerContent::Adjustment { .. } | LayerContent::CelTrack { .. } => None,
    }
}

fn resolve_tile(layer: &Layer, palette: Option<&IndexedPalette>, key: TileKey) -> Option<Tile> {
    match layer.raster_opt()? {
        RasterPlane::Rgba(tiles) => tiles.get(&key).cloned(),
        RasterPlane::Indexed(_) => layer
            .rgba_tile(palette, key)
            .map(|tile| Arc::new(tile.into_owned())),
    }
}

pub(crate) fn preview_node_tile(
    doc: &Document,
    plan: &RenderPlan,
    index: usize,
    key: TileKey,
) -> Vec<u8> {
    let mut pixels = node_tile(doc, plan, index, key, &mut |layer| match &layer.content {
        LayerContent::Vector(vector) => plan.vectors.lock().ok()?.tile(vector, key),
        _ => resolve_tile(layer, doc.palette.as_ref(), key),
    })
    .map_or_else(|| vec![0; TILE_BYTES], |tile| tile.as_ref().clone());
    apply_mask_cached(&mut pixels, &doc.layers[index], key, &plan.coverage);
    pixels
}

pub(crate) fn adjustment_preview_document(
    doc: &Document,
    plan: &RenderPlan,
    index: usize,
) -> Document {
    let mut copy = doc.clone();
    let mut scope = index;
    loop {
        let siblings = plan.hierarchy.siblings(scope);
        let position = siblings
            .iter()
            .position(|&sibling| sibling == scope)
            .unwrap();
        for &sibling in &siblings[position + 1..] {
            copy.layers[sibling].visible = false;
        }
        match plan.hierarchy.parent[scope] {
            Some(parent) => scope = parent,
            None => break,
        }
    }
    copy.layers[index].visible = true;
    copy
}

fn composite_tree(
    result: &mut [u8],
    doc: &Document,
    plan: &RenderPlan,
    siblings: &[usize],
    key: TileKey,
    opaque: bool,
    resolve: &mut impl FnMut(&Layer) -> Option<Tile>,
) {
    let mut position = 0;
    while position < siblings.len() {
        let index = siblings[position];
        let base = &doc.layers[index];
        if let Some(kernel) = &plan.kernels[index] {
            kernel.apply(result, base, key, &plan.coverage);
            position += 1;
            continue;
        }
        let mut end = position + 1;
        while end < siblings.len() && doc.layers[siblings[end]].clipping {
            end += 1;
        }
        let clips = &siblings[position + 1..end];
        position = end;
        if !base.visible || base.opacity == 0.0 {
            continue;
        }
        if matches!(
            base.content,
            LayerContent::Group {
                isolation: GroupIsolation::PassThrough,
                ..
            }
        ) {
            composite_tree(
                result,
                doc,
                plan,
                &plan.hierarchy.children[index],
                key,
                opaque,
                resolve,
            );
            continue;
        }
        let Some(source) = node_tile(doc, plan, index, key, resolve) else {
            continue;
        };
        let mut edited = if !clips.is_empty() || base.has_enabled_masks() {
            let mut pixels = source.as_ref().clone();
            apply_mask_cached(&mut pixels, base, key, &plan.coverage);
            Some(pixels)
        } else {
            None
        };
        if let Some(pixels) = edited.as_mut() {
            for &clip_index in clips {
                let clip_layer = &doc.layers[clip_index];
                if !clip_layer.visible || clip_layer.opacity == 0.0 {
                    continue;
                }
                if let Some(kernel) = &plan.kernels[clip_index] {
                    kernel.apply(pixels, clip_layer, key, &plan.coverage);
                    continue;
                }
                if let Some(clip) = node_tile(doc, plan, clip_index, key, resolve) {
                    let mut clip = std::borrow::Cow::Borrowed(clip.as_slice());
                    if clip_layer.has_enabled_masks() {
                        apply_mask_cached(clip.to_mut(), clip_layer, key, &plan.coverage);
                    }
                    crate::blending::composite_preserving_alpha(
                        pixels,
                        &clip,
                        (clip_layer.opacity * 255.0).round() as u32,
                        clip_layer.blend,
                    );
                }
            }
        }
        crate::blending::composite(
            result,
            edited.as_deref().unwrap_or(&source),
            (base.opacity * 255.0).round() as u32,
            base.blend,
            opaque,
        );
    }
}

fn composite_layers(
    result: &mut [u8],
    layers: &[Layer],
    palette: Option<&IndexedPalette>,
    key: TileKey,
    opaque: bool,
) {
    composite_groups(result, layers, key, opaque, |layer| {
        layer.rgba_tile(palette, key)
    });
}

fn composite_groups<'a, T: std::ops::Deref>(
    result: &mut [u8],
    layers: &'a [Layer],
    key: TileKey,
    opaque: bool,
    mut resolve: impl FnMut(&'a Layer) -> Option<T>,
) where
    T::Target: AsRef<[u8]>,
{
    let mut index = 0;
    while index < layers.len() {
        let base = &layers[index];
        let mut end = index + 1;
        while end < layers.len() && layers[end].clipping {
            end += 1;
        }
        let group = &layers[index + 1..end];
        index = end;
        if base.clipping || !base.visible || base.opacity == 0.0 {
            continue;
        }
        let Some(source) = resolve(base) else {
            continue;
        };
        let source = source.deref().as_ref();
        let mut edited = if !group.is_empty() || base.has_enabled_masks() {
            let mut pixels = source.to_vec();
            apply_mask(&mut pixels, base, key);
            Some(pixels)
        } else {
            None
        };
        if let Some(pixels) = edited.as_mut() {
            for layer in group
                .iter()
                .filter(|layer| layer.visible && layer.opacity > 0.0)
            {
                if let Some(clip) = resolve(layer) {
                    let mut clip = std::borrow::Cow::Borrowed(clip.deref().as_ref());
                    if layer.has_enabled_masks() {
                        apply_mask(clip.to_mut(), layer, key);
                    }
                    crate::blending::composite_preserving_alpha(
                        pixels,
                        &clip,
                        (layer.opacity * 255.0).round() as u32,
                        layer.blend,
                    );
                }
            }
        }
        crate::blending::composite(
            result,
            edited.as_deref().unwrap_or(source),
            (base.opacity * 255.0).round() as u32,
            base.blend,
            opaque,
        );
    }
}

pub(crate) fn masked_tile(
    layer: &Layer,
    palette: Option<&IndexedPalette>,
    key: TileKey,
) -> Vec<u8> {
    let mut result = layer
        .rgba_tile(palette, key)
        .map_or_else(|| vec![0; TILE_BYTES], |tile| tile.into_owned());
    apply_mask(&mut result, layer, key);
    result
}

fn apply_mask(result: &mut [u8], layer: &Layer, key: TileKey) {
    apply_mask_cached(result, layer, key, &RefCell::default());
}

fn apply_mask_cached(
    result: &mut [u8],
    layer: &Layer,
    key: TileKey,
    cache: &RefCell<crate::mask_stack::CoverageCache>,
) {
    if !layer.has_enabled_masks() {
        return;
    }
    let coverage = cache.borrow_mut().tile(layer, key);
    for (index, pixel) in result.as_chunks_mut::<4>().0.iter_mut().enumerate() {
        let alpha = u32::from(coverage.as_ref().map_or_else(
            || {
                crate::mask_stack::coverage(
                    layer,
                    (key.0 * TILE_SIZE + index as u32 % TILE_SIZE) as i32,
                    (key.1 * TILE_SIZE + index as u32 / TILE_SIZE) as i32,
                )
            },
            |tile| tile[index],
        ));
        for value in pixel {
            *value = ((u32::from(*value) * alpha + 127) / 255) as u8;
        }
    }
}

fn white_background(result: &mut [u8]) {
    for pixel in result.as_chunks_mut::<4>().0 {
        let white = 255 - pixel[3];
        for channel in &mut pixel[..3] {
            *channel = channel.saturating_add(white);
        }
        pixel[3] = 255;
    }
}

pub struct StrokeCompositor {
    lower_count: usize,
    hierarchy: Option<RenderPlan>,
    opaque: bool,
    transparent: bool,
    tiles: BTreeMap<TileKey, Vec<u8>>,
    order: VecDeque<TileKey>,
}

impl StrokeCompositor {
    pub fn new(doc: &Document, transparent: bool) -> Self {
        let hierarchy = Some(RenderPlan::new(doc));
        let mut active = doc
            .layers
            .iter()
            .position(|layer| layer.id == doc.active)
            .unwrap();
        if let Some(plan) = &hierarchy {
            while let Some(parent) = plan.hierarchy.parent[active] {
                active = parent;
            }
        }
        Self {
            lower_count: crate::clipping::base_index(&doc.layers, active),
            hierarchy,
            opaque: !transparent && normal_layers(doc),
            transparent,
            tiles: BTreeMap::new(),
            order: VecDeque::new(),
        }
    }

    pub fn invalidate_mask(&mut self, id: u32) {
        if let Some(plan) = &self.hierarchy {
            plan.coverage.borrow_mut().remove_layer(id);
        }
    }

    pub fn tile(&mut self, doc: &Document, key: TileKey) -> Vec<u8> {
        if self.lower_count == 0 {
            return composite_prepared(
                doc,
                key,
                self.transparent,
                self.hierarchy.as_ref(),
                &mut crate::indexed::RgbaCache::default(),
            );
        }
        if !self.tiles.contains_key(&key) {
            if self.tiles.len() == MAX_STROKE_CACHE_BYTES / TILE_BYTES {
                self.tiles.remove(&self.order.pop_front().unwrap());
            }
            let mut lower = vec![if self.opaque { 255 } else { 0 }; TILE_BYTES];
            if let Some(plan) = &self.hierarchy {
                let split = plan
                    .hierarchy
                    .roots
                    .partition_point(|&index| index < self.lower_count);
                composite_tree(
                    &mut lower,
                    doc,
                    plan,
                    &plan.hierarchy.roots[..split],
                    key,
                    self.opaque,
                    &mut |layer| match &layer.content {
                        LayerContent::Vector(vector) => plan.vectors.lock().ok()?.tile(vector, key),
                        _ => resolve_tile(layer, doc.palette.as_ref(), key),
                    },
                );
            } else {
                composite_layers(
                    &mut lower,
                    &doc.layers[..self.lower_count],
                    doc.palette.as_ref(),
                    key,
                    self.opaque,
                );
            }
            self.tiles.insert(key, lower);
            self.order.push_back(key);
        }
        let mut result = self.tiles[&key].clone();
        if let Some(plan) = &self.hierarchy {
            let split = plan
                .hierarchy
                .roots
                .partition_point(|&index| index < self.lower_count);
            composite_tree(
                &mut result,
                doc,
                plan,
                &plan.hierarchy.roots[split..],
                key,
                self.opaque,
                &mut |layer| match &layer.content {
                    LayerContent::Vector(vector) => plan.vectors.lock().ok()?.tile(vector, key),
                    _ => resolve_tile(layer, doc.palette.as_ref(), key),
                },
            );
        } else {
            composite_layers(
                &mut result,
                &doc.layers[self.lower_count..],
                doc.palette.as_ref(),
                key,
                self.opaque,
            );
        }
        if !self.opaque && !self.transparent {
            white_background(&mut result);
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stroke_cache_is_bounded_and_recreates_evicted_tiles() {
        let mut doc = Document::new(8192, 512).unwrap();
        doc.layers.push(Layer::new(2, "Paint".into()));
        doc.active = 2;
        let mut cache = StrokeCompositor::new(&doc, false);
        for y in 0..4 {
            for x in 0..64 {
                assert_eq!(cache.tile(&doc, (x, y)), composite_tile(&doc, (x, y)));
                assert!(
                    cache.tiles.values().map(Vec::len).sum::<usize>() <= MAX_STROKE_CACHE_BYTES
                );
                assert_eq!(cache.tiles.len(), cache.order.len());
            }
        }
        assert!(!cache.tiles.contains_key(&(0, 0)));
        assert_eq!(cache.tile(&doc, (0, 0)), composite_tile(&doc, (0, 0)));
        assert_eq!(cache.tiles.len() * TILE_BYTES, MAX_STROKE_CACHE_BYTES);
    }

    #[test]
    fn nested_group_stroke_caches_complete_lower_roots_without_splitting_ancestors_or_clip_chains()
    {
        for isolation in [GroupIsolation::Isolated, GroupIsolation::PassThrough] {
            let mut doc = Document::new(8192, 512).unwrap();
            let lower = Layer::group(1, "Lower".into(), GroupIsolation::Isolated);
            let mut paper = Layer::new(2, "Paper".into());
            paper.parent_id = Some(1);
            let painting = Layer::group(3, "Painting".into(), isolation);
            let mut nested = Layer::group(4, "Nested".into(), GroupIsolation::Isolated);
            nested.parent_id = Some(3);
            nested.opacity = 0.7;
            let mut leaf = Layer::new(5, "Paint".into());
            leaf.parent_id = Some(4);
            leaf.blend = BlendMode::Multiply;
            let mut clip = Layer::new(6, "Clip".into());
            clip.parent_id = Some(4);
            clip.clipping = true;
            let paper_pixels = Arc::new([90, 40, 20, 128].repeat((TILE_SIZE * TILE_SIZE) as usize));
            for y in 0..4 {
                for x in 0..64 {
                    paper
                        .raster_mut()
                        .unwrap()
                        .tiles_mut()
                        .insert((x, y), paper_pixels.clone());
                }
            }
            doc.layers = vec![lower, paper, painting, nested, leaf, clip];
            doc.active = 6;
            doc.next_id = 7;
            doc.validate().unwrap();
            for transparent in [false, true] {
                let mut cache = StrokeCompositor::new(&doc, transparent);
                assert_eq!(cache.lower_count, 2);
                for x in 0..65 {
                    let key = (x % 64, x / 64);
                    assert_eq!(
                        cache.tile(&doc, key),
                        composite_tile_background(&doc, key, transparent)
                    );
                    assert!(cache.tiles.len() * TILE_BYTES <= MAX_STROKE_CACHE_BYTES);
                }
                assert_eq!(cache.tiles.len() * TILE_BYTES, MAX_STROKE_CACHE_BYTES);
                doc.layers[4].raster_mut().unwrap().tiles_mut().insert(
                    (0, 0),
                    Arc::new([10, 50, 100, 128].repeat((TILE_SIZE * TILE_SIZE) as usize)),
                );
                doc.layers[5].raster_mut().unwrap().tiles_mut().insert(
                    (0, 0),
                    Arc::new([50, 10, 0, 64].repeat((TILE_SIZE * TILE_SIZE) as usize)),
                );
                assert_eq!(
                    cache.tile(&doc, (0, 0)),
                    composite_tile_background(&doc, (0, 0), transparent)
                );
            }
        }
    }
}
