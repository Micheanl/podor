use crate::{model::*, raster::composite_tile_background, storage::MAX_FILE_BYTES};

mod import;
pub use import::load;

pub fn export(doc: &Document) -> Result<Vec<u8>, String> {
    let mut output = b"8BPS\0\x01\0\0\0\0\0\0\0\x04".to_vec();
    output.extend(doc.height.to_be_bytes());
    output.extend(doc.width.to_be_bytes());
    output.extend([0, 8, 0, 3]);
    output.extend([0; 8]);
    let section = length_slot(&mut output);
    let info = length_slot(&mut output);
    output.extend((-(doc.layers.len() as i16)).to_be_bytes());
    let mut records = Vec::with_capacity(doc.layers.len());
    for layer in &doc.layers {
        let bounds = bounds(doc, layer);
        for value in [bounds.top, bounds.left, bounds.bottom, bounds.right] {
            output.extend(value.to_be_bytes());
        }
        output.extend(4u16.to_be_bytes());
        let mut channels = [0; 4];
        for (index, id) in [0i16, 1, 2, -1].into_iter().enumerate() {
            output.extend(id.to_be_bytes());
            channels[index] = length_slot(&mut output);
        }
        output.extend(b"8BIM");
        output.extend(match layer.blend {
            BlendMode::Normal => b"norm",
            BlendMode::Multiply => b"mul ",
            BlendMode::Screen => b"scrn",
            BlendMode::Overlay => b"over",
            BlendMode::SoftLight => b"sLit",
            BlendMode::Darken => b"dark",
            BlendMode::Lighten => b"lite",
            BlendMode::Difference => b"diff",
        });
        output.extend([
            (layer.opacity * 255.0).round() as u8,
            0,
            u8::from(layer.alpha_locked) | (u8::from(!layer.visible) << 1),
            0,
        ]);
        let extra = length_slot(&mut output);
        output.extend([0; 8]);
        let name = layer
            .name
            .chars()
            .take(255)
            .map(|c| if c.is_ascii() { c as u8 } else { b'?' })
            .collect::<Vec<_>>();
        output.push(name.len() as u8);
        output.extend(&name);
        output.extend(std::iter::repeat_n(0, (4 - (name.len() + 1) % 4) % 4));
        let unicode: Vec<_> = layer.name.encode_utf16().collect();
        output.extend(b"8BIMluni");
        output.extend((6 + unicode.len() as u32 * 2).to_be_bytes());
        output.extend((unicode.len() as u32).to_be_bytes());
        for value in unicode {
            output.extend(value.to_be_bytes());
        }
        output.extend([0, 0]);
        output.extend(b"8BIMlyid\0\0\0\x04");
        output.extend(layer.id.to_be_bytes());
        output.extend(b"8BIMlspf\0\0\0\x04");
        output.extend(
            (if layer.locked {
                0x8000_0007u32
            } else {
                u32::from(layer.alpha_locked)
            })
            .to_be_bytes(),
        );
        finish_length(&mut output, extra);
        records.push((layer, bounds, channels));
    }
    for (layer, bounds, channels) in records {
        let width = (bounds.right - bounds.left) as usize;
        let height = (bounds.bottom - bounds.top) as usize;
        let mut row = vec![0; width];
        for (channel, slot) in channels.into_iter().enumerate() {
            let start = output.len();
            output.extend(1u16.to_be_bytes());
            let lengths = output.len();
            output.resize(lengths + height * 2, 0);
            for y in 0..height {
                layer_row(layer, bounds, y as u32, channel, &mut row);
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

fn layer_row(layer: &Layer, bounds: Rect, y: u32, channel: usize, row: &mut [u8]) {
    row.fill(0);
    let y = bounds.top + y;
    for tx in bounds.left / TILE_SIZE..bounds.right.div_ceil(TILE_SIZE) {
        let Some(tile) = layer.tiles.get(&(tx, y / TILE_SIZE)) else {
            continue;
        };
        let left = (tx * TILE_SIZE).max(bounds.left);
        let right = ((tx + 1) * TILE_SIZE).min(bounds.right);
        let start = ((y % TILE_SIZE * TILE_SIZE + left % TILE_SIZE) * 4) as usize;
        let count = (right - left) as usize;
        let destination = (left - bounds.left) as usize;
        for (target, pixel) in row[destination..destination + count]
            .iter_mut()
            .zip(tile[start..start + count * 4].as_chunks::<4>().0)
        {
            *target = straight(pixel, channel);
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
        .tiles
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
