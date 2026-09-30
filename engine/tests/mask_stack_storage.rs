use bincode::Options;
use podor_engine::{model::*, AdjustmentEffect, AdjustmentSpec, Command, Engine};
use serde_json::json;
use std::{collections::BTreeMap, io::Write, sync::Arc};

#[derive(serde::Serialize)]
enum V7Content {
    Raster(RasterPlane),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
    Adjustment {
        settings: AdjustmentEffect,
    },
}

#[derive(serde::Serialize)]
struct V7Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    content: V7Content,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    mask: Option<LayerMask>,
    clipping: bool,
}

#[derive(serde::Serialize)]
struct V7Document {
    width: u32,
    height: u32,
    layers: Vec<V7Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
}

#[derive(serde::Serialize)]
struct V8Layer<'a> {
    id: u32,
    name: &'a str,
    visible: bool,
    opacity: f32,
    content: V7Content,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    masks: &'a [MaskEntry],
    clipping: bool,
}

#[derive(serde::Serialize)]
struct V8Document<'a> {
    width: u32,
    height: u32,
    layers: Vec<V8Layer<'a>>,
    active: u32,
    next_id: u32,
    palette: Option<&'a IndexedPalette>,
    next_mask_id: u32,
    active_mask_id: Option<u32>,
}

fn encode(header: &[u8], value: &impl serde::Serialize) -> Vec<u8> {
    let payload = bincode::DefaultOptions::new().serialize(value).unwrap();
    let mut gzip = flate2::write::GzEncoder::new(header.to_vec(), flate2::Compression::fast());
    gzip.write_all(&payload).unwrap();
    gzip.finish().unwrap()
}

fn encode_v8(doc: &Document) -> Vec<u8> {
    let old = V8Document {
        width: doc.width,
        height: doc.height,
        layers: doc
            .layers
            .iter()
            .map(|layer| V8Layer {
                id: layer.id,
                name: &layer.name,
                visible: layer.visible,
                opacity: layer.opacity,
                content: match &layer.content {
                    LayerContent::Raster(raster) => V7Content::Raster(raster.clone()),
                    LayerContent::Group { isolation, closed } => V7Content::Group {
                        isolation: *isolation,
                        closed: *closed,
                    },
                    LayerContent::Adjustment { settings } => V7Content::Adjustment {
                        settings: settings.clone(),
                    },
                    LayerContent::Vector(_) | LayerContent::CelTrack { .. } => {
                        panic!("V8 fixture cannot contain vectors or Cel tracks")
                    }
                },
                parent_id: layer.parent_id,
                blend: layer.blend,
                alpha_locked: layer.alpha_locked,
                locked: layer.locked,
                masks: &layer.masks,
                clipping: layer.clipping,
            })
            .collect(),
        active: doc.active,
        next_id: doc.next_id,
        palette: doc.palette.as_ref(),
        next_mask_id: doc.next_mask_id,
        active_mask_id: doc.active_mask_id,
    };
    encode(b"PODOR\x08", &old)
}

fn tone() -> AdjustmentEffect {
    let spec: AdjustmentSpec = serde_json::from_value(json!({
        "kind":"tone","brightness":0.125,"contrast":-0.25,"saturation":0.5
    }))
    .unwrap();
    spec.into()
}

fn plane(default: u8, enabled: bool, linked: bool) -> LayerMask {
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
    tile[..4].copy_from_slice(&[7, 255, 128, 0]);
    plane.tiles.insert((0, 0), Arc::new(tile));
    plane
}

fn raster(indexed: bool) -> RasterPlane {
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
}

fn palette(indexed: bool) -> Option<IndexedPalette> {
    indexed.then_some(IndexedPalette {
        colors: vec![[7, 8, 9, 0], [80, 160, 240, 128], [240, 160, 80, 255]],
        transparent: 0,
        order: vec![2, 0, 1],
    })
}

fn old_fixture(indexed: bool) -> V7Document {
    let make = |id, parent_id, content, mask| V7Layer {
        id,
        name: format!("Legacy {id}"),
        visible: true,
        opacity: 1.0,
        content,
        parent_id,
        blend: BlendMode::Normal,
        alpha_locked: false,
        locked: false,
        mask,
        clipping: false,
    };
    V7Document {
        width: 3,
        height: 1,
        active: 5,
        next_id: 6,
        palette: palette(indexed),
        layers: vec![
            make(1, None, V7Content::Raster(raster(indexed)), None),
            make(
                3,
                None,
                V7Content::Group {
                    isolation: GroupIsolation::Isolated,
                    closed: true,
                },
                Some(plane(255, true, false)),
            ),
            make(4, Some(3), V7Content::Raster(raster(indexed)), None),
            make(
                5,
                Some(3),
                V7Content::Adjustment { settings: tone() },
                Some(plane(0, false, true)),
            ),
        ],
    }
}

#[test]
fn independent_v7_raster_group_and_adjustment_masks_migrate_to_deterministic_global_ids() {
    for indexed in [false, true] {
        let old = old_fixture(indexed);
        let bytes = encode(b"PODOR\x07", &old);
        let mut first = Engine::new(1, 1).unwrap();
        first.load(&bytes).unwrap();
        let mut second = Engine::new(1, 1).unwrap();
        second.load(&bytes).unwrap();
        assert_eq!(first.save().unwrap(), second.save().unwrap());
        assert_eq!(first.document.next_mask_id, 3);
        assert_eq!(first.document.active_mask_id, None);
        assert_eq!(first.document.palette, old.palette);
        assert_eq!(first.document.active, 5);
        assert_eq!(first.document.next_id, 6);
        for (layer, prior) in first.document.layers.iter().zip(&old.layers) {
            assert_eq!((layer.id, layer.parent_id), (prior.id, prior.parent_id));
            assert_eq!(layer.name, prior.name);
            assert_eq!(layer.first_mask(), prior.mask.as_ref());
            assert_eq!(layer.masks.len(), usize::from(prior.mask.is_some()));
            if let V7Content::Raster(raw) = &prior.content {
                assert_eq!(layer.raster().unwrap(), raw);
            }
        }
        assert_eq!(first.document.layers[1].masks[0].id, 1);
        assert_eq!(first.document.layers[3].masks[0].id, 2);
        assert_eq!(first.document.layers[3].masks[0].name, "Mask");
        assert_eq!(
            first.document.layers[3].content,
            LayerContent::Adjustment { settings: tone() }
        );
        let migrated = first.save().unwrap();
        assert!(migrated.starts_with(b"PODOR\x0c"));
        second.load(&migrated).unwrap();
        assert_eq!(second.save().unwrap(), migrated);
    }
}

fn current(indexed: bool) -> Engine {
    let mut engine = Engine::new(1, 1).unwrap();
    engine
        .load(&encode(b"PODOR\x07", &old_fixture(indexed)))
        .unwrap();
    engine.document.layers[1].masks = vec![
        MaskEntry {
            id: 11,
            name: "Soft edge".into(),
            plane: plane(255, true, false),
        },
        MaskEntry {
            id: 14,
            name: "隐藏 蒙版".into(),
            plane: plane(0, false, true),
        },
    ];
    engine.document.layers[3].masks = vec![
        MaskEntry {
            id: 21,
            name: "Pigment".into(),
            plane: plane(255, true, true),
        },
        MaskEntry {
            id: 34,
            name: "Highlights".into(),
            plane: plane(0, false, false),
        },
    ];
    engine.document.next_mask_id = 50;
    engine.document.active_mask_id = Some(34);
    let bytes = engine.save().unwrap();
    engine.load(&bytes).unwrap();
    engine
}

#[test]
fn v8_stack_roundtrip_preserves_order_names_independent_planes_selection_and_counter() {
    for indexed in [false, true] {
        let mut engine = current(indexed);
        let before = engine.save().unwrap();
        let frame = engine.frame_with_background(true);
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&encode_v8(&engine.document)).unwrap();
        assert!(reopened.document == engine.document);
        assert_eq!(reopened.save().unwrap(), before);
        assert_eq!(reopened.document.next_mask_id, 50);
        assert_eq!(reopened.document.active_mask_id, Some(34));
        assert_eq!(reopened.frame_with_background(true), frame);
        let group = &reopened.document.layers[1].masks;
        assert_eq!(
            group.iter().map(|mask| mask.id).collect::<Vec<_>>(),
            [11, 14]
        );
        assert_ne!(group[0].plane, group[1].plane);
    }
}

fn with_history() -> (Engine, Vec<u8>, Vec<u8>, Vec<u8>) {
    let mut engine = current(false);
    let initial = engine.save().unwrap();
    engine
        .command(Command::SetLayer {
            id: 1,
            visible: true,
            opacity: 1.0,
            name: "Checkpoint".into(),
        })
        .unwrap();
    let current = engine.save().unwrap();
    engine
        .command(Command::SetLayer {
            id: 1,
            visible: true,
            opacity: 1.0,
            name: "Redo".into(),
        })
        .unwrap();
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    (engine, initial, current, future)
}

fn assert_atomic(projects: Vec<Vec<u8>>) {
    let (mut engine, initial, current, future) = with_history();
    let (mut control, _, _, _) = with_history();
    let pending = control.frame();
    let state = engine.state();
    for (index, bytes) in projects.into_iter().enumerate() {
        assert!(
            engine.load(&bytes).is_err(),
            "accepted invalid stack {index}"
        );
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
fn invalid_v8_global_ids_counter_target_names_and_mask_limit_reject_atomically() {
    let valid = current(false).document;
    let mut candidates = Vec::new();
    for property in 0..7 {
        let mut bad = valid.clone();
        match property {
            0 => bad.layers[1].masks[0].id = 0,
            1 => bad.layers[3].masks[0].id = 11,
            2 => bad.layers[3].masks[0].id = u32::MAX,
            3 => bad.next_mask_id = 34,
            4 => bad.active_mask_id = Some(11),
            5 => bad.layers[3].masks[0].name.clear(),
            _ => {
                bad.layers[3].masks = (1..=MAX_LAYER_MASKS as u32 + 1)
                    .map(|id| MaskEntry {
                        id: 100 + id,
                        name: format!("Mask {id}"),
                        plane: plane(255, false, true),
                    })
                    .collect()
            }
        }
        if property == 6 {
            bad.next_mask_id = 200;
            bad.active_mask_id = None;
        }
        assert!(bad.validate().is_err());
        candidates.push(encode_v8(&bad));
    }
    assert_atomic(candidates);
}

#[test]
fn invalid_v8_plane_is_not_serialized_and_future_v0c_preserves_current_history() {
    let mut engine = current(false);
    engine.document.layers[3].masks[0]
        .plane
        .tiles
        .insert((0, 0), Arc::new(vec![7; MASK_TILE_BYTES - 1]));
    assert!(engine.save().is_err());
    let invalid = encode_v8(&engine.document);
    let mut future = current(false).save().unwrap();
    future[5] = 13;
    let mut probe = Engine::new(1, 1).unwrap();
    assert!(probe.load(&future).unwrap_err().contains("版本"));
    assert_atomic(vec![invalid, future]);
}
