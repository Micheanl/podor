use super::{blend_from, GridMetadata, PaletteMetadata, ProjectMetadata};
use crate::{
    animation::{AnimationSet, Cel, CelKind, CelSource, Frame, FrameTag, TagDirection},
    model::*,
};
use flate2::{Decompress, FlushDecompress, Status};
use std::{collections::BTreeMap, sync::Arc};

struct Reader<'a> {
    bytes: &'a [u8],
    position: usize,
}
impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, position: 0 }
    }
    fn take(&mut self, count: usize) -> Result<&'a [u8], String> {
        let end = self
            .position
            .checked_add(count)
            .ok_or("Aseprite 数据长度无效")?;
        let bytes = self
            .bytes
            .get(self.position..end)
            .ok_or("Aseprite 数据被截断")?;
        self.position = end;
        Ok(bytes)
    }
    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, String> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn i16(&mut self) -> Result<i16, String> {
        Ok(i16::from_le_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn string(&mut self) -> Result<String, String> {
        let size = usize::from(self.u16()?);
        if size > MAX_ASEPRITE_NAME_BYTES {
            return Err("Aseprite 名称超出长度限制".into());
        }
        std::str::from_utf8(self.take(size)?)
            .map(str::to_owned)
            .map_err(|_| "Aseprite 名称不是有效 UTF-8".into())
    }
    fn remaining(&self) -> &'a [u8] {
        &self.bytes[self.position..]
    }
    fn finish(self) -> Result<(), String> {
        if self.position == self.bytes.len() {
            Ok(())
        } else {
            Err("Aseprite 数据包含未支持的附加字段".into())
        }
    }
}

#[derive(Clone, PartialEq)]
struct Palette {
    colors: Vec<[u8; 4]>,
    names: Vec<Option<String>>,
    known: Vec<bool>,
}
impl Palette {
    fn new(size: usize) -> Result<Self, String> {
        if size == 0 || size > MAX_INDEXED_COLORS {
            return Err("Aseprite 色板数量超出限制".into());
        }
        Ok(Self {
            colors: vec![[0, 0, 0, 255]; size],
            names: vec![None; size],
            known: vec![false; size],
        })
    }
    fn modern(&mut self, bytes: &[u8]) -> Result<(), String> {
        let mut r = Reader::new(bytes);
        let size = r.u32()? as usize;
        let first = r.u32()? as usize;
        let last = r.u32()? as usize;
        r.take(8)?;
        if size == 0 || size > MAX_INDEXED_COLORS || first > last || last >= size {
            return Err("Aseprite 色板范围无效".into());
        }
        self.colors.resize(size, [0, 0, 0, 255]);
        self.names.resize(size, None);
        self.known.resize(size, false);
        for index in first..=last {
            let flags = r.u16()?;
            if flags & !1 != 0 {
                return Err("Aseprite 色板属性暂不支持".into());
            }
            self.colors[index] = r.take(4)?.try_into().unwrap();
            self.known[index] = true;
            self.names[index] = if flags & 1 != 0 {
                Some(r.string()?)
            } else {
                None
            };
        }
        r.finish()
    }
    fn old(&mut self, bytes: &[u8], six: bool) -> Result<(), String> {
        let mut r = Reader::new(bytes);
        let packets = usize::from(r.u16()?);
        let mut first = 0usize;
        for _ in 0..packets {
            first = first
                .checked_add(usize::from(r.u8()?))
                .ok_or("Aseprite 色板范围无效")?;
            let count = usize::from(r.u8()?);
            let count = if count == 0 { 256 } else { count };
            if first
                .checked_add(count)
                .is_none_or(|end| end > self.colors.len())
            {
                return Err("Aseprite 色板范围无效".into());
            }
            for index in first..first + count {
                let rgb = r.take(3)?;
                if six && rgb.iter().any(|&value| value > 63) {
                    return Err("Aseprite 六位色板无效".into());
                }
                let convert = |value: u8| {
                    if six {
                        (value << 2) | (value >> 4)
                    } else {
                        value
                    }
                };
                self.colors[index] = [convert(rgb[0]), convert(rgb[1]), convert(rgb[2]), 255];
                self.names[index] = None;
                self.known[index] = true;
            }
        }
        r.finish()
    }
}

fn layer(bytes: &[u8], flags: u32, index: usize, parents: &mut Vec<u32>) -> Result<Layer, String> {
    let mut r = Reader::new(bytes);
    let attributes = r.u16()?;
    let kind = r.u16()?;
    let depth = usize::from(r.u16()?);
    r.take(4)?;
    let blend = r.u16()?;
    let opacity = r.u8()?;
    r.take(3)?;
    let name = r.string()?;
    r.finish()?;
    if attributes & !(1 | 2 | 32) != 0 || kind > 1 {
        return Err("Aseprite 图层类型或属性暂不支持".into());
    }
    if depth > MAX_GROUP_DEPTH || depth > parents.len() {
        return Err("Aseprite 图层层级无效".into());
    }
    parents.truncate(depth);
    let id = index as u32 + 1;
    let parent_id = parents.last().copied();
    let mut result = Layer::new(id, name);
    result.visible = attributes & 1 != 0;
    result.locked = attributes & 2 == 0;
    result.parent_id = parent_id;
    if kind == 1 {
        let isolated = flags & 2 != 0;
        result.content = LayerContent::Group {
            isolation: if isolated {
                GroupIsolation::Isolated
            } else {
                GroupIsolation::PassThrough
            },
            closed: attributes & 32 != 0,
        };
        if isolated {
            result.blend = blend_from(blend)?;
            if flags & 1 != 0 {
                result.opacity = f32::from(opacity) / 255.0;
            }
        }
        parents.push(id);
    } else {
        if attributes & 32 != 0 {
            return Err("Aseprite 像素图层折叠属性无效".into());
        }
        result.content = LayerContent::CelTrack {
            kind: CelKind::Raster,
        };
        result.blend = blend_from(blend)?;
        if flags & 1 != 0 {
            result.opacity = f32::from(opacity) / 255.0;
        }
    }
    Ok(result)
}

fn tags(bytes: &[u8], frame_count: usize) -> Result<Vec<FrameTag>, String> {
    let mut r = Reader::new(bytes);
    let count = usize::from(r.u16()?);
    r.take(8)?;
    if count > MAX_ANIMATION_TAGS {
        return Err("Aseprite 标签数量超出限制".into());
    }
    let mut result = Vec::with_capacity(count);
    for index in 0..count {
        let from = usize::from(r.u16()?);
        let to = usize::from(r.u16()?);
        let direction = match r.u8()? {
            0 => TagDirection::Forward,
            1 => TagDirection::Reverse,
            2 => TagDirection::PingPong,
            3 => TagDirection::PingPongReverse,
            _ => return Err("Aseprite 标签方向无效".into()),
        };
        let repeat = r.u16()?;
        r.take(6)?;
        let rgb = r.take(3)?;
        r.take(1)?;
        let color = [rgb[0], rgb[1], rgb[2], 255];
        let name = r.string()?;
        if from > to
            || to >= frame_count
            || repeat > 0
                && matches!(
                    direction,
                    TagDirection::PingPong | TagDirection::PingPongReverse
                )
        {
            return Err("Aseprite 标签范围或往返重复语义暂不支持".into());
        }
        result.push(FrameTag {
            id: index as u32 + 1,
            name,
            color,
            from_frame: from as u32 + 1,
            to_frame: to as u32 + 1,
            direction,
            repeat,
        });
    }
    r.finish()?;
    Ok(result)
}

struct Budget {
    decoded: u64,
    tiles: usize,
}
fn pixels(
    bytes: &[u8],
    compressed: bool,
    bounds: Rect,
    indexed: bool,
    palette: Option<&IndexedPalette>,
    budget: &mut Budget,
) -> Result<RasterPlane, String> {
    let width = bounds.right - bounds.left;
    let height = bounds.bottom - bounds.top;
    let x = bounds.left;
    let y = bounds.top;
    let channels = if indexed { 1 } else { 4 };
    let expected = u64::from(width) * u64::from(height) * channels;
    budget.decoded = budget
        .decoded
        .checked_add(expected)
        .ok_or("Aseprite 解码大小无效")?;
    if budget.decoded > MAX_ASEPRITE_DECODED_BYTES {
        return Err("Aseprite 解码像素超过限制".into());
    }
    let tile_bytes = if indexed {
        INDEX_TILE_BYTES
    } else {
        TILE_BYTES
    };
    let transparent = palette.map_or(0, |palette| palette.transparent);
    let mut plane = if indexed {
        RasterPlane::Indexed(BTreeMap::new())
    } else {
        RasterPlane::Rgba(BTreeMap::new())
    };
    let mut decoded = 0usize;
    let mut partial = [0u8; 4];
    let mut accept = |values: &[u8]| -> Result<(), String> {
        for &value in values {
            partial[decoded % channels as usize] = value;
            decoded += 1;
            if !decoded.is_multiple_of(channels as usize) {
                continue;
            }
            let index = decoded / channels as usize - 1;
            let px = x + index as u32 % width;
            let py = y + index as u32 / width;
            if indexed {
                if usize::from(partial[0]) >= palette.unwrap().colors.len() {
                    return Err("Aseprite 像素索引超出色板".into());
                }
                if partial[0] == transparent {
                    continue;
                }
            } else {
                let alpha = u32::from(partial[3]);
                for channel in &mut partial[..3] {
                    *channel = ((u32::from(*channel) * alpha + 127) / 255) as u8;
                }
                if partial == [0; 4] {
                    continue;
                }
            }
            let key = (px / TILE_SIZE, py / TILE_SIZE);
            if !plane.tiles().contains_key(&key) {
                budget.tiles = budget
                    .tiles
                    .checked_add(tile_bytes + 64)
                    .ok_or("Aseprite 工程内存超出限制")?;
                if budget.tiles > MAX_DOCUMENT_BYTES {
                    return Err("Aseprite 工程内存超出限制".into());
                }
                plane
                    .tiles_mut()
                    .insert(key, Arc::new(vec![transparent; tile_bytes]));
            }
            let tile = Arc::get_mut(plane.tiles_mut().get_mut(&key).unwrap()).unwrap();
            let offset =
                ((py % TILE_SIZE) * TILE_SIZE + px % TILE_SIZE) as usize * channels as usize;
            tile[offset..offset + channels as usize].copy_from_slice(&partial[..channels as usize]);
        }
        Ok(())
    };
    if compressed {
        let mut decoder = Decompress::new(true);
        let mut output = [0u8; 32 * 1024];
        loop {
            let before_in = decoder.total_in();
            let before_out = decoder.total_out();
            let status = decoder
                .decompress(
                    &bytes[before_in as usize..],
                    &mut output,
                    FlushDecompress::None,
                )
                .map_err(|_| "Aseprite zlib 像素无效")?;
            let count = (decoder.total_out() - before_out) as usize;
            if decoder.total_out() > expected {
                return Err("Aseprite zlib 解码大小超出声明".into());
            }
            accept(&output[..count])?;
            if status == Status::StreamEnd {
                if decoder.total_in() != bytes.len() as u64 || decoder.total_out() != expected {
                    return Err("Aseprite zlib 长度或尾随数据无效".into());
                }
                break;
            }
            if decoder.total_in() == before_in && count == 0 {
                return Err("Aseprite zlib 流被截断".into());
            }
        }
    } else {
        if bytes.len() as u64 != expected {
            return Err("Aseprite 像素长度无效".into());
        }
        accept(bytes)?;
    }
    Ok(plane)
}

pub(crate) fn import(bytes: &[u8]) -> Result<Document, String> {
    if bytes.len() < 128 || bytes.len() > MAX_ASEPRITE_BYTES {
        return Err("Aseprite 文件大小超出限制".into());
    }
    let mut r = Reader::new(bytes);
    if r.u32()? as usize != bytes.len() || r.u16()? != 0xa5e0 {
        return Err("Aseprite 文件头无效".into());
    }
    let frame_count = usize::from(r.u16()?);
    let width = u32::from(r.u16()?);
    let height = u32::from(r.u16()?);
    let depth = r.u16()?;
    let flags = r.u32()?;
    let speed = r.u16()?;
    r.take(8)?;
    let transparent = r.u8()?;
    r.take(3)?;
    let old_colors = r.u16()?;
    let px = r.u8()?;
    let py = r.u8()?;
    let grid = GridMetadata {
        x: r.i16()?,
        y: r.i16()?,
        width: r.u16()?,
        height: r.u16()?,
    };
    r.take(84)?;
    let mut document = Document::new(width, height)?;
    if frame_count == 0
        || frame_count > MAX_ANIMATION_FRAMES
        || !(depth == 8 || depth == 32)
        || flags & !3 != 0
        || px != 0 && py != 0 && px != py
    {
        return Err("Aseprite 帧数、颜色模式或配置暂不支持".into());
    }
    let indexed = depth == 8;
    let initial_colors = if old_colors == 0 {
        256
    } else {
        usize::from(old_colors)
    };
    if initial_colors > MAX_INDEXED_COLORS {
        return Err("Aseprite 色板数量超出限制".into());
    }
    let mut global_palette: Option<Palette> = None;
    let mut modern_palette_seen = false;
    let mut srgb = false;
    let mut profile: Option<bool> = None;
    let mut frames = Vec::with_capacity(frame_count);
    let mut cels: BTreeMap<u32, Arc<Cel>> = BTreeMap::new();
    let mut all_tags = Vec::new();
    let mut positions: BTreeMap<(usize, usize), (u32, u32)> = BTreeMap::new();
    let mut parents = Vec::new();
    let mut layers = Vec::new();
    let mut budget = Budget {
        decoded: 0,
        tiles: 0,
    };
    let mut next_cel = 1u32;
    for frame_index in 0..frame_count {
        let size = r.u32()? as usize;
        if size < 16 {
            return Err("Aseprite 帧长度无效".into());
        }
        let mut frame = Reader::new(r.take(size - 4)?);
        if frame.u16()? != 0xf1fa {
            return Err("Aseprite 帧标识无效".into());
        }
        let old_chunks = u32::from(frame.u16()?);
        let duration = frame.u16()?;
        frame.take(2)?;
        let new_chunks = frame.u32()?;
        let count = if new_chunks == 0 {
            old_chunks
        } else {
            new_chunks
        };
        if count as usize > MAX_ASEPRITE_FRAME_CHUNKS
            || count as usize > frame.remaining().len() / 6
        {
            return Err("Aseprite 区块数量无效或超出限制".into());
        }
        let duration = if duration == 0 {
            u32::from(speed)
        } else {
            u32::from(duration)
        };
        if duration == 0 || duration > MAX_FRAME_DURATION_MS {
            return Err("Aseprite 帧时长无效".into());
        }
        let mut raw_cels = Vec::new();
        let mut modern = Vec::new();
        let mut old = Vec::new();
        let mut tag_cursor: Option<usize> = None;
        for _ in 0..count {
            let length = frame.u32()? as usize;
            let kind = frame.u16()?;
            if length < 6 {
                return Err("Aseprite 区块长度无效".into());
            }
            let payload = frame.take(length - 6)?;
            if kind != 0x2020 {
                tag_cursor = None;
            }
            match kind {
                0x2004 => {
                    if frame_index != 0 || layers.len() >= MAX_LAYER_NODES {
                        return Err("Aseprite 图层布局或数量无效".into());
                    }
                    layers.push(layer(payload, flags, layers.len(), &mut parents)?);
                }
                0x2005 => {
                    if raw_cels.len() >= MAX_LAYERS {
                        return Err("Aseprite 帧图像数量超出限制".into());
                    }
                    raw_cels.push(payload);
                }
                0x2019 => modern.push(payload),
                0x0004 | 0x0011 => old.push((payload, kind == 0x0011)),
                0x2007 => {
                    let mut c = Reader::new(payload);
                    let kind = c.u16()?;
                    let attributes = c.u16()?;
                    c.u32()?;
                    c.take(8)?;
                    c.finish()?;
                    if kind > 1 || attributes != 0 {
                        return Err("Aseprite ICC 或固定伽马配置暂不支持".into());
                    }
                    let value = kind == 1;
                    if profile.is_some_and(|previous| previous != value) {
                        return Err("逐帧 Aseprite 色彩配置暂不支持".into());
                    }
                    profile = Some(value);
                    srgb = value;
                }
                0x2018 => {
                    if frame_index != 0 || !all_tags.is_empty() {
                        return Err("Aseprite 重复或逐帧标签暂不支持".into());
                    }
                    all_tags = tags(payload, frame_count)?;
                    tag_cursor = Some(0);
                }
                0x2020 => {
                    let mut data = Reader::new(payload);
                    let flags = data.u32()?;
                    if flags == 2 {
                        let index = tag_cursor.ok_or("Aseprite 非标签用户数据暂不支持")?;
                        let tag = all_tags
                            .get_mut(index)
                            .ok_or("Aseprite 标签用户数据数量无效")?;
                        tag.color = data.take(4)?.try_into().unwrap();
                        tag_cursor = Some(index + 1);
                    } else if flags == 0 {
                        if let Some(index) = tag_cursor {
                            tag_cursor = Some(index + 1);
                        }
                    } else {
                        return Err("Aseprite 文本或属性用户数据暂不支持".into());
                    }
                    data.finish()?;
                }
                _ => return Err(format!("Aseprite 区块 0x{kind:04x} 暂不支持，工程未导入")),
            }
        }
        frame.finish()?;
        let mut palette = global_palette
            .clone()
            .unwrap_or(Palette::new(initial_colors)?);
        if !modern.is_empty() {
            modern_palette_seen = true;
            for payload in modern {
                palette.modern(payload)?;
            }
        } else if !modern_palette_seen {
            for (payload, six) in old {
                palette.old(payload, six)?;
            }
        }
        let have_palette = palette.known.iter().any(|known| *known);
        if (indexed || have_palette) && palette.known.iter().any(|known| !*known) {
            return Err("Aseprite 初始色板数据不完整".into());
        }
        if have_palette {
            if global_palette
                .as_ref()
                .is_some_and(|previous| previous != &palette)
            {
                return Err("逐帧 Aseprite 色板或名称暂不支持".into());
            }
            global_palette = Some(palette);
        }
        if indexed {
            let palette = global_palette.as_ref().ok_or("Aseprite 索引工程缺少色板")?;
            if palette.colors.len() < 2 || usize::from(transparent) >= palette.colors.len() {
                return Err("Aseprite 透明色槽无效".into());
            }
            let mut colors = palette.colors.clone();
            colors[usize::from(transparent)][3] = 0;
            document.palette = Some(IndexedPalette {
                order: (0..colors.len()).map(|index| index as u8).collect(),
                colors,
                transparent,
            });
        }
        let mut exposures = BTreeMap::new();
        for bytes in raw_cels {
            let mut cel = Reader::new(bytes);
            let layer_index = usize::from(cel.u16()?);
            let x = cel.i16()?;
            let y = cel.i16()?;
            let opacity = cel.u8()?;
            let kind = cel.u16()?;
            let z = cel.i16()?;
            cel.take(5)?;
            let layer = layers
                .get(layer_index)
                .ok_or("Aseprite 图像引用的图层不存在")?;
            if !matches!(layer.content, LayerContent::CelTrack { .. })
                || opacity != 255
                || z != 0
                || x < 0
                || y < 0
                || exposures.contains_key(&layer.id)
            {
                return Err("Aseprite 图像位置、不透明度或层叠偏移暂不支持".into());
            }
            let x = x as u32;
            let y = y as u32;
            let id = if kind == 1 {
                let previous = usize::from(cel.u16()?);
                cel.finish()?;
                if previous >= frame_index {
                    return Err("Aseprite 链接图像必须引用之前的帧".into());
                }
                let id = *frames
                    .get(previous)
                    .and_then(|frame: &Frame| frame.exposures.get(&layer.id))
                    .ok_or("Aseprite 链接图像不存在")?;
                if positions.get(&(previous, layer_index)) != Some(&(x, y)) {
                    return Err("不同放置位置的链接图像暂不支持".into());
                }
                id
            } else if kind == 0 || kind == 2 {
                let w = u32::from(cel.u16()?);
                let h = u32::from(cel.u16()?);
                if w == 0
                    || h == 0
                    || x.checked_add(w).is_none_or(|right| right > width)
                    || y.checked_add(h).is_none_or(|bottom| bottom > height)
                {
                    return Err("Aseprite 图像尺寸超出画布".into());
                }
                if cels.len() >= MAX_ANIMATION_CELS {
                    return Err("Aseprite 图像数量超出限制".into());
                }
                let raster = pixels(
                    cel.remaining(),
                    kind == 2,
                    Rect {
                        left: x,
                        top: y,
                        right: x + w,
                        bottom: y + h,
                    },
                    indexed,
                    document.palette.as_ref(),
                    &mut budget,
                )?;
                let id = next_cel;
                next_cel += 1;
                cels.insert(
                    id,
                    Arc::new(Cel {
                        id,
                        layer_id: layer.id,
                        source: CelSource::Raster(Arc::new(raster)),
                        masks: Vec::new(),
                    }),
                );
                id
            } else {
                return Err("Aseprite 图块图像暂不支持".into());
            };
            exposures.insert(layer.id, id);
            positions.insert((frame_index, layer_index), (x, y));
        }
        frames.push(Frame {
            id: frame_index as u32 + 1,
            duration_ms: duration,
            exposures,
        });
    }
    r.finish()?;
    if layers.is_empty() {
        return Err("Aseprite 工程没有图层".into());
    }
    document.active = layers
        .iter()
        .find(|layer| !layer.is_group())
        .map_or(layers[0].id, |layer| layer.id);
    document.next_id = layers.len() as u32 + 1;
    document.layers = layers;
    let metadata = ProjectMetadata {
        companion_palette: if indexed {
            None
        } else {
            global_palette.as_ref().map(|palette| PaletteMetadata {
                colors: palette.colors.clone(),
                names: palette.names.clone(),
            })
        },
        indexed_names: if indexed {
            global_palette.unwrap().names
        } else {
            Vec::new()
        },
        grid,
        srgb,
    };
    document.aseprite_metadata = Some(Arc::new(metadata));
    document.animation = Some(Arc::new(AnimationSet {
        frames,
        cels,
        active_frame: 1,
        next_frame_id: frame_count as u32 + 1,
        next_cel_id: next_cel,
        next_tag_id: all_tags.len() as u32 + 1,
        tags: all_tags,
    }));
    document.validate()?;
    Ok(document)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cumulative_decode_and_padded_tile_budgets_reject_before_allocating_another_plane() {
        let rect = Rect {
            left: 0,
            top: 0,
            right: 1,
            bottom: 1,
        };
        let mut budget = Budget {
            decoded: MAX_ASEPRITE_DECODED_BYTES - 4,
            tiles: 0,
        };
        assert!(pixels(&[255, 0, 0, 255], false, rect, false, None, &mut budget).is_ok());
        assert_eq!(budget.decoded, MAX_ASEPRITE_DECODED_BYTES);
        let old_tiles = budget.tiles;
        assert!(pixels(&[255, 0, 0, 255], false, rect, false, None, &mut budget).is_err());
        assert_eq!(budget.tiles, old_tiles);
        let mut budget = Budget {
            decoded: 0,
            tiles: MAX_DOCUMENT_BYTES - TILE_BYTES - 64,
        };
        assert!(pixels(&[255, 0, 0, 255], false, rect, false, None, &mut budget).is_ok());
        assert_eq!(budget.tiles, MAX_DOCUMENT_BYTES);
        assert!(pixels(&[255, 0, 0, 255], false, rect, false, None, &mut budget).is_err());
        let palette = IndexedPalette {
            colors: vec![[0; 4], [0; 4]],
            transparent: 0,
            order: vec![0, 1],
        };
        let mut budget = Budget {
            decoded: 0,
            tiles: MAX_DOCUMENT_BYTES - INDEX_TILE_BYTES - 64,
        };
        let plane = pixels(&[1], false, rect, true, Some(&palette), &mut budget).unwrap();
        assert_eq!(plane.tiles()[&(0, 0)][0], 1);
        assert_eq!(budget.tiles, MAX_DOCUMENT_BYTES);
        assert!(pixels(&[1], false, rect, true, Some(&palette), &mut budget).is_err());
    }
}
