use crate::{model::*, raster::composite_tile_background, storage::unpremultiply};
use std::io::{Cursor, Write};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

mod import;
pub use import::load;

pub fn export(doc: &Document) -> Result<Vec<u8>, String> {
    doc.validate()?;
    if doc
        .layers
        .iter()
        .any(|layer| matches!(layer.content, LayerContent::Vector(_)))
    {
        return Err("ORA 尚不能保留可编辑矢量图层，请保存 podor 工程或选择烘焙副本导出".into());
    }
    if doc.layers.iter().any(Layer::is_adjustment) {
        return Err("ORA 尚不能保留可编辑调整图层，请保存 podor 工程或选择烘焙副本导出".into());
    }
    if doc.layers.iter().any(|layer| layer.clipping) {
        return Err("ORA 不能保留剪贴蒙版，请改用 podor 工程或 PSD".into());
    }
    if doc.layers.iter().any(|layer| !layer.masks.is_empty()) {
        return Err("ORA 不能保留独立图层蒙版，请改用 podor 工程或 PSD".into());
    }
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    let mut archive = ZipWriter::new(Cursor::new(Vec::new()));
    archive
        .start_file("mimetype", options)
        .map_err(|e| e.to_string())?;
    archive
        .write_all(b"image/openraster")
        .map_err(|e| e.to_string())?;
    let mut stack = format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<image version=\"0.0.6\" w=\"{}\" h=\"{}\"><stack>\n",
        doc.width, doc.height
    );
    write_stack(doc, None, &mut archive, options, &mut stack)?;
    stack.push_str("</stack></image>\n");
    archive
        .start_file("stack.xml", options)
        .map_err(|e| e.to_string())?;
    archive
        .write_all(stack.as_bytes())
        .map_err(|e| e.to_string())?;

    let longest = doc.width.max(doc.height).max(EXPORT_THUMBNAIL_EDGE);
    let thumb_width = (doc.width * EXPORT_THUMBNAIL_EDGE / longest).max(1);
    let thumb_height = (doc.height * EXPORT_THUMBNAIL_EDGE / longest).max(1);
    let mut sums = vec![[0u64; 4]; (thumb_width * thumb_height) as usize];
    let mut band = Vec::new();
    archive
        .start_file("mergedimage.png", options)
        .map_err(|e| e.to_string())?;
    write_png(&mut archive, doc.width, doc.height, true, |y, row| {
        if y % TILE_SIZE == 0 {
            band.clear();
            for tx in 0..doc.width.div_ceil(TILE_SIZE) {
                band.push(composite_tile_background(doc, (tx, y / TILE_SIZE), true));
            }
        }
        for (tx, tile) in band.iter().enumerate() {
            let left = tx * TILE_SIZE as usize * 4;
            let count = (row.len() - left).min(TILE_SIZE as usize * 4);
            let source = (y % TILE_SIZE * TILE_SIZE * 4) as usize;
            row[left..left + count].copy_from_slice(&tile[source..source + count]);
        }
        let thumb_row = y * thumb_height / doc.height * thumb_width;
        for (x, pixel) in row.as_chunks::<4>().0.iter().enumerate() {
            let sum = &mut sums[(thumb_row + x as u32 * thumb_width / doc.width) as usize];
            for channel in 0..4 {
                sum[channel] += u64::from(pixel[channel]);
            }
        }
    })?;
    drop(band);
    archive
        .start_file("Thumbnails/thumbnail.png", options)
        .map_err(|e| e.to_string())?;
    write_png(&mut archive, thumb_width, thumb_height, true, |y, row| {
        let rows =
            ((y + 1) * doc.height).div_ceil(thumb_height) - (y * doc.height).div_ceil(thumb_height);
        for (x, pixel) in row.as_chunks_mut::<4>().0.iter_mut().enumerate() {
            let x = x as u32;
            let columns =
                ((x + 1) * doc.width).div_ceil(thumb_width) - (x * doc.width).div_ceil(thumb_width);
            let count = u64::from(rows * columns);
            let sum = sums[(y * thumb_width + x) as usize];
            for channel in 0..4 {
                pixel[channel] = ((sum[channel] + count / 2) / count).min(255) as u8;
            }
        }
    })?;
    let output = archive.finish().map_err(|e| e.to_string())?.into_inner();
    if output.len() > crate::storage::MAX_FILE_BYTES {
        return Err("文件过大".into());
    }
    Ok(output)
}

fn write_stack(
    doc: &Document,
    parent: Option<u32>,
    archive: &mut ZipWriter<Cursor<Vec<u8>>>,
    options: SimpleFileOptions,
    stack: &mut String,
) -> Result<(), String> {
    for layer in doc
        .layers
        .iter()
        .rev()
        .filter(|layer| layer.parent_id == parent)
    {
        let mode = match layer.blend {
            BlendMode::Normal => "svg:src-over",
            BlendMode::Multiply => "svg:multiply",
            BlendMode::Screen => "svg:screen",
            BlendMode::Overlay => "svg:overlay",
            BlendMode::SoftLight => "svg:soft-light",
            BlendMode::Darken => "svg:darken",
            BlendMode::Lighten => "svg:lighten",
            BlendMode::Difference => "svg:difference",
        };
        let properties = format!(
            "name=\"{}\" opacity=\"{}\" visibility=\"{}\" composite-op=\"{mode}\" selected=\"{}\"",
            xml_name(&layer.name)?,
            layer.opacity,
            if layer.visible { "visible" } else { "hidden" },
            layer.id == doc.active,
        );
        if let LayerContent::Group { isolation, .. } = layer.content {
            let isolation = match isolation {
                GroupIsolation::Isolated => "isolate",
                GroupIsolation::PassThrough => "auto",
            };
            stack.push_str(&format!("<stack {properties} isolation=\"{isolation}\">\n"));
            write_stack(doc, Some(layer.id), archive, options, stack)?;
            stack.push_str("</stack>\n");
            continue;
        }
        let raster = layer.raster()?;
        let region = layer_bounds(doc, layer);
        let path = format!("data/layer-{}.png", layer.id);
        stack.push_str(&format!(
            "<layer {properties} src=\"{path}\" x=\"{}\" y=\"{}\"/>\n",
            region.left, region.top,
        ));
        archive
            .start_file(path, options)
            .map_err(|e| e.to_string())?;
        write_png(
            &mut *archive,
            region.right - region.left,
            region.bottom - region.top,
            !raster.is_indexed(),
            |y, row| {
                let y = region.top + y;
                for tx in region.left / TILE_SIZE..region.right.div_ceil(TILE_SIZE) {
                    if let Some(tile) = raster.tiles().get(&(tx, y / TILE_SIZE)) {
                        let left = (tx * TILE_SIZE).max(region.left);
                        let right = ((tx + 1) * TILE_SIZE).min(region.right);
                        let source = (y % TILE_SIZE * TILE_SIZE + left % TILE_SIZE) as usize;
                        let destination = ((left - region.left) * 4) as usize;
                        let count = ((right - left) * 4) as usize;
                        if raster.is_indexed() {
                            let palette = doc.palette.as_ref().unwrap();
                            for (pixel, &index) in row[destination..destination + count]
                                .as_chunks_mut::<4>()
                                .0
                                .iter_mut()
                                .zip(&tile[source..source + count / 4])
                            {
                                *pixel = palette.colors[index as usize];
                            }
                        } else {
                            let source = source * 4;
                            row[destination..destination + count]
                                .copy_from_slice(&tile[source..source + count]);
                        }
                    }
                }
            },
        )?;
    }
    Ok(())
}

fn write_png(
    output: &mut impl Write,
    width: u32,
    height: u32,
    premultiplied: bool,
    mut fill_row: impl FnMut(u32, &mut [u8]),
) -> Result<(), String> {
    let mut encoder = png::Encoder::new(output, width, height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder.write_header().map_err(|e| e.to_string())?;
    let mut stream = writer.stream_writer().map_err(|e| e.to_string())?;
    let mut row = vec![0; width as usize * 4];
    for y in 0..height {
        row.fill(0);
        fill_row(y, &mut row);
        if premultiplied {
            unpremultiply(&mut row);
        }
        stream.write_all(&row).map_err(|e| e.to_string())?;
    }
    stream.finish().map_err(|e| e.to_string())?;
    writer.finish().map_err(|e| e.to_string())
}

fn layer_bounds(doc: &Document, layer: &Layer) -> Rect {
    layer
        .raster()
        .unwrap()
        .tiles()
        .keys()
        .map(|&(tx, ty)| Rect {
            left: tx * TILE_SIZE,
            top: ty * TILE_SIZE,
            right: ((tx + 1) * TILE_SIZE).min(doc.width),
            bottom: ((ty + 1) * TILE_SIZE).min(doc.height),
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

fn xml_name(name: &str) -> Result<String, String> {
    let mut output = String::with_capacity(name.len());
    for c in name.chars() {
        match c {
            '&' => output.push_str("&amp;"),
            '<' => output.push_str("&lt;"),
            '>' => output.push_str("&gt;"),
            '"' => output.push_str("&quot;"),
            '\'' => output.push_str("&apos;"),
            '\n' => output.push_str("&#10;"),
            '\r' => output.push_str("&#13;"),
            '\t' => output.push_str("&#9;"),
            c if c >= ' ' && c != '\u{fffe}' && c != '\u{ffff}' => output.push(c),
            _ => return Err("图层名称包含不支持的字符".into()),
        }
    }
    Ok(output)
}
