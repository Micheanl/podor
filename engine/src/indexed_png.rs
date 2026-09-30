use crate::{
    indexed::nearest_color,
    model::*,
    raster::FrameCompositor,
    storage::{unpremultiply, IndexedExportPolicy, MAX_FILE_BYTES},
};
use std::{collections::BTreeMap, io::Write};

pub(crate) struct DecodedIndexedPng {
    pub width: u32,
    pub height: u32,
    pub colors: Vec<[u8; 4]>,
    pub indices: Vec<u8>,
    pub transparent: Option<u8>,
}

pub(crate) fn decode(bytes: &[u8]) -> Result<DecodedIndexedPng, String> {
    if bytes.len() > MAX_FILE_BYTES {
        return Err("PNG 文件过大".into());
    }
    let mut decoder = png::Decoder::new(bytes);
    decoder.set_limits(png::Limits {
        bytes: MAX_DOCUMENT_BYTES,
    });
    decoder.set_ignore_text_chunk(true);
    decoder.set_ignore_iccp_chunk(true);
    decoder.set_transformations(png::Transformations::IDENTITY);
    let mut reader = decoder.read_info().map_err(|_| "无法读取索引 PNG 图片")?;
    let info = reader.info();
    let (width, height) = (info.width, info.height);
    if width == 0
        || height == 0
        || width > MAX_DIMENSION
        || height > MAX_DIMENSION
        || u64::from(width) * u64::from(height) > MAX_PIXELS
    {
        return Err("PNG 图片尺寸超出限制".into());
    }
    if info.color_type != png::ColorType::Indexed {
        return Err("图片不是索引色 PNG".into());
    }
    if info.animation_control.is_some() {
        return Err("暂不支持动画 PNG，请先导出为静态图片".into());
    }
    let depth = info.bit_depth as u8;
    if !matches!(depth, 1 | 2 | 4 | 8) {
        return Err("索引 PNG 位深无效".into());
    }
    let palette = info.palette.as_ref().ok_or("索引 PNG 缺少调色板")?;
    let count = palette.len() / 3;
    if !palette.len().is_multiple_of(3) || !(1..=256).contains(&count) || count > (1 << depth) {
        return Err("索引 PNG 调色板无效".into());
    }
    let alpha = info.trns.as_deref().unwrap_or_default();
    if alpha.len() > count {
        return Err("索引 PNG 透明度数量超过调色板".into());
    }
    let colors: Vec<_> = palette
        .as_chunks::<3>()
        .0
        .iter()
        .enumerate()
        .map(|(index, rgb)| {
            [
                rgb[0],
                rgb[1],
                rgb[2],
                alpha.get(index).copied().unwrap_or(255),
            ]
        })
        .collect();
    let transparent = colors
        .iter()
        .position(|color| color[3] == 0)
        .map(|index| index as u8);
    let mut pixels = vec![0; reader.output_buffer_size()];
    let output = reader
        .next_frame(&mut pixels)
        .map_err(|_| "索引 PNG 图片数据损坏")?;
    reader.finish().map_err(|_| "索引 PNG 图片数据损坏")?;
    let mut indices = Vec::with_capacity(width as usize * height as usize);
    for row in pixels[..output.buffer_size()].chunks_exact(output.line_size) {
        for x in 0..width as usize {
            let bit = x * depth as usize;
            let index =
                (row[bit / 8] >> (8 - depth - (bit % 8) as u8)) & ((1u16 << depth) - 1) as u8;
            if index as usize >= count {
                return Err("PNG 像素索引超出调色板".into());
            }
            indices.push(index);
        }
    }
    Ok(DecodedIndexedPng {
        width,
        height,
        colors,
        indices,
        transparent,
    })
}

pub(crate) fn export(
    doc: &Document,
    transparent: bool,
    policy: IndexedExportPolicy,
) -> Result<Vec<u8>, String> {
    doc.validate()?;
    let palette = doc.palette.as_ref().ok_or("请先转换为索引色工程")?;
    let mut colors = palette.colors.clone();
    if !transparent {
        for color in &mut colors {
            let alpha = u32::from(color[3]);
            for value in &mut color[..3] {
                *value = ((u32::from(*value) * alpha + 127) / 255 + 255 - alpha) as u8;
            }
            color[3] = 255;
        }
    }
    let mut visible = doc
        .layers
        .iter()
        .filter(|layer| layer.visible && layer.opacity > 0.0);
    let first = visible.next();
    let direct = first.filter(|layer| {
        visible.next().is_none()
            && !doc
                .layers
                .iter()
                .any(|layer| layer.is_group() || layer.is_adjustment())
            && layer.raster_opt().is_some_and(RasterPlane::is_indexed)
            && layer.opacity == 1.0
            && layer.blend == BlendMode::Normal
            && !layer.has_enabled_masks()
    });
    let mut lookup = BTreeMap::new();
    for (index, color) in colors.iter().enumerate() {
        lookup.entry(*color).or_insert(index as u8);
    }
    let mut output = Vec::new();
    let mut encoder = png::Encoder::new(&mut output, doc.width, doc.height);
    encoder.set_color(png::ColorType::Indexed);
    encoder.set_depth(png::BitDepth::Eight);
    encoder.set_palette(
        colors
            .iter()
            .flat_map(|color| color[..3].iter().copied())
            .collect::<Vec<_>>(),
    );
    if let Some(last) = colors.iter().rposition(|color| color[3] != 255) {
        encoder.set_trns(
            colors[..=last]
                .iter()
                .map(|color| color[3])
                .collect::<Vec<_>>(),
        );
    }
    let mut writer = encoder.write_header().map_err(|error| error.to_string())?;
    let mut stream = writer.stream_writer().map_err(|error| error.to_string())?;
    let mut row = vec![palette.transparent; doc.width as usize];
    let mut band = Vec::new();
    let mut compositor = FrameCompositor::new(doc, transparent);
    for y in 0..doc.height {
        if let Some(layer) = direct {
            row.fill(palette.transparent);
            for tx in 0..doc.width.div_ceil(TILE_SIZE) {
                if let Some(tile) = layer.raster()?.tiles().get(&(tx, y / TILE_SIZE)) {
                    let left = (tx * TILE_SIZE) as usize;
                    let count = (row.len() - left).min(TILE_SIZE as usize);
                    let source = (y % TILE_SIZE * TILE_SIZE) as usize;
                    row[left..left + count].copy_from_slice(&tile[source..source + count]);
                }
            }
        } else {
            if y.is_multiple_of(TILE_SIZE) {
                band.clear();
                for tx in 0..doc.width.div_ceil(TILE_SIZE) {
                    band.push(compositor.tile(doc, (tx, y / TILE_SIZE)));
                }
            }
            for (tx, tile) in band.iter().enumerate() {
                let left = tx * TILE_SIZE as usize;
                let count = (row.len() - left).min(TILE_SIZE as usize);
                let source = (y % TILE_SIZE * TILE_SIZE * 4) as usize;
                for (target, pixel) in row[left..left + count]
                    .iter_mut()
                    .zip(tile[source..source + count * 4].as_chunks::<4>().0)
                {
                    let mut color = *pixel;
                    if transparent {
                        unpremultiply(&mut color);
                    }
                    *target = if transparent && color[3] == 0 {
                        palette.transparent
                    } else if let Some(&index) = lookup.get(&color) {
                        index
                    } else {
                        if matches!(policy, IndexedExportPolicy::Exact) {
                            return Err("合成颜色不在调色板中，请选择量化导出或普通 PNG".into());
                        }
                        let index = nearest_color(&colors, *pixel);
                        if lookup.len() < MAX_INDEXED_EXPORT_CACHE_ENTRIES {
                            lookup.insert(color, index);
                        }
                        index
                    };
                }
            }
        }
        stream.write_all(&row).map_err(|error| error.to_string())?;
    }
    stream.finish().map_err(|error| error.to_string())?;
    writer.finish().map_err(|error| error.to_string())?;
    Ok(output)
}
