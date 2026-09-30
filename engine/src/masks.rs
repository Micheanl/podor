use crate::{
    color_selection::ColorSelection,
    model::*,
    selection::{Selection, SelectionPoint},
};
use std::{
    collections::{BTreeSet, HashMap, HashSet, VecDeque},
    sync::Arc,
};

#[derive(Clone, Copy, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MaskMode {
    Reveal,
    Hide,
    Selection,
}

pub fn gray(color: [u8; 3]) -> u8 {
    ((u32::from(color[0]) * 77 + u32::from(color[1]) * 150 + u32::from(color[2]) * 29 + 128) / 256)
        as u8
}

pub fn create(
    doc: &Document,
    selection: Option<&Selection>,
    mode: MaskMode,
) -> Result<LayerMask, String> {
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: doc.width as i32,
        bottom: doc.height as i32,
    };
    let mut mask = LayerMask::new(
        bounds,
        if matches!(mode, MaskMode::Reveal) {
            255
        } else {
            0
        },
    );
    if matches!(mode, MaskMode::Selection) {
        let selected = selection.ok_or("请先创建选区")?;
        let region = selected.bounds();
        for y in region.top..region.bottom {
            for x in region.left..region.right {
                put(&mut mask, x as i32, y as i32, selected.coverage(x, y))?;
            }
        }
    }
    Ok(mask)
}

pub fn check_transaction(before: &Document, after: &Document) -> Result<(), String> {
    after.validate()?;
    let live: HashSet<_> = after.resources().map(|(ptr, _)| ptr).collect();
    let retained: HashMap<_, _> = before
        .resources()
        .filter(|(ptr, _)| !live.contains(ptr))
        .collect();
    if retained.values().sum::<usize>() > MAX_HISTORY_BYTES {
        return Err("操作会超出撤销内存限制".into());
    }
    Ok(())
}

fn put(mask: &mut LayerMask, x: i32, y: i32, value: u8) -> Result<bool, String> {
    if mask.sample(x, y) == value {
        return Ok(false);
    }
    let x = (x - mask.bounds.left) as u32;
    let y = (y - mask.bounds.top) as u32;
    let key = (x / TILE_SIZE, y / TILE_SIZE);
    if !mask.tiles.contains_key(&key) && mask.tiles.len() >= MAX_DOCUMENT_BYTES / MASK_TILE_BYTES {
        return Err("蒙版超过内存限制".into());
    }
    let tile = mask
        .tiles
        .entry(key)
        .or_insert_with(|| Arc::new(vec![mask.default; MASK_TILE_BYTES]));
    Arc::make_mut(tile)[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize] = value;
    Ok(true)
}

pub fn reframe(source: &LayerMask, bounds: MaskBounds) -> Result<LayerMask, String> {
    bounds.validate()?;
    if source.bounds == bounds {
        return Ok(source.clone());
    }
    let mut output = LayerMask {
        bounds,
        tiles: Default::default(),
        ..source.clone()
    };
    for (&(tx, ty), tile) in &source.tiles {
        for y in 0..TILE_SIZE.min(source.bounds.height() - ty * TILE_SIZE) {
            for x in 0..TILE_SIZE.min(source.bounds.width() - tx * TILE_SIZE) {
                let dx = source.bounds.left + (tx * TILE_SIZE + x) as i32;
                let dy = source.bounds.top + (ty * TILE_SIZE + y) as i32;
                if dx >= bounds.left && dx < bounds.right && dy >= bounds.top && dy < bounds.bottom
                {
                    put(&mut output, dx, dy, tile[(y * TILE_SIZE + x) as usize])?;
                }
            }
        }
    }
    Ok(output)
}

fn editing_mask(doc: &Document) -> Result<LayerMask, String> {
    crate::groups::check_editable(doc, doc.active, false)?;
    let layer = doc
        .layers
        .iter()
        .find(|layer| layer.id == doc.active)
        .unwrap();
    if layer.locked {
        return Err("图层已锁定，请先解锁".into());
    }
    if !layer.visible {
        return Err("请先显示当前图层".into());
    }
    let mask = &doc.selected_mask().ok_or("当前图层没有蒙版")?.plane;
    reframe(
        mask,
        MaskBounds {
            left: mask.bounds.left.min(0),
            top: mask.bounds.top.min(0),
            right: mask.bounds.right.max(doc.width as i32),
            bottom: mask.bounds.bottom.max(doc.height as i32),
        },
    )
}

fn finish(
    doc: &mut Document,
    mut mask: LayerMask,
    modified: bool,
    changed: Option<&BTreeSet<TileKey>>,
) -> Result<bool, String> {
    if !modified {
        return Ok(false);
    }
    if let Some(changed) = changed {
        for key in changed {
            if mask
                .tiles
                .get(key)
                .is_some_and(|tile| tile.iter().all(|&value| value == mask.default))
            {
                mask.tiles.remove(key);
            }
        }
    } else {
        mask.tiles
            .retain(|_, tile| tile.iter().any(|&value| value != mask.default));
    }
    let old_bytes = doc
        .selected_mask()
        .map_or(0, |mask| mask.plane.tiles.len() * MASK_TILE_BYTES);
    if doc.pixel_bytes() - old_bytes + mask.tiles.len() * MASK_TILE_BYTES > MAX_DOCUMENT_BYTES {
        return Err("工程像素超过内存限制".into());
    }
    doc.selected_mask_mut().ok_or("当前图层没有蒙版")?.plane = mask;
    Ok(true)
}

pub fn stamp(
    doc: &mut Document,
    selection: Option<&Selection>,
    brush: Brush,
    point: Sample,
    dirty: &mut BTreeSet<TileKey>,
) -> Result<bool, String> {
    if brush.smudge {
        return Err("蒙版不支持涂抹笔".into());
    }
    let opacity = brush.opacity_at_pressure(point.pressure);
    if opacity == 0.0 {
        return Ok(false);
    }
    let (dabs, count) = crate::dab::Dab::symmetric(doc.bounds(), selection, brush, point);
    let mut mask = editing_mask(doc)?;
    let value = if brush.eraser { 0 } else { gray(brush.color) };
    let mut changed = BTreeSet::new();
    let mut changed_mask = BTreeSet::new();
    for dab in &dabs[..count] {
        for y in dab.bounds.top..dab.bounds.bottom {
            for x in dab.bounds.left..dab.bounds.right {
                let selected = selection.map_or(255, |selection| selection.coverage(x, y));
                let selected = if brush.raster == BrushRaster::Antialiased {
                    selected
                } else {
                    u8::from(selected >= 128) * 255
                };
                let coverage = dabs[..count]
                    .iter()
                    .filter(|part| part.bounds.contains(x, y))
                    .fold(0.0f32, |value, part| {
                        value.max(part.coverage::<false>(x, y))
                    });
                if dabs[..count]
                    .iter()
                    .position(|part| part.bounds.contains(x, y))
                    != dabs[..count]
                        .iter()
                        .position(|part| std::ptr::eq(part, dab))
                {
                    continue;
                }
                let alpha = (coverage * opacity * f32::from(selected)).round() as u8;
                let old = mask.sample(x as i32, y as i32);
                let next = crate::selection::mix(old, value, alpha);
                if put(&mut mask, x as i32, y as i32, next)? {
                    changed.insert((x / TILE_SIZE, y / TILE_SIZE));
                    changed_mask.insert((
                        (x as i32 - mask.bounds.left) as u32 / TILE_SIZE,
                        (y as i32 - mask.bounds.top) as u32 / TILE_SIZE,
                    ));
                }
            }
        }
    }
    let modified = finish(doc, mask, !changed.is_empty(), Some(&changed_mask))?;
    dirty.extend(changed);
    Ok(modified)
}

pub fn fill(
    doc: &mut Document,
    selection: Option<&Selection>,
    settings: ColorSelection,
    color: [u8; 4],
    dirty: &mut BTreeSet<TileKey>,
) -> Result<bool, String> {
    if settings.merged {
        return Err("蒙版填色不支持合并图层取样".into());
    }
    if !doc.bounds().contains(settings.x, settings.y) {
        return Err("填充位置超出画布".into());
    }
    if selection.is_some_and(|selection| selection.coverage(settings.x, settings.y) == 0) {
        return Err("填充位置不在选区内".into());
    }
    let mut output = editing_mask(doc)?;
    let source = output.clone();
    if color[3] == 0 {
        return Ok(false);
    }
    let target = source.sample(settings.x as i32, settings.y as i32);
    let mut matched = vec![false; doc.width as usize * doc.height as usize];
    for y in 0..doc.height {
        for x in 0..doc.width {
            matched[(y * doc.width + x) as usize] =
                source.sample(x as i32, y as i32).abs_diff(target) <= settings.tolerance
                    && selection.is_none_or(|selection| selection.coverage(x, y) > 0);
        }
    }
    if settings.contiguous {
        let mut visited = vec![false; matched.len()];
        let seed = (settings.y * doc.width + settings.x) as usize;
        let mut queue = VecDeque::from([seed]);
        visited[seed] = true;
        while let Some(index) = queue.pop_front() {
            let x = index % doc.width as usize;
            for next in [
                index.checked_sub(doc.width as usize),
                (index + (doc.width as usize) < matched.len())
                    .then_some(index + doc.width as usize),
                (x > 0).then(|| index - 1),
                (x + 1 < doc.width as usize).then_some(index + 1),
            ]
            .into_iter()
            .flatten()
            {
                if matched[next] && !visited[next] {
                    if queue.len() >= MAX_SELECTION_FLOOD_BYTES / size_of::<usize>() {
                        return Err("连续区域过于复杂".into());
                    }
                    visited[next] = true;
                    queue.push_back(next);
                }
            }
        }
        matched = visited;
    }
    let value = gray(color[..3].try_into().unwrap());
    let mut changed = BTreeSet::new();
    for y in 0..doc.height {
        for x in 0..doc.width {
            if matched[(y * doc.width + x) as usize] {
                let coverage = selection.map_or(255, |selection| selection.coverage(x, y));
                let alpha = ((u32::from(color[3]) * u32::from(coverage) + 127) / 255) as u8;
                let old = source.sample(x as i32, y as i32);
                if put(
                    &mut output,
                    x as i32,
                    y as i32,
                    crate::selection::mix(old, value, alpha),
                )? {
                    changed.insert((x / TILE_SIZE, y / TILE_SIZE));
                }
            }
        }
    }
    let modified = finish(doc, output, !changed.is_empty(), None)?;
    dirty.extend(changed);
    Ok(modified)
}

pub fn lasso(
    doc: &mut Document,
    selection: Option<&Selection>,
    points: Vec<SelectionPoint>,
    color: [u8; 3],
    opacity: f32,
    eraser: bool,
    dirty: &mut BTreeSet<TileKey>,
) -> Result<bool, String> {
    if !opacity.is_finite()
        || !(0.0..=1.0).contains(&opacity)
        || points.len() > MAX_SELECTION_POINTS
        || points.iter().any(|point| {
            !point.x.is_finite()
                || !point.y.is_finite()
                || point.x.abs().max(point.y.abs()) > MAX_DIMENSION as f32 * 2.0
        })
    {
        return Err("套索填色参数无效".into());
    }
    let mut output = editing_mask(doc)?;
    if opacity == 0.0 || points.len() < 3 {
        return Ok(false);
    }
    let shape = Selection::lasso_mask(points, doc.bounds());
    let value = if eraser { 0 } else { gray(color) };
    let mut changed = BTreeSet::new();
    for y in 0..doc.height {
        for x in 0..doc.width {
            let coverage = (u32::from(shape.coverage(x, y))
                * u32::from(selection.map_or(255, |s| s.coverage(x, y)))
                + 127)
                / 255;
            let alpha = (opacity * coverage as f32).round() as u8;
            let old = output.sample(x as i32, y as i32);
            if put(
                &mut output,
                x as i32,
                y as i32,
                crate::selection::mix(old, value, alpha),
            )? {
                changed.insert((x / TILE_SIZE, y / TILE_SIZE));
            }
        }
    }
    let modified = finish(doc, output, !changed.is_empty(), None)?;
    dirty.extend(changed);
    Ok(modified)
}

pub fn offset(mask: &LayerMask, dx: i32, dy: i32) -> Result<LayerMask, String> {
    let mut result = mask.clone();
    result.bounds = MaskBounds {
        left: mask.bounds.left.checked_add(dx).ok_or("蒙版移动越界")?,
        top: mask.bounds.top.checked_add(dy).ok_or("蒙版移动越界")?,
        right: mask.bounds.right.checked_add(dx).ok_or("蒙版移动越界")?,
        bottom: mask.bounds.bottom.checked_add(dy).ok_or("蒙版移动越界")?,
    };
    result.validate()?;
    Ok(result)
}

pub fn translate_selected(
    _doc: &Document,
    source: &LayerMask,
    dx: i32,
    dy: i32,
    selection: Option<&Selection>,
) -> Result<LayerMask, String> {
    let Some(selection) = selection else {
        return offset(source, dx, dy);
    };
    if selection.is_empty() || (dx == 0 && dy == 0) {
        return Ok(source.clone());
    }
    let region = selection.bounds();
    let shifted = MaskBounds {
        left: (region.left as i32).checked_add(dx).ok_or("蒙版移动越界")?,
        top: (region.top as i32).checked_add(dy).ok_or("蒙版移动越界")?,
        right: (region.right as i32)
            .checked_add(dx)
            .ok_or("蒙版移动越界")?,
        bottom: (region.bottom as i32)
            .checked_add(dy)
            .ok_or("蒙版移动越界")?,
    };
    let bounds = MaskBounds {
        left: source.bounds.left.min(region.left as i32).min(shifted.left),
        top: source.bounds.top.min(region.top as i32).min(shifted.top),
        right: source
            .bounds
            .right
            .max(region.right as i32)
            .max(shifted.right),
        bottom: source
            .bounds
            .bottom
            .max(region.bottom as i32)
            .max(shifted.bottom),
    };
    let mut output = reframe(source, bounds)?;
    for y in region.top..region.bottom {
        for x in region.left..region.right {
            let old = source.sample(x as i32, y as i32);
            put(
                &mut output,
                x as i32,
                y as i32,
                crate::selection::mix(old, source.default, selection.coverage(x, y)),
            )?;
        }
    }
    for y in region.top..region.bottom {
        for x in region.left..region.right {
            let destx = x as i32 + dx;
            let desty = y as i32 + dy;
            let old = output.sample(destx, desty);
            put(
                &mut output,
                destx,
                desty,
                crate::selection::mix(
                    old,
                    source.sample(x as i32, y as i32),
                    selection.coverage(x, y),
                ),
            )?;
        }
    }
    output
        .tiles
        .retain(|_, tile| tile.iter().any(|&value| value != output.default));
    Ok(output)
}

pub fn affine(
    mask: &LayerMask,
    reference: MaskBounds,
    settings: crate::LayerTransform,
) -> Result<LayerMask, String> {
    if reference.width() == 0 || reference.height() == 0 {
        return Err("蒙版没有可变换的内容".into());
    }
    Document::new(settings.width, settings.height)?;
    if !settings.dx.is_finite()
        || !settings.dy.is_finite()
        || !settings.angle.is_finite()
        || settings.dx.abs() > MAX_TRANSFORM_OFFSET
        || settings.dy.abs() > MAX_TRANSFORM_OFFSET
        || settings.angle.abs() > 360.0
    {
        return Err("蒙版变换参数无效".into());
    }
    let sx = f64::from(settings.width) / f64::from(reference.width());
    let sy = f64::from(settings.height) / f64::from(reference.height());
    let cx = (f64::from(reference.left) + f64::from(reference.right)) / 2.0;
    let cy = (f64::from(reference.top) + f64::from(reference.bottom)) / 2.0;
    if sx == 1.0
        && sy == 1.0
        && settings.dx == 0.0
        && settings.dy == 0.0
        && settings.angle % 360.0 == 0.0
        && !settings.flip_x
        && !settings.flip_y
    {
        return Ok(mask.clone());
    }
    let snap = |value: f64| if value.abs() < 1e-12 { 0.0 } else { value };
    let (sin, cos) = (
        snap(settings.angle.to_radians().sin()),
        snap(settings.angle.to_radians().cos()),
    );
    let forward = |x: i32, y: i32| {
        let mut x = (f64::from(x) - cx) * sx;
        let mut y = (f64::from(y) - cy) * sy;
        if settings.flip_x {
            x = -x;
        }
        if settings.flip_y {
            y = -y;
        }
        (
            cx + settings.dx + x * cos - y * sin,
            cy + settings.dy + x * sin + y * cos,
        )
    };
    let corners = [
        forward(mask.bounds.left, mask.bounds.top),
        forward(mask.bounds.right, mask.bounds.top),
        forward(mask.bounds.left, mask.bounds.bottom),
        forward(mask.bounds.right, mask.bounds.bottom),
    ];
    let bounds = MaskBounds {
        left: corners
            .iter()
            .map(|p| p.0)
            .fold(f64::INFINITY, f64::min)
            .floor() as i32,
        top: corners
            .iter()
            .map(|p| p.1)
            .fold(f64::INFINITY, f64::min)
            .floor() as i32,
        right: corners
            .iter()
            .map(|p| p.0)
            .fold(f64::NEG_INFINITY, f64::max)
            .ceil() as i32,
        bottom: corners
            .iter()
            .map(|p| p.1)
            .fold(f64::NEG_INFINITY, f64::max)
            .ceil() as i32,
    };
    bounds.validate()?;
    let mut result = LayerMask {
        bounds,
        tiles: Default::default(),
        ..mask.clone()
    };
    if mask.tiles.is_empty() {
        return Ok(result);
    }
    let mut sampler =
        crate::mask_resample::MaskSampler::new(mask, (1.0 / sx).max(1.0), (1.0 / sy).max(1.0));
    for y in bounds.top..bounds.bottom {
        for x in bounds.left..bounds.right {
            let dx = f64::from(x) + 0.5 - cx - settings.dx;
            let dy = f64::from(y) + 0.5 - cy - settings.dy;
            let mut sourcex = dx * cos + dy * sin;
            let mut sourcey = -dx * sin + dy * cos;
            if settings.flip_x {
                sourcex = -sourcex;
            }
            if settings.flip_y {
                sourcey = -sourcey;
            }
            let sourcex = sourcex / sx + cx;
            let sourcey = sourcey / sy + cy;
            let value = if settings.filter == crate::ResampleFilter::Nearest {
                mask.sample(sourcex.floor() as i32, sourcey.floor() as i32)
            } else {
                sampler.sample(sourcex, sourcey)
            };
            put(&mut result, x, y, value)?;
        }
    }
    Ok(result)
}
