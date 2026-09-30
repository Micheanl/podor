use podor_engine::{model::*, Command, Engine};

fn point(x: f32, pressure: f32) -> Sample {
    Sample {
        x,
        y: 64.5,
        pressure,
    }
}

fn paint(engine: &mut Engine, brush: Brush, points: &[Sample], batch: usize) {
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    for chunk in points.chunks(batch) {
        engine.samples(chunk).unwrap();
    }
    engine.command(Command::End).unwrap();
}

fn dot(brush: Brush, pressure: f32) -> Engine {
    let mut engine = Engine::new(128, 128).unwrap();
    paint(&mut engine, brush, &[point(64.5, pressure)], 1);
    engine
}

fn alpha(engine: &Engine, x: u32, y: u32) -> u8 {
    engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .get(&(0, 0))
        .map_or(0, |tile| tile[((y * 128 + x) * 4 + 3) as usize])
}

#[test]
fn pressure_curve_changes_the_footprint_and_opacity_independently() {
    for (curve, width, opacity) in [(-1.0, 17, 64), (0.0, 33, 128), (1.0, 49, 191)] {
        let brush = Brush {
            size: 64.0,
            hardness: 1.0,
            pressure_curve: curve,
            ..Brush::default()
        };
        let size = dot(brush, 0.5);
        assert_eq!((0..128).filter(|&x| alpha(&size, x, 64) > 0).count(), width);
        assert_eq!(alpha(&size, 64, 64), 255);
        let opacity_only = dot(
            Brush {
                size_pressure: 0.0,
                opacity_pressure: 1.0,
                ..brush
            },
            0.5,
        );
        assert_eq!(alpha(&opacity_only, 64, 64), opacity);
        assert_eq!(
            (0..128)
                .filter(|&x| alpha(&opacity_only, x, 64) > 0)
                .count(),
            65
        );
    }
    let partial = dot(
        Brush {
            size: 64.0,
            hardness: 1.0,
            size_pressure: 0.5,
            opacity_pressure: 0.5,
            ..Brush::default()
        },
        0.5,
    );
    assert_eq!(alpha(&partial, 64, 64), 191);
    assert_eq!((0..128).filter(|&x| alpha(&partial, x, 64) > 0).count(), 49);
}

#[test]
fn disabling_pressure_keeps_spacing_and_pixels_independent_of_input() {
    let mut reference = Engine::new(128, 128).unwrap();
    let brush = Brush {
        size: 12.0,
        opacity: 0.2,
        size_pressure: 0.0,
        spacing: 0.5,
        ..Brush::default()
    };
    let points = (0..101)
        .map(|i| point(14.5 + i as f32, 1.0))
        .collect::<Vec<_>>();
    paint(&mut reference, brush, &points, 8);
    for curve in [-1.0, 0.0, 1.0] {
        let mut varying = Engine::new(128, 128).unwrap();
        let varying_points = points
            .iter()
            .enumerate()
            .map(|(i, p)| Sample {
                pressure: (i as f32 * 0.2).sin() * 0.5 + 0.5,
                ..*p
            })
            .collect::<Vec<_>>();
        paint(
            &mut varying,
            Brush {
                pressure_curve: curve,
                ..brush
            },
            &varying_points,
            1,
        );
        assert_eq!(reference.save().unwrap(), varying.save().unwrap());
    }
}

#[test]
fn legacy_brushes_keep_linear_size_and_constant_opacity() {
    let legacy: Brush = serde_json::from_str(
        r#"{"size":64,"opacity":0.5,"hardness":1,"color":[0,0,0],"eraser":false}"#,
    )
    .unwrap();
    let expected = Brush {
        size: 64.0,
        opacity: 0.5,
        hardness: 1.0,
        ..Brush::default()
    };
    for (pressure, width) in [
        (-1.0, 3),
        (0.0, 3),
        (0.25, 17),
        (0.5, 33),
        (1.0, 65),
        (2.0, 65),
    ] {
        let painted = dot(legacy, pressure);
        assert_eq!(
            painted.save().unwrap(),
            dot(expected, pressure).save().unwrap()
        );
        assert_eq!(alpha(&painted, 64, 64), 128);
        assert_eq!(
            (0..128).filter(|&x| alpha(&painted, x, 64) > 0).count(),
            width
        );
    }
    for curve in [-1.0, 0.0, 1.0] {
        let brush = Brush {
            pressure_curve: curve,
            opacity_pressure: 1.0,
            ..expected
        };
        assert_eq!(dot(brush, -1.0).document.tile_count(), 0);
        assert_eq!(dot(brush, 0.0).document.tile_count(), 0);
        assert_eq!(
            dot(brush, 2.0).save().unwrap(),
            dot(expected, 1.0).save().unwrap()
        );
    }
}

#[test]
fn invalid_pressure_parameters_do_not_change_the_document_or_start_a_stroke() {
    for value in [-1.01, 1.01, f32::NAN, f32::INFINITY] {
        for brush in [
            Brush {
                pressure_curve: value,
                ..Brush::default()
            },
            Brush {
                size_pressure: value,
                ..Brush::default()
            },
            Brush {
                opacity_pressure: value,
                ..Brush::default()
            },
        ] {
            let mut engine = Engine::new(128, 128).unwrap();
            let before = engine.save().unwrap();
            assert!(engine
                .command(Command::Begin {
                    brush,
                    assistant: None
                })
                .is_err());
            assert_eq!(before, engine.save().unwrap());
            engine
                .command(Command::Begin {
                    brush: Brush::default(),
                    assistant: None,
                })
                .unwrap();
        }
    }
    for brush in [
        Brush {
            size_pressure: -0.01,
            ..Brush::default()
        },
        Brush {
            opacity_pressure: -0.01,
            ..Brush::default()
        },
    ] {
        assert!(brush.validate().is_err());
    }
}

#[test]
fn pressure_strokes_remain_continuous_and_preserve_batching_history_and_cancel() {
    let points = (0..101)
        .map(|i| {
            point(
                14.5 + i as f32,
                0.1 + 0.9 * (i as f32 * std::f32::consts::PI / 100.0).sin(),
            )
        })
        .collect::<Vec<_>>();
    for curve in [-1.0, 0.0, 1.0] {
        for tip in [BrushTip::Round, BrushTip::Flat] {
            let brush = Brush {
                size: 32.0,
                hardness: 1.0,
                pressure_curve: curve,
                tip,
                follow_direction: true,
                stabilization: 0.5,
                ..Brush::default()
            };
            let mut whole = Engine::new(128, 128).unwrap();
            let before = whole.save().unwrap();
            paint(&mut whole, brush, &points, points.len());
            for x in 15..115 {
                assert!(alpha(&whole, x, 64) > 0, "gap at {x}");
            }
            let after = whole.save().unwrap();
            let mut single = Engine::new(128, 128).unwrap();
            paint(&mut single, brush, &points, 1);
            assert_eq!(after, single.save().unwrap());
            whole.command(Command::Undo).unwrap();
            assert_eq!(before, whole.save().unwrap());
            whole.command(Command::Redo).unwrap();
            assert_eq!(after, whole.save().unwrap());
            whole
                .command(Command::Begin {
                    brush,
                    assistant: None,
                })
                .unwrap();
            whole
                .samples(&[point(32.5, 0.4), point(96.5, 0.9)])
                .unwrap();
            whole.command(Command::Cancel).unwrap();
            assert_eq!(after, whole.save().unwrap());
        }
    }
}

#[test]
fn pressure_opacity_applies_to_eraser_and_alpha_locked_painting() {
    for locked in [false, true] {
        let mut engine = Engine::new(128, 128).unwrap();
        engine
            .command(Command::Fill {
                contiguous: true,
                merged: false,
                x: 0,
                y: 0,
                color: [0, 0, 0, 128],
                tolerance: 0,
            })
            .unwrap();
        if locked {
            engine
                .command(Command::SetProtection {
                    id: 1,
                    alpha_locked: Some(true),
                    locked: None,
                })
                .unwrap();
        }
        let brush = Brush {
            size: 32.0,
            hardness: 1.0,
            color: [255, 255, 255],
            size_pressure: 0.0,
            opacity_pressure: 1.0,
            eraser: !locked,
            ..Brush::default()
        };
        paint(&mut engine, brush, &[point(64.5, 0.25)], 1);
        assert_eq!(alpha(&engine, 64, 64), if locked { 128 } else { 95 });
        assert_eq!(alpha(&engine, 100, 64), 128);
        if locked {
            let tile = &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)];
            assert_eq!(tile[((64 * 128 + 64) * 4) as usize], 32);
        }
    }
}
