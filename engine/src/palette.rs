use crate::{model::*, raster::composite_tile_background};
use std::collections::BTreeSet;

#[derive(Clone, Copy, Default)]
struct ColorBin {
    weight: u64,
    channels: [u64; 3],
}

impl ColorBin {
    fn color(self) -> [u8; 3] {
        self.channels
            .map(|sum| ((sum + self.weight / 2) / self.weight) as u8)
    }
}

struct ColorBox {
    bins: Vec<ColorBin>,
    weight: u64,
    channel: usize,
    range: u8,
}

impl ColorBox {
    fn new(bins: Vec<ColorBin>) -> Self {
        let mut low = [255; 3];
        let mut high = [0; 3];
        let mut weight = 0;
        for bin in &bins {
            weight += bin.weight;
            for (index, value) in bin.color().into_iter().enumerate() {
                low[index] = low[index].min(value);
                high[index] = high[index].max(value);
            }
        }
        let channel = (0..3)
            .max_by_key(|&index| high[index] - low[index])
            .unwrap();
        Self {
            bins,
            weight,
            channel,
            range: high[channel] - low[channel],
        }
    }

    fn split(mut self) -> (Self, Self) {
        self.bins.sort_unstable_by_key(|bin| {
            let color = bin.color();
            (color[self.channel], color)
        });
        let mut weight = 0;
        let mut middle = 1;
        for (index, bin) in self.bins[..self.bins.len() - 1].iter().enumerate() {
            weight += bin.weight;
            middle = index + 1;
            if weight * 2 >= self.weight {
                break;
            }
        }
        let right = self.bins.split_off(middle);
        (Self::new(self.bins), Self::new(right))
    }

    fn color(&self) -> [u8; 3] {
        let mut total = ColorBin {
            weight: self.weight,
            ..Default::default()
        };
        for bin in &self.bins {
            for (sum, value) in total.channels.iter_mut().zip(bin.channels) {
                *sum += value;
            }
        }
        total.color()
    }
}

pub fn extract(doc: &Document, count: usize) -> Result<Vec<[u8; 3]>, String> {
    if !(1..=MAX_PALETTE_COLORS).contains(&count) {
        return Err("色卡数量无效".into());
    }
    let keys: BTreeSet<_> = doc
        .layers
        .iter()
        .filter(|layer| layer.visible && layer.opacity > 0.0)
        .flat_map(|layer| layer.tiles.keys().copied())
        .collect();
    let mut histogram = vec![ColorBin::default(); 1 << 15];
    for key in keys {
        let tile = composite_tile_background(doc, key, true);
        for y in 0..TILE_SIZE.min(doc.height - key.1 * TILE_SIZE) {
            for x in 0..TILE_SIZE.min(doc.width - key.0 * TILE_SIZE) {
                let offset = ((y * TILE_SIZE + x) * 4) as usize;
                let alpha = u64::from(tile[offset + 3]);
                if alpha == 0 {
                    continue;
                }
                let rgb: [u8; 3] = std::array::from_fn(|channel| {
                    ((u64::from(tile[offset + channel]) * 255 + alpha / 2) / alpha).min(255) as u8
                });
                let index = (usize::from(rgb[0] >> 3) << 10)
                    | (usize::from(rgb[1] >> 3) << 5)
                    | usize::from(rgb[2] >> 3);
                let bin = &mut histogram[index];
                bin.weight += alpha;
                for (sum, value) in bin.channels.iter_mut().zip(rgb) {
                    *sum += u64::from(value) * alpha;
                }
            }
        }
    }
    let bins: Vec<_> = histogram.into_iter().filter(|bin| bin.weight > 0).collect();
    if bins.is_empty() {
        return Ok(Vec::new());
    }
    let mut boxes = vec![ColorBox::new(bins)];
    while boxes.len() < count {
        let next = boxes
            .iter()
            .enumerate()
            .filter(|(_, group)| group.bins.len() > 1)
            .max_by_key(|(_, group)| u64::from(group.range).pow(2) * group.weight)
            .map(|(index, _)| index);
        let Some(index) = next else {
            break;
        };
        let (left, right) = boxes.remove(index).split();
        boxes.extend([left, right]);
    }
    boxes.sort_by_key(|group| (std::cmp::Reverse(group.weight), group.color()));
    let mut colors = Vec::with_capacity(boxes.len());
    for group in boxes {
        let color = group.color();
        if !colors.contains(&color) {
            colors.push(color);
        }
    }
    Ok(colors)
}
