use podor_engine::{model::*, Command, Engine};

fn stamp(brush: Brush) -> Engine {
    let mut engine = Engine::new(128, 128).unwrap();
    engine.command(Command::Begin { brush }).unwrap();
    engine
        .samples(&[Sample {
            x: 64.0,
            y: 64.0,
            pressure: 1.0,
        }])
        .unwrap();
    engine.command(Command::End).unwrap();
    engine
}

fn alpha(engine: &Engine, x: u32, y: u32) -> u8 {
    engine.document.layers[0].tiles[&(0, 0)][((y * 128 + x) * 4 + 3) as usize]
}

#[test]
fn flat_tip_aspect_and_rotation_change_the_footprint() {
    let brush = Brush {
        size: 40.0,
        hardness: 1.0,
        tip: BrushTip::Flat,
        aspect: 0.2,
        ..Brush::default()
    };
    let horizontal = stamp(brush);
    let vertical = stamp(Brush {
        angle: 90.0,
        ..brush
    });
    assert!(alpha(&horizontal, 78, 64) > 0);
    assert_eq!(alpha(&horizontal, 64, 78), 0);
    assert_eq!(alpha(&vertical, 78, 64), 0);
    assert!(alpha(&vertical, 64, 78) > 0);
}

#[test]
fn textured_tip_is_repeatable_and_has_real_grain() {
    let brush = Brush {
        size: 48.0,
        hardness: 1.0,
        grain: 1.0,
        ..Brush::default()
    };
    let first = stamp(brush);
    let second = stamp(brush);
    assert_eq!(first.export_png().unwrap(), second.export_png().unwrap());
    let values: Vec<_> = (50..78).map(|x| alpha(&first, x, 64)).collect();
    assert!(values.iter().max().unwrap() - values.iter().min().unwrap() > 100);
}

#[test]
fn invalid_brush_extension_cannot_start_a_stroke() {
    for brush in [
        Brush {
            aspect: 0.0,
            ..Brush::default()
        },
        Brush {
            grain: f32::NAN,
            ..Brush::default()
        },
        Brush {
            spacing: 0.0,
            ..Brush::default()
        },
    ] {
        let mut engine = Engine::new(128, 128).unwrap();
        assert!(engine.command(Command::Begin { brush }).is_err());
        assert_eq!(engine.document.tile_count(), 0);
    }
}

fn stroke(brush: Brush, points: &[Sample], batch: usize) -> Engine {
    let mut engine = Engine::new(128, 128).unwrap();
    engine.command(Command::Begin { brush }).unwrap();
    for points in points.chunks(batch) {
        engine.samples(points).unwrap();
    }
    engine.command(Command::End).unwrap();
    engine
}

fn point(x: f32, y: f32) -> Sample {
    Sample {
        x,
        y,
        pressure: 1.0,
    }
}

#[test]
fn directional_nib_rotates_start_body_and_tail_without_a_sideways_cap() {
    let brush = Brush {
        size: 24.0,
        tip: BrushTip::Flat,
        aspect: 0.25,
        hardness: 1.0,
        spacing: 0.4,
        follow_direction: true,
        ..Brush::default()
    };
    for stabilization in [0.0, 0.5, 1.0] {
        let vertical = stroke(
            Brush {
                stabilization,
                ..brush
            },
            &[point(64.0, 20.0), point(64.0, 96.7), point(64.0, 96.7)],
            1,
        );
        for y in [20, 60, 96] {
            assert_eq!(alpha(&vertical, 74, y), 0, "wrong orientation at {y}");
            assert!(alpha(&vertical, 64, y) > 0);
        }
        assert!(
            alpha(&vertical, 64, 108) > 0,
            "tail did not reach final input"
        );
    }
    let horizontal = stroke(brush, &[point(20.0, 64.0), point(96.7, 64.0)], 2);
    assert_eq!(alpha(&horizontal, 60, 74), 0);
    assert!(alpha(&horizontal, 108, 64) > 0);
    let offset = stroke(
        Brush {
            angle: 90.0,
            ..brush
        },
        &[point(64.0, 20.0), point(64.0, 96.7)],
        2,
    );
    assert!(alpha(&offset, 74, 60) > 0);
    assert_eq!(alpha(&offset, 64, 108), 0);
}

#[test]
fn directional_nib_keeps_dots_batching_history_and_cancel_consistent() {
    let brush = Brush {
        size: 28.0,
        tip: BrushTip::Flat,
        aspect: 0.3,
        angle: 90.0,
        grain: 0.5,
        stabilization: 0.5,
        follow_direction: true,
        ..Brush::default()
    };
    assert_eq!(
        stamp(brush).export_png().unwrap(),
        stamp(Brush {
            follow_direction: false,
            ..brush
        })
        .export_png()
        .unwrap(),
    );
    let points = [
        point(20.0, 20.0),
        point(48.0, 55.0),
        point(48.0, 55.0),
        point(90.0, 65.0),
        point(70.0, 90.0),
    ];
    let mut whole = stroke(brush, &points, points.len());
    let single = stroke(brush, &points, 1);
    let saved = whole.save().unwrap();
    assert_eq!(saved, single.save().unwrap());
    whole.command(Command::Undo).unwrap();
    assert_eq!(whole.document.tile_count(), 0);
    whole.command(Command::Redo).unwrap();
    assert_eq!(saved, whole.save().unwrap());
    whole.command(Command::Begin { brush }).unwrap();
    whole
        .samples(&[point(110.0, 20.0), point(110.0, 100.0)])
        .unwrap();
    whole.command(Command::Cancel).unwrap();
    assert_eq!(saved, whole.save().unwrap());
}
