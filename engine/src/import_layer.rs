use crate::{model::*, storage};
use std::{collections::btree_map::Entry, sync::Arc};

pub fn prepare(doc: &Document, bytes: &[u8], name: &str) -> Result<Layer, String> {
    if doc.layers.len() >= MAX_LAYER_NODES
        || doc
            .layers
            .iter()
            .filter(|layer| layer.raster_opt().is_some())
            .count()
            >= MAX_LAYERS
    {
        return Err("已达到图层上限".into());
    }
    let active = doc
        .layers
        .iter()
        .find(|layer| layer.id == doc.active)
        .ok_or("图层不存在")?;
    let parent_id = if active.is_group() {
        Some(active.id)
    } else {
        active.parent_id
    };
    if let Some(id) = parent_id {
        crate::groups::check_editable(doc, id, false)?;
    }
    if name.trim().is_empty() || name.len() > MAX_LAYER_NAME_BYTES {
        return Err("图层属性无效".into());
    }
    if !matches!(
        image::guess_format(bytes),
        Ok(image::ImageFormat::Png | image::ImageFormat::Jpeg | image::ImageFormat::WebP)
    ) {
        return Err("导入图层请选择 PNG、JPEG 或静态 WebP 图片".into());
    }
    if bytes.len() > storage::MAX_FILE_BYTES {
        return Err("文件过大".into());
    }
    if bytes.starts_with(b"\x89PNG") {
        let reader = png::Decoder::new(bytes)
            .read_info()
            .map_err(|_| "无法读取 PNG 图片")?;
        if reader.info().animation_control.is_some() {
            return Err("暂不支持动画 PNG，请先导出为静态图片".into());
        }
    }
    let source = storage::load(bytes)?;
    let source = if source.palette.is_some() {
        crate::indexed::convert(&source, None)?
    } else {
        source
    };
    let (width, height) = fit(source.width, source.height, doc.width, doc.height);
    let left = (doc.width - width) / 2;
    let top = (doc.height - height) / 2;
    let mut layer = Layer::new(doc.next_id, name.to_owned());
    layer.parent_id = parent_id;
    let budget = (MAX_DOCUMENT_BYTES / TILE_BYTES).saturating_sub(doc.tile_count());
    if (width, height) == (source.width, source.height)
        && left.is_multiple_of(TILE_SIZE)
        && top.is_multiple_of(TILE_SIZE)
    {
        let source = source.layers.first().ok_or("图片没有像素图层")?.raster()?;
        if source.tiles().len() > budget {
            return Err("工程像素超过内存限制".into());
        }
        if source.tiles().len() > MAX_HISTORY_BYTES / TILE_BYTES {
            return Err("导入图层超过撤销内存限制".into());
        }
        layer.raster_mut()?.set_tiles(
            source
                .tiles()
                .iter()
                .map(|((x, y), tile)| ((x + left / TILE_SIZE, y + top / TILE_SIZE), tile.clone()))
                .collect(),
        );
        return Ok(layer);
    }
    let mut row = vec![[0u8; 4]; source.width as usize];
    let mut output = vec![[0u8; 4]; width as usize];
    let scaled = width != source.width || height != source.height;
    let horizontal = if scaled {
        weights(source.width, width)
    } else {
        Vec::new()
    };
    let vertical = if scaled {
        weights(source.height, height)
    } else {
        Vec::new()
    };
    let mut sums = if scaled {
        vec![[0.0; 4]; width as usize]
    } else {
        Vec::new()
    };
    for y in 0..height {
        if scaled {
            sums.fill([0.0; 4]);
            for &(sy, wy) in &vertical[y as usize] {
                read_row(&source, sy as u32, &mut row)?;
                for (target, samples) in sums.iter_mut().zip(&horizontal) {
                    for &(sx, wx) in samples {
                        for (channel, &value) in target.iter_mut().zip(&row[sx]) {
                            *channel += f64::from(value) * wx * wy;
                        }
                    }
                }
            }
            for (target, sum) in output.iter_mut().zip(&sums) {
                for (channel, value) in target.iter_mut().zip(sum) {
                    *channel = value.round().clamp(0.0, 255.0) as u8;
                }
            }
        } else {
            read_row(&source, y, &mut output)?;
        }
        write_row(&mut layer, left, top + y, &output, budget)?;
    }
    Ok(layer)
}

fn fit(width: u32, height: u32, canvas_width: u32, canvas_height: u32) -> (u32, u32) {
    if width <= canvas_width && height <= canvas_height {
        (width, height)
    } else if u64::from(width) * u64::from(canvas_height)
        > u64::from(height) * u64::from(canvas_width)
    {
        (
            canvas_width,
            (u64::from(height) * u64::from(canvas_width) / u64::from(width)).max(1) as u32,
        )
    } else {
        (
            (u64::from(width) * u64::from(canvas_height) / u64::from(height)).max(1) as u32,
            canvas_height,
        )
    }
}

fn weights(source: u32, target: u32) -> Vec<Vec<(usize, f64)>> {
    let scale = f64::from(source) / f64::from(target);
    (0..target)
        .map(|index| {
            let start = f64::from(index) * scale;
            let end = f64::from(index + 1) * scale;
            (start.floor() as usize..(end.ceil() as usize).min(source as usize))
                .map(|sample| {
                    (
                        sample,
                        (end.min((sample + 1) as f64) - start.max(sample as f64)) / scale,
                    )
                })
                .collect()
        })
        .collect()
}

fn read_row(source: &Document, y: u32, row: &mut [[u8; 4]]) -> Result<(), String> {
    let raster = source.layers.first().ok_or("图片没有像素图层")?.raster()?;
    row.fill([0; 4]);
    for tx in 0..source.width.div_ceil(TILE_SIZE) {
        if let Some(tile) = raster.tiles().get(&(tx, y / TILE_SIZE)) {
            let start = (y % TILE_SIZE * TILE_SIZE * 4) as usize;
            let left = (tx * TILE_SIZE) as usize;
            let count = (source.width as usize - left).min(TILE_SIZE as usize);
            row[left..left + count]
                .copy_from_slice(tile[start..start + count * 4].as_chunks::<4>().0);
        }
    }
    Ok(())
}

fn write_row(
    layer: &mut Layer,
    left: u32,
    y: u32,
    row: &[[u8; 4]],
    budget: usize,
) -> Result<(), String> {
    let right = left + row.len() as u32;
    for tx in left / TILE_SIZE..right.div_ceil(TILE_SIZE) {
        let start = left.max(tx * TILE_SIZE);
        let end = right.min((tx + 1) * TILE_SIZE);
        let pixels = &row[(start - left) as usize..(end - left) as usize];
        if pixels.iter().all(|pixel| pixel[3] == 0) {
            continue;
        }
        let raster = layer.raster_mut()?;
        let count = raster.tiles().len();
        let tile = match raster.tiles_mut().entry((tx, y / TILE_SIZE)) {
            Entry::Occupied(entry) => entry.into_mut(),
            Entry::Vacant(entry) => {
                if count >= budget {
                    return Err("工程像素超过内存限制".into());
                }
                if count >= MAX_HISTORY_BYTES / TILE_BYTES {
                    return Err("导入图层超过撤销内存限制".into());
                }
                entry.insert(Arc::new(vec![0; TILE_BYTES]))
            }
        };
        let offset = ((y % TILE_SIZE * TILE_SIZE + start % TILE_SIZE) * 4) as usize;
        Arc::get_mut(tile).unwrap()[offset..offset + pixels.len() * 4]
            .as_chunks_mut::<4>()
            .0
            .copy_from_slice(pixels);
    }
    Ok(())
}
