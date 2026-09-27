use crate::{model::*, storage::MAX_FILE_BYTES};
use std::{
    collections::btree_map::Entry,
    io::{Cursor, Read},
    sync::Arc,
};
use zip::{CompressionMethod, ZipArchive};

pub fn load(bytes: &[u8]) -> Result<Document, String> {
    let entries = validate_directory(bytes)?;
    let mut archive = ZipArchive::new(Cursor::new(bytes)).map_err(|_| "ORA 压缩包已损坏")?;
    if archive.len() != entries || archive.name_for_index(0) != Some("mimetype") {
        return Err("ORA 文件目录无效".into());
    }
    if archive
        .by_index(0)
        .map_err(|_| "ORA 类型信息无效")?
        .compression()
        != CompressionMethod::Stored
        || read_metadata(&mut archive, "mimetype", 16)? != b"image/openraster"
    {
        return Err("这不是有效的 ORA 文件".into());
    }
    let xml = read_metadata(&mut archive, "stack.xml", MAX_ORA_METADATA_BYTES)?;
    let xml = std::str::from_utf8(&xml).map_err(|_| "ORA 图层信息无效")?;
    let tree = roxmltree::Document::parse_with_options(
        xml,
        roxmltree::ParsingOptions {
            nodes_limit: MAX_ORA_XML_NODES,
            ..Default::default()
        },
    )
    .map_err(|_| "ORA 图层信息无效")?;
    let root = tree.root_element();
    if !root.has_tag_name("image") {
        return Err("ORA 图层信息无效".into());
    }
    let width = attribute::<u32>(root, "w", None)?;
    let height = attribute::<u32>(root, "h", None)?;
    let mut doc = Document::new(width, height)?;
    let stacks: Vec<_> = root.children().filter(|node| node.is_element()).collect();
    if stacks.len() != 1 || !stacks[0].has_tag_name("stack") {
        return Err("ORA 图层信息无效".into());
    }
    let nodes: Vec<_> = stacks[0]
        .children()
        .filter(|node| node.is_element())
        .collect();
    if nodes.len() > MAX_LAYERS {
        return Err("ORA 图层数量超过上限".into());
    }
    if nodes.iter().any(|node| node.has_tag_name("stack")) {
        return Err("暂不支持含图层组的 ORA，请先在原软件中取消分组".into());
    }
    if nodes
        .iter()
        .any(|node| !node.has_tag_name("layer") || node.children().any(|child| child.is_element()))
    {
        return Err("ORA 含有暂不支持的图层类型".into());
    }
    if nodes.is_empty() {
        return Ok(doc);
    }
    doc.layers.clear();
    let mut remaining_tiles = MAX_DOCUMENT_BYTES / TILE_BYTES;
    let mut selected = None;
    for (index, node) in nodes.into_iter().rev().enumerate() {
        let id = index as u32 + 1;
        let mut layer = Layer::new(
            id,
            node.attribute("name")
                .filter(|value| !value.is_empty())
                .map(str::to_owned)
                .unwrap_or_else(|| format!("图层 {id}")),
        );
        if layer.name.len() > MAX_LAYER_NAME_BYTES {
            return Err("ORA 图层名称过长".into());
        }
        layer.opacity = attribute(node, "opacity", Some(1.0f32))?;
        if !layer.opacity.is_finite() || !(0.0..=1.0).contains(&layer.opacity) {
            return Err("ORA 图层不透明度无效".into());
        }
        layer.visible = match node.attribute("visibility").unwrap_or("visible") {
            "visible" => true,
            "hidden" => false,
            _ => return Err("ORA 图层可见性无效".into()),
        };
        layer.blend = match node.attribute("composite-op").unwrap_or("svg:src-over") {
            "svg:src-over" => BlendMode::Normal,
            "svg:multiply" => BlendMode::Multiply,
            "svg:screen" => BlendMode::Screen,
            "svg:overlay" => BlendMode::Overlay,
            "svg:soft-light" => BlendMode::SoftLight,
            "svg:darken" => BlendMode::Darken,
            "svg:lighten" => BlendMode::Lighten,
            "svg:difference" => BlendMode::Difference,
            _ => return Err("ORA 使用了暂不支持的混合模式".into()),
        };
        let x = attribute(node, "x", Some(0i32))?;
        let y = attribute(node, "y", Some(0i32))?;
        let path = node.attribute("src").ok_or("ORA 缺少图层图像")?;
        if path.contains(['\\', ':'])
            || path
                .split('/')
                .any(|part| part.is_empty() || part == "." || part == "..")
        {
            return Err("ORA 图层路径无效".into());
        }
        if !path.to_ascii_lowercase().ends_with(".png") {
            return Err("ORA 图层必须为 PNG 图片".into());
        }
        let mut file = archive.by_name(path).map_err(|_| "ORA 图层图像无法读取")?;
        if file.size() > MAX_FILE_BYTES as u64 {
            return Err("ORA 图层图像过大".into());
        }
        let mut bounded = (&mut file).take(MAX_FILE_BYTES as u64 + 1);
        read_layer(
            &mut bounded,
            &mut layer,
            doc.bounds(),
            x,
            y,
            &mut remaining_tiles,
        )?;
        std::io::copy(&mut bounded, &mut std::io::sink()).map_err(|_| "ORA 图层图像已损坏")?;
        if bounded.limit() == 0 {
            return Err("ORA 图层图像过大".into());
        }
        if node.attribute("selected") == Some("true") {
            selected = Some(id);
        }
        doc.layers.push(layer);
    }
    doc.next_id = doc.layers.len() as u32 + 1;
    doc.active = selected.unwrap_or(doc.next_id - 1);
    doc.validate()?;
    Ok(doc)
}

fn validate_directory(bytes: &[u8]) -> Result<usize, String> {
    let start = bytes.len().saturating_sub(22 + u16::MAX as usize);
    let end = bytes[start..]
        .windows(22)
        .enumerate()
        .rfind(|(offset, entry)| {
            entry.starts_with(b"PK\x05\x06")
                && start
                    + offset
                    + 22
                    + usize::from(u16::from_le_bytes(entry[20..22].try_into().unwrap()))
                    == bytes.len()
        })
        .map(|(offset, _)| offset + start)
        .ok_or("ORA 文件目录无效")?;
    let tail = &bytes[end..];
    let short = |at| u16::from_le_bytes(tail[at..at + 2].try_into().unwrap()) as usize;
    let long = |at| u32::from_le_bytes(tail[at..at + 4].try_into().unwrap()) as usize;
    if short(4) != 0
        || short(6) != 0
        || short(8) != short(10)
        || short(10) > MAX_ORA_ENTRIES
        || long(12) > MAX_ORA_METADATA_BYTES
        || long(16).checked_add(long(12)) != Some(end)
        || 22 + short(20) != tail.len()
    {
        return Err("ORA 文件目录过大或格式不受支持".into());
    }
    Ok(short(10))
}

fn read_metadata(
    archive: &mut ZipArchive<Cursor<&[u8]>>,
    path: &str,
    limit: usize,
) -> Result<Vec<u8>, String> {
    let file = archive.by_name(path).map_err(|_| "ORA 缺少必要文件")?;
    if file.size() > limit as u64 {
        return Err("ORA 图层信息过大".into());
    }
    let mut bytes = Vec::new();
    file.take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "ORA 文件已损坏")?;
    if bytes.len() > limit {
        return Err("ORA 图层信息过大".into());
    }
    Ok(bytes)
}

fn attribute<T: std::str::FromStr>(
    node: roxmltree::Node<'_, '_>,
    name: &str,
    default: Option<T>,
) -> Result<T, String> {
    match node.attribute(name) {
        Some(value) => value.parse().map_err(|_| "ORA 图层属性无效".into()),
        None => default.ok_or_else(|| "ORA 缺少画布尺寸".into()),
    }
}

fn read_layer(
    input: &mut impl Read,
    layer: &mut Layer,
    bounds: Rect,
    x: i32,
    y: i32,
    remaining_tiles: &mut usize,
) -> Result<(), String> {
    let mut decoder = png::Decoder::new(input);
    decoder.set_limits(png::Limits {
        bytes: MAX_DOCUMENT_BYTES,
    });
    decoder.set_ignore_text_chunk(true);
    decoder.set_ignore_iccp_chunk(true);
    decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let mut reader = decoder.read_info().map_err(|_| "ORA 图层 PNG 无法读取")?;
    let width = reader.info().width;
    let height = reader.info().height;
    Document::new(width, height)?;
    if reader.info().animation_control.is_some() {
        return Err("ORA 图层不支持动画 PNG".into());
    }
    let color = reader.output_color_type().0;
    if reader.info().interlaced {
        let mut pixels = vec![0; reader.output_buffer_size()];
        let info = reader
            .next_frame(&mut pixels)
            .map_err(|_| "ORA 图层 PNG 已损坏")?;
        for (row, bytes) in pixels[..info.buffer_size()]
            .chunks_exact(info.line_size)
            .enumerate()
        {
            write_row(
                layer,
                bounds,
                x,
                i64::from(y) + row as i64,
                bytes,
                color,
                remaining_tiles,
            )?;
        }
    } else {
        for row in 0..height {
            let bytes = reader
                .next_row()
                .map_err(|_| "ORA 图层 PNG 已损坏")?
                .ok_or("ORA 图层 PNG 已损坏")?;
            write_row(
                layer,
                bounds,
                x,
                i64::from(y) + i64::from(row),
                bytes.data(),
                color,
                remaining_tiles,
            )?;
        }
    }
    reader.finish().map_err(|_| "ORA 图层 PNG 已损坏".into())
}

fn write_row(
    layer: &mut Layer,
    bounds: Rect,
    x: i32,
    y: i64,
    row: &[u8],
    color: png::ColorType,
    remaining_tiles: &mut usize,
) -> Result<(), String> {
    if y < 0 || y >= i64::from(bounds.bottom) {
        return Ok(());
    }
    let channels = color.samples();
    let left = i64::from(x).max(0);
    let right = (i64::from(x) + (row.len() / channels) as i64).min(i64::from(bounds.right));
    if right <= left {
        return Ok(());
    }
    let (left, right, y) = (left as u32, right as u32, y as u32);
    for tx in left / TILE_SIZE..right.div_ceil(TILE_SIZE) {
        let begin = (tx * TILE_SIZE).max(left);
        let end = ((tx + 1) * TILE_SIZE).min(right);
        let source = ((i64::from(begin) - i64::from(x)) as usize) * channels;
        let count = (end - begin) as usize;
        let bytes = &row[source..source + count * channels];
        if matches!(color, png::ColorType::Rgba | png::ColorType::GrayscaleAlpha)
            && bytes
                .chunks_exact(channels)
                .all(|pixel| pixel[channels - 1] == 0)
        {
            continue;
        }
        let tile = match layer.tiles.entry((tx, y / TILE_SIZE)) {
            Entry::Occupied(entry) => entry.into_mut(),
            Entry::Vacant(entry) => {
                if *remaining_tiles == 0 {
                    return Err("工程像素超过内存限制".into());
                }
                *remaining_tiles -= 1;
                entry.insert(Arc::new(vec![0; TILE_BYTES]))
            }
        };
        let target = ((y % TILE_SIZE * TILE_SIZE + begin % TILE_SIZE) * 4) as usize;
        let tile = Arc::get_mut(tile).unwrap();
        for (source, target) in bytes
            .chunks_exact(channels)
            .zip(tile[target..target + count * 4].as_chunks_mut::<4>().0)
        {
            let (rgb, alpha) = match color {
                png::ColorType::Rgb => ([source[0], source[1], source[2]], 255),
                png::ColorType::Rgba => ([source[0], source[1], source[2]], source[3]),
                png::ColorType::Grayscale => ([source[0]; 3], 255),
                png::ColorType::GrayscaleAlpha => ([source[0]; 3], source[1]),
                _ => return Err("ORA 图层 PNG 颜色格式不受支持".into()),
            };
            for c in 0..3 {
                target[c] = ((u32::from(rgb[c]) * u32::from(alpha) + 127) / 255) as u8;
            }
            target[3] = alpha;
        }
    }
    Ok(())
}
