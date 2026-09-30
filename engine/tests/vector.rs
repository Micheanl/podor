use podor_engine::{model::*, Command, CopyMode, Engine};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

fn command(engine: &mut Engine, mut value: Value) -> Result<Value, String> {
    if value.get("revision").is_none() {
        value["revision"] = engine.state()["revision"].clone();
    }
    if value.get("selection_id").is_none() {
        value["selection_id"] = engine.state()["selectionId"].clone();
    }
    engine.command(serde_json::from_value(value).unwrap())
}

fn create(engine: &mut Engine) -> u32 {
    command(
        engine,
        json!({"type":"create_vector","name":"Vectors","parent_id":null,"index":1}),
    )
    .unwrap();
    engine.document.active
}

fn object(geometry: Value, fill: Option<[u8; 4]>, stroke: Value) -> Value {
    json!({
        "name":"Shape","visible":true,"geometry":geometry,
        "transform":[1.0,0.0,0.0,1.0,0.0,0.0],
        "style":{"fill":fill,"stroke":stroke,"fill_rule":"non_zero"}
    })
}

fn rect(x: f64, y: f64, width: f64, height: f64, color: [u8; 4]) -> Value {
    object(
        json!({"kind":"rect","x":x,"y":y,"width":width,"height":height}),
        Some(color),
        Value::Null,
    )
}

fn stroke(width: f64, cap: &str, join: &str, miter_limit: f64) -> Value {
    json!({"color":[255,0,0,255],"width":width,"cap":cap,"join":join,"miter_limit":miter_limit})
}

fn add(engine: &mut Engine, id: u32, spec: Value) -> u32 {
    command(
        engine,
        json!({"type":"add_vector_object","id":id,"object":spec,"index":null}),
    )
    .unwrap();
    let objects = command(engine, json!({"type":"vector_objects","id":id})).unwrap();
    objects["objects"].as_array().unwrap().last().unwrap()["id"]
        .as_u64()
        .unwrap() as u32
}

fn get(engine: &mut Engine, id: u32, object_id: u32) -> Value {
    command(
        engine,
        json!({"type":"vector_object","id":id,"object_id":object_id}),
    )
    .unwrap()["object"]
        .clone()
}

fn set(engine: &mut Engine, id: u32, object_id: u32, spec: Value) -> Result<Value, String> {
    command(
        engine,
        json!({"type":"set_vector_object","id":id,"object_id":object_id,"object":spec}),
    )
}

fn tiles(packet: &[u8]) -> BTreeMap<TileKey, Vec<u8>> {
    let count = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
    assert_eq!(packet.len(), 16 + count * (8 + TILE_BYTES));
    packet[16..]
        .as_chunks::<{ 8 + TILE_BYTES }>()
        .0
        .iter()
        .map(|record| {
            let x = u32::from_le_bytes(record[..4].try_into().unwrap());
            let y = u32::from_le_bytes(record[4..8].try_into().unwrap());
            ((x, y), record[8..].to_vec())
        })
        .collect()
}

fn frame(engine: &Engine) -> BTreeMap<TileKey, Vec<u8>> {
    let mut copy = Engine::new(1, 1).unwrap();
    copy.load(&engine.save().unwrap()).unwrap();
    tiles(&copy.frame_with_background(true))
}

fn at(frame: &BTreeMap<TileKey, Vec<u8>>, x: u32, y: u32) -> [u8; 4] {
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    frame
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| tile[offset..offset + 4].try_into().unwrap())
}

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    at(&frame(engine), x, y)
}

fn assert_frames_equal(
    actual: &BTreeMap<TileKey, Vec<u8>>,
    expected: &BTreeMap<TileKey, Vec<u8>>,
    width: u32,
    height: u32,
) {
    for y in 0..height {
        for x in 0..width {
            assert_eq!(at(actual, x, y), at(expected, x, y), "at {x},{y}");
        }
    }
}

fn assert_preview_commit(
    engine: &mut Engine,
    original: &BTreeMap<TileKey, Vec<u8>>,
    prepared: &[u8],
) {
    let mut expected = original.clone();
    expected.extend(tiles(prepared));
    let mut actual = original.clone();
    actual.extend(tiles(&engine.frame_with_background(true)));
    assert_frames_equal(
        &actual,
        &expected,
        engine.document.width,
        engine.document.height,
    );
    assert_frames_equal(
        &actual,
        &frame(engine),
        engine.document.width,
        engine.document.height,
    );
}

fn preview(engine: &Engine, edit: Value) -> Result<Vec<u8>, String> {
    engine.preview_layer_action(
        serde_json::from_value(json!({
            "id":engine.document.active,"revision":engine.state()["revision"],
            "selection_id":engine.state()["selectionId"],
            "mask_editing":engine.state()["maskEditing"],
            "mask_id":engine.state()["activeMaskId"],
            "action":{"kind":"vector","edit":edit}
        }))
        .unwrap(),
    )
}

fn atomic_error(engine: &mut Engine, value: Value) {
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let state = engine.state();
    assert!(command(engine, value).is_err());
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

fn install_mask(engine: &mut Engine, id: u32, gray: u8, linked: bool) -> u32 {
    let layer = engine
        .document
        .layers
        .iter_mut()
        .find(|layer| layer.id == id)
        .unwrap();
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 32,
            bottom: 32,
        },
        255,
    );
    if gray != 255 {
        mask.tiles
            .insert((0, 0), Arc::new(vec![gray; MASK_TILE_BYTES]));
    }
    mask.linked = linked;
    layer.masks.push(MaskEntry {
        id: 0,
        name: "Mask".into(),
        plane: mask,
    });
    engine.document.assign_mask_ids().unwrap();
    engine.document.validate().unwrap();
    engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap()
        .masks
        .last()
        .unwrap()
        .id
}

#[test]
fn typed_shapes_and_bezier_control_points_remain_editable_after_save_and_reopen() {
    let cases = [
        (
            rect(2.0, 2.0, 6.0, 6.0, [255, 0, 0, 255]),
            json!({"x":18.0}),
            (5, 5),
            (21, 5),
        ),
        (
            object(
                json!({"kind":"ellipse","cx":6.0,"cy":6.0,"rx":3.0,"ry":3.0}),
                Some([255, 0, 0, 255]),
                Value::Null,
            ),
            json!({"cx":22.0}),
            (6, 6),
            (22, 6),
        ),
        (
            object(
                json!({"kind":"line","x1":2.0,"y1":6.0,"x2":10.0,"y2":6.0}),
                None,
                stroke(4.0, "butt", "miter", 4.0),
            ),
            json!({"x1":18.0,"x2":26.0}),
            (6, 6),
            (22, 6),
        ),
        (
            object(
                json!({"kind":"path","segments":[
                    {"kind":"move_to","x":2.0,"y":12.0},
                    {"kind":"quad_to","cx":8.0,"cy":0.0,"x":14.0,"y":12.0}
                ]}),
                None,
                stroke(2.0, "round", "round", 4.0),
            ),
            json!({"segments":[
                {"kind":"move_to","x":2.0,"y":12.0},
                {"kind":"quad_to","cx":24.0,"cy":0.0,"x":14.0,"y":12.0}
            ]}),
            (8, 6),
            (16, 6),
        ),
        (
            object(
                json!({"kind":"path","segments":[
                    {"kind":"move_to","x":2.0,"y":12.0},
                    {"kind":"cubic_to","c1x":2.0,"c1y":0.0,"c2x":14.0,"c2y":0.0,"x":14.0,"y":12.0}
                ]}),
                None,
                stroke(2.0, "round", "round", 4.0),
            ),
            json!({"segments":[
                {"kind":"move_to","x":2.0,"y":12.0},
                {"kind":"cubic_to","c1x":2.0,"c1y":20.0,"c2x":14.0,"c2y":20.0,"x":14.0,"y":12.0}
            ]}),
            (8, 3),
            (8, 18),
        ),
    ];
    for (spec, patch, old_point, new_point) in cases {
        let mut engine = Engine::new(32, 32).unwrap();
        let id = create(&mut engine);
        let object_id = add(&mut engine, id, spec);
        let canonical = get(&mut engine, id, object_id);
        assert!(pixel(&engine, old_point.0, old_point.1)[3] > 100);
        let saved = engine.save().unwrap();
        engine.load(&saved).unwrap();
        assert_eq!(get(&mut engine, id, object_id), canonical);
        let mut edited = canonical;
        for (key, value) in patch.as_object().unwrap() {
            edited["geometry"][key] = value.clone();
        }
        set(&mut engine, id, object_id, edited.clone()).unwrap();
        assert_eq!(get(&mut engine, id, object_id), edited);
        assert_eq!(pixel(&engine, old_point.0, old_point.1), [0; 4]);
        assert!(pixel(&engine, new_point.0, new_point.1)[3] > 100);
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), saved);
        engine.command(Command::Redo).unwrap();
        assert_eq!(get(&mut engine, id, object_id), edited);
    }
}

#[test]
fn filled_rectangles_emit_premultiplied_alpha_and_fractional_edges_are_antialiased() {
    let mut engine = Engine::new(16, 16).unwrap();
    let id = create(&mut engine);
    let object_id = add(
        &mut engine,
        id,
        rect(2.0, 2.0, 6.0, 6.0, [255, 128, 64, 128]),
    );
    assert_eq!(pixel(&engine, 4, 4), [128, 64, 32, 128]);
    assert_eq!(pixel(&engine, 1, 4), [0; 4]);
    let mut edited = get(&mut engine, id, object_id);
    edited["geometry"]["x"] = json!(2.5);
    set(&mut engine, id, object_id, edited).unwrap();
    let edge = pixel(&engine, 2, 4);
    assert!(edge[3].abs_diff(64) <= 2, "half-covered edge: {edge:?}");
    assert_eq!(edge[0], edge[3]);
    assert!(edge[1].abs_diff(edge[3] / 2) <= 1);
    assert!(edge[2].abs_diff(edge[3] / 4) <= 1);
    assert_eq!(pixel(&engine, 3, 4), [128, 64, 32, 128]);
    assert_eq!(pixel(&engine, 9, 4), [0; 4]);
}

#[test]
fn repeated_contour_uses_winding_number_and_even_odd_parity() {
    let mut engine = Engine::new(16, 16).unwrap();
    let id = create(&mut engine);
    let spec = object(
        json!({"kind":"path","segments":[
            {"kind":"move_to","x":2.0,"y":2.0},
            {"kind":"line_to","x":12.0,"y":2.0},
            {"kind":"line_to","x":12.0,"y":12.0},
            {"kind":"line_to","x":2.0,"y":12.0},
            {"kind":"line_to","x":2.0,"y":2.0},
            {"kind":"line_to","x":12.0,"y":2.0},
            {"kind":"line_to","x":12.0,"y":12.0},
            {"kind":"line_to","x":2.0,"y":12.0},
            {"kind":"close"}
        ]}),
        Some([0, 255, 0, 255]),
        Value::Null,
    );
    let object_id = add(&mut engine, id, spec);
    assert_eq!(pixel(&engine, 6, 6), [0, 255, 0, 255]);
    let mut even_odd = get(&mut engine, id, object_id);
    even_odd["style"]["fill_rule"] = json!("even_odd");
    set(&mut engine, id, object_id, even_odd).unwrap();
    assert_eq!(pixel(&engine, 6, 6), [0; 4]);
    assert_eq!(pixel(&engine, 0, 0), [0; 4]);
}

#[test]
fn line_caps_and_acute_joins_have_distinct_geometric_coverage() {
    let mut cap_pixels = BTreeMap::new();
    for cap in ["butt", "round", "square"] {
        let mut engine = Engine::new(32, 32).unwrap();
        let id = create(&mut engine);
        add(
            &mut engine,
            id,
            object(
                json!({"kind":"line","x1":8.0,"y1":8.0,"x2":24.0,"y2":8.0}),
                None,
                stroke(6.0, cap, "miter", 4.0),
            ),
        );
        assert_eq!(pixel(&engine, 12, 8), [255, 0, 0, 255]);
        cap_pixels.insert(cap, (pixel(&engine, 6, 8), pixel(&engine, 5, 5)));
    }
    assert_eq!(cap_pixels["butt"].0, [0; 4]);
    assert_eq!(cap_pixels["round"].0, [255, 0, 0, 255]);
    assert_eq!(cap_pixels["square"].0, [255, 0, 0, 255]);
    assert_eq!(cap_pixels["square"].1, [255, 0, 0, 255]);
    assert!(cap_pixels["round"].1[3] < 64);
    let mut joins = BTreeMap::new();
    for (join, limit) in [
        ("miter", 4.0),
        ("round", 4.0),
        ("bevel", 4.0),
        ("miter", 1.0),
    ] {
        let mut engine = Engine::new(32, 32).unwrap();
        let id = create(&mut engine);
        add(
            &mut engine,
            id,
            object(
                json!({"kind":"path","segments":[
                    {"kind":"move_to","x":8.0,"y":24.0},
                    {"kind":"line_to","x":16.0,"y":8.0},
                    {"kind":"line_to","x":24.0,"y":24.0}
                ]}),
                None,
                stroke(6.0, "butt", join, limit),
            ),
        );
        joins.insert(
            (join, limit as u32),
            (pixel(&engine, 15, 3), pixel(&engine, 15, 5)),
        );
    }
    assert!(joins[&("miter", 4)].0[3] > 150);
    for key in [("round", 4), ("bevel", 4), ("miter", 1)] {
        assert_eq!(joins[&key].0, [0; 4]);
    }
    assert!(joins[&("round", 4)].1[3] > joins[&("bevel", 4)].1[3]);
}

#[test]
fn object_order_visibility_and_affine_transform_are_independent_of_typed_geometry() {
    let mut engine = Engine::new(32, 32).unwrap();
    let id = create(&mut engine);
    let red = add(&mut engine, id, rect(2.0, 2.0, 8.0, 8.0, [255, 0, 0, 255]));
    let blue = add(&mut engine, id, rect(4.0, 4.0, 8.0, 8.0, [0, 0, 255, 255]));
    assert_eq!(pixel(&engine, 6, 6), [0, 0, 255, 255]);
    command(
        &mut engine,
        json!({"type":"reorder_vector_object","id":id,"object_id":red,"index":1}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 6, 6), [255, 0, 0, 255]);
    let mut transformed = get(&mut engine, id, red);
    transformed["transform"] = json!([0.0, 1.0, -1.0, 0.0, 24.0, 0.0]);
    set(&mut engine, id, red, transformed.clone()).unwrap();
    assert_eq!(pixel(&engine, 6, 6), [0, 0, 255, 255]);
    assert_eq!(pixel(&engine, 18, 6), [255, 0, 0, 255]);
    assert_eq!(
        get(&mut engine, id, red)["geometry"],
        transformed["geometry"]
    );
    let mut hidden = get(&mut engine, id, blue);
    hidden["visible"] = json!(false);
    set(&mut engine, id, blue, hidden).unwrap();
    assert_eq!(pixel(&engine, 6, 6), [0; 4]);
    assert_eq!(pixel(&engine, 18, 6), [255, 0, 0, 255]);
}

#[test]
fn read_only_queries_pick_topmost_visible_coverage_and_preserve_all_native_state() {
    let mut engine = Engine::new(32, 32).unwrap();
    let id = create(&mut engine);
    let bottom = add(&mut engine, id, rect(2.0, 2.0, 8.0, 8.0, [255, 0, 0, 255]));
    let top = add(&mut engine, id, rect(4.0, 4.0, 8.0, 8.0, [0, 0, 255, 255]));
    let mut hidden = rect(4.0, 4.0, 8.0, 8.0, [0, 255, 0, 255]);
    hidden["visible"] = json!(false);
    add(&mut engine, id, hidden);
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let state = engine.state();
    let summary = command(&mut engine, json!({"type":"vector_objects","id":id})).unwrap();
    assert_eq!(summary["objects"].as_array().unwrap().len(), 3);
    assert!(summary["objects"][0].get("geometry").is_none());
    assert_eq!(summary["objects"][0]["id"], bottom);
    assert_eq!(summary["objects"][1]["id"], top);
    for (x, y, expected) in [
        (6.0, 6.0, Some(top)),
        (3.0, 3.0, Some(bottom)),
        (20.0, 20.0, None),
    ] {
        let result = command(
            &mut engine,
            json!({"type":"pick_vector_object","id":id,"x":x,"y":y,"tolerance":0.0}),
        )
        .unwrap();
        assert_eq!(result["object_id"], json!(expected));
    }
    get(&mut engine, id, bottom);
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    let layer = state["layers"]
        .as_array()
        .unwrap()
        .iter()
        .find(|layer| layer["id"] == id)
        .unwrap();
    assert_eq!(layer["kind"], "vector");
    assert_eq!(layer["vector"]["objectCount"], 3);
    assert!(layer["vector"].get("objects").is_none());
    assert_eq!(state["maxVectorObjects"], MAX_VECTOR_OBJECTS);
    assert_eq!(state["maxVectorSegments"], MAX_VECTOR_SEGMENTS);
}

#[test]
fn prepared_node_edits_are_read_only_and_clear_old_tiles_before_one_atomic_commit() {
    let mut engine = Engine::new(384, 32).unwrap();
    let id = create(&mut engine);
    let object_id = add(
        &mut engine,
        id,
        rect(8.0, 8.0, 16.0, 16.0, [255, 0, 0, 255]),
    );
    let mut edited = get(&mut engine, id, object_id);
    edited["geometry"]["x"] = json!(264.0);
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let state = engine.state();
    let original = frame(&engine);
    let prepared = preview(
        &engine,
        json!({"type":"set","object_id":object_id,"object":edited}),
    )
    .unwrap();
    let changes = tiles(&prepared);
    assert!(changes.contains_key(&(0, 0)));
    assert!(changes.contains_key(&(2, 0)));
    assert_eq!(at(&changes, 12, 12), [0; 4]);
    assert_eq!(at(&changes, 268, 12), [255, 0, 0, 255]);
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    let mut merged = original.clone();
    merged.extend(changes);
    assert_eq!(at(&merged, 12, 12), [0; 4]);
    set(&mut engine, id, object_id, edited).unwrap();
    assert_preview_commit(&mut engine, &original, &prepared);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
}

#[test]
fn add_delete_and_reorder_previews_match_commit_and_cancel_does_not_assign_ids() {
    let mut engine = Engine::new(32, 32).unwrap();
    let id = create(&mut engine);
    let red = add(
        &mut engine,
        id,
        rect(2.0, 2.0, 10.0, 10.0, [255, 0, 0, 255]),
    );
    let blue = add(
        &mut engine,
        id,
        rect(2.0, 2.0, 10.0, 10.0, [0, 0, 255, 255]),
    );
    for (edit, commit) in [
        (
            json!({"type":"reorder","object_id":red,"index":1}),
            json!({"type":"reorder_vector_object","id":id,"object_id":red,"index":1}),
        ),
        (
            json!({"type":"delete","object_id":blue}),
            json!({"type":"delete_vector_object","id":id,"object_id":blue}),
        ),
        (
            json!({"type":"add","object":rect(18.0,2.0,8.0,8.0,[0,255,0,255]),"index":null}),
            json!({"type":"add_vector_object","id":id,"object":rect(18.0,2.0,8.0,8.0,[0,255,0,255]),"index":null}),
        ),
    ] {
        engine.frame_with_background(true);
        let before = engine.save().unwrap();
        let state = engine.state();
        let original = frame(&engine);
        let prepared = preview(&engine, edit).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame_with_background(true).len(), 16);
        command(&mut engine, commit).unwrap();
        assert_preview_commit(&mut engine, &original, &prepared);
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        engine.command(Command::Redo).unwrap();
    }
    assert_eq!(pixel(&engine, 6, 6), [255, 0, 0, 255]);
    assert_eq!(pixel(&engine, 20, 5), [0, 255, 0, 255]);
    let summary = command(&mut engine, json!({"type":"vector_objects","id":id})).unwrap();
    assert_eq!(summary["nextObjectId"], 4);
}

#[test]
fn exact_object_noops_keep_revision_history_allocation_and_dirty_frames_unchanged() {
    let mut engine = Engine::new(16, 16).unwrap();
    let id = create(&mut engine);
    let object_id = add(&mut engine, id, rect(2.0, 2.0, 8.0, 8.0, [255, 0, 0, 255]));
    let spec = get(&mut engine, id, object_id);
    let source = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap()
        .vector()
        .unwrap()
        .clone();
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let state = engine.state();
    for edit in [
        json!({"type":"set","object_id":object_id,"object":spec}),
        json!({"type":"reorder","object_id":object_id,"index":0}),
    ] {
        assert_eq!(preview(&engine, edit).unwrap().len(), 16);
    }
    set(&mut engine, id, object_id, spec).unwrap();
    command(
        &mut engine,
        json!({"type":"reorder_vector_object","id":id,"object_id":object_id,"index":0}),
    )
    .unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    assert!(Arc::ptr_eq(
        &source,
        engine
            .document
            .layers
            .iter()
            .find(|layer| layer.id == id)
            .unwrap()
            .vector()
            .unwrap()
    ));
}

#[test]
fn stale_targets_invalid_geometry_and_styles_reject_without_any_transaction() {
    let mut engine = Engine::new(32, 32).unwrap();
    let id = create(&mut engine);
    let object_id = add(&mut engine, id, rect(2.0, 2.0, 8.0, 8.0, [255, 0, 0, 255]));
    let original = get(&mut engine, id, object_id);
    let revision = engine.state()["revision"].as_u64().unwrap();
    let mut invalid = Vec::new();
    for (field, value) in [
        (
            "geometry",
            json!({"kind":"rect","x":2.0,"y":2.0,"width":0.0,"height":8.0}),
        ),
        (
            "geometry",
            json!({"kind":"ellipse","cx":2.0,"cy":2.0,"rx":-1.0,"ry":8.0}),
        ),
        (
            "geometry",
            json!({"kind":"path","segments":[{"kind":"line_to","x":2.0,"y":2.0}]}),
        ),
        (
            "geometry",
            json!({"kind":"path","segments":[{"kind":"close"}]}),
        ),
        ("transform", json!([1.0, 0.0, 2.0, 0.0, 0.0, 0.0])),
        (
            "style",
            json!({"fill":null,"stroke":null,"fill_rule":"non_zero"}),
        ),
        (
            "style",
            json!({"fill":null,"stroke":stroke(0.0,"butt","miter",4.0),"fill_rule":"non_zero"}),
        ),
        (
            "style",
            json!({"fill":null,"stroke":stroke(1.0,"butt","miter",0.0),"fill_rule":"non_zero"}),
        ),
        ("name", json!("x".repeat(MAX_LAYER_NAME_BYTES + 1))),
    ] {
        let mut spec = original.clone();
        spec[field] = value;
        invalid
            .push(json!({"type":"set_vector_object","id":id,"object_id":object_id,"object":spec}));
    }
    invalid.extend([
        json!({"type":"set_vector_object","id":id,"object_id":object_id,"object":original,"revision":revision - 1}),
        json!({"type":"delete_vector_object","id":id,"object_id":u32::MAX}),
        json!({"type":"reorder_vector_object","id":id,"object_id":object_id,"index":1}),
        json!({"type":"add_vector_object","id":1,"object":original,"index":null}),
        json!({"type":"pick_vector_object","id":id,"x":6.0,"y":6.0,"tolerance":16.01}),
    ]);
    for value in invalid {
        atomic_error(&mut engine, value);
    }
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let state = engine.state();
    for field in ["revision", "selection_id", "id"] {
        let mut request = json!({"id":id,"revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":false,"action":{"kind":"vector","edit":{"type":"delete","object_id":object_id}}});
        request[field] = if field == "id" {
            json!(999)
        } else {
            json!(u64::MAX)
        };
        assert!(engine
            .preview_layer_action(serde_json::from_value(request).unwrap())
            .is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
}

#[test]
fn ancestor_locks_and_visibility_and_pixel_selection_protect_vector_edits() {
    let mut engine = Engine::new(32, 32).unwrap();
    let id = create(&mut engine);
    let object_id = add(&mut engine, id, rect(2.0, 2.0, 8.0, 8.0, [255, 0, 0, 255]));
    command(
        &mut engine,
        json!({"type":"group_layers","ids":[id],"name":"Group","parent_id":null,"index":1}),
    )
    .unwrap();
    let group = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.is_group())
        .unwrap()
        .id;
    command(&mut engine, json!({"type":"select_layer","id":id})).unwrap();
    let mut moved = get(&mut engine, id, object_id);
    moved["geometry"]["x"] = json!(18.0);
    for (locked, visible) in [(true, true), (false, false)] {
        engine
            .document
            .layers
            .iter_mut()
            .find(|layer| layer.id == group)
            .unwrap()
            .locked = locked;
        engine
            .document
            .layers
            .iter_mut()
            .find(|layer| layer.id == group)
            .unwrap()
            .visible = visible;
        atomic_error(
            &mut engine,
            json!({"type":"set_vector_object","id":id,"object_id":object_id,"object":moved}),
        );
        assert!(preview(
            &engine,
            json!({"type":"set","object_id":object_id,"object":moved})
        )
        .is_err());
    }
    engine
        .document
        .layers
        .iter_mut()
        .find(|layer| layer.id == group)
        .unwrap()
        .visible = true;
    command(
        &mut engine,
        json!({"type":"select","rect":{"left":2,"top":2,"right":4,"bottom":4}}),
    )
    .unwrap();
    atomic_error(
        &mut engine,
        json!({"type":"set_vector_object","id":id,"object_id":object_id,"object":moved}),
    );
    atomic_error(&mut engine, json!({"type":"rasterize_vector","id":id}));
    atomic_error(
        &mut engine,
        json!({"type":"translate_layer","id":id,"dx":1,"dy":0}),
    );
}

#[test]
fn object_and_segment_limits_reject_atomically_including_document_segment_budget() {
    let mut engine = Engine::new(16, 16).unwrap();
    let id = create(&mut engine);
    let spec = rect(2.0, 2.0, 2.0, 2.0, [255, 0, 0, 255]);
    for _ in 0..MAX_VECTOR_OBJECTS {
        command(
            &mut engine,
            json!({"type":"add_vector_object","id":id,"object":spec,"index":null}),
        )
        .unwrap();
    }
    atomic_error(
        &mut engine,
        json!({"type":"add_vector_object","id":id,"object":spec,"index":null}),
    );
    let mut engine = Engine::new(16, 16).unwrap();
    let id = create(&mut engine);
    let mut segments = vec![json!({"kind":"move_to","x":2.0,"y":2.0})];
    segments.extend((1..MAX_VECTOR_SEGMENTS).map(
        |index| json!({"kind":"line_to","x":2.0 + (index % 2) as f64,"y":2.0 + (index % 3) as f64}),
    ));
    let spec = object(
        json!({"kind":"path","segments":segments}),
        None,
        stroke(1.0, "butt", "bevel", 4.0),
    );
    for _ in 0..MAX_DOCUMENT_VECTOR_SEGMENTS / MAX_VECTOR_SEGMENTS {
        command(
            &mut engine,
            json!({"type":"add_vector_object","id":id,"object":spec,"index":null}),
        )
        .unwrap();
    }
    atomic_error(
        &mut engine,
        json!({"type":"add_vector_object","id":id,"object":spec,"index":null}),
    );
    let mut oversized = spec;
    oversized["geometry"]["segments"]
        .as_array_mut()
        .unwrap()
        .push(json!({"kind":"line_to","x":3.0,"y":3.0}));
    let mut engine = Engine::new(16, 16).unwrap();
    let id = create(&mut engine);
    atomic_error(
        &mut engine,
        json!({"type":"add_vector_object","id":id,"object":oversized,"index":null}),
    );
}

#[test]
fn vector_geometry_is_counted_and_copy_on_write_undo_restores_the_original_allocation() {
    let mut engine = Engine::new(32, 32).unwrap();
    let id = create(&mut engine);
    let object_id = add(&mut engine, id, rect(2.0, 2.0, 8.0, 8.0, [255, 0, 0, 255]));
    let source = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap()
        .vector()
        .unwrap()
        .clone();
    let resource_bytes: usize = engine.document.resources().map(|(_, bytes)| bytes).sum();
    assert!(resource_bytes > 0);
    command(&mut engine, json!({"type":"duplicate_layer","id":id})).unwrap();
    let duplicate_id = engine.document.active;
    assert!(Arc::ptr_eq(
        &source,
        engine
            .document
            .layers
            .iter()
            .find(|layer| layer.id == duplicate_id)
            .unwrap()
            .vector()
            .unwrap()
    ));
    let before = engine.save().unwrap();
    let mut moved = get(&mut engine, duplicate_id, object_id);
    moved["geometry"]["x"] = json!(18.0);
    set(&mut engine, duplicate_id, object_id, moved).unwrap();
    assert!(Arc::ptr_eq(
        &source,
        engine
            .document
            .layers
            .iter()
            .find(|layer| layer.id == id)
            .unwrap()
            .vector()
            .unwrap()
    ));
    assert!(!Arc::ptr_eq(
        &source,
        engine
            .document
            .layers
            .iter()
            .find(|layer| layer.id == duplicate_id)
            .unwrap()
            .vector()
            .unwrap()
    ));
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert!(Arc::ptr_eq(
        &source,
        engine
            .document
            .layers
            .iter()
            .find(|layer| layer.id == duplicate_id)
            .unwrap()
            .vector()
            .unwrap()
    ));
    engine.command(Command::Redo).unwrap();
    assert_eq!(pixel(&engine, 5, 5), [255, 0, 0, 255]);
    assert_eq!(pixel(&engine, 21, 5), [255, 0, 0, 255]);
}

#[test]
fn whole_layer_and_group_geometry_preserve_vector_objects_and_all_linked_masks() {
    let mut engine = Engine::new(64, 64).unwrap();
    let id = create(&mut engine);
    let object_id = add(&mut engine, id, rect(2.0, 2.0, 8.0, 8.0, [255, 0, 0, 255]));
    let original_geometry = get(&mut engine, id, object_id)["geometry"].clone();
    let linked = install_mask(&mut engine, id, 255, true);
    let fixed = install_mask(&mut engine, id, 255, false);
    let masks = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap()
        .masks
        .clone();
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let original = frame(&engine);
    let request = json!({"id":id,"revision":engine.state()["revision"],"selection_id":engine.state()["selectionId"],"mask_editing":false,"action":{"kind":"translate","dx":16,"dy":0}});
    let prepared = engine
        .preview_layer_action(serde_json::from_value(request).unwrap())
        .unwrap();
    command(
        &mut engine,
        json!({"type":"translate_layer","id":id,"dx":16,"dy":0}),
    )
    .unwrap();
    assert_preview_commit(&mut engine, &original, &prepared);
    assert_eq!(pixel(&engine, 5, 5), [0; 4]);
    assert_eq!(pixel(&engine, 21, 5), [255, 0, 0, 255]);
    assert_eq!(
        get(&mut engine, id, object_id)["geometry"],
        original_geometry
    );
    let layer = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap();
    assert!(layer.is_vector());
    assert_eq!(
        layer
            .masks
            .iter()
            .find(|mask| mask.id == linked)
            .unwrap()
            .plane
            .bounds
            .left,
        masks[0].plane.bounds.left + 16
    );
    assert_eq!(
        layer
            .masks
            .iter()
            .find(|mask| mask.id == fixed)
            .unwrap()
            .plane,
        masks[1].plane
    );
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    command(
        &mut engine,
        json!({"type":"group_layers","ids":[id],"name":"Group","parent_id":null,"index":1}),
    )
    .unwrap();
    let group_id = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.is_group())
        .unwrap()
        .id;
    command(
        &mut engine,
        json!({"type":"translate_layer","id":group_id,"dx":16,"dy":16}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 21, 21), [255, 0, 0, 255]);
    command(&mut engine, json!({"type":"select_layer","id":id})).unwrap();
    assert_eq!(
        get(&mut engine, id, object_id)["geometry"],
        original_geometry
    );
    assert!(engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap()
        .is_vector());
}

#[test]
fn whole_vector_scaling_uses_the_shape_extent_without_antialias_padding() {
    let mut engine = Engine::new(32, 32).unwrap();
    let id = create(&mut engine);
    let object_id = add(&mut engine, id, rect(8.0, 8.0, 8.0, 8.0, [255, 0, 0, 255]));
    let geometry = get(&mut engine, id, object_id)["geometry"].clone();
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let original = frame(&engine);
    let transform = json!({"width":16,"height":8,"dx":0.0,"dy":0.0,"angle":0.0,"flip_x":false,"flip_y":false,"filter":"nearest"});
    let prepared = engine.preview_layer_action(serde_json::from_value(json!({"id":id,"revision":engine.state()["revision"],"selection_id":engine.state()["selectionId"],"mask_editing":false,"action":{"kind":"transform","transform":transform}})).unwrap()).unwrap();
    command(
        &mut engine,
        json!({"type":"transform_layer","id":id,"transform":transform}),
    )
    .unwrap();
    assert_preview_commit(&mut engine, &original, &prepared);
    assert_eq!(pixel(&engine, 5, 10), [255, 0, 0, 255]);
    assert_eq!(pixel(&engine, 18, 10), [255, 0, 0, 255]);
    assert_eq!(pixel(&engine, 3, 10), [0; 4]);
    assert_eq!(pixel(&engine, 20, 10), [0; 4]);
    assert_eq!(get(&mut engine, id, object_id)["geometry"], geometry);
    assert!(engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap()
        .is_vector());
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn vector_stack_masks_clipping_and_adjustment_layers_use_the_same_coverage() {
    let mut engine = Engine::new(32, 32).unwrap();
    let id = create(&mut engine);
    add(&mut engine, id, rect(2.0, 2.0, 8.0, 8.0, [255, 0, 0, 255]));
    install_mask(&mut engine, id, 128, true);
    install_mask(&mut engine, id, 128, true);
    assert_eq!(pixel(&engine, 5, 5), [64, 0, 0, 64]);
    command(
        &mut engine,
        json!({"type":"create_vector","name":"Clip","parent_id":null,"index":2}),
    )
    .unwrap();
    let clipped = engine.document.active;
    add(
        &mut engine,
        clipped,
        rect(0.0, 0.0, 16.0, 16.0, [0, 0, 255, 255]),
    );
    command(
        &mut engine,
        json!({"type":"set_clipping","id":clipped,"clipping":true}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 5, 5), [0, 0, 64, 64]);
    assert_eq!(pixel(&engine, 12, 12), [0; 4]);
    command(&mut engine, json!({"type":"create_adjustment","name":"Gray","parent_id":null,"index":3,"settings":{"kind":"tone","brightness":0.0,"contrast":0.0,"saturation":-1.0}})).unwrap();
    let adjusted = pixel(&engine, 5, 5);
    assert_eq!(adjusted[3], 64);
    assert_eq!(adjusted[0], adjusted[1]);
    assert_eq!(adjusted[1], adjusted[2]);
    assert!(adjusted[0] > 0 && adjusted[0] < 64);
    assert_eq!(pixel(&engine, 12, 12), [0; 4]);
}

#[test]
fn pixel_tools_reject_vector_sources_but_mask_painting_remains_editable_and_undoable() {
    let mut engine = Engine::new(32, 32).unwrap();
    let id = create(&mut engine);
    let object_id = add(&mut engine, id, rect(2.0, 2.0, 8.0, 8.0, [255, 0, 0, 255]));
    let spec = get(&mut engine, id, object_id);
    let brush = Brush {
        color: [0; 3],
        size: 1.0,
        opacity: 1.0,
        raster: BrushRaster::Pixel,
        size_pressure: 0.0,
        opacity_pressure: 0.0,
        stabilization: 0.0,
        ..Brush::default()
    };
    for value in [
        json!({"type":"begin","brush":{"size":1.0,"opacity":1.0,"hardness":1.0,"color":[0,0,0],"eraser":false,"raster":"pixel","size_pressure":0.0,"opacity_pressure":0.0,"stabilization":0.0}}),
        json!({"type":"fill","x":5,"y":5,"color":[0,0,0,255],"tolerance":0}),
        json!({"type":"clear"}),
        json!({"type":"blur","sigma":1.0}),
    ] {
        atomic_error(&mut engine, value);
    }
    command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    let mask_id = engine.state()["activeMaskId"].as_u64().unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","id":id,"mask_id":mask_id,"enabled":true}),
    )
    .unwrap();
    let before = engine.save().unwrap();
    atomic_error(
        &mut engine,
        json!({"type":"set_vector_object","id":id,"object_id":object_id,"object":spec}),
    );
    atomic_error(&mut engine, json!({"type":"rasterize_vector","id":id}));
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x: 5.5,
            y: 5.5,
            pressure: 1.0,
        }])
        .unwrap();
    engine.command(Command::End).unwrap();
    assert_eq!(pixel(&engine, 5, 5), [0; 4]);
    assert_eq!(pixel(&engine, 6, 5), [255, 0, 0, 255]);
    assert_eq!(get(&mut engine, id, object_id), spec);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(pixel(&engine, 5, 5), [255, 0, 0, 255]);
    engine.command(Command::Redo).unwrap();
    assert_eq!(pixel(&engine, 5, 5), [0; 4]);
}

#[test]
fn explicit_rasterization_preserves_composite_metadata_masks_and_editable_undo() {
    let mut engine = Engine::new(32, 32).unwrap();
    let id = create(&mut engine);
    let object_id = add(
        &mut engine,
        id,
        rect(2.0, 2.0, 8.0, 8.0, [255, 128, 64, 128]),
    );
    install_mask(&mut engine, id, 128, true);
    command(
        &mut engine,
        json!({"type":"set_layer","id":id,"visible":true,"opacity":0.5,"name":"Keep metadata"}),
    )
    .unwrap();
    let before = engine.save().unwrap();
    let expected = frame(&engine);
    let masks = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap()
        .masks
        .clone();
    command(&mut engine, json!({"type":"rasterize_vector","id":id})).unwrap();
    let layer = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap();
    assert!(!layer.is_vector());
    assert_eq!(layer.name, "Keep metadata");
    assert_eq!(layer.opacity, 0.5);
    assert_eq!(layer.masks, masks);
    assert_frames_equal(
        &frame(&engine),
        &expected,
        engine.document.width,
        engine.document.height,
    );
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    let mut changed = get(&mut engine, id, object_id);
    changed["geometry"]["x"] = json!(18.0);
    set(&mut engine, id, object_id, changed).unwrap();
    assert_eq!(pixel(&engine, 5, 5), [0; 4]);
    assert!(pixel(&engine, 21, 5)[3] > 0);
}

#[test]
fn vector_content_explicitly_rejects_indexed_conversion() {
    let mut engine = Engine::new(16, 16).unwrap();
    let id = create(&mut engine);
    add(&mut engine, id, rect(2.0, 2.0, 4.0, 4.0, [255, 0, 0, 255]));
    atomic_error(
        &mut engine,
        json!({"type":"convert_color_mode","mode":"indexed","palette":{"colors":[[0,0,0,0],[255,0,0,255]],"order":[0,1],"transparent":0}}),
    );
    let mut indexed = Engine::new(16, 16).unwrap();
    command(&mut indexed, json!({"type":"new_indexed","width":16,"height":16,"palette":{"colors":[[0,0,0,0],[255,0,0,255]],"order":[0,1],"transparent":0}})).unwrap();
    atomic_error(
        &mut indexed,
        json!({"type":"create_vector","name":"Vector","parent_id":null,"index":1}),
    );
}

#[test]
fn image_and_canvas_resize_preserve_typed_geometry_and_editable_undo() {
    for (resize, old_point, new_point, edited_point) in [
        (
            json!({"type":"resize_image","width":64,"height":64,"filter":"nearest"}),
            (10, 10),
            (20, 20),
            (36, 20),
        ),
        (
            json!({"type":"resize_canvas","width":48,"height":48,"anchor":8}),
            (10, 10),
            (25, 25),
            (34, 25),
        ),
    ] {
        let mut engine = Engine::new(32, 32).unwrap();
        let id = create(&mut engine);
        let object_id = add(&mut engine, id, rect(8.0, 8.0, 8.0, 8.0, [255, 0, 0, 255]));
        let original = get(&mut engine, id, object_id);
        let before = engine.save().unwrap();
        command(&mut engine, resize).unwrap();
        assert_eq!(
            get(&mut engine, id, object_id)["geometry"],
            original["geometry"]
        );
        assert_eq!(pixel(&engine, old_point.0, old_point.1), [0; 4]);
        assert_eq!(pixel(&engine, new_point.0, new_point.1), [255, 0, 0, 255]);
        let after = engine.save().unwrap();
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), after);
        let mut edited = get(&mut engine, id, object_id);
        edited["geometry"]["x"] = json!(12.0);
        set(&mut engine, id, object_id, edited).unwrap();
        assert_eq!(pixel(&engine, new_point.0, new_point.1), [0; 4]);
        assert_eq!(
            pixel(&engine, edited_point.0, edited_point.1),
            [255, 0, 0, 255]
        );
        assert!(engine
            .document
            .layers
            .iter()
            .find(|layer| layer.id == id)
            .unwrap()
            .is_vector());
    }
}

#[test]
fn real_raster_stroke_frames_keep_lower_and_upper_vector_layers_in_the_composite() {
    let mut engine = Engine::new(32, 32).unwrap();
    let lower = create(&mut engine);
    add(
        &mut engine,
        lower,
        rect(2.0, 2.0, 10.0, 10.0, [255, 0, 0, 255]),
    );
    command(
        &mut engine,
        json!({"type":"move_node","id":lower,"parent_id":null,"index":0}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"create_vector","name":"Upper","parent_id":null,"index":2}),
    )
    .unwrap();
    let upper = engine.document.active;
    add(
        &mut engine,
        upper,
        rect(6.0, 2.0, 6.0, 10.0, [0, 0, 255, 255]),
    );
    command(&mut engine, json!({"type":"select_layer","id":1})).unwrap();
    let original = frame(&engine);
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let brush = Brush {
        color: [0, 255, 0],
        size: 1.0,
        opacity: 1.0,
        raster: BrushRaster::Pixel,
        size_pressure: 0.0,
        opacity_pressure: 0.0,
        stabilization: 0.0,
        ..Brush::default()
    };
    let mut displayed = original.clone();
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    for x in [4.5, 8.5, 16.5] {
        engine
            .samples(&[Sample {
                x,
                y: 4.5,
                pressure: 1.0,
            }])
            .unwrap();
        displayed.extend(tiles(&engine.frame_with_background(true)));
        assert_frames_equal(&displayed, &frame(&engine), 32, 32);
        assert_eq!(at(&displayed, 3, 3), [255, 0, 0, 255]);
        assert_eq!(at(&displayed, 8, 3), [0, 0, 255, 255]);
    }
    assert_eq!(at(&displayed, 4, 4), [0, 255, 0, 255]);
    assert_eq!(at(&displayed, 8, 4), [0, 0, 255, 255]);
    assert_eq!(at(&displayed, 16, 4), [0, 255, 0, 255]);
    engine.command(Command::End).unwrap();
    displayed.extend(tiles(&engine.frame_with_background(true)));
    assert_frames_equal(&displayed, &frame(&engine), 32, 32);
    let painted = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    displayed.extend(tiles(&engine.frame_with_background(true)));
    assert_frames_equal(&displayed, &original, 32, 32);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), painted);
    displayed.extend(tiles(&engine.frame_with_background(true)));
    let pre_cancel = frame(&engine);
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x: 3.5,
            y: 6.5,
            pressure: 1.0,
        }])
        .unwrap();
    displayed.extend(tiles(&engine.frame_with_background(true)));
    assert_eq!(at(&displayed, 3, 6), [0, 255, 0, 255]);
    engine.command(Command::Cancel).unwrap();
    assert_eq!(engine.save().unwrap(), painted);
    displayed.extend(tiles(&engine.frame_with_background(true)));
    assert_frames_equal(&displayed, &pre_cancel, 32, 32);
}

#[test]
fn vector_layer_blend_preview_commits_hand_calculated_colors_and_exact_noops() {
    for (blend, expected) in [
        ("normal", [192, 64, 64, 255]),
        ("multiply", [128, 64, 64, 255]),
        ("screen", [192, 128, 128, 255]),
    ] {
        let mut engine = Engine::new(32, 32).unwrap();
        let lower = create(&mut engine);
        add(
            &mut engine,
            lower,
            rect(2.0, 2.0, 12.0, 12.0, [128, 128, 128, 255]),
        );
        command(
            &mut engine,
            json!({"type":"create_vector","name":"Upper","parent_id":null,"index":2}),
        )
        .unwrap();
        let upper = engine.document.active;
        add(
            &mut engine,
            upper,
            rect(2.0, 2.0, 12.0, 12.0, [255, 0, 0, 255]),
        );
        let source = engine
            .document
            .layers
            .iter()
            .find(|layer| layer.id == upper)
            .unwrap()
            .vector()
            .unwrap()
            .clone();
        engine.frame_with_background(true);
        let before = engine.save().unwrap();
        let state = engine.state();
        let original = frame(&engine);
        let request = json!({"id":upper,"revision":state["revision"],"selection_id":state["selectionId"],"settings":{"kind":"layer_blend","brightness":0.0,"contrast":0.0,"saturation":0.0,"sigma":0.0,"opacity":0.5,"blend":blend}});
        let prepared = engine
            .preview_adjustment(serde_json::from_value(request.clone()).unwrap())
            .unwrap();
        assert_eq!(at(&tiles(&prepared), 6, 6), expected);
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame_with_background(true).len(), 16);
        command(
            &mut engine,
            json!({"type":"apply_adjustment","request":request}),
        )
        .unwrap();
        assert_preview_commit(&mut engine, &original, &prepared);
        assert_eq!(pixel(&engine, 6, 6), expected);
        assert!(Arc::ptr_eq(
            &source,
            engine
                .document
                .layers
                .iter()
                .find(|layer| layer.id == upper)
                .unwrap()
                .vector()
                .unwrap()
        ));
        let after = engine.save().unwrap();
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), after);
        engine.frame_with_background(true);
        let state = engine.state();
        let noop = json!({"id":upper,"revision":state["revision"],"selection_id":state["selectionId"],"settings":{"kind":"layer_blend","brightness":0.0,"contrast":0.0,"saturation":0.0,"sigma":0.0,"opacity":0.5,"blend":blend}});
        assert_eq!(
            engine
                .preview_adjustment(serde_json::from_value(noop.clone()).unwrap())
                .unwrap()
                .len(),
            16
        );
        command(
            &mut engine,
            json!({"type":"apply_adjustment","request":noop}),
        )
        .unwrap();
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), after);
        assert_eq!(engine.frame_with_background(true).len(), 16);
    }
}

#[test]
fn vector_only_groups_and_documents_have_real_previews_thumbnails_visible_copy_and_palettes() {
    for grouped in [false, true] {
        let mut engine = Engine::new(96, 96).unwrap();
        let red = create(&mut engine);
        add(
            &mut engine,
            red,
            rect(12.0, 12.0, 32.0, 72.0, [144, 24, 64, 255]),
        );
        command(
            &mut engine,
            json!({"type":"create_vector","name":"Blue","parent_id":null,"index":2}),
        )
        .unwrap();
        let blue = engine.document.active;
        add(
            &mut engine,
            blue,
            rect(52.0, 12.0, 32.0, 72.0, [32, 160, 192, 128]),
        );
        command(&mut engine, json!({"type":"remove_layer","id":1})).unwrap();
        let mut group_id = None;
        if grouped {
            command(&mut engine,json!({"type":"group_layers","ids":[red,blue],"name":"Vectors only","parent_id":null,"index":0})).unwrap();
            group_id = Some(engine.document.active);
        }
        engine.frame_with_background(true);
        let before = engine.save().unwrap();
        let state = engine.state();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        let packet = loop {
            let bytes = engine.previews().unwrap();
            if !bytes.is_empty() {
                break bytes;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "vector preview worker did not finish"
            );
            std::thread::sleep(std::time::Duration::from_millis(10));
        };
        assert_eq!(
            u64::from_le_bytes(packet[..8].try_into().unwrap()),
            state["revision"].as_u64().unwrap()
        );
        assert_eq!(u32::from_le_bytes(packet[8..12].try_into().unwrap()), 96);
        let count = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
        assert_eq!(count, engine.document.layers.len() + 1);
        assert_eq!(packet.len(), 16 + count * (4 + 96 * 96 * 4));
        let previews: BTreeMap<_, _> = packet[16..]
            .as_chunks::<{ 4 + 96 * 96 * 4 }>()
            .0
            .iter()
            .map(|record| {
                (
                    u32::from_le_bytes(record[..4].try_into().unwrap()),
                    &record[4..],
                )
            })
            .collect();
        let raw = |bytes: &[u8], edge: usize, x: usize, y: usize| -> [u8; 4] {
            bytes[(y * edge + x) * 4..(y * edge + x + 1) * 4]
                .try_into()
                .unwrap()
        };
        for preview_id in std::iter::once(0).chain(group_id) {
            assert_eq!(raw(previews[&preview_id], 96, 24, 48), [144, 24, 64, 255]);
            assert_eq!(raw(previews[&preview_id], 96, 68, 48), [16, 80, 96, 128]);
            assert_eq!(raw(previews[&preview_id], 96, 0, 0), [0; 4]);
        }
        assert_eq!(raw(previews[&red], 96, 24, 48), [144, 24, 64, 255]);
        assert_eq!(raw(previews[&blue], 96, 68, 48), [16, 80, 96, 128]);
        let thumbnail = engine.thumbnail();
        let edge = u32::from_le_bytes(thumbnail[..4].try_into().unwrap()) as usize;
        assert_eq!(edge, 256);
        assert_eq!(thumbnail.len(), 4 + edge * edge * 4);
        assert_eq!(raw(&thumbnail[4..], edge, 64, 128), [144, 24, 64, 255]);
        assert_eq!(raw(&thumbnail[4..], edge, 181, 128), [16, 80, 96, 128]);
        assert_eq!(raw(&thumbnail[4..], edge, 0, 0), [0; 4]);
        let copied = engine.copy_selection(CopyMode::Visible).unwrap();
        let mut reader = png::Decoder::new(&copied[16..]).read_info().unwrap();
        let mut pixels = vec![0; reader.output_buffer_size()];
        let info = reader.next_frame(&mut pixels).unwrap();
        assert_eq!((info.width, info.height), (96, 96));
        assert_eq!(raw(&pixels, 96, 24, 48), [144, 24, 64, 255]);
        let blue_pixel = raw(&pixels, 96, 68, 48);
        assert_eq!(blue_pixel[3], 128);
        for (actual, expected) in blue_pixel[..3].iter().zip([32u8, 160, 192]) {
            assert!(actual.abs_diff(expected) <= 1);
        }
        assert_eq!(raw(&pixels, 96, 0, 0), [0; 4]);
        let colors = engine.extract_palette(12).unwrap();
        assert_eq!(colors.len(), 2);
        assert!(colors.contains(&[144, 24, 64]));
        assert!(colors.iter().any(|color| color
            .iter()
            .zip([32u8, 160, 192])
            .all(|(actual, expected)| actual.abs_diff(expected) <= 1)));
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame_with_background(true).len(), 16);
    }
}

#[test]
fn vector_picking_uses_real_fill_and_stroke_coverage_and_bounded_finite_tolerance() {
    for (spec, inside, outside) in [
        (
            object(
                json!({"kind":"ellipse","cx":16.0,"cy":16.0,"rx":10.0,"ry":10.0}),
                Some([255, 0, 0, 255]),
                Value::Null,
            ),
            (16.0, 16.0),
            (6.0, 6.0),
        ),
        (
            object(
                json!({"kind":"line","x1":4.0,"y1":4.0,"x2":28.0,"y2":28.0}),
                None,
                stroke(2.0, "butt", "miter", 4.0),
            ),
            (16.0, 16.0),
            (10.0, 24.0),
        ),
        (
            object(
                json!({"kind":"path","segments":[{"kind":"move_to","x":2.0,"y":28.0},{"kind":"quad_to","cx":16.0,"cy":0.0,"x":30.0,"y":28.0}]}),
                None,
                stroke(2.0, "round", "round", 4.0),
            ),
            (16.0, 14.0),
            (16.0, 27.0),
        ),
        (
            object(
                json!({"kind":"rect","x":6.0,"y":6.0,"width":20.0,"height":20.0}),
                None,
                stroke(2.0, "butt", "miter", 4.0),
            ),
            (6.0, 16.0),
            (16.0, 16.0),
        ),
    ] {
        let mut engine = Engine::new(32, 32).unwrap();
        let id = create(&mut engine);
        let object_id = add(&mut engine, id, spec);
        engine.frame_with_background(true);
        let before = engine.save().unwrap();
        let state = engine.state();
        for tolerance in [0.0, 0.01, 0.25] {
            let hit = command(&mut engine,json!({"type":"pick_vector_object","id":id,"x":inside.0,"y":inside.1,"tolerance":tolerance})).unwrap();
            assert_eq!(hit["object_id"], object_id);
            let miss = command(&mut engine,json!({"type":"pick_vector_object","id":id,"x":outside.0,"y":outside.1,"tolerance":tolerance})).unwrap();
            assert!(miss["object_id"].is_null());
        }
        assert_eq!(command(&mut engine,json!({"type":"pick_vector_object","id":id,"x":inside.0,"y":inside.1,"tolerance":16.0})).unwrap()["object_id"],object_id);
        for (x, y, tolerance) in [
            (16.0, 16.0, -0.01),
            (16.0, 16.0, 16.01),
            (16.0, 16.0, 1e100),
            (1e100, 16.0, 0.0),
            (16.0, 1e100, 0.0),
        ] {
            atomic_error(
                &mut engine,
                json!({"type":"pick_vector_object","id":id,"x":x,"y":y,"tolerance":tolerance}),
            );
        }
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame_with_background(true).len(), 16);
    }
}
