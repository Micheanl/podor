use podor_engine::{
    model::{Brush, Sample, TILE_BYTES},
    Command, Engine,
};

fn brush(eraser: bool) -> Brush {
    Brush {
        size: 24.0,
        opacity: 1.0,
        hardness: 0.9,
        color: [35, 94, 235],
        eraser,
        ..Brush::default()
    }
}
fn dot(engine: &mut Engine, x: f32, y: f32, eraser: bool) {
    engine
        .command(Command::Begin {
            brush: brush(eraser),
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x,
            y,
            pressure: 1.0,
        }])
        .unwrap();
    engine.command(Command::End).unwrap();
}
fn pick(engine: &mut Engine, x: u32, y: u32) -> serde_json::Value {
    engine.command(Command::Pick { x, y }).unwrap()["color"].clone()
}

#[test]
fn undo_redo_restores_pixels_without_copying_other_tiles() {
    let mut e = Engine::new(512, 512).unwrap();
    dot(&mut e, 64.0, 64.0, false);
    let color = pick(&mut e, 64, 64);
    assert_eq!(color, serde_json::json!([35, 94, 235]));
    let frame = e.frame();
    assert_eq!(frame.len(), 16 + 8 + TILE_BYTES);
    assert_eq!(e.frame().len(), 16);
    e.command(Command::Undo).unwrap();
    assert_eq!(pick(&mut e, 64, 64), serde_json::json!([255, 255, 255]));
    e.command(Command::Redo).unwrap();
    assert_eq!(pick(&mut e, 64, 64), color);
}

#[test]
fn eraser_only_changes_active_layer() {
    let mut e = Engine::new(256, 256).unwrap();
    dot(&mut e, 64.0, 64.0, false);
    e.command(Command::AddLayer).unwrap();
    dot(&mut e, 64.0, 64.0, true);
    assert_eq!(pick(&mut e, 64, 64), serde_json::json!([35, 94, 235]));
    e.command(Command::SelectLayer { id: 1 }).unwrap();
    dot(&mut e, 64.0, 64.0, true);
    assert_eq!(pick(&mut e, 64, 64), serde_json::json!([255, 255, 255]));
}

#[test]
fn pressure_changes_footprint_and_interpolation_has_no_gaps() {
    let mut e = Engine::new(256, 256).unwrap();
    e.command(Command::Begin {
        brush: brush(false),
        assistant: None,
    })
    .unwrap();
    e.samples(&[
        Sample {
            x: 20.0,
            y: 50.0,
            pressure: 0.2,
        },
        Sample {
            x: 200.0,
            y: 50.0,
            pressure: 1.0,
        },
    ])
    .unwrap();
    e.command(Command::End).unwrap();
    for x in 20..200 {
        assert_ne!(pick(&mut e, x, 50), serde_json::json!([255, 255, 255]));
    }
    assert_eq!(pick(&mut e, 20, 59), serde_json::json!([255, 255, 255]));
    assert_ne!(pick(&mut e, 196, 56), serde_json::json!([255, 255, 255]));
}

#[test]
fn cancel_and_new_branch_preserve_history_semantics() {
    let mut e = Engine::new(256, 256).unwrap();
    dot(&mut e, 40.0, 40.0, false);
    e.command(Command::Begin {
        brush: brush(false),
        assistant: None,
    })
    .unwrap();
    e.samples(&[Sample {
        x: 80.0,
        y: 80.0,
        pressure: 1.0,
    }])
    .unwrap();
    e.command(Command::Cancel).unwrap();
    assert_eq!(pick(&mut e, 80, 80), serde_json::json!([255, 255, 255]));
    e.command(Command::Undo).unwrap();
    dot(&mut e, 100.0, 100.0, false);
    assert_eq!(e.state()["canRedo"], false);
}

#[test]
fn project_round_trip_and_png_dimensions() {
    let mut e = Engine::new(230, 170).unwrap();
    dot(&mut e, 229.0, 169.0, false);
    e.command(Command::AddLayer).unwrap();
    let bytes = e.save().unwrap();
    let mut loaded = Engine::new(1, 1).unwrap();
    loaded.load(&bytes).unwrap();
    assert_eq!(loaded.document.layers.len(), 2);
    assert_eq!(pick(&mut loaded, 229, 169), pick(&mut e, 229, 169));
    let png = loaded.export_png().unwrap();
    let decoder = png::Decoder::new(png.as_slice());
    let reader = decoder.read_info().unwrap();
    assert_eq!((reader.info().width, reader.info().height), (230, 170));
    assert!(loaded.load(b"bad data").is_err());
    assert_eq!(loaded.document.width, 230);
}

#[test]
fn invalid_input_cannot_corrupt_document() {
    assert!(Engine::new(0, 0).is_err());
    assert!(Engine::new(8192, 8192).is_err());
    let mut e = Engine::new(256, 256).unwrap();
    assert!(e.command(Command::SelectLayer { id: 999 }).is_err());
    assert!(e.command(Command::RemoveLayer { id: 1 }).is_err());
    e.command(Command::Begin {
        brush: brush(false),
        assistant: None,
    })
    .unwrap();
    assert!(e
        .samples(&[Sample {
            x: f32::NAN,
            y: 0.0,
            pressure: 1.0
        }])
        .is_err());
    e.command(Command::Cancel).unwrap();
    e.document.validate().unwrap();
}

#[test]
fn layer_visibility_opacity_and_order_are_undoable() {
    let mut e = Engine::new(256, 256).unwrap();
    dot(&mut e, 64.0, 64.0, false);
    e.command(Command::SetLayer {
        id: 1,
        visible: false,
        opacity: 1.0,
        name: "底色".into(),
    })
    .unwrap();
    assert_eq!(pick(&mut e, 64, 64), serde_json::json!([255, 255, 255]));
    e.command(Command::Undo).unwrap();
    assert_eq!(pick(&mut e, 64, 64), serde_json::json!([35, 94, 235]));
    e.command(Command::AddLayer).unwrap();
    e.command(Command::MoveLayer {
        id: 2,
        direction: -1,
    })
    .unwrap();
    assert_eq!(e.document.layers[0].id, 2);
    e.command(Command::Undo).unwrap();
    assert_eq!(e.document.layers[0].id, 1);
}

#[test]
fn batching_does_not_change_stroke_pixels() {
    let points: Vec<_> = (0..80)
        .map(|i| Sample {
            x: 20.0 + i as f32 * 2.5,
            y: 80.0,
            pressure: 0.3 + i as f32 * 0.008,
        })
        .collect();
    let mut batch = Engine::new(256, 256).unwrap();
    let mut single = Engine::new(256, 256).unwrap();
    for engine in [&mut batch, &mut single] {
        engine
            .command(Command::Begin {
                brush: brush(false),
                assistant: None,
            })
            .unwrap();
    }
    batch.samples(&points).unwrap();
    for point in &points {
        single.samples(&[*point]).unwrap();
    }
    for engine in [&mut batch, &mut single] {
        engine.command(Command::End).unwrap();
    }
    assert_eq!(batch.export_png().unwrap(), single.export_png().unwrap());
}

#[test]
fn history_has_a_fixed_entry_limit() {
    let mut e = Engine::new(128, 128).unwrap();
    for index in 0..90 {
        dot(&mut e, 10.0 + index as f32, 64.0, false);
    }
    let mut undos = 0;
    while e.state()["canUndo"] == true {
        e.command(Command::Undo).unwrap();
        undos += 1;
    }
    assert_eq!(undos, podor_engine::model::MAX_HISTORY_ENTRIES);
}
