use crate::{animation::CelSource, model::*, Command, LayerTransform, ResampleFilter};
use std::{collections::HashMap, collections::HashSet, sync::Arc};

#[derive(Clone, Copy)]
enum Mapping {
    Offset(i32, i32),
    Affine(Rect, LayerTransform),
}

struct Reservation {
    live: HashMap<usize, (usize, usize)>,
    allocated: usize,
}

impl Reservation {
    fn new(document: &Document) -> Self {
        let mut result = Self {
            live: HashMap::new(),
            allocated: 0,
        };
        for resource in document.resources() {
            result.keep(resource);
        }
        result
    }

    fn keep(&mut self, (id, bytes): (usize, usize)) {
        self.live.entry(id).or_insert((0, bytes)).0 += 1;
    }

    fn remove(&mut self, (id, _): (usize, usize)) {
        if let Some(entry) = self.live.get_mut(&id) {
            entry.0 -= 1;
            if entry.0 == 0 {
                self.live.remove(&id);
            }
        }
    }

    fn allocate(&mut self, bytes: usize) -> Result<(), String> {
        self.allocated = add(self.allocated, bytes)?;
        if self.allocated > MAX_DOCUMENT_BYTES {
            return Err("所有动画源的变换结果超过内存限制".into());
        }
        Ok(())
    }

    fn finish(&self, document: &Document) -> Result<(), String> {
        let live = self
            .live
            .values()
            .try_fold(self.allocated, |total, (_, bytes)| add(total, *bytes))?;
        if live > MAX_DOCUMENT_BYTES {
            return Err("所有动画源的变换结果超过内存限制".into());
        }
        let retained: HashMap<_, _> = document
            .resources()
            .filter(|(id, _)| !self.live.contains_key(id))
            .collect();
        if retained
            .values()
            .try_fold(0, |total, bytes| add(total, *bytes))?
            > MAX_HISTORY_BYTES
        {
            return Err("变换所有动画源会超过撤销内存限制".into());
        }
        Ok(())
    }
}

fn add(left: usize, right: usize) -> Result<usize, String> {
    left.checked_add(right)
        .ok_or_else(|| "动画资源大小超出限制".into())
}

fn mul(left: usize, right: usize) -> Result<usize, String> {
    left.checked_mul(right)
        .ok_or_else(|| "动画资源大小超出限制".into())
}

pub(crate) fn reserve(
    document: &Document,
    command: &Command,
    mask_editing: bool,
) -> Result<(), String> {
    let Some(animation) = &document.animation else {
        return Ok(());
    };
    let mut affected = None;
    let mut canvas = document.bounds();
    let mut resize_image = false;
    let mapping = match command {
        Command::ResizeCanvas {
            width,
            height,
            anchor,
            ..
        } => {
            Document::new(*width, *height)?;
            if *anchor >= 9 {
                return Err("画布定位无效".into());
            }
            if (*width, *height) == (document.width, document.height) {
                return Ok(());
            }
            canvas.right = *width;
            canvas.bottom = *height;
            Mapping::Offset(
                (*width as i32 - document.width as i32) * i32::from(anchor % 3) / 2,
                (*height as i32 - document.height as i32) * i32::from(anchor / 3) / 2,
            )
        }
        Command::ResizeImage {
            width,
            height,
            filter,
            ..
        } => {
            Document::new(*width, *height)?;
            if (*width, *height) == (document.width, document.height) {
                return Ok(());
            }
            resize_image = true;
            canvas.right = *width;
            canvas.bottom = *height;
            Mapping::Affine(
                document.bounds(),
                LayerTransform {
                    width: *width,
                    height: *height,
                    dx: (f64::from(*width) - f64::from(document.width)) / 2.0,
                    dy: (f64::from(*height) - f64::from(document.height)) / 2.0,
                    angle: 0.0,
                    flip_x: false,
                    flip_y: false,
                    filter: *filter,
                },
            )
        }
        Command::TranslateLayer { id, dx, dy, .. } if !mask_editing => {
            let Some(ids) = group_ids(document, *id)? else {
                return Ok(());
            };
            affected = Some(ids);
            if dx.unsigned_abs() > document.width || dy.unsigned_abs() > document.height {
                return Err("移动距离超出画布尺寸".into());
            }
            if *dx == 0 && *dy == 0 {
                return Ok(());
            }
            Mapping::Offset(*dx, *dy)
        }
        Command::TransformLayer { id, transform, .. } if !mask_editing => {
            let Some(ids) = group_ids(document, *id)? else {
                return Ok(());
            };
            affected = Some(ids);
            validate_transform(*transform)?;
            let current = crate::animation::view(document, animation.active_frame)?;
            let world = crate::groups::bounds(&current, *id)?;
            if identity(world, *transform) {
                return Ok(());
            }
            Mapping::Affine(world, *transform)
        }
        _ => return Ok(()),
    };
    let changed = |id| affected.as_ref().is_none_or(|ids| ids.contains(&id));
    let mut budget = Reservation::new(document);
    budget.remove((Arc::as_ptr(animation) as usize, animation.metadata_bytes()));
    if affected.is_none() && !document.assistants.items.is_empty() {
        budget.remove((
            Arc::as_ptr(&document.assistants) as usize,
            document.assistants.bytes(),
        ));
        budget.allocate(document.assistants.bytes())?;
    }
    let mut metadata = animation.metadata_bytes();
    let mut sources = HashMap::new();
    let mut occupied = HashMap::new();
    for cel in animation.cels.values().filter(|cel| changed(cel.layer_id)) {
        for tile in cel.buffers() {
            budget.remove((Arc::as_ptr(tile) as usize, tile.len()));
        }
        for resource in cel.vector_resources() {
            budget.remove(resource);
        }
        let old = cel_metadata(&cel.masks)?;
        let mut own = std::mem::size_of::<crate::animation::Cel>();
        for mask in &cel.masks {
            let count = reserve_mask(&mask.plane, mapping, resize_image, &mut budget)?;
            own = add(own, add(mask.name.len(), mul(count, 64)?)?)?;
        }
        metadata = add(metadata.checked_sub(old).ok_or("动画元数据大小无效")?, own)?;
        budget.allocate(own)?;
        let id = match &cel.source {
            CelSource::Raster(source) => (Arc::as_ptr(source) as usize, 0),
            CelSource::Vector(source) => (Arc::as_ptr(source) as usize, 1),
        };
        sources.entry(id).or_insert(&cel.source);
    }
    for (&(id, _), source) in &sources {
        match source {
            CelSource::Raster(source) => {
                let old = add(
                    std::mem::size_of::<RasterPlane>(),
                    mul(source.tiles().len(), 64)?,
                )?;
                if !budget.live.contains_key(&id) {
                    metadata = metadata.checked_sub(old).ok_or("动画元数据大小无效")?;
                }
                let count = reserve_raster(
                    source,
                    document,
                    canvas,
                    mapping,
                    affected.is_some(),
                    &mut occupied,
                    &mut budget,
                )?;
                let own = add(std::mem::size_of::<RasterPlane>(), mul(count, 64)?)?;
                metadata = add(metadata, own)?;
                budget.allocate(own)?;
            }
            CelSource::Vector(source) => budget.allocate(source.bytes())?,
        }
    }
    for layer in document.layers.iter().filter(|layer| changed(layer.id)) {
        for mask in &layer.masks {
            for tile in mask.plane.tiles.values() {
                budget.remove((Arc::as_ptr(tile) as usize, tile.len()));
            }
            reserve_mask(&mask.plane, mapping, resize_image, &mut budget)?;
        }
    }
    if metadata > MAX_ANIMATION_METADATA_BYTES {
        return Err("变换后的动画元数据超过限制".into());
    }
    budget.allocate(metadata)?;
    budget.finish(document)
}

fn group_ids(document: &Document, id: u32) -> Result<Option<HashSet<u32>>, String> {
    let Some(index) = document
        .layers
        .iter()
        .position(|layer| layer.id == id && layer.is_group())
    else {
        return Ok(None);
    };
    let hierarchy = crate::groups::Hierarchy::new(document)?;
    Ok(Some(
        document.layers[index..hierarchy.end[index]]
            .iter()
            .map(|layer| layer.id)
            .collect(),
    ))
}

fn validate_transform(transform: LayerTransform) -> Result<(), String> {
    Document::new(transform.width, transform.height)?;
    if !transform.dx.is_finite()
        || !transform.dy.is_finite()
        || !transform.angle.is_finite()
        || transform.dx.abs() > MAX_TRANSFORM_OFFSET
        || transform.dy.abs() > MAX_TRANSFORM_OFFSET
        || transform.angle.abs() > 360.0
    {
        return Err("图层变换参数无效".into());
    }
    Ok(())
}

fn identity(world: Rect, transform: LayerTransform) -> bool {
    transform.width == world.right - world.left
        && transform.height == world.bottom - world.top
        && transform.dx == 0.0
        && transform.dy == 0.0
        && transform.angle % 360.0 == 0.0
        && !transform.flip_x
        && !transform.flip_y
}

fn cel_metadata(masks: &[MaskEntry]) -> Result<usize, String> {
    masks.iter().try_fold(
        std::mem::size_of::<crate::animation::Cel>(),
        |total, mask| {
            add(
                total,
                add(mask.name.len(), mul(mask.plane.tiles.len(), 64)?)?,
            )
        },
    )
}

fn insert_area(keys: &mut HashSet<TileKey>, area: Rect, bytes: usize) -> Result<(), String> {
    if area.left >= area.right || area.top >= area.bottom {
        return Ok(());
    }
    for y in area.top / TILE_SIZE..area.bottom.div_ceil(TILE_SIZE) {
        for x in area.left / TILE_SIZE..area.right.div_ceil(TILE_SIZE) {
            keys.insert((x, y));
            if mul(keys.len(), bytes)? > MAX_DOCUMENT_BYTES {
                return Err("动画源的映射范围超过内存限制".into());
            }
        }
    }
    Ok(())
}

fn reserve_raster(
    source: &RasterPlane,
    document: &Document,
    canvas: Rect,
    mapping: Mapping,
    group: bool,
    occupied: &mut HashMap<usize, Option<Rect>>,
    budget: &mut Reservation,
) -> Result<usize, String> {
    let old_canvas = document.bounds();
    let mut allocated = HashSet::new();
    let mut reused = 0usize;
    let empty_group = group
        && matches!(mapping, Mapping::Affine(..))
        && source.tiles().values().all(|tile| {
            occupied
                .entry(Arc::as_ptr(tile) as usize)
                .or_insert_with(|| occupied_bounds(tile, document.palette.as_ref()))
                .is_none()
        });
    for (&(x, y), tile) in source.tiles() {
        if empty_group {
            budget.keep((Arc::as_ptr(tile) as usize, tile.len()));
            reused = add(reused, 1)?;
            continue;
        }
        let left = x.checked_mul(TILE_SIZE).ok_or("像素块坐标无效")?;
        let top = y.checked_mul(TILE_SIZE).ok_or("像素块坐标无效")?;
        let right = left.checked_add(TILE_SIZE).ok_or("像素块坐标无效")?;
        let bottom = top.checked_add(TILE_SIZE).ok_or("像素块坐标无效")?;
        let Some(tile_area) = (Rect {
            left,
            top,
            right,
            bottom,
        })
        .intersect(old_canvas) else {
            continue;
        };
        if let Mapping::Offset(dx, dy) = mapping {
            let target_left = i64::from(left) + i64::from(dx);
            let target_top = i64::from(top) + i64::from(dy);
            if dx % TILE_SIZE as i32 == 0
                && dy % TILE_SIZE as i32 == 0
                && tile_area.right == right
                && tile_area.bottom == bottom
                && target_left >= 0
                && target_top >= 0
                && target_left + i64::from(TILE_SIZE) <= i64::from(canvas.right)
                && target_top + i64::from(TILE_SIZE) <= i64::from(canvas.bottom)
            {
                budget.keep((Arc::as_ptr(tile) as usize, tile.len()));
                reused = add(reused, 1)?;
                continue;
            }
        }
        let Some(local) = *occupied
            .entry(Arc::as_ptr(tile) as usize)
            .or_insert_with(|| occupied_bounds(tile, document.palette.as_ref()))
        else {
            continue;
        };
        let Some(content) = (Rect {
            left: left.checked_add(local.left).ok_or("像素块坐标无效")?,
            top: top.checked_add(local.top).ok_or("像素块坐标无效")?,
            right: left.checked_add(local.right).ok_or("像素块坐标无效")?,
            bottom: top.checked_add(local.bottom).ok_or("像素块坐标无效")?,
        })
        .intersect(old_canvas) else {
            continue;
        };
        let area = match mapping {
            Mapping::Offset(dx, dy) => Rect {
                left: (i64::from(content.left) + i64::from(dx)).clamp(0, i64::from(canvas.right))
                    as u32,
                top: (i64::from(content.top) + i64::from(dy)).clamp(0, i64::from(canvas.bottom))
                    as u32,
                right: (i64::from(content.right) + i64::from(dx)).clamp(0, i64::from(canvas.right))
                    as u32,
                bottom: (i64::from(content.bottom) + i64::from(dy))
                    .clamp(0, i64::from(canvas.bottom)) as u32,
            },
            Mapping::Affine(world, transform) => {
                crate::transform::mapped_area(content, world, transform, canvas)
            }
        };
        insert_area(&mut allocated, area, source.tile_bytes())?;
    }
    budget.allocate(mul(allocated.len(), source.tile_bytes())?)?;
    add(reused, allocated.len())
}

fn occupied_bounds(tile: &[u8], palette: Option<&IndexedPalette>) -> Option<Rect> {
    let mut bounds = Rect {
        left: TILE_SIZE,
        top: TILE_SIZE,
        right: 0,
        bottom: 0,
    };
    for y in 0..TILE_SIZE {
        for x in 0..TILE_SIZE {
            let offset = (y * TILE_SIZE + x) as usize;
            if match palette {
                Some(palette) => tile[offset] != palette.transparent,
                None => tile[offset * 4 + 3] != 0,
            } {
                bounds.left = bounds.left.min(x);
                bounds.top = bounds.top.min(y);
                bounds.right = bounds.right.max(x + 1);
                bounds.bottom = bounds.bottom.max(y + 1);
            }
        }
    }
    (bounds.left < bounds.right && bounds.top < bounds.bottom).then_some(bounds)
}

fn reserve_mask(
    mask: &LayerMask,
    mapping: Mapping,
    resize_image: bool,
    budget: &mut Reservation,
) -> Result<usize, String> {
    let Mapping::Affine(world, transform) = mapping else {
        if let Mapping::Offset(dx, dy) = mapping {
            if mask.linked {
                MaskBounds {
                    left: mask.bounds.left.checked_add(dx).ok_or("蒙版移动越界")?,
                    top: mask.bounds.top.checked_add(dy).ok_or("蒙版移动越界")?,
                    right: mask.bounds.right.checked_add(dx).ok_or("蒙版移动越界")?,
                    bottom: mask.bounds.bottom.checked_add(dy).ok_or("蒙版移动越界")?,
                }
                .validate()?;
            }
        }
        for tile in mask.tiles.values() {
            budget.keep((Arc::as_ptr(tile) as usize, tile.len()));
        }
        return Ok(mask.tiles.len());
    };
    if !resize_image && !mask.linked || identity(world, transform) {
        for tile in mask.tiles.values() {
            budget.keep((Arc::as_ptr(tile) as usize, tile.len()));
        }
        return Ok(mask.tiles.len());
    }
    let mapped = |bounds: [f64; 4], margin: bool| {
        let sx = f64::from(transform.width) / f64::from(world.right - world.left);
        let sy = f64::from(transform.height) / f64::from(world.bottom - world.top);
        let cx = (f64::from(world.left) + f64::from(world.right)) / 2.0;
        let cy = (f64::from(world.top) + f64::from(world.bottom)) / 2.0;
        let (sin, cos) = transform.angle.to_radians().sin_cos();
        let snap = |value: f64| if value.abs() < 1e-12 { 0.0 } else { value };
        let (sin, cos) = (snap(sin), snap(cos));
        let mut result = [
            f64::INFINITY,
            f64::INFINITY,
            f64::NEG_INFINITY,
            f64::NEG_INFINITY,
        ];
        for x in [bounds[0], bounds[2]] {
            for y in [bounds[1], bounds[3]] {
                let x = (x - cx) * sx * if transform.flip_x { -1.0 } else { 1.0 };
                let y = (y - cy) * sy * if transform.flip_y { -1.0 } else { 1.0 };
                let px = cx + transform.dx + x * cos - y * sin;
                let py = cy + transform.dy + x * sin + y * cos;
                result[0] = result[0].min(px);
                result[1] = result[1].min(py);
                result[2] = result[2].max(px);
                result[3] = result[3].max(py);
            }
        }
        if margin && transform.filter != ResampleFilter::Nearest {
            let ex = (3.0 * sx.max(1.0) + 1.0) * cos.abs() + (3.0 * sy.max(1.0) + 1.0) * sin.abs();
            let ey = (3.0 * sx.max(1.0) + 1.0) * sin.abs() + (3.0 * sy.max(1.0) + 1.0) * cos.abs();
            result[0] -= ex;
            result[1] -= ey;
            result[2] += ex;
            result[3] += ey;
        }
        result
    };
    let full = mapped(
        [
            f64::from(mask.bounds.left),
            f64::from(mask.bounds.top),
            f64::from(mask.bounds.right),
            f64::from(mask.bounds.bottom),
        ],
        false,
    );
    let bounds = MaskBounds {
        left: full[0].floor() as i32,
        top: full[1].floor() as i32,
        right: full[2].ceil() as i32,
        bottom: full[3].ceil() as i32,
    };
    bounds.validate()?;
    let mut keys = HashSet::new();
    for &(x, y) in mask.tiles.keys() {
        let left = x.checked_mul(TILE_SIZE).ok_or("蒙版块坐标无效")?;
        let top = y.checked_mul(TILE_SIZE).ok_or("蒙版块坐标无效")?;
        let right = left
            .checked_add(TILE_SIZE)
            .ok_or("蒙版块坐标无效")?
            .min(mask.bounds.width());
        let bottom = top
            .checked_add(TILE_SIZE)
            .ok_or("蒙版块坐标无效")?
            .min(mask.bounds.height());
        let area = mapped(
            [
                f64::from(mask.bounds.left) + f64::from(left),
                f64::from(mask.bounds.top) + f64::from(top),
                f64::from(mask.bounds.left) + f64::from(right),
                f64::from(mask.bounds.top) + f64::from(bottom),
            ],
            true,
        );
        insert_area(
            &mut keys,
            Rect {
                left: (area[0].floor() - f64::from(bounds.left))
                    .clamp(0.0, f64::from(bounds.width())) as u32,
                top: (area[1].floor() - f64::from(bounds.top))
                    .clamp(0.0, f64::from(bounds.height())) as u32,
                right: (area[2].ceil() - f64::from(bounds.left))
                    .clamp(0.0, f64::from(bounds.width())) as u32,
                bottom: (area[3].ceil() - f64::from(bounds.top))
                    .clamp(0.0, f64::from(bounds.height())) as u32,
            },
            MASK_TILE_BYTES,
        )?;
    }
    budget.allocate(mul(keys.len(), MASK_TILE_BYTES)?)?;
    Ok(keys.len())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::animation::{AnimationSet, Cel, CelKind, Frame};
    use serde_json::json;
    use std::collections::BTreeMap;

    fn document(shared: bool) -> Document {
        let mut doc = Document::new(8192, 2048).unwrap();
        doc.layers[0].content = LayerContent::CelTrack {
            kind: CelKind::Raster,
        };
        let tile = Arc::new([0, 0, 0, 255].repeat(TILE_BYTES / 4));
        let raster = Arc::new(RasterPlane::Rgba(
            (0..1024)
                .map(|key| ((key % 64, key / 64), tile.clone()))
                .collect(),
        ));
        let mut frames = vec![Frame {
            id: 1,
            duration_ms: 100,
            exposures: BTreeMap::new(),
        }];
        let mut cels = BTreeMap::new();
        for id in 1..=3 {
            cels.insert(
                id,
                Arc::new(Cel {
                    id,
                    layer_id: 1,
                    source: CelSource::Raster(if shared {
                        raster.clone()
                    } else {
                        Arc::new(raster.as_ref().clone())
                    }),
                    masks: vec![],
                }),
            );
            frames.push(Frame {
                id: id + 1,
                duration_ms: 100,
                exposures: BTreeMap::from([(1, id)]),
            });
        }
        doc.animation = Some(Arc::new(AnimationSet {
            frames,
            cels,
            active_frame: 1,
            next_frame_id: 5,
            next_cel_id: 4,
            next_tag_id: 1,
            tags: vec![],
        }));
        doc.validate().unwrap();
        doc
    }

    fn command(value: serde_json::Value) -> Command {
        serde_json::from_value(value).unwrap()
    }

    #[test]
    fn reserve_rejects_all_inactive_output_before_rasterization() {
        let doc = document(false);
        assert!(doc.pixel_bytes() < 1024 * 1024);
        let saved = crate::storage::save(&doc).unwrap();
        let request = command(
            json!({"type":"resize_image","width":4096,"height":4096,"filter":"nearest","revision":0}),
        );
        assert!(reserve(&doc, &request, false).is_err());
        assert!(crate::storage::save(&doc).unwrap() == saved);
    }

    #[test]
    fn reserve_counts_a_shared_source_once_and_aligned_tiles_by_buffer_identity() {
        let request = command(
            json!({"type":"resize_image","width":4096,"height":4096,"filter":"nearest","revision":0}),
        );
        reserve(&document(true), &request, false).unwrap();
        let crop = command(
            json!({"type":"resize_canvas","width":8064,"height":2048,"anchor":0,"revision":0}),
        );
        reserve(&document(false), &crop, false).unwrap();
    }

    #[test]
    fn sparse_masks_use_the_transformed_origin_and_do_not_reserve_blank_bounds() {
        let mut doc = document(true);
        let animation = Arc::make_mut(doc.animation.as_mut().unwrap());
        for cel in animation.cels.values_mut() {
            Arc::make_mut(cel).source =
                CelSource::Raster(Arc::new(RasterPlane::Rgba(BTreeMap::new())));
        }
        let tile = Arc::new(vec![0; MASK_TILE_BYTES]);
        for (index, cel) in animation.cels.values_mut().take(2).enumerate() {
            for offset in 0..16 {
                let mut plane = LayerMask::new(
                    MaskBounds {
                        left: -64,
                        top: -64,
                        right: 8128,
                        bottom: 1984,
                    },
                    255,
                );
                plane.linked = false;
                plane.tiles.insert((0, 0), tile.clone());
                Arc::make_mut(cel).masks.push(MaskEntry {
                    id: (index * 16 + offset + 1) as u32,
                    name: "Mask".into(),
                    plane,
                });
            }
        }
        doc.next_mask_id = 33;
        doc.validate().unwrap();
        let transform = LayerTransform {
            width: 4096,
            height: 4096,
            dx: -2048.0,
            dy: 1024.0,
            angle: 0.0,
            flip_x: false,
            flip_y: false,
            filter: ResampleFilter::Nearest,
        };
        let mut budget = Reservation::new(&doc);
        let mask = &doc.animation.as_ref().unwrap().cels[&1].masks[0].plane;
        assert_eq!(
            reserve_mask(
                mask,
                Mapping::Affine(doc.bounds(), transform),
                true,
                &mut budget
            )
            .unwrap(),
            2
        );
        let request = command(
            json!({"type":"resize_image","width":4096,"height":4096,"filter":"nearest","revision":0}),
        );
        reserve(&doc, &request, false).unwrap();
    }

    #[test]
    fn group_reservation_uses_occupied_pixels_and_preserves_foreign_sources() {
        let mut doc = document(false);
        let mut leaf = doc.layers[0].clone();
        leaf.id = 3;
        leaf.parent_id = Some(2);
        doc.layers = vec![
            Layer::group(2, "Group".into(), GroupIsolation::Isolated),
            leaf,
        ];
        doc.active = 2;
        doc.next_id = 4;
        let mut bytes = vec![0; TILE_BYTES];
        bytes[((3 * TILE_SIZE + 2) * 4 + 3) as usize] = 255;
        let tile = Arc::new(bytes);
        let animation = Arc::make_mut(doc.animation.as_mut().unwrap());
        for cel in animation.cels.values_mut() {
            let cel = Arc::make_mut(cel);
            cel.layer_id = 3;
            cel.source = CelSource::Raster(Arc::new(RasterPlane::Rgba(BTreeMap::from([(
                (0, 0),
                tile.clone(),
            )]))));
        }
        for frame in &mut animation.frames {
            frame.exposures = frame.exposures.values().map(|id| (3, *id)).collect();
        }
        let mut outside = Layer::new(4, "Outside".into());
        outside.content = LayerContent::CelTrack {
            kind: crate::animation::CelKind::Raster,
        };
        doc.layers.push(outside);
        doc.next_id = 5;
        let outside_tile = Arc::new([255, 0, 0, 255].repeat(TILE_BYTES / 4));
        animation.cels.insert(
            4,
            Arc::new(Cel {
                id: 4,
                layer_id: 4,
                source: CelSource::Raster(Arc::new(RasterPlane::Rgba(
                    (0..1024)
                        .map(|key| ((key % 64, key / 64), outside_tile.clone()))
                        .collect(),
                ))),
                masks: vec![],
            }),
        );
        for frame in &mut animation.frames {
            frame.exposures.insert(4, 4);
        }
        animation.next_cel_id = 5;
        animation.active_frame = 2;
        doc.validate().unwrap();
        let request = command(
            json!({"type":"transform_layer","id":2,"revision":0,"transform":{"width":128,"height":128,"filter":"nearest"}}),
        );
        reserve(&doc, &request, false).unwrap();
        let invalid = command(
            json!({"type":"resize_canvas","width":8192,"height":2048,"anchor":9,"revision":0}),
        );
        assert!(reserve(&doc, &invalid, false).is_err());
    }
}
