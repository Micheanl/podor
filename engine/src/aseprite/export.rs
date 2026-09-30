use crate::{
    animation::{CelSource, FrameTag, TagDirection},
    animation_export::BoundedCursor,
    groups::Hierarchy,
    model::*,
    raster::FrameCompositor,
};
use flate2::{write::ZlibEncoder, Compression};
use std::{
    borrow::Cow,
    collections::BTreeMap,
    io::{Seek, SeekFrom, Write},
};

const SCRATCH_RESERVE: usize = 24 * 1024 * 1024;

#[derive(Default)]
struct Work {
    pixels: u64,
    decoded: u64,
}

impl Work {
    fn visit(&mut self, pixels: u64) -> Result<(), String> {
        self.pixels = self
            .pixels
            .checked_add(pixels)
            .ok_or("Aseprite 导出工作量过大")?;
        if self.pixels > MAX_ANIMATION_EXPORT_PIXEL_VISITS {
            return Err("Aseprite 导出像素工作量超过限制".into());
        }
        Ok(())
    }

    fn image(&mut self, bounds: Rect, indexed: bool) -> Result<(), String> {
        let pixels = area(bounds);
        self.visit(pixels)?;
        self.decoded = self
            .decoded
            .checked_add(pixels * if indexed { 1 } else { 4 })
            .ok_or("Aseprite 导出解码大小过大")?;
        if self.decoded > MAX_ASEPRITE_DECODED_BYTES {
            return Err("Aseprite 导出累计图像大小超过限制".into());
        }
        Ok(())
    }
}

fn area(bounds: Rect) -> u64 {
    u64::from(bounds.right - bounds.left) * u64::from(bounds.bottom - bounds.top)
}

fn blank_bounds() -> Rect {
    Rect {
        left: 0,
        top: 0,
        right: 1,
        bottom: 1,
    }
}

fn extend(bounds: &mut Option<Rect>, x: u32, y: u32) {
    if let Some(bounds) = bounds {
        bounds.left = bounds.left.min(x);
        bounds.top = bounds.top.min(y);
        bounds.right = bounds.right.max(x + 1);
        bounds.bottom = bounds.bottom.max(y + 1);
    } else {
        *bounds = Some(Rect {
            left: x,
            top: y,
            right: x + 1,
            bottom: y + 1,
        });
    }
}

fn source_bounds(
    document: &Document,
    source: &RasterPlane,
    work: &mut Work,
) -> Result<Rect, String> {
    let mut bounds = None;
    let transparent = document
        .palette
        .as_ref()
        .map_or(0, |palette| palette.transparent);
    for (&(tx, ty), tile) in source.tiles() {
        let left = tx * TILE_SIZE;
        let top = ty * TILE_SIZE;
        let width = TILE_SIZE.min(document.width - left);
        let height = TILE_SIZE.min(document.height - top);
        work.visit(u64::from(width) * u64::from(height))?;
        for y in 0..height {
            for x in 0..width {
                let offset = (y * TILE_SIZE + x) as usize;
                let present = if source.is_indexed() {
                    tile[offset] != transparent
                } else {
                    tile[offset * 4 + 3] != 0
                };
                if present {
                    extend(&mut bounds, left + x, top + y);
                }
            }
        }
    }
    Ok(bounds.unwrap_or_else(blank_bounds))
}

fn editable_bounds(document: &Document, work: &mut Work) -> Result<BTreeMap<u32, Rect>, String> {
    let mut bounds = BTreeMap::new();
    let mut sources = BTreeMap::new();
    let mut add = |id: u32, source: &RasterPlane| -> Result<(), String> {
        let key = source as *const RasterPlane as usize;
        let rect = if let Some(&rect) = sources.get(&key) {
            rect
        } else {
            let rect = source_bounds(document, source, work)?;
            sources.insert(key, rect);
            rect
        };
        work.image(rect, source.is_indexed())?;
        bounds.insert(id, rect);
        Ok(())
    };
    if let Some(animation) = &document.animation {
        for cel in animation.cels.values() {
            let CelSource::Raster(source) = &cel.source else {
                return Err("Aseprite 可编辑导出不支持矢量 Cel".into());
            };
            add(cel.id, source)?;
        }
    } else {
        for layer in &document.layers {
            if let Some(source) = layer.raster_opt() {
                add(layer.id, source)?;
            }
        }
    }
    Ok(bounds)
}

fn frame_view(document: &Document, index: usize) -> Result<Cow<'_, Document>, String> {
    let Some(animation) = &document.animation else {
        return Ok(Cow::Borrowed(document));
    };
    let frame = &animation.frames[index];
    let mut maps = 0usize;
    for layer in &document.layers {
        if let LayerContent::CelTrack { .. } = layer.content {
            if let Some(cel) = frame
                .exposures
                .get(&layer.id)
                .and_then(|id| animation.cels.get(id))
            {
                if let CelSource::Raster(source) = &cel.source {
                    maps = maps.saturating_add(source.tiles().len());
                }
                maps = maps.saturating_add(
                    cel.masks
                        .iter()
                        .map(|mask| mask.plane.tiles.len())
                        .sum::<usize>(),
                );
            }
        } else {
            maps = maps.saturating_add(
                layer
                    .masks
                    .iter()
                    .map(|mask| mask.plane.tiles.len())
                    .sum::<usize>(),
            );
        }
    }
    let band = document.width as usize * TILE_SIZE as usize * 4;
    if maps
        .saturating_mul(128)
        .saturating_add(band)
        .saturating_add(SCRATCH_RESERVE)
        > MAX_ASEPRITE_SCRATCH_BYTES
    {
        return Err("Aseprite 合成导出工作内存超过限制".into());
    }
    Ok(Cow::Owned(crate::animation::view(document, frame.id)?))
}

fn baked_bounds(document: &Document, count: usize, work: &mut Work) -> Result<Vec<Rect>, String> {
    work.visit(u64::from(document.width) * u64::from(document.height) * count as u64)?;
    let mut result = Vec::with_capacity(count);
    for index in 0..count {
        let view = frame_view(document, index)?;
        let mut compositor = FrameCompositor::new(&view, true);
        let mut bounds = None;
        for ty in 0..view.height.div_ceil(TILE_SIZE) {
            for tx in 0..view.width.div_ceil(TILE_SIZE) {
                let tile = compositor.tile(&view, (tx, ty));
                for y in 0..TILE_SIZE.min(view.height - ty * TILE_SIZE) {
                    for x in 0..TILE_SIZE.min(view.width - tx * TILE_SIZE) {
                        if tile[((y * TILE_SIZE + x) * 4 + 3) as usize] != 0 {
                            extend(&mut bounds, tx * TILE_SIZE + x, ty * TILE_SIZE + y);
                        }
                    }
                }
            }
        }
        let bounds = bounds.unwrap_or_else(blank_bounds);
        let covered = Rect {
            left: bounds.left / TILE_SIZE * TILE_SIZE,
            top: bounds.top / TILE_SIZE * TILE_SIZE,
            right: (bounds.right.div_ceil(TILE_SIZE) * TILE_SIZE).min(document.width),
            bottom: (bounds.bottom.div_ceil(TILE_SIZE) * TILE_SIZE).min(document.height),
        };
        work.visit(area(covered).saturating_sub(area(bounds)))?;
        work.image(bounds, false)?;
        result.push(bounds);
    }
    Ok(result)
}

fn put(output: &mut impl Write, bytes: &[u8]) -> Result<(), String> {
    output
        .write_all(bytes)
        .map_err(|_| "Aseprite 输出失败或超过大小限制".into())
}

fn word(output: &mut impl Write, value: u16) -> Result<(), String> {
    put(output, &value.to_le_bytes())
}
fn dword(output: &mut impl Write, value: u32) -> Result<(), String> {
    put(output, &value.to_le_bytes())
}

fn position(output: &mut BoundedCursor) -> Result<u64, String> {
    output
        .stream_position()
        .map_err(|_| "Aseprite 输出位置无效".into())
}

fn patch(output: &mut BoundedCursor, offset: u64, bytes: &[u8]) -> Result<(), String> {
    let end = position(output)?;
    output
        .seek(SeekFrom::Start(offset))
        .map_err(|_| "Aseprite 输出位置无效")?;
    put(output, bytes)?;
    output
        .seek(SeekFrom::Start(end))
        .map_err(|_| "Aseprite 输出位置无效")?;
    Ok(())
}

fn string(output: &mut impl Write, value: &str) -> Result<(), String> {
    if value.len() > MAX_ASEPRITE_NAME_BYTES {
        return Err("Aseprite 名称超过长度限制".into());
    }
    word(
        output,
        u16::try_from(value.len()).map_err(|_| "Aseprite 名称过长")?,
    )?;
    put(output, value.as_bytes())
}

fn chunk(
    output: &mut BoundedCursor,
    kind: u16,
    write: impl FnOnce(&mut BoundedCursor) -> Result<(), String>,
) -> Result<(), String> {
    let start = position(output)?;
    dword(output, 0)?;
    word(output, kind)?;
    write(output)?;
    let size = u32::try_from(position(output)? - start).map_err(|_| "Aseprite 数据块过大")?;
    patch(output, start, &size.to_le_bytes())
}

fn layer(output: &mut BoundedCursor, layer: &Layer, depth: usize) -> Result<(), String> {
    chunk(output, 0x2004, |output| {
        let mut flags = u16::from(layer.visible) | if layer.locked { 0 } else { 2 };
        let group = matches!(layer.content, LayerContent::Group { .. });
        if matches!(layer.content, LayerContent::Group { closed: true, .. }) {
            flags |= 32;
        }
        word(output, flags)?;
        word(output, u16::from(group))?;
        word(
            output,
            u16::try_from(depth).map_err(|_| "Aseprite 图层深度过大")?,
        )?;
        word(output, 0)?;
        word(output, 0)?;
        word(output, super::blend_code(layer.blend))?;
        put(output, &[(layer.opacity * 255.0).round() as u8, 0, 0, 0])?;
        string(output, &layer.name)
    })
}

fn palette(
    output: &mut BoundedCursor,
    colors: &[[u8; 4]],
    names: &[Option<String>],
) -> Result<(), String> {
    chunk(output, 0x2019, |output| {
        let count = u32::try_from(colors.len()).map_err(|_| "Aseprite 调色板过大")?;
        dword(output, count)?;
        dword(output, 0)?;
        dword(output, count.checked_sub(1).ok_or("Aseprite 调色板为空")?)?;
        put(output, &[0; 8])?;
        for (index, color) in colors.iter().enumerate() {
            let name = names.get(index).and_then(Option::as_deref);
            word(output, u16::from(name.is_some()))?;
            put(output, color)?;
            if let Some(name) = name {
                string(output, name)?;
            }
        }
        Ok(())
    })
}

fn tags(output: &mut BoundedCursor, document: &Document, tags: &[FrameTag]) -> Result<(), String> {
    let frames = &document
        .animation
        .as_ref()
        .ok_or("Aseprite 标签缺少动画")?
        .frames;
    chunk(output, 0x2018, |output| {
        word(
            output,
            u16::try_from(tags.len()).map_err(|_| "Aseprite 标签过多")?,
        )?;
        put(output, &[0; 8])?;
        for tag in tags {
            let find = |id| {
                frames
                    .iter()
                    .position(|frame| frame.id == id)
                    .and_then(|index| u16::try_from(index).ok())
                    .ok_or("Aseprite 标签帧无效")
            };
            let from = find(tag.from_frame)?;
            let to = find(tag.to_frame)?;
            word(output, from.min(to))?;
            word(output, from.max(to))?;
            put(
                output,
                &[match tag.direction {
                    TagDirection::Forward => 0,
                    TagDirection::Reverse => 1,
                    TagDirection::PingPong => 2,
                    TagDirection::PingPongReverse => 3,
                }],
            )?;
            word(output, tag.repeat)?;
            put(output, &[0; 6])?;
            put(output, &[tag.color[0], tag.color[1], tag.color[2], 0])?;
            string(output, &tag.name)?;
        }
        Ok(())
    })?;
    for tag in tags {
        chunk(output, 0x2020, |output| {
            dword(output, 2)?;
            put(output, &tag.color)
        })?;
    }
    Ok(())
}

fn straight(pixel: &mut [u8]) {
    let alpha = u32::from(pixel[3]);
    for channel in &mut pixel[..3] {
        *channel = (u32::from(*channel) * 255 + alpha / 2)
            .checked_div(alpha)
            .unwrap_or(0)
            .min(255) as u8;
    }
}

fn write_source(
    output: &mut impl Write,
    document: &Document,
    source: &RasterPlane,
    bounds: Rect,
) -> Result<(), String> {
    let indexed = source.is_indexed();
    let pixel_bytes = if indexed { 1 } else { 4 };
    let mut row = vec![0; (bounds.right - bounds.left) as usize * pixel_bytes];
    let transparent = document
        .palette
        .as_ref()
        .map_or(0, |palette| palette.transparent);
    for y in bounds.top..bounds.bottom {
        row.fill(if indexed { transparent } else { 0 });
        for tx in bounds.left / TILE_SIZE..bounds.right.div_ceil(TILE_SIZE) {
            let Some(tile) = source.tiles().get(&(tx, y / TILE_SIZE)) else {
                continue;
            };
            let left = bounds.left.max(tx * TILE_SIZE);
            let right = bounds.right.min((tx + 1) * TILE_SIZE);
            let offset = ((y % TILE_SIZE * TILE_SIZE + left % TILE_SIZE) as usize) * pixel_bytes;
            let start = (left - bounds.left) as usize * pixel_bytes;
            let bytes = (right - left) as usize * pixel_bytes;
            row[start..start + bytes].copy_from_slice(&tile[offset..offset + bytes]);
        }
        if !indexed {
            for pixel in row.as_chunks_mut::<4>().0 {
                straight(pixel);
            }
        }
        put(output, &row)?;
    }
    Ok(())
}

fn write_composite(output: &mut impl Write, view: &Document, bounds: Rect) -> Result<(), String> {
    let width = (bounds.right - bounds.left) as usize;
    let rows = TILE_SIZE.min(bounds.bottom - bounds.top) as usize;
    let bytes = width
        .checked_mul(rows)
        .and_then(|value| value.checked_mul(4))
        .ok_or("Aseprite 合成带过大")?;
    if bytes + SCRATCH_RESERVE > MAX_ASEPRITE_SCRATCH_BYTES {
        return Err("Aseprite 导出工作内存超过限制".into());
    }
    let mut band = vec![0; bytes];
    let mut compositor = FrameCompositor::new(view, true);
    let mut top = bounds.top;
    while top < bounds.bottom {
        let height = (TILE_SIZE - top % TILE_SIZE).min(bounds.bottom - top);
        band.fill(0);
        for tx in bounds.left / TILE_SIZE..bounds.right.div_ceil(TILE_SIZE) {
            let tile = compositor.tile(view, (tx, top / TILE_SIZE));
            let left = bounds.left.max(tx * TILE_SIZE);
            let right = bounds.right.min((tx + 1) * TILE_SIZE);
            for y in 0..height {
                let offset = (((top + y) % TILE_SIZE * TILE_SIZE + left % TILE_SIZE) * 4) as usize;
                let start = (y as usize * width + (left - bounds.left) as usize) * 4;
                let bytes = ((right - left) * 4) as usize;
                band[start..start + bytes].copy_from_slice(&tile[offset..offset + bytes]);
            }
        }
        let bytes = height as usize * width * 4;
        for pixel in band[..bytes].as_chunks_mut::<4>().0 {
            straight(pixel);
        }
        put(output, &band[..bytes])?;
        top += height;
    }
    Ok(())
}

fn cel(
    output: &mut BoundedCursor,
    index: usize,
    bounds: Rect,
    linked: Option<u16>,
    pixels: impl FnOnce(&mut ZlibEncoder<&mut BoundedCursor>) -> Result<(), String>,
) -> Result<(), String> {
    chunk(output, 0x2005, |output| {
        word(
            output,
            u16::try_from(index).map_err(|_| "Aseprite 图层编号过大")?,
        )?;
        let x = i16::try_from(bounds.left).map_err(|_| "Aseprite Cel 位置无效")?;
        let y = i16::try_from(bounds.top).map_err(|_| "Aseprite Cel 位置无效")?;
        put(output, &x.to_le_bytes())?;
        put(output, &y.to_le_bytes())?;
        put(output, &[255])?;
        word(output, if linked.is_some() { 1 } else { 2 })?;
        word(output, 0)?;
        put(output, &[0; 5])?;
        if let Some(frame) = linked {
            return word(output, frame);
        }
        word(
            output,
            u16::try_from(bounds.right - bounds.left).map_err(|_| "Aseprite Cel 尺寸无效")?,
        )?;
        word(
            output,
            u16::try_from(bounds.bottom - bounds.top).map_err(|_| "Aseprite Cel 尺寸无效")?,
        )?;
        let mut encoder = ZlibEncoder::new(output, Compression::fast());
        pixels(&mut encoder)?;
        encoder
            .finish()
            .map_err(|_| "Aseprite 图像压缩失败或超过输出限制")?;
        Ok(())
    })
}

fn header(document: &Document, frames: usize, bake: bool) -> Result<[u8; 128], String> {
    let mut header = [0; 128];
    header[4..6].copy_from_slice(&0xa5e0u16.to_le_bytes());
    header[6..8].copy_from_slice(
        &u16::try_from(frames)
            .map_err(|_| "Aseprite 帧过多")?
            .to_le_bytes(),
    );
    header[8..10].copy_from_slice(
        &u16::try_from(document.width)
            .map_err(|_| "Aseprite 画布过大")?
            .to_le_bytes(),
    );
    header[10..12].copy_from_slice(
        &u16::try_from(document.height)
            .map_err(|_| "Aseprite 画布过大")?
            .to_le_bytes(),
    );
    let indexed = !bake && document.palette.is_some();
    header[12..14].copy_from_slice(&(if indexed { 8u16 } else { 32u16 }).to_le_bytes());
    let isolated = !bake
        && document.layers.iter().any(|layer| {
            matches!(
                layer.content,
                LayerContent::Group {
                    isolation: GroupIsolation::Isolated,
                    ..
                }
            )
        });
    header[14..18].copy_from_slice(&(if isolated { 3u32 } else { 1u32 }).to_le_bytes());
    let speed = document
        .animation
        .as_ref()
        .map_or(ASEPRITE_STILL_DURATION_MS, |animation| {
            animation.frames[0].duration_ms
        });
    header[18..20].copy_from_slice(
        &u16::try_from(speed)
            .map_err(|_| "Aseprite 帧时长无效")?
            .to_le_bytes(),
    );
    if indexed {
        header[28] = document.palette.as_ref().unwrap().transparent;
    }
    let palette = output_palette(document, bake);
    header[32..34]
        .copy_from_slice(&(palette.map_or(0, |(colors, _)| colors.len()) as u16).to_le_bytes());
    header[34] = 1;
    header[35] = 1;
    if let Some(metadata) = &document.aseprite_metadata {
        header[36..38].copy_from_slice(&metadata.grid.x.to_le_bytes());
        header[38..40].copy_from_slice(&metadata.grid.y.to_le_bytes());
        header[40..42].copy_from_slice(&metadata.grid.width.to_le_bytes());
        header[42..44].copy_from_slice(&metadata.grid.height.to_le_bytes());
    }
    Ok(header)
}

type PaletteView<'a> = (&'a [[u8; 4]], &'a [Option<String>]);
fn output_palette(document: &Document, bake: bool) -> Option<PaletteView<'_>> {
    if bake || document.palette.is_none() {
        if let Some(palette) = document
            .aseprite_metadata
            .as_ref()
            .and_then(|metadata| metadata.companion_palette.as_ref())
        {
            return Some((&palette.colors, &palette.names));
        }
    }
    document.palette.as_ref().map(|palette| {
        (
            palette.colors.as_slice(),
            document
                .aseprite_metadata
                .as_ref()
                .map_or(&[][..], |metadata| metadata.indexed_names.as_slice()),
        )
    })
}

pub(super) fn export(document: &Document, bake_layers: bool) -> Result<Vec<u8>, String> {
    encode(document, bake_layers, MAX_ASEPRITE_BYTES)
}

fn encode(document: &Document, bake_layers: bool, output_limit: usize) -> Result<Vec<u8>, String> {
    document.validate()?;
    let (editable, blocking) = super::issues(document);
    if !blocking.is_empty() {
        return Err(format!(
            "Aseprite 导出不支持：{}",
            blocking.keys().copied().collect::<Vec<_>>().join("、")
        ));
    }
    if !bake_layers && !editable.is_empty() {
        return Err(format!(
            "Aseprite 可编辑导出无法保留：{}；请明确选择合成副本",
            editable.keys().copied().collect::<Vec<_>>().join("、")
        ));
    }
    let frames = document
        .animation
        .as_ref()
        .map_or(1, |animation| animation.frames.len());
    let mut work = Work::default();
    let source_bounds = if bake_layers {
        BTreeMap::new()
    } else {
        editable_bounds(document, &mut work)?
    };
    let composite_bounds = if bake_layers {
        baked_bounds(document, frames, &mut work)?
    } else {
        vec![blank_bounds(); frames]
    };
    let hierarchy = Hierarchy::new(document)?;
    let mut output = BoundedCursor::new(output_limit);
    put(&mut output, &header(document, frames, bake_layers)?)?;
    let mut first_cels = BTreeMap::new();
    for (index, &bounds) in composite_bounds.iter().enumerate() {
        let duration = document
            .animation
            .as_ref()
            .map_or(ASEPRITE_STILL_DURATION_MS, |animation| {
                animation.frames[index].duration_ms
            });
        let start = position(&mut output)?;
        dword(&mut output, 0)?;
        word(&mut output, 0xf1fa)?;
        word(&mut output, 0)?;
        word(
            &mut output,
            u16::try_from(duration).map_err(|_| "Aseprite 帧时长无效")?,
        )?;
        word(&mut output, 0)?;
        dword(&mut output, 0)?;
        let mut chunks = 0u32;
        if index == 0 {
            if let Some(metadata) = &document.aseprite_metadata {
                chunk(&mut output, 0x2007, |output| {
                    word(output, u16::from(metadata.srgb))?;
                    word(output, 0)?;
                    dword(output, 0)?;
                    put(output, &[0; 8])
                })?;
                chunks += 1;
            }
            if let Some((colors, names)) = output_palette(document, bake_layers) {
                palette(&mut output, colors, names)?;
                chunks += 1;
            }
            if bake_layers {
                layer(&mut output, &Layer::new(1, "Composite".into()), 0)?;
                chunks += 1;
            } else {
                for (index, entry) in document.layers.iter().enumerate() {
                    layer(&mut output, entry, hierarchy.depth[index])?;
                    chunks += 1;
                }
            }
            if let Some(animation) = &document.animation {
                if !animation.tags.is_empty() {
                    tags(&mut output, document, &animation.tags)?;
                    chunks += 1 + animation.tags.len() as u32;
                }
            }
        }
        if bake_layers {
            let view = frame_view(document, index)?;
            cel(&mut output, 0, bounds, None, |output| {
                write_composite(output, &view, bounds)
            })?;
            chunks += 1;
        } else if let Some(animation) = &document.animation {
            for (layer_index, layer) in document.layers.iter().enumerate() {
                let Some(&id) = animation.frames[index].exposures.get(&layer.id) else {
                    continue;
                };
                let bounds = source_bounds[&id];
                let CelSource::Raster(source) = &animation.cels[&id].source else {
                    return Err("Aseprite 不支持矢量 Cel".into());
                };
                let linked = first_cels.get(&id).copied();
                cel(&mut output, layer_index, bounds, linked, |output| {
                    write_source(output, document, source, bounds)
                })?;
                first_cels.entry(id).or_insert(index as u16);
                chunks += 1;
            }
        } else {
            for (layer_index, layer) in document.layers.iter().enumerate() {
                if let Some(source) = layer.raster_opt() {
                    let bounds = source_bounds[&layer.id];
                    cel(&mut output, layer_index, bounds, None, |output| {
                        write_source(output, document, source, bounds)
                    })?;
                    chunks += 1;
                }
            }
        }
        let size = u32::try_from(position(&mut output)? - start).map_err(|_| "Aseprite 帧过大")?;
        patch(&mut output, start, &size.to_le_bytes())?;
        let old_count = u16::try_from(chunks).unwrap_or(u16::MAX);
        patch(&mut output, start + 6, &old_count.to_le_bytes())?;
        patch(&mut output, start + 12, &chunks.to_le_bytes())?;
    }
    let size = u32::try_from(position(&mut output)?).map_err(|_| "Aseprite 文件过大")?;
    patch(&mut output, 0, &size.to_le_bytes())?;
    Ok(output.into_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{animation, Engine};
    use std::sync::Arc;

    #[test]
    fn rejected_baked_work_is_preflighted_before_rendering_any_of_the_large_frames() {
        let mut doc = animation::enable(&Document::new(8192, 2048).unwrap(), 100).unwrap();
        let animation = Arc::make_mut(doc.animation.as_mut().unwrap());
        let frame = animation.frames[0].clone();
        animation.frames = (1..=17)
            .map(|id| {
                let mut frame = frame.clone();
                frame.id = id;
                frame
            })
            .collect();
        animation.next_frame_id = 18;
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&crate::storage::save(&doc).unwrap()).unwrap();
        let saved = engine.save().unwrap();
        let state = engine.state();
        let error = engine
            .export_aseprite(super::super::AsepriteExportRequest {
                revision: state["revision"].as_u64().unwrap(),
                bake_layers: true,
            })
            .unwrap_err();
        assert!(error.contains("工作量"));
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), saved);
    }

    #[test]
    fn header_and_zlib_trailer_output_limit_failures_discard_the_whole_file_and_keep_sources() {
        let mut doc = Document::new(2, 1).unwrap();
        let mut tile = vec![0; TILE_BYTES];
        tile[..8].copy_from_slice(&[255, 0, 0, 255, 0, 0, 128, 128]);
        doc.layers[0]
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert((0, 0), Arc::new(tile));
        let mut engine = Engine::new(2, 1).unwrap();
        engine.load(&crate::storage::save(&doc).unwrap()).unwrap();
        let saved = engine.save().unwrap();
        let state = engine.state();
        for bake in [false, true] {
            let bytes = encode(&engine.document, bake, MAX_ASEPRITE_BYTES).unwrap();
            for limit in [1, 128, bytes.len() - 1] {
                assert!(encode(&engine.document, bake, limit).is_err());
            }
        }
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), saved);
    }
}
