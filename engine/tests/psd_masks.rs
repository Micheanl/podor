use flate2::{write::ZlibEncoder, Compression};
use podor_engine::{model::*, Command, Engine, ExportFormat, ExportOptions};
use std::{io::Write, sync::Arc};

const VALUES: [u8; 18] = [
    0, 32, 64, 128, 192, 255, 255, 192, 128, 64, 32, 0, 0, 64, 128, 192, 255, 128,
];

#[derive(Clone)]
struct FixtureLayer {
    bounds: [i32; 4],
    mask: Vec<u8>,
    channels: Vec<(i16, Vec<u8>)>,
}

fn block(data: &[u8], output: &mut Vec<u8>) {
    output.extend((data.len() as u32).to_be_bytes());
    output.extend(data);
}

fn encoded(plane: &[u8], width: usize, compression: u16) -> Vec<u8> {
    let mut result = compression.to_be_bytes().to_vec();
    match compression {
        0 => result.extend(plane),
        1 => {
            let mut rows = Vec::new();
            for row in plane.chunks(width) {
                let mut data = Vec::new();
                for run in row.chunks(128) {
                    if run.len() > 1 && run.iter().all(|value| *value == run[0]) {
                        data.extend([(257 - run.len()) as u8, run[0]]);
                    } else {
                        data.push((run.len() - 1) as u8);
                        data.extend(run);
                    }
                }
                result.extend((data.len() as u16).to_be_bytes());
                rows.push(data);
            }
            for row in rows {
                result.extend(row);
            }
        }
        2 | 3 => {
            let mut data = plane.to_vec();
            if compression == 3 {
                for row in data.chunks_mut(width) {
                    for index in (1..row.len()).rev() {
                        row[index] = row[index].wrapping_sub(row[index - 1]);
                    }
                }
            }
            let mut zipper = ZlibEncoder::new(Vec::new(), Compression::fast());
            zipper.write_all(&data).unwrap();
            result.extend(zipper.finish().unwrap());
        }
        _ => unreachable!(),
    }
    result
}

fn mask_metadata(bounds: [i32; 4], default: u8, flags: u8) -> Vec<u8> {
    let mut result = Vec::new();
    for value in bounds {
        result.extend(value.to_be_bytes());
    }
    result.extend([default, flags, 0, 0]);
    result
}

fn layer(compression: u16, default: u8, flags: u8) -> FixtureLayer {
    FixtureLayer {
        bounds: [1, 2, 5, 7],
        mask: mask_metadata([-1, -2, 2, 4], default, flags),
        channels: [2i16, -2, 0, -1, 1]
            .into_iter()
            .map(|id| {
                let data = if id == -2 {
                    encoded(&VALUES, 6, compression)
                } else {
                    encoded(
                        &[match id {
                            0 => 200,
                            1 => 100,
                            2 => 50,
                            _ => 192,
                        }; 20],
                        5,
                        compression,
                    )
                };
                (id, data)
            })
            .collect(),
    }
}

fn fixture(layers: &[FixtureLayer]) -> Vec<u8> {
    let mut output = b"8BPS\0\x01\0\0\0\0\0\0\0\x04".to_vec();
    output.extend(7u32.to_be_bytes());
    output.extend(9u32.to_be_bytes());
    output.extend([0, 8, 0, 3]);
    output.extend([0; 8]);
    let mut info = (-(layers.len() as i16)).to_be_bytes().to_vec();
    for layer in layers {
        for value in layer.bounds {
            info.extend(value.to_be_bytes());
        }
        info.extend((layer.channels.len() as u16).to_be_bytes());
        for (id, bytes) in &layer.channels {
            info.extend(id.to_be_bytes());
            info.extend((bytes.len() as u32).to_be_bytes());
        }
        info.extend(b"8BIMnorm");
        info.extend([255, 0, 0, 0]);
        let mut extra = Vec::new();
        block(&layer.mask, &mut extra);
        extra.extend([0; 4]);
        extra.extend([1, b'L', 0, 0]);
        block(&extra, &mut info);
    }
    for layer in layers {
        for (_, bytes) in &layer.channels {
            info.extend(bytes);
        }
    }
    if !info.len().is_multiple_of(2) {
        info.push(0);
    }
    let mut section = Vec::new();
    block(&info, &mut section);
    section.extend([0; 4]);
    block(&section, &mut output);
    output
}

fn rgba_pixel(layer: &Layer, x: u32, y: u32) -> [u8; 4] {
    let tile = &layer.raster().unwrap().tiles()[&(x / TILE_SIZE, y / TILE_SIZE)];
    let start = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tile[start..start + 4].try_into().unwrap()
}

fn transparent_png(engine: &Engine) -> Vec<u8> {
    let encoded = engine
        .export_image(ExportOptions {
            transparent: true,
            ..Default::default()
        })
        .unwrap();
    let mut reader = png::Decoder::new(encoded.as_slice()).read_info().unwrap();
    let mut pixels = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut pixels).unwrap();
    assert_eq!(info.color_type, png::ColorType::Rgba);
    pixels.truncate(info.buffer_size());
    pixels
}

#[test]
fn external_user_mask_planes_preserve_offsets_alpha_and_all_compressions() {
    for compression in 0..=3 {
        for default in [0, 255] {
            let mut engine = Engine::new(1, 1).unwrap();
            engine
                .load(&fixture(&[layer(compression, default, 0)]))
                .unwrap();
            let active = &engine.document.layers[0];
            let mask = active.first_mask().unwrap();
            assert_eq!(
                mask.bounds,
                MaskBounds {
                    left: -2,
                    top: -1,
                    right: 4,
                    bottom: 2
                }
            );
            assert_eq!(mask.default, default);
            assert!(mask.enabled && mask.linked);
            for y in 0..3 {
                for x in 0..6 {
                    assert_eq!(mask.sample(x - 2, y - 1), VALUES[(y * 6 + x) as usize]);
                }
            }
            assert_eq!(mask.sample(-3, 0), default);
            assert_eq!(mask.sample(4, 1), default);
            assert_eq!(mask.tiles.values().next().unwrap().len(), MASK_TILE_BYTES);
            assert_eq!(rgba_pixel(active, 3, 1), [151, 75, 38, 192]);
            let pixels = transparent_png(&engine);
            assert_eq!(pixels[(9 + 2) * 4 + 3], 192);
            assert_eq!(pixels[(9 + 3) * 4 + 3], 96);
            assert_eq!(pixels[(9 + 4) * 4 + 3], if default == 0 { 0 } else { 192 });
            let original = pixels;
            let saved = engine.save().unwrap();
            let mut restored = Engine::new(1, 1).unwrap();
            restored.load(&saved).unwrap();
            assert_eq!(restored.document.layers[0].first_mask(), Some(mask));
            assert_eq!(transparent_png(&restored), original);
        }
    }
}

#[test]
fn disabled_unlinked_and_inverted_masks_keep_absolute_geometry() {
    for flags in [1u8, 2, 3, 4, 5, 6, 7] {
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&fixture(&[layer(3, 0, flags)])).unwrap();
        let mask = engine.document.layers[0].first_mask().unwrap();
        assert_eq!(mask.linked, flags & 1 == 0);
        assert_eq!(mask.enabled, flags & 2 == 0);
        assert_eq!(mask.bounds.left, -2);
        assert_eq!(mask.default, if flags & 4 != 0 { 255 } else { 0 });
        assert_eq!(mask.sample(-2, -1), if flags & 4 != 0 { 255 } else { 0 });
        let alpha = transparent_png(&engine)[(9 + 3) * 4 + 3];
        assert_eq!(alpha, if flags & 2 != 0 { 192 } else { 96 });
        let exported = engine
            .export_image(ExportOptions {
                format: ExportFormat::Psd,
                ..Default::default()
            })
            .unwrap();
        let mut restored = Engine::new(1, 1).unwrap();
        restored.load(&exported).unwrap();
        assert_eq!(restored.document.layers[0].first_mask(), Some(mask));
        assert_eq!(transparent_png(&restored), transparent_png(&engine));
    }
}

#[test]
fn a_user_mask_does_not_replace_an_absent_transparency_channel() {
    let mut source = layer(2, 255, 0);
    source.channels.retain(|(id, _)| *id != -1);
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&fixture(&[source])).unwrap();
    assert_eq!(
        rgba_pixel(&engine.document.layers[0], 3, 1),
        [200, 100, 50, 255]
    );
    let pixels = transparent_png(&engine);
    assert_eq!(pixels[(9 + 3) * 4 + 3], 128);
    assert_eq!(pixels[(9 + 4) * 4 + 3], 255);
}

#[test]
fn empty_user_masks_preserve_default_visibility_without_allocating_a_plane() {
    for compression in 0u16..=3 {
        for (default, flags) in [(0, 0), (255, 0), (0, 2), (255, 3)] {
            let mut source = layer(0, default, flags);
            source.mask = mask_metadata([1, 2, 1, 2], default, flags);
            source.channels[1].1 = if compression < 2 {
                compression.to_be_bytes().to_vec()
            } else {
                encoded(&[], 1, compression)
            };
            let mut engine = Engine::new(1, 1).unwrap();
            engine.load(&fixture(&[source])).unwrap();
            let mask = engine.document.layers[0].first_mask().unwrap();
            assert!(mask.tiles.is_empty());
            assert_eq!(mask.bounds.width(), 0);
            assert_eq!(mask.bounds.height(), 0);
            assert_eq!(mask.sample(2, 1), default);
            assert_eq!(
                transparent_png(&engine)[(9 + 3) * 4 + 3],
                if flags & 2 != 0 || default == 255 {
                    192
                } else {
                    0
                }
            );
            let exported = engine
                .export_image(ExportOptions {
                    format: ExportFormat::Psd,
                    ..Default::default()
                })
                .unwrap();
            let mut restored = Engine::new(1, 1).unwrap();
            restored.load(&exported).unwrap();
            assert_eq!(restored.document.layers[0].first_mask(), Some(mask));
            assert_eq!(transparent_png(&restored), transparent_png(&engine));
        }
    }
}

struct Cursor<'a>(&'a [u8]);

impl<'a> Cursor<'a> {
    fn take(&mut self, size: usize) -> &'a [u8] {
        let (head, tail) = self.0.split_at(size);
        self.0 = tail;
        head
    }

    fn short(&mut self) -> u16 {
        u16::from_be_bytes(self.take(2).try_into().unwrap())
    }
    fn long(&mut self) -> u32 {
        u32::from_be_bytes(self.take(4).try_into().unwrap())
    }
    fn block(&mut self) -> Self {
        let size = self.long() as usize;
        Self(self.take(size))
    }
}

fn decode_packbits(data: &[u8], width: usize, height: usize) -> Vec<u8> {
    let mut cursor = Cursor(data);
    assert_eq!(cursor.short(), 1);
    let lengths: Vec<_> = (0..height).map(|_| cursor.short() as usize).collect();
    let mut pixels = Vec::new();
    for length in lengths {
        let mut row = Cursor(cursor.take(length));
        let start = pixels.len();
        while !row.0.is_empty() {
            let control = row.take(1)[0] as i8;
            if control >= 0 {
                pixels.extend(row.take(control as usize + 1));
            } else if control != -128 {
                pixels.extend(std::iter::repeat_n(
                    row.take(1)[0],
                    (1 - i16::from(control)) as usize,
                ));
            }
        }
        assert_eq!(pixels.len() - start, width);
    }
    assert!(cursor.0.is_empty());
    pixels
}

#[test]
fn exporter_writes_independent_mask_dimensions_bytes_flags_and_merged_alpha() {
    let mut engine = Engine::new(129, 131).unwrap();
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: [200, 100, 50, 192],
            tolerance: 0,
            contiguous: true,
            merged: false,
        })
        .unwrap();
    let mut mask = LayerMask::new(
        MaskBounds {
            left: -3,
            top: 1,
            right: 130,
            bottom: 132,
        },
        255,
    );
    mask.linked = false;
    let mut first = vec![255; MASK_TILE_BYTES];
    first[3] = 0;
    first[4] = 128;
    mask.tiles.insert((0, 0), Arc::new(first));
    let mut last = vec![255; MASK_TILE_BYTES];
    last[2 * TILE_SIZE as usize + 4] = 64;
    mask.tiles.insert((1, 1), Arc::new(last));
    engine.document.layers[0].set_first_mask(Some(mask.clone()));
    engine.document.assign_mask_ids().unwrap();
    let output = engine
        .export_image(ExportOptions {
            format: ExportFormat::Psd,
            ..Default::default()
        })
        .unwrap();
    let mut file = Cursor(&output[26..]);
    assert!(file.block().0.is_empty());
    assert_eq!(
        file.block().0,
        [
            b"8BIM\x04\x2D\0\0\0\0\0\x06\0\x01".as_slice(),
            &1u32.to_be_bytes()
        ]
        .concat()
    );
    let mut section = file.block();
    let mut info = section.block();
    assert_eq!(info.short() as i16, -1);
    info.take(16);
    assert_eq!(info.short(), 5);
    let channels: Vec<_> = (0..5)
        .map(|_| (info.short() as i16, info.long() as usize))
        .collect();
    assert_eq!(
        channels.iter().map(|entry| entry.0).collect::<Vec<_>>(),
        [0, 1, 2, -1, -2]
    );
    info.take(12);
    let mut extra = info.block();
    assert_eq!(extra.block().0, mask_metadata([1, -3, 132, 130], 255, 1));
    let mut decoded_mask = Vec::new();
    for (id, length) in channels {
        let bytes = info.take(length);
        if id == -2 {
            decoded_mask = decode_packbits(bytes, 133, 131);
        }
    }
    assert_eq!(decoded_mask[3], 0);
    assert_eq!(decoded_mask[4], 128);
    assert_eq!(decoded_mask[130 * 133 + 132], 64);
    assert_eq!(decoded_mask[132], 255);
    let merged = decode_packbits(file.0, 129, 131 * 4);
    let rendered = transparent_png(&engine);
    assert_eq!(merged[129 * 131 * 3 + 129], 0);
    assert_eq!(merged[129 * 131 * 3 + 129 + 1], 96);
    assert!(merged[129 * 131 * 3..]
        .iter()
        .zip(rendered.as_chunks::<4>().0)
        .all(|(alpha, pixel)| *alpha == pixel[3]));
    let mut imported = Engine::new(1, 1).unwrap();
    imported.load(&output).unwrap();
    assert_eq!(imported.document.layers[0].first_mask(), Some(&mask));
    assert_eq!(transparent_png(&imported), rendered);
}

#[test]
fn invalid_and_unsupported_masks_do_not_replace_the_current_document() {
    let mut engine = Engine::new(3, 3).unwrap();
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
    let state = engine.state();
    let saved = engine.save().unwrap();
    let mut bad = Vec::new();
    let mut candidate = layer(0, 0, 0);
    candidate.channels.retain(|(id, _)| *id != -2);
    bad.push(candidate);
    let mut candidate = layer(0, 0, 0);
    candidate.mask.clear();
    bad.push(candidate);
    for bounds in [
        [3, 0, 0, 4],
        [0, 4, 3, 0],
        [0, 0, 8193, 1],
        [0, -16385, 1, -16384],
    ] {
        let mut candidate = layer(0, 0, 0);
        candidate.mask = mask_metadata(bounds, 0, 0);
        bad.push(candidate);
    }
    for (default, flags) in [(127, 0), (0, 8), (0, 16), (0, 128)] {
        bad.push(layer(0, default, flags));
    }
    let mut candidate = layer(0, 0, 0);
    candidate.mask.extend([0; 16]);
    bad.push(candidate);
    let mut candidate = layer(0, 0, 0);
    candidate.channels[1].0 = -3;
    bad.push(candidate);
    let mut candidate = layer(0, 0, 0);
    candidate.channels[4].0 = -2;
    bad.push(candidate);
    for compression in 0..=3 {
        let mut candidate = layer(compression, 0, 0);
        candidate.channels[1].1.pop();
        bad.push(candidate);
    }
    for layer in bad {
        assert!(engine.load(&fixture(&[layer])).is_err());
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), saved);
    }
}

#[test]
fn mask_decode_and_retained_plane_budgets_fail_atomically() {
    let mut engine = Engine::new(3, 3).unwrap();
    let saved = engine.save().unwrap();
    let mut large = layer(0, 0, 0);
    large.mask = mask_metadata([0, 0, 4096, 4096], 0, 0);
    large.channels[1].1 = vec![0, 0];
    let error = engine
        .load(&fixture(&vec![large.clone(); MAX_LAYERS]))
        .unwrap_err();
    assert!(error.contains("解码"), "{error}");
    assert_eq!(engine.save().unwrap(), saved);
    large.channels[1].1 = encoded(&vec![255; 4096 * 4096], 4096, 1);
    let error = engine.load(&fixture(&vec![large; 8])).unwrap_err();
    assert!(error.contains("内存"), "{error}");
    assert_eq!(engine.save().unwrap(), saved);
}
