use podor_engine::{model::*, AdjustmentRequest, Command, Engine, ExportOptions};
use serde_json::json;
use std::sync::Arc;

fn artwork(mode: BlendMode, active: u32) -> Engine {
    let mut engine = Engine::new(160, 96).unwrap();
    for id in 1..=2 {
        let mut layer = Layer::new(id, format!("Layer {id}"));
        layer.blend = if id == 2 { mode } else { BlendMode::Normal };
        layer.opacity = 0.7;
        for key in [(0, 0), (1, 0)] {
            let mut pixels = vec![0; TILE_BYTES];
            for y in 20..80 {
                for x in 0..128 {
                    let i = (y * 128 + x) * 4;
                    pixels[i..i + 4].copy_from_slice(if id == 1 {
                        &[25, 40, 60, 96]
                    } else {
                        &[64, 22, 16, 128]
                    });
                }
            }
            layer.tiles.insert(key, Arc::new(pixels));
        }
        if id == 1 {
            engine.document.layers[0] = layer;
        } else {
            engine.document.layers.push(layer);
        }
    }
    engine.document.active = active;
    engine.document.next_id = 3;
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
    engine
}

fn png(engine: &Engine) -> Vec<u8> {
    engine
        .export_image(ExportOptions {
            transparent: true,
            ..Default::default()
        })
        .unwrap()
}

fn matches_export(engine: &mut Engine) {
    let frame = engine.frame_with_background(true);
    let exported = png(engine);
    let mut reader = png::Decoder::new(exported.as_slice()).read_info().unwrap();
    let mut pixels = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut pixels).unwrap();
    assert_eq!(info.color_type, png::ColorType::Rgba);
    for tile in frame[16..].as_chunks::<{ TILE_BYTES + 8 }>().0 {
        let tx = u32::from_le_bytes(tile[..4].try_into().unwrap());
        let ty = u32::from_le_bytes(tile[4..8].try_into().unwrap());
        for (i, pixel) in tile[8..].as_chunks::<4>().0.iter().enumerate() {
            let x = tx * TILE_SIZE + i as u32 % TILE_SIZE;
            let y = ty * TILE_SIZE + i as u32 / TILE_SIZE;
            if x >= info.width || y >= info.height {
                continue;
            }
            let offset = ((y * info.width + x) * 4) as usize;
            let expected = &pixels[offset..offset + 4];
            assert_eq!(pixel[3], expected[3]);
            for c in 0..3 {
                let premultiplied = (u32::from(expected[c]) * u32::from(expected[3]) + 127) / 255;
                assert!(u32::from(pixel[c]).abs_diff(premultiplied) <= 1);
            }
        }
    }
}

#[test]
fn switching_display_frames_never_changes_pixels_history_or_export() {
    let mut engine = artwork(BlendMode::Normal, 2);
    let saved = engine.save().unwrap();
    let exported = png(&engine);
    let state = engine.state();
    let opaque = engine.frame();
    assert!(opaque[24..24 + TILE_BYTES]
        .as_chunks::<4>()
        .0
        .iter()
        .all(|p| p[3] == 255));
    let transparent = engine.frame_with_background(true);
    assert_eq!(&transparent[24..28], &[0, 0, 0, 0]);
    assert!(transparent[24..24 + TILE_BYTES]
        .as_chunks::<4>()
        .0
        .iter()
        .any(|p| p[3] > 0 && p[3] < 255));
    assert_eq!(engine.frame_with_background(true).len(), 16);
    assert_eq!(engine.frame(), opaque);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), saved);
    assert_eq!(png(&engine), exported);
}

#[test]
fn transparent_stroke_cache_matches_export_across_blends_erasers_and_mode_switches() {
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
        for active in [1, 2] {
            let mut engine = artwork(mode, active);
            for eraser in [false, true] {
                matches_export(&mut engine);
                engine
                    .command(Command::Begin {
                        brush: Brush {
                            size: 35.0,
                            opacity: 0.5,
                            hardness: 0.4,
                            color: [180, 50, 30],
                            eraser,
                            ..Default::default()
                        },
                    })
                    .unwrap();
                for (x, y) in [(115.0, 28.0), (130.0, 42.0), (152.0, 77.0)] {
                    engine
                        .samples(&[Sample {
                            x,
                            y,
                            pressure: 0.7,
                        }])
                        .unwrap();
                    matches_export(&mut engine);
                    engine.frame();
                    matches_export(&mut engine);
                }
                engine.command(Command::End).unwrap();
                matches_export(&mut engine);
                engine.command(Command::Undo).unwrap();
                matches_export(&mut engine);
                engine.command(Command::Redo).unwrap();
                matches_export(&mut engine);
            }
        }
    }
}

#[test]
fn adjustment_preview_and_commit_retain_transparency() {
    for kind in ["tone", "blur", "layer_blend"] {
        let mut engine = artwork(BlendMode::Screen, 2);
        engine.frame_with_background(true);
        let initial = engine.save().unwrap();
        let request = json!({"id":2,"revision":engine.state()["revision"],"settings":{"kind":kind,"brightness":0.15,"contrast":0.1,"saturation":-0.1,"sigma":3.0,"opacity":0.25,"blend":"overlay"}});
        let preview = engine
            .preview_adjustment(
                serde_json::from_value::<AdjustmentRequest>(request.clone()).unwrap(),
            )
            .unwrap();
        assert_eq!(engine.save().unwrap(), initial);
        engine
            .command(
                serde_json::from_value(json!({"type":"apply_adjustment","request":request}))
                    .unwrap(),
            )
            .unwrap();
        assert_eq!(engine.frame_with_background(true), preview);
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), initial);
        matches_export(&mut engine);
    }
}
