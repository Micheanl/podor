use image::{codecs::jpeg::JpegEncoder, codecs::webp::WebPEncoder, ImageEncoder};
use podor_engine::{model::*, Command, Engine};

fn encoded(rgb: &[u8], width: u32, height: u32, jpeg: bool, orientation: u8) -> Vec<u8> {
    let exif = vec![
        b'I',
        b'I',
        42,
        0,
        8,
        0,
        0,
        0,
        1,
        0,
        0x12,
        1,
        3,
        0,
        1,
        0,
        0,
        0,
        orientation,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
    ];
    let mut bytes = Vec::new();
    if jpeg {
        let mut encoder = JpegEncoder::new_with_quality(&mut bytes, 100);
        encoder.set_exif_metadata(exif).unwrap();
        encoder
            .encode(rgb, width, height, image::ExtendedColorType::Rgb8)
            .unwrap();
    } else {
        let mut encoder = WebPEncoder::new_lossless(&mut bytes);
        encoder.set_exif_metadata(exif).unwrap();
        encoder
            .encode(rgb, width, height, image::ExtendedColorType::Rgb8)
            .unwrap();
    }
    bytes
}

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    let layer = &engine.document.layers[0];
    let Some(tile) = layer
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
    else {
        return [0; 4];
    };
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tile[offset..offset + 4].try_into().unwrap()
}

#[test]
fn jpeg_rgb_and_grayscale_open_as_editable_documents() {
    for grayscale in [false, true] {
        let mut bytes = Vec::new();
        let mut encoder = JpegEncoder::new_with_quality(&mut bytes, 100);
        if grayscale {
            encoder
                .encode(&vec![85; 32 * 24], 32, 24, image::ExtendedColorType::L8)
                .unwrap();
        } else {
            encoder
                .encode(
                    &[60, 120, 180].repeat(32 * 24),
                    32,
                    24,
                    image::ExtendedColorType::Rgb8,
                )
                .unwrap();
        }
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&bytes).unwrap();
        assert_eq!((engine.document.width, engine.document.height), (32, 24));
        assert_eq!(engine.document.layers[0].name, "导入的图像");
        let expected = if grayscale {
            [85, 85, 85, 255]
        } else {
            [60, 120, 180, 255]
        };
        for (actual, expected) in pixel(&engine, 12, 12).into_iter().zip(expected) {
            assert!(actual.abs_diff(expected) <= 2);
        }
        assert_eq!(engine.state()["canUndo"], false);
        let before = engine.save().unwrap();
        engine.command(Command::Clear).unwrap();
        engine.command(Command::Undo).unwrap();
        assert_eq!(before, engine.save().unwrap());
    }
}

#[test]
fn webp_alpha_is_premultiplied_once_across_tile_boundaries() {
    let mut rgba = Vec::new();
    for y in 0..129 {
        for x in 0..130 {
            rgba.extend_from_slice(&[204, 102, 51, [0, 1, 64, 128, 255][(x + y) % 5]]);
        }
    }
    let mut bytes = Vec::new();
    WebPEncoder::new_lossless(&mut bytes)
        .encode(&rgba, 130, 129, image::ExtendedColorType::Rgba8)
        .unwrap();
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&bytes).unwrap();
    assert_eq!((engine.document.width, engine.document.height), (130, 129));
    for y in 0..129 {
        for x in 0..130 {
            let alpha = u32::from(rgba[((y * 130 + x) * 4 + 3) as usize]);
            let actual = pixel(&engine, x, y);
            for (channel, value) in actual[..3].iter().zip([204, 102, 51]) {
                assert_eq!(*channel, ((value * alpha + 127) / 255) as u8);
            }
            assert_eq!(u32::from(actual[3]), alpha);
        }
    }
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
    assert_eq!(saved, engine.save().unwrap());
}

#[test]
fn jpeg_and_webp_apply_all_eight_exif_orientations() {
    let values = [25, 60, 100, 140, 185, 230];
    let mut rgb = Vec::new();
    for y in 0..32 {
        for x in 0..48 {
            rgb.extend_from_slice(&[values[y / 16 * 3 + x / 16]; 3]);
        }
    }
    for jpeg in [true, false] {
        for orientation in 1..=8 {
            let bytes = encoded(&rgb, 48, 32, jpeg, orientation);
            let mut engine = Engine::new(1, 1).unwrap();
            engine.load(&bytes).unwrap();
            let dimensions = if orientation >= 5 { (32, 48) } else { (48, 32) };
            assert_eq!((engine.document.width, engine.document.height), dimensions);
            for (index, value) in values.into_iter().enumerate() {
                let (x, y) = ((index % 3 * 16 + 8) as u32, (index / 3 * 16 + 8) as u32);
                let (x, y) = match orientation {
                    1 => (x, y),
                    2 => (47 - x, y),
                    3 => (47 - x, 31 - y),
                    4 => (x, 31 - y),
                    5 => (y, x),
                    6 => (31 - y, x),
                    7 => (31 - y, 47 - x),
                    8 => (y, 47 - x),
                    _ => unreachable!(),
                };
                assert!(
                    pixel(&engine, x, y)[0].abs_diff(value) <= 2,
                    "jpeg={jpeg}, orientation={orientation}"
                );
            }
        }
    }
}

#[test]
fn oversized_dimensions_are_rejected_before_pixel_decode() {
    let rgb = vec![100; 8 * 8 * 3];
    for jpeg in [true, false] {
        for (width, height) in [(8193u32, 1u32), (8192, 8192)] {
            let mut bytes = encoded(&rgb, 8, 8, jpeg, 1);
            if jpeg {
                let sof = bytes
                    .windows(2)
                    .position(|value| value == [0xff, 0xc0])
                    .unwrap();
                bytes[sof + 5..sof + 7].copy_from_slice(&(height as u16).to_be_bytes());
                bytes[sof + 7..sof + 9].copy_from_slice(&(width as u16).to_be_bytes());
            } else {
                let vp8x = bytes.windows(4).position(|value| value == b"VP8X").unwrap();
                bytes[vp8x + 12..vp8x + 15].copy_from_slice(&(width - 1).to_le_bytes()[..3]);
                bytes[vp8x + 15..vp8x + 18].copy_from_slice(&(height - 1).to_le_bytes()[..3]);
                let vp8l = bytes.windows(4).position(|value| value == b"VP8L").unwrap();
                let header = (width - 1) | ((height - 1) << 14);
                bytes[vp8l + 9..vp8l + 13].copy_from_slice(&header.to_le_bytes());
            }
            let mut engine = Engine::new(1, 1).unwrap();
            assert_eq!(engine.load(&bytes).unwrap_err(), "画布尺寸超出限制");
        }
    }
}

#[test]
fn damaged_images_preserve_document_history_selection_and_pixels() {
    let mut engine = Engine::new(16, 16).unwrap();
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color: [120, 30, 60, 255],
            tolerance: 0,
        })
        .unwrap();
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 2,
                top: 2,
                right: 8,
                bottom: 8,
            }),
        })
        .unwrap();
    let saved = engine.save().unwrap();
    let state = engine.state();
    for bytes in [
        b"\xff\xd8\xffbroken".as_slice(),
        b"RIFF\x20\0\0\0WEBPVP8Lbroken",
        b"unknown image",
    ] {
        assert!(engine.load(bytes).is_err());
        assert_eq!(engine.save().unwrap(), saved);
        assert_eq!(engine.state(), state);
    }
    engine.command(Command::Undo).unwrap();
    assert_eq!(pixel(&engine, 0, 0), [0; 4]);
}

#[test]
fn external_lossy_webp_and_progressive_jpeg_decode() {
    for (bytes, expected) in [
        (
            include_bytes!("fixtures/lossy-alpha.webp").as_slice(),
            [25u8, 50, 100, 128],
        ),
        (
            include_bytes!("fixtures/progressive.jpg").as_slice(),
            [60u8, 120, 180, 255],
        ),
    ] {
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(bytes).unwrap();
        assert_eq!((engine.document.width, engine.document.height), (24, 16));
        for (actual, expected) in pixel(&engine, 12, 8).into_iter().zip(expected) {
            assert!(actual.abs_diff(expected) <= 3);
        }
    }
}

#[test]
fn animated_webp_is_explicitly_rejected_without_changing_the_document() {
    let mut engine = Engine::new(32, 24).unwrap();
    let saved = engine.save().unwrap();
    let error = engine
        .load(include_bytes!("fixtures/animated.webp"))
        .unwrap_err();
    assert_eq!(error, "暂不支持动态 WebP，请先导出为静态图片");
    assert_eq!(engine.save().unwrap(), saved);
}
