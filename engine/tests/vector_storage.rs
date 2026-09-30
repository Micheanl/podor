use bincode::Options;
use podor_engine::{model::*, AdjustmentSpec, Command, Engine};
use serde_json::json;
use std::{collections::BTreeMap, io::Write, sync::Arc};

#[derive(Clone, serde::Serialize)]
enum V8Content {
    Raster(RasterPlane),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
    Adjustment {
        settings: podor_engine::AdjustmentEffect,
    },
}

#[derive(Clone, serde::Serialize)]
struct V8Layer<C = V8Content> {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    content: C,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    masks: Vec<MaskEntry>,
    clipping: bool,
}

#[derive(Clone, serde::Serialize)]
struct V8Document<C = V8Content> {
    width: u32,
    height: u32,
    layers: Vec<V8Layer<C>>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
    next_mask_id: u32,
    active_mask_id: Option<u32>,
}

fn encode(header: &[u8], value: &impl serde::Serialize) -> Vec<u8> {
    let payload = bincode::DefaultOptions::new().serialize(value).unwrap();
    let mut gzip = flate2::write::GzEncoder::new(header.to_vec(), flate2::Compression::fast());
    gzip.write_all(&payload).unwrap();
    gzip.finish().unwrap()
}

fn mask(id: u32, default: u8, enabled: bool, linked: bool) -> MaskEntry {
    let mut plane = LayerMask::new(
        MaskBounds {
            left: -1,
            top: 0,
            right: 3,
            bottom: 1,
        },
        default,
    );
    plane.enabled = enabled;
    plane.linked = linked;
    let mut tile = vec![default; MASK_TILE_BYTES];
    tile[..4].copy_from_slice(&[17, 255, 128, 0]);
    plane.tiles.insert((0, 0), Arc::new(tile));
    MaskEntry {
        id,
        name: format!("Independent {id}"),
        plane,
    }
}

fn fixture(indexed: bool) -> V8Document {
    let raster = || {
        let values = if indexed {
            vec![1; INDEX_TILE_BYTES]
        } else {
            [40, 80, 120, 128].repeat(TILE_BYTES / 4)
        };
        let tiles = BTreeMap::from([((0, 0), Arc::new(values))]);
        if indexed {
            RasterPlane::Indexed(tiles)
        } else {
            RasterPlane::Rgba(tiles)
        }
    };
    let layer = |id, parent_id, content, masks, clipping| V8Layer {
        id,
        name: format!("Legacy {id}"),
        visible: true,
        opacity: 1.0,
        content,
        parent_id,
        blend: BlendMode::Normal,
        alpha_locked: false,
        locked: false,
        masks,
        clipping,
    };
    let settings: AdjustmentSpec = serde_json::from_value(json!({
        "kind":"tone","brightness":0.2,"contrast":-0.1,"saturation":0.3
    }))
    .unwrap();
    V8Document {
        width: 3,
        height: 1,
        active: 5,
        next_id: 7,
        palette: indexed.then_some(IndexedPalette {
            colors: vec![[0; 4], [80, 160, 240, 128], [240, 160, 80, 255]],
            transparent: 0,
            order: vec![2, 0, 1],
        }),
        next_mask_id: 50,
        active_mask_id: Some(34),
        layers: vec![
            layer(1, None, V8Content::Raster(raster()), vec![], false),
            layer(
                3,
                None,
                V8Content::Group {
                    isolation: GroupIsolation::Isolated,
                    closed: true,
                },
                vec![mask(11, 255, true, false), mask(14, 0, false, true)],
                false,
            ),
            layer(4, Some(3), V8Content::Raster(raster()), vec![], false),
            layer(
                6,
                Some(3),
                V8Content::Raster(raster()),
                vec![mask(21, 255, true, true)],
                true,
            ),
            layer(
                5,
                Some(3),
                V8Content::Adjustment {
                    settings: settings.into(),
                },
                vec![mask(31, 255, true, false), mask(34, 0, false, true)],
                false,
            ),
        ],
    }
}

#[test]
fn independent_v8_dto_migrates_all_prior_types_and_mask_identity_without_reassigning() {
    for indexed in [false, true] {
        let old = fixture(indexed);
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&encode(b"PODOR\x08", &old)).unwrap();
        let mut expected = bincode::DefaultOptions::new().serialize(&old).unwrap();
        expected.extend(
            bincode::DefaultOptions::new()
                .serialize(&podor_engine::assistants::AssistantSet::default())
                .unwrap(),
        );
        expected.extend(
            bincode::DefaultOptions::new()
                .serialize(&Option::<Arc<podor_engine::animation::AnimationSet>>::None)
                .unwrap(),
        );
        expected.extend(
            bincode::DefaultOptions::new()
                .serialize(&Option::<Arc<podor_engine::aseprite::ProjectMetadata>>::None)
                .unwrap(),
        );
        assert!(
            bincode::DefaultOptions::new()
                .serialize(&engine.document)
                .unwrap()
                == expected
        );
        assert!(engine.document.assistants.items.is_empty());
        assert!(engine.document.animation.is_none());
        assert!(engine.document.aseprite_metadata.is_none());
        assert_eq!(engine.document.assistants.snap_id, None);
        assert_eq!(engine.document.assistants.next_id, 1);
        assert_eq!(engine.document.next_mask_id, 50);
        assert_eq!(engine.document.active_mask_id, Some(34));
        assert_eq!(engine.document.layers[3].parent_id, Some(3));
        assert!(engine.document.layers[3].clipping);
        assert_eq!(
            engine.document.layers[1]
                .masks
                .iter()
                .map(|mask| mask.id)
                .collect::<Vec<_>>(),
            [11, 14]
        );
        let saved = engine.save().unwrap();
        assert!(saved.starts_with(b"PODOR\x0c"));
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&saved).unwrap();
        assert_eq!(reopened.save().unwrap(), saved);
    }
}

fn rename(engine: &mut Engine, name: &str) {
    engine
        .command(Command::SetLayer {
            id: 1,
            name: name.into(),
            visible: true,
            opacity: 1.0,
        })
        .unwrap();
}

fn with_history() -> (Engine, Vec<u8>, Vec<u8>, Vec<u8>) {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&encode(b"PODOR\x08", &fixture(false))).unwrap();
    let initial = engine.save().unwrap();
    rename(&mut engine, "Checkpoint");
    let current = engine.save().unwrap();
    rename(&mut engine, "Redo");
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    (engine, initial, current, future)
}

fn assert_atomic(bytes: Vec<Vec<u8>>) {
    let (mut engine, initial, current, future) = with_history();
    let (mut control, _, _, _) = with_history();
    let pending = control.frame();
    let state = engine.state();
    for (index, bytes) in bytes.into_iter().enumerate() {
        assert!(engine.load(&bytes).is_err());
        assert_eq!(engine.save().unwrap(), current);
        assert_eq!(engine.state(), state);
        if index == 0 {
            assert_eq!(engine.frame(), pending);
        } else {
            assert_eq!(engine.frame().len(), 16);
        }
    }
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), future);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), current);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), initial);
}

#[test]
fn v8_mask_ids_counters_and_foreign_targets_are_validated_without_migration_repairs() {
    let mut invalid = Vec::new();
    for property in 0..5 {
        let mut old = fixture(false);
        match property {
            0 => old.layers[1].masks[0].id = 0,
            1 => old.layers[4].masks[0].id = 11,
            2 => old.next_mask_id = 34,
            3 => old.next_mask_id = 0,
            _ => old.active_mask_id = Some(11),
        }
        invalid.push(encode(b"PODOR\x08", &old));
    }
    assert_atomic(invalid);
}

#[test]
fn forged_new_content_discriminant_in_v8_and_future_v0c_are_rejected_atomically() {
    let forged = V8Document {
        width: 1,
        height: 1,
        active: 1,
        next_id: 2,
        palette: None,
        next_mask_id: 1,
        active_mask_id: None,
        layers: vec![V8Layer {
            id: 1,
            name: "Unsupported".into(),
            visible: true,
            opacity: 1.0,
            content: 3u32,
            parent_id: None,
            blend: BlendMode::Normal,
            alpha_locked: false,
            locked: false,
            masks: vec![],
            clipping: false,
        }],
    };
    let mut future = with_history().0.save().unwrap();
    future[5] = 13;
    assert_atomic(vec![encode(b"PODOR\x08", &forged), future]);
}

fn command(engine: &mut Engine, mut value: serde_json::Value) {
    value["revision"] = engine.state()["revision"].clone();
    engine
        .command(serde_json::from_value(value).unwrap())
        .unwrap();
}

#[test]
fn v9_vector_shapes_paths_styles_ids_and_transforms_persist_across_load_undo_and_redo() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&encode(b"PODOR\x08", &fixture(false))).unwrap();
    command(
        &mut engine,
        json!({"type":"create_vector","name":"Vector","parent_id":null,"index":2}),
    );
    let id = engine.document.active;
    for (index, geometry) in [
        json!({"kind":"rect","x":-1,"y":0,"width":2,"height":1}),
        json!({"kind":"ellipse","cx":1,"cy":0.5,"rx":0.5,"ry":0.25}),
        json!({"kind":"line","x1":0,"y1":0,"x2":2,"y2":1}),
        json!({"kind":"path","segments":[{"kind":"move_to","x":0,"y":0},
            {"kind":"line_to","x":1,"y":0},{"kind":"quad_to","cx":2,"cy":0,"x":2,"y":1},
            {"kind":"cubic_to","c1x":1,"c1y":1,"c2x":0,"c2y":1,"x":0,"y":0},{"kind":"close"}]}),
    ]
    .into_iter()
    .enumerate()
    {
        command(
            &mut engine,
            json!({"type":"add_vector_object","id":id,"object":{
                "name":format!("Shape {index}"),"visible":index!=1,"geometry":geometry,
                "transform":[1,0.125,0.25,1,-0.25,0.125],
                "style":{"fill":if index==2 {None}else{Some([10,20,30,128])},
                    "stroke":{"color":[40,50,60,64],"width":0.5,"cap":"square","join":"miter","miter_limit":8},
                    "fill_rule":"even_odd"}
            }}),
        );
    }
    let original = engine.save().unwrap();
    assert!(original.starts_with(b"PODOR\x0c"));
    let vector = engine.document.active_mut().vector().unwrap();
    let specs = vector
        .objects
        .iter()
        .map(|object| serde_json::to_value(object.spec()).unwrap())
        .collect::<Vec<_>>();
    assert_eq!(vector.next_object_id, 5);
    assert_eq!(
        vector
            .objects
            .iter()
            .map(|object| object.id)
            .collect::<Vec<_>>(),
        [1, 2, 3, 4]
    );
    assert_eq!(engine.document.next_mask_id, 50);
    assert_eq!(engine.document.active_mask_id, None);
    let pixels = engine.export_png().unwrap();
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&original).unwrap();
    assert_eq!(reopened.save().unwrap(), original);
    assert_eq!(reopened.export_png().unwrap(), pixels);
    let vector = reopened.document.active_mut().vector().unwrap();
    assert_eq!(
        vector
            .objects
            .iter()
            .map(|object| serde_json::to_value(object.spec()).unwrap())
            .collect::<Vec<_>>(),
        specs
    );
    let mut changed = specs[1].clone();
    changed["geometry"] = json!({"kind":"rect","x":0,"y":0,"width":2,"height":1});
    changed["visible"] = json!(true);
    command(
        &mut engine,
        json!({"type":"set_vector_object","id":id,"object_id":2,"object":changed}),
    );
    let edited = engine.save().unwrap();
    assert_ne!(edited, original);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), original);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), edited);
    reopened.load(&edited).unwrap();
    assert_eq!(reopened.save().unwrap(), edited);
    assert_eq!(
        reopened.document.active_mut().vector().unwrap().objects[1].id,
        2
    );
    let mut mislabeled = edited;
    mislabeled[5] = 8;
    assert_atomic(vec![mislabeled]);
}
