use crate::model::*;
use std::collections::{BTreeMap, VecDeque};

pub(crate) struct MaskSampler<'a> {
    mask: &'a LayerMask,
    factor_x: u32,
    factor_y: u32,
    scale_x: f64,
    scale_y: f64,
    cache: BTreeMap<TileKey, Vec<u8>>,
    order: VecDeque<TileKey>,
}

impl<'a> MaskSampler<'a> {
    pub(crate) fn new(mask: &'a LayerMask, scale_x: f64, scale_y: f64) -> Self {
        let scale_x = scale_x.max(1.0);
        let scale_y = scale_y.max(1.0);
        let factor_x = scale_x.floor().clamp(1.0, f64::from(MAX_DIMENSION)) as u32;
        let factor_y = scale_y.floor().clamp(1.0, f64::from(MAX_DIMENSION)) as u32;
        Self {
            mask,
            factor_x,
            factor_y,
            scale_x: scale_x / f64::from(factor_x),
            scale_y: scale_y / f64::from(factor_y),
            cache: BTreeMap::new(),
            order: VecDeque::new(),
        }
    }

    pub(crate) fn sample(&mut self, x: f64, y: f64) -> u8 {
        if self.mask.tiles.is_empty() {
            return self.mask.default;
        }
        let x = (x - f64::from(self.mask.bounds.left)) / f64::from(self.factor_x);
        let y = (y - f64::from(self.mask.bounds.top)) / f64::from(self.factor_y);
        let horizontal = Kernel::new(x, self.scale_x);
        let vertical = Kernel::new(y, self.scale_y);
        let mut sum = f64::from(self.mask.default) * horizontal.sum * vertical.sum;
        for (iy, &wy) in vertical.weights[..vertical.length].iter().enumerate() {
            if wy == 0.0 {
                continue;
            }
            for (ix, &wx) in horizontal.weights[..horizontal.length].iter().enumerate() {
                if wx != 0.0 {
                    let value =
                        self.pixel(horizontal.start + ix as i32, vertical.start + iy as i32);
                    sum += (f64::from(value) - f64::from(self.mask.default)) * wx * wy;
                }
            }
        }
        (sum / (horizontal.sum * vertical.sum))
            .round()
            .clamp(0.0, 255.0) as u8
    }

    fn pixel(&mut self, x: i32, y: i32) -> u8 {
        if x < 0
            || y < 0
            || x as u32 >= self.mask.bounds.width().div_ceil(self.factor_x)
            || y as u32 >= self.mask.bounds.height().div_ceil(self.factor_y)
        {
            return self.mask.default;
        }
        if self.factor_x == 1 && self.factor_y == 1 {
            return self
                .mask
                .sample(self.mask.bounds.left + x, self.mask.bounds.top + y);
        }
        let (x, y) = (x as u32, y as u32);
        let key = (x / TILE_SIZE, y / TILE_SIZE);
        if !self.cache.contains_key(&key) {
            let mut tile = if self.cache.len() == MAX_RESAMPLE_CACHE_BYTES / MASK_TILE_BYTES {
                self.cache.remove(&self.order.pop_front().unwrap()).unwrap()
            } else {
                vec![self.mask.default; MASK_TILE_BYTES]
            };
            self.reduce_tile(key, &mut tile);
            self.cache.insert(key, tile);
            self.order.push_back(key);
        }
        self.cache[&key][(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize]
    }

    fn reduce_tile(&self, key: TileKey, tile: &mut [u8]) {
        tile.fill(self.mask.default);
        let width = self.mask.bounds.width().div_ceil(self.factor_x);
        let height = self.mask.bounds.height().div_ceil(self.factor_y);
        for y in 0..TILE_SIZE.min(height - key.1 * TILE_SIZE) {
            for x in 0..TILE_SIZE.min(width - key.0 * TILE_SIZE) {
                let left = (key.0 * TILE_SIZE + x) * self.factor_x;
                let top = (key.1 * TILE_SIZE + y) * self.factor_y;
                tile[(y * TILE_SIZE + x) as usize] = self.average(left, top);
            }
        }
    }

    fn average(&self, left: u32, top: u32) -> u8 {
        let count = u64::from(self.factor_x) * u64::from(self.factor_y);
        let mut sum = i64::from(self.mask.default) * count as i64;
        let right = (left + self.factor_x).min(self.mask.bounds.width());
        let bottom = (top + self.factor_y).min(self.mask.bounds.height());
        for ty in top / TILE_SIZE..bottom.div_ceil(TILE_SIZE) {
            for tx in left / TILE_SIZE..right.div_ceil(TILE_SIZE) {
                let Some(tile) = self.mask.tiles.get(&(tx, ty)) else {
                    continue;
                };
                let begin = left.max(tx * TILE_SIZE);
                let end = right.min((tx + 1) * TILE_SIZE);
                for y in top.max(ty * TILE_SIZE)..bottom.min((ty + 1) * TILE_SIZE) {
                    let start = (y % TILE_SIZE * TILE_SIZE + begin % TILE_SIZE) as usize;
                    let row = &tile[start..start + (end - begin) as usize];
                    sum += row.iter().map(|value| i64::from(*value)).sum::<i64>()
                        - i64::from(self.mask.default) * row.len() as i64;
                }
            }
        }
        ((sum as u64 + count / 2) / count) as u8
    }
}

pub(crate) struct Kernel {
    pub(crate) start: i32,
    pub(crate) length: usize,
    pub(crate) weights: [f64; 14],
    pub(crate) sum: f64,
}

impl Kernel {
    pub(crate) fn new(center: f64, scale: f64) -> Self {
        let start = (center - 0.5 - 3.0 * scale).ceil() as i32;
        let end = (center - 0.5 + 3.0 * scale).floor() as i32;
        let length = (end - start + 1) as usize;
        let mut weights = [0.0; 14];
        for (index, weight) in weights[..length].iter_mut().enumerate() {
            let value = ((f64::from(start + index as i32) + 0.5 - center) / scale).abs();
            *weight = if value < 1e-12 {
                1.0
            } else if value >= 3.0 {
                0.0
            } else {
                let value = value * std::f64::consts::PI;
                value.sin() / value * (value / 3.0).sin() / (value / 3.0)
            };
        }
        Self {
            start,
            length,
            sum: weights[..length].iter().sum(),
            weights,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn mask(width: i32, height: i32, default: u8, tile: Vec<u8>) -> LayerMask {
        let mut mask = LayerMask::new(
            MaskBounds {
                left: -7,
                top: -11,
                right: width - 7,
                bottom: height - 11,
            },
            default,
        );
        let tile = Arc::new(tile);
        for y in 0..(height as u32).div_ceil(TILE_SIZE) {
            for x in 0..(width as u32).div_ceil(TILE_SIZE) {
                mask.tiles.insert((x, y), tile.clone());
            }
        }
        mask
    }

    #[test]
    fn large_checkerboard_minification_retains_average_coverage() {
        let tile: Vec<_> = (0..MASK_TILE_BYTES)
            .map(|i| {
                if (i + i / TILE_SIZE as usize).is_multiple_of(2) {
                    0
                } else {
                    255
                }
            })
            .collect();
        let mask = mask(4096, 4096, 0, tile);
        let mut sampler = MaskSampler::new(&mask, 4096.0, 4096.0);
        assert_eq!(sampler.sample(2048.0 - 7.0, 2048.0 - 11.0), 128);
        assert_eq!(sampler.cache.len(), 1);
        assert_eq!(sampler.sample(2048.0 - 7.0, 2048.0 - 11.0), 128);
        assert_eq!(sampler.cache.len(), 1);
    }

    #[test]
    fn anisotropic_filter_and_partial_cells_include_the_default_background() {
        for default in [0, 255] {
            let mask = mask(109, 1, default, vec![255 - default; MASK_TILE_BYTES]);
            let mut sampler = MaskSampler::new(&mask, 64.0, 1.0);
            assert_eq!(sampler.sample(32.0 - 7.0, 0.5 - 11.0), 255 - default);
            assert_eq!(
                sampler.sample(96.0 - 7.0, 0.5 - 11.0),
                if default == 0 { 179 } else { 76 }
            );
            assert_eq!(sampler.sample(-1000.0, -1000.0), default);
        }
    }

    #[test]
    fn identity_keeps_signed_pixel_centers_and_constant_default_masks() {
        let tile: Vec<_> = (0..MASK_TILE_BYTES).map(|i| (i % 256) as u8).collect();
        let mask = mask(17, 13, 255, tile);
        let mut sampler = MaskSampler::new(&mask, 1.0, 1.0);
        for y in -11..2 {
            for x in -7..10 {
                assert_eq!(
                    sampler.sample(f64::from(x) + 0.5, f64::from(y) + 0.5),
                    mask.sample(x, y)
                );
            }
        }
        assert!(sampler.cache.is_empty());
        let mask = LayerMask::new(mask.bounds, 255);
        assert_eq!(
            MaskSampler::new(&mask, 4096.0, 4096.0).sample(0.0, 0.0),
            255
        );
    }

    #[test]
    fn the_reduced_plane_cache_stays_within_the_shared_resample_budget() {
        let mask = mask(8192, 2048, 0, vec![127; MASK_TILE_BYTES]);
        let mut sampler = MaskSampler::new(&mask, 2.0, 1.0);
        for y in 0..16 {
            for x in 0..32 {
                assert_eq!(
                    sampler.sample(
                        f64::from(x * 256 + 128) - 7.0,
                        f64::from(y * 128 + 64) - 11.0
                    ),
                    127
                );
                assert!(
                    sampler.cache.values().map(Vec::len).sum::<usize>() <= MAX_RESAMPLE_CACHE_BYTES
                );
            }
        }
        assert_eq!(
            sampler.cache.len(),
            MAX_RESAMPLE_CACHE_BYTES / MASK_TILE_BYTES
        );
    }
}
