use flate2::{write::ZlibEncoder, Compression};
use podor_engine::{model::*, Engine, ExportFormat, ExportOptions, IndexedExportPolicy};
use std::io::Write;

fn chunk(output: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    output.extend((data.len() as u32).to_be_bytes());
    output.extend(kind);
    output.extend(data);
    let mut crc = u32::MAX;
    for &value in kind.iter().chain(data) {
        crc ^= u32::from(value);
        for _ in 0..8 {
            crc = (crc >> 1) ^ (0xedb8_8320 & 0u32.wrapping_sub(crc & 1));
        }
    }
    output.extend((!crc).to_be_bytes());
}

fn fixture(
    width: u32,
    height: u32,
    depth: u8,
    colors: &[[u8; 4]],
    indices: &[u8],
    interlaced: bool,
) -> Vec<u8> {
    assert_eq!(indices.len(), (width * height) as usize);
    let passes = if interlaced {
        vec![
            (0, 0, 8, 8),
            (4, 0, 8, 8),
            (0, 4, 4, 8),
            (2, 0, 4, 4),
            (0, 2, 2, 4),
            (1, 0, 2, 2),
            (0, 1, 1, 2),
        ]
    } else {
        vec![(0, 0, 1, 1)]
    };
    let mut scanlines = Vec::new();
    for (left, top, step_x, step_y) in passes {
        if left >= width || top >= height {
            continue;
        }
        let columns = (width - left).div_ceil(step_x) as usize;
        for y in (top..height).step_by(step_y as usize) {
            scanlines.push(0);
            let mut row = vec![0; (columns * depth as usize).div_ceil(8)];
            for (column, x) in (left..width).step_by(step_x as usize).enumerate() {
                let bit = column * depth as usize;
                row[bit / 8] |= indices[(y * width + x) as usize] << (8 - depth - (bit % 8) as u8);
            }
            scanlines.extend(row);
        }
    }
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::fast());
    encoder.write_all(&scanlines).unwrap();
    let compressed = encoder.finish().unwrap();
    let mut output = b"\x89PNG\r\n\x1a\n".to_vec();
    let mut header = Vec::new();
    header.extend(width.to_be_bytes());
    header.extend(height.to_be_bytes());
    header.extend([depth, 3, 0, 0, u8::from(interlaced)]);
    chunk(&mut output, b"IHDR", &header);
    chunk(
        &mut output,
        b"PLTE",
        &colors
            .iter()
            .flat_map(|color| color[..3].iter().copied())
            .collect::<Vec<_>>(),
    );
    chunk(
        &mut output,
        b"tRNS",
        &colors.iter().map(|color| color[3]).collect::<Vec<_>>(),
    );
    chunk(&mut output, b"IDAT", &compressed);
    chunk(&mut output, b"IEND", &[]);
    output
}

fn replace_chunk(bytes: &[u8], kind: &[u8; 4], replacement: &[u8]) -> Vec<u8> {
    let mut output = bytes[..8].to_vec();
    let mut offset = 8;
    while offset < bytes.len() {
        let count = u32::from_be_bytes(bytes[offset..offset + 4].try_into().unwrap()) as usize;
        let actual: &[u8; 4] = bytes[offset + 4..offset + 8].try_into().unwrap();
        let data = &bytes[offset + 8..offset + 8 + count];
        chunk(
            &mut output,
            actual,
            if actual == kind { replacement } else { data },
        );
        offset += count + 12;
    }
    output
}

fn load(bytes: &[u8]) -> Engine {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(bytes).unwrap();
    engine.document.validate().unwrap();
    engine
}

fn native_indices(engine: &Engine) -> Vec<u8> {
    let doc = &engine.document;
    let transparent = doc.palette.as_ref().unwrap().transparent;
    let layer = &doc.layers[0];
    assert!(layer.raster().unwrap().is_indexed());
    assert!(layer
        .raster()
        .unwrap()
        .tiles()
        .values()
        .all(|tile| tile.len() == INDEX_TILE_BYTES));
    (0..doc.height)
        .flat_map(|y| {
            (0..doc.width).map(move |x| {
                layer
                    .raster()
                    .unwrap()
                    .tiles()
                    .get(&(x / TILE_SIZE, y / TILE_SIZE))
                    .map_or(transparent, |tile| {
                        tile[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize]
                    })
            })
        })
        .collect()
}

fn decode(bytes: &[u8]) -> (Vec<[u8; 4]>, Vec<u8>) {
    let mut decoder = png::Decoder::new(bytes);
    decoder.set_transformations(png::Transformations::IDENTITY);
    let mut reader = decoder.read_info().unwrap();
    let info = reader.info();
    assert_eq!(info.color_type, png::ColorType::Indexed);
    assert_eq!(info.bit_depth, png::BitDepth::Eight);
    let alpha = info.trns.as_deref().unwrap_or_default();
    let colors = info
        .palette
        .as_ref()
        .unwrap()
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
    let mut indices = vec![0; reader.output_buffer_size()];
    let output = reader.next_frame(&mut indices).unwrap();
    indices.truncate(output.buffer_size());
    reader.finish().unwrap();
    (colors, indices)
}

fn export(
    engine: &Engine,
    transparent: bool,
    policy: IndexedExportPolicy,
) -> Result<Vec<u8>, String> {
    engine.export_image(ExportOptions {
        format: ExportFormat::IndexedPng,
        transparent,
        indexed_policy: policy,
        ..Default::default()
    })
}

#[test]
fn packed_depths_and_adam7_preserve_palette_alpha_indices_and_row_padding() {
    let all_colors = [
        [80, 90, 100, 0],
        [7, 150, 233, 1],
        [150, 40, 210, 128],
        [30, 200, 80, 255],
    ];
    for depth in [1, 2, 4, 8] {
        let colors = &all_colors[..if depth == 1 { 2 } else { 4 }];
        let indices: Vec<_> = (0..7)
            .flat_map(|y| (0..5).map(move |x| ((x + 2 * y) % colors.len()) as u8))
            .collect();
        for interlaced in [false, true] {
            let source = fixture(5, 7, depth, colors, &indices, interlaced);
            let engine = load(&source);
            assert_eq!(engine.document.palette.as_ref().unwrap().colors, colors);
            assert_eq!(
                native_indices(&engine),
                indices,
                "depth={depth}, Adam7={interlaced}"
            );
            assert_eq!(
                decode(&export(&engine, true, IndexedExportPolicy::Exact).unwrap()),
                (colors.to_vec(), indices.clone())
            );
        }
    }
}

#[test]
fn partial_opaque_palette_appends_transparent_without_remapping_original_indices() {
    let colors = [[7, 150, 233, 1], [30, 200, 80, 255]];
    let indices = [1, 0, 1, 0, 1];
    let engine = load(&fixture(5, 1, 1, &colors, &indices, false));
    let palette = engine.document.palette.as_ref().unwrap();
    assert_eq!(&palette.colors[..colors.len()], &colors);
    assert_eq!(palette.colors.len(), 3);
    assert_eq!(palette.transparent, 2);
    assert_eq!(palette.colors[2][3], 0);
    assert_eq!(native_indices(&engine), indices);

    let one = [[91, 38, 170, 0]];
    let engine = load(&fixture(3, 2, 1, &one, &[0; 6], true));
    let palette = engine.document.palette.as_ref().unwrap();
    assert_eq!(palette.colors[0], one[0]);
    assert_eq!(palette.colors.len(), 2);
    assert_eq!(palette.transparent, 0);
    assert_eq!(palette.colors[1][3], 255);
    assert_eq!(native_indices(&engine), [0; 6]);
}

#[test]
fn full_opaque_palette_opens_as_rgba_without_replacing_any_source_color() {
    let colors: Vec<_> = (0..256)
        .map(|value| [value as u8, 255 - value as u8, 93, 255])
        .collect();
    let indices: Vec<_> = (0..256).map(|value| value as u8).collect();
    let engine = load(&fixture(256, 1, 8, &colors, &indices, false));
    assert!(engine.document.palette.is_none());
    assert!(!engine.document.layers[0].raster().unwrap().is_indexed());
    let bytes = engine
        .export_image(ExportOptions {
            transparent: true,
            ..Default::default()
        })
        .unwrap();
    let mut reader = png::Decoder::new(bytes.as_slice()).read_info().unwrap();
    let mut pixels = vec![0; reader.output_buffer_size()];
    reader.next_frame(&mut pixels).unwrap();
    assert_eq!(pixels.as_chunks::<4>().0, colors);
    assert!(export(&engine, true, IndexedExportPolicy::Exact).is_err());
}

#[test]
fn exact_export_preserves_duplicate_unused_entries_and_nonzero_transparent_index() {
    let colors = [
        [180, 20, 60, 255],
        [180, 20, 60, 255],
        [190, 7, 88, 0],
        [7, 150, 233, 1],
        [10, 240, 170, 128],
        [44, 19, 100, 255],
    ];
    let indices: Vec<_> = (0..129 * 3).map(|value| [1, 0, 3, 2][value % 4]).collect();
    let engine = load(&fixture(129, 3, 8, &colors, &indices, false));
    assert_eq!(engine.document.palette.as_ref().unwrap().transparent, 2);
    let before = engine.save().unwrap();
    let state = engine.state();
    let tile = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    let bytes = export(&engine, true, IndexedExportPolicy::Exact).unwrap();
    assert_eq!(decode(&bytes), (colors.to_vec(), indices));
    assert_eq!(before, engine.save().unwrap());
    assert_eq!(state, engine.state());
    assert!(std::sync::Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
}

#[test]
fn all_256_entries_and_duplicate_transparent_pixels_keep_their_original_indices() {
    let colors: Vec<_> = (0..256)
        .map(|value| {
            [
                value as u8,
                255 - value as u8,
                73,
                if value == 255 { 0 } else { 255 },
            ]
        })
        .collect();
    let indices: Vec<_> = (0..256).map(|value| value as u8).collect();
    let engine = load(&fixture(256, 1, 8, &colors, &indices, true));
    assert_eq!(engine.document.palette.as_ref().unwrap().transparent, 255);
    assert_eq!(
        decode(&export(&engine, true, IndexedExportPolicy::Exact).unwrap()),
        (colors, indices)
    );

    let colors = [[91, 5, 44, 0], [7, 150, 233, 0], [30, 20, 100, 255]];
    let engine = load(&fixture(3, 1, 2, &colors, &[1; 3], false));
    assert_eq!(native_indices(&engine), [1; 3]);
    assert_eq!(
        decode(&export(&engine, true, IndexedExportPolicy::Exact).unwrap()),
        (colors.to_vec(), vec![1; 3])
    );
}

#[test]
fn missing_and_short_trns_entries_default_to_opaque() {
    let colors = [
        [0, 0, 0, 0],
        [7, 150, 233, 1],
        [50, 160, 170, 128],
        [230, 120, 40, 1],
    ];
    let original = fixture(4, 1, 2, &colors, &[0, 1, 2, 3], false);
    let engine = load(&replace_chunk(&original, b"tRNS", &[0, 1]));
    let expected = [
        [0, 0, 0, 0],
        [7, 150, 233, 1],
        [50, 160, 170, 255],
        [230, 120, 40, 255],
    ];
    assert_eq!(engine.document.palette.as_ref().unwrap().colors, expected);
    let mut without_alpha = Vec::new();
    without_alpha.extend(&original[..8]);
    let mut offset = 8;
    while offset < original.len() {
        let count = u32::from_be_bytes(original[offset..offset + 4].try_into().unwrap()) as usize;
        if &original[offset + 4..offset + 8] != b"tRNS" {
            without_alpha.extend(&original[offset..offset + count + 12]);
        }
        offset += count + 12;
    }
    let engine = load(&without_alpha);
    let palette = engine.document.palette.as_ref().unwrap();
    assert_eq!(palette.colors.len(), 5);
    assert!(palette.colors[..4].iter().all(|color| color[3] == 255));
    assert_eq!(palette.transparent, 4);
    assert_eq!(native_indices(&engine), [0, 1, 2, 3]);
}

#[test]
fn opaque_indexed_export_uses_local_white_matte_palette_and_keeps_document_alpha() {
    let colors = [
        [73, 20, 184, 0],
        [7, 150, 233, 1],
        [180, 70, 130, 128],
        [40, 80, 160, 255],
    ];
    let engine = load(&fixture(4, 1, 2, &colors, &[0, 1, 2, 3], false));
    let before = engine.save().unwrap();
    let output = export(&engine, false, IndexedExportPolicy::Exact).unwrap();
    let (actual, indices) = decode(&output);
    assert_eq!(indices, [0, 1, 2, 3]);
    let expected: Vec<_> = colors
        .iter()
        .map(|color| {
            let alpha = u32::from(color[3]);
            let rgb = color[..3]
                .iter()
                .map(|value| ((u32::from(*value) * alpha + 127) / 255 + 255 - alpha) as u8)
                .collect::<Vec<_>>();
            [rgb[0], rgb[1], rgb[2], 255]
        })
        .collect();
    assert_eq!(actual, expected);
    let reader = png::Decoder::new(output.as_slice()).read_info().unwrap();
    assert!(reader.info().trns.is_none());
    assert_eq!(before, engine.save().unwrap());
    assert_eq!(engine.document.palette.as_ref().unwrap().colors, colors);
}

#[test]
fn exact_export_rejects_unlisted_composite_but_quantize_is_explicit_and_non_destructive() {
    let colors = [[0, 0, 0, 0], [100, 180, 230, 255]];
    let mut engine = load(&fixture(3, 2, 1, &colors, &[1; 6], false));
    engine.document.layers[0].opacity = 0.5;
    let before = engine.save().unwrap();
    let state = engine.state();
    assert!(export(&engine, true, IndexedExportPolicy::Exact)
        .unwrap_err()
        .contains("合成颜色"));
    let (actual, indices) = decode(&export(&engine, true, IndexedExportPolicy::Quantize).unwrap());
    assert_eq!(actual, colors);
    assert_eq!(indices.len(), 6);
    assert!(indices.iter().all(|&index| index < 2));
    assert_eq!(before, engine.save().unwrap());
    assert_eq!(state, engine.state());
    let options: ExportOptions =
        serde_json::from_value(serde_json::json!({"format":"indexed_png","transparent":true}))
            .unwrap();
    assert_eq!(options.indexed_policy, IndexedExportPolicy::Exact);
    assert!(engine.export_image(options).is_err());
}

#[test]
fn low_alpha_premultiplied_collision_is_not_an_exact_straight_color_match() {
    let colors = [[0, 0, 0, 0], [7, 150, 233, 1], [1, 200, 200, 1]];
    let mut engine = load(&fixture(3, 1, 2, &colors, &[2, 1, 2], false));
    assert_eq!(
        decode(&export(&engine, true, IndexedExportPolicy::Exact).unwrap()),
        (colors.to_vec(), vec![2, 1, 2])
    );
    let mut upper = Layer::new(2, "Empty visible layer".into());
    upper.content =
        podor_engine::model::LayerContent::Raster(RasterPlane::Indexed(Default::default()));
    engine.document.layers.push(upper);
    engine.document.next_id = 3;
    engine.document.validate().unwrap();
    let before = engine.save().unwrap();
    assert!(export(&engine, true, IndexedExportPolicy::Exact)
        .unwrap_err()
        .contains("合成颜色"));
    let (actual, indices) = decode(&export(&engine, true, IndexedExportPolicy::Quantize).unwrap());
    assert_eq!(actual, colors);
    assert!(indices.iter().all(|&index| index == 1));
    assert_eq!(before, engine.save().unwrap());
}

#[test]
fn fully_transparent_composite_uses_the_reserved_index_with_hidden_rgb() {
    let colors = [[73, 20, 184, 0], [40, 80, 160, 255]];
    let mut engine = load(&fixture(3, 1, 1, &colors, &[0; 3], false));
    engine.document.layers[0].visible = false;
    let (actual, indices) = decode(&export(&engine, true, IndexedExportPolicy::Exact).unwrap());
    assert_eq!(actual, colors);
    assert_eq!(indices, [0; 3]);
}

#[test]
fn invalid_palette_alpha_indices_animation_and_dimensions_fail_atomically() {
    let colors = [[0, 0, 0, 0], [40, 90, 170, 255]];
    let valid = fixture(3, 2, 2, &colors, &[1; 6], false);
    let mut animation = valid[..8].to_vec();
    let header_length = 25;
    animation.extend(&valid[8..8 + header_length]);
    chunk(&mut animation, b"acTL", &[0, 0, 0, 1, 0, 0, 0, 0]);
    animation.extend(&valid[8 + header_length..]);
    let mut huge_header = valid[16..29].to_vec();
    huge_header[..4].copy_from_slice(&(MAX_DIMENSION + 1).to_be_bytes());
    let mut excessive_area = valid[16..29].to_vec();
    excessive_area[..4].copy_from_slice(&4097u32.to_be_bytes());
    excessive_area[4..8].copy_from_slice(&4096u32.to_be_bytes());
    let invalid = [
        replace_chunk(&valid, b"PLTE", &[10, 20, 30, 40]),
        replace_chunk(&valid, b"PLTE", &vec![0; 257 * 3]),
        replace_chunk(&valid, b"tRNS", &[0, 255, 123]),
        fixture(3, 2, 2, &colors, &[2; 6], false),
        animation,
        replace_chunk(&valid, b"IHDR", &huge_header),
        replace_chunk(&valid, b"IHDR", &excessive_area),
    ];
    let mut engine = load(&valid);
    let before = engine.save().unwrap();
    let state = engine.state();
    let tile = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    for bytes in invalid {
        assert!(engine.load(&bytes).is_err());
        assert_eq!(before, engine.save().unwrap());
        assert_eq!(state, engine.state());
        assert!(std::sync::Arc::ptr_eq(
            &tile,
            &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
        ));
    }
}
