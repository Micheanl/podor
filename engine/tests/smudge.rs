use podor_engine::{model::*, Command, Engine, SelectionKind, SelectionSpec};
use std::sync::Arc;

fn brush() -> Brush {
    Brush {
        size: 24.0,
        opacity: 0.8,
        hardness: 0.7,
        smudge: true,
        size_pressure: 0.0,
        ..Brush::default()
    }
}
fn point(x: f32, y: f32) -> Sample {
    Sample {
        x,
        y,
        pressure: 1.0,
    }
}
fn pixel(e: &Engine, x: u32, y: u32) -> [u8; 4] {
    e.document.layers[0]
        .tiles
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| {
            let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[i..i + 4].try_into().unwrap()
        })
}
fn set_pixel(e: &mut Engine, x: u32, y: u32, value: [u8; 4]) {
    let tile = Arc::make_mut(
        e.document
            .active_mut()
            .tiles
            .entry((x / TILE_SIZE, y / TILE_SIZE))
            .or_insert_with(|| Arc::new(vec![0; TILE_BYTES])),
    );
    let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tile[i..i + 4].copy_from_slice(&value);
}
fn colors() -> Engine {
    let mut e = Engine::new(300, 160).unwrap();
    for y in 0..160 {
        for x in 0..300 {
            set_pixel(
                &mut e,
                x,
                y,
                if x < 128 {
                    [180, 40, 60, 255]
                } else {
                    [20, 80, 200, 255]
                },
            );
        }
    }
    e
}
fn stroke(e: &mut Engine, brush: Brush, samples: &[Sample], batch: usize) {
    e.command(Command::Begin { brush }).unwrap();
    for chunk in samples.chunks(batch) {
        e.samples(chunk).unwrap();
    }
    e.command(Command::End).unwrap();
}

#[test]
fn fractional_transport_matches_bilinear_premultiplied_colors() {
    let mut e = Engine::new(32, 20).unwrap();
    set_pixel(&mut e, 8, 10, [120, 0, 0, 128]);
    set_pixel(&mut e, 9, 10, [0, 60, 0, 64]);
    set_pixel(&mut e, 13, 10, [0, 0, 200, 255]);
    let value = Brush {
        size: 4.5,
        spacing: 1.0,
        hardness: 1.0,
        opacity: 0.5,
        ..brush()
    };
    stroke(&mut e, value, &[point(8.5, 10.5), point(13.0, 10.5)], 2);
    assert_eq!(pixel(&e, 13, 10), [30, 15, 100, 176]);
    e.document.validate().unwrap();
}

#[test]
fn smearing_crosses_tiles_ignores_foreground_and_undo_restores_every_pixel() {
    let mut e = colors();
    let original = e.save().unwrap();
    let samples: Vec<_> = (0..61).map(|i| point(108.0 + i as f32, 80.0)).collect();
    stroke(&mut e, brush(), &samples, 3);
    assert!(pixel(&e, 134, 80)[0] > 20);
    assert!(pixel(&e, 134, 80)[2] < 200);
    assert_eq!(pixel(&e, 134, 20), [20, 80, 200, 255]);
    let changed = e.save().unwrap();
    e.command(Command::Undo).unwrap();
    assert_eq!(original, e.save().unwrap());
    e.command(Command::Redo).unwrap();
    assert_eq!(changed, e.save().unwrap());
    let mut other = colors();
    stroke(
        &mut other,
        Brush {
            color: [0, 255, 0],
            ..brush()
        },
        &samples,
        samples.len(),
    );
    assert_eq!(changed, other.save().unwrap());
}

#[test]
fn mixing_paints_the_brush_color_into_the_smear() {
    let samples: Vec<_> = (0..41).map(|i| point(108.0 + i as f32 * 0.5, 80.0)).collect();
    let results: Vec<[u8; 4]> = [0.0, 0.5, 1.0]
        .into_iter()
        .map(|mix| {
            let mut e = colors();
            stroke(
                &mut e,
                Brush {
                    mix,
                    color: [10, 200, 30],
                    hardness: 1.0,
                    ..brush()
                },
                &samples,
                3,
            );
            pixel(&e, 116, 80)
        })
        .collect();
    let plain = results[0];
    let half = results[1];
    let painted = results[2];
    assert!(plain[1] < 120, "smear without paint should keep the canvas");
    assert!(half[1] > plain[1], "half mix should move toward the paint");
    assert!(half[1] < painted[1], "half mix should not reach full paint");
    assert!(painted[1] > 160, "full mix should dominate with the paint");
    assert_eq!(painted[3], 255);
    assert_eq!(plain[3], 255);
}

#[test]
fn mixing_on_bare_canvas_uses_the_painterly_alpha_of_the_brush() {
    let mut e = Engine::new(64, 32).unwrap();
    let samples: Vec<_> = (0..9).map(|i| point(12.0 + i as f32, 16.0)).collect();
    stroke(
        &mut e,
        Brush {
            mix: 1.0,
            color: [0, 90, 0],
            hardness: 1.0,
            ..brush()
        },
        &samples,
        3,
    );
    let value = pixel(&e, 15, 16);
    assert!(value[3] > 0);
    assert!(
        (i32::from(value[1]) - i32::from(value[3]) * 90 / 255).abs() <= 1,
        "green {value:?} should track alpha at the 90/255 paint ratio"
    );
}

#[test]
fn short_drags_apply_the_final_segment_when_the_pen_lifts() {
    let mut e = colors();
    let before = e.save().unwrap();
    stroke(
        &mut e,
        Brush {
            spacing: 1.0,
            ..brush()
        },
        &[point(127.0, 80.0), point(128.0, 80.0)],
        1,
    );
    assert!(pixel(&e, 128, 80)[0] > 20);
    e.command(Command::Undo).unwrap();
    assert_eq!(before, e.save().unwrap());
}

#[test]
fn transparent_edges_do_not_gain_dark_fringe_and_alpha_lock_keeps_the_outline() {
    for locked in [false, true] {
        let mut e = Engine::new(260, 80).unwrap();
        for y in 0..80 {
            for x in 0..128 {
                set_pixel(&mut e, x, y, [200, 0, 0, 200]);
            }
        }
        e.document.active_mut().alpha_locked = locked;
        let before = e.save().unwrap();
        let samples: Vec<_> = (0..51).map(|i| point(112.0 + i as f32, 40.0)).collect();
        stroke(&mut e, brush(), &samples, 8);
        if locked {
            assert_eq!(before, e.save().unwrap());
            assert!(!e.state()["canUndo"].as_bool().unwrap());
        } else {
            assert!(pixel(&e, 130, 40)[3] > 0);
            for x in 0..260 {
                for y in 0..80 {
                    let p = pixel(&e, x, y);
                    assert_eq!(p[0], p[3]);
                    assert_eq!(p[1], 0);
                    assert_eq!(p[2], 0);
                }
            }
        }
        e.document.validate().unwrap();
    }
}

#[test]
fn selection_limits_sampling_and_changes_and_stroke_cancel_discards_the_buffer() {
    let mut e = colors();
    e.command(Command::SelectShape {
        selection: SelectionSpec {
            kind: SelectionKind::Ellipse,
            bounds: Rect {
                left: 100,
                top: 45,
                right: 165,
                bottom: 115,
            },
            points: vec![],
        },
    })
    .unwrap();
    let before = e.save().unwrap();
    e.command(Command::Begin { brush: brush() }).unwrap();
    e.samples(&[point(110.0, 80.0), point(150.0, 80.0)])
        .unwrap();
    assert_ne!(before, e.save().unwrap());
    assert_eq!(pixel(&e, 160, 48), [20, 80, 200, 255]);
    e.command(Command::Cancel).unwrap();
    assert_eq!(before, e.save().unwrap());
    assert!(!e.state()["canUndo"].as_bool().unwrap());
    e.command(Command::Select { rect: None }).unwrap();
    stroke(
        &mut e,
        brush(),
        &[point(240.0, 60.0), point(270.0, 60.0)],
        1,
    );
    assert_eq!(before, e.save().unwrap());
}

#[test]
fn empty_zero_strength_and_stationary_strokes_do_not_allocate_tiles_or_history() {
    for (value, samples) in [
        (brush(), vec![point(100.0, 30.0), point(160.0, 30.0)]),
        (
            Brush {
                opacity: 0.0,
                ..brush()
            },
            vec![point(100.0, 30.0), point(160.0, 30.0)],
        ),
        (brush(), vec![point(100.0, 30.0)]),
        (
            Brush {
                follow_direction: true,
                ..brush()
            },
            vec![point(100.0, 30.0)],
        ),
    ] {
        let mut e = Engine::new(300, 80).unwrap();
        stroke(&mut e, value, &samples, 1);
        assert!(e.document.layers[0].tiles.is_empty());
        assert!(!e.state()["canUndo"].as_bool().unwrap());
        assert_eq!(e.state()["revision"], 0);
    }
    assert!(Brush {
        smudge: true,
        eraser: true,
        ..Brush::default()
    }
    .validate()
    .is_err());
}

#[test]
fn only_the_active_layer_is_sampled_and_locked_layers_reject_smudging() {
    let mut e = colors();
    e.command(Command::AddLayer).unwrap();
    stroke(
        &mut e,
        brush(),
        &[point(100.0, 60.0), point(180.0, 60.0)],
        1,
    );
    assert!(e.document.layers[1].tiles.is_empty());
    e.document.active_mut().locked = true;
    assert!(e.command(Command::Begin { brush: brush() }).is_err());
    e.document.active_mut().locked = false;
    e.document.active_mut().visible = false;
    assert!(e.command(Command::Begin { brush: brush() }).is_err());
}

#[test]
fn exceeding_the_document_budget_can_cancel_without_losing_the_original_pixels() {
    let mut e = Engine::new(4096, 4096).unwrap();
    let solid = Arc::new([160, 40, 80, 255].repeat((TILE_SIZE * TILE_SIZE) as usize));
    e.document.active_mut().tiles.insert((0, 0), solid.clone());
    for id in [2, 3] {
        let mut layer = Layer::new(id, format!("{id}"));
        for y in 0..32 {
            for x in 0..32 {
                if id == 3 && x == 31 && y == 31 {
                    continue;
                }
                layer.tiles.insert((x, y), solid.clone());
            }
        }
        e.document.layers.push(layer);
    }
    e.document.next_id = 4;
    let before = e.save().unwrap();
    e.command(Command::Begin { brush: brush() }).unwrap();
    assert!(e
        .samples(&[point(112.0, 60.0), point(160.0, 60.0)])
        .is_err());
    e.command(Command::Cancel).unwrap();
    assert_eq!(before, e.save().unwrap());
    assert!(!e.state()["canUndo"].as_bool().unwrap());
}

#[test]
fn pressure_texture_and_stabilization_are_independent_of_packet_boundaries() {
    let samples: Vec<_> = (0..80)
        .map(|i| Sample {
            x: 100.0 + i as f32,
            y: 80.0 + (i as f32 * 0.17).sin() * 12.0,
            pressure: 0.25 + i as f32 / 110.0,
        })
        .collect();
    for tip in [BrushTip::Round, BrushTip::Flat] {
        let value = Brush {
            tip,
            aspect: 0.4,
            grain: 0.3,
            angle: 70.0,
            follow_direction: true,
            stabilization: 0.5,
            size_pressure: 0.5,
            opacity_pressure: 0.7,
            ..brush()
        };
        let mut a = colors();
        let before = a.save().unwrap();
        let mut b = colors();
        stroke(&mut a, value, &samples, 1);
        stroke(&mut b, value, &samples, samples.len());
        assert_ne!(before, a.save().unwrap());
        assert_eq!(a.save().unwrap(), b.save().unwrap());
        a.document.validate().unwrap();
    }
}
