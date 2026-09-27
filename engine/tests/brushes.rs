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
