use podor_engine::{
    animation::{Cel, CelSource},
    model::*,
    Command, Engine, LayerActionRequest,
};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

type Tiles = BTreeMap<TileKey, Vec<u8>>;

fn timeline(kind: &str) -> bool {
    matches!(
        kind,
        "enable_animation"
            | "add_frame"
            | "duplicate_frame"
            | "delete_frame"
            | "reorder_frames"
            | "set_frame_duration"
            | "select_frame"
            | "new_cel"
            | "clear_cel"
            | "link_cel"
            | "unlink_cel"
            | "add_frame_tag"
            | "set_frame_tag"
            | "delete_frame_tag"
            | "undo"
            | "redo"
            | "state"
    )
}

fn envelope(engine: &Engine, mut value: Value) -> Value {
    let state = engine.state();
    if value.get("revision").is_none() {
        value["revision"] = state["revision"].clone();
    }
    if value.get("selection_id").is_none() {
        value["selection_id"] = state["selectionId"].clone();
    }
    if !state["animation"].is_null() && !timeline(value["type"].as_str().unwrap()) {
        if value.get("target_layer_id").is_none() {
            value["target_layer_id"] = state["active"].clone();
        }
        if value.get("frame_id").is_none() {
            value["frame_id"] = state["animation"]["activeFrameId"].clone();
        }
        if value.get("cel_id").is_none() {
            value["cel_id"] = state["animation"]["activeCelId"].clone();
        }
    }
    value
}

fn send(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command_request(serde_json::from_value(envelope(engine, value)).unwrap())
}

fn raw(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command_request(serde_json::from_value(value).unwrap())
}

fn put(layer: &mut Layer, x: u32, y: u32, color: [u8; 4]) {
    let tile = layer
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .entry((x / TILE_SIZE, y / TILE_SIZE))
        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&color);
}

fn reload(engine: &mut Engine) {
    engine.document.assign_mask_ids().unwrap();
    engine.load(&engine.save().unwrap()).unwrap();
}

fn still(width: u32, height: u32) -> Engine {
    let mut engine = Engine::new(width, height).unwrap();
    put(&mut engine.document.layers[0], 2, 3, [255, 0, 0, 255]);
    reload(&mut engine);
    engine
}

fn enable(engine: &mut Engine) -> u32 {
    send(engine, json!({"type":"enable_animation","duration_ms":100})).unwrap();
    active_frame(engine)
}

fn active_frame(engine: &Engine) -> u32 {
    engine.state()["animation"]["activeFrameId"]
        .as_u64()
        .unwrap() as u32
}

fn select(engine: &mut Engine, id: u32) {
    send(engine, json!({"type":"select_frame","frame_id":id})).unwrap();
}

fn add_frame(engine: &mut Engine, index: usize, duration: u32) -> u32 {
    send(
        engine,
        json!({"type":"add_frame","index":index,"duration_ms":duration,"select":true}),
    )
    .unwrap();
    active_frame(engine)
}

fn duplicate(engine: &mut Engine, source: u32, index: usize, linked: bool) -> u32 {
    send(engine,json!({"type":"duplicate_frame","frame_id":source,"index":index,"linked":linked,"select":true})).unwrap();
    active_frame(engine)
}

fn cel_id(engine: &Engine, frame: u32, layer: u32) -> Option<u32> {
    engine
        .document
        .animation
        .as_ref()
        .unwrap()
        .frames
        .iter()
        .find(|candidate| candidate.id == frame)
        .unwrap()
        .exposures
        .get(&layer)
        .copied()
}

fn cel(engine: &Engine, frame: u32, layer: u32) -> &Arc<Cel> {
    let id = cel_id(engine, frame, layer).unwrap();
    &engine.document.animation.as_ref().unwrap().cels[&id]
}

fn plane(engine: &Engine, frame: u32, layer: u32) -> &Arc<RasterPlane> {
    match &cel(engine, frame, layer).source {
        CelSource::Raster(plane) => plane,
        _ => panic!("expected raster source"),
    }
}

fn source_pixel(engine: &Engine, frame: u32, layer: u32, x: u32, y: u32) -> [u8; 4] {
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    plane(engine, frame, layer)
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| tile[offset..offset + 4].try_into().unwrap())
}

fn source_index(engine: &Engine, frame: u32, layer: u32, x: u32, y: u32) -> u8 {
    plane(engine, frame, layer)
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or(
            engine.document.palette.as_ref().unwrap().transparent,
            |tile| tile[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize],
        )
}

fn tiles(packet: &[u8]) -> Tiles {
    assert!(packet.len() >= 16);
    let count = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
    assert_eq!(packet.len(), 16 + count * (8 + TILE_BYTES));
    packet[16..]
        .as_chunks::<{ 8 + TILE_BYTES }>()
        .0
        .iter()
        .map(|record| {
            (
                (
                    u32::from_le_bytes(record[..4].try_into().unwrap()),
                    u32::from_le_bytes(record[4..8].try_into().unwrap()),
                ),
                record[8..].to_vec(),
            )
        })
        .collect()
}

fn render(engine: &Engine, id: u32) -> Tiles {
    let request = serde_json::from_value(json!({"revision":engine.state()["revision"],"frame_id":id,"transparent":true,"region":null})).unwrap();
    tiles(&engine.render_animation_frame(request).unwrap())
}

fn pixel(frame: &Tiles, x: u32, y: u32) -> [u8; 4] {
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    frame
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| tile[offset..offset + 4].try_into().unwrap())
}

fn brush(color: [u8; 3], index: Option<u8>, opacity: f32) -> Value {
    json!({"type":"begin","brush":{"size":1,"opacity":opacity,"hardness":1,
        "color":color,"index":index,"eraser":false,"raster":"pixel",
        "size_pressure":0,"opacity_pressure":0,"stabilization":0}})
}

fn dab(engine: &mut Engine, x: f32, y: f32) {
    engine
        .samples(&[Sample {
            x,
            y,
            pressure: 1.0,
        }])
        .unwrap();
}

fn paint(engine: &mut Engine, x: f32, y: f32, color: [u8; 3]) {
    send(engine, brush(color, None, 1.0)).unwrap();
    dab(engine, x, y);
    send(engine, json!({"type":"end"})).unwrap();
}

fn atomic(engine: &mut Engine, value: Value) {
    engine.frame_with_background(true);
    let saved = engine.save().unwrap();
    let state = engine.state();
    assert!(send(engine, value).is_err());
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

fn raw_atomic(engine: &mut Engine, value: Value) {
    engine.frame_with_background(true);
    let saved = engine.save().unwrap();
    let state = engine.state();
    assert!(raw(engine, value).is_err());
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

fn reveal_mask(engine: &mut Engine) -> u32 {
    send(
        engine,
        json!({"type":"add_mask","mode":"reveal","name":"Coverage"}),
    )
    .unwrap();
    engine.state()["activeMaskId"].as_u64().unwrap() as u32
}

fn leave_mask(engine: &mut Engine) {
    send(engine, json!({"type":"set_mask_editing","enabled":false})).unwrap();
}

fn rect(color: [u8; 4]) -> Value {
    json!({"name":"Editable shape","visible":true,
        "geometry":{"kind":"rect","x":8,"y":6,"width":5,"height":4},
        "transform":[1,0,0,1,0,0],
        "style":{"fill":color,"stroke":null,"fill_rule":"non_zero"}})
}

fn vector_fixture() -> Engine {
    let mut engine = still(32, 24);
    send(
        &mut engine,
        json!({"type":"create_vector","name":"Shapes","parent_id":null,"index":1}),
    )
    .unwrap();
    let id = engine.document.active;
    send(
        &mut engine,
        json!({"type":"add_vector_object","id":id,"object":rect([0,0,255,255]),"index":null}),
    )
    .unwrap();
    reload(&mut engine);
    engine
}

#[test]
fn enabling_animation_preserves_real_sources_masks_and_still_undo() {
    let mut engine = vector_fixture();
    let vector_id = engine.document.active;
    send(&mut engine, json!({"type":"select_layer","id":1})).unwrap();
    let mask_id = reveal_mask(&mut engine);
    leave_mask(&mut engine);
    reload(&mut engine);
    let source = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    let vector = engine.document.layers[1].vector().unwrap().clone();
    let before = engine.save().unwrap();
    assert!(engine.state()["animation"].is_null());
    let first = enable(&mut engine);
    let animation = engine.document.animation.as_ref().unwrap();
    assert_eq!(animation.frames.len(), 1);
    assert_eq!(animation.cels.len(), 2);
    assert_ne!(cel_id(&engine, first, 1), cel_id(&engine, first, vector_id));
    assert!(engine
        .document
        .layers
        .iter()
        .all(|layer| layer.masks.is_empty()));
    assert!(Arc::ptr_eq(
        &source,
        &plane(&engine, first, 1).tiles()[&(0, 0)]
    ));
    assert_eq!(cel(&engine, first, 1).masks[0].id, mask_id);
    let CelSource::Vector(actual) = &cel(&engine, first, vector_id).source else {
        panic!("vector source lost");
    };
    assert!(Arc::ptr_eq(&vector, actual));
    let frame = render(&engine, first);
    assert_eq!(pixel(&frame, 2, 3), [255, 0, 0, 255]);
    assert_eq!(pixel(&frame, 10, 8), [0, 0, 255, 255]);
    let after = engine.save().unwrap();
    assert!(after.starts_with(b"PODOR\x0c"));
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    assert_eq!(engine.state()["canUndo"], false);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
    engine.document.validate().unwrap();
}

#[test]
fn blank_exposures_do_not_hold_previous_source_and_explicit_cels_have_identity() {
    let mut engine = still(16, 12);
    let first = enable(&mut engine);
    let second = add_frame(&mut engine, 1, 70);
    assert_eq!(cel_id(&engine, second, 1), None);
    assert_eq!(engine.state()["layers"][0]["hasCel"], false);
    assert_eq!(pixel(&render(&engine, second), 2, 3), [0; 4]);
    assert_eq!(pixel(&render(&engine, first), 2, 3), [255, 0, 0, 255]);
    let before = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"new_cel","frame_id":second,"layer_id":1}),
    )
    .unwrap();
    let created = cel_id(&engine, second, 1).unwrap();
    assert_ne!(created, cel_id(&engine, first, 1).unwrap());
    assert_eq!(engine.state()["layers"][0]["hasCel"], true);
    assert!(plane(&engine, second, 1).tiles().is_empty());
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
}

#[test]
fn canceled_and_noop_strokes_on_blank_tracks_preserve_counters_and_history() {
    let mut engine = still(16, 12);
    enable(&mut engine);
    let second = add_frame(&mut engine, 1, 100);
    let before = engine.save().unwrap();
    let state = engine.state();
    let counters = (
        engine.document.animation.as_ref().unwrap().next_cel_id,
        engine.document.next_mask_id,
    );
    send(&mut engine, brush([0, 0, 255], None, 1.0)).unwrap();
    dab(&mut engine, 5.5, 6.5);
    send(&mut engine, json!({"type":"cancel"})).unwrap();
    assert!(engine.save().unwrap() == before);
    assert_eq!(engine.state(), state);
    send(&mut engine, brush([0, 0, 255], None, 0.0)).unwrap();
    dab(&mut engine, 5.5, 6.5);
    send(&mut engine, json!({"type":"end"})).unwrap();
    assert!(engine.save().unwrap() == before);
    assert_eq!(engine.state(), state);
    assert_eq!(cel_id(&engine, second, 1), None);
    paint(&mut engine, 5.5, 6.5, [0, 0, 255]);
    assert!(cel_id(&engine, second, 1).is_some());
    assert_eq!(
        engine.document.animation.as_ref().unwrap().next_cel_id,
        counters.0 + 1
    );
    assert_eq!(engine.document.next_mask_id, counters.1);
    assert_eq!(source_pixel(&engine, second, 1, 5, 6), [0, 0, 255, 255]);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
}

#[test]
fn independent_duplicates_share_buffers_then_copy_on_write_with_new_mask_ids() {
    let mut engine = still(16, 12);
    let mask = reveal_mask(&mut engine);
    leave_mask(&mut engine);
    reload(&mut engine);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, false);
    assert_ne!(cel_id(&engine, first, 1), cel_id(&engine, second, 1));
    assert!(Arc::ptr_eq(
        plane(&engine, first, 1),
        plane(&engine, second, 1)
    ));
    assert_ne!(cel(&engine, second, 1).masks[0].id, mask);
    let before = engine.save().unwrap();
    paint(&mut engine, 2.5, 3.5, [0, 0, 255]);
    assert_eq!(source_pixel(&engine, first, 1, 2, 3), [255, 0, 0, 255]);
    assert_eq!(source_pixel(&engine, second, 1, 2, 3), [0, 0, 255, 255]);
    assert!(!Arc::ptr_eq(
        plane(&engine, first, 1),
        plane(&engine, second, 1)
    ));
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    assert!(Arc::ptr_eq(
        plane(&engine, first, 1),
        plane(&engine, second, 1)
    ));
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
    engine.document.validate().unwrap();
}

#[test]
fn linked_edits_affect_all_exposures_and_unlink_is_one_real_cow_transaction() {
    let mut engine = still(16, 12);
    reveal_mask(&mut engine);
    leave_mask(&mut engine);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, true);
    let linked = cel_id(&engine, first, 1).unwrap();
    assert_eq!(cel_id(&engine, second, 1), Some(linked));
    paint(&mut engine, 2.5, 3.5, [0, 255, 0]);
    for id in [first, second] {
        assert_eq!(pixel(&render(&engine, id), 2, 3), [0, 255, 0, 255]);
    }
    let before = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"unlink_cel","frame_id":second,"layer_id":1,"cel_id":linked}),
    )
    .unwrap();
    let independent = cel_id(&engine, second, 1).unwrap();
    assert_ne!(independent, linked);
    assert_ne!(
        cel(&engine, first, 1).masks[0].id,
        cel(&engine, second, 1).masks[0].id
    );
    assert!(Arc::ptr_eq(
        plane(&engine, first, 1),
        plane(&engine, second, 1)
    ));
    let unlinked = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == unlinked);
    paint(&mut engine, 2.5, 3.5, [0, 0, 255]);
    assert_eq!(pixel(&render(&engine, first), 2, 3), [0, 255, 0, 255]);
    assert_eq!(pixel(&render(&engine, second), 2, 3), [0, 0, 255, 255]);
}

#[test]
fn cel_masks_edit_logical_cel_without_overwriting_pixels_or_other_frame_masks() {
    let mut engine = still(16, 12);
    reveal_mask(&mut engine);
    leave_mask(&mut engine);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, false);
    let mask = cel(&engine, second, 1).masks[0].id;
    let source = plane(&engine, second, 1).tiles()[&(0, 0)].clone();
    send(
        &mut engine,
        json!({"type":"set_mask_editing","id":1,"mask_id":mask,"enabled":true}),
    )
    .unwrap();
    let before = engine.save().unwrap();
    paint(&mut engine, 2.5, 3.5, [0; 3]);
    assert_eq!(cel(&engine, second, 1).masks[0].plane.sample(2, 3), 0);
    assert_eq!(cel(&engine, first, 1).masks[0].plane.sample(2, 3), 255);
    assert!(Arc::ptr_eq(
        &source,
        &plane(&engine, second, 1).tiles()[&(0, 0)]
    ));
    assert_eq!(pixel(&render(&engine, second), 2, 3), [0; 4]);
    assert_eq!(pixel(&render(&engine, first), 2, 3), [255, 0, 0, 255]);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
}

#[test]
fn clearing_one_linked_exposure_preserves_other_frames_and_reclaims_only_orphans() {
    let mut engine = still(16, 12);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, true);
    let id = cel_id(&engine, first, 1).unwrap();
    let before = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"clear_cel","frame_id":second,"layer_id":1,"cel_id":id}),
    )
    .unwrap();
    assert_eq!(cel_id(&engine, second, 1), None);
    assert!(engine
        .document
        .animation
        .as_ref()
        .unwrap()
        .cels
        .contains_key(&id));
    assert_eq!(pixel(&render(&engine, first), 2, 3), [255, 0, 0, 255]);
    assert_eq!(pixel(&render(&engine, second), 2, 3), [0; 4]);
    atomic(
        &mut engine,
        json!({"type":"clear_cel","frame_id":second,"layer_id":1,"cel_id":id}),
    );
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    select(&mut engine, first);
    send(
        &mut engine,
        json!({"type":"clear_cel","frame_id":first,"layer_id":1,"cel_id":id}),
    )
    .unwrap();
    select(&mut engine, second);
    send(
        &mut engine,
        json!({"type":"clear_cel","frame_id":second,"layer_id":1,"cel_id":id}),
    )
    .unwrap();
    assert!(!engine
        .document
        .animation
        .as_ref()
        .unwrap()
        .cels
        .contains_key(&id));
    engine.document.validate().unwrap();
}

#[test]
fn vector_objects_remain_editable_per_cel_and_rasterize_converts_the_entire_track() {
    let mut engine = vector_fixture();
    let layer = engine.document.active;
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, false);
    send(
        &mut engine,
        json!({"type":"set_vector_object","id":layer,"object_id":1,"object":rect([0,255,0,255])}),
    )
    .unwrap();
    assert_eq!(pixel(&render(&engine, first), 10, 8), [0, 0, 255, 255]);
    assert_eq!(pixel(&render(&engine, second), 10, 8), [0, 255, 0, 255]);
    for frame in [first, second] {
        let CelSource::Vector(source) = &cel(&engine, frame, layer).source else {
            panic!("expected real vector Cel");
        };
        assert_eq!(source.objects[0].id, 1);
    }
    let before = engine.save().unwrap();
    send(&mut engine, json!({"type":"rasterize_vector","id":layer})).unwrap();
    for frame in [first, second] {
        assert!(matches!(
            cel(&engine, frame, layer).source,
            CelSource::Raster(_)
        ));
    }
    assert_eq!(pixel(&render(&engine, first), 10, 8), [0, 0, 255, 255]);
    assert_eq!(pixel(&render(&engine, second), 10, 8), [0, 255, 0, 255]);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
    engine.document.validate().unwrap();
}

fn indexed_fixture() -> Engine {
    let mut engine = Engine::new(16, 12).unwrap();
    send(&mut engine,json!({"type":"new_indexed","width":16,"height":12,
        "palette":{"colors":[[0,0,0,0],[220,30,70,255],[220,30,70,255],[30,80,120,0]],"transparent":0,"order":[0,1,2,3]}})).unwrap();
    let mut tile = vec![0; INDEX_TILE_BYTES];
    tile[(3 * TILE_SIZE + 2) as usize] = 1;
    tile[(3 * TILE_SIZE + 4) as usize] = 3;
    engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new(tile));
    reload(&mut engine);
    engine
}

#[test]
fn indexed_cels_preserve_slot_identity_and_palette_edits_reveal_inactive_transparency() {
    let mut engine = indexed_fixture();
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, false);
    send(&mut engine, brush([220, 30, 70], Some(2), 1.0)).unwrap();
    dab(&mut engine, 2.5, 3.5);
    send(&mut engine, json!({"type":"end"})).unwrap();
    assert_eq!(source_index(&engine, first, 1, 2, 3), 1);
    assert_eq!(source_index(&engine, second, 1, 2, 3), 2);
    send(
        &mut engine,
        json!({"type":"set_palette_color","index":2,"color":[40,110,210,255]}),
    )
    .unwrap();
    assert_eq!(pixel(&render(&engine, first), 2, 3), [220, 30, 70, 255]);
    assert_eq!(pixel(&render(&engine, second), 2, 3), [40, 110, 210, 255]);
    send(
        &mut engine,
        json!({"type":"set_palette_color","index":3,"color":[30,80,120,255]}),
    )
    .unwrap();
    for frame in [first, second] {
        assert_eq!(source_index(&engine, frame, 1, 4, 3), 3);
        assert_eq!(pixel(&render(&engine, frame), 4, 3), [30, 80, 120, 255]);
    }
    let before = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"remove_palette_color","index":2,"replacement":1}),
    )
    .unwrap();
    assert_eq!(source_index(&engine, second, 1, 2, 3), 1);
    for frame in [first, second] {
        assert_eq!(source_index(&engine, frame, 1, 4, 3), 2);
    }
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    send(
        &mut engine,
        json!({"type":"convert_color_mode","mode":"rgba"}),
    )
    .unwrap();
    assert!(engine.document.palette.is_none());
    assert_eq!(source_pixel(&engine, first, 1, 2, 3), [220, 30, 70, 255]);
    assert_eq!(source_pixel(&engine, second, 1, 2, 3), [40, 110, 210, 255]);
    engine.document.validate().unwrap();
}

#[test]
fn timeline_identity_duration_order_and_tag_anchor_edits_are_one_undo_each() {
    let mut engine = still(16, 12);
    let first = enable(&mut engine);
    let second = add_frame(&mut engine, 1, 80);
    let third = add_frame(&mut engine, 2, 120);
    let tag = json!({"name":"Walk","from_frame":first,"to_frame":third,
        "direction":"ping_pong","repeat":2,"color":[140,110,240,255]});
    send(&mut engine, json!({"type":"add_frame_tag","tag":tag})).unwrap();
    let tag_id = engine.state()["animation"]["tags"][0]["id"].clone();
    let before = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"set_frame_duration","frame_id":second,"duration_ms":1}),
    )
    .unwrap();
    assert_eq!(engine.state()["animation"]["frames"][1]["durationMs"], 1);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
    send(
        &mut engine,
        json!({"type":"reorder_frames","ids":[third,second,first]}),
    )
    .unwrap();
    let animation = &engine.state()["animation"];
    assert_eq!(animation["frames"][0]["id"], third);
    assert_eq!(animation["tags"][0]["fromFrame"], third);
    assert_eq!(animation["tags"][0]["toFrame"], first);
    assert_eq!(animation["tags"][0]["id"], tag_id);
    send(&mut engine, json!({"type":"delete_frame","frame_id":third})).unwrap();
    assert_eq!(engine.state()["animation"]["tags"][0]["fromFrame"], second);
    assert_eq!(engine.state()["animation"]["tags"][0]["toFrame"], first);
    send(&mut engine,json!({"type":"set_frame_tag","id":tag_id,"tag":{
        "name":"Still","from_frame":first,"to_frame":first,"direction":"reverse","repeat":1,"color":[10,20,30,255]}})).unwrap();
    let before_delete = engine.save().unwrap();
    send(&mut engine, json!({"type":"delete_frame_tag","id":tag_id})).unwrap();
    assert!(engine.state()["animation"]["tags"]
        .as_array()
        .unwrap()
        .is_empty());
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before_delete);
    engine.document.validate().unwrap();
}

#[test]
fn invalid_timeline_definitions_and_stale_revisions_are_atomic() {
    let mut engine = still(16, 12);
    let first = enable(&mut engine);
    for request in [
        json!({"type":"delete_frame","frame_id":first}),
        json!({"type":"add_frame","index":2,"duration_ms":100}),
        json!({"type":"add_frame","index":1,"duration_ms":0}),
        json!({"type":"set_frame_duration","frame_id":first,"duration_ms":60001}),
        json!({"type":"select_frame","frame_id":999}),
        json!({"type":"reorder_frames","ids":[first,first]}),
        json!({"type":"reorder_frames","ids":[]}),
        json!({"type":"duplicate_frame","frame_id":999,"index":1,"linked":false}),
        json!({"type":"add_frame_tag","tag":{"name":"Invalid","from_frame":first,"to_frame":999,"direction":"forward","repeat":0,"color":[0,0,0,255]}}),
        json!({"type":"set_frame_duration","frame_id":first,"duration_ms":90,"revision":0}),
    ] {
        atomic(&mut engine, request);
    }
}

#[test]
fn selecting_frames_is_runtime_only_clears_selection_and_invalidates_drafts() {
    let mut engine = still(256, 12);
    let first = enable(&mut engine);
    let second = add_frame(&mut engine, 1, 100);
    paint(&mut engine, 140.5, 3.5, [0, 0, 255]);
    select(&mut engine, first);
    send(
        &mut engine,
        json!({"type":"select","rect":{"left":1,"top":1,"right":5,"bottom":5}}),
    )
    .unwrap();
    let state = engine.state();
    let request: LayerActionRequest = serde_json::from_value(json!({"id":1,
        "revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":false,
        "frame_id":first,"cel_id":cel_id(&engine,first,1),"target_layer_id":1,"action":{"kind":"translate","dx":1,"dy":0}})).unwrap();
    assert!(engine.preview_layer_action(request).is_ok());
    select(&mut engine, second);
    let after = engine.state();
    assert_eq!(after["contentId"], state["contentId"]);
    assert_eq!(after["canUndo"], state["canUndo"]);
    assert_eq!(after["canRedo"], state["canRedo"]);
    assert!(after["revision"].as_u64().unwrap() > state["revision"].as_u64().unwrap());
    assert!(after["selectionId"].as_u64().unwrap() > state["selectionId"].as_u64().unwrap());
    assert!(after["selection"].is_null());
    let stale: LayerActionRequest = serde_json::from_value(json!({"id":1,
        "revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":false,
        "frame_id":first,"cel_id":cel_id(&engine,first,1),"target_layer_id":1,"action":{"kind":"translate","dx":1,"dy":0}})).unwrap();
    assert!(engine.preview_layer_action(stale).is_err());
    select(&mut engine, second);
    assert_eq!(engine.state(), after);
    assert_eq!(pixel(&render(&engine, first), 2, 3), [255, 0, 0, 255]);
    assert_eq!(pixel(&render(&engine, second), 140, 3), [0, 0, 255, 255]);
}

#[test]
fn frame_switch_packets_clear_old_tiles_and_do_not_leave_previous_frame_pixels() {
    let mut engine = still(256, 12);
    let first = enable(&mut engine);
    let second = add_frame(&mut engine, 1, 100);
    paint(&mut engine, 140.5, 3.5, [0, 0, 255]);
    select(&mut engine, first);
    let mut merged = tiles(&engine.frame_with_background(true));
    assert_eq!(pixel(&merged, 2, 3), [255, 0, 0, 255]);
    select(&mut engine, second);
    let delta = tiles(&engine.frame_with_background(true));
    assert!(delta.contains_key(&(0, 0)));
    assert!(delta.contains_key(&(1, 0)));
    merged.extend(delta);
    assert_eq!(pixel(&merged, 2, 3), [0; 4]);
    assert_eq!(pixel(&merged, 140, 3), [0, 0, 255, 255]);
    let expected = render(&engine, second);
    for y in 0..12 {
        for x in 0..256 {
            assert_eq!(pixel(&merged, x, y), pixel(&expected, x, y), "at {x},{y}");
        }
    }
}

#[test]
fn readonly_frame_render_matches_selected_composite_without_dirty_or_state_changes() {
    let mut engine = still(256, 12);
    let first = enable(&mut engine);
    let second = add_frame(&mut engine, 1, 100);
    paint(&mut engine, 140.5, 3.5, [0, 0, 255]);
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let state = engine.state();
    let original = render(&engine, first);
    assert_eq!(pixel(&original, 2, 3), [255, 0, 0, 255]);
    assert_eq!(pixel(&original, 140, 3), [0; 4]);
    assert_eq!(pixel(&render(&engine, second), 140, 3), [0, 0, 255, 255]);
    let request = serde_json::from_value(
        json!({"revision":state["revision"],"frame_id":second,"transparent":true,
        "region":{"left":128,"top":0,"right":256,"bottom":12}}),
    )
    .unwrap();
    let region = tiles(&engine.render_animation_frame(request).unwrap());
    assert!(region.keys().all(|key| *key == (1, 0)));
    assert_eq!(pixel(&region, 140, 3), [0, 0, 255, 255]);
    assert!(engine.save().unwrap() == before);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    for request in [
        json!({"revision":0,"frame_id":first,"transparent":true}),
        json!({"revision":state["revision"],"frame_id":999,"transparent":true}),
    ] {
        assert!(engine
            .render_animation_frame(serde_json::from_value(request).unwrap())
            .is_err());
    }
    select(&mut engine, first);
    let selected = tiles(&engine.frame_with_background(true));
    for y in 0..12 {
        for x in 0..256 {
            assert_eq!(pixel(&selected, x, y), pixel(&original, x, y), "at {x},{y}");
        }
    }
}

#[test]
fn animated_command_targets_require_matching_frame_cel_layer_and_revision() {
    let mut engine = still(16, 12);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, false);
    let revision = engine.state()["revision"].clone();
    let current = cel_id(&engine, second, 1);
    for request in [
        json!({"type":"clear","revision":revision}),
        json!({"type":"clear","revision":revision,"frame_id":second,"target_layer_id":1}),
        json!({"type":"clear","revision":revision,"frame_id":first,"cel_id":cel_id(&engine,first,1),"target_layer_id":1}),
        json!({"type":"clear","revision":revision,"frame_id":second,"cel_id":cel_id(&engine,first,1),"target_layer_id":1}),
        json!({"type":"clear","revision":revision,"frame_id":second,"cel_id":null,"target_layer_id":1}),
        json!({"type":"clear","revision":revision,"frame_id":second,"cel_id":current}),
        json!({"type":"clear","revision":revision,"frame_id":second,"cel_id":current,"target_layer_id":999}),
        json!({"type":"clear","revision":0,"frame_id":second,"cel_id":current,"target_layer_id":1}),
    ] {
        raw_atomic(&mut engine, request);
    }
    send(&mut engine, brush([0, 0, 255], None, 1.0)).unwrap();
    atomic(&mut engine, json!({"type":"select_frame","frame_id":first}));
    atomic(
        &mut engine,
        json!({"type":"delete_frame","frame_id":second}),
    );
    dab(&mut engine, 2.5, 3.5);
    send(&mut engine, json!({"type":"cancel"})).unwrap();
    assert_eq!(source_pixel(&engine, first, 1, 2, 3), [255, 0, 0, 255]);
    assert_eq!(source_pixel(&engine, second, 1, 2, 3), [255, 0, 0, 255]);
}

#[test]
fn image_and_canvas_resize_transform_inactive_cels_masks_and_linked_sources_once() {
    let mut engine = still(16, 12);
    reveal_mask(&mut engine);
    leave_mask(&mut engine);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, false);
    paint(&mut engine, 5.5, 6.5, [0, 0, 255]);
    let third = duplicate(&mut engine, second, 2, true);
    let before = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"resize_image","width":32,"height":24,"filter":"nearest"}),
    )
    .unwrap();
    for frame in [first, second, third] {
        assert_eq!(source_pixel(&engine, frame, 1, 4, 6), [255, 0, 0, 255]);
        assert_eq!(cel(&engine, frame, 1).masks[0].plane.bounds.right, 32);
        assert_eq!(cel(&engine, frame, 1).masks[0].plane.bounds.bottom, 24);
    }
    assert_eq!(source_pixel(&engine, first, 1, 10, 12), [0; 4]);
    assert_eq!(source_pixel(&engine, second, 1, 10, 12), [0, 0, 255, 255]);
    assert_eq!(source_pixel(&engine, third, 1, 10, 12), [0, 0, 255, 255]);
    assert_eq!(cel_id(&engine, second, 1), cel_id(&engine, third, 1));
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
    send(
        &mut engine,
        json!({"type":"resize_canvas","width":34,"height":26,"anchor":4}),
    )
    .unwrap();
    for frame in [first, second, third] {
        assert_eq!(source_pixel(&engine, frame, 1, 5, 7), [255, 0, 0, 255]);
    }
    engine.document.validate().unwrap();
}

#[test]
fn isolated_group_and_global_masks_composite_each_frame_and_group_moves_all_cels() {
    let mut engine = Engine::new(16, 12).unwrap();
    let mut group = Layer::group(2, "Global group".into(), GroupIsolation::Isolated);
    group.opacity = 0.5;
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 16,
            bottom: 12,
        },
        255,
    );
    mask.tiles
        .insert((0, 0), Arc::new(vec![128; MASK_TILE_BYTES]));
    group.masks = vec![MaskEntry {
        id: 0,
        name: "Global coverage".into(),
        plane: mask,
    }];
    let mut child = Layer::new(3, "Paint".into());
    child.parent_id = Some(2);
    put(&mut child, 2, 3, [255, 0, 0, 255]);
    engine.document.layers.extend([group, child]);
    engine.document.next_id = 4;
    engine.document.active = 3;
    reload(&mut engine);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, false);
    paint(&mut engine, 2.5, 3.5, [0, 0, 255]);
    assert_eq!(pixel(&render(&engine, first), 2, 3), [64, 0, 0, 64]);
    assert_eq!(pixel(&render(&engine, second), 2, 3), [0, 0, 64, 64]);
    send(&mut engine, json!({"type":"select_layer","id":2})).unwrap();
    assert_eq!(engine.state()["animation"]["activeCelId"], Value::Null);
    let before = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"translate_layer","id":2,"dx":2,"dy":1}),
    )
    .unwrap();
    assert_eq!(source_pixel(&engine, first, 3, 4, 4), [255, 0, 0, 255]);
    assert_eq!(source_pixel(&engine, second, 3, 4, 4), [0, 0, 255, 255]);
    let actual = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == 2)
        .unwrap();
    assert_eq!(actual.masks[0].plane.bounds.left, 2);
    assert_eq!(actual.masks[0].plane.bounds.top, 1);
    assert_eq!(pixel(&render(&engine, first), 4, 4), [64, 0, 0, 64]);
    assert_eq!(pixel(&render(&engine, second), 4, 4), [0, 0, 64, 64]);
    assert_eq!(pixel(&render(&engine, first), 2, 3), [0; 4]);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
}

#[test]
fn animation_resources_include_inactive_sources_and_deduplicate_shared_allocations() {
    let mut engine = still(256, 12);
    let first = enable(&mut engine);
    let original = plane(&engine, first, 1).tiles()[&(0, 0)].clone();
    let pointer = Arc::as_ptr(&original) as usize;
    let second = duplicate(&mut engine, first, 1, false);
    let third = duplicate(&mut engine, first, 2, true);
    assert!(Arc::ptr_eq(
        plane(&engine, first, 1),
        plane(&engine, second, 1)
    ));
    let unique: BTreeMap<_, _> = engine.document.resources().collect();
    assert_eq!(unique.get(&pointer), Some(&TILE_BYTES));
    assert!(engine.document.pixel_bytes() >= TILE_BYTES);
    assert!(engine.document.pixel_bytes() < 2 * TILE_BYTES);
    select(&mut engine, second);
    paint(&mut engine, 140.5, 3.5, [0, 0, 255]);
    select(&mut engine, third);
    assert_eq!(source_pixel(&engine, first, 1, 140, 3), [0; 4]);
    assert_eq!(source_pixel(&engine, second, 1, 140, 3), [0, 0, 255, 255]);
    assert!(Arc::ptr_eq(
        &original,
        &plane(&engine, second, 1).tiles()[&(0, 0)]
    ));
    let inactive_pointer = Arc::as_ptr(&plane(&engine, second, 1).tiles()[&(1, 0)]) as usize;
    assert!(engine
        .document
        .resources()
        .any(|(ptr, bytes)| ptr == inactive_pointer && bytes == TILE_BYTES));
    assert!(engine.document.pixel_bytes() >= 2 * TILE_BYTES);
    assert!(engine.document.pixel_bytes() < 3 * TILE_BYTES);
    assert!(engine
        .document
        .resources()
        .any(|(ptr, bytes)| ptr == pointer && bytes == TILE_BYTES));
    engine.document.validate().unwrap();
}

#[test]
fn frame_limit_and_invalid_inactive_bindings_reject_without_partial_transactions() {
    let mut engine = Engine::new(1, 1).unwrap();
    enable(&mut engine);
    for index in 1..256 {
        send(
            &mut engine,
            json!({"type":"add_frame","index":index,"duration_ms":1,"select":false}),
        )
        .unwrap();
    }
    assert_eq!(
        engine.state()["animation"]["frames"]
            .as_array()
            .unwrap()
            .len(),
        256
    );
    atomic(
        &mut engine,
        json!({"type":"add_frame","index":256,"duration_ms":1,"select":false}),
    );
    let mut invalid = engine.document.clone();
    let animation = Arc::make_mut(invalid.animation.as_mut().unwrap());
    animation.frames[255].exposures.insert(1, 9999);
    assert!(invalid.validate().is_err());
    assert_eq!(
        engine.document.animation.as_ref().unwrap().frames[255]
            .exposures
            .len(),
        0
    );
    engine.document.validate().unwrap();
}

#[test]
fn duration_and_tag_metadata_preserve_current_frame_packets_and_source_allocations() {
    let mut engine = still(16, 12);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, true);
    engine.frame_with_background(true);
    let source = plane(&engine, first, 1).clone();
    let before = engine.state();
    send(
        &mut engine,
        json!({"type":"set_frame_duration","frame_id":first,"duration_ms":80}),
    )
    .unwrap();
    assert!(engine.state()["contentId"].as_u64().unwrap() > before["contentId"].as_u64().unwrap());
    assert_eq!(engine.frame_with_background(true).len(), 16);
    assert!(Arc::ptr_eq(&source, plane(&engine, second, 1)));
    send(
        &mut engine,
        json!({"type":"add_frame_tag","tag":{"name":"Idle","from_frame":first,"to_frame":second,
        "direction":"ping_pong_reverse","repeat":0,"color":[40,80,120,255]}}),
    )
    .unwrap();
    assert_eq!(engine.frame_with_background(true).len(), 16);
    assert!(Arc::ptr_eq(&source, plane(&engine, first, 1)));
    assert!(Arc::ptr_eq(&source, plane(&engine, second, 1)));
    let state = engine.state();
    let saved = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"set_frame_duration","frame_id":first,"duration_ms":80}),
    )
    .unwrap();
    assert_eq!(engine.state(), state);
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

#[test]
fn clipping_and_adjustment_use_each_frames_actual_alpha_gate_and_backdrop() {
    let mut engine = still(16, 12);
    let mut clipped = Layer::new(2, "Clipped blue".into());
    clipped.clipping = true;
    put(&mut clipped, 2, 3, [0, 0, 255, 255]);
    put(&mut clipped, 5, 3, [0, 0, 255, 255]);
    let spec: podor_engine::AdjustmentSpec = serde_json::from_value(json!({"kind":"curves",
        "curves":{"rgb":{"points":[{"x":0,"y":255},{"x":255,"y":0}]}}}))
    .unwrap();
    let mut adjustment = Layer::new(3, "Global inversion".into());
    adjustment.content = LayerContent::Adjustment {
        settings: spec.into(),
    };
    engine.document.layers.extend([clipped, adjustment]);
    engine.document.next_id = 4;
    reload(&mut engine);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, false);
    send(&mut engine, json!({"type":"clear"})).unwrap();
    paint(&mut engine, 5.5, 3.5, [255, 0, 0]);
    assert_eq!(pixel(&render(&engine, first), 2, 3), [255, 255, 0, 255]);
    assert_eq!(pixel(&render(&engine, first), 5, 3), [0; 4]);
    assert_eq!(pixel(&render(&engine, second), 2, 3), [0; 4]);
    assert_eq!(pixel(&render(&engine, second), 5, 3), [255, 255, 0, 255]);
    engine.document.validate().unwrap();
}

#[test]
fn source_budget_counts_large_inactive_cels_and_rejects_overbudget_candidates() {
    let mut engine = Engine::new(8192, 2048).unwrap();
    let first = enable(&mut engine);
    let second = add_frame(&mut engine, 1, 100);
    send(
        &mut engine,
        json!({"type":"new_cel","frame_id":second,"layer_id":1}),
    )
    .unwrap();
    let third = add_frame(&mut engine, 2, 100);
    send(
        &mut engine,
        json!({"type":"new_cel","frame_id":third,"layer_id":1}),
    )
    .unwrap();
    select(&mut engine, first);
    let saved = engine.save().unwrap();
    let state = engine.state();
    let mut candidate = engine.document.clone();
    assert_eq!(2 * 1024 * TILE_BYTES, MAX_DOCUMENT_BYTES);
    for frame in [second, third] {
        let id = cel_id(&engine, frame, 1).unwrap();
        let mut data = BTreeMap::new();
        for index in 0..1024 {
            let mut tile = vec![0; TILE_BYTES];
            tile[..4].copy_from_slice(&[1, 0, 0, 1]);
            data.insert((index % 64, index / 64), Arc::new(tile));
        }
        let animation = Arc::make_mut(candidate.animation.as_mut().unwrap());
        Arc::make_mut(animation.cels.get_mut(&id).unwrap()).source =
            CelSource::Raster(Arc::new(RasterPlane::Rgba(data)));
    }
    assert!(candidate.pixel_bytes() > MAX_DOCUMENT_BYTES);
    assert!(candidate.validate().is_err());
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.state(), state);
    engine.document.validate().unwrap();
}

#[test]
fn explicit_link_replaces_only_addressed_exposure_and_reclaims_the_old_cel() {
    let mut engine = still(16, 12);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, false);
    let independent = cel_id(&engine, second, 1).unwrap();
    let original = cel_id(&engine, first, 1).unwrap();
    paint(&mut engine, 2.5, 3.5, [0, 0, 255]);
    let before = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"link_cel","frame_id":second,"layer_id":1,"source_frame_id":first}),
    )
    .unwrap();
    assert_eq!(cel_id(&engine, second, 1), Some(original));
    assert!(!engine
        .document
        .animation
        .as_ref()
        .unwrap()
        .cels
        .contains_key(&independent));
    assert_eq!(pixel(&render(&engine, second), 2, 3), [255, 0, 0, 255]);
    let linked = engine.save().unwrap();
    let state = engine.state();
    send(
        &mut engine,
        json!({"type":"link_cel","frame_id":second,"layer_id":1,"source_frame_id":first}),
    )
    .unwrap();
    assert_eq!(engine.state(), state);
    assert!(engine.save().unwrap() == linked);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    assert_eq!(pixel(&render(&engine, second), 2, 3), [0, 0, 255, 255]);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == linked);
}

#[test]
fn duplicating_a_track_preserves_internal_link_topology_without_linking_to_the_source() {
    let mut engine = still(16, 12);
    reveal_mask(&mut engine);
    leave_mask(&mut engine);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, true);
    let third = duplicate(&mut engine, first, 2, false);
    paint(&mut engine, 2.5, 3.5, [0, 0, 255]);
    let before = engine.save().unwrap();
    send(&mut engine, json!({"type":"duplicate_layer","id":1})).unwrap();
    let layer = engine.document.active;
    assert_ne!(layer, 1);
    assert_eq!(
        cel_id(&engine, first, layer),
        cel_id(&engine, second, layer)
    );
    assert_ne!(cel_id(&engine, first, layer), cel_id(&engine, first, 1));
    assert_ne!(cel_id(&engine, third, layer), cel_id(&engine, first, layer));
    for frame in [first, second, third] {
        assert_ne!(
            cel(&engine, frame, layer).masks[0].id,
            cel(&engine, frame, 1).masks[0].id
        );
        assert_eq!(
            source_pixel(&engine, frame, layer, 2, 3),
            source_pixel(&engine, frame, 1, 2, 3)
        );
    }
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
    select(&mut engine, first);
    paint(&mut engine, 2.5, 3.5, [0, 255, 0]);
    assert_eq!(source_pixel(&engine, first, layer, 2, 3), [0, 255, 0, 255]);
    assert_eq!(source_pixel(&engine, second, layer, 2, 3), [0, 255, 0, 255]);
    assert_eq!(source_pixel(&engine, first, 1, 2, 3), [255, 0, 0, 255]);
    assert_eq!(source_pixel(&engine, third, layer, 2, 3), [0, 0, 255, 255]);
    engine.document.validate().unwrap();
}

fn blank_tracks() -> (Engine, u32, u32) {
    let mut engine = Engine::new(16, 12).unwrap();
    send(&mut engine, json!({"type":"add_layer"})).unwrap();
    let second_layer = engine.document.active;
    reload(&mut engine);
    enable(&mut engine);
    let frame = add_frame(&mut engine, 1, 100);
    send(&mut engine, json!({"type":"select_layer","id":1})).unwrap();
    assert_eq!(cel_id(&engine, frame, 1), None);
    assert_eq!(cel_id(&engine, frame, second_layer), None);
    (engine, frame, second_layer)
}

#[test]
fn changing_between_blank_tracks_rejects_captured_begin_with_identical_frame_cel_revision() {
    let (mut engine, frame, second_layer) = blank_tracks();
    let captured = envelope(&engine, brush([0, 0, 255], None, 1.0));
    assert_eq!(captured["target_layer_id"], 1);
    assert_eq!(captured["frame_id"], frame);
    assert_eq!(captured["cel_id"], Value::Null);
    send(
        &mut engine,
        json!({"type":"select_layer","id":second_layer}),
    )
    .unwrap();
    let state = engine.state();
    assert_eq!(state["revision"], captured["revision"]);
    assert_eq!(state["animation"]["activeFrameId"], captured["frame_id"]);
    assert_eq!(state["animation"]["activeCelId"], captured["cel_id"]);
    raw_atomic(&mut engine, captured);
    let saved = engine.save().unwrap();
    let next_cel = engine.document.animation.as_ref().unwrap().next_cel_id;
    send(&mut engine, brush([0, 0, 255], None, 1.0)).unwrap();
    dab(&mut engine, 4.5, 5.5);
    send(&mut engine, json!({"type":"cancel"})).unwrap();
    assert_eq!(engine.state(), state);
    assert!(engine.save().unwrap() == saved);
    assert_eq!(
        engine.document.animation.as_ref().unwrap().next_cel_id,
        next_cel
    );
    assert_eq!(cel_id(&engine, frame, 1), None);
    assert_eq!(cel_id(&engine, frame, second_layer), None);
}

#[test]
fn blank_track_preview_accepts_explicit_null_cel_and_rejects_missing_or_stale_layer() {
    let (mut engine, frame, second_layer) = blank_tracks();
    engine.frame_with_background(true);
    let state = engine.state();
    let saved = engine.save().unwrap();
    let value = json!({"id":1,"revision":state["revision"],"selection_id":state["selectionId"],
        "mask_editing":false,"mask_id":null,"target_layer_id":1,"frame_id":frame,"cel_id":null,
        "action":{"kind":"translate","dx":1,"dy":0}});
    let request: LayerActionRequest = serde_json::from_value(value.clone()).unwrap();
    assert_eq!(request.cel_id, Some(None));
    assert_eq!(engine.preview_layer_action(request).unwrap().len(), 16);
    for field in ["cel_id", "target_layer_id"] {
        let mut missing = value.clone();
        missing.as_object_mut().unwrap().remove(field);
        let request = serde_json::from_value(missing).unwrap();
        assert!(engine.preview_layer_action(request).is_err());
    }
    assert_eq!(engine.state(), state);
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    send(
        &mut engine,
        json!({"type":"select_layer","id":second_layer}),
    )
    .unwrap();
    assert_eq!(engine.state()["revision"], state["revision"]);
    let stale = serde_json::from_value(value).unwrap();
    assert!(engine.preview_layer_action(stale).is_err());
    let current_state = engine.state();
    let current = serde_json::from_value(
        json!({"id":second_layer,"revision":current_state["revision"],
        "selection_id":current_state["selectionId"],"mask_editing":false,"mask_id":null,
        "target_layer_id":second_layer,"frame_id":frame,"cel_id":null,
        "action":{"kind":"translate","dx":1,"dy":0}}),
    )
    .unwrap();
    assert_eq!(engine.preview_layer_action(current).unwrap().len(), 16);
    engine.document.validate().unwrap();
}

fn distant_group_frames(indexed: bool) -> (Engine, u32, u32) {
    let mut engine = Engine::new(256, 256).unwrap();
    if indexed {
        send(
            &mut engine,
            json!({"type":"new_indexed","width":256,"height":256,"palette":{
            "colors":[[0,0,0,0],[255,0,0,255],[0,0,255,0],[0,255,0,255]],
            "transparent":0,"order":[0,1,2,3]}}),
        )
        .unwrap();
        let mut data = vec![0; INDEX_TILE_BYTES];
        data[(3 * TILE_SIZE + 2) as usize] = 1;
        engine.document.layers[0]
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert((0, 0), Arc::new(data));
    } else {
        put(&mut engine.document.layers[0], 2, 3, [255, 0, 0, 255]);
    }
    let mut group = Layer::group(2, "Shared pivot".into(), GroupIsolation::Isolated);
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 2,
            top: 3,
            right: 3,
            bottom: 4,
        },
        255,
    );
    mask.tiles
        .insert((0, 0), Arc::new(vec![128; MASK_TILE_BYTES]));
    group.masks = vec![MaskEntry {
        id: 0,
        name: "Local coverage".into(),
        plane: mask,
    }];
    let mut leaf = engine.document.layers.remove(0);
    leaf.parent_id = Some(2);
    engine.document.layers = vec![group, leaf];
    engine.document.next_id = 3;
    reload(&mut engine);
    let first = enable(&mut engine);
    let second = duplicate(&mut engine, first, 1, false);
    send(&mut engine, json!({"type":"clear"})).unwrap();
    send(&mut engine, brush([0, 255, 0], indexed.then_some(3), 1.0)).unwrap();
    dab(&mut engine, 100.5, 3.5);
    send(&mut engine, json!({"type":"end"})).unwrap();
    if indexed {
        send(
            &mut engine,
            json!({"type":"select","rect":{"left":101,"top":3,"right":102,"bottom":4}}),
        )
        .unwrap();
        send(
            &mut engine,
            json!({"type":"fill_indexed","index":2,"x":101,"y":3,
            "tolerance":0,"contiguous":true,"merged":false,"opacity":1}),
        )
        .unwrap();
        send(&mut engine, json!({"type":"select","rect":null})).unwrap();
        assert_eq!(source_index(&engine, second, 1, 101, 3), 2);
    }
    select(&mut engine, first);
    send(&mut engine, json!({"type":"select_layer","id":2})).unwrap();
    (engine, first, second)
}

fn scale_group(engine: &mut Engine) {
    send(
        engine,
        json!({"type":"transform_layer","id":2,"transform":{
        "width":2,"height":2,"dx":1.5,"dy":1.5,"angle":0,
        "flip_x":false,"flip_y":false,"filter":"nearest"}}),
    )
    .unwrap();
}

fn transformed_group_mask(engine: &Engine) {
    let group = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == 2)
        .unwrap();
    assert_eq!(
        group.masks[0].plane.bounds,
        MaskBounds {
            left: 3,
            top: 4,
            right: 5,
            bottom: 6
        }
    );
    assert_eq!(group.masks[0].plane.sample(3, 4), 128);
    assert_eq!(group.masks[0].plane.sample(199, 4), 255);
}

#[test]
fn group_scaling_preserves_distant_inactive_rgba_pixels_with_the_current_frame_pivot() {
    let (mut engine, first, second) = distant_group_frames(false);
    let before = engine.save().unwrap();
    let identities = (cel_id(&engine, first, 1), cel_id(&engine, second, 1));
    scale_group(&mut engine);
    assert_eq!(
        (cel_id(&engine, first, 1), cel_id(&engine, second, 1)),
        identities
    );
    for y in 0..256 {
        for x in 0..256 {
            let current = if (3..5).contains(&x) && (4..6).contains(&y) {
                [255, 0, 0, 255]
            } else {
                [0; 4]
            };
            let inactive = if (199..201).contains(&x) && (4..6).contains(&y) {
                [0, 255, 0, 255]
            } else {
                [0; 4]
            };
            assert_eq!(
                source_pixel(&engine, first, 1, x, y),
                current,
                "current {x},{y}"
            );
            assert_eq!(
                source_pixel(&engine, second, 1, x, y),
                inactive,
                "inactive {x},{y}"
            );
        }
    }
    transformed_group_mask(&engine);
    assert_eq!(pixel(&render(&engine, first), 3, 4), [128, 0, 0, 128]);
    assert_eq!(pixel(&render(&engine, second), 199, 4), [0, 255, 0, 255]);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    assert_eq!(source_pixel(&engine, first, 1, 2, 3), [255, 0, 0, 255]);
    assert_eq!(source_pixel(&engine, second, 1, 100, 3), [0, 255, 0, 255]);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
    engine.document.validate().unwrap();
}

#[test]
fn group_scaling_preserves_distant_inactive_index_slots_including_hidden_transparency() {
    let (mut engine, first, second) = distant_group_frames(true);
    let before = engine.save().unwrap();
    scale_group(&mut engine);
    for y in 0..256 {
        for x in 0..256 {
            let current = u8::from((3..5).contains(&x) && (4..6).contains(&y));
            let inactive = if (199..201).contains(&x) && (4..6).contains(&y) {
                3
            } else if (201..203).contains(&x) && (4..6).contains(&y) {
                2
            } else {
                0
            };
            assert_eq!(
                source_index(&engine, first, 1, x, y),
                current,
                "current {x},{y}"
            );
            assert_eq!(
                source_index(&engine, second, 1, x, y),
                inactive,
                "inactive {x},{y}"
            );
        }
    }
    transformed_group_mask(&engine);
    assert_eq!(pixel(&render(&engine, first), 3, 4), [128, 0, 0, 128]);
    assert_eq!(pixel(&render(&engine, second), 199, 4), [0, 255, 0, 255]);
    assert_eq!(pixel(&render(&engine, second), 201, 4), [0; 4]);
    let transformed = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"set_palette_color","index":2,"color":[0,0,255,255]}),
    )
    .unwrap();
    assert_eq!(pixel(&render(&engine, second), 201, 4), [0, 0, 255, 255]);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == transformed);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    assert_eq!(source_index(&engine, second, 1, 100, 3), 3);
    assert_eq!(source_index(&engine, second, 1, 101, 3), 2);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == transformed);
    engine.document.validate().unwrap();
}

#[test]
fn lanczos_group_translation_preserves_distant_inactive_rgba_pixels_without_cropping() {
    let (mut engine, first, second) = distant_group_frames(false);
    let before = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"transform_layer","id":2,"transform":{
        "width":1,"height":1,"dx":10,"dy":20,"angle":0,
        "flip_x":false,"flip_y":false,"filter":"lanczos3"}}),
    )
    .unwrap();
    for y in 0..256 {
        for x in 0..256 {
            let current = if (x, y) == (12, 23) {
                [255, 0, 0, 255]
            } else {
                [0; 4]
            };
            let inactive = if (x, y) == (110, 23) {
                [0, 255, 0, 255]
            } else {
                [0; 4]
            };
            assert_eq!(
                source_pixel(&engine, first, 1, x, y),
                current,
                "current {x},{y}"
            );
            assert_eq!(
                source_pixel(&engine, second, 1, x, y),
                inactive,
                "inactive {x},{y}"
            );
        }
    }
    let group = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == 2)
        .unwrap();
    assert_eq!(group.masks[0].plane.sample(12, 23), 128);
    assert_eq!(group.masks[0].plane.sample(110, 23), 255);
    assert_eq!(pixel(&render(&engine, first), 12, 23), [128, 0, 0, 128]);
    assert_eq!(pixel(&render(&engine, second), 110, 23), [0, 255, 0, 255]);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
    engine.document.validate().unwrap();
}

#[test]
fn undo_and_redo_that_change_frames_clear_the_selection_of_the_previous_frame() {
    let mut engine = still(16, 12);
    let first = enable(&mut engine);
    let before = engine.save().unwrap();
    let before_content = engine.state()["contentId"].clone();
    send(
        &mut engine,
        json!({"type":"add_frame","index":1,"duration_ms":100,"select":false}),
    )
    .unwrap();
    let second = engine.document.animation.as_ref().unwrap().frames[1].id;
    let after_content = engine.state()["contentId"].clone();
    assert_eq!(active_frame(&engine), first);
    select(&mut engine, second);
    send(
        &mut engine,
        json!({"type":"select","rect":{"left":8,"top":4,"right":12,"bottom":8}}),
    )
    .unwrap();
    let selected_second = engine.state();
    assert!(!selected_second["selection"].is_null());
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    let undone = engine.state();
    assert_eq!(active_frame(&engine), first);
    assert_eq!(engine.document.animation.as_ref().unwrap().frames.len(), 1);
    assert!(undone["selection"].is_null());
    assert!(
        undone["selectionId"].as_u64().unwrap() > selected_second["selectionId"].as_u64().unwrap()
    );
    assert_eq!(undone["contentId"], before_content);
    assert!(engine.save().unwrap() == before);
    send(
        &mut engine,
        json!({"type":"select","rect":{"left":1,"top":1,"right":5,"bottom":5}}),
    )
    .unwrap();
    let selected_first = engine.state();
    assert!(!selected_first["selection"].is_null());
    assert_eq!(selected_first["canRedo"], true);
    engine.command(Command::Redo).unwrap();
    let redone = engine.state();
    assert_eq!(active_frame(&engine), second);
    assert_eq!(engine.document.animation.as_ref().unwrap().frames.len(), 2);
    assert!(redone["selection"].is_null());
    assert!(
        redone["selectionId"].as_u64().unwrap() > selected_first["selectionId"].as_u64().unwrap()
    );
    assert_eq!(redone["contentId"], after_content);
    assert!(engine.save().unwrap() == after);
    assert_eq!(pixel(&render(&engine, first), 2, 3), [255, 0, 0, 255]);
    assert_eq!(pixel(&render(&engine, second), 2, 3), [0; 4]);
    engine.document.validate().unwrap();
}

#[test]
fn chunked_strokes_with_interleaved_frame_and_state_reads_match_single_sample_packets() {
    let samples = [
        Sample {
            x: 56.0,
            y: 24.0,
            pressure: 0.25,
        },
        Sample {
            x: 64.0,
            y: 30.0,
            pressure: 0.75,
        },
        Sample {
            x: 80.0,
            y: 38.0,
            pressure: 1.0,
        },
    ];
    for mode in 0..3 {
        for mask in [false, true] {
            for eraser in [false, true] {
                for soft in [false, true] {
                    let mut fixture = Engine::new(128, 96).unwrap();
                    send(
                        &mut fixture,
                        json!({"type":"select","rect":{"left":0,"top":0,"right":32,"bottom":96}}),
                    )
                    .unwrap();
                    send(
                        &mut fixture,
                        json!({"type":"fill","x":0,"y":0,"color":[20,80,100,255],"tolerance":0}),
                    )
                    .unwrap();
                    send(&mut fixture, json!({"type":"select","rect":null})).unwrap();
                    if mode != 0 {
                        enable(&mut fixture);
                        if mode == 2 {
                            add_frame(&mut fixture, 1, 100);
                        }
                    }
                    if mask {
                        reveal_mask(&mut fixture);
                    }
                    let checkpoint = fixture.save().unwrap();
                    let mut batch = Engine::new(1, 1).unwrap();
                    batch.load(&checkpoint).unwrap();
                    let mut chunked = Engine::new(1, 1).unwrap();
                    chunked.load(&checkpoint).unwrap();
                    if mask {
                        for engine in [&mut batch, &mut chunked] {
                            send(engine, json!({"type":"set_mask_editing","enabled":true}))
                                .unwrap();
                        }
                    }
                    let begin = json!({"type":"begin","brush":{
                        "color":[179,87,52],"size":6,"opacity":if soft {0.45}else{1.0},
                        "hardness":if soft {0.4}else{1.0},"stabilization":if soft {0.65}else{0.0},
                        "size_pressure":0.5,"opacity_pressure":0,"spacing":0.08,
                        "eraser":eraser,"texture":"smooth","raster":"antialiased"}});
                    send(&mut batch, begin.clone()).unwrap();
                    batch.samples(&samples).unwrap();
                    send(&mut batch, json!({"type":"end"})).unwrap();
                    send(&mut chunked, begin).unwrap();
                    for (index, sample) in samples.iter().enumerate() {
                        chunked.samples(std::slice::from_ref(sample)).unwrap();
                        let before = chunked.state();
                        chunked.frame_with_background(index % 2 == 0);
                        assert_eq!(send(&mut chunked, json!({"type":"state"})).unwrap(), before);
                        assert_eq!(chunked.state(), before);
                    }
                    send(&mut chunked, json!({"type":"end"})).unwrap();
                    assert_eq!(batch.state(), chunked.state());
                    assert!(
                        batch.save().unwrap() == chunked.save().unwrap(),
                        "source differs: mode={mode}, mask={mask}, eraser={eraser}, soft={soft}"
                    );
                    let mut expected = Engine::new(1, 1).unwrap();
                    expected.load(&batch.save().unwrap()).unwrap();
                    let mut actual = Engine::new(1, 1).unwrap();
                    actual.load(&chunked.save().unwrap()).unwrap();
                    let expected = tiles(&expected.frame_with_background(true));
                    let actual = tiles(&actual.frame_with_background(true));
                    assert!(
                        expected == actual,
                        "composite differs: mode={mode}, mask={mask}, eraser={eraser}, soft={soft}"
                    );
                }
            }
        }
    }
}
