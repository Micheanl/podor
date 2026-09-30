use podor_engine::{model::*, Command, Engine};
use std::sync::Arc;

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    let layer = &engine.document.layers[0];
    let Some(tile) = layer
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
    else {
        return [0; 4];
    };
    let index = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tile[index..index + 4].try_into().unwrap()
}

fn patterned() -> Engine {
    let mut engine = Engine::new(259, 193).unwrap();
    for y in 0..193 {
        for x in 0..259 {
            if (x * 31 + y * 17) % 11 < 3 {
                let tile = engine.document.layers[0]
                    .raster_mut()
                    .unwrap()
                    .tiles_mut()
                    .entry((x / TILE_SIZE, y / TILE_SIZE))
                    .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
                let index = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                let alpha = ((x + y) % 192 + 32) as u8;
                Arc::make_mut(tile)[index..index + 4].copy_from_slice(&[
                    alpha / 2,
                    alpha / 3,
                    alpha,
                    alpha,
                ]);
            }
        }
    }
    engine
}

#[test]
fn translation_crosses_tiles_clips_and_round_trips_through_history_and_storage() {
    for (dx, dy) in [
        (1, 1),
        (-1, -1),
        (127, -128),
        (-128, 127),
        (258, 192),
        (-259, 0),
        (0, 193),
    ] {
        let original = patterned();
        let before = original.save().unwrap();
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&before).unwrap();
        engine.frame();
        let state = engine
            .command(Command::TranslateLayer {
                mask_id: None,
                id: 1,
                dx,
                dy,
            })
            .unwrap();
        assert!(state["canUndo"].as_bool().unwrap());
        for y in 0..193 {
            for x in 0..259 {
                let sx = x as i32 - dx;
                let sy = y as i32 - dy;
                let expected = if (0..259).contains(&sx) && (0..193).contains(&sy) {
                    pixel(&original, sx as u32, sy as u32)
                } else {
                    [0; 4]
                };
                assert_eq!(
                    pixel(&engine, x, y),
                    expected,
                    "offset {dx},{dy} pixel {x},{y}"
                );
            }
        }
        let moved = engine.save().unwrap();
        assert_eq!(
            u32::from_le_bytes(engine.frame()[12..16].try_into().unwrap()),
            6
        );
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), moved);
        let mut loaded = Engine::new(1, 1).unwrap();
        loaded.load(&moved).unwrap();
        assert_eq!(loaded.save().unwrap(), moved);
    }
}

#[test]
fn aligned_moves_reuse_tiles_and_memory_limit_rejection_keeps_original_layer() {
    let mut engine = Engine::new(2048, 2048).unwrap();
    let pixels = Arc::new(vec![255; TILE_BYTES]);
    engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((1, 1), pixels.clone());
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 128,
            dy: -128,
        })
        .unwrap();
    assert!(Arc::ptr_eq(
        &pixels,
        &engine.document.layers[0].raster().unwrap().tiles()[&(2, 0)]
    ));
    engine.command(Command::Undo).unwrap();
    let mut remaining = MAX_DOCUMENT_BYTES / TILE_BYTES - 1;
    for id in 2..=9 {
        let mut layer = Layer::new(id, format!("Layer {id}"));
        for tile in 0..256.min(remaining) {
            layer
                .raster_mut()
                .unwrap()
                .tiles_mut()
                .insert((tile as u32 % 16, tile as u32 / 16), pixels.clone());
        }
        remaining -= layer.raster().unwrap().tiles().len();
        engine.document.layers.push(layer);
    }
    engine.document.next_id = 10;
    engine.document.validate().unwrap();
    assert_eq!(remaining, 0);
    let state = engine.state();
    assert!(engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 1,
            dy: 1
        })
        .is_err());
    assert_eq!(engine.state(), state);
    assert_eq!(engine.document.layers[0].raster().unwrap().tiles().len(), 1);
    assert!(Arc::ptr_eq(
        &pixels,
        &engine.document.layers[0].raster().unwrap().tiles()[&(1, 1)]
    ));
}

#[test]
fn translation_is_atomic_for_locked_selected_and_out_of_range_requests() {
    let mut engine = patterned();
    for (locked, alpha_locked) in [(true, false), (false, true), (false, false)] {
        engine.document.layers[0].locked = locked;
        engine.document.layers[0].alpha_locked = alpha_locked;
        let before = engine.save().unwrap();
        let state = engine.state();
        let shift = if locked || alpha_locked { 1 } else { i32::MIN };
        assert!(engine
            .command(Command::TranslateLayer {
                mask_id: None,
                id: 1,
                dx: shift,
                dy: 0
            })
            .is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 0,
                top: 0,
                right: 10,
                bottom: 10,
            }),
        })
        .unwrap();
    engine.document.layers[0].locked = true;
    let before = engine.save().unwrap();
    assert!(engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 1,
            dy: 1
        })
        .is_err());
    assert_eq!(engine.save().unwrap(), before);
    engine.document.layers[0].locked = false;
    let before = engine.save().unwrap();
    engine.command(Command::Select { rect: None }).unwrap();
    let state = engine.state();
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 0,
            dy: 0,
        })
        .unwrap();
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), before);
    let mut empty = Engine::new(8192, 2048).unwrap();
    empty
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 16,
            dy: -32,
        })
        .unwrap();
    assert_eq!(empty.state()["revision"], 0);
    assert!(empty.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
}

#[test]
fn translation_keeps_other_layers_and_streams_uncomposited_pixels_once() {
    let mut engine = patterned();
    engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
    engine.document.layers[0].blend = BlendMode::Multiply;
    engine.document.layers[0].opacity = 0.5;
    let unchanged = engine.document.layers[1].raster().unwrap().tiles().clone();
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 17,
            dy: 19,
        })
        .unwrap();
    assert_eq!(engine.document.layers[0].blend, BlendMode::Multiply);
    assert_eq!(engine.document.layers[0].opacity, 0.5);
    for (key, tile) in &unchanged {
        assert!(Arc::ptr_eq(
            tile,
            &engine.document.layers[1].raster().unwrap().tiles()[key]
        ));
    }
    let frame = engine.layer_frame().unwrap();
    let word = |offset| u32::from_le_bytes(frame[offset..offset + 4].try_into().unwrap());
    assert_eq!(
        [word(0), word(4), word(8), word(12)],
        [259, 193, TILE_SIZE, 2]
    );
    let mut offset = 16;
    for layer in &engine.document.layers {
        assert_eq!(word(offset), layer.id);
        assert_eq!(
            word(offset + 4) as usize,
            layer.raster().unwrap().tiles().len()
        );
        offset += 8;
        for (&(x, y), tile) in layer.raster().unwrap().tiles() {
            assert_eq!([word(offset), word(offset + 4)], [x, y]);
            assert_eq!(&frame[offset + 8..offset + 8 + TILE_BYTES], tile.as_slice());
            offset += 8 + TILE_BYTES;
        }
    }
    assert_eq!(offset, frame.len());
    engine.document.layers[1].visible = false;
    assert_eq!(
        u32::from_le_bytes(engine.layer_frame().unwrap()[12..16].try_into().unwrap()),
        1
    );
}
#[test]
fn selected_pixels_move_without_moving_the_rest_and_undo_restores_all_pixels() {
    let mut engine = Engine::new(300, 200).unwrap();
    let mut tile = vec![0; TILE_BYTES];
    for (x, y, color) in [
        (10u32, 10u32, [210, 50, 80, 255]),
        (70, 10, [10, 80, 100, 255]),
    ] {
        let i = ((y * TILE_SIZE + x) * 4) as usize;
        tile[i..i + 4].copy_from_slice(&color);
    }
    engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new(tile));
    let before = engine.save().unwrap();
    engine
        .command(
            serde_json::from_str(
                r#"{"type":"select","rect":{"left":5,"top":5,"right":20,"bottom":20}}"#,
            )
            .unwrap(),
        )
        .unwrap();
    let revision = engine.state()["revision"].as_u64().unwrap();
    let preview = engine.selection_move_frame().unwrap();
    assert_eq!(u32::from_le_bytes(preview[12..16].try_into().unwrap()), 2);
    assert_eq!(engine.save().unwrap(), before);
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 128,
            dy: 0,
        })
        .unwrap();
    assert_eq!(pixel(&engine, 10, 10), [0; 4]);
    assert_eq!(pixel(&engine, 138, 10), [210, 50, 80, 255]);
    assert_eq!(pixel(&engine, 70, 10), [10, 80, 100, 255]);
    assert_eq!(engine.state()["revision"].as_u64().unwrap(), revision + 1);
    assert!(engine.state()["selection"].is_null());
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
}
