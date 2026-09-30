use crate::{mask_resample::Kernel, model::*};
use std::collections::{BTreeMap, VecDeque};

pub(crate) struct RasterSampler<'a> {
    layer: &'a Layer,
    width: u32,
    height: u32,
    factor_x: u32,
    factor_y: u32,
    scale_x: f64,
    scale_y: f64,
    cache: BTreeMap<TileKey, Vec<u8>>,
    order: VecDeque<TileKey>,
}

impl<'a> RasterSampler<'a> {
    pub(crate) fn new(layer: &'a Layer, width: u32, height: u32, sx: f64, sy: f64) -> Self {
        let sx = sx.max(1.0);
        let sy = sy.max(1.0);
        let factor_x = sx.floor().clamp(1.0, f64::from(MAX_DIMENSION)) as u32;
        let factor_y = sy.floor().clamp(1.0, f64::from(MAX_DIMENSION)) as u32;
        Self {
            layer,
            width,
            height,
            factor_x,
            factor_y,
            scale_x: sx / f64::from(factor_x),
            scale_y: sy / f64::from(factor_y),
            cache: BTreeMap::new(),
            order: VecDeque::new(),
        }
    }

    pub(crate) fn sample(&mut self, x: f64, y: f64) -> [u8; 4] {
        let horizontal = Kernel::new(x / f64::from(self.factor_x), self.scale_x);
        let vertical = Kernel::new(y / f64::from(self.factor_y), self.scale_y);
        let mut sum = [0.0; 4];
        for (iy, &wy) in vertical.weights[..vertical.length].iter().enumerate() {
            if wy == 0.0 {
                continue;
            }
            for (ix, &wx) in horizontal.weights[..horizontal.length].iter().enumerate() {
                if wx != 0.0 {
                    let pixel =
                        self.pixel(horizontal.start + ix as i32, vertical.start + iy as i32);
                    for channel in 0..4 {
                        sum[channel] += f64::from(pixel[channel]) * wx * wy;
                    }
                }
            }
        }
        let mut pixel = sum.map(|value| {
            (value / (horizontal.sum * vertical.sum))
                .round()
                .clamp(0.0, 255.0) as u8
        });
        for channel in 0..3 {
            pixel[channel] = pixel[channel].min(pixel[3]);
        }
        pixel
    }

    fn pixel(&mut self, x: i32, y: i32) -> [u8; 4] {
        if x < 0
            || y < 0
            || x as u32 >= self.width.div_ceil(self.factor_x)
            || y as u32 >= self.height.div_ceil(self.factor_y)
        {
            return [0; 4];
        }
        let (x, y) = (x as u32, y as u32);
        if self.factor_x == 1 && self.factor_y == 1 {
            return self.source(x, y);
        }
        let key = (x / TILE_SIZE, y / TILE_SIZE);
        if !self.cache.contains_key(&key) {
            let mut tile = if self.cache.len() == MAX_RESAMPLE_CACHE_BYTES / TILE_BYTES {
                self.cache.remove(&self.order.pop_front().unwrap()).unwrap()
            } else {
                vec![0; TILE_BYTES]
            };
            tile.fill(0);
            let width = self.width.div_ceil(self.factor_x);
            let height = self.height.div_ceil(self.factor_y);
            for row in 0..TILE_SIZE.min(height - key.1 * TILE_SIZE) {
                for column in 0..TILE_SIZE.min(width - key.0 * TILE_SIZE) {
                    let left = (key.0 * TILE_SIZE + column) * self.factor_x;
                    let top = (key.1 * TILE_SIZE + row) * self.factor_y;
                    let mut sum = [0_u64; 4];
                    for y in top..(top + self.factor_y).min(self.height) {
                        for x in left..(left + self.factor_x).min(self.width) {
                            let pixel = self.source(x, y);
                            for channel in 0..4 {
                                sum[channel] += u64::from(pixel[channel]);
                            }
                        }
                    }
                    let count = u64::from(self.factor_x) * u64::from(self.factor_y);
                    let pixel = sum.map(|value| ((value + count / 2) / count) as u8);
                    let offset = ((row * TILE_SIZE + column) * 4) as usize;
                    tile[offset..offset + 4].copy_from_slice(&pixel);
                }
            }
            self.cache.insert(key, tile);
            self.order.push_back(key);
        }
        let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
        self.cache[&key][offset..offset + 4].try_into().unwrap()
    }

    fn source(&self, x: u32, y: u32) -> [u8; 4] {
        self.layer
            .raster_opt()
            .and_then(|plane| plane.tiles().get(&(x / TILE_SIZE, y / TILE_SIZE)))
            .map_or([0; 4], |tile| {
                let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                tile[offset..offset + 4].try_into().unwrap()
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn large_checkerboard_minification_preserves_premultiplied_average_with_bounded_cache() {
        let mut document = Document::new(4096, 4096).unwrap();
        let mut tile = vec![0; TILE_BYTES];
        for y in 0..TILE_SIZE {
            for x in 0..TILE_SIZE {
                if (x + y).is_multiple_of(2) {
                    let offset = ((y * TILE_SIZE + x) * 4) as usize;
                    tile[offset..offset + 4].copy_from_slice(&[100, 0, 0, 200]);
                }
            }
        }
        let tile = Arc::new(tile);
        let plane = document.layers[0].raster_mut().unwrap().tiles_mut();
        for y in 0..4096 / TILE_SIZE {
            for x in 0..4096 / TILE_SIZE {
                plane.insert((x, y), tile.clone());
            }
        }
        let mut sampler = RasterSampler::new(&document.layers[0], 4096, 4096, 4096.0, 4096.0);
        assert_eq!(sampler.sample(2048.0, 2048.0), [50, 0, 0, 100]);
        assert_eq!(sampler.cache.len(), 1);
        assert_eq!(sampler.sample(2048.0, 2048.0), [50, 0, 0, 100]);
        assert!(sampler.cache.len() * TILE_BYTES <= MAX_RESAMPLE_CACHE_BYTES);
    }
}
