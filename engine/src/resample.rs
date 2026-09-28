use crate::model::*;
use serde::Deserialize;
use std::{
    collections::{BTreeMap, HashSet},
    sync::Arc,
};

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ResampleFilter {
    #[default]
    Lanczos3,
    Nearest,
}

struct Kernel {
    start: u32,
    weights: Vec<f32>,
}

fn kernels(source: u32, target: u32, filter: ResampleFilter) -> Vec<Kernel> {
    let ratio = f64::from(source) / f64::from(target);
    let scale = ratio.max(1.0);
    (0..target)
        .map(|i| {
            let center = (f64::from(i) + 0.5) * ratio;
            if filter == ResampleFilter::Nearest || source == target {
                return Kernel {
                    start: (center.floor() as u32).min(source - 1),
                    weights: vec![1.0],
                };
            }
            let start = (center - 3.0 * scale - 0.5).ceil().max(0.0) as u32;
            let end = ((center + 3.0 * scale - 0.5).floor() as u32 + 1).min(source);
            let mut weights = (start..end)
                .map(|sample| {
                    let x = ((f64::from(sample) + 0.5 - center) / scale).abs();
                    if x < f64::EPSILON {
                        1.0
                    } else if x >= 3.0 {
                        0.0
                    } else {
                        let p = std::f64::consts::PI * x;
                        (p.sin() / p * (p / 3.0).sin() / (p / 3.0)) as f32
                    }
                })
                .collect::<Vec<_>>();
            let sum = weights.iter().sum::<f32>();
            for weight in &mut weights {
                *weight /= sum;
            }
            Kernel { start, weights }
        })
        .collect()
}

pub fn resize(
    doc: &Document,
    width: u32,
    height: u32,
    filter: ResampleFilter,
) -> Result<Document, String> {
    Document::new(width, height)?;
    let retained: HashSet<_> = doc
        .layers
        .iter()
        .flat_map(|layer| layer.tiles.values())
        .map(Arc::as_ptr)
        .collect();
    if retained.len() * TILE_BYTES > MAX_HISTORY_BYTES {
        return Err("缩放图像会超出撤销内存限制".into());
    }
    resize_pixels(doc, width, height, filter)
}

pub fn resize_pixels(
    doc: &Document,
    width: u32,
    height: u32,
    filter: ResampleFilter,
) -> Result<Document, String> {
    Document::new(width, height)?;
    let transposed =
        u64::from(width) * u64::from(doc.height) > u64::from(height) * u64::from(doc.width);
    let (source_inner, source_outer) = axes(doc.width, doc.height, transposed);
    let (target_inner, target_outer) = axes(width, height, transposed);
    let horizontal = kernels(source_inner, target_inner, filter);
    let vertical = kernels(source_outer, target_outer, filter);
    let mut resized = doc.clone();
    resized.width = width;
    resized.height = height;
    let mut budget = MAX_DOCUMENT_BYTES / TILE_BYTES;
    for (source, target) in doc.layers.iter().zip(&mut resized.layers) {
        target.tiles.clear();
        if source.tiles.is_empty() {
            continue;
        }
        let min_x = source
            .tiles
            .keys()
            .map(|&(x, y)| axes(x, y, transposed).0)
            .min()
            .unwrap()
            * TILE_SIZE;
        let max_x = ((source
            .tiles
            .keys()
            .map(|&(x, y)| axes(x, y, transposed).0)
            .max()
            .unwrap()
            + 1)
            * TILE_SIZE)
            .min(source_inner);
        let min_y = source
            .tiles
            .keys()
            .map(|&(x, y)| axes(x, y, transposed).1)
            .min()
            .unwrap()
            * TILE_SIZE;
        let max_y = ((source
            .tiles
            .keys()
            .map(|&(x, y)| axes(x, y, transposed).1)
            .max()
            .unwrap()
            + 1)
            * TILE_SIZE)
            .min(source_outer);
        let overlap = |kernel: &Kernel, min: u32, max: u32| {
            kernel.start < max && kernel.start + kernel.weights.len() as u32 > min
        };
        let left = horizontal.iter().position(|k| overlap(k, min_x, max_x));
        let right = horizontal.iter().rposition(|k| overlap(k, min_x, max_x));
        let (Some(left), Some(right)) = (left, right) else {
            continue;
        };
        let columns = &horizontal[left..=right];
        let cache_limit =
            (MAX_RESAMPLE_CACHE_BYTES / (columns.len() * size_of::<[f32; 4]>())).max(1);
        let mut cache = BTreeMap::<u32, Vec<[f32; 4]>>::new();
        let mut row = vec![[0; 4]; source_inner as usize];
        let mut sums = vec![[0.0f32; 4]; columns.len()];
        let mut output = vec![[0u8; 4]; columns.len()];
        let mut nearest_row = None;
        for (y, kernel) in vertical.iter().enumerate() {
            if !overlap(kernel, min_y, max_y) {
                continue;
            }
            if filter == ResampleFilter::Nearest {
                if nearest_row != Some(kernel.start) {
                    read_line(source, kernel.start, &mut row, transposed);
                    for (pixel, sample) in output.iter_mut().zip(columns) {
                        *pixel = row[sample.start as usize];
                    }
                    nearest_row = Some(kernel.start);
                }
                write_line(
                    target,
                    left as u32,
                    y as u32,
                    &output,
                    transposed,
                    &mut budget,
                )?;
                continue;
            }
            sums.fill([0.0; 4]);
            for (index, &weight) in kernel.weights.iter().enumerate() {
                if weight == 0.0 {
                    continue;
                }
                let sy = kernel.start + index as u32;
                if sy < min_y || sy >= max_y {
                    continue;
                }
                if !cache.contains_key(&sy) {
                    let mut filtered = if cache.len() == cache_limit {
                        cache.pop_first().unwrap().1
                    } else {
                        vec![[0.0; 4]; columns.len()]
                    };
                    read_line(source, sy, &mut row, transposed);
                    for (target, samples) in filtered.iter_mut().zip(columns) {
                        *target = [0.0; 4];
                        for (i, &wx) in samples.weights.iter().enumerate() {
                            for (channel, &value) in
                                target.iter_mut().zip(&row[samples.start as usize + i])
                            {
                                *channel += f32::from(value) * wx;
                            }
                        }
                    }
                    cache.insert(sy, filtered);
                }
                for (sum, sample) in sums.iter_mut().zip(&cache[&sy]) {
                    for (channel, value) in sum.iter_mut().zip(sample) {
                        *channel += value * weight;
                    }
                }
            }
            for (pixel, sum) in output.iter_mut().zip(&sums) {
                let alpha = sum[3].round().clamp(0.0, 255.0);
                for (channel, value) in pixel.iter_mut().zip(sum) {
                    *channel = value.round().clamp(0.0, alpha) as u8;
                }
            }
            write_line(
                target,
                left as u32,
                y as u32,
                &output,
                transposed,
                &mut budget,
            )?;
        }
    }
    Ok(resized)
}

fn axes(x: u32, y: u32, transposed: bool) -> (u32, u32) {
    if transposed {
        (y, x)
    } else {
        (x, y)
    }
}

fn read_line(layer: &Layer, outer: u32, row: &mut [[u8; 4]], transposed: bool) {
    row.fill([0; 4]);
    for (index, chunk) in row.chunks_mut(TILE_SIZE as usize).enumerate() {
        if let Some(tile) = layer
            .tiles
            .get(&axes(index as u32, outer / TILE_SIZE, transposed))
        {
            if transposed {
                for (inner, pixel) in chunk.iter_mut().enumerate() {
                    let offset = (inner * TILE_SIZE as usize + (outer % TILE_SIZE) as usize) * 4;
                    pixel.copy_from_slice(&tile[offset..offset + 4]);
                }
            } else {
                let offset = (outer % TILE_SIZE * TILE_SIZE * 4) as usize;
                chunk.copy_from_slice(tile[offset..offset + chunk.len() * 4].as_chunks::<4>().0);
            }
        }
    }
}

fn write_line(
    layer: &mut Layer,
    start: u32,
    outer: u32,
    row: &[[u8; 4]],
    transposed: bool,
    budget: &mut usize,
) -> Result<(), String> {
    let end = start + row.len() as u32;
    for index in start / TILE_SIZE..end.div_ceil(TILE_SIZE) {
        let left = start.max(index * TILE_SIZE);
        let right = end.min((index + 1) * TILE_SIZE);
        let pixels = &row[(left - start) as usize..(right - start) as usize];
        if pixels.iter().all(|pixel| pixel[3] == 0) {
            continue;
        }
        let key = axes(index, outer / TILE_SIZE, transposed);
        let tile = match layer.tiles.entry(key) {
            std::collections::btree_map::Entry::Occupied(entry) => entry.into_mut(),
            std::collections::btree_map::Entry::Vacant(entry) => {
                if *budget == 0 {
                    return Err("工程像素超过内存限制".into());
                }
                *budget -= 1;
                entry.insert(Arc::new(vec![0; TILE_BYTES]))
            }
        };
        let tile = Arc::get_mut(tile).unwrap();
        if transposed {
            for (inner, pixel) in pixels.iter().enumerate() {
                let offset = (((left % TILE_SIZE) as usize + inner) * TILE_SIZE as usize
                    + (outer % TILE_SIZE) as usize)
                    * 4;
                tile[offset..offset + 4].copy_from_slice(pixel);
            }
        } else {
            let offset = ((outer % TILE_SIZE * TILE_SIZE + left % TILE_SIZE) * 4) as usize;
            tile[offset..offset + pixels.len() * 4]
                .as_chunks_mut::<4>()
                .0
                .copy_from_slice(pixels);
        }
    }
    Ok(())
}
