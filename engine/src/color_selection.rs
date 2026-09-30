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
    select_clipped(document, settings, None, false)
}

pub(crate) fn select_clipped(
    document: &Document,
    settings: ColorSelection,
    selection: Option<&Selection>,
    preserve_alpha: bool,
) -> Result<Selection, String> {
    if !document.bounds().contains(settings.x, settings.y) {
        return Err("取样位置超出画布".into());
    }
    let active = document
        .layers
        .iter()
        .find(|layer| layer.id == document.active)
        .ok_or("图层不存在")?;
    if !settings.merged || preserve_alpha {
        active.raster()?;
    }
    if document.palette.is_some() && !settings.merged && settings.tolerance == 0 {
        return crate::indexed::select_indices(document, settings, selection, preserve_alpha);
    }
    let key = (settings.x / TILE_SIZE, settings.y / TILE_SIZE);
    let offset = ((settings.y % TILE_SIZE * TILE_SIZE + settings.x % TILE_SIZE) * 4) as usize;
    let sample = if settings.merged {
        composite_tile_background(document, key, true)[offset..offset + 4]
            .try_into()
            .unwrap()
    } else {
        active
            .rgba_tile(document.palette.as_ref(), key)
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
        let hierarchy = crate::groups::Hierarchy::new(document)?;
        let keys: BTreeSet<_> = document
            .layers
            .iter()
            .enumerate()
            .filter(|(index, layer)| hierarchy.visible[*index] && layer.opacity > 0.0)
            .filter_map(|(_, layer)| layer.raster_opt())
            .flat_map(|raster| raster.tiles().keys().copied())
            .collect();
        for key in keys {
            write_tile(key, &composite_tile_background(document, key, true));
        }
    } else {
        for &key in active.raster()?.tiles().keys() {
            write_tile(
                key,
                &active.rgba_tile(document.palette.as_ref(), key).unwrap(),
            );
        }
    }
    if let Some(selection) = selection {
        for (index, value) in mask.iter_mut().enumerate() {
            if selection.coverage((index % width) as u32, (index / width) as u32) == 0 {
                *value = 0;
            }
        }
    }
    if preserve_alpha {
        for ty in 0..document.height.div_ceil(TILE_SIZE) {
            for tx in 0..document.width.div_ceil(TILE_SIZE) {
                let old = active.rgba_tile(document.palette.as_ref(), (tx, ty));
                let left = tx * TILE_SIZE;
                let columns = TILE_SIZE.min(document.width - left) as usize;
                for y in ty * TILE_SIZE..((ty + 1) * TILE_SIZE).min(document.height) {
                    let start = y as usize * width + left as usize;
                    for (x, value) in mask[start..start + columns].iter_mut().enumerate() {
                        if old.as_ref().is_none_or(|tile| {
                            tile[((y % TILE_SIZE * TILE_SIZE) as usize + x) * 4 + 3] == 0
                        }) {
                            *value = 0;
                        }
                    }
                }
            }
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
    if let Some(selection) = selection {
        for (index, value) in mask.iter_mut().enumerate() {
            if *value != 0 {
                *value = selection.coverage((index % width) as u32, (index / width) as u32);
            }
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

pub(crate) fn connected(mask: &mut [u8], width: usize, seed: usize) -> Result<(), String> {
    if mask[seed] != 1 {
        mask.fill(0);
        return Ok(());
    }
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
