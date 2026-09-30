use podor_engine::{model::*, AdjustmentRequest, Command, CopyMode, Engine};
use serde_json::{json, Value};
use std::sync::Arc;

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn fixture(active: u32) -> Engine {
    let mut engine = Engine::new(256, 128).unwrap();
    let outer = Layer::group(2, "Outer".into(), GroupIsolation::Isolated);
    let mut inner = Layer::group(3, "Inner".into(), GroupIsolation::Isolated);
    inner.parent_id = Some(2);
    let mut red = Layer::new(4, "Red".into());
    red.parent_id = Some(3);
    red.raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new([200, 40, 10, 255].repeat(TILE_BYTES / 4)));
    let mut blue = Layer::new(5, "Blue".into());
    blue.parent_id = Some(3);
    blue.raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((1, 0), Arc::new([10, 40, 200, 255].repeat(TILE_BYTES / 4)));
    engine
        .document
        .layers
        .extend([outer, inner, red, blue, Layer::new(6, "Top".into())]);
    engine.document.active = active;
    engine.document.next_id = 7;
    reload(&mut engine);
    engine
}

fn reload(engine: &mut Engine) {
    let bytes = engine.save().unwrap();
    engine.load(&bytes).unwrap();
    engine.frame();
}

fn actions(engine: &Engine) -> Vec<Value> {
    let state = engine.state();
    let id = engine.document.active;
    let revision = state["revision"].as_u64().unwrap();
    vec![
        json!({"type":"fill","x":8,"y":8,"color":[20,180,90,255],"tolerance":0}),
        json!({"type":"fill_lasso","points":[{"x":2,"y":2},{"x":50,"y":2},{"x":50,"y":50}],"color":[20,180,90],"opacity":1,"eraser":false}),
        json!({"type":"tone","settings":{"brightness":0.2,"contrast":0,"saturation":0}}),
        json!({"type":"blur","sigma":3}),
        json!({"type":"gradient","id":id,"revision":revision,"settings":{"start":[0,0],"end":[80,0],"from":[0,0,0,255],"to":[255,255,255,255],"opacity":1,"shape":"linear"}}),
        json!({"type":"gradient_map","id":id,"revision":revision,"selection_id":state["selectionId"],"settings":{"stops":[{"position":0,"color":[0,0,0]},{"position":1,"color":[255,255,255]}]}}),
        json!({"type":"cut_selection","revision":revision}),
    ]
}

fn request(engine: &Engine, kind: &str, opacity: f32) -> AdjustmentRequest {
    serde_json::from_value(request_value(engine, kind, opacity)).unwrap()
}

fn request_value(engine: &Engine, kind: &str, opacity: f32) -> Value {
    json!({"id":engine.document.active,"revision":engine.state()["revision"],
        "selection_id":engine.state()["selectionId"],"settings":{"kind":kind,"brightness":0.2,"contrast":0,"saturation":0,"sigma":3,"opacity":opacity,"blend":"normal"}})
}

#[test]
fn group_targets_reject_leaf_edits_and_histogram_without_changing_state_or_pixels() {
    for id in [2, 3] {
        let mut engine = fixture(id);
        let before = engine.save().unwrap();
        let state = engine.state();
        for action in actions(&engine) {
            assert!(command(&mut engine, action).is_err());
            assert_eq!(engine.state(), state);
            assert_eq!(engine.save().unwrap(), before);
            assert_eq!(engine.frame().len(), 16);
        }
        for smudge in [false, true] {
            assert!(engine
                .command(Command::Begin {
                    brush: Brush {
                        smudge,
                        ..Brush::default()
                    },
                    assistant: None
                })
                .is_err());
        }
        for kind in ["tone", "blur", "curves"] {
            assert!(engine
                .preview_adjustment(request(&engine, kind, 1.0))
                .is_err());
        }
        assert!(engine.curve_histogram().is_err());
        assert!(engine.copy_selection(CopyMode::Layer).is_err());
        assert!(engine.copy_selection(CopyMode::Cut).is_err());
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), before);
    }
}

#[test]
fn nested_hidden_or_locked_ancestors_block_edits_and_previews_atomically() {
    for parent in [2, 3] {
        for hidden in [false, true] {
            let mut engine = fixture(4);
            let group = engine
                .document
                .layers
                .iter_mut()
                .find(|layer| layer.id == parent)
                .unwrap();
            group.visible = !hidden;
            group.locked = !hidden;
            reload(&mut engine);
            let before = engine.save().unwrap();
            let state = engine.state();
            for action in actions(&engine) {
                assert!(command(&mut engine, action).is_err());
                assert_eq!(engine.save().unwrap(), before);
                assert_eq!(engine.state(), state);
            }
            for kind in ["tone", "blur", "curves", "gradient_map"] {
                let mut value = request_value(&engine, kind, 1.0);
                if kind == "gradient_map" {
                    value["settings"]["gradient_map"] = json!({"stops":[{"position":0,"color":[0,0,0]},{"position":1,"color":[255,255,255]}]});
                }
                assert!(engine
                    .preview_adjustment(serde_json::from_value(value).unwrap())
                    .is_err());
            }
            assert!(engine
                .command(Command::Begin {
                    brush: Brush {
                        smudge: true,
                        ..Brush::default()
                    },
                    assistant: None
                })
                .is_err());
            assert_eq!(engine.frame().len(), 16);
            assert_eq!(engine.save().unwrap(), before);
            assert_eq!(engine.state(), state);
        }
    }
}

#[test]
fn group_visible_copy_and_color_selection_use_children_instead_of_an_empty_plane() {
    let mut engine = fixture(2);
    let before = engine.save().unwrap();
    let state = engine.state();
    let packet = engine.copy_selection(CopyMode::Visible).unwrap();
    let mut reader = png::Decoder::new(&packet[16..]).read_info().unwrap();
    let mut pixels = vec![0; reader.output_buffer_size()];
    let output = reader.next_frame(&mut pixels).unwrap();
    assert_eq!((output.width, output.height), (256, 128));
    assert_eq!(&pixels[..4], &[200, 40, 10, 255]);
    assert_eq!(&pixels[128 * 4..129 * 4], &[10, 40, 200, 255]);
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    let select = |merged| json!({"type":"select_color","settings":{"x":8,"y":8,"tolerance":0,"contiguous":false,"merged":merged},"mode":"replace"});
    assert!(command(&mut engine, select(false)).is_err());
    assert_eq!(engine.state(), state);
    command(&mut engine, select(true)).unwrap();
    let frame = engine.selection_frame();
    assert_eq!(u32::from_le_bytes(frame[4..8].try_into().unwrap()), 1);
    assert_eq!(&frame[8..16], &[0; 8]);
    assert!(frame[16..]
        .as_chunks::<4>()
        .0
        .iter()
        .all(|pixel| pixel[3] == 255));
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn group_opacity_preview_covers_all_descendant_tiles_and_matches_commit() {
    let mut engine = fixture(2);
    let before = engine.save().unwrap();
    let state = engine.state();
    let identity = engine
        .preview_adjustment(request(&engine, "layer_blend", 1.0))
        .unwrap();
    assert_eq!(identity.len(), 16);
    let value = request_value(&engine, "layer_blend", 0.4);
    let preview = engine
        .preview_adjustment(serde_json::from_value(value.clone()).unwrap())
        .unwrap();
    assert_eq!(u32::from_le_bytes(preview[12..16].try_into().unwrap()), 2);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), before);
    command(
        &mut engine,
        json!({"type":"apply_adjustment","request":value}),
    )
    .unwrap();
    assert_eq!(engine.frame(), preview);
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .find(|layer| layer.id == 2)
            .unwrap()
            .opacity,
        0.4
    );
    let committed = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), committed);
}

fn png() -> Vec<u8> {
    let mut bytes = Vec::new();
    let mut encoder = png::Encoder::new(&mut bytes, 1, 1);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    encoder
        .write_header()
        .unwrap()
        .write_image_data(&[40, 100, 220, 255])
        .unwrap();
    bytes
}

#[test]
fn import_and_paste_insert_inside_active_group_or_above_same_parent_leaf() {
    for active in [2, 3, 4] {
        for paste in [false, true] {
            let mut engine = fixture(active);
            let before = engine.save().unwrap();
            let bytes = png();
            if paste {
                engine.paste_image(&[vec![0; 16], bytes].concat()).unwrap();
            } else {
                engine.import_layer(&bytes, "Imported").unwrap();
            }
            let layer = engine
                .document
                .layers
                .iter()
                .find(|layer| layer.id == 7)
                .unwrap();
            assert!(!layer.is_group());
            assert_eq!(layer.parent_id, Some(if active == 2 { 2 } else { 3 }));
            let order: Vec<_> = engine
                .document
                .layers
                .iter()
                .map(|layer| layer.id)
                .collect();
            assert_eq!(
                order,
                if active == 4 {
                    vec![1, 2, 3, 4, 7, 5, 6]
                } else {
                    vec![1, 2, 3, 4, 5, 7, 6]
                }
            );
            engine.document.validate().unwrap();
            let imported = engine.save().unwrap();
            engine.command(Command::Undo).unwrap();
            assert_eq!(engine.save().unwrap(), before);
            engine.command(Command::Redo).unwrap();
            assert_eq!(engine.save().unwrap(), imported);
        }
    }
}

#[test]
fn insertion_checks_destination_ancestors_but_preserves_locked_sibling_behavior() {
    for active in [2, 4] {
        for hidden in [false, true] {
            let mut engine = fixture(active);
            engine.document.layers[1].visible = !hidden;
            engine.document.layers[1].locked = !hidden;
            reload(&mut engine);
            let before = engine.save().unwrap();
            let state = engine.state();
            let bytes = png();
            assert!(engine.import_layer(&bytes, "Imported").is_err());
            assert!(engine.paste_image(&[vec![0; 16], bytes].concat()).is_err());
            assert_eq!(engine.save().unwrap(), before);
            assert_eq!(engine.state(), state);
        }
    }
    let mut engine = fixture(4);
    engine.document.layers[3].locked = true;
    reload(&mut engine);
    engine.import_layer(&png(), "Imported").unwrap();
    assert_eq!(engine.document.layers[4].parent_id, Some(3));
    assert!(engine.document.layers[3].locked);
}

#[test]
fn empty_groups_do_not_consume_raster_slots_but_node_and_leaf_caps_remain_atomic() {
    let mut engine = Engine::new(16, 16).unwrap();
    engine.document.layers.extend(
        (2..=33).map(|id| Layer::group(id, format!("Group {id}"), GroupIsolation::Isolated)),
    );
    engine.document.active = 33;
    engine.document.next_id = 34;
    reload(&mut engine);
    engine.import_layer(&png(), "Imported").unwrap();
    assert_eq!(engine.document.layers.len(), 34);
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .filter(|layer| !layer.is_group())
            .count(),
        2
    );
    assert_eq!(engine.document.layers.last().unwrap().parent_id, Some(33));
    for nodes in [false, true] {
        let mut engine = Engine::new(16, 16).unwrap();
        if nodes {
            engine.document.layers.extend(
                (2..=MAX_LAYER_NODES as u32)
                    .map(|id| Layer::group(id, format!("Group {id}"), GroupIsolation::Isolated)),
            );
            engine.document.active = MAX_LAYER_NODES as u32;
        } else {
            engine
                .document
                .layers
                .extend((2..=MAX_LAYERS as u32).map(|id| Layer::new(id, format!("Layer {id}"))));
            engine.document.layers.push(Layer::group(
                MAX_LAYERS as u32 + 1,
                "Group".into(),
                GroupIsolation::Isolated,
            ));
            engine.document.active = MAX_LAYERS as u32 + 1;
        }
        engine.document.next_id = engine.document.active + 1;
        reload(&mut engine);
        let before = engine.save().unwrap();
        let state = engine.state();
        assert!(engine.import_layer(&png(), "Imported").is_err());
        assert!(engine.paste_image(&[vec![0; 16], png()].concat()).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
}
