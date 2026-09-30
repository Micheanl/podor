use crate::{model::*, raster::composite_tile_background, storage::MAX_FILE_BYTES};

mod import;
pub use import::load;

pub fn export(doc: &Document) -> Result<Vec<u8>, String> {
    doc.validate()?;
    if doc
        .layers
        .iter()
        .any(|layer| matches!(layer.content, LayerContent::Vector(_)))
    {
        return Err("PSD 尚不能保留可编辑矢量图层，请保存 podor 工程或选择烘焙副本导出".into());
    }
    if doc.layers.iter().any(|layer| layer.masks.len() > 1) {
        return Err("PSD 尚不能保留多个独立蒙版，请保存 podor 工程或选择烘焙副本导出".into());
    }
    if doc.layers.iter().any(Layer::is_adjustment) {
        return Err("PSD 尚不能保留可编辑调整图层，请保存 podor 工程或选择烘焙副本导出".into());
    }
    let mut plan = Vec::with_capacity(doc.layers.len() * 2);
    plan_records(doc, None, 0, &mut plan);
    let mut output = b"8BPS\0\x01\0\0\0\0\0\0\0\x04".to_vec();
    output.extend(doc.height.to_be_bytes());
    output.extend(doc.width.to_be_bytes());
    output.extend([0, 8, 0, 3]);
    output.extend([0; 4]);
    let resources = length_slot(&mut output);
    output.extend(b"8BIM\x04\x2D\0\0\0\0\0\x06\0\x01");
    output.extend(doc.active.to_be_bytes());
    finish_length(&mut output, resources);
    let section = length_slot(&mut output);
    let info = length_slot(&mut output);
    output.extend((-(plan.len() as i16)).to_be_bytes());
    let mut records = Vec::with_capacity(plan.len());
    for record in plan {
        let layer = record.layer();
        let bounds = layer.filter(|layer| !layer.is_group()).map_or(
            Rect {
                left: 0,
                top: 0,
                right: 0,
                bottom: 0,
            },
            |layer| bounds(doc, layer),
        );
        for value in [bounds.top, bounds.left, bounds.bottom, bounds.right] {
            output.extend(value.to_be_bytes());
        }
        let mut ids = if matches!(record, PsdRecord::Raster(_)) {
            vec![0i16, 1, 2, -1]
        } else {
            Vec::new()
        };
        if layer.is_some_and(|layer| layer.first_mask().is_some()) {
            ids.push(-2);
        }
        output.extend((ids.len() as u16).to_be_bytes());
        let mut channels = Vec::with_capacity(ids.len());
        for id in ids {
            output.extend(id.to_be_bytes());
            channels.push((id, length_slot(&mut output)));
        }
        output.extend(b"8BIM");
        let mode = layer.map_or(b"norm", psd_mode);
        output.extend(mode);
        output.extend(layer.map_or([255, 0, 2, 0], |layer| {
            [
                (layer.opacity * 255.0).round() as u8,
                u8::from(layer.clipping),
                u8::from(layer.alpha_locked) | (u8::from(!layer.visible) << 1),
                0,
            ]
        }));
        let extra = length_slot(&mut output);
        if let Some(mask) = layer.and_then(Layer::first_mask) {
            mask.validate()?;
            output.extend(20u32.to_be_bytes());
            for value in [
                mask.bounds.top,
                mask.bounds.left,
                mask.bounds.bottom,
                mask.bounds.right,
            ] {
                output.extend(value.to_be_bytes());
            }
            output.extend([
                mask.default,
                u8::from(!mask.linked) | (u8::from(!mask.enabled) << 1),
                0,
                0,
            ]);
        } else {
            output.extend([0; 4]);
        }
        output.extend([0; 4]);
        let full_name = layer.map_or("</Layer group>", |layer| layer.name.as_str());
        let name = full_name
            .chars()
            .take(255)
            .map(|c| if c.is_ascii() { c as u8 } else { b'?' })
            .collect::<Vec<_>>();
        output.push(name.len() as u8);
        output.extend(&name);
        output.extend(std::iter::repeat_n(0, (4 - (name.len() + 1) % 4) % 4));
        let unicode: Vec<_> = full_name.encode_utf16().collect();
        output.extend(b"8BIMluni");
        output.extend((6 + unicode.len() as u32 * 2).to_be_bytes());
        output.extend((unicode.len() as u32).to_be_bytes());
        for value in unicode {
            output.extend(value.to_be_bytes());
        }
        output.extend([0, 0]);
        if let Some(layer) = layer {
            output.extend(b"8BIMlyid\0\0\0\x04");
            output.extend(layer.id.to_be_bytes());
            output.extend(b"8BIMlspf\0\0\0\x04");
            output.extend(
                (if layer.locked {
                    0x8000_0006u32 | u32::from(layer.alpha_locked || !layer.is_group())
                } else {
                    u32::from(layer.alpha_locked)
                })
                .to_be_bytes(),
            );
            output.extend(b"8BIMclbl\0\0\0\x04\x01\0\0\0");
        }
        match record {
            PsdRecord::Raster(_) => {}
            PsdRecord::Folder(layer) => {
                let LayerContent::Group { closed, .. } = layer.content else {
                    unreachable!()
                };
                output.extend(b"8BIMlsct\0\0\0\x10");
                output.extend((if closed { 2u32 } else { 1u32 }).to_be_bytes());
                output.extend(b"8BIM");
                output.extend(mode);
                output.extend([0; 4]);
            }
            PsdRecord::Divider { depth } => {
                output.extend(if depth > 5 { b"8BIMlsdk" } else { b"8BIMlsct" });
                output.extend(b"\0\0\0\x04\0\0\0\x03");
            }
        }
        finish_length(&mut output, extra);
        records.push((layer, bounds, channels));
    }
    for (layer, bounds, channels) in records {
        for (channel_id, slot) in channels {
            let mask = (channel_id == -2).then(|| layer.unwrap().first_mask().unwrap());
            let (width, height) = mask.map_or(
                (
                    (bounds.right - bounds.left) as usize,
                    (bounds.bottom - bounds.top) as usize,
                ),
                |mask| (mask.bounds.width() as usize, mask.bounds.height() as usize),
            );
            let mut row = vec![0; width];
            let start = output.len();
            output.extend(1u16.to_be_bytes());
            let lengths = output.len();
            output.resize(lengths + height * 2, 0);
            for y in 0..height {
                if let Some(mask) = mask {
                    mask_row(mask, y as u32, &mut row);
                } else {
                    layer_row(
                        layer.unwrap(),
                        doc.palette.as_ref(),
                        bounds,
                        y as u32,
                        if channel_id == -1 {
                            3
                        } else {
                            channel_id as usize
                        },
                        &mut row,
                    );
                }
                let size = pack_bits(&row, &mut output);
                output[lengths + y * 2..lengths + y * 2 + 2].copy_from_slice(&size.to_be_bytes());
                check_size(output.len())?;
            }
            let size = (output.len() - start) as u32;
            output[slot..slot + 4].copy_from_slice(&size.to_be_bytes());
        }
    }
    if !(output.len() - info - 4).is_multiple_of(2) {
        output.push(0);
    }
    finish_length(&mut output, info);
    output.extend([0; 4]);
    finish_length(&mut output, section);
    merged(doc, &mut output)?;
    Ok(output)
}

#[derive(Clone, Copy)]
enum PsdRecord<'a> {
    Raster(&'a Layer),
    Folder(&'a Layer),
    Divider { depth: usize },
}

impl<'a> PsdRecord<'a> {
    fn layer(self) -> Option<&'a Layer> {
        match self {
            Self::Raster(layer) | Self::Folder(layer) => Some(layer),
            Self::Divider { .. } => None,
        }
    }
}

fn plan_records<'a>(
    doc: &'a Document,
    parent: Option<u32>,
    depth: usize,
    records: &mut Vec<PsdRecord<'a>>,
) {
    for layer in doc.layers.iter().filter(|layer| layer.parent_id == parent) {
        if layer.is_group() {
            records.push(PsdRecord::Divider { depth: depth + 1 });
            plan_records(doc, Some(layer.id), depth + 1, records);
            records.push(PsdRecord::Folder(layer));
        } else {
            records.push(PsdRecord::Raster(layer));
        }
    }
}

fn psd_mode(layer: &Layer) -> &'static [u8; 4] {
    if matches!(
        layer.content,
        LayerContent::Group {
            isolation: GroupIsolation::PassThrough,
            ..
        }
    ) {
        return b"pass";
    }
    match layer.blend {
        BlendMode::Normal => b"norm",
        BlendMode::Multiply => b"mul ",
        BlendMode::Screen => b"scrn",
        BlendMode::Overlay => b"over",
        BlendMode::SoftLight => b"sLit",
        BlendMode::Darken => b"dark",
        BlendMode::Lighten => b"lite",
        BlendMode::Difference => b"diff",
    }
}

fn mask_row(mask: &LayerMask, y: u32, row: &mut [u8]) {
    row.fill(mask.default);
    for tx in 0..mask.bounds.width().div_ceil(TILE_SIZE) {
        let Some(tile) = mask.tiles.get(&(tx, y / TILE_SIZE)) else {
            continue;
        };
        let left = tx as usize * TILE_SIZE as usize;
        let count = (row.len() - left).min(TILE_SIZE as usize);
        let start = (y % TILE_SIZE * TILE_SIZE) as usize;
        row[left..left + count].copy_from_slice(&tile[start..start + count]);
    }
}

fn merged(doc: &Document, output: &mut Vec<u8>) -> Result<(), String> {
    output.extend(1u16.to_be_bytes());
    let lengths = output.len();
    output.resize(lengths + doc.height as usize * 8, 0);
    let mut planes: [Vec<u8>; 4] = std::array::from_fn(|_| Vec::new());
    let mut band = Vec::new();
    let mut row = vec![0; doc.width as usize];
    for y in 0..doc.height {
        if y % TILE_SIZE == 0 {
            band.clear();
            for x in 0..doc.width.div_ceil(TILE_SIZE) {
                band.push(composite_tile_background(doc, (x, y / TILE_SIZE), true));
            }
        }
        for (channel, plane) in planes.iter_mut().enumerate() {
            for (tx, tile) in band.iter().enumerate() {
                let left = tx * TILE_SIZE as usize;
                let count = (row.len() - left).min(TILE_SIZE as usize);
                let start = (y % TILE_SIZE * TILE_SIZE * 4) as usize;
                for (target, pixel) in row[left..left + count]
                    .iter_mut()
                    .zip(tile[start..start + count * 4].as_chunks::<4>().0)
                {
                    // PSD 的合成预览要求 RGB 混合白底，透明度仍单独保存。
                    *target = if channel == 3 {
                        pixel[3]
                    } else {
                        pixel[channel].saturating_add(255 - pixel[3])
                    };
                }
            }
            let size = pack_bits(&row, plane);
            let offset = lengths + (channel * doc.height as usize + y as usize) * 2;
            output[offset..offset + 2].copy_from_slice(&size.to_be_bytes());
        }
        check_size(output.len() + planes.iter().map(Vec::len).sum::<usize>())?;
    }
    for plane in planes {
        output.extend(plane);
    }
    Ok(())
}

fn layer_row(
    layer: &Layer,
    palette: Option<&IndexedPalette>,
    bounds: Rect,
    y: u32,
    channel: usize,
    row: &mut [u8],
) {
    row.fill(0);
    let raster = layer.raster().unwrap();
    let y = bounds.top + y;
    for tx in bounds.left / TILE_SIZE..bounds.right.div_ceil(TILE_SIZE) {
        let Some(tile) = raster.tiles().get(&(tx, y / TILE_SIZE)) else {
            continue;
        };
        let left = (tx * TILE_SIZE).max(bounds.left);
        let right = ((tx + 1) * TILE_SIZE).min(bounds.right);
        let start = (y % TILE_SIZE * TILE_SIZE + left % TILE_SIZE) as usize;
        let count = (right - left) as usize;
        let destination = (left - bounds.left) as usize;
        if raster.is_indexed() {
            let palette = palette.unwrap();
            for (target, &index) in row[destination..destination + count]
                .iter_mut()
                .zip(&tile[start..start + count])
            {
                *target = palette.colors[index as usize][channel];
            }
        } else {
            let start = start * 4;
            for (target, pixel) in row[destination..destination + count]
                .iter_mut()
                .zip(tile[start..start + count * 4].as_chunks::<4>().0)
            {
                *target = straight(pixel, channel);
            }
        }
    }
}

fn straight(pixel: &[u8; 4], channel: usize) -> u8 {
    if channel == 3 {
        return pixel[3];
    }
    let alpha = u32::from(pixel[3]);
    ((u32::from(pixel[channel]) * 255 + alpha / 2)
        .checked_div(alpha)
        .unwrap_or(0))
    .min(255) as u8
}

fn bounds(doc: &Document, layer: &Layer) -> Rect {
    layer
        .raster()
        .unwrap()
        .tiles()
        .keys()
        .map(|&(x, y)| Rect {
            left: x * TILE_SIZE,
            top: y * TILE_SIZE,
            right: ((x + 1) * TILE_SIZE).min(doc.width),
            bottom: ((y + 1) * TILE_SIZE).min(doc.height),
        })
        .reduce(|a, b| Rect {
            left: a.left.min(b.left),
            top: a.top.min(b.top),
            right: a.right.max(b.right),
            bottom: a.bottom.max(b.bottom),
        })
        .unwrap_or(Rect {
            left: 0,
            top: 0,
            right: 1,
            bottom: 1,
        })
}

fn length_slot(output: &mut Vec<u8>) -> usize {
    let offset = output.len();
    output.extend([0; 4]);
    offset
}

fn finish_length(output: &mut [u8], slot: usize) {
    let length = (output.len() - slot - 4) as u32;
    output[slot..slot + 4].copy_from_slice(&length.to_be_bytes());
}

fn check_size(size: usize) -> Result<(), String> {
    if size > MAX_FILE_BYTES {
        Err("PSD 文件超过大小限制".into())
    } else {
        Ok(())
    }
}

fn pack_bits(row: &[u8], output: &mut Vec<u8>) -> u16 {
    let start = output.len();
    let mut index = 0;
    while index < row.len() {
        let mut run = 1;
        while run < 128 && index + run < row.len() && row[index + run] == row[index] {
            run += 1;
        }
        if run >= 3 {
            output.extend([(257 - run) as u8, row[index]]);
            index += run;
        } else {
            let begin = index;
            index += 1;
            while index < row.len() && index - begin < 128 {
                if index + 2 < row.len()
                    && row[index] == row[index + 1]
                    && row[index] == row[index + 2]
                {
                    break;
                }
                index += 1;
            }
            output.push((index - begin - 1) as u8);
            output.extend(&row[begin..index]);
        }
    }
    (output.len() - start) as u16
}
