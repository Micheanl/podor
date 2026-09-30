use crate::{
    animation::{self, FrameTag, TagDirection},
    model::*,
    raster::FrameCompositor,
    Engine,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    borrow::Cow,
    collections::{BTreeMap, BTreeSet},
    io::{self, Seek, SeekFrom, Write},
};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

#[derive(Clone, Debug, Deserialize)]
#[serde(tag = "format", rename_all = "snake_case", deny_unknown_fields)]
pub enum AnimationExportRequest {
    Gif {
        revision: u64,
        scope: ExportScope,
        #[serde(default)]
        color_policy: ColorPolicy,
        #[serde(default)]
        alpha: AlphaPolicy,
        #[serde(default)]
        timing: TimingPolicy,
    },
    Atlas {
        revision: u64,
        scope: ExportScope,
        #[serde(default)]
        columns: u32,
        #[serde(default)]
        padding: u32,
    },
}

#[derive(Clone, Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum ExportScope {
    All {
        direction: TagDirection,
        repeat: u16,
    },
    Range {
        from_frame: u32,
        to_frame: u32,
        direction: TagDirection,
        repeat: u16,
    },
    Tag {
        id: u32,
    },
}

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ColorPolicy {
    #[default]
    Exact,
    Quantize,
}

#[derive(Clone, Copy, Debug, Default, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum AlphaPolicy {
    #[default]
    Exact,
    Matte {
        color: [u8; 3],
    },
    Threshold {
        cutoff: u8,
    },
}

#[derive(Clone, Copy, Debug, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TimingPolicy {
    #[default]
    Exact,
    Round,
}

impl AnimationExportRequest {
    fn revision(&self) -> u64 {
        match self {
            Self::Gif { revision, .. } | Self::Atlas { revision, .. } => *revision,
        }
    }
    fn scope(&self) -> &ExportScope {
        match self {
            Self::Gif { scope, .. } | Self::Atlas { scope, .. } => scope,
        }
    }
}

impl Engine {
    pub(crate) fn animation_export_snapshot(
        &self,
        request: &AnimationExportRequest,
    ) -> Result<Document, String> {
        if request.revision() != self.revision {
            return Err("动画导出请求已过期".into());
        }
        if self.has_live_stroke() {
            return Err("请先结束笔画再导出动画".into());
        }
        if self.document.animation.is_none() {
            return Err("工程没有动画".into());
        }
        Ok(self.document.clone())
    }
    pub fn export_animation(&self, request: AnimationExportRequest) -> Result<Vec<u8>, String> {
        export(&self.animation_export_snapshot(&request)?, request)
    }
}

pub(crate) fn capabilities() -> Value {
    json!({"operation":26,"maxFrames":MAX_ANIMATION_FRAMES,
        "maxSequenceFrames":MAX_ANIMATION_EXPORT_SEQUENCE,"maxPixels":MAX_PIXELS,
        "maxPixelVisits":MAX_ANIMATION_EXPORT_PIXEL_VISITS,"maxOutputBytes":MAX_ANIMATION_EXPORT_BYTES,
        "maxScratchBytes":MAX_ANIMATION_EXPORT_SCRATCH_BYTES,"maxAtlasDimension":MAX_DIMENSION,
        "maxAtlasPadding":MAX_ATLAS_PADDING})
}

struct Plan {
    frames: Vec<u32>,
    sequence: Vec<u32>,
    direction: TagDirection,
    repeat: u16,
    tag: Option<FrameTag>,
}

fn plan(document: &Document, scope: &ExportScope) -> Result<Plan, String> {
    let animation = document.animation.as_ref().ok_or("工程没有动画")?;
    let (endpoints, direction, repeat, tag) = match scope {
        ExportScope::All { direction, repeat } => (None, *direction, *repeat, None),
        ExportScope::Range {
            from_frame,
            to_frame,
            direction,
            repeat,
        } => (Some((*from_frame, *to_frame)), *direction, *repeat, None),
        ExportScope::Tag { id } => {
            let tag = animation
                .tags
                .iter()
                .find(|tag| tag.id == *id)
                .ok_or("动画标签不存在")?;
            (
                Some((tag.from_frame, tag.to_frame)),
                tag.direction,
                tag.repeat,
                Some(tag.clone()),
            )
        }
    };
    let span = if let Some((a, b)) = endpoints {
        let find = |id| {
            animation
                .frames
                .iter()
                .position(|frame| frame.id == id)
                .ok_or("导出范围中的帧不存在")
        };
        let a = find(a)?;
        let b = find(b)?;
        a.min(b)..=a.max(b)
    } else {
        0..=animation.frames.len().checked_sub(1).ok_or("动画没有帧")?
    };
    let frames: Vec<_> = animation.frames[span]
        .iter()
        .map(|frame| frame.id)
        .collect();
    if frames.is_empty() || frames.len() > MAX_ANIMATION_FRAMES {
        return Err("导出帧数量超出限制".into());
    }
    let mut sequence = frames.clone();
    if matches!(
        direction,
        TagDirection::Reverse | TagDirection::PingPongReverse
    ) {
        sequence.reverse();
    }
    if matches!(
        direction,
        TagDirection::PingPong | TagDirection::PingPongReverse
    ) && sequence.len() > 2
    {
        let back: Vec<_> = sequence[1..sequence.len() - 1]
            .iter()
            .rev()
            .copied()
            .collect();
        sequence.extend(back);
    }
    if sequence.len() > MAX_ANIMATION_EXPORT_SEQUENCE {
        return Err("动画播放序列超出限制".into());
    }
    Ok(Plan {
        frames,
        sequence,
        direction,
        repeat,
        tag,
    })
}

fn visits(document: &Document, count: usize, extra: u64) -> Result<(), String> {
    let pixels = u64::from(document.width) * u64::from(document.height);
    if pixels
        .checked_mul(count as u64)
        .and_then(|value| value.checked_add(extra))
        .is_none_or(|value| value > MAX_ANIMATION_EXPORT_PIXEL_VISITS)
    {
        return Err("动画导出总处理像素超出限制，请缩小范围或画布".into());
    }
    Ok(())
}

pub(crate) fn export(
    document: &Document,
    request: AnimationExportRequest,
) -> Result<Vec<u8>, String> {
    let plan = plan(document, request.scope())?;
    match request {
        AnimationExportRequest::Gif {
            color_policy,
            alpha,
            timing,
            ..
        } => {
            visits(document, plan.frames.len() + plan.sequence.len(), 0)?;
            document.validate()?;
            gif(
                document,
                &plan,
                color_policy,
                alpha,
                timing,
                MAX_ANIMATION_EXPORT_BYTES,
            )
        }
        AnimationExportRequest::Atlas {
            columns, padding, ..
        } => {
            let grid = Grid::new(document, plan.frames.len(), columns, padding)?;
            visits(
                document,
                plan.frames.len(),
                u64::from(grid.width) * u64::from(grid.height),
            )?;
            document.validate()?;
            atlas(document, &plan, grid, MAX_ANIMATION_EXPORT_BYTES)
        }
    }
}

fn walk(
    document: &Document,
    frame: u32,
    mut pixel: impl FnMut(usize, [u8; 4]) -> Result<(), String>,
) -> Result<(), String> {
    let view = animation::view(document, frame)?;
    let mut compositor = FrameCompositor::new(&view, true);
    for ty in 0..view.height.div_ceil(TILE_SIZE) {
        for tx in 0..view.width.div_ceil(TILE_SIZE) {
            let tile = compositor.tile(&view, (tx, ty));
            for y in 0..TILE_SIZE.min(view.height - ty * TILE_SIZE) {
                for x in 0..TILE_SIZE.min(view.width - tx * TILE_SIZE) {
                    let offset = ((y * TILE_SIZE + x) * 4) as usize;
                    let index = ((ty * TILE_SIZE + y) * view.width + tx * TILE_SIZE + x) as usize;
                    pixel(index, tile[offset..offset + 4].try_into().unwrap())?;
                }
            }
        }
    }
    Ok(())
}

fn normalize(mut color: [u8; 4], policy: AlphaPolicy) -> Result<[u8; 4], String> {
    match policy {
        AlphaPolicy::Exact if color[3] != 0 && color[3] != 255 => {
            return Err("GIF 不能精确保留半透明像素，请明确选择底色合成或透明阈值".into());
        }
        AlphaPolicy::Matte { color: matte } => {
            let remaining = u32::from(255 - color[3]);
            for index in 0..3 {
                color[index] = (u32::from(color[index])
                    + (u32::from(matte[index]) * remaining + 127) / 255)
                    .min(255) as u8;
            }
            color[3] = 255;
            return Ok(color);
        }
        AlphaPolicy::Threshold { cutoff } => {
            if cutoff == 0 {
                return Err("透明阈值必须在 1 到 255 之间".into());
            }
            if color[3] < cutoff {
                return Ok([0; 4]);
            }
            crate::storage::unpremultiply(&mut color);
            color[3] = 255;
        }
        _ => {}
    }
    if color[3] == 0 {
        color = [0; 4];
    }
    Ok(color)
}

fn delay(duration: u32, timing: TimingPolicy) -> Result<u16, String> {
    if matches!(timing, TimingPolicy::Exact) && !duration.is_multiple_of(10) {
        return Err("GIF 帧时长需为 10 ms 的倍数，请明确选择时长取整".into());
    }
    Ok(((duration + 5) / 10).max(1) as u16)
}

struct GifFrameInfo {
    colors: BTreeSet<[u8; 3]>,
    delay: u16,
}

fn random(value: u64) -> u64 {
    let mut value = value.wrapping_add(0x9e3779b97f4a7c15);
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d049bb133111eb);
    value ^ (value >> 31)
}

fn gif(
    document: &Document,
    plan: &Plan,
    policy: ColorPolicy,
    alpha: AlphaPolicy,
    timing: TimingPolicy,
    output_limit: usize,
) -> Result<Vec<u8>, String> {
    if matches!(alpha, AlphaPolicy::Threshold { cutoff: 0 }) {
        return Err("透明阈值必须在 1 到 255 之间".into());
    }
    let pixel_count = document.width as usize * document.height as usize;
    if pixel_count + 8 * 1024 * 1024 > MAX_ANIMATION_EXPORT_SCRATCH_BYTES {
        return Err("GIF 导出工作内存超出限制".into());
    }
    let mut infos = BTreeMap::new();
    let mut transparent = false;
    let mut shared_colors = BTreeSet::new();
    let mut samples = Vec::new();
    let quota = MAX_GIF_SAMPLES / plan.frames.len();
    for &id in &plan.frames {
        let duration = document.animation.as_ref().unwrap().frame(id)?.duration_ms;
        let frame_delay = delay(duration, timing)?;
        let mut colors = BTreeSet::new();
        let mut reservoir: Vec<[u8; 4]> = Vec::new();
        let mut seen = 0u64;
        walk(document, id, |_, pixel| {
            let color = normalize(pixel, alpha)?;
            if color[3] == 0 {
                transparent = true;
            } else if policy == ColorPolicy::Exact {
                colors.insert(color[..3].try_into().unwrap());
                if colors.len() > 256 {
                    return Err("GIF 精确导出每帧不能超过 256 个颜色，请明确选择颜色量化".into());
                }
            } else {
                if shared_colors.len() <= 256 {
                    shared_colors.insert([color[0], color[1], color[2]]);
                }
                seen += 1;
                if reservoir.len() < quota {
                    reservoir.push(color);
                } else {
                    let target = random(seen ^ (u64::from(id) << 32)) % seen;
                    if target > 0 && target < quota as u64 {
                        reservoir[target as usize] = color;
                    }
                }
            }
            Ok(())
        })?;
        samples.extend(reservoir.into_iter().flatten());
        infos.insert(
            id,
            GifFrameInfo {
                colors,
                delay: frame_delay,
            },
        );
    }
    if transparent && infos.values().any(|info| info.colors.len() > 255) {
        return Err(
            "透明 GIF 需保留透明槽，每帧最多 255 个不透明颜色，请明确选择量化或底色合成".into(),
        );
    }
    let shared_exact = (policy == ColorPolicy::Quantize
        && shared_colors.len() <= if transparent { 255 } else { 256 })
    .then(|| {
        shared_colors
            .iter()
            .enumerate()
            .map(|(index, color)| (*color, (index + usize::from(transparent)) as u8))
            .collect::<BTreeMap<[u8; 3], u8>>()
    });
    let quantizer =
        if policy == ColorPolicy::Quantize && shared_exact.is_none() && !samples.is_empty() {
            Some(color_quant::NeuQuant::new(
                if samples.len() < 4096 { 1 } else { 10 },
                if transparent { 255 } else { 256 },
                &samples,
            ))
        } else {
            None
        };
    drop(samples);
    let mut palette = Vec::new();
    if transparent {
        palette.extend([0; 3]);
    }
    if let Some(quantizer) = &quantizer {
        palette.extend(quantizer.color_map_rgb());
    } else if let Some(colors) = &shared_exact {
        palette.extend(colors.keys().flatten());
    }
    if palette.is_empty() {
        palette.extend([0; 3]);
    }
    let global = if policy == ColorPolicy::Quantize {
        palette.as_slice()
    } else {
        &[0; 6]
    };
    let mut encoder = gif::Encoder::new(
        BoundedCursor::new(output_limit),
        document.width as u16,
        document.height as u16,
        global,
    )
    .map_err(|error| error.to_string())?;
    encoder
        .set_repeat(if plan.repeat == 0 {
            gif::Repeat::Infinite
        } else {
            gif::Repeat::Finite(plan.repeat - 1)
        })
        .map_err(|error| error.to_string())?;
    let mut indices = vec![0; pixel_count];
    let mut cache: BTreeMap<[u8; 4], u8> = BTreeMap::new();
    for &id in &plan.sequence {
        let info = &infos[&id];
        let local_palette = if policy == ColorPolicy::Exact {
            let mut palette = Vec::new();
            if transparent {
                palette.extend([0; 3]);
            }
            palette.extend(info.colors.iter().flatten());
            if palette.is_empty() {
                palette.extend([0; 3]);
            }
            Some(palette)
        } else {
            None
        };
        let exact: BTreeMap<[u8; 3], u8> = info
            .colors
            .iter()
            .enumerate()
            .map(|(index, color)| (*color, (index + usize::from(transparent)) as u8))
            .collect();
        walk(document, id, |index, pixel| {
            let color = normalize(pixel, alpha)?;
            indices[index] = if color[3] == 0 {
                0
            } else if policy == ColorPolicy::Exact {
                exact[&[color[0], color[1], color[2]]]
            } else if let Some(colors) = &shared_exact {
                colors[&[color[0], color[1], color[2]]]
            } else if let Some(&index) = cache.get(&color) {
                index
            } else {
                let index = quantizer
                    .as_ref()
                    .ok_or("GIF 量化采样无效")?
                    .index_of(&color)
                    + usize::from(transparent);
                let index = index as u8;
                if cache.len() < MAX_INDEXED_EXPORT_CACHE_ENTRIES {
                    cache.insert(color, index);
                }
                index
            };
            Ok(())
        })?;
        let mut encoded =
            BoundedCursor::new(MAX_ANIMATION_EXPORT_SCRATCH_BYTES - pixel_count - 8 * 1024 * 1024);
        lzw(&indices, &mut encoded)?;
        let frame = gif::Frame {
            width: document.width as u16,
            height: document.height as u16,
            delay: info.delay,
            dispose: if transparent {
                gif::DisposalMethod::Background
            } else {
                gif::DisposalMethod::Keep
            },
            transparent: transparent.then_some(0),
            palette: local_palette,
            buffer: Cow::Borrowed(&encoded.bytes),
            ..Default::default()
        };
        encoder
            .write_lzw_pre_encoded_frame(&frame)
            .map_err(|error| error.to_string())?;
    }
    Ok(encoder
        .into_inner()
        .map_err(|error| error.to_string())?
        .bytes)
}

fn lzw(indices: &[u8], output: &mut BoundedCursor) -> Result<(), String> {
    output.bytes.clear();
    output.position = 0;
    let max = indices.iter().copied().max().unwrap_or(0);
    let bits = (u32::from(max) + 1)
        .max(4)
        .next_power_of_two()
        .trailing_zeros() as u8;
    output
        .write_all(&[bits])
        .map_err(|error| error.to_string())?;
    let mut encoder = weezl::encode::Encoder::new(weezl::BitOrder::Lsb, bits);
    encoder.finish();
    let mut offset = 0;
    let mut chunk = [0; 4096];
    loop {
        let result = encoder.encode_bytes(&indices[offset..], &mut chunk);
        offset += result.consumed_in;
        output
            .write_all(&chunk[..result.consumed_out])
            .map_err(|error| error.to_string())?;
        match result.status.map_err(|error| error.to_string())? {
            weezl::LzwStatus::Done => break,
            _ if result.consumed_in == 0 && result.consumed_out == 0 => {
                return Err("GIF 压缩未取得进展".into())
            }
            _ => {}
        }
    }
    if offset != indices.len() {
        return Err("GIF 压缩像素数量不一致".into());
    }
    Ok(())
}

pub(crate) struct BoundedCursor {
    bytes: Vec<u8>,
    position: usize,
    limit: usize,
}

impl BoundedCursor {
    pub(crate) fn new(limit: usize) -> Self {
        Self {
            bytes: Vec::new(),
            position: 0,
            limit,
        }
    }
    pub(crate) fn into_bytes(self) -> Vec<u8> {
        self.bytes
    }
}

impl Write for BoundedCursor {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        let end = self
            .position
            .checked_add(bytes.len())
            .filter(|&end| end <= self.limit)
            .ok_or_else(|| io::Error::other("动画导出缓冲超出限制"))?;
        if end > self.bytes.len() {
            if end > self.bytes.capacity() {
                let target = end
                    .max(self.bytes.capacity().max(4096).saturating_mul(2))
                    .min(self.limit);
                self.bytes
                    .try_reserve_exact(target - self.bytes.len())
                    .map_err(io::Error::other)?;
            }
            self.bytes.resize(end, 0);
        }
        self.bytes[self.position..end].copy_from_slice(bytes);
        self.position = end;
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Seek for BoundedCursor {
    fn seek(&mut self, position: SeekFrom) -> io::Result<u64> {
        let next = match position {
            SeekFrom::Start(value) => i128::from(value),
            SeekFrom::End(value) => self.bytes.len() as i128 + i128::from(value),
            SeekFrom::Current(value) => self.position as i128 + i128::from(value),
        };
        if next < 0 || next > self.limit as i128 {
            return Err(io::Error::other("动画导出偏移超出限制"));
        }
        self.position = next as usize;
        Ok(self.position as u64)
    }
}

#[derive(Clone, Copy)]
struct Grid {
    columns: u32,
    width: u32,
    height: u32,
    cell_width: u32,
    cell_height: u32,
    padding: u32,
}

impl Grid {
    fn new(document: &Document, count: usize, columns: u32, padding: u32) -> Result<Self, String> {
        if padding > MAX_ATLAS_PADDING || columns > count as u32 {
            return Err("图集列数或边距超出限制".into());
        }
        let cell_width = document
            .width
            .checked_add(padding * 2)
            .ok_or("图集尺寸超出限制")?;
        let cell_height = document
            .height
            .checked_add(padding * 2)
            .ok_or("图集尺寸超出限制")?;
        let candidate = |columns| {
            let width = cell_width.checked_mul(columns)?;
            let height = cell_height.checked_mul((count as u32).div_ceil(columns))?;
            (width <= MAX_DIMENSION
                && height <= MAX_DIMENSION
                && u64::from(width) * u64::from(height) <= MAX_PIXELS)
                .then_some(Self {
                    columns,
                    width,
                    height,
                    cell_width,
                    cell_height,
                    padding,
                })
        };
        if columns > 0 {
            return candidate(columns).ok_or("图集尺寸超出限制，请减少帧或修改列数".into());
        }
        let ideal = (count as f64 * f64::from(cell_height) / f64::from(cell_width))
            .sqrt()
            .ceil() as u32;
        (1..=count as u32)
            .filter_map(candidate)
            .min_by_key(|grid| {
                (
                    grid.columns.abs_diff(ideal.clamp(1, count as u32)),
                    grid.columns,
                )
            })
            .ok_or_else(|| "图集尺寸超出限制，请减少帧或缩小画布".into())
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AtlasFrame {
    id: u32,
    duration_ms: u32,
    rect: AtlasRect,
}
#[derive(Serialize)]
struct AtlasRect {
    x: u32,
    y: u32,
    width: u32,
    height: u32,
}

fn atlas(
    document: &Document,
    plan: &Plan,
    grid: Grid,
    output_limit: usize,
) -> Result<Vec<u8>, String> {
    let animation = document.animation.as_ref().unwrap();
    let mut frames = Vec::new();
    for (index, &id) in plan.frames.iter().enumerate() {
        frames.push(AtlasFrame {
            id,
            duration_ms: animation.frame(id)?.duration_ms,
            rect: AtlasRect {
                x: index as u32 % grid.columns * grid.cell_width + grid.padding,
                y: index as u32 / grid.columns * grid.cell_height + grid.padding,
                width: document.width,
                height: document.height,
            },
        });
    }
    let tag = plan.tag.as_ref().map(|tag| {
        json!({"id":tag.id,"name":tag.name,"color":tag.color,
        "fromFrame":tag.from_frame,"toFrame":tag.to_frame})
    });
    let metadata = serde_json::to_vec(&json!({"format":"podor_atlas","version":1,"image":"atlas.png",
        "width":grid.width,"height":grid.height,"sourceWidth":document.width,"sourceHeight":document.height,
        "columns":grid.columns,"padding":grid.padding,"frames":frames,
        "playback":{"frameIds":plan.sequence,"direction":plan.direction,"repeat":plan.repeat},"tag":tag}))
        .map_err(|error| error.to_string())?;
    if metadata.len() > MAX_ATLAS_METADATA_BYTES {
        return Err("图集元数据超出限制".into());
    }
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    let mut archive = ZipWriter::new(BoundedCursor::new(output_limit));
    archive
        .start_file("atlas.png", options)
        .map_err(|error| error.to_string())?;
    {
        let mut encoder = png::Encoder::new(&mut archive, grid.width, grid.height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().map_err(|error| error.to_string())?;
        {
            let mut stream = writer.stream_writer().map_err(|error| error.to_string())?;
            let mut band = vec![0; grid.width as usize * TILE_SIZE.min(grid.height) as usize * 4];
            for top in (0..grid.height).step_by(TILE_SIZE as usize) {
                let rows = TILE_SIZE.min(grid.height - top);
                band.fill(0);
                for frame in &frames {
                    let rect = &frame.rect;
                    let start = top.max(rect.y);
                    let end = (top + rows).min(rect.y + rect.height);
                    if start >= end {
                        continue;
                    }
                    let view = animation::view(document, frame.id)?;
                    let mut compositor = FrameCompositor::new(&view, true);
                    for ty in (start - rect.y) / TILE_SIZE..=(end - 1 - rect.y) / TILE_SIZE {
                        for tx in 0..view.width.div_ceil(TILE_SIZE) {
                            let tile = compositor.tile(&view, (tx, ty));
                            let width = TILE_SIZE.min(view.width - tx * TILE_SIZE) as usize * 4;
                            for y in start.max(rect.y + ty * TILE_SIZE)
                                ..end.min(rect.y + (ty + 1) * TILE_SIZE)
                            {
                                let source =
                                    ((y - rect.y - ty * TILE_SIZE) * TILE_SIZE * 4) as usize;
                                let dest = (((y - top) * grid.width + rect.x + tx * TILE_SIZE) * 4)
                                    as usize;
                                band[dest..dest + width]
                                    .copy_from_slice(&tile[source..source + width]);
                            }
                        }
                    }
                }
                let output = &mut band[..(grid.width * rows * 4) as usize];
                crate::storage::unpremultiply(output);
                stream
                    .write_all(output)
                    .map_err(|error| error.to_string())?;
            }
            stream.finish().map_err(|error| error.to_string())?;
        }
        writer.finish().map_err(|error| error.to_string())?;
    }
    archive
        .start_file(
            "atlas.json",
            options.compression_method(CompressionMethod::Deflated),
        )
        .map_err(|error| error.to_string())?;
    archive
        .write_all(&metadata)
        .map_err(|error| error.to_string())?;
    Ok(archive.finish().map_err(|error| error.to_string())?.bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn output_budget_failure_at_codec_trailers_discards_partial_files_and_preserves_sources() {
        let document = animation::enable(&Document::new(4, 2).unwrap(), 100).unwrap();
        let saved = crate::storage::save(&document).unwrap();
        let scope = ExportScope::All {
            direction: TagDirection::Forward,
            repeat: 1,
        };
        let plan = plan(&document, &scope).unwrap();
        let gif_bytes = gif(
            &document,
            &plan,
            ColorPolicy::Exact,
            AlphaPolicy::Exact,
            TimingPolicy::Exact,
            MAX_ANIMATION_EXPORT_BYTES,
        )
        .unwrap();
        assert!(gif(
            &document,
            &plan,
            ColorPolicy::Exact,
            AlphaPolicy::Exact,
            TimingPolicy::Exact,
            gif_bytes.len() - 1
        )
        .is_err());
        let grid = Grid::new(&document, 1, 0, 0).unwrap();
        let atlas_bytes = atlas(&document, &plan, grid, MAX_ANIMATION_EXPORT_BYTES).unwrap();
        assert_eq!(
            atlas(&document, &plan, grid, MAX_ANIMATION_EXPORT_BYTES).unwrap(),
            atlas_bytes
        );
        assert!(atlas(&document, &plan, grid, atlas_bytes.len() - 1).is_err());
        assert_eq!(crate::storage::save(&document).unwrap(), saved);
    }

    #[test]
    fn output_and_seek_limits_fail_without_partial_buffer_mutation() {
        let mut output = BoundedCursor::new(5);
        output.write_all(b"abcd").unwrap();
        assert!(output.write_all(b"ef").is_err());
        assert_eq!(output.bytes, b"abcd");
        assert_eq!(output.position, 4);
        assert!(output.seek(SeekFrom::Start(6)).is_err());
        assert!(output.seek(SeekFrom::Current(-5)).is_err());
        assert_eq!(output.position, 4);
        output.seek(SeekFrom::Start(1)).unwrap();
        output.write_all(b"xy").unwrap();
        assert_eq!(output.bytes, b"axyd");
    }

    #[test]
    fn checked_grid_and_work_budgets_reject_large_sparse_exports() {
        let document = Document::new(MAX_DIMENSION, 2048).unwrap();
        assert!(Grid::new(&document, 2, 0, 0).is_err());
        assert!(Grid::new(&document, 1, 1, 1).is_err());
        assert!(visits(&document, 17, 0).is_err());
        assert!(visits(&document, 16, 0).is_ok());
        let narrow = Document::new(8192, 1).unwrap();
        let grid = Grid::new(&narrow, 256, 0, 0).unwrap();
        assert_eq!(grid.columns, 1);
        assert_eq!((grid.width, grid.height), (8192, 256));
    }

    #[test]
    fn bounded_lzw_matches_external_decoder_at_small_and_entropy_boundaries() {
        for size in [1, 3, 128, 65536] {
            let indices: Vec<_> = (0..size).map(|index| random(index as u64) as u8).collect();
            let mut encoded = BoundedCursor::new(size * 2 + 8192);
            lzw(&indices, &mut encoded).unwrap();
            let mut decoder = weezl::decode::Decoder::new(weezl::BitOrder::Lsb, encoded.bytes[0]);
            assert_eq!(decoder.decode(&encoded.bytes[1..]).unwrap(), indices);
            assert!(encoded.bytes.len() <= size * 2 + 8192);
        }
        let mut encoded = BoundedCursor::new(1);
        assert!(lzw(&[0; 16], &mut encoded).is_err());
    }
}
