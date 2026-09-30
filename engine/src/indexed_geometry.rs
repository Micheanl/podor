use crate::{indexed::read_index, model::*, selection::Selection, LayerTransform, ResampleFilter};
use std::{collections::BTreeMap, sync::Arc};

fn put(
    tiles: &mut BTreeMap<TileKey, Tile>,
    transparent: u8,
    x: u32,
    y: u32,
    index: u8,
    budget: usize,
) -> Result<(), String> {
    if index == transparent {
        return Ok(());
    }
    let key = (x / TILE_SIZE, y / TILE_SIZE);
    if !tiles.contains_key(&key) && tiles.len() >= budget {
        return Err("工程像素超过内存限制".into());
    }
    let tile = tiles
        .entry(key)
        .or_insert_with(|| Arc::new(vec![transparent; INDEX_TILE_BYTES]));
    Arc::make_mut(tile)[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize] = index;
    Ok(())
}

pub fn remap(
    layer: &Layer,
    palette: &IndexedPalette,
    source: Rect,
    target: Rect,
    dx: i32,
    dy: i32,
    budget: usize,
) -> Result<BTreeMap<TileKey, Tile>, String> {
    let mut tiles = BTreeMap::new();
    for (&key, tile) in layer.raster()?.tiles() {
        let left = key.0 * TILE_SIZE;
        let top = key.1 * TILE_SIZE;
        let new_left = i64::from(left) + i64::from(dx);
        let new_top = i64::from(top) + i64::from(dy);
        if dx % TILE_SIZE as i32 == 0
            && dy % TILE_SIZE as i32 == 0
            && left + TILE_SIZE <= source.right
            && top + TILE_SIZE <= source.bottom
            && new_left >= 0
            && new_top >= 0
            && new_left + i64::from(TILE_SIZE) <= i64::from(target.right)
            && new_top + i64::from(TILE_SIZE) <= i64::from(target.bottom)
        {
            if tiles.len() >= budget {
                return Err("工程像素超过内存限制".into());
            }
            tiles.insert(
                (new_left as u32 / TILE_SIZE, new_top as u32 / TILE_SIZE),
                tile.clone(),
            );
            continue;
        }
        for row in 0..TILE_SIZE.min(source.bottom - top) {
            let y = i64::from(top + row) + i64::from(dy);
            if y < 0 || y >= i64::from(target.bottom) {
                continue;
            }
            for column in 0..TILE_SIZE.min(source.right - left) {
                let x = i64::from(left + column) + i64::from(dx);
                if x < 0 || x >= i64::from(target.right) {
                    continue;
                }
                put(
                    &mut tiles,
                    palette.transparent,
                    x as u32,
                    y as u32,
                    tile[(row * TILE_SIZE + column) as usize],
                    budget,
                )?;
            }
        }
    }
    Ok(tiles)
}

pub fn translate(
    doc: &Document,
    id: u32,
    dx: i32,
    dy: i32,
    selection: Option<&Selection>,
) -> Result<BTreeMap<TileKey, Tile>, String> {
    let palette = doc.palette.as_ref().unwrap();
    let layer = doc
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .ok_or("图层不存在")?;
    if layer.locked || layer.alpha_locked || !layer.visible {
        return Err("请先显示并解锁当前图层和透明度".into());
    }
    if dx.unsigned_abs() > doc.width || dy.unsigned_abs() > doc.height {
        return Err("移动距离超出画布尺寸".into());
    }
    if dx == 0 && dy == 0 || selection.is_some_and(Selection::is_empty) {
        return Ok(layer.raster()?.tiles().clone());
    }
    if selection.is_some() {
        return Err("索引色选区移动尚未支持，请先取消选区或转换为 RGBA".into());
    }
    let budget = (MAX_DOCUMENT_BYTES - doc.pixel_bytes()
        + layer.raster()?.tiles().len() * INDEX_TILE_BYTES)
        / INDEX_TILE_BYTES;
    remap(layer, palette, doc.bounds(), doc.bounds(), dx, dy, budget)
}

pub fn transform(
    doc: &Document,
    id: u32,
    transform: LayerTransform,
) -> Result<BTreeMap<TileKey, Tile>, String> {
    transform_with_bounds(doc, id, transform, None)
}

pub fn transform_with_bounds(
    doc: &Document,
    id: u32,
    transform: LayerTransform,
    world_bounds: Option<Rect>,
) -> Result<BTreeMap<TileKey, Tile>, String> {
    let palette = doc.palette.as_ref().unwrap();
    let layer = doc
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .ok_or("图层不存在")?;
    if layer.locked || layer.alpha_locked || !layer.visible {
        return Err("请先显示并解锁当前图层和透明度".into());
    }
    if transform.filter != ResampleFilter::Nearest {
        return Err("索引色变换请使用最近邻采样".into());
    }
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
    let content_bounds = crate::transform::bounds(layer, doc.bounds(), Some(palette));
    if world_bounds.is_some() && content_bounds.is_none() {
        return Ok(layer.raster()?.tiles().clone());
    }
    let bounds = world_bounds
        .or(content_bounds)
        .ok_or("当前图层没有可变换的内容")?;
    let width = bounds.right - bounds.left;
    let height = bounds.bottom - bounds.top;
    if (transform.width, transform.height) == (width, height)
        && transform.dx == 0.0
        && transform.dy == 0.0
        && transform.angle % 360.0 == 0.0
        && !transform.flip_x
        && !transform.flip_y
    {
        return Ok(layer.raster()?.tiles().clone());
    }
    let snap = |value: f64| if value.abs() < 1e-12 { 0.0 } else { value };
    let (sin, cos) = transform.angle.to_radians().sin_cos();
    let (sin, cos) = (snap(sin), snap(cos));
    let cx = f64::from(bounds.left) + f64::from(width) / 2.0 + transform.dx;
    let cy = f64::from(bounds.top) + f64::from(height) / 2.0 + transform.dy;
    let half_x = f64::from(transform.width) / 2.0;
    let half_y = f64::from(transform.height) / 2.0;
    let ex = half_x * cos.abs() + half_y * sin.abs();
    let ey = half_x * sin.abs() + half_y * cos.abs();
    let mut area = Rect {
        left: (cx - ex).floor().clamp(0.0, f64::from(doc.width)) as u32,
        top: (cy - ey).floor().clamp(0.0, f64::from(doc.height)) as u32,
        right: (cx + ex).ceil().clamp(0.0, f64::from(doc.width)) as u32,
        bottom: (cy + ey).ceil().clamp(0.0, f64::from(doc.height)) as u32,
    };
    if world_bounds.is_some() {
        area =
            crate::transform::mapped_area(content_bounds.unwrap(), bounds, transform, doc.bounds());
    }
    let budget = (MAX_DOCUMENT_BYTES - doc.pixel_bytes()
        + layer.raster()?.tiles().len() * INDEX_TILE_BYTES)
        / INDEX_TILE_BYTES;
    let mut tiles = BTreeMap::new();
    for y in area.top..area.bottom {
        for x in area.left..area.right {
            let dx = f64::from(x) + 0.5 - cx;
            let dy = f64::from(y) + 0.5 - cy;
            let mut sx = dx * cos + dy * sin;
            let mut sy = -dx * sin + dy * cos;
            if transform.flip_x {
                sx = -sx;
            }
            if transform.flip_y {
                sy = -sy;
            }
            let sx = (sx + half_x) * f64::from(width) / f64::from(transform.width);
            let sy = (sy + half_y) * f64::from(height) / f64::from(transform.height);
            let sx = sx + f64::from(bounds.left);
            let sy = sy + f64::from(bounds.top);
            if sx < 0.0 || sy < 0.0 || sx >= f64::from(doc.width) || sy >= f64::from(doc.height) {
                continue;
            }
            let index = read_index(layer, palette, sx.floor() as u32, sy.floor() as u32);
            put(&mut tiles, palette.transparent, x, y, index, budget)?;
        }
    }
    Ok(tiles)
}

pub fn resize(
    doc: &Document,
    width: u32,
    height: u32,
    filter: ResampleFilter,
) -> Result<Document, String> {
    Document::new(width, height)?;
    if filter != ResampleFilter::Nearest {
        return Err("索引色缩放请使用最近邻采样".into());
    }
    let palette = doc.palette.as_ref().unwrap();
    let mut resized = doc.clone();
    resized.width = width;
    resized.height = height;
    resized.assistants = doc.assistants.affine(
        f64::from(width) / f64::from(doc.width),
        f64::from(height) / f64::from(doc.height),
        0.0,
        0.0,
    )?;
    let mask_bytes = doc
        .layers
        .iter()
        .map(|layer| layer.mask_bytes())
        .sum::<usize>();
    let mut budget = (MAX_DOCUMENT_BYTES - mask_bytes) / INDEX_TILE_BYTES;
    for (source, target) in doc.layers.iter().zip(&mut resized.layers) {
        if source.raster_opt().is_none() {
            continue;
        }
        let mut tiles = BTreeMap::new();
        if !source.raster()?.tiles().is_empty() {
            for y in 0..height {
                let sy =
                    (((f64::from(y) + 0.5) * f64::from(doc.height) / f64::from(height)).floor()
                        as u32)
                        .min(doc.height - 1);
                for x in 0..width {
                    let sx = (((f64::from(x) + 0.5) * f64::from(doc.width) / f64::from(width))
                        .floor() as u32)
                        .min(doc.width - 1);
                    put(
                        &mut tiles,
                        palette.transparent,
                        x,
                        y,
                        read_index(source, palette, sx, sy),
                        budget,
                    )?;
                }
            }
        }
        budget -= tiles.len();
        target.raster_mut()?.set_tiles(tiles);
    }
    Ok(resized)
}
