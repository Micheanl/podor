use crate::model::*;
use crate::raster::composite_tile_background;
use crate::selection::Selection;
use serde::Deserialize;
use std::collections::{BTreeSet, VecDeque};

#[derive(Clone, Copy, Deserialize)]
pub struct ColorSelection {
    pub x: u32,
    pub y: u32,
    pub tolerance: u8,
    pub contiguous: bool,
    pub merged: bool,
}

pub fn select(document: &Document, settings: ColorSelection) -> Result<Selection, String> {
    if !document.bounds().contains(settings.x, settings.y) {
        return Err("取样位置超出画布".into());
    }
    let active = document
        .layers
        .iter()
        .find(|layer| layer.id == document.active)
        .unwrap();
    let key = (settings.x / TILE_SIZE, settings.y / TILE_SIZE);
    let offset = ((settings.y % TILE_SIZE * TILE_SIZE + settings.x % TILE_SIZE) * 4) as usize;
    let sample = if settings.merged {
        composite_tile_background(document, key, true)[offset..offset + 4]
            .try_into()
            .unwrap()
    } else {
        active
            .tiles
            .get(&key)
            .map_or([0; 4], |tile| tile[offset..offset + 4].try_into().unwrap())
    };
    let target = straight(sample);
    let matches = |pixel: [u8; 4]| {
        straight(pixel)
            .iter()
            .zip(target)
            .all(|(&a, b)| a.abs_diff(b) <= settings.tolerance)
    };
    let width = document.width as usize;
    let mut mask = vec![u8::from(matches([0; 4])); width * document.height as usize];
    let mut write_tile = |(tx, ty): TileKey, tile: &[u8]| {
        let left = tx * TILE_SIZE;
        let top = ty * TILE_SIZE;
        let columns = TILE_SIZE.min(document.width - left) as usize;
        for y in top..(top + TILE_SIZE).min(document.height) {
            let source = ((y - top) * TILE_SIZE * 4) as usize;
            let destination = y as usize * width + left as usize;
            for (value, pixel) in mask[destination..destination + columns]
                .iter_mut()
                .zip(tile[source..source + columns * 4].as_chunks::<4>().0)
            {
                *value = u8::from(matches(*pixel));
            }
        }
    };
    if settings.merged {
        let keys: BTreeSet<_> = document
            .layers
            .iter()
            .filter(|layer| layer.visible && layer.opacity > 0.0)
            .flat_map(|layer| layer.tiles.keys().copied())
            .collect();
        for key in keys {
            write_tile(key, &composite_tile_background(document, key, true));
        }
    } else {
        for (&key, tile) in &active.tiles {
            write_tile(key, tile);
        }
    }
    if settings.contiguous {
        connected(
            &mut mask,
            width,
            settings.y as usize * width + settings.x as usize,
        )?;
    } else {
        for value in &mut mask {
            *value *= 255;
        }
    }
    Ok(Selection::from_mask(document.bounds(), mask))
}

fn straight(pixel: [u8; 4]) -> [u8; 4] {
    let alpha = u32::from(pixel[3]);
    if alpha == 0 {
        return [0; 4];
    }
    let mut result = pixel;
    for value in &mut result[..3] {
        *value = ((u32::from(*value) * 255 + alpha / 2) / alpha).min(255) as u8;
    }
    result
}

fn connected(mask: &mut [u8], width: usize, seed: usize) -> Result<(), String> {
    let mut queue = VecDeque::new();
    enqueue(mask, width, seed, &mut queue)?;
    while let Some(seed) = queue.pop_front() {
        let seed = seed as usize;
        let row = seed / width * width;
        let mut left = seed;
        let mut right = seed + 1;
        while left > row && mask[left - 1] == 2 {
            left -= 1;
        }
        while right < row + width && mask[right] == 2 {
            right += 1;
        }
        mask[left..right].fill(255);
        for adjacent in [
            row.checked_sub(width),
            (row + width < mask.len()).then_some(row + width),
        ]
        .into_iter()
        .flatten()
        {
            let mut x = adjacent + left - row;
            let end = adjacent + right - row;
            while x < end {
                if mask[x] == 1 {
                    x = enqueue(mask, width, x, &mut queue)?;
                } else {
                    x += 1;
                }
            }
        }
    }
    for value in mask {
        if *value != 255 {
            *value = 0;
        }
    }
    Ok(())
}

fn enqueue(
    mask: &mut [u8],
    width: usize,
    seed: usize,
    queue: &mut VecDeque<u32>,
) -> Result<usize, String> {
    if queue.len() >= MAX_SELECTION_FLOOD_BYTES / size_of::<u32>() {
        return Err("连续区域过于复杂，请缩小图像后重试".into());
    }
    let row = seed / width * width;
    let mut left = seed;
    let mut right = seed + 1;
    while left > row && mask[left - 1] == 1 {
        left -= 1;
    }
    while right < row + width && mask[right] == 1 {
        right += 1;
    }
    mask[left..right].fill(2);
    queue.push_back(seed as u32);
    Ok(right)
}
