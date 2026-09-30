use flate2::{write::ZlibEncoder, Compression};
use podor_engine::{model::*, Command, Engine, ExportFormat, ExportOptions};
use std::io::Write;

fn pixel(layer: &Layer, x: u32, y: u32) -> [u8; 4] {
    layer
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| {
            let index = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[index..index + 4].try_into().unwrap()
        })
}

fn png_pixels(bytes: &[u8]) -> Vec<u8> {
    let mut reader = png::Decoder::new(bytes).read_info().unwrap();
    let mut data = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut data).unwrap();
    data.truncate(info.buffer_size());
    data
}

#[test]
fn gimp_psd_preserves_layers_offsets_names_locks_and_preview() {
    let mut engine = Engine::new(4, 4).unwrap();
    engine
        .load(include_bytes!("fixtures/gimp-layers.psd"))
        .unwrap();
    let doc = &engine.document;
    assert_eq!((doc.width, doc.height), (129, 131));
    assert_eq!(
        doc.layers
            .iter()
            .map(|layer| layer.name.as_str())
            .collect::<Vec<_>>(),
        ["底色 🎨", "Paint", "Hidden"]
    );
    assert_eq!(doc.layers[1].blend, BlendMode::Multiply);
    assert_eq!(doc.layers[1].opacity, 128.0 / 255.0);
    assert!(doc.layers[1].alpha_locked);
    assert!(!doc.layers[2].visible);
    assert_eq!(pixel(&doc.layers[0], 128, 130), [80, 120, 200, 255]);
    assert_eq!(pixel(&doc.layers[1], 125, 0), [180, 70, 130, 255]);
    assert_eq!(pixel(&doc.layers[1], 124, 0), [0; 4]);
    assert_eq!(pixel(&doc.layers[1], 128, 16), [180, 70, 130, 255]);
    assert_eq!(pixel(&doc.layers[1], 128, 17), [0; 4]);
    let expected = png_pixels(include_bytes!("fixtures/gimp-psd-preview.png"));
    let actual = png_pixels(&engine.export_png().unwrap());
    assert_eq!(expected.len(), actual.len());
    assert!(expected
        .iter()
        .zip(&actual)
        .all(|(a, b)| a.abs_diff(*b) <= 1));
}

fn block(data: &[u8], output: &mut Vec<u8>) {
    output.extend((data.len() as u32).to_be_bytes());
    output.extend(data);
}

fn tag(key: &[u8; 4], data: &[u8]) -> Vec<u8> {
    let mut output = b"8BIM".to_vec();
    output.extend(key);
    block(data, &mut output);
    if !data.len().is_multiple_of(2) {
        output.push(0);
    }
    output
}

fn compressed(plane: &[u8], width: usize, compression: u16) -> Vec<u8> {
    let mut output = compression.to_be_bytes().to_vec();
    match compression {
        0 => output.extend(plane),
        1 => {
            let rows: Vec<_> = plane
                .chunks(width)
                .map(|row| {
                    let mut packed = vec![128];
                    for chunk in row.chunks(128) {
                        if chunk.iter().all(|v| *v == chunk[0]) {
                            packed.extend([(257 - chunk.len()) as u8, chunk[0]]);
                        } else {
                            packed.push((chunk.len() - 1) as u8);
                            packed.extend(chunk);
                        }
                    }
                    packed
                })
                .collect();
            for row in &rows {
                output.extend((row.len() as u16).to_be_bytes());
            }
            for row in rows {
                output.extend(row);
            }
        }
        2 | 3 => {
            let mut data = plane.to_vec();
            if compression == 3 {
                for row in data.chunks_mut(width) {
                    for x in (1..row.len()).rev() {
                        row[x] = row[x].wrapping_sub(row[x - 1]);
                    }
                }
            }
            let mut encoder = ZlibEncoder::new(Vec::new(), Compression::fast());
            encoder.write_all(&data).unwrap();
            output.extend(encoder.finish().unwrap());
        }
        _ => unreachable!(),
    }
    output
}

struct TestLayer {
    bounds: [i32; 4],
    mode: [u8; 4],
    opacity: u8,
    flags: u8,
    clipping: u8,
    extra: Vec<u8>,
    mask: Vec<u8>,
    ranges: Vec<u8>,
    channels: Vec<(i16, Vec<u8>)>,
}

impl TestLayer {
    fn new(compression: u16) -> Self {
        Self {
            bounds: [-1, -2, 2, 3],
            mode: *b"norm",
            opacity: 255,
            flags: 0,
            clipping: 0,
            extra: vec![],
            mask: vec![],
            ranges: vec![],
            channels: [2i16, -1, 0, 1]
                .into_iter()
                .map(|id| {
                    let plane: Vec<_> = (0..15)
                        .map(|i| match id {
                            -1 => {
                                if i % 3 == 0 {
                                    0
                                } else {
                                    128
                                }
                            }
                            0 => (i * 17) as u8,
                            1 => 200,
                            _ => 90,
                        })
                        .collect();
                    (id, compressed(&plane, 5, compression))
                })
                .collect(),
        }
    }
}

fn header(width: u32, height: u32, channels: u16) -> Vec<u8> {
    let mut bytes = b"8BPS\0\x01\0\0\0\0\0\0".to_vec();
    bytes.extend(channels.to_be_bytes());
    bytes.extend(height.to_be_bytes());
    bytes.extend(width.to_be_bytes());
    bytes.extend([0, 8, 0, 3]);
    bytes.extend([0; 8]);
    bytes
}

fn fixture(layers: &[TestLayer]) -> Vec<u8> {
    let mut bytes = header(129, 131, 4);
    let mut info = (-(layers.len() as i16)).to_be_bytes().to_vec();
    for layer in layers {
        for bound in layer.bounds {
            info.extend(bound.to_be_bytes());
        }
        info.extend((layer.channels.len() as u16).to_be_bytes());
        for (id, data) in &layer.channels {
            info.extend(id.to_be_bytes());
            info.extend((data.len() as u32).to_be_bytes());
        }
        info.extend(b"8BIM");
        info.extend(layer.mode);
        info.extend([layer.opacity, layer.clipping, layer.flags, 0]);
        let mut extra = vec![];
        block(&layer.mask, &mut extra);
        block(&layer.ranges, &mut extra);
        extra.extend([1, b'L', 0, 0]);
        extra.extend(&layer.extra);
        block(&extra, &mut info);
    }
    for layer in layers {
        for (_, data) in &layer.channels {
            info.extend(data);
        }
    }
    if !info.len().is_multiple_of(2) {
        info.push(0);
    }
    let mut section = vec![];
    block(&info, &mut section);
    section.extend([0; 4]);
    block(&section, &mut bytes);
    bytes
}

fn clipping_layer(
    color: [u8; 3],
    alpha: [u8; 3],
    clipping: bool,
    mask: Option<[u8; 3]>,
) -> TestLayer {
    let mut layer = TestLayer::new(0);
    layer.bounds = [0, 0, 1, 3];
    layer.clipping = u8::from(clipping);
    layer.channels = (0..4)
        .map(|channel| {
            let plane = if channel == 3 {
                alpha
            } else {
                [color[channel]; 3]
            };
            (
                if channel == 3 { -1 } else { channel as i16 },
                compressed(&plane, 3, 0),
            )
        })
        .collect();
    if let Some(values) = mask {
        layer.mask = [0i32, 0, 1, 3]
            .into_iter()
            .flat_map(i32::to_be_bytes)
            .collect();
        layer.mask.extend([255, 0, 0, 0]);
        layer.channels.push((-2, compressed(&values, 3, 0)));
    }
    layer
}

#[test]
fn external_clipping_chain_preserves_raw_pixels_masks_base_opacity_and_visibility() {
    for (opacity, visible) in [(255, true), (128, true), (255, false)] {
        let mut base = clipping_layer([255, 0, 0], [255, 128, 0], false, Some([255, 128, 255]));
        base.opacity = opacity;
        base.flags = if visible { 0 } else { 2 };
        let green = clipping_layer([0, 255, 0], [255; 3], true, None);
        let blue = clipping_layer([0, 0, 255], [255; 3], true, Some([255, 0, 255]));
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&fixture(&[base, green, blue])).unwrap();
        assert_eq!(
            engine
                .document
                .layers
                .iter()
                .map(|layer| layer.clipping)
                .collect::<Vec<_>>(),
            [false, true, true]
        );
        assert_eq!(pixel(&engine.document.layers[0], 1, 0), [128, 0, 0, 128]);
        assert_eq!(pixel(&engine.document.layers[1], 2, 0), [0, 255, 0, 255]);
        assert_eq!(pixel(&engine.document.layers[2], 1, 0), [0, 0, 255, 255]);
        assert_eq!(
            engine.document.layers[0].first_mask().unwrap().sample(1, 0),
            128
        );
        assert_eq!(
            engine.document.layers[2].first_mask().unwrap().sample(1, 0),
            0
        );
        let alpha = if visible { opacity } else { 0 };
        let partial = if visible {
            ((u32::from(opacity) * 64 + 127) / 255) as u8
        } else {
            0
        };
        let expected = [0, 0, alpha, alpha, 0, partial, 0, partial, 0, 0, 0, 0];
        assert_eq!(&engine.frame_with_background(true)[24..36], &expected);
        let before = engine.save().unwrap();
        let state = engine.state();
        let encoded = engine
            .export_image(ExportOptions {
                format: ExportFormat::Psd,
                ..Default::default()
            })
            .unwrap();
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&encoded).unwrap();
        for (source, imported) in engine.document.layers.iter().zip(&reopened.document.layers) {
            assert_eq!(source.clipping, imported.clipping);
            assert_eq!(source.visible, imported.visible);
            assert_eq!(source.opacity, imported.opacity);
            for x in 0..3 {
                assert_eq!(pixel(source, x, 0), pixel(imported, x, 0));
                assert_eq!(
                    source.first_mask().map(|mask| mask.sample(x as i32, 0)),
                    imported.first_mask().map(|mask| mask.sample(x as i32, 0))
                );
            }
        }
        assert_eq!(&reopened.frame_with_background(true)[24..36], &expected);
        assert_eq!(before, engine.save().unwrap());
        assert_eq!(state, engine.state());
    }
}

#[test]
fn unsupported_clipping_flags_and_group_compositing_settings_reject_atomically() {
    let mut engine = Engine::new(3, 1).unwrap();
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: [30, 70, 90, 255],
            tolerance: 0,
            contiguous: true,
            merged: false,
        })
        .unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    let base = || clipping_layer([255, 0, 0], [255; 3], false, None);
    for (key, flag) in [(b"clbl", 0), (b"infx", 0), (b"knko", 1)] {
        let mut clipped = clipping_layer([0, 0, 255], [255; 3], true, None);
        clipped.extra = tag(key, &[flag, 0, 0, 0]);
        assert!(engine
            .load(&fixture(&[base(), clipped]))
            .unwrap_err()
            .contains("尚未支持"));
        assert_eq!(before, engine.save().unwrap());
        assert_eq!(state, engine.state());
    }
    let mut clipped = clipping_layer([0, 0, 255], [255; 3], true, None);
    clipped.clipping = 2;
    assert!(engine
        .load(&fixture(&[base(), clipped]))
        .unwrap_err()
        .contains("剪贴蒙版标记"));
    assert_eq!(before, engine.save().unwrap());
    assert_eq!(state, engine.state());
    let mut clipped = clipping_layer([0, 0, 255], [255; 3], true, None);
    clipped.extra = [
        tag(b"clbl", &[1, 0, 0, 0]),
        tag(b"infx", &[1, 0, 0, 0]),
        tag(b"knko", &[0, 0, 0, 0]),
    ]
    .concat();
    engine.load(&fixture(&[base(), clipped])).unwrap();
    assert_eq!(
        &engine.frame_with_background(true)[24..28],
        &[0, 0, 255, 255]
    );
}

#[test]
fn all_compressions_handle_negative_offsets_channel_order_and_transparency() {
    for compression in 0..4 {
        let mut engine = Engine::new(1, 1).unwrap();
        engine
            .load(&fixture(&[TestLayer::new(compression)]))
            .unwrap();
        let layer = &engine.document.layers[0];
        for y in 0..2 {
            for x in 0..3 {
                let i = (y + 1) * 5 + x + 2;
                let alpha = if i % 3 == 0 { 0 } else { 128 };
                let expected = [
                    ((i * 17 * alpha + 127) / 255) as u8,
                    ((200 * alpha + 127) / 255) as u8,
                    ((90 * alpha + 127) / 255) as u8,
                    alpha as u8,
                ];
                assert_eq!(
                    pixel(layer, x, y),
                    expected,
                    "compression={compression}, ({x},{y})"
                );
            }
        }
        assert_eq!(pixel(layer, 3, 0), [0; 4]);
        assert_eq!(pixel(layer, 0, 2), [0; 4]);
        assert_eq!(layer.raster().unwrap().tiles().len(), 1);
    }
}

#[test]
fn export_import_roundtrip_retains_all_modes_and_locks() {
    for mode in [
        BlendMode::Normal,
        BlendMode::Multiply,
        BlendMode::Screen,
        BlendMode::Overlay,
        BlendMode::SoftLight,
        BlendMode::Darken,
        BlendMode::Lighten,
        BlendMode::Difference,
    ] {
        let mut engine = Engine::new(129, 131).unwrap();
        engine
            .command(Command::Fill {
                contiguous: true,
                merged: false,
                x: 0,
                y: 0,
                color: [50, 100, 200, 128],
                tolerance: 0,
            })
            .unwrap();
        engine.command(Command::AddLayer).unwrap();
        engine.document.layers[0].name = "底色 🎨".into();
        engine.document.layers[0].locked = true;
        engine.document.layers[0].alpha_locked = true;
        engine.document.layers[1].visible = false;
        engine.document.layers[1].opacity = 64. / 255.;
        engine.document.layers[1].blend = mode;
        let bytes = engine
            .export_image(ExportOptions {
                format: ExportFormat::Psd,
                ..Default::default()
            })
            .unwrap();
        let before = engine.save().unwrap();
        engine.load(&bytes).unwrap();
        assert_eq!(engine.save().unwrap(), before);
    }
}

#[test]
fn merged_rgb_psd_supports_all_compressions() {
    for compression in 0..4 {
        let mut bytes = header(5, 3, 3);
        bytes.extend([0; 4]);
        let planes = [vec![55; 15], vec![99; 15], vec![210; 15]].concat();
        bytes.extend(compressed(&planes, 5, compression));
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&bytes).unwrap();
        assert_eq!(pixel(&engine.document.layers[0], 4, 2), [55, 99, 210, 255]);
    }
}

#[test]
fn unsupported_layer_features_are_reported_without_changing_the_document() {
    let mut engine = Engine::new(16, 16).unwrap();
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color: [44, 55, 66, 255],
            tolerance: 0,
        })
        .unwrap();
    let original = engine.save().unwrap();
    for (key, data, message) in [
        (b"lsct", vec![0, 0, 0, 1], "图层组"),
        (b"TySh", vec![0; 4], "尚未支持"),
        (b"vmsk", vec![0; 8], "尚未支持"),
        (b"lrFX", vec![0; 4], "尚未支持"),
        (b"iOpa", vec![100], "尚未支持"),
        (b"SoLd", vec![0; 4], "尚未支持"),
    ] {
        let mut layer = TestLayer::new(1);
        layer.extra = tag(key, &data);
        assert!(engine
            .load(&fixture(&[layer]))
            .unwrap_err()
            .contains(message));
        assert_eq!(engine.save().unwrap(), original);
    }
    for kind in 0..4 {
        let mut layer = TestLayer::new(1);
        match kind {
            0 => layer.mask = vec![0; 20],
            1 => layer.clipping = 2,
            2 => layer.mode = *b"hLit",
            _ => layer.ranges = vec![1; 8],
        }
        assert!(engine.load(&fixture(&[layer])).is_err());
        assert_eq!(engine.save().unwrap(), original);
    }
    engine.command(Command::Undo).unwrap();
    assert_eq!(pixel(&engine.document.layers[0], 0, 0), [0; 4]);
}

#[test]
fn truncated_records_and_streams_never_partially_replace_the_document() {
    let mut engine = Engine::new(4, 4).unwrap();
    let before = engine.save().unwrap();
    for compression in 0..4 {
        let bytes = fixture(&[TestLayer::new(compression)]);
        for length in 0..bytes.len() {
            assert!(
                engine.load(&bytes[..length]).is_err(),
                "compression {compression}, length {length}"
            );
        }
        let mut layer = TestLayer::new(compression);
        layer.channels[0].1.pop();
        assert!(engine.load(&fixture(&[layer])).is_err());
        assert_eq!(before, engine.save().unwrap());
    }
}

#[test]
fn invalid_channel_ids_dimensions_counts_and_names_are_rejected() {
    let mut engine = Engine::new(1, 1).unwrap();
    for kind in 0..6 {
        let mut layer = TestLayer::new(0);
        match kind {
            0 => layer.channels[0].0 = -1,
            1 => layer.bounds = [i32::MIN, 0, i32::MAX, 10],
            2 => layer.bounds = [0, 5, 4, 0],
            3 => layer.extra = tag(b"luni", &u32::MAX.to_be_bytes()),
            4 => layer.channels[0].1[1] = 4,
            _ => layer.flags = 0x18,
        }
        assert!(engine.load(&fixture(&[layer])).is_err());
    }
    let mut bytes = fixture(&[TestLayer::new(0)]);
    bytes[42..44].copy_from_slice(&(-(MAX_PSD_RECORDS as i16 + 1)).to_be_bytes());
    assert!(engine.load(&bytes).unwrap_err().contains("图层数量"));
}

#[test]
fn malformed_packbits_and_zip_checksums_are_rejected() {
    for bytes in [
        vec![0, 1, 0, 2, 0, 1, 0, 1, 127, 1, 128, 128],
        compressed(&[30; 16], 5, 2),
    ] {
        let mut layer = TestLayer::new(0);
        layer.channels[0].1 = bytes;
        assert!(Engine::new(1, 1).unwrap().load(&fixture(&[layer])).is_err());
    }
    let mut layer = TestLayer::new(2);
    *layer.channels[0].1.last_mut().unwrap() ^= 0xff;
    assert!(Engine::new(1, 1).unwrap().load(&fixture(&[layer])).is_err());
}

#[test]
fn decoded_work_and_allocated_tiles_are_bounded() {
    let mut layers: Vec<_> = (0..9).map(|_| TestLayer::new(0)).collect();
    for layer in &mut layers {
        layer.bounds = [0, 0, 4096, 4096];
    }
    let mut engine = Engine::new(1, 1).unwrap();
    assert!(engine
        .load(&fixture(&layers))
        .unwrap_err()
        .contains("解码像素"));

    let plane = compressed(&vec![255; 2048 * 2048], 2048, 1);
    for layer in &mut layers {
        layer.bounds = [0, 0, 2048, 2048];
        for (_, channel) in &mut layer.channels {
            *channel = plane.clone();
        }
    }
    let mut bytes = fixture(&layers);
    bytes[14..18].copy_from_slice(&2048u32.to_be_bytes());
    bytes[18..22].copy_from_slice(&2048u32.to_be_bytes());
    assert!(engine.load(&bytes).unwrap_err().contains("内存限制"));
    assert_eq!((engine.document.width, engine.document.height), (1, 1));
}

#[test]
fn transparent_layers_stay_sparse_and_empty_single_layer_psd_opens() {
    let mut layer = TestLayer::new(0);
    layer.channels[1].1 = compressed(&[0; 15], 5, 0);
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&fixture(&[layer])).unwrap();
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
    engine.document.layers[0].name = "图层 1".into();
    let bytes = engine
        .export_image(ExportOptions {
            format: ExportFormat::Psd,
            ..Default::default()
        })
        .unwrap();
    engine.load(&bytes).unwrap();
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
    assert_eq!(engine.document.layers[0].name, "图层 1");
}
