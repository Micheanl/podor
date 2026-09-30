use podor_engine::{model::*, Command, Engine};

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    let layer = &engine.document.layers[0];
    layer
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| {
            let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[offset..offset + 4].try_into().unwrap()
        })
}

fn point(x: f32, y: f32) -> Sample {
    Sample {
        x,
        y,
        pressure: 1.0,
    }
}

fn stroke(engine: &mut Engine, brush: Brush, points: &[Sample], batch: usize) {
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    for points in points.chunks(batch) {
        engine.samples(points).unwrap();
    }
    engine.command(Command::End).unwrap();
}

fn brush(mode: SymmetryMode) -> Brush {
    Brush {
        size: 30.0,
        opacity: 0.6,
        color: [140, 30, 60],
        symmetry: Symmetry {
            mode,
            ..Default::default()
        },
        ..Default::default()
    }
}

#[test]
fn mirrors_flat_rotated_and_grainy_tips_with_pressure_and_stabilization() {
    let points: Vec<_> = (0..40)
        .map(|i| Sample {
            x: 21.0 + i as f32 * 1.1,
            y: 34.0 + (i as f32 * 0.13).sin() * 14.0,
            pressure: 0.2 + i as f32 * 0.02,
        })
        .collect();
    for mode in [
        SymmetryMode::Vertical,
        SymmetryMode::Horizontal,
        SymmetryMode::Quadrant,
    ] {
        let mut engine = Engine::new(257, 259).unwrap();
        stroke(
            &mut engine,
            Brush {
                tip: BrushTip::Flat,
                aspect: 0.3,
                angle: 32.0,
                grain: 0.75,
                follow_direction: true,
                stabilization: 0.3,
                opacity_pressure: 0.8,
                ..brush(mode)
            },
            &points,
            7,
        );
        assert!(pixel(&engine, 35, 46)[3] > 0);
        for y in 0..259 {
            for x in 0..257 {
                let a = pixel(&engine, x, y);
                if mode != SymmetryMode::Horizontal {
                    assert!(
                        a.iter()
                            .zip(pixel(&engine, 256 - x, y))
                            .all(|(a, b)| a.abs_diff(b) <= 1),
                        "x {x}, y {y}"
                    );
                }
                if mode != SymmetryMode::Vertical {
                    assert!(
                        a.iter()
                            .zip(pixel(&engine, x, 258 - y))
                            .all(|(a, b)| a.abs_diff(b) <= 1),
                        "x {x}, y {y}"
                    );
                }
            }
        }
    }
}

#[test]
fn overlapping_copies_blend_once_and_leave_no_seam_on_axes() {
    let mut single = Engine::new(128, 128).unwrap();
    let mut mirrored = Engine::new(128, 128).unwrap();
    stroke(
        &mut single,
        brush(SymmetryMode::Off),
        &[point(64.0, 64.0)],
        1,
    );
    stroke(
        &mut mirrored,
        brush(SymmetryMode::Quadrant),
        &[point(64.0, 64.0)],
        1,
    );
    assert_eq!(single.save().unwrap(), mirrored.save().unwrap());
    for eraser in [false, true] {
        let mut left = Engine::new(128, 128).unwrap();
        let mut right = Engine::new(128, 128).unwrap();
        let mut both = Engine::new(128, 128).unwrap();
        if eraser {
            for engine in [&mut left, &mut right, &mut both] {
                engine
                    .command(Command::Fill {
                        contiguous: true,
                        merged: false,
                        x: 0,
                        y: 0,
                        color: [90, 40, 150, 200],
                        tolerance: 0,
                    })
                    .unwrap();
            }
        }
        stroke(
            &mut left,
            Brush {
                eraser,
                ..brush(SymmetryMode::Off)
            },
            &[point(60.5, 64.0)],
            1,
        );
        stroke(
            &mut right,
            Brush {
                eraser,
                ..brush(SymmetryMode::Off)
            },
            &[point(67.5, 64.0)],
            1,
        );
        stroke(
            &mut both,
            Brush {
                eraser,
                ..brush(SymmetryMode::Vertical)
            },
            &[point(60.5, 64.0)],
            1,
        );
        for y in 0..128 {
            for x in 0..128 {
                let a = pixel(&left, x, y)[3];
                let b = pixel(&right, x, y)[3];
                assert_eq!(
                    pixel(&both, x, y)[3],
                    if eraser { a.min(b) } else { a.max(b) }
                );
            }
        }
    }
}

#[test]
fn all_copies_share_one_history_entry_and_packet_boundaries_do_not_matter() {
    let points: Vec<_> = (0..70)
        .map(|i| point(30.0 + i as f32, 35.0 + i as f32 * 0.4))
        .collect();
    let mut first = Engine::new(512, 512).unwrap();
    let mut second = Engine::new(512, 512).unwrap();
    let before = first.save().unwrap();
    stroke(&mut first, brush(SymmetryMode::Quadrant), &points, 1);
    stroke(&mut second, brush(SymmetryMode::Quadrant), &points, 13);
    let after = first.save().unwrap();
    assert_eq!(after, second.save().unwrap());
    first.command(Command::Undo).unwrap();
    assert_eq!(first.save().unwrap(), before);
    first.command(Command::Redo).unwrap();
    assert_eq!(first.save().unwrap(), after);
    first
        .command(Command::Begin {
            brush: brush(SymmetryMode::Quadrant),
            assistant: None,
        })
        .unwrap();
    first.samples(&[point(220.0, 240.0)]).unwrap();
    first.command(Command::Cancel).unwrap();
    assert_eq!(first.save().unwrap(), after);
}

#[test]
fn shifted_axes_snap_consistently_and_allow_the_source_outside_the_canvas() {
    let mut engine = Engine::new(257, 129).unwrap();
    let brush = Brush {
        size: 4.0,
        hardness: 1.0,
        symmetry: Symmetry {
            mode: SymmetryMode::Quadrant,
            x: 0.25,
            y: 0.25,
        },
        ..Default::default()
    };
    stroke(&mut engine, brush, &[point(-5.5, 12.5)], 1);
    assert!(pixel(&engine, 134, 12)[3] > 0);
    assert!(pixel(&engine, 134, 52)[3] > 0);
    assert_eq!(pixel(&engine, 129, 12), [0; 4]);
}

#[test]
fn selection_and_alpha_lock_limit_all_copies_without_changing_other_layers() {
    let mut engine = Engine::new(256, 256).unwrap();
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
    engine.document.layers[0].alpha_locked = true;
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 128,
                top: 0,
                right: 256,
                bottom: 128,
            }),
        })
        .unwrap();
    let before = engine.save().unwrap();
    stroke(
        &mut engine,
        brush(SymmetryMode::Quadrant),
        &[point(30.5, 30.5)],
        1,
    );
    assert_eq!(pixel(&engine, 30, 30), [25, 50, 100, 128]);
    assert_eq!(pixel(&engine, 225, 225), [25, 50, 100, 128]);
    assert_eq!(pixel(&engine, 225, 30)[3], 128);
    assert_ne!(pixel(&engine, 225, 30), [25, 50, 100, 128]);
    engine.command(Command::Undo).unwrap();
    assert_eq!(before, engine.save().unwrap());
}

#[test]
fn legacy_brush_defaults_and_invalid_axes_leave_the_document_intact() {
    let brush: Brush = serde_json::from_str(
        r#"{"size":12,"opacity":1,"hardness":1,"color":[0,0,0],"eraser":false}"#,
    )
    .unwrap();
    assert!(brush.symmetry.mode == SymmetryMode::Off);
    let mut engine = Engine::new(16, 16).unwrap();
    let before = engine.save().unwrap();
    for value in [f32::NAN, f32::INFINITY, -0.1, 1.1] {
        for symmetry in [
            Symmetry {
                x: value,
                ..Default::default()
            },
            Symmetry {
                y: value,
                ..Default::default()
            },
        ] {
            assert!(engine
                .command(Command::Begin {
                    brush: Brush { symmetry, ..brush },
                    assistant: None
                })
                .is_err());
            assert_eq!(before, engine.save().unwrap());
        }
    }
    assert!(engine
        .command(Command::Begin {
            brush: Brush {
                smudge: true,
                ..self::brush(SymmetryMode::Vertical)
            },
            assistant: None
        })
        .is_err());
}
