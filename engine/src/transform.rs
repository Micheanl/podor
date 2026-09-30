use crate::{model::*, resample, translation, ResampleFilter};
use serde::Deserialize;
use std::{
    collections::{BTreeMap, HashSet},
    sync::Arc,
};

#[derive(Clone, Copy, Deserialize)]
pub struct LayerTransform {
    pub width: u32,
    pub height: u32,
    #[serde(default)]
    pub dx: f64,
    #[serde(default)]
    pub dy: f64,
    #[serde(default)]
    pub angle: f64,
    #[serde(default)]
    pub flip_x: bool,
    #[serde(default)]
    pub flip_y: bool,
    #[serde(default)]
    pub filter: ResampleFilter,
}

pub fn bounds(layer: &Layer, canvas: Rect, palette: Option<&IndexedPalette>) -> Option<Rect> {
    if let Ok(vector) = layer.vector() {
        return crate::vector::bounds(vector, canvas);
    }
    let mut area = Rect {
        left: canvas.right,
        top: canvas.bottom,
        right: 0,
        bottom: 0,
    };
    let raster = layer.raster_opt()?;
    for (&(tx, ty), pixels) in raster.tiles() {
        for y in 0..TILE_SIZE.min(canvas.bottom - ty * TILE_SIZE) {
            for x in 0..TILE_SIZE.min(canvas.right - tx * TILE_SIZE) {
                let occupied = if raster.is_indexed() {
                    pixels[(y * TILE_SIZE + x) as usize] != palette.unwrap().transparent
                } else {
                    pixels[((y * TILE_SIZE + x) * 4 + 3) as usize] != 0
                };
                if occupied {
                    let x = tx * TILE_SIZE + x;
                    let y = ty * TILE_SIZE + y;
                    area.left = area.left.min(x);
                    area.top = area.top.min(y);
                    area.right = area.right.max(x + 1);
                    area.bottom = area.bottom.max(y + 1);
                }
            }
        }
    }
    (area.left < area.right && area.top < area.bottom).then_some(area)
}

pub fn mapped_area(content: Rect, world: Rect, transform: LayerTransform, canvas: Rect) -> Rect {
    let ratio_x = f64::from(transform.width) / f64::from(world.right - world.left);
    let ratio_y = f64::from(transform.height) / f64::from(world.bottom - world.top);
    let cx = f64::from(world.left) + f64::from(world.right - world.left) / 2.0;
    let cy = f64::from(world.top) + f64::from(world.bottom - world.top) / 2.0;
    let (sin, cos) = transform.angle.to_radians().sin_cos();
    let mut left = f64::INFINITY;
    let mut top = f64::INFINITY;
    let mut right = f64::NEG_INFINITY;
    let mut bottom = f64::NEG_INFINITY;
    for x in [content.left, content.right] {
        for y in [content.top, content.bottom] {
            let sx = (f64::from(x) - cx) * ratio_x * if transform.flip_x { -1.0 } else { 1.0 };
            let sy = (f64::from(y) - cy) * ratio_y * if transform.flip_y { -1.0 } else { 1.0 };
            let dx = cx + transform.dx + sx * cos - sy * sin;
            let dy = cy + transform.dy + sx * sin + sy * cos;
            left = left.min(dx);
            right = right.max(dx);
            top = top.min(dy);
            bottom = bottom.max(dy);
        }
    }
    let (margin_x, margin_y) = if transform.filter == ResampleFilter::Nearest {
        (0.0, 0.0)
    } else {
        (3.0 * ratio_x.max(1.0) + 1.0, 3.0 * ratio_y.max(1.0) + 1.0)
    };
    let ex = margin_x * cos.abs() + margin_y * sin.abs();
    let ey = margin_x * sin.abs() + margin_y * cos.abs();
    Rect {
        left: (left - ex).floor().clamp(0.0, f64::from(canvas.right)) as u32,
        top: (top - ey).floor().clamp(0.0, f64::from(canvas.bottom)) as u32,
        right: (right + ex).ceil().clamp(0.0, f64::from(canvas.right)) as u32,
        bottom: (bottom + ey).ceil().clamp(0.0, f64::from(canvas.bottom)) as u32,
    }
}

pub fn prepare(
    doc: &Document,
    id: u32,
    transform: LayerTransform,
) -> Result<BTreeMap<TileKey, Tile>, String> {
    prepare_with_bounds(doc, id, transform, None)
}

pub fn prepare_with_bounds(
    doc: &Document,
    id: u32,
    transform: LayerTransform,
    world_bounds: Option<Rect>,
) -> Result<BTreeMap<TileKey, Tile>, String> {
    if doc.palette.is_some() {
        return if world_bounds.is_some() {
            crate::indexed_geometry::transform_with_bounds(doc, id, transform, world_bounds)
        } else {
            crate::indexed_geometry::transform(doc, id, transform)
        };
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
    let content_bounds = bounds(layer, doc.bounds(), doc.palette.as_ref());
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
    let shared: HashSet<_> = doc
        .layers
        .iter()
        .filter(|other| other.id != id)
        .flat_map(|other| other.raster_buffers())
        .map(Arc::as_ptr)
        .collect();
    let retained: HashSet<_> = layer
        .raster()?
        .tiles()
        .values()
        .map(Arc::as_ptr)
        .filter(|pointer| !shared.contains(pointer))
        .collect();
    if retained.len() * TILE_BYTES > MAX_HISTORY_BYTES {
        return Err("图层变换会超出撤销内存限制".into());
    }
    if world_bounds.is_some()
        && content_bounds.is_some_and(|content| {
            content.left < bounds.left
                || content.top < bounds.top
                || content.right > bounds.right
                || content.bottom > bounds.bottom
        })
    {
        return prepare_unclipped(doc, layer, bounds, content_bounds.unwrap(), transform);
    }
    let scaled = {
        let mut cropped = Document::new(width, height)?;
        let mut source = layer.clone();
        source.parent_id = None;
        source.clipping = false;
        source.raster_mut()?.set_tiles(translation::remap(
            layer,
            doc.bounds(),
            cropped.bounds(),
            -(bounds.left as i32),
            -(bounds.top as i32),
            MAX_DOCUMENT_BYTES / TILE_BYTES,
        )?);
        cropped.layers = vec![source];
        cropped.active = id;
        cropped.next_id = doc.next_id;
        if (width, height) == (transform.width, transform.height) {
            cropped
        } else {
            resample::resize_pixels(
                &cropped,
                transform.width,
                transform.height,
                transform.filter,
            )?
        }
    };
    let angle = transform.angle.to_radians();
    let snap = |v: f64| if v.abs() < 1e-12 { 0.0 } else { v };
    let (sin, cos) = (snap(angle.sin()), snap(angle.cos()));
    let center_x = f64::from(bounds.left) + f64::from(width) / 2.0 + transform.dx;
    let center_y = f64::from(bounds.top) + f64::from(height) / 2.0 + transform.dy;
    let margin = if transform.filter == ResampleFilter::Nearest {
        0.0
    } else {
        0.5
    };
    let half_x = f64::from(transform.width) / 2.0;
    let half_y = f64::from(transform.height) / 2.0;
    let extent_x = (half_x + margin) * cos.abs() + (half_y + margin) * sin.abs();
    let extent_y = (half_x + margin) * sin.abs() + (half_y + margin) * cos.abs();
    let mut area = Rect {
        left: (center_x - extent_x)
            .floor()
            .clamp(0.0, f64::from(doc.width)) as u32,
        top: (center_y - extent_y)
            .floor()
            .clamp(0.0, f64::from(doc.height)) as u32,
        right: (center_x + extent_x)
            .ceil()
            .clamp(0.0, f64::from(doc.width)) as u32,
        bottom: (center_y + extent_y)
            .ceil()
            .clamp(0.0, f64::from(doc.height)) as u32,
    };
    if world_bounds.is_some() {
        let Some(occupied) = area.intersect(mapped_area(
            content_bounds.unwrap(),
            bounds,
            transform,
            doc.bounds(),
        )) else {
            return Ok(BTreeMap::new());
        };
        area = occupied;
    }
    let mut sampler = Sampler {
        layer: &scaled.layers[0],
        width: scaled.width,
        height: scaled.height,
        key: (u32::MAX, u32::MAX),
        pixels: None,
    };
    let budget =
        MAX_DOCUMENT_BYTES / TILE_BYTES - (doc.tile_count() - layer.raster()?.tiles().len());
    let mut output = BTreeMap::new();
    for ty in area.top / TILE_SIZE..area.bottom.div_ceil(TILE_SIZE) {
        for tx in area.left / TILE_SIZE..area.right.div_ceil(TILE_SIZE) {
            let mut tile: Option<Vec<u8>> = None;
            for y in area.top.max(ty * TILE_SIZE)..area.bottom.min((ty + 1) * TILE_SIZE) {
                for x in area.left.max(tx * TILE_SIZE)..area.right.min((tx + 1) * TILE_SIZE) {
                    let dx = f64::from(x) + 0.5 - center_x;
                    let dy = f64::from(y) + 0.5 - center_y;
                    let mut sx = dx * cos + dy * sin;
                    let mut sy = -dx * sin + dy * cos;
                    if transform.flip_x {
                        sx = -sx;
                    }
                    if transform.flip_y {
                        sy = -sy;
                    }
                    let pixel = sampler.sample(sx + half_x, sy + half_y, transform.filter);
                    if pixel[3] == 0 {
                        continue;
                    }
                    let bytes = tile.get_or_insert_with(|| vec![0; TILE_BYTES]);
                    let index = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                    bytes[index..index + 4].copy_from_slice(&pixel);
                }
            }
            if let Some(tile) = tile {
                if output.len() >= budget {
                    return Err("工程像素超过内存限制".into());
                }
                output.insert((tx, ty), Arc::new(tile));
            }
        }
    }
    Ok(output)
}

fn prepare_unclipped(
    doc: &Document,
    layer: &Layer,
    world: Rect,
    content: Rect,
    settings: LayerTransform,
) -> Result<BTreeMap<TileKey, Tile>, String> {
    let sx = f64::from(settings.width) / f64::from(world.right - world.left);
    let sy = f64::from(settings.height) / f64::from(world.bottom - world.top);
    let cx = f64::from(world.left) + f64::from(world.right - world.left) / 2.0;
    let cy = f64::from(world.top) + f64::from(world.bottom - world.top) / 2.0;
    let snap = |value: f64| if value.abs() < 1e-12 { 0.0 } else { value };
    let (sin, cos) = settings.angle.to_radians().sin_cos();
    let (sin, cos) = (snap(sin), snap(cos));
    let area = mapped_area(content, world, settings, doc.bounds());
    let mut nearest = Sampler {
        layer,
        width: doc.width,
        height: doc.height,
        key: (u32::MAX, u32::MAX),
        pixels: None,
    };
    let mut smooth = crate::raster_resample::RasterSampler::new(
        layer,
        doc.width,
        doc.height,
        1.0 / sx,
        1.0 / sy,
    );
    let budget =
        MAX_DOCUMENT_BYTES / TILE_BYTES - (doc.tile_count() - layer.raster()?.tiles().len());
    let mut output = BTreeMap::new();
    for ty in area.top / TILE_SIZE..area.bottom.div_ceil(TILE_SIZE) {
        for tx in area.left / TILE_SIZE..area.right.div_ceil(TILE_SIZE) {
            let mut tile: Option<Vec<u8>> = None;
            for y in area.top.max(ty * TILE_SIZE)..area.bottom.min((ty + 1) * TILE_SIZE) {
                for x in area.left.max(tx * TILE_SIZE)..area.right.min((tx + 1) * TILE_SIZE) {
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
                    let pixel = if settings.filter == ResampleFilter::Nearest {
                        nearest.pixel(sourcex.floor() as i32, sourcey.floor() as i32)
                    } else {
                        smooth.sample(sourcex, sourcey)
                    };
                    if pixel[3] == 0 {
                        continue;
                    }
                    let bytes = tile.get_or_insert_with(|| vec![0; TILE_BYTES]);
                    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                    bytes[offset..offset + 4].copy_from_slice(&pixel);
                }
            }
            if let Some(tile) = tile {
                if output.len() >= budget {
                    return Err("工程像素超过内存限制".into());
                }
                output.insert((tx, ty), Arc::new(tile));
            }
        }
    }
    Ok(output)
}

struct Sampler<'a> {
    layer: &'a Layer,
    width: u32,
    height: u32,
    key: TileKey,
    pixels: Option<&'a [u8]>,
}

impl Sampler<'_> {
    fn pixel(&mut self, x: i32, y: i32) -> [u8; 4] {
        if x < 0 || y < 0 || x as u32 >= self.width || y as u32 >= self.height {
            return [0; 4];
        }
        let (x, y) = (x as u32, y as u32);
        let key = (x / TILE_SIZE, y / TILE_SIZE);
        if self.key != key {
            self.key = key;
            self.pixels = self
                .layer
                .raster_opt()
                .and_then(|raster| raster.tiles().get(&key))
                .map(|tile| tile.as_slice());
        }
        self.pixels.map_or([0; 4], |pixels| {
            let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            pixels[i..i + 4].try_into().unwrap()
        })
    }

    fn sample(&mut self, x: f64, y: f64, filter: ResampleFilter) -> [u8; 4] {
        if filter == ResampleFilter::Nearest {
            return self.pixel(x.floor() as i32, y.floor() as i32);
        }
        let (x, y) = (x - 0.5, y - 0.5);
        let (ix, iy) = (x.floor() as i32, y.floor() as i32);
        let (fx, fy) = (x - f64::from(ix), y - f64::from(iy));
        let a = self.pixel(ix, iy);
        if fx == 0.0 && fy == 0.0 {
            return a;
        }
        let b = self.pixel(ix + 1, iy);
        let c = self.pixel(ix, iy + 1);
        let d = self.pixel(ix + 1, iy + 1);
        std::array::from_fn(|i| {
            let top = f64::from(a[i]) * (1.0 - fx) + f64::from(b[i]) * fx;
            let bottom = f64::from(c[i]) * (1.0 - fx) + f64::from(d[i]) * fx;
            (top * (1.0 - fy) + bottom * fy).round().clamp(0.0, 255.0) as u8
        })
    }
}
