use podor_engine::{model::*, Command, Engine, ExportFormat, ExportOptions};
use std::{collections::BTreeMap, sync::Arc};

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}
impl<'a> Reader<'a> {
    fn take(&mut self, count: usize) -> &'a [u8] {
        let bytes = &self.bytes[self.offset..self.offset + count];
        self.offset += count;
        bytes
    }
    fn u16(&mut self) -> u16 {
        u16::from_be_bytes(self.take(2).try_into().unwrap())
    }
    fn u32(&mut self) -> u32 {
        u32::from_be_bytes(self.take(4).try_into().unwrap())
    }
    fn block(&mut self) -> &'a [u8] {
        let length = self.u32() as usize;
        self.take(length)
    }
}

struct ParsedLayer {
    bounds: [u32; 4],
    mode: [u8; 4],
    opacity: u8,
    clipping: u8,
    clipped_as_group: Option<bool>,
    flags: u8,
    protection: u32,
    name: String,
    channels: Vec<(i16, usize)>,
    pixels: Vec<u8>,
}
struct Parsed {
    width: usize,
    height: usize,
    layers: Vec<ParsedLayer>,
    merged: Vec<u8>,
}

fn unpack(row: &[u8], expected: usize) -> Vec<u8> {
    let mut reader = Reader {
        bytes: row,
        offset: 0,
    };
    let mut result = Vec::new();
    while reader.offset < row.len() {
        let control = reader.take(1)[0] as i8;
        match control {
            0..=127 => result.extend(reader.take(control as usize + 1)),
            -127..=-1 => result.extend(std::iter::repeat_n(
                reader.take(1)[0],
                (1 - i16::from(control)) as usize,
            )),
            -128 => {}
        }
    }
    assert_eq!(result.len(), expected);
    result
}

fn parse(bytes: &[u8]) -> Parsed {
    let mut reader = Reader { bytes, offset: 0 };
    assert_eq!(reader.take(4), b"8BPS");
    assert_eq!(reader.u16(), 1);
    assert_eq!(reader.take(6), [0; 6]);
    assert_eq!(reader.u16(), 4);
    let height = reader.u32() as usize;
    let width = reader.u32() as usize;
    assert_eq!(reader.u16(), 8);
    assert_eq!(reader.u16(), 3);
    reader.block();
    reader.block();
    let mut section = Reader {
        bytes: reader.block(),
        offset: 0,
    };
    let mut info = Reader {
        bytes: section.block(),
        offset: 0,
    };
    let count = info.u16() as i16;
    assert!(count < 0);
    let mut layers = Vec::new();
    for _ in 0..-count {
        let bounds = std::array::from_fn(|_| info.u32());
        let channels = (0..info.u16())
            .map(|_| (info.u16() as i16, info.u32() as usize))
            .collect::<Vec<_>>();
        assert_eq!(info.take(4), b"8BIM");
        let mode = info.take(4).try_into().unwrap();
        let opacity = info.take(1)[0];
        let clipping = info.take(1)[0];
        let flags = info.take(1)[0];
        assert_eq!(info.take(1), [0]);
        let mut extra = Reader {
            bytes: info.block(),
            offset: 0,
        };
        assert!(extra.block().is_empty());
        assert!(extra.block().is_empty());
        let legacy_length = extra.take(1)[0] as usize;
        extra.take((legacy_length + 1).div_ceil(4) * 4 - 1);
        let mut name = None;
        let mut protection = 0;
        let mut clipped_as_group = None;
        while extra.offset < extra.bytes.len() {
            assert_eq!(extra.take(4), b"8BIM");
            let key = extra.take(4);
            let data = extra.block();
            if key == b"luni" {
                let mut text = Reader {
                    bytes: data,
                    offset: 0,
                };
                let length = text.u32();
                let chars = (0..length).map(|_| text.u16()).collect::<Vec<_>>();
                name = Some(String::from_utf16(&chars).unwrap());
            }
            if key == b"lspf" {
                protection = u32::from_be_bytes(data.try_into().unwrap());
            }
            if key == b"clbl" {
                assert_eq!(data.len(), 4);
                assert_eq!(&data[1..], &[0; 3]);
                clipped_as_group = Some(data[0] != 0);
            }
            if !data.len().is_multiple_of(2) {
                extra.take(1);
            }
        }
        layers.push(ParsedLayer {
            bounds,
            mode,
            opacity,
            clipping,
            clipped_as_group,
            flags,
            protection,
            name: name.unwrap(),
            channels,
            pixels: Vec::new(),
        });
    }
    for layer in &mut layers {
        let width = (layer.bounds[3] - layer.bounds[1]) as usize;
        let height = (layer.bounds[2] - layer.bounds[0]) as usize;
        layer.pixels.resize(width * height * 4, 0);
        for &(id, length) in &layer.channels {
            let channel = if id == -1 { 3 } else { id as usize };
            let mut data = Reader {
                bytes: info.take(length),
                offset: 0,
            };
            assert_eq!(data.u16(), 1);
            let lengths = (0..height).map(|_| data.u16() as usize).collect::<Vec<_>>();
            for (y, length) in lengths.into_iter().enumerate() {
                for (x, value) in unpack(data.take(length), width).into_iter().enumerate() {
                    layer.pixels[(y * width + x) * 4 + channel] = value;
                }
            }
            assert_eq!(data.offset, data.bytes.len());
        }
    }
    assert!(info
        .take(info.bytes.len() - info.offset)
        .iter()
        .all(|&byte| byte == 0));
    assert!(section.block().is_empty());
    assert_eq!(section.offset, section.bytes.len());
    assert_eq!(reader.u16(), 1);
    let lengths = (0..height * 4)
        .map(|_| reader.u16() as usize)
        .collect::<Vec<_>>();
    let mut merged = vec![0; width * height * 4];
    for channel in 0..4 {
        for y in 0..height {
            for (x, value) in unpack(reader.take(lengths[channel * height + y]), width)
                .into_iter()
                .enumerate()
            {
                merged[(y * width + x) * 4 + channel] = value;
            }
        }
    }
    assert_eq!(reader.offset, bytes.len());
    for pixel in merged.as_chunks_mut::<4>().0 {
        let alpha = u32::from(pixel[3]);
        for value in &mut pixel[..3] {
            *value = (u32::from(value.saturating_sub(255 - alpha as u8)) * 255 + alpha / 2)
                .checked_div(alpha)
                .unwrap_or(0)
                .min(255) as u8;
        }
    }
    Parsed {
        width,
        height,
        layers,
        merged,
    }
}

fn export(engine: &Engine) -> Vec<u8> {
    engine
        .export_image(ExportOptions {
            format: ExportFormat::Psd,
            ..Default::default()
        })
        .unwrap()
}

fn fill(engine: &mut Engine, color: [u8; 4]) {
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color,
            tolerance: 0,
        })
        .unwrap();
}

#[test]
fn layer_records_keep_unicode_order_visibility_opacity_and_protection() {
    let mut engine = Engine::new(257, 129).unwrap();
    fill(&mut engine, [200, 100, 50, 128]);
    engine
        .command(Command::SetLayer {
            id: 1,
            visible: true,
            opacity: 0.5,
            name: "底色 🎨".into(),
        })
        .unwrap();
    engine
        .command(Command::SetProtection {
            id: 1,
            alpha_locked: Some(true),
            locked: Some(false),
        })
        .unwrap();
    engine.command(Command::AddLayer).unwrap();
    fill(&mut engine, [0, 128, 255, 255]);
    engine
        .command(Command::SetLayer {
            id: 2,
            visible: false,
            opacity: 0.25,
            name: "隐藏色块".into(),
        })
        .unwrap();
    engine
        .command(Command::SetProtection {
            id: 2,
            alpha_locked: None,
            locked: Some(true),
        })
        .unwrap();
    let saved = engine.save().unwrap();
    let state = engine.state();
    let bytes = export(&engine);
    let parsed = parse(&bytes);
    assert_eq!((parsed.width, parsed.height), (257, 129));
    assert_eq!(parsed.layers.len(), 2);
    let upper = &parsed.layers[1];
    assert_eq!(upper.name, "隐藏色块");
    assert_eq!(upper.opacity, 64);
    assert_eq!(upper.flags & 2, 2);
    assert_eq!(upper.protection, 0x8000_0007);
    assert_eq!(&upper.pixels[..4], &[0, 128, 255, 255]);
    let lower = &parsed.layers[0];
    assert_eq!(lower.name, "底色 🎨");
    assert_eq!(lower.opacity, 128);
    assert_eq!(lower.flags & 3, 1);
    assert_eq!(lower.protection, 1);
    assert_eq!(&lower.pixels[..4], &[199, 100, 50, 128]);
    assert!(saved == engine.save().unwrap());
    assert_eq!(state, engine.state());
    fixture("properties.psd", &bytes);
}

#[test]
fn merged_preview_matches_png_for_all_blends() {
    let modes = [
        (BlendMode::Normal, "normal", *b"norm"),
        (BlendMode::Multiply, "multiply", *b"mul "),
        (BlendMode::Screen, "screen", *b"scrn"),
        (BlendMode::Overlay, "overlay", *b"over"),
        (BlendMode::SoftLight, "soft_light", *b"sLit"),
        (BlendMode::Darken, "darken", *b"dark"),
        (BlendMode::Lighten, "lighten", *b"lite"),
        (BlendMode::Difference, "difference", *b"diff"),
    ];
    let mut engine = Engine::new(129, 131).unwrap();
    fill(&mut engine, [80, 120, 200, 192]);
    engine.command(Command::AddLayer).unwrap();
    fill(&mut engine, [180, 70, 130, 128]);
    for (mode, name, code) in modes {
        engine.command(Command::SetBlend { id: 2, mode }).unwrap();
        let bytes = export(&engine);
        let parsed = parse(&bytes);
        assert_eq!(parsed.layers[1].mode, code);
        let png = engine
            .export_image(ExportOptions {
                transparent: true,
                ..Default::default()
            })
            .unwrap();
        let mut reader = png::Decoder::new(png.as_slice()).read_info().unwrap();
        let mut pixels = vec![0; reader.output_buffer_size()];
        reader.next_frame(&mut pixels).unwrap();
        assert!(parsed.merged == pixels, "Merged preview differs for {name}");
        fixture(&format!("{name}.psd"), &bytes);
        fixture(&format!("{name}-expected.png"), &png);
    }
}

#[test]
fn packbits_handles_long_runs_literal_boundaries_and_partial_tiles() {
    for width in [1, 2, 127, 128, 129, 257, 8192] {
        let mut engine = Engine::new(width, 3).unwrap();
        let mut expected = Vec::new();
        for y in 0..3 {
            for x in 0..width {
                let pixel = [
                    (x % 251) as u8,
                    if x < 129 { 80 } else { (x % 2) as u8 },
                    y as u8,
                    255,
                ];
                let tile = engine.document.layers[0]
                    .raster_mut()
                    .unwrap()
                    .tiles_mut()
                    .entry((x / TILE_SIZE, 0))
                    .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
                let offset = ((y * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&pixel);
                expected.extend(pixel);
            }
        }
        let parsed = parse(&export(&engine));
        assert!(
            parsed.layers[0].pixels == expected,
            "Layer differs at width {width}"
        );
        assert!(parsed.merged == expected, "Merged differs at width {width}");
    }
}

#[test]
fn sparse_layers_keep_offsets_empty_layers_and_transparent_gaps() {
    let mut engine = Engine::new(513, 385).unwrap();
    let mut pixels = vec![0; TILE_BYTES];
    pixels[..4].copy_from_slice(&[40, 80, 120, 128]);
    engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .set_tiles(BTreeMap::from([
            ((1, 1), Arc::new(pixels.clone())),
            ((3, 2), Arc::new(pixels)),
        ]));
    for _ in 1..MAX_LAYERS {
        engine.command(Command::AddLayer).unwrap();
    }
    let bytes = export(&engine);
    let parsed = parse(&bytes);
    assert_eq!(parsed.layers.len(), MAX_LAYERS);
    assert_eq!(parsed.layers[1].bounds, [0, 0, 1, 1]);
    assert_eq!(parsed.layers[1].pixels, [0, 0, 0, 0]);
    let lower = &parsed.layers[0];
    assert_eq!(lower.bounds, [128, 128, 384, 512]);
    assert_eq!(&lower.pixels[..4], &[80, 159, 239, 128]);
    let gap = (128 * 4) as usize;
    assert_eq!(&lower.pixels[gap..gap + 4], &[0; 4]);
    fixture("sparse.psd", &bytes);
    let empty = parse(&export(&Engine::new(1, 1).unwrap()));
    assert_eq!(empty.merged, [0; 4]);
}

fn fixture(name: &str, bytes: &[u8]) {
    if let Ok(directory) = std::env::var("PODOR_PSD_FIXTURES") {
        std::fs::create_dir_all(&directory).unwrap();
        std::fs::write(std::path::Path::new(&directory).join(name), bytes).unwrap();
    }
}

#[test]
fn indexed_layers_export_original_straight_colors_and_remain_editable_rgb_layers() {
    let colors = vec![
        [0, 0, 0, 0],
        [7, 150, 233, 1],
        [40, 200, 80, 128],
        [250, 30, 40, 255],
    ];
    let mut engine = Engine::new(129, 2).unwrap();
    engine.document.palette = Some(IndexedPalette {
        colors: colors.clone(),
        transparent: 0,
        order: vec![0, 1, 2, 3],
    });
    let mut first = vec![0; INDEX_TILE_BYTES];
    first[0] = 1;
    first[1] = 3;
    let mut last = vec![0; INDEX_TILE_BYTES];
    last[0] = 2;
    engine.document.layers[0].content =
        podor_engine::model::LayerContent::Raster(RasterPlane::Indexed(BTreeMap::from([
            ((0, 0), Arc::new(first)),
            ((1, 0), Arc::new(last)),
        ])));
    let mut upper = Layer::new(2, "Indexed upper".into());
    let mut pixels = vec![0; INDEX_TILE_BYTES];
    pixels[TILE_SIZE as usize] = 3;
    upper.content = podor_engine::model::LayerContent::Raster(RasterPlane::Indexed(
        BTreeMap::from([((0, 0), Arc::new(pixels))]),
    ));
    upper.visible = false;
    engine.document.layers.push(upper);
    engine.document.next_id = 3;
    engine.document.validate().unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    let encoded = export(&engine);
    let parsed = parse(&encoded);
    assert_eq!(parsed.layers.len(), 2);
    assert_eq!(&parsed.layers[0].pixels[..4], &colors[1]);
    assert_eq!(&parsed.layers[0].pixels[4..8], &colors[3]);
    assert_eq!(&parsed.layers[0].pixels[128 * 4..129 * 4], &colors[2]);
    assert_eq!(parsed.layers[1].flags & 2, 2);
    assert_eq!(&parsed.layers[1].pixels[128 * 4..129 * 4], &colors[3]);
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&encoded).unwrap();
    assert!(reopened.document.palette.is_none());
    assert_eq!(reopened.document.layers.len(), 2);
    for x in [0, 1, 128] {
        let expected = engine.document.layers[0]
            .rgba_tile(engine.document.palette.as_ref(), (x / TILE_SIZE, 0))
            .unwrap();
        let actual = reopened.document.layers[0]
            .rgba_tile(None, (x / TILE_SIZE, 0))
            .unwrap();
        let offset = (x % TILE_SIZE * 4) as usize;
        assert_eq!(&expected[offset..offset + 4], &actual[offset..offset + 4]);
    }
    assert_eq!(before, engine.save().unwrap());
    assert_eq!(state, engine.state());
}

#[test]
fn indexed_clip_chain_exports_straight_channels_explicit_group_flags_and_matching_merged_image() {
    let colors = vec![
        [0, 0, 0, 0],
        [7, 150, 233, 1],
        [40, 200, 80, 128],
        [250, 30, 40, 255],
    ];
    let mut engine = Engine::new(3, 1).unwrap();
    engine.document.palette = Some(IndexedPalette {
        colors: colors.clone(),
        transparent: 0,
        order: vec![0, 1, 2, 3],
    });
    let indices = [3, 1, 2];
    engine.document.layers.clear();
    for (offset, &index) in indices.iter().enumerate() {
        let mut layer = Layer::new(offset as u32 + 1, format!("Layer {offset}"));
        let mut tile = vec![0; INDEX_TILE_BYTES];
        tile[..3].fill(index);
        layer.content = podor_engine::model::LayerContent::Raster(RasterPlane::Indexed(
            BTreeMap::from([((0, 0), Arc::new(tile))]),
        ));
        layer.clipping = offset != 0;
        engine.document.layers.push(layer);
    }
    engine.document.next_id = 4;
    engine.document.active = 3;
    engine.document.validate().unwrap();
    let original_indices = engine
        .document
        .layers
        .iter()
        .map(|layer| layer.raster().unwrap().tiles()[&(0, 0)].clone())
        .collect::<Vec<_>>();
    let before = engine.save().unwrap();
    let state = engine.state();
    let encoded = export(&engine);
    let parsed = parse(&encoded);
    for (offset, layer) in parsed.layers.iter().enumerate() {
        assert_eq!(layer.clipping, u8::from(offset != 0));
        assert_eq!(layer.clipped_as_group, Some(true));
        assert_eq!(layer.pixels, [colors[indices[offset] as usize]; 3].concat());
    }
    let expected_png = engine
        .export_image(ExportOptions {
            transparent: true,
            ..Default::default()
        })
        .unwrap();
    let mut reader = png::Decoder::new(expected_png.as_slice())
        .read_info()
        .unwrap();
    let mut expected = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut expected).unwrap();
    expected.truncate(info.buffer_size());
    assert_eq!(parsed.merged, expected);
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&encoded).unwrap();
    assert!(reopened.document.palette.is_none());
    assert_eq!(
        &engine.frame_with_background(true)[24..36],
        &reopened.frame_with_background(true)[24..36]
    );
    for (layer, tile) in engine.document.layers.iter().zip(original_indices) {
        assert!(Arc::ptr_eq(
            &layer.raster().unwrap().tiles()[&(0, 0)],
            &tile
        ));
    }
    assert_eq!(before, engine.save().unwrap());
    assert_eq!(state, engine.state());
}
