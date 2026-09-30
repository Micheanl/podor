use crate::{dab::Dab, model::*, selection::Selection};
use std::{collections::BTreeSet, sync::Arc};

pub(crate) struct Smudge {
    size: usize,
    previous: Vec<u8>,
    next: Vec<u8>,
    origin: (i32, i32),
    point: Option<Sample>,
}

impl Smudge {
    pub fn new(brush: Brush) -> Result<Self, String> {
        let size = (brush.size * std::f32::consts::SQRT_2).ceil() as usize + 4;
        let bytes = size * size * 4;
        if bytes * 2 > MAX_SMUDGE_CACHE_BYTES {
            return Err("涂抹笔尖过大".into());
        }
        Ok(Self {
            size,
            previous: vec![0; bytes],
            next: vec![0; bytes],
            origin: (0, 0),
            point: None,
        })
    }

    fn capture(&mut self, layer: &Layer, canvas: Rect, origin: (i32, i32)) -> Result<(), String> {
        let raster = layer.raster()?;
        if raster.is_indexed() {
            return Err("索引色涂抹尚未支持，请先转换为 RGBA".into());
        }
        self.next.fill(0);
        let left = origin.0.max(0) as u32;
        let top = origin.1.max(0) as u32;
        let right = (origin.0 + self.size as i32)
            .max(0)
            .min(canvas.right as i32) as u32;
        let bottom = (origin.1 + self.size as i32)
            .max(0)
            .min(canvas.bottom as i32) as u32;
        if left >= right || top >= bottom {
            return Ok(());
        }
        for ty in top / TILE_SIZE..bottom.div_ceil(TILE_SIZE) {
            for tx in left / TILE_SIZE..right.div_ceil(TILE_SIZE) {
                let Some(tile) = raster.tiles().get(&(tx, ty)) else {
                    continue;
                };
                let start = left.max(tx * TILE_SIZE);
                let count = (right.min((tx + 1) * TILE_SIZE) - start) as usize * 4;
                for y in top.max(ty * TILE_SIZE)..bottom.min((ty + 1) * TILE_SIZE) {
                    let source = ((y % TILE_SIZE * TILE_SIZE + start % TILE_SIZE) * 4) as usize;
                    let target = ((y as i32 - origin.1) as usize * self.size
                        + (start as i32 - origin.0) as usize)
                        * 4;
                    self.next[target..target + count]
                        .copy_from_slice(&tile[source..source + count]);
                }
            }
        }
        Ok(())
    }

    fn sample(
        &self,
        x: f32,
        y: f32,
        canvas: Rect,
        selection: Option<&Selection>,
        pixel: bool,
    ) -> Option<[f32; 4]> {
        let ix = (x + if pixel { 0.5 } else { 0.0 }).floor() as i32;
        let iy = (y + if pixel { 0.5 } else { 0.0 }).floor() as i32;
        let fx = if pixel { 0.0 } else { x - ix as f32 };
        let fy = if pixel { 0.0 } else { y - iy as f32 };
        let mut color = [0.0; 4];
        let mut weight = 0.0;
        for (dy, wy) in [(0, 1.0 - fy), (1, fy)] {
            for (dx, wx) in [(0, 1.0 - fx), (1, fx)] {
                let sx = ix + dx;
                let sy = iy + dy;
                if sx < 0 || sy < 0 || sx >= self.size as i32 || sy >= self.size as i32 {
                    continue;
                }
                let px = sx + self.origin.0;
                let py = sy + self.origin.1;
                if px < 0 || py < 0 || !canvas.contains(px as u32, py as u32) {
                    continue;
                }
                let coverage = selection.map_or(255, |s| s.coverage(px as u32, py as u32));
                let coverage = if pixel {
                    u8::from(coverage >= 128) * 255
                } else {
                    coverage
                };
                let w = wx * wy * f32::from(coverage) / 255.0;
                let offset = (sy as usize * self.size + sx as usize) * 4;
                for (c, value) in color.iter_mut().enumerate() {
                    *value += f32::from(self.previous[offset + c]) * w;
                }
                weight += w;
            }
        }
        if weight <= f32::EPSILON {
            None
        } else {
            Some(color.map(|v| v / weight))
        }
    }

    pub fn stamp(
        &mut self,
        doc: &mut Document,
        selection: Option<&Selection>,
        brush: Brush,
        point: Sample,
        dirty: &mut BTreeSet<TileKey>,
        remaining: &mut usize,
    ) -> Result<bool, String> {
        let index = crate::groups::check_editable(doc, doc.active, true)?;
        let canvas = doc.bounds();
        let pixel = brush.raster != BrushRaster::Antialiased;
        let half = self.size as f32 * 0.5;
        let origin = (
            (point.x - half).floor() as i32,
            (point.y - half).floor() as i32,
        );
        self.capture(&doc.layers[index], canvas, origin)?;
        let mut changed = false;
        if let Some(previous) = self.point {
            let opacity = brush.opacity_at_pressure(point.pressure);
            let dab = Dab::new(canvas, selection, brush, point);
            let bounds = dab.bounds;
            if bounds.left < bounds.right && bounds.top < bounds.bottom && opacity > 0.0 {
                let layer = doc.active_mut();
                let alpha_locked = layer.alpha_locked;
                let raster = layer.raster_mut()?;
                for ty in bounds.top / TILE_SIZE..bounds.bottom.div_ceil(TILE_SIZE) {
                    for tx in bounds.left / TILE_SIZE..bounds.right.div_ceil(TILE_SIZE) {
                        let mut tile_changed = false;
                        for y in
                            bounds.top.max(ty * TILE_SIZE)..bounds.bottom.min((ty + 1) * TILE_SIZE)
                        {
                            for x in bounds.left.max(tx * TILE_SIZE)
                                ..bounds.right.min((tx + 1) * TILE_SIZE)
                            {
                                let selected = selection.map_or(255, |s| s.coverage(x, y));
                                let selected = if pixel {
                                    u8::from(selected >= 128) * 255
                                } else {
                                    selected
                                };
                                let amount =
                                    dab.coverage::<false>(x, y) * opacity * f32::from(selected)
                                        / 255.0;
                                if amount == 0.0 {
                                    continue;
                                }
                                let index = ((y as i32 - origin.1) as usize * self.size
                                    + (x as i32 - origin.0) as usize)
                                    * 4;
                                let old: [u8; 4] = self.next[index..index + 4].try_into().unwrap();
                                if alpha_locked && old[3] == 0 {
                                    continue;
                                }
                                let Some(mut source) = self.sample(
                                    x as f32 + previous.x - point.x - self.origin.0 as f32,
                                    y as f32 + previous.y - point.y - self.origin.1 as f32,
                                    canvas,
                                    selection,
                                    pixel,
                                ) else {
                                    continue;
                                };
                                if brush.mix > 0.0 {
                                    let mix = brush.mix;
                                    for (channel, value) in source[..3].iter_mut().enumerate() {
                                        *value = *value * (1.0 - mix)
                                            + f32::from(brush.color[channel]) * mix;
                                    }
                                    source[3] = source[3] * (1.0 - mix) + 255.0 * mix;
                                }
                                if alpha_locked {
                                    if source[3] <= f32::EPSILON {
                                        continue;
                                    }
                                    let factor = f32::from(old[3]) / source[3];
                                    for channel in &mut source[..3] {
                                        *channel *= factor;
                                    }
                                    source[3] = f32::from(old[3]);
                                }
                                let alpha = (source[3] * amount
                                    + f32::from(old[3]) * (1.0 - amount))
                                    .round() as u8;
                                let result: [u8; 4] = std::array::from_fn(|c| {
                                    if c == 3 {
                                        alpha
                                    } else {
                                        (source[c] * amount + f32::from(old[c]) * (1.0 - amount))
                                            .round()
                                            .clamp(0.0, f32::from(alpha))
                                            as u8
                                    }
                                });
                                if result == old {
                                    continue;
                                }
                                self.next[index..index + 4].copy_from_slice(&result);
                                tile_changed = true;
                            }
                        }
                        if tile_changed {
                            if !raster.tiles().contains_key(&(tx, ty)) {
                                if *remaining == 0 {
                                    return Err("当前工程已达到像素内存上限".into());
                                }
                                *remaining -= 1;
                            }
                            let tile = Arc::make_mut(
                                raster
                                    .tiles_mut()
                                    .entry((tx, ty))
                                    .or_insert_with(|| Arc::new(vec![0; TILE_BYTES])),
                            );
                            let left = bounds.left.max(tx * TILE_SIZE);
                            let count =
                                (bounds.right.min((tx + 1) * TILE_SIZE) - left) as usize * 4;
                            for y in bounds.top.max(ty * TILE_SIZE)
                                ..bounds.bottom.min((ty + 1) * TILE_SIZE)
                            {
                                let source = ((y as i32 - origin.1) as usize * self.size
                                    + (left as i32 - origin.0) as usize)
                                    * 4;
                                let target =
                                    ((y % TILE_SIZE * TILE_SIZE + left % TILE_SIZE) * 4) as usize;
                                tile[target..target + count]
                                    .copy_from_slice(&self.next[source..source + count]);
                            }
                            dirty.insert((tx, ty));
                            changed = true;
                        }
                    }
                }
            }
        }
        std::mem::swap(&mut self.previous, &mut self.next);
        self.origin = origin;
        self.point = Some(point);
        Ok(changed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn largest_tip_reuses_a_bounded_pair_of_buffers() {
        let brush = Brush {
            size: 256.0,
            smudge: true,
            tip: BrushTip::Flat,
            ..Brush::default()
        };
        let mut smudge = Smudge::new(brush).unwrap();
        let mut pointers = [smudge.previous.as_ptr(), smudge.next.as_ptr()];
        pointers.sort();
        assert!(smudge.previous.capacity() + smudge.next.capacity() <= MAX_SMUDGE_CACHE_BYTES);
        let mut doc = Document::new(512, 512).unwrap();
        let mut dirty = BTreeSet::new();
        let mut remaining = MAX_DOCUMENT_BYTES / TILE_BYTES;
        for i in 0..12 {
            smudge
                .stamp(
                    &mut doc,
                    None,
                    brush,
                    Sample {
                        x: 80.0 + i as f32 * 12.3,
                        y: 230.5,
                        pressure: 0.4 + i as f32 * 0.05,
                    },
                    &mut dirty,
                    &mut remaining,
                )
                .unwrap();
            let mut current = [smudge.previous.as_ptr(), smudge.next.as_ptr()];
            current.sort();
            assert_eq!(pointers, current);
        }
        assert!(dirty.is_empty());
        assert_eq!(doc.tile_count(), 0);
    }
}
