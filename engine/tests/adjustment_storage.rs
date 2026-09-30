use bincode::Options;
use podor_engine::{model::*, AdjustmentEffect, AdjustmentSpec, Command, Engine};
use serde_json::{json, Value};
use std::{collections::BTreeMap, io::Write, sync::Arc};

#[derive(serde::Serialize)]
enum V6Content {
    Raster(RasterPlane),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
}

#[derive(serde::Serialize)]
struct V6Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    content: V6Content,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    mask: Option<LayerMask>,
    clipping: bool,
}

#[derive(serde::Serialize)]
struct V6Document {
    width: u32,
    height: u32,
    layers: Vec<V6Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
}

fn compress(header: &[u8], payload: &[u8]) -> Vec<u8> {
    let mut gzip = flate2::write::GzEncoder::new(header.to_vec(), flate2::Compression::fast());
    gzip.write_all(payload).unwrap();
    gzip.finish().unwrap()
}

fn encode(header: &[u8], value: &impl serde::Serialize) -> Vec<u8> {
    compress(
        header,
        &bincode::DefaultOptions::new().serialize(value).unwrap(),
    )
}

fn v7_payload(doc: &Document) -> Vec<u8> {
    let layers: Vec<_> = doc
        .layers
        .iter()
        .map(|layer| {
            (
                layer.id,
                &layer.name,
                layer.visible,
                layer.opacity,
                &layer.content,
                layer.parent_id,
                layer.blend,
                layer.alpha_locked,
                layer.locked,
                layer.first_mask().cloned(),
                layer.clipping,
            )
        })
        .collect();
    bincode::DefaultOptions::new()
        .serialize(&(
            doc.width,
            doc.height,
            layers,
            doc.active,
            doc.next_id,
            &doc.palette,
        ))
        .unwrap()
}

fn layer(doc: &Document, id: u32) -> &Layer {
    doc.layers.iter().find(|layer| layer.id == id).unwrap()
}

fn layer_mut(doc: &mut Document, id: u32) -> &mut Layer {
    doc.layers.iter_mut().find(|layer| layer.id == id).unwrap()
}

fn mask(enabled: bool) -> LayerMask {
    let mut result = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 3,
            bottom: 1,
        },
        255,
    );
    result.enabled = enabled;
    result.linked = false;
    let mut coverage = vec![255; MASK_TILE_BYTES];
    coverage[..3].copy_from_slice(&[255, 0, 128]);
    result.tiles.insert((0, 0), Arc::new(coverage));
    result
}

fn old_layer(id: u32, parent_id: Option<u32>, content: V6Content) -> V6Layer {
    V6Layer {
        id,
        name: format!("V6 pigment {id} 🎨"),
        visible: true,
        opacity: 1.0,
        content,
        parent_id,
        blend: BlendMode::Normal,
        alpha_locked: false,
        locked: false,
        mask: None,
        clipping: false,
    }
}

fn old_fixture(indexed: bool) -> V6Document {
    let raster = |slot: u8| {
        let pixels = if indexed {
            vec![slot; INDEX_TILE_BYTES]
        } else {
            match slot {
                1 => [10, 20, 30, 255],
                2 => [255, 0, 0, 255],
                3 => [0, 128, 0, 128],
                _ => [0; 4],
            }
            .repeat(TILE_BYTES / 4)
        };
        let tiles = BTreeMap::from([((0, 0), Arc::new(pixels))]);
        V6Content::Raster(if indexed {
            RasterPlane::Indexed(tiles)
        } else {
            RasterPlane::Rgba(tiles)
        })
    };
    let mut outer = old_layer(
        2,
        None,
        V6Content::Group {
            isolation: GroupIsolation::Isolated,
            closed: true,
        },
    );
    outer.mask = Some(mask(true));
    let pass = old_layer(
        3,
        Some(2),
        V6Content::Group {
            isolation: GroupIsolation::PassThrough,
            closed: true,
        },
    );
    let mut clip = old_layer(9, Some(3), raster(3));
    clip.clipping = true;
    clip.alpha_locked = true;
    clip.mask = Some(mask(false));
    let mut hidden = old_layer(
        13,
        None,
        V6Content::Raster(if indexed {
            RasterPlane::Indexed(BTreeMap::new())
        } else {
            RasterPlane::Rgba(BTreeMap::new())
        }),
    );
    hidden.visible = false;
    hidden.opacity = 0.3;
    hidden.blend = BlendMode::Screen;
    hidden.locked = true;
    hidden.mask = Some(mask(false));
    V6Document {
        width: 3,
        height: 1,
        layers: vec![
            old_layer(1, None, raster(1)),
            outer,
            pass,
            old_layer(7, Some(3), raster(2)),
            clip,
            hidden,
        ],
        active: 2,
        next_id: 20,
        palette: indexed.then_some(IndexedPalette {
            colors: vec![
                [44, 55, 66, 0],
                [10, 20, 30, 255],
                [255, 0, 0, 255],
                [0, 255, 0, 128],
            ],
            transparent: 0,
            order: vec![3, 1, 0, 2],
        }),
    }
}

#[test]
fn independent_v6_nested_fixture_migrates_index_slots_parents_masks_and_clipping_to_v7() {
    for indexed in [false, true] {
        let old = old_fixture(indexed);
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&encode(b"PODOR\x06", &old)).unwrap();
        assert_eq!(
            (
                engine.document.width,
                engine.document.height,
                engine.document.active,
                engine.document.next_id
            ),
            (3, 1, 2, 20)
        );
        assert_eq!(engine.document.palette, old.palette);
        assert_eq!(engine.document.layers.len(), old.layers.len());
        for (layer, prior) in engine.document.layers.iter().zip(&old.layers) {
            assert_eq!((layer.id, layer.parent_id), (prior.id, prior.parent_id));
            assert_eq!(layer.name, prior.name);
            assert_eq!(layer.visible, prior.visible);
            assert_eq!(layer.opacity, prior.opacity);
            assert_eq!(layer.blend, prior.blend);
            assert_eq!(layer.alpha_locked, prior.alpha_locked);
            assert_eq!(layer.locked, prior.locked);
            assert_eq!(layer.first_mask(), prior.mask.as_ref());
            assert_eq!(layer.clipping, prior.clipping);
            match &prior.content {
                V6Content::Raster(raster) => assert_eq!(layer.raster().unwrap(), raster),
                V6Content::Group { isolation, closed } => assert_eq!(
                    layer.content,
                    LayerContent::Group {
                        isolation: *isolation,
                        closed: *closed
                    }
                ),
            }
        }
        assert_eq!(engine.state()["layers"][4]["clippingBase"], 7);
        let frame = engine.frame_with_background(true);
        assert_eq!(&frame[24..32], &[127, 128, 0, 255, 10, 20, 30, 255]);
        let migrated = engine.save().unwrap();
        assert!(migrated.starts_with(b"PODOR\x0c"));
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&migrated).unwrap();
        assert_eq!(reopened.save().unwrap(), migrated);
        assert!(reopened.document == engine.document);
        assert_eq!(reopened.frame_with_background(true), frame);
    }
}

fn settings() -> Vec<(Value, Value)> {
    vec![
        (
            json!({"kind":"tone","brightness":0.125,"contrast":-0.375,"saturation":0.625}),
            json!({"kind":"tone","brightness":-0.25,"contrast":0.5,"saturation":-0.125}),
        ),
        (
            json!({"kind":"curves","curves":{
                "rgb":{"points":[{"x":0,"y":12},{"x":100,"y":170},{"x":255,"y":244}]},
                "red":{"points":[{"x":0,"y":5},{"x":255,"y":250}]},
                "green":{"points":[{"x":0,"y":0},{"x":200,"y":215},{"x":255,"y":255}]},
                "blue":{"points":[{"x":0,"y":16},{"x":255,"y":230}]}
            }}),
            json!({"kind":"curves","curves":{
                "rgb":{"points":[{"x":0,"y":0},{"x":80,"y":120},{"x":190,"y":140},{"x":255,"y":255}]},
                "blue":{"points":[{"x":0,"y":5},{"x":255,"y":249}]}
            }}),
        ),
        (
            json!({"kind":"gradient_map","gradient_map":{"stops":[
                {"position":0.0,"color":[14,24,44]},
                {"position":0.375,"color":[94,36,101]},
                {"position":1.0,"color":[231,194,143]}
            ]}}),
            json!({"kind":"gradient_map","gradient_map":{"stops":[
                {"position":0.125,"color":[5,9,22]},
                {"position":0.625,"color":[120,194,83]},
                {"position":0.875,"color":[249,241,189]}
            ]}}),
        ),
    ]
}

fn effect(value: Value) -> AdjustmentEffect {
    serde_json::from_value::<AdjustmentSpec>(value)
        .unwrap()
        .into()
}

fn valid_engine() -> Engine {
    let mut engine = Engine::new(3, 1).unwrap();
    engine
        .load(&encode(b"PODOR\x06", &old_fixture(false)))
        .unwrap();
    engine.document.layers[1].locked = false;
    for (offset, (initial, _)) in settings().into_iter().enumerate() {
        let mut layer = Layer::new(20 + offset as u32, format!("Settings {offset}"));
        layer.parent_id = Some(2);
        layer.content = LayerContent::Adjustment {
            settings: effect(initial),
        };
        engine.document.layers.insert(5 + offset, layer);
    }
    engine.document.active = 21;
    engine.document.next_id = 23;
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
    engine
}

fn assert_roundtrip(engine: &Engine, bytes: &[u8]) {
    assert!(bytes.starts_with(b"PODOR\x0c"));
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(bytes).unwrap();
    assert!(reopened.document == engine.document);
    assert_eq!(reopened.save().unwrap(), bytes);
}

#[test]
fn v7_three_independent_settings_roundtrip_after_edit_and_undo_without_baking_raster_data() {
    let mut engine = valid_engine();
    let raster = layer(&engine.document, 7).raster().unwrap().clone();
    let initial = engine.save().unwrap();
    assert_roundtrip(&engine, &initial);
    for (offset, (_, replacement)) in settings().into_iter().enumerate() {
        let before = engine.save().unwrap();
        let prior = engine.document.clone();
        let id = 20 + offset as u32;
        let expected = effect(replacement.clone());
        engine
            .command(Command::SetAdjustment {
                id,
                settings: serde_json::from_value(replacement).unwrap(),
                revision: engine.state()["revision"].as_u64().unwrap(),
            })
            .unwrap();
        let after = engine.save().unwrap();
        assert_ne!(before, after);
        assert_eq!(
            layer(&engine.document, id).content,
            LayerContent::Adjustment { settings: expected }
        );
        for prior_layer in &prior.layers {
            if prior_layer.id != id {
                assert!(layer(&engine.document, prior_layer.id) == prior_layer);
            }
        }
        assert_eq!(layer(&engine.document, 7).raster().unwrap(), &raster);
        assert_roundtrip(&engine, &after);
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        assert_roundtrip(&engine, &before);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), after);
        assert_roundtrip(&engine, &after);
    }
}

fn invalid_settings() -> Vec<AdjustmentEffect> {
    let mut result: Vec<_> = [
        json!({"kind":"tone","brightness":1.01,"contrast":0.0,"saturation":0.0}),
        json!({"kind":"tone","brightness":0.0,"contrast":-1.01,"saturation":0.0}),
        json!({"kind":"tone","brightness":0.0,"contrast":0.0,"saturation":2.0}),
        json!({"kind":"curves","curves":{"rgb":{"points":[{"x":0,"y":0},{"x":0,"y":128},{"x":255,"y":255}]}}}),
        json!({"kind":"curves","curves":{"red":{"points":[{"x":1,"y":0},{"x":255,"y":255}]}}}),
        json!({"kind":"gradient_map","gradient_map":{"stops":[{"position":0.5,"color":[1,2,3]}]}}),
        json!({"kind":"gradient_map","gradient_map":{"stops":[{"position":0.8,"color":[1,2,3]},{"position":0.2,"color":[4,5,6]}]}}),
        json!({"kind":"gradient_map","gradient_map":{"stops":[{"position":-0.1,"color":[1,2,3]},{"position":1.0,"color":[4,5,6]}]}}),
    ].into_iter().map(effect).collect();
    let mut nonfinite = effect(settings()[0].0.clone());
    if let AdjustmentEffect::Tone(tone) = &mut nonfinite {
        tone.brightness = f32::NAN;
    }
    result.push(nonfinite);
    let mut nonfinite = effect(settings()[2].0.clone());
    if let AdjustmentEffect::GradientMap(map) = &mut nonfinite {
        map.stops[1].position = f64::INFINITY;
    }
    result.push(nonfinite);
    result
}

#[test]
fn invalid_adjustment_parameters_cannot_be_saved_and_save_does_not_modify_raw_document() {
    let engine = valid_engine();
    for settings in invalid_settings() {
        let mut invalid = Engine::new(1, 1).unwrap();
        invalid.document = engine.document.clone();
        layer_mut(&mut invalid.document, 20).content = LayerContent::Adjustment { settings };
        let before = bincode::DefaultOptions::new()
            .serialize(&invalid.document)
            .unwrap();
        assert!(invalid.save().is_err());
        assert_eq!(
            bincode::DefaultOptions::new()
                .serialize(&invalid.document)
                .unwrap(),
            before
        );
    }
}

fn with_history() -> (Engine, Vec<u8>, Vec<u8>, Vec<u8>) {
    let mut engine = valid_engine();
    let initial = engine.save().unwrap();
    let rename = |engine: &mut Engine, name: &str| {
        engine
            .command(Command::SetLayer {
                id: 1,
                name: name.into(),
                visible: true,
                opacity: 1.0,
            })
            .unwrap();
    };
    rename(&mut engine, "V7 undo checkpoint");
    let current = engine.save().unwrap();
    rename(&mut engine, "V7 redo checkpoint");
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    (engine, initial, current, future)
}

fn assert_rejected(projects: Vec<Vec<u8>>) {
    let (mut engine, initial, current, future) = with_history();
    let (mut control, _, _, _) = with_history();
    let dirty = control.frame();
    let state = engine.state();
    assert_eq!(state["canUndo"], true);
    assert_eq!(state["canRedo"], true);
    for (index, bytes) in projects.into_iter().enumerate() {
        assert!(
            engine.load(&bytes).is_err(),
            "accepted damaged project {index}"
        );
        assert_eq!(engine.save().unwrap(), current);
        assert_eq!(engine.state(), state);
        if index == 0 {
            assert_eq!(engine.frame(), dirty);
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
fn damaged_v7_parameter_payloads_reject_atomically_and_preserve_real_undo_redo() {
    let engine = valid_engine();
    let projects = invalid_settings()
        .into_iter()
        .map(|settings| {
            let mut invalid = engine.document.clone();
            layer_mut(&mut invalid, 20).content = LayerContent::Adjustment { settings };
            compress(b"PODOR\x07", &v7_payload(&invalid))
        })
        .collect();
    assert_rejected(projects);
}

#[test]
fn future_v0c_truncated_gzip_and_trailing_v7_payload_reject_without_replacing_current_project() {
    let engine = valid_engine();
    let saved = engine.save().unwrap();
    let mut future = saved.clone();
    future[5] = 13;
    let mut probe = Engine::new(1, 1).unwrap();
    assert!(probe.load(&future).unwrap_err().contains("版本"));
    let mut truncated = saved;
    truncated.truncate(truncated.len() - 4);
    let mut payload = v7_payload(&engine.document);
    payload.push(0);
    let trailing = compress(b"PODOR\x07", &payload);
    assert_rejected(vec![future, truncated, trailing]);
}
