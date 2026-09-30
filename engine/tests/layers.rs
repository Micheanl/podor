use podor_engine::{model::*, Command, Engine, ExportOptions};
use std::sync::Arc;

fn fill(engine: &mut Engine, color: [u8; 4]) {
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color,
            tolerance: 0,
        })
        .unwrap();
}

#[test]
fn duplicate_shares_pixels_until_edit_and_undo_restores_layer_identity() {
    let mut engine = Engine::new(24, 24).unwrap();
    fill(&mut engine, [150, 30, 80, 200]);
    engine
        .command(Command::SetBlend {
            id: 1,
            mode: BlendMode::Multiply,
        })
        .unwrap();
    engine
        .command(Command::SetLayer {
            id: 1,
            name: "颜色".into(),
            visible: true,
            opacity: 0.6,
        })
        .unwrap();
    let before = engine.save().unwrap();
    engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
    let copied = engine.save().unwrap();
    let layers = &engine.document.layers;
    assert_eq!(layers.iter().map(|l| l.id).collect::<Vec<_>>(), [1, 2]);
    assert_eq!(engine.document.active, 2);
    assert_eq!(layers[1].name, "颜色 · 2");
    assert_eq!(layers[1].blend, BlendMode::Multiply);
    assert_eq!(layers[1].opacity, 0.6);
    assert!(Arc::ptr_eq(
        &layers[0].raster().unwrap().tiles()[&(0, 0)],
        &layers[1].raster().unwrap().tiles()[&(0, 0)]
    ));
    fill(&mut engine, [0, 255, 0, 255]);
    assert!(!Arc::ptr_eq(
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)],
        &engine.document.layers[1].raster().unwrap().tiles()[&(0, 0)]
    ));
    assert_eq!(
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)][..4],
        &[118, 24, 63, 200]
    );
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), copied);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), copied);
    let mut loaded = Engine::new(1, 1).unwrap();
    loaded.load(&copied).unwrap();
    assert_eq!(loaded.state()["layers"], engine.state()["layers"]);
}

#[test]
fn duplicate_handles_unicode_names_hidden_layers_and_limits_atomically() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine
        .command(Command::SetLayer {
            id: 1,
            name: "绘".repeat(85),
            visible: false,
            opacity: 0.0,
        })
        .unwrap();
    engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
    assert!(engine.document.layers[1].name.len() <= MAX_LAYER_NAME_BYTES);
    assert!(engine.document.layers[1].name.ends_with(" · 2"));
    assert!(!engine.document.layers[1].visible);
    assert_eq!(engine.document.layers[1].opacity, 0.0);
    while engine.document.layers.len() < MAX_LAYERS {
        engine.command(Command::AddLayer).unwrap();
    }
    for id in [1, 999] {
        let before = engine.save().unwrap();
        let state = engine.state();
        assert!(engine.command(Command::DuplicateLayer { id }).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
}

#[test]
fn merge_preserves_transparent_composite_all_modes_hidden_layers_and_history() {
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
        let mut engine = Engine::new(129, 131).unwrap();
        fill(&mut engine, [80, 120, 200, 192]);
        engine.command(Command::AddLayer).unwrap();
        fill(&mut engine, [255, 0, 0, 255]);
        engine
            .command(Command::SetLayer {
                id: 2,
                name: "隐藏底稿".into(),
                visible: false,
                opacity: 1.0,
            })
            .unwrap();
        let hidden = engine.document.layers[1].raster().unwrap().tiles()[&(0, 0)].clone();
        engine.command(Command::AddLayer).unwrap();
        fill(&mut engine, [180, 70, 130, 128]);
        engine.command(Command::SetBlend { id: 3, mode }).unwrap();
        engine
            .command(Command::SetLayer {
                id: 3,
                name: "颜色".into(),
                visible: true,
                opacity: 0.6,
            })
            .unwrap();
        engine.command(Command::AddLayer).unwrap();
        engine
            .command(Command::SetLayer {
                id: 4,
                name: "隐藏参考".into(),
                visible: false,
                opacity: 1.0,
            })
            .unwrap();
        engine
            .command(Command::Select {
                rect: Some(Rect {
                    left: 0,
                    top: 0,
                    right: 1,
                    bottom: 1,
                }),
            })
            .unwrap();
        let before = engine.save().unwrap();
        let expected = engine
            .export_image(ExportOptions {
                transparent: true,
                ..Default::default()
            })
            .unwrap();
        engine.command(Command::MergeVisible).unwrap();
        assert_eq!(
            engine
                .document
                .layers
                .iter()
                .map(|l| l.id)
                .collect::<Vec<_>>(),
            [2, 5, 4]
        );
        assert_eq!(engine.document.active, 5);
        assert_eq!(engine.document.layers[1].blend, BlendMode::Normal);
        assert_eq!(engine.document.layers[1].opacity, 1.0);
        assert!(Arc::ptr_eq(
            &hidden,
            &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
        ));
        assert_eq!(
            engine
                .export_image(ExportOptions {
                    transparent: true,
                    ..Default::default()
                })
                .unwrap(),
            expected
        );
        let merged = engine.save().unwrap();
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), merged);
    }
}

#[test]
fn empty_merge_stays_sparse_and_invalid_merge_does_not_change_state() {
    let mut engine = Engine::new(8192, 2048).unwrap();
    let before = engine.state();
    assert!(engine.command(Command::MergeVisible).is_err());
    assert_eq!(engine.state(), before);
    engine.command(Command::AddLayer).unwrap();
    engine.command(Command::MergeVisible).unwrap();
    assert_eq!(engine.document.layers.len(), 1);
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.document.layers.len(), 2);
}

#[test]
fn pixel_and_undo_budgets_reject_before_changing_document() {
    let mut engine = Engine::new(8192, 2048).unwrap();
    let tile = Arc::new(vec![255; TILE_BYTES]);
    for y in 0..16 {
        for x in 0..64 {
            engine.document.layers[0]
                .raster_mut()
                .unwrap()
                .tiles_mut()
                .insert((x, y), tile.clone());
        }
    }
    engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
    let state = engine.state();
    assert!(engine.command(Command::DuplicateLayer { id: 1 }).is_err());
    assert_eq!(engine.state(), state);
    for pixel in engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .values_mut()
    {
        *pixel = Arc::new(vec![128; TILE_BYTES]);
    }
    let state = engine.state();
    assert!(engine.command(Command::MergeVisible).is_err());
    assert_eq!(engine.state(), state);
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[1].raster().unwrap().tiles()[&(0, 0)]
    ));
}
