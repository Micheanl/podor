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

pub fn bounds(layer: &Layer, canvas: Rect) -> Option<Rect> {
    let mut area = Rect {
        left: canvas.right,
        top: canvas.bottom,
        right: 0,
        bottom: 0,
    };
    for (&(tx, ty), pixels) in &layer.tiles {
        for y in 0..TILE_SIZE.min(canvas.bottom - ty * TILE_SIZE) {
            for x in 0..TILE_SIZE.min(canvas.right - tx * TILE_SIZE) {
                if pixels[((y * TILE_SIZE + x) * 4 + 3) as usize] != 0 {
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

pub fn prepare(
    doc: &Document,
    id: u32,
    transform: LayerTransform,
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
    let bounds = bounds(layer, doc.bounds()).ok_or("当前图层没有可变换的内容")?;
    let width = bounds.right - bounds.left;
    let height = bounds.bottom - bounds.top;
    if (transform.width, transform.height) == (width, height)
        && transform.dx == 0.0
        && transform.dy == 0.0
        && transform.angle % 360.0 == 0.0
        && !transform.flip_x
        && !transform.flip_y
    {
        return Ok(layer.tiles.clone());
    }
    let shared: HashSet<_> = doc
        .layers
        .iter()
        .filter(|other| other.id != id)
        .flat_map(|other| other.tiles.values())
        .map(Arc::as_ptr)
        .collect();
    let retained: HashSet<_> = layer
        .tiles
        .values()
        .map(Arc::as_ptr)
        .filter(|pointer| !shared.contains(pointer))
        .collect();
    if retained.len() * TILE_BYTES > MAX_HISTORY_BYTES {
        return Err("图层变换会超出撤销内存限制".into());
    }
    let scaled = {
        let mut cropped = Document::new(width, height)?;
        let mut source = layer.clone();
        source.tiles = translation::remap(
            layer,
            doc.bounds(),
            cropped.bounds(),
            -(bounds.left as i32),
            -(bounds.top as i32),
            MAX_DOCUMENT_BYTES / TILE_BYTES,
        )?;
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
    let area = Rect {
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
    let mut sampler = Sampler {
        layer: &scaled.layers[0],
        width: scaled.width,
        height: scaled.height,
        key: (u32::MAX, u32::MAX),
        pixels: None,
    };
    let budget = MAX_DOCUMENT_BYTES / TILE_BYTES - (doc.tile_count() - layer.tiles.len());
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
            self.pixels = self.layer.tiles.get(&key).map(|tile| tile.as_slice());
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
