use podor_engine::{model::*, AdjustmentRequest, Command, Engine};
use serde_json::{json, Value};
use std::sync::Arc;

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn fixture() -> Engine {
    let mut engine = Engine::new(256, 192).unwrap();
    let mut layer = Layer::new(2, "Paint".into());
    layer.opacity = 0.7;
    layer.blend = BlendMode::Multiply;
    for key in [(0, 0), (1, 1)] {
        let mut tile = vec![0; TILE_BYTES];
        for y in 0..64 {
            for x in 0..128 {
                let i = (y * 128 + x) * 4;
                tile[i..i + 4].copy_from_slice(&[40 + (x % 64) as u8, 25, 70, 128]);
            }
        }
        layer.tiles.insert(key, Arc::new(tile));
    }
    engine.document.layers.push(layer);
    engine.document.active = 2;
    engine.document.next_id = 3;
    let bytes = engine.save().unwrap();
    engine.load(&bytes).unwrap();
    engine.frame();
    engine
}

fn request(engine: &Engine, kind: &str, brightness: f32) -> Value {
    json!({"id":engine.document.active,"revision":engine.state()["revision"],
        "settings":{"kind":kind,"brightness":brightness,"contrast":0.2,"saturation":-0.15,"sigma":3.0}})
}

fn preview(engine: &Engine, request: &Value) -> Result<Vec<u8>, String> {
    engine.preview_adjustment(serde_json::from_value::<AdjustmentRequest>(request.clone()).unwrap())
}

fn apply(engine: &mut Engine, request: &Value) -> Result<Value, String> {
    command(engine, json!({"type":"apply_adjustment","request":request}))
}

#[test]
fn preview_is_read_only_and_confirmed_pixels_match_with_one_undo() {
    for kind in ["tone", "blur"] {
        let mut engine = fixture();
        let initial = engine.save().unwrap();
        let before = engine.state();
        let value = request(&engine, kind, 0.2);
        let pixels = preview(&engine, &value).unwrap();
        assert!(pixels.len() > 16);
        assert_eq!(engine.save().unwrap(), initial);
        assert_eq!(engine.state(), before);
        assert_eq!(engine.frame().len(), 16);
        apply(&mut engine, &value).unwrap();
        assert_eq!(engine.frame(), pixels);
        assert_eq!(
            engine.state()["revision"].as_u64().unwrap(),
            before["revision"].as_u64().unwrap() + 1
        );
        let applied = engine.save().unwrap();
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), initial);
        assert_eq!(engine.state()["canUndo"], false);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), applied);
    }
}

#[test]
fn repeated_settings_start_from_original_and_match_existing_filters() {
    for kind in ["tone", "blur"] {
        let mut engine = fixture();
        let initial = engine.save().unwrap();
        let first = request(&engine, kind, 0.1);
        let a = preview(&engine, &first).unwrap();
        let mut second = request(&engine, kind, -0.3);
        second["settings"]["sigma"] = json!(12.0);
        assert_ne!(a, preview(&engine, &second).unwrap());
        assert_eq!(a, preview(&engine, &first).unwrap());
        apply(&mut engine, &first).unwrap();
        let mut reference = Engine::new(256, 192).unwrap();
        reference.load(&initial).unwrap();
        command(
            &mut reference,
            if kind == "tone" {
                json!({"type":"tone","settings":first["settings"]})
            } else {
                json!({"type":"blur","sigma":3.0})
            },
        )
        .unwrap();
        assert_eq!(engine.save().unwrap(), reference.save().unwrap());
    }
}

#[test]
fn selection_holes_and_alpha_lock_are_preserved() {
    for kind in ["tone", "blur"] {
        let mut engine = fixture();
        command(
            &mut engine,
            json!({"type":"set_protection","id":2,"alpha_locked":true}),
        )
        .unwrap();
        command(
            &mut engine,
            json!({"type":"select","rect":{"left":0,"top":0,"right":100,"bottom":80}}),
        )
        .unwrap();
        command(&mut engine, json!({"type":"combine_selection","mode":"subtract","selection":{"kind":"ellipse","left":20,"top":10,"right":60,"bottom":50}})).unwrap();
        engine.frame();
        let old = engine.document.layers[1].tiles[&(0, 0)].clone();
        let value = request(&engine, kind, 0.3);
        let frame = preview(&engine, &value).unwrap();
        apply(&mut engine, &value).unwrap();
        assert_eq!(engine.frame(), frame);
        let result = &engine.document.layers[1].tiles[&(0, 0)];
        for (a, b) in old.as_chunks::<4>().0.iter().zip(result.as_chunks::<4>().0) {
            assert_eq!(a[3], b[3]);
        }
        let hole = (30 * 128 + 40) * 4;
        assert_eq!(&old[hole..hole + 4], &result[hole..hole + 4]);
        let outside = (10 * 128 + 115) * 4;
        assert_eq!(&old[outside..outside + 4], &result[outside..outside + 4]);
        assert_ne!(old.as_slice(), result.as_slice());
    }
}

#[test]
fn stale_locked_hidden_and_invalid_requests_do_not_change_the_document() {
    for fault in ["revision", "id", "locked", "hidden", "tone", "blur"] {
        let mut engine = fixture();
        let mut value = request(&engine, if fault == "blur" { "blur" } else { "tone" }, 0.3);
        match fault {
            "revision" => value["revision"] = json!(999),
            "id" => value["id"] = json!(1),
            "locked" => engine.document.layers[1].locked = true,
            "hidden" => engine.document.layers[1].visible = false,
            "tone" => value["settings"]["brightness"] = json!(5.0),
            "blur" => value["settings"]["sigma"] = json!(-1.0),
            _ => unreachable!(),
        }
        let before = engine.save().unwrap();
        let state = engine.state();
        assert!(preview(&engine, &value).is_err());
        assert!(apply(&mut engine, &value).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame().len(), 16);
    }
}

#[test]
fn neutral_and_empty_selections_do_not_create_pixels_or_history() {
    let mut engine = fixture();
    let mut value = request(&engine, "tone", 0.0);
    value["settings"]["contrast"] = json!(0.0);
    value["settings"]["saturation"] = json!(0.0);
    let before = engine.state();
    assert_eq!(preview(&engine, &value).unwrap().len(), 16);
    apply(&mut engine, &value).unwrap();
    assert_eq!(engine.state(), before);
    command(
        &mut engine,
        json!({"type":"select","rect":{"left":0,"top":0,"right":10,"bottom":10}}),
    )
    .unwrap();
    command(&mut engine, json!({"type":"combine_selection","mode":"subtract","selection":{"left":0,"top":0,"right":10,"bottom":10}})).unwrap();
    let before = engine.state();
    for kind in ["tone", "blur"] {
        let value = request(&engine, kind, 0.4);
        assert_eq!(preview(&engine, &value).unwrap().len(), 16);
        apply(&mut engine, &value).unwrap();
        assert_eq!(engine.state(), before);
    }
}
