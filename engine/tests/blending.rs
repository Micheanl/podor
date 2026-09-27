use podor_engine::{model::*, Command, Engine, ExportOptions};
use std::{
    sync::Arc,
    time::{Duration, Instant},
};

const MODES: [BlendMode; 8] = [
    BlendMode::Normal,
    BlendMode::Multiply,
    BlendMode::Screen,
    BlendMode::Overlay,
    BlendMode::SoftLight,
    BlendMode::Darken,
    BlendMode::Lighten,
    BlendMode::Difference,
];

fn fill(engine: &mut Engine, color: [u8; 4]) {
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color,
            tolerance: 0,
        })
        .unwrap();
}

fn rgba(engine: &Engine, transparent: bool) -> Vec<u8> {
    let bytes = engine
        .export_image(ExportOptions {
            transparent,
            ..Default::default()
        })
        .unwrap();
    let mut decoder = png::Decoder::new(bytes.as_slice()).read_info().unwrap();
    let mut pixels = vec![0; decoder.output_buffer_size()];
    decoder.next_frame(&mut pixels).unwrap();
    pixels
}

fn preview(engine: &mut Engine) -> Vec<u8> {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let result = engine.previews().unwrap();
        if !result.is_empty() {
            return result;
        }
        assert!(Instant::now() < deadline);
        std::thread::sleep(Duration::from_millis(1));
    }
}

#[test]
fn modes_match_known_opaque_colors_and_isolate_the_white_background() {
    let expected = [
        [192, 64, 128],
        [48, 32, 96],
        [208, 160, 224],
        [96, 65, 192],
        [96, 96, 192],
        [64, 64, 128],
        [192, 128, 192],
        [128, 64, 64],
    ];
    let mut engine = Engine::new(16, 16).unwrap();
    fill(&mut engine, [64, 128, 192, 255]);
    engine.command(Command::AddLayer).unwrap();
    fill(&mut engine, [192, 64, 128, 255]);
    for (mode, expected) in MODES.into_iter().zip(expected) {
        engine.command(Command::SetBlend { id: 2, mode }).unwrap();
        assert_eq!(&rgba(&engine, true)[..3], expected, "{mode:?}");
        let frame = engine.frame();
        assert_eq!(&frame[24..27], expected);
        assert_eq!(&rgba(&engine, false)[..3], expected);
        assert_eq!(
            engine.command(Command::Pick { x: 0, y: 0 }).unwrap()["color"],
            serde_json::json!(expected)
        );
    }
    engine.command(Command::RemoveLayer { id: 1 }).unwrap();
    engine
        .command(Command::SetBlend {
            id: 2,
            mode: BlendMode::Screen,
        })
        .unwrap();
    assert_eq!(&rgba(&engine, false)[..4], [192, 64, 128, 255]);
}

fn reference(mode: BlendMode, b: f64, s: f64) -> f64 {
    match mode {
        BlendMode::Normal => s,
        BlendMode::Multiply => b * s,
        BlendMode::Screen => 1.0 - (1.0 - b) * (1.0 - s),
        BlendMode::Overlay => {
            if b <= 0.5 {
                2.0 * b * s
            } else {
                1.0 - 2.0 * (1.0 - b) * (1.0 - s)
            }
        }
        BlendMode::SoftLight => {
            if s <= 0.5 {
                b - (1.0 - 2.0 * s) * b * (1.0 - b)
            } else {
                let d = if b <= 0.25 {
                    ((16.0 * b - 12.0) * b + 4.0) * b
                } else {
                    b.sqrt()
                };
                b + (2.0 * s - 1.0) * (d - b)
            }
        }
        BlendMode::Darken => b.min(s),
        BlendMode::Lighten => b.max(s),
        BlendMode::Difference => (b - s).abs(),
    }
}

#[test]
fn partial_alpha_and_layer_opacity_follow_source_over_without_halos() {
    let mut engine = Engine::new(25, 1).unwrap();
    engine.command(Command::AddLayer).unwrap();
    let mut bottom = vec![0; TILE_BYTES];
    let mut top = vec![0; TILE_BYTES];
    for (i, (ab, a_s)) in [0u32, 1, 64, 128, 255]
        .into_iter()
        .flat_map(|ab| [0u32, 1, 64, 200, 255].map(|a_s| (ab, a_s)))
        .enumerate()
    {
        for (tile, rgb, alpha) in [
            (&mut bottom, [64, 128, 192], ab),
            (&mut top, [192, 64, 128], a_s),
        ] {
            for c in 0..3 {
                tile[i * 4 + c] = ((rgb[c] * alpha + 127) / 255) as u8;
            }
            tile[i * 4 + 3] = alpha as u8;
        }
    }
    engine.document.layers[0]
        .tiles
        .insert((0, 0), Arc::new(bottom.clone()));
    engine.document.layers[1]
        .tiles
        .insert((0, 0), Arc::new(top.clone()));
    engine
        .command(Command::SetLayer {
            id: 2,
            visible: true,
            opacity: 0.37,
            name: "top".into(),
        })
        .unwrap();
    let opacity = (0.37f64 * 255.0).round() / 255.0;
    for mode in MODES {
        engine.command(Command::SetBlend { id: 2, mode }).unwrap();
        let pixels = rgba(&engine, true);
        let white = rgba(&engine, false);
        for i in 0..25 {
            let ab = f64::from(bottom[i * 4 + 3]) / 255.0;
            let original_as = f64::from(top[i * 4 + 3]) / 255.0;
            let a_s = original_as * opacity;
            let alpha = a_s + ab * (1.0 - a_s);
            assert!((f64::from(pixels[i * 4 + 3]) - alpha * 255.0).abs() <= 1.0);
            for c in 0..3 {
                let b = if ab == 0.0 {
                    0.0
                } else {
                    f64::from(bottom[i * 4 + c]) / (255.0 * ab)
                };
                let s = if original_as == 0.0 {
                    0.0
                } else {
                    f64::from(top[i * 4 + c]) / (255.0 * original_as)
                };
                let expected = ((1.0 - a_s) * ab * b
                    + (1.0 - ab) * a_s * s
                    + a_s * ab * reference(mode, b, s))
                    * 255.0;
                let actual = f64::from(pixels[i * 4 + c]) * f64::from(pixels[i * 4 + 3]) / 255.0;
                assert!(
                    (actual - expected).abs() <= 2.0,
                    "{mode:?} pixel={i}, channel={c}: {actual} != {expected}"
                );
                assert!(
                    (f64::from(white[i * 4 + c]) - expected - (1.0 - alpha) * 255.0).abs() <= 2.0
                );
            }
        }
    }
}

#[test]
fn blend_changes_round_trip_and_undo() {
    let mut engine = Engine::new(32, 24).unwrap();
    fill(&mut engine, [52, 104, 208, 128]);
    engine.command(Command::AddLayer).unwrap();
    fill(&mut engine, [170, 80, 50, 200]);
    engine
        .command(Command::SetBlend {
            id: 2,
            mode: BlendMode::SoftLight,
        })
        .unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.document.layers[1].blend, BlendMode::Normal);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.document.layers[1].blend, BlendMode::SoftLight);
    engine
        .command(Command::SetLayer {
            id: 2,
            visible: true,
            opacity: 0.4,
            name: "renamed".into(),
        })
        .unwrap();
    let saved = engine.save().unwrap();
    assert!(saved.starts_with(b"PODOR\x02"));
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&saved).unwrap();
    assert_eq!(reopened.document.layers[1].blend, BlendMode::SoftLight);
    assert_eq!(reopened.state()["layers"][1]["blend"], "soft_light");
    assert_eq!(rgba(&reopened, true), rgba(&engine, true));
    let unsupported = [b"OTHER\x01".as_slice(), &saved[6..]].concat();
    assert!(reopened.load(&unsupported).is_err());
    assert_eq!(rgba(&reopened, true), rgba(&engine, true));
    assert!(engine
        .command(Command::SetBlend {
            id: 999,
            mode: BlendMode::Screen
        })
        .is_err());
    assert_eq!(saved, engine.save().unwrap());
    assert!(
        serde_json::from_str::<Command>(r#"{"type":"set_blend","id":2,"mode":"unsupported"}"#)
            .is_err()
    );
}

#[test]
fn composite_thumbnail_blends_before_resizing_correlated_details() {
    let mut engine = Engine::new(192, 192).unwrap();
    engine.command(Command::AddLayer).unwrap();
    let mut tile = vec![0; TILE_BYTES];
    for y in 0..TILE_SIZE {
        for x in 0..TILE_SIZE {
            let index = ((y * TILE_SIZE + x) * 4) as usize;
            let value = if x % 2 == 0 { 255 } else { 0 };
            tile[index..index + 4].copy_from_slice(&[value, value, value, 255]);
        }
    }
    let tile = Arc::new(tile);
    for layer in &mut engine.document.layers {
        for x in 0..2 {
            for y in 0..2 {
                layer.tiles.insert((x, y), tile.clone());
            }
        }
    }
    engine
        .command(Command::SetBlend {
            id: 2,
            mode: BlendMode::Multiply,
        })
        .unwrap();
    let bytes = preview(&mut engine);
    assert!(bytes[20..20 + (PREVIEW_EDGE * PREVIEW_EDGE * 4) as usize]
        .as_chunks::<4>()
        .0
        .iter()
        .all(|pixel| pixel == &[128, 128, 128, 255]));
    assert_eq!(
        &rgba(&engine, true)[..8],
        [255, 255, 255, 255, 0, 0, 0, 255]
    );
}
