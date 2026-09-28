use podor_engine::{model::*, AdjustmentRequest, Command, Engine};
use serde_json::{json, Value};
use std::sync::Arc;

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).map_err(|_| "invalid command")?)
}

fn fixture() -> Engine {
    let mut engine = Engine::new(256, 96).unwrap();
    for tx in 0..2 {
        let mut pixels = vec![0; TILE_BYTES];
        for y in 0..96 {
            for x in 0..128 {
                let alpha = [0u32, 32, 128, 255][y % 4];
                let color = [(tx * 128 + x) as u32, 120, 210];
                let i = (y * 128 + x) * 4;
                for c in 0..3 {
                    pixels[i + c] = ((color[c] * alpha + 127) / 255) as u8;
                }
                pixels[i + 3] = alpha as u8;
            }
        }
        engine.document.layers[0]
            .tiles
            .insert((tx as u32, 0), Arc::new(pixels));
    }
    let mut other = Layer::new(2, "Other".into());
    other
        .tiles
        .insert((0, 0), Arc::new([10, 20, 30, 40].repeat(TILE_BYTES / 4)));
    engine.document.layers.push(other);
    engine.document.next_id = 3;
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
    engine.frame_with_background(true);
    engine
}

fn request(engine: &Engine, curves: Value) -> Value {
    json!({"id":engine.document.active,"revision":engine.state()["revision"],"settings":{
        "kind":"curves","brightness":0.0,"contrast":0.0,"saturation":0.0,"sigma":3.0,"curves":curves}})
}

fn preview(engine: &Engine, request: &Value) -> Result<Vec<u8>, String> {
    engine.preview_adjustment(
        serde_json::from_value::<AdjustmentRequest>(request.clone())
            .map_err(|_| "invalid request")?,
    )
}

fn apply(engine: &mut Engine, request: &Value) -> Result<Value, String> {
    command(engine, json!({"type":"apply_adjustment","request":request}))
}

#[test]
fn curves_match_independent_linear_mapping_preserve_alpha_selection_and_other_layers() {
    let mut engine = fixture();
    command(
        &mut engine,
        json!({"type":"select","rect":{"left":100,"top":20,"right":180,"bottom":90}}),
    )
    .unwrap();
    engine.document.layers[0].alpha_locked = true;
    let source = engine.document.layers[0].clone();
    let other = engine.document.layers[1].tiles[&(0, 0)].clone();
    let saved = engine.save().unwrap();
    let state = engine.state();
    let value = request(
        &engine,
        json!({
        "rgb":{"points":[{"x":0,"y":32},{"x":255,"y":224}]},
        "red":{"points":[{"x":0,"y":255},{"x":255,"y":0}]},
        "blue":{"points":[{"x":0,"y":80},{"x":255,"y":80}]}}),
    );
    let result = preview(&engine, &value).unwrap();
    assert_eq!(engine.save().unwrap(), saved);
    assert_eq!(engine.state(), state);
    apply(&mut engine, &value).unwrap();
    assert_eq!(engine.frame_with_background(true), result);
    for (&(tx, ty), pixels) in &source.tiles {
        let actual = &engine.document.layers[0].tiles[&(tx, ty)];
        for (i, p) in pixels.as_chunks::<4>().0.iter().enumerate() {
            let x = tx * 128 + (i % 128) as u32;
            let y = ty * 128 + (i / 128) as u32;
            let alpha = u32::from(p[3]);
            let mut expected = *p;
            if (100..180).contains(&x) && (20..90).contains(&y) && alpha > 0 {
                for c in 0..3 {
                    let raw = (u32::from(p[c]) * 255 + alpha / 2) / alpha;
                    let master = (32.0 + 192.0 * raw as f64 / 255.0).round() as u32;
                    let value = match c {
                        0 => 255 - master,
                        1 => master,
                        _ => 80,
                    };
                    expected[c] = ((value * alpha + 127) / 255) as u8;
                }
            }
            assert_eq!(&actual[i * 4..i * 4 + 4], expected, "pixel {x},{y}");
        }
    }
    assert!(Arc::ptr_eq(
        &other,
        &engine.document.layers[1].tiles[&(0, 0)]
    ));
    let edited = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), saved);
    assert_eq!(engine.state()["canUndo"], false);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), edited);
}

#[test]
fn identity_and_empty_selection_do_not_copy_pixels_or_add_history() {
    let mut engine = fixture();
    let value = request(&engine, json!({}));
    let tile = engine.document.layers[0].tiles[&(0, 0)].clone();
    let state = engine.state();
    assert_eq!(preview(&engine, &value).unwrap().len(), 16);
    apply(&mut engine, &value).unwrap();
    assert_eq!(engine.state(), state);
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].tiles[&(0, 0)]
    ));
    command(
        &mut engine,
        json!({"type":"select","rect":{"left":0,"top":0,"right":1,"bottom":1}}),
    )
    .unwrap();
    let value = request(
        &engine,
        json!({"rgb":{"points":[{"x":0,"y":255},{"x":255,"y":0}]}}),
    );
    let before = engine.save().unwrap();
    assert_eq!(preview(&engine, &value).unwrap().len(), 16);
    apply(&mut engine, &value).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    command(&mut engine,json!({"type":"combine_selection","mode":"subtract","selection":{"left":0,"top":0,"right":1,"bottom":1}})).unwrap();
    assert_eq!(engine.state()["selection"]["empty"], true);
    let value = request(
        &engine,
        json!({"rgb":{"points":[{"x":0,"y":255},{"x":255,"y":0}]}}),
    );
    let state = engine.state();
    assert_eq!(preview(&engine, &value).unwrap().len(), 16);
    apply(&mut engine, &value).unwrap();
    assert_eq!(engine.state(), state);
    let histogram: Vec<Vec<f32>> = serde_json::from_slice(&engine.curve_histogram()).unwrap();
    assert!(histogram.iter().flatten().all(|&value| value == 0.0));
}

#[test]
fn curved_selection_edges_mix_coverage_without_changing_alpha() {
    let mut engine = fixture();
    command(&mut engine,json!({"type":"select_shape","selection":{"kind":"ellipse","left":100,"top":20,"right":180,"bottom":90}})).unwrap();
    let mask = engine.selection_frame();
    let source = engine.document.layers[0].clone();
    let value = request(
        &engine,
        json!({"rgb":{"points":[{"x":0,"y":255},{"x":255,"y":0}]}}),
    );
    apply(&mut engine, &value).unwrap();
    let mut partial = 0;
    for tile in mask[8..].as_chunks::<{ 8 + TILE_BYTES }>().0 {
        let tx = u32::from_le_bytes(tile[..4].try_into().unwrap());
        let ty = u32::from_le_bytes(tile[4..8].try_into().unwrap());
        let before = &source.tiles[&(tx, ty)];
        let after = &engine.document.layers[0].tiles[&(tx, ty)];
        for i in 0..TILE_BYTES / 4 {
            let coverage = u32::from(tile[8 + i * 4 + 3]);
            let alpha = u32::from(before[i * 4 + 3]);
            partial += usize::from(coverage > 0 && coverage < 255 && alpha > 0);
            assert_eq!(after[i * 4 + 3], alpha as u8);
            for c in 0..3 {
                let old = u32::from(before[i * 4 + c]);
                let raw = (old * 255 + alpha / 2).checked_div(alpha).unwrap_or(0);
                let inverted = ((255 - raw) * alpha + 127) / 255;
                let expected = (old * (255 - coverage) + inverted * coverage + 127) / 255;
                assert_eq!(after[i * 4 + c], expected as u8);
            }
        }
    }
    assert!(partial > 20);
}

#[test]
fn invalid_curves_and_unavailable_layers_are_rejected_without_changes() {
    for points in [
        json!([]),
        json!([{"x":0,"y":0}]),
        json!([{"x":0,"y":0},{"x":0,"y":80},{"x":255,"y":255}]),
        json!([{"x":1,"y":0},{"x":255,"y":255}]),
        json!([{"x":0,"y":-1},{"x":255,"y":255}]),
        json!((0..=16)
            .map(|i| json!({"x":if i==16 {255}else{i*15},"y":i*15}))
            .collect::<Vec<_>>()),
    ] {
        let mut engine = fixture();
        let before = engine.save().unwrap();
        let value = request(&engine, json!({"red":{"points":points}}));
        assert!(preview(&engine, &value).is_err());
        assert!(apply(&mut engine, &value).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state()["canUndo"], false);
    }
    for fault in ["locked", "hidden", "revision", "active"] {
        let mut engine = fixture();
        let mut value = request(&engine, json!({}));
        match fault {
            "locked" => engine.document.layers[0].locked = true,
            "hidden" => engine.document.layers[0].visible = false,
            "revision" => value["revision"] = json!(999),
            _ => value["id"] = json!(2),
        }
        let saved = engine.save().unwrap();
        assert!(preview(&engine, &value).is_err());
        assert!(apply(&mut engine, &value).is_err());
        assert_eq!(engine.save().unwrap(), saved);
    }
}

#[test]
fn histogram_uses_active_selected_pixels_and_weights_transparency_without_mutation() {
    let mut engine = Engine::new(4, 1).unwrap();
    let mut tile = vec![0; TILE_BYTES];
    tile[..16].copy_from_slice(&[
        30, 60, 90, 255, 60, 30, 10, 128, 0, 0, 0, 0, 255, 255, 255, 255,
    ]);
    engine.document.layers[0]
        .tiles
        .insert((0, 0), Arc::new(tile));
    command(
        &mut engine,
        json!({"type":"select","rect":{"left":0,"top":0,"right":3,"bottom":1}}),
    )
    .unwrap();
    let saved = engine.save().unwrap();
    let state = engine.state();
    let histogram: Vec<Vec<f32>> = serde_json::from_slice(&engine.curve_histogram()).unwrap();
    assert_eq!(histogram.len(), 4);
    assert!(histogram.iter().all(|h| h.len() == 256));
    assert_eq!(histogram[1][30], 1.0);
    assert_eq!(histogram[1][120], 128.0 / 255.0);
    assert_eq!(histogram[1][0], 0.0);
    assert_eq!(histogram[1][255], 0.0);
    assert_eq!(histogram[2][60], 1.0);
    assert_eq!(histogram[0][30], histogram[0][90]);
    assert_eq!(engine.save().unwrap(), saved);
    assert_eq!(engine.state(), state);
}
