use podor_engine::{
    model::*, Command, Engine, SelectionKind, SelectionMode, SelectionPoint, SelectionSpec,
};
use std::sync::Arc;

fn path(points: &[[f32; 2]]) -> Vec<SelectionPoint> {
    points
        .iter()
        .map(|&[x, y]| SelectionPoint { x, y })
        .collect()
}

fn rectangle(left: f32, top: f32, right: f32, bottom: f32) -> Vec<SelectionPoint> {
    path(&[[left, top], [right, top], [right, bottom], [left, bottom]])
}

fn apply(
    engine: &mut Engine,
    points: Vec<SelectionPoint>,
    color: [u8; 3],
    opacity: f32,
    eraser: bool,
) -> Result<(), String> {
    engine
        .command(Command::FillLasso {
            points,
            color,
            opacity,
            eraser,
        })
        .map(|_| ())
}

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

fn mask(engine: &Engine, width: u32, height: u32) -> Vec<u8> {
    let bytes = engine.selection_frame();
    let mut output = vec![0; (width * height) as usize];
    for tile in bytes[8..].as_chunks::<{ 8 + TILE_BYTES }>().0 {
        let tx = u32::from_le_bytes(tile[..4].try_into().unwrap());
        let ty = u32::from_le_bytes(tile[4..8].try_into().unwrap());
        for y in 0..TILE_SIZE {
            for x in 0..TILE_SIZE {
                let (px, py) = (tx * TILE_SIZE + x, ty * TILE_SIZE + y);
                if px < width && py < height {
                    output[(py * width + px) as usize] =
                        tile[8 + ((y * TILE_SIZE + x) * 4 + 3) as usize];
                }
            }
        }
    }
    output
}

#[test]
fn closes_path_clips_canvas_and_commits_one_atomic_undo() {
    let mut engine = Engine::new(270, 150).unwrap();
    let before = engine.save().unwrap();
    apply(
        &mut engine,
        rectangle(-20.0, 12.0, 265.0, 160.0),
        [200, 40, 80],
        0.5,
        false,
    )
    .unwrap();
    assert_eq!(pixel(&engine, 0, 12), [100, 20, 40, 128]);
    assert_eq!(pixel(&engine, 264, 149), [100, 20, 40, 128]);
    assert_eq!(pixel(&engine, 265, 60), [0; 4]);
    assert_eq!(pixel(&engine, 10, 11), [0; 4]);
    let after = engine.save().unwrap();
    assert_eq!(engine.state()["revision"], 1);
    engine.document.validate().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state()["canUndo"], false);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
}

#[test]
fn uses_selection_antialiasing_and_even_odd_self_intersections() {
    let points = path(&[[4.25, 4.1], [55.7, 56.3], [5.2, 56.5], [55.7, 4.1]]);
    let mut selected = Engine::new(64, 64).unwrap();
    selected
        .command(Command::SelectShape {
            selection: SelectionSpec {
                bounds: selected.document.bounds(),
                kind: SelectionKind::Lasso,
                points: points.clone(),
            },
        })
        .unwrap();
    let coverage = mask(&selected, 64, 64);
    let mut engine = Engine::new(64, 64).unwrap();
    apply(&mut engine, points, [255, 255, 255], 1.0, false).unwrap();
    for y in 0..64 {
        for x in 0..64 {
            assert_eq!(pixel(&engine, x, y), [coverage[(y * 64 + x) as usize]; 4]);
        }
    }
    assert_eq!(pixel(&engine, 30, 10)[3], 255);
    assert_eq!(pixel(&engine, 10, 30)[3], 0);
    assert!(coverage.iter().any(|&alpha| alpha > 0 && alpha < 255));
}

#[test]
fn multiplies_selection_coverage_without_replacing_selection_or_its_id() {
    let mut engine = Engine::new(64, 64).unwrap();
    engine
        .command(Command::SelectShape {
            selection: SelectionSpec {
                bounds: Rect {
                    left: 6,
                    top: 7,
                    right: 54,
                    bottom: 57,
                },
                kind: SelectionKind::Ellipse,
                points: vec![],
            },
        })
        .unwrap();
    engine
        .command(Command::CombineSelection {
            selection: SelectionSpec {
                bounds: engine.document.bounds(),
                kind: SelectionKind::Rectangle,
                points: vec![],
            },
            mode: SelectionMode::Intersect,
        })
        .unwrap();
    let selection = engine.state()["selection"].clone();
    let selected_mask = mask(&engine, 64, 64);
    let points = path(&[[1.2, 2.4], [58.8, 45.7], [3.1, 59.2]]);
    let mut unselected = Engine::new(64, 64).unwrap();
    apply(&mut unselected, points.clone(), [255; 3], 1.0, false).unwrap();
    apply(&mut engine, points, [255; 3], 1.0, false).unwrap();
    for y in 0..64 {
        for x in 0..64 {
            let expected = (u32::from(pixel(&unselected, x, y)[3])
                * u32::from(selected_mask[(y * 64 + x) as usize])
                + 127)
                / 255;
            assert_eq!(pixel(&engine, x, y), [expected as u8; 4]);
        }
    }
    assert_eq!(engine.state()["selection"], selection);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.state()["selection"], selection);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.state()["selection"], selection);
}

#[test]
fn eraser_reduces_premultiplied_pixels_and_removes_empty_tiles() {
    let mut engine = Engine::new(128, 128).unwrap();
    let points = rectangle(0.0, 0.0, 128.0, 128.0);
    apply(&mut engine, points.clone(), [200, 40, 80], 0.5, false).unwrap();
    let before = engine.save().unwrap();
    apply(&mut engine, points.clone(), [0; 3], 0.5, true).unwrap();
    assert_eq!(pixel(&engine, 64, 64), [50, 10, 20, 64]);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    apply(&mut engine, points, [0; 3], 1.0, true).unwrap();
    assert!(engine
        .document
        .active_mut()
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn alpha_lock_recolors_existing_pixels_without_growing_alpha() {
    let mut engine = Engine::new(64, 64).unwrap();
    apply(
        &mut engine,
        rectangle(12.0, 12.0, 44.0, 44.0),
        [200, 40, 80],
        0.5,
        false,
    )
    .unwrap();
    engine
        .command(Command::SetProtection {
            id: 1,
            alpha_locked: Some(true),
            locked: None,
        })
        .unwrap();
    apply(
        &mut engine,
        rectangle(0.0, 0.0, 64.0, 64.0),
        [20, 200, 40],
        1.0,
        false,
    )
    .unwrap();
    assert_eq!(pixel(&engine, 20, 20), [10, 100, 20, 128]);
    assert_eq!(pixel(&engine, 4, 4), [0; 4]);
    let state = engine.state();
    let before = engine.save().unwrap();
    assert!(apply(
        &mut engine,
        rectangle(0.0, 0.0, 64.0, 64.0),
        [0; 3],
        1.0,
        true
    )
    .is_err());
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn valid_empty_operations_preserve_content_and_redo() {
    let mut engine = Engine::new(64, 64).unwrap();
    apply(
        &mut engine,
        rectangle(4.0, 4.0, 60.0, 60.0),
        [200, 40, 80],
        1.0,
        false,
    )
    .unwrap();
    engine.command(Command::Undo).unwrap();
    let state = engine.state();
    let before = engine.save().unwrap();
    let mut retraced = rectangle(4.0, 4.0, 60.0, 60.0);
    retraced.extend(retraced.clone());
    for (points, opacity) in [
        (vec![], 1.0),
        (path(&[[1.0, 2.0], [5.0, 6.0]]), 1.0),
        (path(&[[1.0, 2.0], [5.0, 6.0], [9.0, 10.0]]), 1.0),
        (rectangle(80.0, 80.0, 120.0, 120.0), 1.0),
        (rectangle(-40.0, -40.0, -20.0, -20.0), 1.0),
        (rectangle(4.0, 4.0, 60.0, 60.0), 0.0),
        (retraced, 1.0),
    ] {
        apply(&mut engine, points, [200, 40, 80], opacity, false).unwrap();
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), before);
    }
    engine.command(Command::Redo).unwrap();
    let state = engine.state();
    apply(
        &mut engine,
        rectangle(4.0, 4.0, 60.0, 60.0),
        [200, 40, 80],
        1.0,
        false,
    )
    .unwrap();
    assert_eq!(engine.state(), state);
}

#[test]
fn rejects_invalid_paths_opacity_protected_layers_and_active_strokes_atomically() {
    let mut engine = Engine::new(64, 64).unwrap();
    for (points, opacity) in [
        (path(&[[f32::NAN, 1.0]]), 0.0),
        (path(&[[1.0, f32::INFINITY]]), 0.0),
        (path(&[[MAX_DIMENSION as f32 * 2.0 + 1.0, 1.0]]), 1.0),
        (
            vec![SelectionPoint { x: 1.0, y: 1.0 }; MAX_SELECTION_POINTS + 1],
            1.0,
        ),
        (rectangle(0.0, 0.0, 32.0, 32.0), f32::NAN),
        (rectangle(0.0, 0.0, 32.0, 32.0), -0.1),
        (rectangle(0.0, 0.0, 32.0, 32.0), 1.1),
    ] {
        let state = engine.state();
        let before = engine.save().unwrap();
        assert!(apply(&mut engine, points, [255; 3], opacity, false).is_err());
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), before);
    }
    for (locked, visible) in [(true, true), (false, false)] {
        let layer = engine.document.active_mut();
        layer.locked = locked;
        layer.visible = visible;
        let state = engine.state();
        assert!(apply(
            &mut engine,
            rectangle(0.0, 0.0, 32.0, 32.0),
            [255; 3],
            1.0,
            false
        )
        .is_err());
        assert_eq!(engine.state(), state);
    }
    engine.document.active_mut().visible = true;
    engine
        .command(Command::Begin {
            brush: Brush::default(),
            assistant: None,
        })
        .unwrap();
    let state = engine.state();
    assert!(apply(
        &mut engine,
        rectangle(0.0, 0.0, 32.0, 32.0),
        [255; 3],
        1.0,
        false
    )
    .is_err());
    assert_eq!(engine.state(), state);
    engine.command(Command::Cancel).unwrap();
}

#[test]
fn preflights_document_and_history_budgets_without_partial_writes() {
    let mut engine = Engine::new(4096, 4096).unwrap();
    let pixels = Arc::new(vec![255; TILE_BYTES]);
    for id in [2, 3] {
        let mut layer = Layer::new(id, id.to_string());
        for ty in 0..32 {
            for tx in 0..32 {
                layer
                    .raster_mut()
                    .unwrap()
                    .tiles_mut()
                    .insert((tx, ty), pixels.clone());
            }
        }
        engine.document.layers.push(layer);
    }
    engine.document.next_id = 4;
    let state = engine.state();
    assert!(apply(
        &mut engine,
        rectangle(0.0, 0.0, 128.0, 128.0),
        [200, 40, 80],
        1.0,
        false
    )
    .is_err());
    assert_eq!(engine.state(), state);
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
    let mut engine = Engine::new(4097, 4094).unwrap();
    for ty in 0..32 {
        for tx in 0..33 {
            let mut tile = vec![0; TILE_BYTES];
            for y in 0..TILE_SIZE {
                for x in 0..TILE_SIZE {
                    if tx * TILE_SIZE + x < 4097 && ty * TILE_SIZE + y < 4094 {
                        let offset = ((y * TILE_SIZE + x) * 4) as usize;
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
                .insert((tx, ty), Arc::new(tile));
        }
    }
    let state = engine.state();
    let before = engine.document.layers[0].raster().unwrap().tiles().clone();
    assert!(apply(
        &mut engine,
        rectangle(0.0, 0.0, 4097.0, 4094.0),
        [200, 40, 80],
        1.0,
        false
    )
    .is_err());
    assert_eq!(engine.state(), state);
    for (key, tile) in before {
        assert!(Arc::ptr_eq(
            &tile,
            &engine.document.layers[0].raster().unwrap().tiles()[&key]
        ));
    }
}
