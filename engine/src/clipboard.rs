use crate::{model::*, raster, selection::Selection, storage, translation};
use std::{
    borrow::Cow,
    collections::{BTreeSet, HashSet},
    io::Write,
    sync::Arc,
};

pub enum CopyMode {
    Layer,
    Visible,
    Cut,
}

fn active(doc: &Document) -> &Layer {
    doc.layers
        .iter()
        .find(|layer| layer.id == doc.active)
        .unwrap()
}

fn can_cut(layer: &Layer) -> Result<(), String> {
    if layer.locked {
        return Err("图层已锁定，请先解锁".into());
    }
    if layer.alpha_locked {
        return Err("请先解除透明度锁定".into());
    }
    Ok(())
}

pub fn copy(
    doc: &Document,
    selection: Option<&Selection>,
    mode: CopyMode,
) -> Result<Vec<u8>, String> {
    let layer = active(doc);
    if matches!(mode, CopyMode::Cut) {
        can_cut(layer)?;
    }
    let bounds = selection.map_or(doc.bounds(), Selection::bounds);
    let width = bounds.right - bounds.left;
    let height = bounds.bottom - bounds.top;
    let mut output = Vec::new();
    for value in [doc.width, doc.height, bounds.left, bounds.top] {
        output.extend(value.to_le_bytes());
    }
    let mut nonempty = false;
    {
        let mut encoder = png::Encoder::new(&mut output, width, height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder.set_compression(png::Compression::Fast);
        let mut writer = encoder.write_header().map_err(|_| "复制图像失败")?;
        let mut stream = writer.stream_writer().map_err(|_| "复制图像失败")?;
        let mut row = vec![0; width as usize * 4];
        for ty in bounds.top / TILE_SIZE..bounds.bottom.div_ceil(TILE_SIZE) {
            let tiles: Vec<_> = (bounds.left / TILE_SIZE..bounds.right.div_ceil(TILE_SIZE))
                .map(|tx| {
                    let tile = if matches!(mode, CopyMode::Visible) {
                        Some(Cow::Owned(raster::composite_tile_background(
                            doc,
                            (tx, ty),
                            true,
                        )))
                    } else {
                        layer
                            .tiles
                            .get(&(tx, ty))
                            .map(|tile| Cow::Borrowed(tile.as_slice()))
                    };
                    (tx, tile)
                })
                .collect();
            for y in bounds.top.max(ty * TILE_SIZE)..bounds.bottom.min((ty + 1) * TILE_SIZE) {
                row.fill(0);
                for (tx, tile) in &tiles {
                    let Some(tile) = tile else {
                        continue;
                    };
                    let left = bounds.left.max(tx * TILE_SIZE);
                    let right = bounds.right.min((tx + 1) * TILE_SIZE);
                    let source = ((y % TILE_SIZE * TILE_SIZE + left % TILE_SIZE) * 4) as usize;
                    let target = ((left - bounds.left) * 4) as usize;
                    let length = ((right - left) * 4) as usize;
                    row[target..target + length].copy_from_slice(&tile[source..source + length]);
                }
                if let Some(mask) = selection.and_then(|selection| selection.row(y)) {
                    for (pixel, &coverage) in row.as_chunks_mut::<4>().0.iter_mut().zip(mask) {
                        for channel in pixel {
                            *channel =
                                ((u32::from(*channel) * u32::from(coverage) + 127) / 255) as u8;
                        }
                    }
                }
                nonempty |= row.as_chunks::<4>().0.iter().any(|pixel| pixel[3] != 0);
                storage::unpremultiply(&mut row);
                stream.write_all(&row).map_err(|_| "复制图像失败")?;
            }
        }
        stream.finish().map_err(|_| "复制图像失败")?;
    }
    if !nonempty {
        return Err("选中区域没有可复制的内容".into());
    }
    if output.len() > MAX_CLIPBOARD_BYTES + 16 {
        return Err("剪贴板图片过大".into());
    }
    Ok(output)
}

pub fn cut(
    doc: &Document,
    selection: Option<&Selection>,
) -> Result<(Layer, BTreeSet<TileKey>), String> {
    let source = active(doc);
    can_cut(source)?;
    let mut layer = source.clone();
    let mut dirty = BTreeSet::new();
    for (&key, pixels) in &source.tiles {
        let bounds = Rect {
            left: key.0 * TILE_SIZE,
            top: key.1 * TILE_SIZE,
            right: (key.0 + 1) * TILE_SIZE,
            bottom: (key.1 + 1) * TILE_SIZE,
        };
        let Some(area) = bounds.intersect(selection.map_or(doc.bounds(), Selection::bounds)) else {
            continue;
        };
        if selection.is_some_and(|selection| !selection.intersects(area)) {
            continue;
        }
        if Some(area) == bounds.intersect(doc.bounds())
            && selection
                .and_then(|selection| selection.row(area.top))
                .is_none()
        {
            layer.tiles.remove(&key);
            dirty.insert(key);
            continue;
        }
        let mut changed: Option<Vec<u8>> = None;
        for y in area.top..area.bottom {
            for x in area.left..area.right {
                let index = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                let coverage = selection.map_or(255, |selection| selection.coverage(x, y));
                if coverage == 0 || pixels[index + 3] == 0 {
                    continue;
                }
                let new: [u8; 4] = std::array::from_fn(|c| {
                    ((u32::from(pixels[index + c]) * (255 - u32::from(coverage)) + 127) / 255) as u8
                });
                if new == pixels[index..index + 4] {
                    continue;
                }
                changed.get_or_insert_with(|| pixels.as_ref().clone())[index..index + 4]
                    .copy_from_slice(&new);
            }
        }
        if let Some(pixels) = changed {
            dirty.insert(key);
            if pixels.as_chunks::<4>().0.iter().all(|pixel| pixel[3] == 0) {
                layer.tiles.remove(&key);
            } else {
                layer.tiles.insert(key, Arc::new(pixels));
            }
        }
    }
    let live: HashSet<_> = doc
        .layers
        .iter()
        .filter(|other| other.id != source.id)
        .flat_map(|layer| layer.tiles.values())
        .chain(layer.tiles.values())
        .map(Arc::as_ptr)
        .collect();
    let retained: HashSet<_> = source
        .tiles
        .values()
        .map(Arc::as_ptr)
        .filter(|pointer| !live.contains(pointer))
        .collect();
    if retained.len() * TILE_BYTES > MAX_HISTORY_BYTES {
        return Err("剪切内容超过撤销内存限制".into());
    }
    Ok((layer, dirty))
}

pub fn paste(doc: &Document, packet: &[u8]) -> Result<Layer, String> {
    if doc.layers.len() >= MAX_LAYERS {
        return Err("已达到图层上限".into());
    }
    if packet.len() > MAX_CLIPBOARD_BYTES + 16 {
        return Err("剪贴板图片过大".into());
    }
    let header = packet.get(..16).ok_or("剪贴板图片数据无效")?;
    let origin: [u32; 4] =
        std::array::from_fn(|i| u32::from_le_bytes(header[i * 4..i * 4 + 4].try_into().unwrap()));
    let png = &packet[16..];
    if !png.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Err("剪贴板图片数据无效".into());
    }
    let reader = png::Decoder::new(png)
        .read_info()
        .map_err(|_| "剪贴板图片数据无效")?;
    if reader.info().animation_control.is_some() {
        return Err("暂不支持动画 PNG，请先导出为静态图片".into());
    }
    drop(reader);
    let source = storage::load(png)?;
    let (left, top) = if origin == [0; 4] {
        (
            (doc.width as i32 - source.width as i32) / 2,
            (doc.height as i32 - source.height as i32) / 2,
        )
    } else {
        if origin[0] == 0
            || origin[1] == 0
            || origin[0] > MAX_DIMENSION
            || origin[1] > MAX_DIMENSION
            || u64::from(origin[0]) * u64::from(origin[1]) > MAX_PIXELS
            || u64::from(origin[2]) + u64::from(source.width) > u64::from(origin[0])
            || u64::from(origin[3]) + u64::from(source.height) > u64::from(origin[1])
        {
            return Err("剪贴板图片位置无效".into());
        }
        if (origin[0], origin[1]) == (doc.width, doc.height) {
            (origin[2] as i32, origin[3] as i32)
        } else {
            (
                (doc.width as i32 - source.width as i32) / 2,
                (doc.height as i32 - source.height as i32) / 2,
            )
        }
    };
    let budget = (MAX_DOCUMENT_BYTES / TILE_BYTES)
        .saturating_sub(doc.tile_count())
        .min(MAX_HISTORY_BYTES / TILE_BYTES);
    let mut layer = Layer::new(doc.next_id, "粘贴的图像".into());
    layer.tiles = translation::remap(
        &source.layers[0],
        source.bounds(),
        doc.bounds(),
        left,
        top,
        budget,
    )?;
    if layer.tiles.is_empty() {
        return Err("剪贴板图片没有可见内容".into());
    }
    Ok(layer)
}
