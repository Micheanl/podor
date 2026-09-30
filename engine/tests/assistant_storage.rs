use bincode::Options;
use podor_engine::{
    assistants::{AssistantSet, Family, Geometry as AssistantGeometry, Point},
    model::*,
    vector::*,
    AdjustmentEffect, AdjustmentSpec, Command, Engine, ExportFormat, ExportOptions,
};
use serde_json::{json, Value};
use std::{collections::BTreeMap, io::Write, sync::Arc};

#[derive(Clone, serde::Serialize)]
enum V9Content {
    Raster(RasterPlane),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
    Adjustment {
        settings: AdjustmentEffect,
    },
    Vector(Arc<VectorLayer>),
}

#[derive(Clone, serde::Serialize)]
struct V9Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    content: V9Content,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    masks: Vec<MaskEntry>,
    clipping: bool,
}

#[derive(Clone, serde::Serialize)]
struct V9Document {
    width: u32,
    height: u32,
    layers: Vec<V9Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
    next_mask_id: u32,
    active_mask_id: Option<u32>,
}

#[derive(serde::Serialize)]
struct V10Document {
    width: u32,
    height: u32,
    layers: Vec<V9Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
    next_mask_id: u32,
    active_mask_id: Option<u32>,
    assistants: Arc<AssistantSet>,
}

fn v10(document: &Document) -> V10Document {
    assert!(document.animation.is_none());
    V10Document {
        width: document.width,
        height: document.height,
        layers: document
            .layers
            .iter()
            .map(|layer| V9Layer {
                id: layer.id,
                name: layer.name.clone(),
                visible: layer.visible,
                opacity: layer.opacity,
                content: match &layer.content {
                    LayerContent::Raster(raster) => V9Content::Raster(raster.clone()),
                    LayerContent::Group { isolation, closed } => V9Content::Group {
                        isolation: *isolation,
                        closed: *closed,
                    },
                    LayerContent::Adjustment { settings } => V9Content::Adjustment {
                        settings: settings.clone(),
                    },
                    LayerContent::Vector(vector) => V9Content::Vector(vector.clone()),
                    LayerContent::CelTrack { .. } => {
                        panic!("V10 fixture cannot contain Cel tracks")
                    }
                },
                parent_id: layer.parent_id,
                blend: layer.blend,
                alpha_locked: layer.alpha_locked,
                locked: layer.locked,
                masks: layer.masks.clone(),
                clipping: layer.clipping,
            })
            .collect(),
        active: document.active,
        next_id: document.next_id,
        palette: document.palette.clone(),
        next_mask_id: document.next_mask_id,
        active_mask_id: document.active_mask_id,
        assistants: document.assistants.clone(),
    }
}

fn payload(value: &impl serde::Serialize) -> Vec<u8> {
    bincode::DefaultOptions::new().serialize(value).unwrap()
}

fn gzip(header: &[u8], bytes: &[u8]) -> Vec<u8> {
    let mut encoder = flate2::write::GzEncoder::new(header.to_vec(), flate2::Compression::fast());
    encoder.write_all(bytes).unwrap();
    encoder.finish().unwrap()
}

fn encode(header: &[u8], value: &impl serde::Serialize) -> Vec<u8> {
    gzip(header, &payload(value))
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

fn fixture(indexed: bool) -> V9Document {
    let raster = || {
        let bytes = if indexed {
            vec![1; INDEX_TILE_BYTES]
        } else {
            [40, 80, 120, 128].repeat(TILE_BYTES / 4)
        };
        let tiles = BTreeMap::from([((0, 0), Arc::new(bytes))]);
        if indexed {
            RasterPlane::Indexed(tiles)
        } else {
            RasterPlane::Rgba(tiles)
        }
    };
    let layer = |id, parent_id, content, masks, clipping| V9Layer {
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
    let mut old = V9Document {
        width: 8,
        height: 8,
        active: 5,
        next_id: 8,
        palette: indexed.then_some(IndexedPalette {
            colors: vec![[0; 4], [80, 160, 240, 128], [240, 160, 80, 255]],
            transparent: 0,
            order: vec![2, 0, 1],
        }),
        next_mask_id: 50,
        active_mask_id: Some(34),
        layers: vec![
            layer(1, None, V9Content::Raster(raster()), vec![], false),
            layer(
                3,
                None,
                V9Content::Group {
                    isolation: GroupIsolation::Isolated,
                    closed: true,
                },
                vec![mask(11, 255, true, false), mask(14, 0, false, true)],
                false,
            ),
            layer(4, Some(3), V9Content::Raster(raster()), vec![], false),
            layer(
                6,
                Some(3),
                V9Content::Raster(raster()),
                vec![mask(21, 255, true, true)],
                true,
            ),
            layer(
                5,
                Some(3),
                V9Content::Adjustment {
                    settings: settings.into(),
                },
                vec![mask(31, 255, true, false), mask(34, 0, false, true)],
                false,
            ),
        ],
    };
    if !indexed {
        old.layers.push(layer(
            7,
            None,
            V9Content::Vector(Arc::new(VectorLayer {
                next_object_id: 12,
                objects: vec![Arc::new(VectorObject {
                    id: 9,
                    name: "Independent path".into(),
                    visible: false,
                    geometry: Geometry::Path {
                        segments: vec![
                            Segment::MoveTo { x: 0.0, y: 0.0 },
                            Segment::LineTo { x: 3.0, y: 0.0 },
                            Segment::QuadTo {
                                cx: 4.0,
                                cy: 1.0,
                                x: 3.0,
                                y: 3.0,
                            },
                            Segment::CubicTo {
                                c1x: 2.0,
                                c1y: 4.0,
                                c2x: 0.0,
                                c2y: 4.0,
                                x: 0.0,
                                y: 0.0,
                            },
                            Segment::Close,
                        ],
                    },
                    transform: [1.0, 0.125, 0.25, 1.0, -0.25, 0.125],
                    style: Style {
                        fill: Some([10, 20, 30, 128]),
                        stroke: Some(StrokeStyle {
                            color: [40, 50, 60, 64],
                            width: 0.5,
                            cap: Cap::Square,
                            join: Join::Miter,
                            miter_limit: 8.0,
                        }),
                        fill_rule: FillRule::EvenOdd,
                    },
                })],
            })),
            vec![],
            false,
        ));
    }
    old
}

fn native(indexed: bool) -> Engine {
    let mut engine = Engine::new(1, 1).unwrap();
    engine
        .load(&encode(b"PODOR\x09", &fixture(indexed)))
        .unwrap();
    engine
}

fn command(engine: &mut Engine, mut value: Value) {
    value["revision"] = engine.state()["revision"].clone();
    engine
        .command(serde_json::from_value(value).unwrap())
        .unwrap();
}

fn spec(name: &str, geometry: Value) -> Value {
    json!({"name":name,"visible":true,"geometry":geometry})
}

fn add_guides(engine: &mut Engine) {
    for assistant in [
        spec(
            "Parallel",
            json!({"kind":"parallel","a":{"x":-12.5,"y":7.25},"b":{"x":40.125,"y":-20.5}}),
        ),
        spec(
            "Radial",
            json!({"kind":"radial","center":{"x":120.25,"y":-80.125}}),
        ),
        spec(
            "Perspective",
            json!({"kind":"perspective","families":[
                {"kind":"finite_vanishing_point","point":{"x":-2048.5,"y":-12.25}},
                {"kind":"infinite_direction","direction":{"x":0.25,"y":2.5}},
                {"kind":"finite_vanishing_point","point":{"x":4096.125,"y":160.5}}
            ]}),
        ),
    ] {
        command(
            engine,
            json!({"type":"add_assistant","assistant":assistant}),
        );
    }
    command(engine, json!({"type":"set_assistant_snap","id":3}));
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
    let mut engine = native(false);
    add_guides(&mut engine);
    let initial = engine.save().unwrap();
    rename(&mut engine, "Checkpoint");
    let current = engine.save().unwrap();
    rename(&mut engine, "Redo");
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    engine.frame();
    (engine, initial, current, future)
}

fn assert_atomic(projects: Vec<Vec<u8>>) {
    let (mut engine, initial, current, future) = with_history();
    let state = engine.state();
    for (index, bytes) in projects.into_iter().enumerate() {
        assert!(
            engine.load(&bytes).is_err(),
            "accepted corrupt project {index}"
        );
        assert_eq!(engine.save().unwrap(), current);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame().len(), 16);
    }
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), future);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), current);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), initial);
}

#[test]
fn independent_v9_all_content_variants_and_indexed_masks_migrate_without_data_changes() {
    for indexed in [false, true] {
        let old = fixture(indexed);
        let engine = native(indexed);
        let mut expected = payload(&old);
        expected.extend(payload(&engine.document.assistants));
        assert_eq!(payload(&v10(&engine.document)), expected);
        assert_eq!(engine.document.assistants.next_id, 1);
        assert_eq!(engine.document.assistants.snap_id, None);
        assert!(engine.document.assistants.items.is_empty());
        assert_eq!(engine.document.next_mask_id, 50);
        assert_eq!(engine.document.active_mask_id, Some(34));
        assert_eq!(engine.document.palette, old.palette);
        assert_eq!(engine.document.layers[3].parent_id, Some(3));
        assert!(engine.document.layers[3].clipping);
        if !indexed {
            let vector = engine.document.layers[5].vector().unwrap();
            assert_eq!(vector.next_object_id, 12);
            assert_eq!(vector.objects[0].id, 9);
        }
        let pixels = engine.export_png().unwrap();
        let saved = engine.save().unwrap();
        assert!(saved.starts_with(b"PODOR\x0c"));
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&saved).unwrap();
        assert_eq!(reopened.save().unwrap(), saved);
        assert_eq!(reopened.export_png().unwrap(), pixels);
    }
}

#[test]
fn v9_appended_assistants_and_corrupt_mask_identity_and_future_v0c_reject_atomically() {
    let current = with_history().0;
    let mut appended = payload(&fixture(false));
    appended.extend(payload(&current.document.assistants));
    let mut projects = vec![gzip(b"PODOR\x09", &appended)];
    for property in 0..4 {
        let mut old = fixture(false);
        match property {
            0 => old.layers[1].masks[0].id = 0,
            1 => old.layers[4].masks[0].id = 11,
            2 => old.next_mask_id = 34,
            _ => old.active_mask_id = Some(11),
        }
        projects.push(encode(b"PODOR\x09", &old));
    }
    let mut future = current.save().unwrap();
    future[5] = 13;
    projects.push(future);
    assert_atomic(projects);
}

#[test]
fn v0a_all_guide_controls_names_ids_hidden_snap_and_original_directions_roundtrip() {
    for indexed in [false, true] {
        let mut engine = native(indexed);
        let pixels = engine.export_png().unwrap();
        add_guides(&mut engine);
        command(
            &mut engine,
            json!({"type":"set_assistant","id":3,"assistant":{
                "name":"Perspective hidden","visible":false,"geometry":{"kind":"perspective","families":[
                    {"kind":"finite_vanishing_point","point":{"x":-2048.5,"y":-12.25}},
                    {"kind":"infinite_direction","direction":{"x":0.25,"y":2.5}},
                    {"kind":"finite_vanishing_point","point":{"x":4096.125,"y":160.5}}
                ]}
            }}),
        );
        let expected = engine.document.assistants.clone();
        let state = engine.state()["assistants"].clone();
        assert_eq!(expected.next_id, 4);
        assert_eq!(expected.snap_id, Some(3));
        assert_eq!(
            expected
                .items
                .iter()
                .map(|item| item.id)
                .collect::<Vec<_>>(),
            [1, 2, 3]
        );
        assert!(!expected.items[2].visible);
        assert_eq!(
            state["items"][2]["geometry"]["families"][1]["direction"],
            json!({"x":0.25,"y":2.5})
        );
        let saved = engine.save().unwrap();
        assert!(saved.starts_with(b"PODOR\x0c"));
        assert_eq!(engine.export_png().unwrap(), pixels);
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&saved).unwrap();
        assert_eq!(reopened.document.assistants, expected);
        assert_eq!(reopened.state()["assistants"], state);
        assert_eq!(reopened.save().unwrap(), saved);
        assert_eq!(reopened.export_png().unwrap(), pixels);
    }
}

#[test]
fn assistant_edit_delete_and_snap_undo_redo_restore_exact_saved_project() {
    let mut engine = native(false);
    add_guides(&mut engine);
    let before = engine.save().unwrap();
    let pixels = engine.export_png().unwrap();
    engine.frame();
    for change in [
        json!({"type":"set_assistant","id":1,"assistant":spec("Edited",json!({"kind":"parallel","a":{"x":-21.5,"y":2.75},"b":{"x":40.125,"y":-20.5}}))}),
        json!({"type":"set_assistant_snap","id":null}),
        json!({"type":"delete_assistant","id":3}),
    ] {
        command(&mut engine, change);
        let after = engine.save().unwrap();
        assert_ne!(after, before);
        assert_eq!(engine.export_png().unwrap(), pixels);
        assert_eq!(engine.frame().len(), 16);
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), after);
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&after).unwrap();
        assert_eq!(reopened.save().unwrap(), after);
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
    }
}

#[test]
fn v0a_invalid_ids_snap_names_nonfinite_and_degenerate_geometry_cannot_save_or_load() {
    let source = with_history().0.document;
    let mut valid = Engine::new(1, 1).unwrap();
    valid.load(&encode(b"PODOR\x0a", &v10(&source))).unwrap();
    assert!(valid.document == source);
    let mut projects = Vec::new();
    for property in 0..17 {
        let mut bad = source.clone();
        let set = Arc::make_mut(&mut bad.assistants);
        let point = |x, y| Point { x, y };
        match property {
            0 => set.items[0].id = 0,
            1 => set.items[1].id = 1,
            2 => set.next_id = 3,
            3 => set.next_id = 0,
            4 => set.snap_id = Some(999),
            5 => set.items[0].name.clear(),
            6 => set.items[0].name = "x".repeat(MAX_LAYER_NAME_BYTES + 1),
            7 => {
                set.items[0].geometry = AssistantGeometry::Parallel {
                    a: point(f64::NAN, 0.0),
                    b: point(1.0, 0.0),
                }
            }
            8 => {
                set.items[0].geometry = AssistantGeometry::Parallel {
                    a: point(1.0, 2.0),
                    b: point(1.0, 2.0),
                }
            }
            9 => {
                set.items[1].geometry = AssistantGeometry::Radial {
                    center: point(f64::INFINITY, 0.0),
                }
            }
            10 => {
                set.items[1].geometry = AssistantGeometry::Radial {
                    center: point(MAX_ASSISTANT_COORDINATE + 1.0, 0.0),
                }
            }
            11 => {
                set.items[2].geometry = AssistantGeometry::Perspective {
                    families: [
                        Family::FiniteVanishingPoint {
                            point: point(1.0, 2.0),
                        },
                        Family::FiniteVanishingPoint {
                            point: point(1.0, 2.0),
                        },
                        Family::InfiniteDirection {
                            direction: point(0.0, 1.0),
                        },
                    ],
                }
            }
            12 => {
                set.items[2].geometry = AssistantGeometry::Perspective {
                    families: [
                        Family::FiniteVanishingPoint {
                            point: point(1.0, 2.0),
                        },
                        Family::InfiniteDirection {
                            direction: point(0.0, 0.0),
                        },
                        Family::InfiniteDirection {
                            direction: point(0.0, 1.0),
                        },
                    ],
                }
            }
            13 => {
                set.items[2].geometry = AssistantGeometry::Perspective {
                    families: [
                        Family::InfiniteDirection {
                            direction: point(1.0, 0.0),
                        },
                        Family::InfiniteDirection {
                            direction: point(0.0, 1.0),
                        },
                        Family::InfiniteDirection {
                            direction: point(1.0, 1.0),
                        },
                    ],
                }
            }
            14 => {
                set.items[2].geometry = AssistantGeometry::Perspective {
                    families: [
                        Family::FiniteVanishingPoint {
                            point: point(1.0, 2.0),
                        },
                        Family::InfiniteDirection {
                            direction: point(1.0, 2.0),
                        },
                        Family::InfiniteDirection {
                            direction: point(-2.0, -4.0),
                        },
                    ],
                }
            }
            15 => {
                let assistant = set.items[0].clone();
                set.items = (1..=MAX_DRAWING_ASSISTANTS as u32 + 1)
                    .map(|id| {
                        let mut item = assistant.clone();
                        item.id = id;
                        item
                    })
                    .collect();
                set.next_id = MAX_DRAWING_ASSISTANTS as u32 + 2;
            }
            _ => {
                set.items[0].id = u32::MAX;
                set.next_id = u32::MAX;
            }
        }
        assert!(
            bad.validate().is_err(),
            "validated corrupt assistant case {property}"
        );
        let mut probe = Engine::new(1, 1).unwrap();
        probe.document = bad.clone();
        assert!(
            probe.save().is_err(),
            "saved corrupt assistant case {property}"
        );
        projects.push(encode(b"PODOR\x0a", &v10(&bad)));
    }
    assert_atomic(projects);
}

#[test]
fn guides_are_native_metadata_excluded_from_external_images_layer_formats_and_svg() {
    for indexed in [false, true] {
        let mut engine = native(indexed);
        let image = |engine: &Engine, format| {
            engine
                .export_image(ExportOptions {
                    format,
                    transparent: format != ExportFormat::Jpeg,
                    bake_layers: matches!(format, ExportFormat::Psd | ExportFormat::Ora),
                    ..Default::default()
                })
                .unwrap()
        };
        let formats = [
            ExportFormat::Png,
            ExportFormat::Jpeg,
            ExportFormat::Psd,
            ExportFormat::Ora,
        ];
        let expected = formats
            .into_iter()
            .map(|format| image(&engine, format))
            .collect::<Vec<_>>();
        let svg = (!indexed).then(|| {
            engine
                .vector_svg(7, engine.state()["revision"].as_u64().unwrap())
                .unwrap()
        });
        engine.frame();
        add_guides(&mut engine);
        let saved = engine.save().unwrap();
        let state = engine.state();
        for (format, expected) in formats.into_iter().zip(expected) {
            let bytes = image(&engine, format);
            assert_eq!(bytes, expected);
            let mut reopened = Engine::new(1, 1).unwrap();
            reopened.load(&bytes).unwrap();
            assert!(reopened.document.assistants.items.is_empty());
            assert_eq!(reopened.document.assistants.snap_id, None);
            assert_eq!(reopened.document.assistants.next_id, 1);
        }
        if let Some(expected) = svg {
            assert_eq!(
                engine
                    .vector_svg(7, engine.state()["revision"].as_u64().unwrap())
                    .unwrap(),
                expected
            );
        }
        assert_eq!(engine.save().unwrap(), saved);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame().len(), 16);
    }
}
