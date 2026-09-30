use podor_engine::{model::*, Command, Engine};
use std::{collections::BTreeMap, sync::Arc};

fn artwork() -> Engine {
    let mut engine = Engine::new(384, 256).unwrap();
    for x in 124..136 {
        for y in 50..78 {
            let alpha = [0, 1, 32, 128, 254, 255][(x as usize - 124) % 6];
            let tile = engine.document.layers[0]
                .raster_mut()
                .unwrap()
                .tiles_mut()
                .entry((x / TILE_SIZE, 0))
                .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
            let i = ((y * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            Arc::make_mut(tile)[i..i + 4].copy_from_slice(&[alpha, 0, 0, alpha]);
        }
    }
    engine
}

fn protect(engine: &mut Engine, alpha_locked: bool, locked: bool) {
    engine
        .command(Command::SetProtection {
            id: 1,
            alpha_locked: Some(alpha_locked),
            locked: Some(locked),
        })
        .unwrap();
}

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    let tile =
        &engine.document.layers[0].raster().unwrap().tiles()[&(x / TILE_SIZE, y / TILE_SIZE)];
    let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tile[i..i + 4].try_into().unwrap()
}

fn mask(engine: &Engine) -> BTreeMap<TileKey, Vec<u8>> {
    engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .iter()
        .map(|(key, tile)| (*key, tile.as_chunks::<4>().0.iter().map(|p| p[3]).collect()))
        .collect()
}

#[test]
fn alpha_locked_brush_preserves_soft_edges_and_does_not_allocate_empty_tiles() {
    for directional in [false, true] {
        let mut engine = artwork();
        let before = mask(&engine);
        protect(&mut engine, true, false);
        engine
            .command(Command::Begin {
                brush: Brush {
                    size: 128.0,
                    hardness: 1.0,
                    color: [0, 0, 255],
                    follow_direction: directional,
                    stabilization: 0.5,
                    ..Brush::default()
                },
                assistant: None,
            })
            .unwrap();
        engine
            .samples(&[
                Sample {
                    x: 32.0,
                    y: 64.0,
                    pressure: 1.0,
                },
                Sample {
                    x: 352.0,
                    y: 64.0,
                    pressure: 1.0,
                },
            ])
            .unwrap();
        engine.command(Command::End).unwrap();
        assert_eq!(mask(&engine), before);
        for x in 124..136 {
            let p = pixel(&engine, x, 64);
            assert_eq!(p, [0, 0, p[3], p[3]]);
        }
        let painted = engine.save().unwrap();
        engine.command(Command::Undo).unwrap();
        assert_eq!(mask(&engine), before);
        assert!(pixel(&engine, 129, 64)[0] > 0);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), painted);
    }
}

#[test]
fn locked_fill_blends_opacity_and_blur_keeps_alpha_and_selection() {
    let mut engine = artwork();
    let before = mask(&engine);
    protect(&mut engine, true, false);
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 126,
                top: 54,
                right: 136,
                bottom: 74,
            }),
        })
        .unwrap();
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 128,
            y: 64,
            color: [0, 0, 255, 128],
            tolerance: 255,
        })
        .unwrap();
    assert_eq!(mask(&engine), before);
    for x in 126..130 {
        let p = pixel(&engine, x, 64);
        assert!(p[0].abs_diff((u32::from(p[3]) * 127 / 255) as u8) <= 1);
        assert!(p[2].abs_diff((u32::from(p[3]) * 128 / 255) as u8) <= 1);
    }
    assert_eq!(pixel(&engine, 129, 52), [255, 0, 0, 255]);
    engine.command(Command::Blur { sigma: 4.0 }).unwrap();
    assert_eq!(mask(&engine), before);
    assert_eq!(pixel(&engine, 129, 52), [255, 0, 0, 255]);
    assert!(pixel(&engine, 134, 64)[2] > 0);
    let tone = serde_json::from_value(serde_json::json!({"type":"tone", "settings":{
        "brightness":0.2, "contrast":0.1, "saturation":-0.5
    }}))
    .unwrap();
    engine.command(tone).unwrap();
    assert_eq!(mask(&engine), before);
    for tile in engine.document.layers[0].raster().unwrap().tiles().values() {
        assert!(tile
            .as_chunks::<4>()
            .0
            .iter()
            .all(|p| p[..3].iter().all(|c| *c <= p[3])));
    }
    let unchanged = engine.export_png().unwrap();
    engine.command(Command::Select { rect: None }).unwrap();
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color: [20, 80, 100, 255],
            tolerance: 255,
        })
        .unwrap();
    assert_eq!(engine.export_png().unwrap(), unchanged);
}

#[test]
fn layer_lock_rejects_destructive_edits_and_keeps_state_atomic() {
    let mut engine = artwork();
    engine.command(Command::AddLayer).unwrap();
    engine.command(Command::SelectLayer { id: 1 }).unwrap();
    protect(&mut engine, false, true);
    let before = engine.save().unwrap();
    let state = engine.state();
    for command in [
        Command::Begin {
            brush: Brush::default(),
            assistant: None,
        },
        Command::Fill {
            contiguous: true,
            merged: false,
            x: 128,
            y: 64,
            color: [0; 4],
            tolerance: 0,
        },
        Command::Blur { sigma: 2.0 },
        Command::Clear,
        Command::RemoveLayer { id: 1 },
        Command::MergeVisible,
        serde_json::from_value(serde_json::json!({"type":"tone", "settings":{
            "brightness":0.2, "contrast":0.0, "saturation":0.0
        }}))
        .unwrap(),
    ] {
        assert!(engine.command(command).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
    protect(&mut engine, true, false);
    assert!(engine.command(Command::Clear).is_err());
    assert!(engine
        .command(Command::Begin {
            brush: Brush {
                eraser: true,
                ..Brush::default()
            },
            assistant: None
        })
        .is_err());
    protect(&mut engine, false, false);
    engine.command(Command::Clear).unwrap();
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
}

#[test]
fn protection_persists_and_toggling_it_does_not_transfer_canvas_pixels() {
    let mut engine = artwork();
    engine.frame();
    let original = engine.document.layers[0].raster().unwrap().tiles().clone();
    protect(&mut engine, true, true);
    assert_eq!(engine.frame().len(), 16);
    engine
        .command(
            serde_json::from_value(
                serde_json::json!({"type":"set_protection", "id":1, "locked":false}),
            )
            .unwrap(),
        )
        .unwrap();
    assert!(engine.document.layers[0].alpha_locked);
    assert!(!engine.document.layers[0].locked);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.frame().len(), 16);
    for (key, tile) in &original {
        assert!(Arc::ptr_eq(
            tile,
            &engine.document.layers[0].raster().unwrap().tiles()[key]
        ));
    }
    let saved = engine.save().unwrap();
    assert!(saved.starts_with(b"PODOR\x0c"));
    let mut restored = Engine::new(1, 1).unwrap();
    restored.load(&saved).unwrap();
    assert_eq!(restored.state()["layers"][0]["alphaLocked"], true);
    assert_eq!(restored.state()["layers"][0]["locked"], true);
    assert_eq!(restored.export_png().unwrap(), engine.export_png().unwrap());
    engine.command(Command::Undo).unwrap();
    assert!(!engine.document.layers[0].locked && !engine.document.layers[0].alpha_locked);
    assert_eq!(engine.frame().len(), 16);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.frame().len(), 16);
    assert_eq!(engine.save().unwrap(), saved);
    engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
    assert!(engine.document.layers[1].locked && engine.document.layers[1].alpha_locked);
}

#[test]
fn released_v1_project_migrates_without_changing_pixels_or_layer_properties() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine
        .load(include_bytes!("fixtures/project-v1.bin"))
        .unwrap();
    assert_eq!(
        (
            engine.document.width,
            engine.document.height,
            engine.document.active,
            engine.document.next_id
        ),
        (128, 96, 2, 3)
    );
    assert_eq!(engine.document.layers[0].name, "Base");
    assert_eq!(engine.document.layers[0].opacity, 0.75);
    assert!(!engine.document.layers[1].visible);
    assert_eq!(engine.document.layers[1].blend, BlendMode::Multiply);
    assert!(engine
        .document
        .layers
        .iter()
        .all(|layer| !layer.locked && !layer.alpha_locked));
    assert_eq!(
        engine.export_png().unwrap(),
        include_bytes!("fixtures/project-v1.png")
    );
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
    assert_eq!(
        engine.export_png().unwrap(),
        include_bytes!("fixtures/project-v1.png")
    );
    let mut future = saved.clone();
    future[5] = 13;
    assert!(engine.load(&future).unwrap_err().contains("版本"));
    assert_eq!(engine.save().unwrap(), saved);
    assert!(engine.load(&saved[..saved.len() / 2]).is_err());
    assert_eq!(engine.save().unwrap(), saved);
}
