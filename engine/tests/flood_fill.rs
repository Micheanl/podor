use podor_engine::{model::*, Command, Engine, SelectionKind, SelectionSpec};
use serde_json::json;
use std::sync::Arc;

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == engine.document.active)
        .unwrap()
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| {
            let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[offset..offset + 4].try_into().unwrap()
        })
}

fn put(engine: &mut Engine, x: u32, y: u32, color: [u8; 4]) {
    let tile = engine
        .document
        .active_mut()
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .entry((x / TILE_SIZE, y / TILE_SIZE))
        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&color);
}

fn fill(
    engine: &mut Engine,
    x: u32,
    y: u32,
    color: [u8; 4],
    tolerance: u8,
    contiguous: bool,
    merged: bool,
) -> Result<(), String> {
    engine
        .command(Command::Fill {
            x,
            y,
            color,
            tolerance,
            contiguous,
            merged,
        })
        .map(|_| ())
}

#[test]
fn old_json_defaults_to_active_layer_four_neighbor_fill() {
    let mut engine = Engine::new(4, 4).unwrap();
    for y in 0..4 {
        for x in 0..4 {
            put(&mut engine, x, y, [255; 4]);
        }
    }
    for (x, y) in [(0, 0), (1, 1), (2, 2)] {
        put(&mut engine, x, y, [0, 0, 0, 255]);
    }
    let before = engine.save().unwrap();
    engine
        .command(
            serde_json::from_value(json!({
                "type":"fill", "x":0, "y":0, "color":[200,40,80,128], "tolerance":0
            }))
            .unwrap(),
        )
        .unwrap();
    assert_eq!(pixel(&engine, 0, 0), [100, 20, 40, 128]);
    assert_eq!(pixel(&engine, 1, 1), [0, 0, 0, 255]);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    fill(&mut engine, 0, 0, [200, 40, 80, 128], 0, false, false).unwrap();
    for (x, y) in [(0, 0), (1, 1), (2, 2)] {
        assert_eq!(pixel(&engine, x, y), [100, 20, 40, 128]);
    }
    assert_eq!(pixel(&engine, 0, 1), [255; 4]);
}

#[test]
fn straight_color_and_alpha_control_tolerance_at_transparent_boundaries() {
    let mut engine = Engine::new(5, 1).unwrap();
    for (x, color) in [
        [50, 0, 0, 128],
        [52, 0, 0, 128],
        [60, 0, 0, 128],
        [50, 0, 0, 84],
        [0; 4],
    ]
    .into_iter()
    .enumerate()
    {
        put(&mut engine, x as u32, 0, color);
    }
    fill(&mut engine, 0, 0, [0, 255, 0, 255], 12, false, false).unwrap();
    assert_eq!(pixel(&engine, 0, 0), [0, 255, 0, 255]);
    assert_eq!(pixel(&engine, 1, 0), [0, 255, 0, 255]);
    assert_eq!(pixel(&engine, 2, 0), [60, 0, 0, 128]);
    assert_eq!(pixel(&engine, 3, 0), [50, 0, 0, 84]);
    assert_eq!(pixel(&engine, 4, 0), [0; 4]);
    let mut engine = Engine::new(2, 1).unwrap();
    put(&mut engine, 1, 0, [0, 0, 0, 255]);
    fill(&mut engine, 0, 0, [255, 0, 0, 255], 254, true, false).unwrap();
    assert_eq!(pixel(&engine, 0, 0), [255, 0, 0, 255]);
    assert_eq!(pixel(&engine, 1, 0), [0, 0, 0, 255]);
}

#[test]
fn merged_samples_visible_composite_but_writes_only_active_layer() {
    let mut engine = Engine::new(260, 8).unwrap();
    for y in 0..8 {
        for x in 0..260 {
            put(
                &mut engine,
                x,
                y,
                if x < 129 {
                    [200, 40, 80, 255]
                } else {
                    [40, 80, 200, 255]
                },
            );
        }
    }
    let base = engine.document.layers[0].raster().unwrap().tiles().clone();
    engine.command(Command::AddLayer).unwrap();
    for y in 0..8 {
        for x in 0..260 {
            put(&mut engine, x, y, [0, 255, 0, 255]);
        }
    }
    engine.document.active_mut().visible = false;
    let hidden = engine.document.layers[1].raster().unwrap().tiles().clone();
    engine.command(Command::AddLayer).unwrap();
    let before = engine.save().unwrap();
    fill(&mut engine, 0, 0, [0, 255, 0, 128], 0, true, true).unwrap();
    assert_eq!(pixel(&engine, 128, 7), [0, 128, 0, 128]);
    assert_eq!(pixel(&engine, 129, 7), [0; 4]);
    for (key, tile) in &base {
        assert!(Arc::ptr_eq(
            tile,
            &engine.document.layers[0].raster().unwrap().tiles()[key]
        ));
    }
    for (key, tile) in &hidden {
        assert!(Arc::ptr_eq(
            tile,
            &engine.document.layers[1].raster().unwrap().tiles()[key]
        ));
    }
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
}

#[test]
fn antialiased_selection_coverage_is_preserved_and_applied_once() {
    let mut engine = Engine::new(32, 32).unwrap();
    engine
        .command(Command::SelectShape {
            selection: SelectionSpec {
                bounds: Rect {
                    left: 2,
                    top: 3,
                    right: 29,
                    bottom: 30,
                },
                kind: SelectionKind::Ellipse,
                points: vec![],
            },
        })
        .unwrap();
    let state = engine.state()["selection"].clone();
    let selected = engine.selection_frame();
    fill(&mut engine, 16, 16, [200, 40, 80, 128], 0, true, false).unwrap();
    assert_eq!(engine.state()["selection"], state);
    assert_eq!(engine.selection_frame(), selected);
    assert_eq!(pixel(&engine, 16, 16), [100, 20, 40, 128]);
    let tile = &selected[16..];
    let mut partial = 0;
    for y in 0..32 {
        for x in 0..32 {
            let coverage = tile[((y * TILE_SIZE + x) * 4 + 3) as usize];
            let expected =
                [100, 20, 40, 128].map(|value| ((value * u32::from(coverage) + 127) / 255) as u8);
            assert_eq!(pixel(&engine, x, y), expected);
            partial += usize::from(coverage > 0 && coverage < 255);
        }
    }
    assert!(partial > 0);
}

#[test]
fn unchanged_fills_keep_revision_redo_and_dirty_queue() {
    let mut engine = Engine::new(260, 140).unwrap();
    fill(&mut engine, 0, 0, [200, 40, 80, 128], 0, true, false).unwrap();
    let state = engine.state();
    let tiles = engine.document.layers[0].raster().unwrap().tiles().clone();
    engine.frame();
    fill(&mut engine, 0, 0, [200, 40, 80, 128], 255, false, false).unwrap();
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame().len(), 16);
    for (key, tile) in tiles {
        assert!(Arc::ptr_eq(
            &tile,
            &engine.document.layers[0].raster().unwrap().tiles()[&key]
        ));
    }
    engine.command(Command::Undo).unwrap();
    let state = engine.state();
    engine.frame();
    fill(&mut engine, 0, 0, [200, 40, 80, 0], 255, false, false).unwrap();
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame().len(), 16);
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
    engine.command(Command::Redo).unwrap();
    assert_eq!(pixel(&engine, 130, 130), [100, 20, 40, 128]);
}

#[test]
fn alpha_locked_fill_cannot_cross_transparent_gaps_or_change_alpha() {
    let mut engine = Engine::new(5, 1).unwrap();
    for x in [0, 1, 3, 4] {
        put(&mut engine, x, 0, [100, 20, 40, 128]);
    }
    engine.document.active_mut().alpha_locked = true;
    fill(&mut engine, 0, 0, [0, 0, 255, 128], 255, true, false).unwrap();
    assert_eq!(pixel(&engine, 0, 0), [50, 10, 84, 128]);
    assert_eq!(pixel(&engine, 1, 0), [50, 10, 84, 128]);
    assert_eq!(pixel(&engine, 2, 0), [0; 4]);
    assert_eq!(pixel(&engine, 3, 0), [100, 20, 40, 128]);
    let state = engine.state();
    fill(&mut engine, 2, 0, [0, 0, 255, 255], 255, false, false).unwrap();
    assert_eq!(engine.state(), state);
    fill(&mut engine, 0, 0, [255, 0, 0, 0], 255, false, false).unwrap();
    assert_eq!(engine.state(), state);
}

#[test]
fn hidden_locked_invalid_and_active_stroke_requests_are_atomic() {
    let mut engine = Engine::new(8, 8).unwrap();
    for (locked, visible) in [(true, true), (false, false)] {
        engine.document.active_mut().locked = locked;
        engine.document.active_mut().visible = visible;
        let state = engine.state();
        assert!(fill(&mut engine, 0, 0, [255; 4], 0, true, false).is_err());
        assert_eq!(engine.state(), state);
        assert!(engine.document.layers[0]
            .raster()
            .unwrap()
            .tiles()
            .is_empty());
    }
    engine.document.active_mut().visible = true;
    let state = engine.state();
    assert!(fill(&mut engine, 8, 0, [255; 4], 0, true, false).is_err());
    assert_eq!(engine.state(), state);
    engine
        .command(Command::Begin {
            brush: Brush::default(),
            assistant: None,
        })
        .unwrap();
    assert!(fill(&mut engine, 0, 0, [255; 4], 0, true, false).is_err());
    assert_eq!(engine.state(), state);
    engine.command(Command::Cancel).unwrap();
}

#[test]
fn allocation_and_undo_budgets_reject_before_any_tile_is_replaced() {
    let mut engine = Engine::new(4096, 4096).unwrap();
    let tile = Arc::new(vec![255; TILE_BYTES]);
    for id in [2, 3] {
        let mut layer = Layer::new(id, id.to_string());
        for y in 0..32 {
            for x in 0..32 {
                layer
                    .raster_mut()
                    .unwrap()
                    .tiles_mut()
                    .insert((x, y), tile.clone());
            }
        }
        engine.document.layers.push(layer);
    }
    engine.document.next_id = 4;
    let state = engine.state();
    assert!(fill(&mut engine, 0, 0, [255; 4], 0, true, false).is_err());
    assert_eq!(engine.state(), state);
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
    let mut engine = Engine::new(4097, 4094).unwrap();
    for y in 0..32 {
        for x in 0..33 {
            let mut tile = vec![0; TILE_BYTES];
            for py in 0..TILE_SIZE {
                for px in 0..TILE_SIZE {
                    if x * TILE_SIZE + px < 4097 && y * TILE_SIZE + py < 4094 {
                        let offset = ((py * TILE_SIZE + px) * 4) as usize;
                        tile[offset..offset + 4].copy_from_slice(&[0, 0, 200, 255]);
                    }
                }
            }
            engine
                .document
                .active_mut()
                .raster_mut()
                .unwrap()
                .tiles_mut()
                .insert((x, y), Arc::new(tile));
        }
    }
    let before = engine.document.layers[0].raster().unwrap().tiles().clone();
    let state = engine.state();
    assert!(fill(&mut engine, 0, 0, [255; 4], 0, false, false).is_err());
    assert_eq!(engine.state(), state);
    for (key, tile) in before {
        assert!(Arc::ptr_eq(
            &tile,
            &engine.document.layers[0].raster().unwrap().tiles()[&key]
        ));
    }
}
