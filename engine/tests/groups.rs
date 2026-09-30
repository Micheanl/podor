use podor_engine::{model::*, Command, Engine};
use serde_json::{json, Value};
use std::{collections::BTreeSet, sync::Arc};

fn command(engine: &mut Engine, mut value: Value) -> Result<Value, String> {
    if value.get("revision").is_none() {
        value["revision"] = engine.state()["revision"].clone();
    }
    engine.command(serde_json::from_value(value).unwrap())
}

fn put(layer: &mut Layer, x: u32, color: [u8; 4]) {
    let tile = layer
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .entry((0, 0))
        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
    Arc::make_mut(tile)[x as usize * 4..x as usize * 4 + 4].copy_from_slice(&color);
}

fn reload(engine: &mut Engine) {
    engine.load(&engine.save().unwrap()).unwrap();
    engine.frame();
}

fn pixel(engine: &Engine, x: u32) -> [u8; 4] {
    let mut copy = Engine::new(1, 1).unwrap();
    copy.load(&engine.save().unwrap()).unwrap();
    let bytes = copy.frame_with_background(true);
    for record in bytes[16..].as_chunks::<{ 8 + TILE_BYTES }>().0 {
        if record[..8] == [0; 8] {
            return record[8 + x as usize * 4..12 + x as usize * 4]
                .try_into()
                .unwrap();
        }
    }
    [0; 4]
}

fn ids(engine: &Engine) -> Vec<u32> {
    engine
        .document
        .layers
        .iter()
        .map(|layer| layer.id)
        .collect()
}

fn flat() -> Engine {
    let mut engine = Engine::new(16, 16).unwrap();
    for id in 2..=4 {
        let mut layer = Layer::new(id, format!("Paint {id}"));
        put(&mut layer, id, [id as u8 * 20, 10, 30, 255]);
        engine.document.layers.push(layer);
    }
    engine.document.next_id = 5;
    reload(&mut engine);
    engine
}

fn assert_atomic(engine: &mut Engine, value: Value) {
    engine.frame();
    let bytes = engine.save().unwrap();
    let state = engine.state();
    assert!(command(engine, value).is_err());
    assert_eq!(engine.save().unwrap(), bytes);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame().len(), 16);
}

#[test]
fn opaque_children_are_composited_before_each_group_opacity_is_applied_once() {
    let mut engine = Engine::new(16, 16).unwrap();
    let mut outer = Layer::group(2, "Outer".into(), GroupIsolation::Isolated);
    outer.opacity = 0.5;
    let mut inner = Layer::group(3, "Inner".into(), GroupIsolation::Isolated);
    inner.parent_id = Some(2);
    let mut red = Layer::new(4, "Red".into());
    red.parent_id = Some(3);
    put(&mut red, 0, [255, 0, 0, 255]);
    let mut blue = Layer::new(5, "Blue".into());
    blue.parent_id = Some(3);
    put(&mut blue, 0, [0, 0, 255, 255]);
    engine.document.layers.extend([outer, inner, red, blue]);
    engine.document.next_id = 6;
    reload(&mut engine);
    assert_eq!(pixel(&engine, 0), [0, 0, 128, 128]);
    command(
        &mut engine,
        json!({"type":"set_layer","id":3,"name":"Inner","visible":true,"opacity":0.5}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 0), [0, 0, 64, 64]);
}

#[test]
fn pass_through_child_blends_with_external_backdrop_and_isolated_child_does_not() {
    let mut engine = Engine::new(16, 16).unwrap();
    put(&mut engine.document.layers[0], 0, [128, 128, 128, 255]);
    let group = Layer::group(2, "Group".into(), GroupIsolation::Isolated);
    let mut child = Layer::new(3, "Multiply red".into());
    child.parent_id = Some(2);
    child.blend = BlendMode::Multiply;
    put(&mut child, 0, [255, 0, 0, 255]);
    engine.document.layers.extend([group, child]);
    engine.document.next_id = 4;
    reload(&mut engine);
    assert_eq!(pixel(&engine, 0), [255, 0, 0, 255]);
    let isolated = engine.save().unwrap();
    command(
        &mut engine,
        json!({"type":"set_group_isolation","id":2,"isolation":"pass_through"}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 0), [128, 0, 0, 255]);
    let pass = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), isolated);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), pass);
    assert_atomic(
        &mut engine,
        json!({"type":"set_layer","id":2,"name":"Group","visible":true,"opacity":0.5}),
    );
}

#[test]
fn same_parent_clip_chains_use_group_or_member_base_alpha_without_inflation() {
    for base_group in [true, false] {
        let mut engine = Engine::new(16, 16).unwrap();
        if base_group {
            let mut group = Layer::group(2, "Group base".into(), GroupIsolation::Isolated);
            group.opacity = 0.5;
            let mut base = Layer::new(3, "Red".into());
            base.parent_id = Some(2);
            put(&mut base, 0, [255, 0, 0, 255]);
            let mut clip = Layer::new(4, "Blue clip".into());
            clip.clipping = true;
            put(&mut clip, 0, [0, 0, 255, 255]);
            put(&mut clip, 1, [0, 0, 255, 255]);
            engine.document.layers.extend([group, base, clip]);
        } else {
            put(&mut engine.document.layers[0], 0, [128, 0, 0, 128]);
            let mut group = Layer::group(2, "Clipped group".into(), GroupIsolation::Isolated);
            group.clipping = true;
            let mut clip = Layer::new(3, "Blue".into());
            clip.parent_id = Some(2);
            put(&mut clip, 0, [0, 0, 255, 255]);
            put(&mut clip, 1, [0, 0, 255, 255]);
            engine.document.layers.extend([group, clip]);
        }
        engine.document.next_id = 5;
        reload(&mut engine);
        assert_eq!(pixel(&engine, 0), [0, 0, 128, 128]);
        assert_eq!(pixel(&engine, 1), [0; 4]);
    }
}

#[test]
fn group_gray_mask_and_ancestor_visibility_affect_composite_without_destroying_leaf_pixels() {
    let mut engine = Engine::new(16, 16).unwrap();
    let outer = Layer::group(2, "Outer".into(), GroupIsolation::Isolated);
    let mut inner = Layer::group(3, "Inner".into(), GroupIsolation::Isolated);
    inner.parent_id = Some(2);
    let mut child = Layer::new(4, "Paint".into());
    child.parent_id = Some(3);
    put(&mut child, 0, [255, 0, 0, 255]);
    engine.document.layers.extend([outer, inner, child]);
    engine.document.active = 2;
    engine.document.next_id = 5;
    reload(&mut engine);
    let raw = engine.document.layers[3].raster().unwrap().tiles().clone();
    command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","id":2,"enabled":true}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"fill","x":0,"y":0,"color":[128,128,128,255],"tolerance":0}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 0), [128, 0, 0, 128]);
    assert_eq!(engine.document.layers[3].raster().unwrap().tiles(), &raw);
    command(
        &mut engine,
        json!({"type":"set_layer","id":3,"name":"Inner","visible":false,"opacity":1}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 0), [0; 4]);
    assert_eq!(engine.document.layers[3].raster().unwrap().tiles(), &raw);
    let layer = engine.state()["layers"]
        .as_array()
        .unwrap()
        .iter()
        .find(|layer| layer["id"] == 4)
        .unwrap()
        .clone();
    assert_eq!(layer["effectiveVisible"], false);
    command(
        &mut engine,
        json!({"type":"set_protection","id":2,"locked":true}),
    )
    .unwrap();
    let layer = engine.state()["layers"]
        .as_array()
        .unwrap()
        .iter()
        .find(|layer| layer["id"] == 4)
        .unwrap()
        .clone();
    assert_eq!(layer["effectiveLocked"], true);
}

#[test]
fn create_group_move_and_ungroup_preserve_tree_order_and_undo_the_whole_transaction() {
    let mut engine = flat();
    command(
        &mut engine,
        json!({"type":"create_group","name":"Container","index":1}),
    )
    .unwrap();
    assert_eq!(ids(&engine), [1, 5, 2, 3, 4]);
    let before = engine.save().unwrap();
    command(
        &mut engine,
        json!({"type":"group_layers","ids":[2,3],"name":"Paint group","index":2}),
    )
    .unwrap();
    assert_eq!(ids(&engine), [1, 5, 6, 2, 3, 4]);
    assert_eq!(engine.document.layers[3].parent_id, Some(6));
    assert_eq!(engine.document.layers[4].parent_id, Some(6));
    let grouped = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), grouped);
    command(
        &mut engine,
        json!({"type":"move_node","id":6,"parent_id":5,"index":0}),
    )
    .unwrap();
    assert_eq!(ids(&engine), [1, 5, 6, 2, 3, 4]);
    assert_eq!(engine.document.layers[2].parent_id, Some(5));
    assert_eq!(engine.state()["uiOrder"], json!([4, 5, 6, 3, 2, 1]));
    assert_eq!(
        engine.state()["layers"]
            .as_array()
            .unwrap()
            .iter()
            .map(|layer| layer["id"].as_u64().unwrap())
            .collect::<Vec<_>>(),
        [1, 5, 6, 2, 3, 4]
    );
    command(
        &mut engine,
        json!({"type":"set_group_closed","id":5,"closed":true}),
    )
    .unwrap();
    assert_eq!(engine.state()["layers"][1]["closed"], true);
    assert_eq!(engine.state()["uiOrder"], json!([4, 5, 6, 3, 2, 1]));
    command(&mut engine, json!({"type":"ungroup","id":6})).unwrap();
    assert_eq!(ids(&engine), [1, 5, 2, 3, 4]);
    assert_eq!(engine.document.layers[2].parent_id, Some(5));
    assert_eq!(engine.document.layers[3].parent_id, Some(5));
    engine.document.validate().unwrap();
}

#[test]
fn duplicate_and_delete_operate_on_complete_subtrees_with_fresh_parent_ids() {
    let mut engine = flat();
    command(
        &mut engine,
        json!({"type":"group_layers","ids":[2,3],"name":"Nested","index":1}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"create_group","name":"Outer","index":1}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"move_node","id":5,"parent_id":6,"index":0}),
    )
    .unwrap();
    let before = engine.save().unwrap();
    command(&mut engine, json!({"type":"duplicate_layer","id":6})).unwrap();
    assert_eq!(ids(&engine), [1, 6, 5, 2, 3, 7, 8, 9, 10, 4]);
    assert_eq!(engine.document.layers[6].parent_id, Some(7));
    assert_eq!(engine.document.layers[7].parent_id, Some(8));
    assert_eq!(engine.document.layers[8].parent_id, Some(8));
    let all_ids: BTreeSet<_> = ids(&engine).into_iter().collect();
    assert_eq!(all_ids.len(), engine.document.layers.len());
    for (original, cloned) in [(2, 9), (3, 10)] {
        let old = engine
            .document
            .layers
            .iter()
            .find(|layer| layer.id == original)
            .unwrap();
        let new = engine
            .document
            .layers
            .iter()
            .find(|layer| layer.id == cloned)
            .unwrap();
        assert_eq!(old.raster().unwrap().tiles(), new.raster().unwrap().tiles());
    }
    let duplicated = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), duplicated);
    command(&mut engine, json!({"type":"remove_layer","id":6})).unwrap();
    assert_eq!(ids(&engine), [1, 7, 8, 9, 10, 4]);
    engine.document.validate().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), duplicated);
}

#[test]
fn stale_cycle_cross_parent_noncontinuous_and_destructive_ungroup_requests_are_atomic() {
    let mut engine = flat();
    assert_atomic(
        &mut engine,
        json!({"type":"group_layers","ids":[1,3],"name":"Gap","index":0}),
    );
    assert_atomic(
        &mut engine,
        json!({"type":"group_layers","ids":[2,2],"name":"Duplicate","index":1}),
    );
    command(
        &mut engine,
        json!({"type":"group_layers","ids":[2,3],"name":"Group","index":1}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"create_group","name":"Nested","parent_id":5,"index":0}),
    )
    .unwrap();
    assert_atomic(
        &mut engine,
        json!({"type":"move_node","id":5,"parent_id":6,"index":0}),
    );
    assert_atomic(
        &mut engine,
        json!({"type":"move_node","id":5,"parent_id":5,"index":0}),
    );
    assert_atomic(
        &mut engine,
        json!({"type":"group_layers","ids":[2,4],"name":"Mixed","parent_id":5,"index":1}),
    );
    assert_atomic(
        &mut engine,
        json!({"type":"move_node","id":4,"parent_id":2,"index":0}),
    );
    assert_atomic(
        &mut engine,
        json!({"type":"create_group","name":"Stale","index":0,"revision":u64::MAX}),
    );
    assert_atomic(
        &mut engine,
        json!({"type":"move_node","id":4,"index":usize::MAX}),
    );
    command(
        &mut engine,
        json!({"type":"set_layer","id":5,"name":"Group","visible":true,"opacity":0.5}),
    )
    .unwrap();
    assert_atomic(&mut engine, json!({"type":"ungroup","id":5}));
}

#[test]
fn ungroup_preserves_hidden_and_locked_subtrees_and_restores_metadata_with_undo() {
    let mut engine = Engine::new(16, 16).unwrap();
    let mut group = Layer::group(2, "Hidden".into(), GroupIsolation::Isolated);
    group.visible = false;
    group.locked = true;
    let mut nested = Layer::group(3, "Nested".into(), GroupIsolation::Isolated);
    nested.parent_id = Some(2);
    let mut child = Layer::new(4, "Paint".into());
    child.parent_id = Some(3);
    put(&mut child, 0, [255, 0, 0, 255]);
    engine.document.layers.extend([group, nested, child]);
    engine.document.active = 2;
    engine.document.next_id = 5;
    reload(&mut engine);
    let before = engine.save().unwrap();
    command(&mut engine, json!({"type":"ungroup","id":2})).unwrap();
    assert_eq!(ids(&engine), [1, 3, 4]);
    assert_eq!(engine.document.layers[1].parent_id, None);
    assert!(!engine.document.layers[1].visible);
    assert!(engine.document.layers[1].locked);
    assert_eq!(engine.document.layers[2].parent_id, Some(3));
    assert!(engine.document.layers[2].visible);
    assert!(!engine.document.layers[2].locked);
    let state = engine.state();
    assert_eq!(state["layers"][2]["effectiveVisible"], false);
    assert_eq!(state["layers"][2]["effectiveLocked"], true);
    assert_eq!(pixel(&engine, 0), [0; 4]);
    let ungrouped = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), ungrouped);
    assert_eq!(pixel(&engine, 0), [0; 4]);
}

#[test]
fn maximum_group_depth_accepts_a_leaf_but_rejects_an_additional_group_atomically() {
    let mut engine = Engine::new(16, 16).unwrap();
    let mut parent = None;
    for depth in 0..MAX_GROUP_DEPTH {
        command(&mut engine, json!({"type":"create_group","name":format!("Depth {depth}"),"parent_id":parent,"index":if parent.is_none(){1}else{0}})).unwrap();
        parent = Some(engine.document.active);
    }
    let deepest = parent.unwrap();
    engine.command(Command::AddLayer).unwrap();
    assert_eq!(
        engine.document.layers.last().unwrap().parent_id,
        Some(deepest)
    );
    assert_eq!(
        engine.state()["layers"].as_array().unwrap().last().unwrap()["depth"],
        MAX_GROUP_DEPTH
    );
    assert_atomic(
        &mut engine,
        json!({"type":"create_group","name":"Too deep","parent_id":deepest,"index":1}),
    );
    engine.document.validate().unwrap();
}
