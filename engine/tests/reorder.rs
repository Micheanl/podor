use podor_engine::{model::*, Command, Engine};
use std::sync::Arc;

fn reorder(engine: &mut Engine, id: u32, index: usize) {
    engine
        .command(Command::ReorderLayer {
            id,
            index,
            revision: engine.state()["revision"].as_u64().unwrap(),
        })
        .unwrap();
}

#[test]
fn reorder_inserts_without_swapping_other_layers_and_preserves_pixels_and_history() {
    let mut engine = Engine::new(129, 129).unwrap();
    for id in 1..=4 {
        if id > 1 {
            engine.command(Command::AddLayer).unwrap();
        }
        let mut pixels = vec![0; TILE_BYTES];
        pixels[..4].copy_from_slice(&[id as u8 * 50, 0, 0, 255]);
        engine.document.layers[(id - 1) as usize]
            .tiles
            .insert((0, 0), Arc::new(pixels));
    }
    engine.document.layers[0].locked = true;
    engine.document.layers[1].visible = false;
    engine.document.layers[2].blend = BlendMode::Multiply;
    engine.document.layers[2].opacity = 0.4;
    let before = engine.save().unwrap();
    let tiles: Vec<_> = engine
        .document
        .layers
        .iter()
        .map(|layer| layer.tiles[&(0, 0)].clone())
        .collect();
    let initial = engine.command(Command::Pick { x: 0, y: 0 }).unwrap();
    assert_eq!(initial["color"][0], 200);
    let revision = engine.state()["revision"].as_u64().unwrap();
    reorder(&mut engine, 1, 3);
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| layer.id)
            .collect::<Vec<_>>(),
        [2, 3, 4, 1]
    );
    assert_eq!(engine.document.active, 1);
    assert_eq!(engine.state()["revision"], revision + 1);
    assert_eq!(
        engine.command(Command::Pick { x: 0, y: 0 }).unwrap()["color"][0],
        50
    );
    for layer in &engine.document.layers {
        assert!(Arc::ptr_eq(
            &tiles[layer.id as usize - 1],
            &layer.tiles[&(0, 0)]
        ));
    }
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
    let mut loaded = Engine::new(1, 1).unwrap();
    loaded.load(&after).unwrap();
    assert_eq!(loaded.state()["layers"], engine.state()["layers"]);
    reorder(&mut engine, 1, 0);
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| layer.id)
            .collect::<Vec<_>>(),
        [1, 2, 3, 4]
    );
}

#[test]
fn reorder_rejects_stale_or_invalid_targets_and_skips_unchanged_positions() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.command(Command::AddLayer).unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    engine.frame();
    reorder(&mut engine, 1, 0);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame().len(), 16);
    let revision = state["revision"].as_u64().unwrap();
    for (id, index, revision) in [(1, 2, revision), (999, 0, revision), (1, 1, revision - 1)] {
        assert!(engine
            .command(Command::ReorderLayer {
                id,
                index,
                revision
            })
            .is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame().len(), 16);
    }
}

#[test]
fn reorder_only_dirties_tiles_in_the_crossed_layers() {
    let mut engine = Engine::new(512, 128).unwrap();
    for id in 1..=4 {
        if id > 1 {
            engine.command(Command::AddLayer).unwrap();
        }
        engine.document.layers[(id - 1) as usize]
            .tiles
            .insert((id - 1, 0), Arc::new(vec![255; TILE_BYTES]));
    }
    engine.frame();
    reorder(&mut engine, 2, 2);
    let frame = engine.frame();
    assert_eq!(frame.len(), 16 + 2 * (8 + TILE_BYTES));
    assert_eq!(u32::from_le_bytes(frame[16..20].try_into().unwrap()), 1);
    assert_eq!(
        u32::from_le_bytes(frame[24 + TILE_BYTES..28 + TILE_BYTES].try_into().unwrap()),
        2
    );
}
