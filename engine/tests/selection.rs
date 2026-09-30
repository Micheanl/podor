use podor_engine::{model::*, Command, Engine};
use serde_json::{json, Value};
use std::sync::Arc;

fn command(engine: &mut Engine, value: Value) {
    engine
        .command(serde_json::from_value(value).unwrap())
        .unwrap();
}

fn load(bytes: &[u8]) -> Engine {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(bytes).unwrap();
    engine
}

fn shape(kind: &str, points: Value) -> Value {
    json!({"type":"select_shape","selection":{"kind":kind,"left":6,"top":4,"right":58,"bottom":44,"points":points}})
}

fn polygon() -> Value {
    json!([{"x":6.3,"y":4.2},{"x":57.6,"y":9.8},{"x":23.5,"y":22.7},{"x":48.1,"y":43.9},{"x":7.2,"y":37.8}])
}

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| {
            let index = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[index..index + 4].try_into().unwrap()
        })
}

fn fill(engine: &mut Engine, x: u32, y: u32, color: [u8; 4]) {
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x,
            y,
            color,
            tolerance: 255,
        })
        .unwrap();
}

fn mask(shape: Value) -> Engine {
    let mut engine = Engine::new(64, 48).unwrap();
    command(&mut engine, shape);
    fill(&mut engine, 16, 20, [255, 0, 0, 255]);
    engine
}

#[test]
fn ellipse_has_symmetric_antialiased_edges_and_preserves_transparent_color() {
    let engine = mask(shape("ellipse", json!([])));
    let mut area = 0.0;
    let mut edge = 0;
    for y in 0..48 {
        for x in 0..64 {
            let color = pixel(&engine, x, y);
            assert_eq!(color, [color[3], 0, 0, color[3]]);
            assert_eq!(color, pixel(&engine, 63 - x, y));
            assert_eq!(color, pixel(&engine, x, 47 - y));
            edge += usize::from(color[3] > 0 && color[3] < 255);
            area += f64::from(color[3]) / 255.0;
        }
    }
    assert!((area - std::f64::consts::PI * 26.0 * 20.0).abs() < 2.0);
    assert!(edge > 100);
    assert_eq!(pixel(&engine, 6, 4), [0; 4]);
    assert_eq!(pixel(&engine, 32, 24), [255, 0, 0, 255]);
}

#[test]
fn concave_lasso_matches_an_independent_point_in_polygon_reference() {
    let points = polygon();
    let vertices = points
        .as_array()
        .unwrap()
        .iter()
        .map(|p| (p["x"].as_f64().unwrap(), p["y"].as_f64().unwrap()))
        .collect::<Vec<_>>();
    let engine = mask(shape("lasso", points));
    for y in 0..48 {
        for x in 0..64 {
            let mut inside = 0;
            for sy in 0..8 {
                for sx in 0..64 {
                    let px = f64::from(x) + (f64::from(sx) + 0.5) / 64.0;
                    let py = f64::from(y) + (f64::from(sy) + 0.5) / 8.0;
                    let crossings = vertices
                        .iter()
                        .zip(vertices.iter().cycle().skip(1))
                        .filter(|&(&(ax, ay), &(bx, by))| {
                            (ay > py) != (by > py) && px < (bx - ax) * (py - ay) / (by - ay) + ax
                        })
                        .count();
                    inside += u32::from(!crossings.is_multiple_of(2));
                }
            }
            let expected = ((inside * 255 + 256) / 512) as u8;
            assert!(
                pixel(&engine, x, y)[3].abs_diff(expected) <= 2,
                "coverage at {x},{y}"
            );
        }
    }
}

#[test]
fn flood_does_not_cross_unselected_holes_or_disconnected_lobes() {
    let mut engine = Engine::new(64, 48).unwrap();
    command(
        &mut engine,
        shape(
            "lasso",
            json!([
                {"x":4,"y":4},{"x":24,"y":4},{"x":24,"y":24},{"x":4,"y":24},{"x":4,"y":4},
                {"x":40,"y":4},{"x":60,"y":4},{"x":60,"y":24},{"x":40,"y":24},{"x":40,"y":4},{"x":4,"y":4}
            ]),
        ),
    );
    fill(&mut engine, 10, 10, [255; 4]);
    assert_eq!(pixel(&engine, 10, 10), [255; 4]);
    assert_eq!(pixel(&engine, 30, 10), [0; 4]);
    assert_eq!(pixel(&engine, 50, 10), [0; 4]);
    fill(&mut engine, 50, 10, [255; 4]);
    assert_eq!(pixel(&engine, 50, 10), [255; 4]);
    command(
        &mut engine,
        shape(
            "lasso",
            json!([
                {"x":-10,"y":-10},{"x":74,"y":-10},{"x":74,"y":58},{"x":-10,"y":58},{"x":-10,"y":-10},
                {"x":16,"y":12},{"x":48,"y":12},{"x":48,"y":36},{"x":16,"y":36},{"x":16,"y":12},{"x":-10,"y":-10}
            ]),
        ),
    );
    let before = engine.save().unwrap();
    assert!(engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 32,
            y: 24,
            color: [255; 4],
            tolerance: 255
        })
        .is_err());
    assert_eq!(engine.save().unwrap(), before);
    fill(&mut engine, 0, 0, [200, 0, 0, 255]);
    assert_eq!(pixel(&engine, 0, 0), [200, 0, 0, 255]);
    assert_eq!(pixel(&engine, 32, 24), [0; 4]);
}

fn patterned() -> Engine {
    let mut engine = Engine::new(64, 48).unwrap();
    let mut tile = vec![0; TILE_BYTES];
    for y in 0..48 {
        for x in 0..64 {
            let alpha = if (x + y) % 7 == 0 { 90 } else { 180 };
            let i = ((y * TILE_SIZE + x) * 4) as usize;
            tile[i..i + 4].copy_from_slice(&[alpha / 2, alpha / 3, alpha / 4, alpha]);
        }
    }
    engine
        .document
        .active_mut()
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new(tile));
    engine
}

#[test]
fn filters_mix_only_selected_coverage_and_undo_restores_original() {
    for selection in [shape("ellipse", json!([])), shape("lasso", polygon())] {
        let coverage = mask(selection.clone());
        for operation in [
            json!({"type":"tone","settings":{"brightness":0.3,"contrast":0.2,"saturation":-0.5}}),
            json!({"type":"blur","sigma":3.2}),
        ] {
            for alpha_locked in [false, true] {
                let mut selected = patterned();
                selected.document.active_mut().alpha_locked = alpha_locked;
                let before = selected.save().unwrap();
                let mut full = load(&before);
                command(&mut full, operation.clone());
                command(&mut selected, selection.clone());
                command(&mut selected, operation.clone());
                let original = load(&before);
                for y in 0..48 {
                    for x in 0..64 {
                        let amount = u32::from(pixel(&coverage, x, y)[3]);
                        let old = pixel(&original, x, y);
                        let new = pixel(&full, x, y);
                        let actual = pixel(&selected, x, y);
                        for c in 0..4 {
                            let expected = ((u32::from(old[c]) * (255 - amount)
                                + u32::from(new[c]) * amount
                                + 127)
                                / 255) as u8;
                            assert_eq!(
                                actual[c], expected,
                                "filter coverage at {x},{y}, channel {c}"
                            );
                        }
                    }
                }
                let after = selected.save().unwrap();
                selected.command(Command::Undo).unwrap();
                assert_eq!(selected.save().unwrap(), before);
                selected.command(Command::Redo).unwrap();
                assert_eq!(selected.save().unwrap(), after);
            }
        }
    }
}

#[test]
fn brushes_erasers_and_alpha_lock_respect_lasso_and_ellipse() {
    for selection in [shape("ellipse", json!([])), shape("lasso", polygon())] {
        let coverage = mask(selection.clone());
        for (eraser, locked) in [(false, false), (true, false), (false, true)] {
            let mut engine = patterned();
            engine.document.active_mut().alpha_locked = locked;
            let before = engine.save().unwrap();
            command(&mut engine, selection.clone());
            engine
                .command(Command::Begin {
                    brush: Brush {
                        size: 200.0,
                        hardness: 1.0,
                        color: [255, 0, 0],
                        eraser,
                        ..Brush::default()
                    },
                    assistant: None,
                })
                .unwrap();
            engine
                .samples(&[Sample {
                    x: 32.0,
                    y: 24.0,
                    pressure: 1.0,
                }])
                .unwrap();
            engine.command(Command::End).unwrap();
            let original = load(&before);
            for y in 0..48 {
                for x in 0..64 {
                    let old = pixel(&original, x, y);
                    let actual = pixel(&engine, x, y);
                    if pixel(&coverage, x, y)[3] == 0 {
                        assert_eq!(actual, old);
                    }
                    if locked {
                        assert_eq!(actual[3], old[3]);
                    }
                    assert!(actual[..3].iter().all(|&c| c <= actual[3]));
                }
            }
            assert_ne!(engine.save().unwrap(), before);
            engine.command(Command::Undo).unwrap();
            assert_eq!(engine.save().unwrap(), before);
        }
    }
}

#[test]
fn invalid_shapes_leave_selection_pixels_and_history_intact() {
    let mut engine = patterned();
    command(&mut engine, shape("ellipse", json!([])));
    let state = engine.state();
    let before = engine.save().unwrap();
    let mut outside = shape("ellipse", json!([]));
    outside["selection"]["right"] = json!(65);
    for invalid in [
        outside,
        shape("lasso", json!([])),
        shape(
            "lasso",
            json!([{"x":0,"y":0},{"x":10,"y":10},{"x":20,"y":20}]),
        ),
        shape(
            "lasso",
            json!([{"x":0,"y":0},{"x":10,"y":0},{"x":1e30,"y":10}]),
        ),
        shape(
            "lasso",
            json!(vec![json!({"x":10,"y":10}); MAX_SELECTION_POINTS + 1]),
        ),
    ] {
        assert!(engine
            .command(serde_json::from_value(invalid).unwrap())
            .is_err());
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), before);
    }
    assert_eq!(state["revision"], 0);
    assert_eq!(state["canUndo"], false);
    engine.command(Command::Select { rect: None }).unwrap();
    assert!(engine.state()["selection"].is_null());
}

#[test]
fn maximum_canvas_and_long_paths_remain_within_selection_limits() {
    let mut engine = Engine::new(4096, 4096).unwrap();
    let points = (0..MAX_SELECTION_POINTS)
        .map(|i| {
            let t = i as f64 * std::f64::consts::TAU / MAX_SELECTION_POINTS as f64;
            json!({"x":2048.0 + t.cos()*2048.0,"y":2048.0 + t.sin()*2048.0})
        })
        .collect::<Vec<_>>();
    command(&mut engine, shape("lasso", json!(points)));
    assert_eq!(
        engine.state()["selection"]["points"]
            .as_array()
            .unwrap()
            .len(),
        MAX_SELECTION_POINTS
    );
    assert_eq!(engine.state()["selection"]["right"], 4096);
    assert_eq!(engine.document.tile_count(), 0);
}

#[test]
fn painting_outside_curved_selection_does_not_allocate_empty_tiles() {
    let mut engine = Engine::new(1024, 1024).unwrap();
    command(
        &mut engine,
        json!({"type":"select_shape","selection":{"kind":"ellipse","left":0,"top":0,"right":1024,"bottom":1024}}),
    );
    engine
        .command(Command::Begin {
            brush: Brush {
                size: 16.0,
                ..Brush::default()
            },
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x: 1.0,
            y: 1.0,
            pressure: 1.0,
        }])
        .unwrap();
    engine.command(Command::End).unwrap();
    assert_eq!(engine.document.tile_count(), 0);
}
