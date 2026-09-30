use bincode::Options;
use podor_engine::{model::*, Command, Engine};
use std::{collections::BTreeMap, io::Write, sync::Arc};

#[derive(serde::Serialize)]
struct V5Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    raster: RasterPlane,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    mask: Option<LayerMask>,
    clipping: bool,
}

#[derive(serde::Serialize)]
struct V5Document {
    width: u32,
    height: u32,
    layers: Vec<V5Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
}

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

fn v6_payload(doc: &Document) -> Vec<u8> {
    let old = V6Document {
        width: doc.width,
        height: doc.height,
        active: doc.active,
        next_id: doc.next_id,
        palette: doc.palette.clone(),
        layers: doc
            .layers
            .iter()
            .map(|layer| V6Layer {
                id: layer.id,
                name: layer.name.clone(),
                visible: layer.visible,
                opacity: layer.opacity,
                content: match &layer.content {
                    LayerContent::Raster(raster) => V6Content::Raster(raster.clone()),
                    LayerContent::Group { isolation, closed } => V6Content::Group {
                        isolation: *isolation,
                        closed: *closed,
                    },
                    LayerContent::Adjustment { .. }
                    | LayerContent::Vector(_)
                    | LayerContent::CelTrack { .. } => {
                        panic!("V6 fixture cannot contain adjustments, vectors or Cel tracks")
                    }
                },
                parent_id: layer.parent_id,
                blend: layer.blend,
                alpha_locked: layer.alpha_locked,
                locked: layer.locked,
                mask: layer.first_mask().cloned(),
                clipping: layer.clipping,
            })
            .collect(),
    };
    bincode::DefaultOptions::new().serialize(&old).unwrap()
}

fn encode_v6(doc: &Document) -> Vec<u8> {
    encode_payload(b"PODOR\x06", &v6_payload(doc))
}

fn encode_payload(header: &[u8], payload: &[u8]) -> Vec<u8> {
    let mut encoder = flate2::write::GzEncoder::new(header.to_vec(), flate2::Compression::fast());
    encoder.write_all(payload).unwrap();
    encoder.finish().unwrap()
}

fn encode(header: &[u8], value: &impl serde::Serialize) -> Vec<u8> {
    encode_payload(
        header,
        &bincode::DefaultOptions::new().serialize(value).unwrap(),
    )
}

fn palette(indexed: bool) -> Option<IndexedPalette> {
    indexed.then_some(IndexedPalette {
        colors: vec![
            [0, 0, 0, 0],
            [7, 150, 233, 1],
            [250, 20, 30, 128],
            [34, 55, 66, 0],
        ],
        transparent: 0,
        order: vec![3, 1, 0, 2],
    })
}

fn raster(indexed: bool, value: u8) -> RasterPlane {
    let pixels = if indexed {
        vec![value; INDEX_TILE_BYTES]
    } else {
        [value, value / 2, 0, value.max(128)].repeat(TILE_BYTES / 4)
    };
    let tiles = BTreeMap::from([
        ((0, 0), Arc::new(pixels.clone())),
        ((1, 1), Arc::new(pixels)),
    ]);
    if indexed {
        RasterPlane::Indexed(tiles)
    } else {
        RasterPlane::Rgba(tiles)
    }
}

fn mask() -> LayerMask {
    let mut result = LayerMask::new(
        MaskBounds {
            left: -3,
            top: -2,
            right: 130,
            bottom: 133,
        },
        255,
    );
    result.linked = false;
    result.enabled = false;
    result.tiles = BTreeMap::from([
        ((0, 0), Arc::new(vec![80; MASK_TILE_BYTES])),
        ((1, 1), Arc::new(vec![160; MASK_TILE_BYTES])),
    ]);
    result
}

#[test]
fn independent_v5_fixture_migrates_canonical_planes_masks_clipping_and_palette_to_root_leaves() {
    for indexed in [false, true] {
        let old = V5Document {
            width: 129,
            height: 131,
            active: 9,
            next_id: 13,
            palette: palette(indexed),
            layers: vec![
                V5Layer {
                    id: 4,
                    name: "底色 🎨".into(),
                    visible: true,
                    opacity: 0.4,
                    raster: raster(indexed, 1),
                    blend: BlendMode::Screen,
                    alpha_locked: false,
                    locked: false,
                    mask: None,
                    clipping: false,
                },
                V5Layer {
                    id: 9,
                    name: "Clipped pigment".into(),
                    visible: true,
                    opacity: 0.7,
                    raster: raster(indexed, 2),
                    blend: BlendMode::Multiply,
                    alpha_locked: true,
                    locked: true,
                    mask: Some(mask()),
                    clipping: true,
                },
                V5Layer {
                    id: 12,
                    name: "Hidden raw color".into(),
                    visible: false,
                    opacity: 0.2,
                    raster: raster(indexed, 3),
                    blend: BlendMode::Difference,
                    alpha_locked: false,
                    locked: false,
                    mask: Some(mask()),
                    clipping: true,
                },
            ],
        };
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&encode(b"PODOR\x05", &old)).unwrap();
        assert_eq!(
            (
                engine.document.width,
                engine.document.height,
                engine.document.active,
                engine.document.next_id
            ),
            (129, 131, 9, 13)
        );
        assert_eq!(engine.document.palette, old.palette);
        assert_eq!(
            engine
                .document
                .layers
                .iter()
                .map(|layer| layer.id)
                .collect::<Vec<_>>(),
            [4, 9, 12]
        );
        for (layer, prior) in engine.document.layers.iter().zip(&old.layers) {
            assert!(!layer.is_group());
            assert_eq!(layer.parent_id, None);
            assert_eq!(layer.name, prior.name);
            assert_eq!(layer.visible, prior.visible);
            assert_eq!(layer.opacity, prior.opacity);
            assert_eq!(layer.blend, prior.blend);
            assert_eq!(layer.alpha_locked, prior.alpha_locked);
            assert_eq!(layer.locked, prior.locked);
            assert_eq!(layer.clipping, prior.clipping);
            assert_eq!(layer.first_mask(), prior.mask.as_ref());
            assert_eq!(layer.raster().unwrap(), &prior.raster);
        }
        assert_eq!(engine.state()["layers"][1]["clippingBase"], 4);
        assert_eq!(engine.state()["layers"][2]["clippingBase"], 4);
        let migrated = engine.save().unwrap();
        assert!(migrated.starts_with(b"PODOR\x0c"));
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&migrated).unwrap();
        assert_eq!(reopened.save().unwrap(), migrated);
    }
}

fn nested(indexed: bool) -> Document {
    let mut result = Document::new(129, 131).unwrap();
    result.palette = palette(indexed);
    let leaf = |id, parent, value| {
        let mut layer = Layer::new(id, format!("Pigment {id}"));
        layer.parent_id = parent;
        layer.content = LayerContent::Raster(raster(indexed, value));
        layer
    };
    let mut outer = Layer::group(20, "Outer 图层组".into(), GroupIsolation::Isolated);
    outer.opacity = 0.7;
    outer.blend = BlendMode::Screen;
    outer.locked = true;
    outer.set_first_mask(Some(mask()));
    outer.content = LayerContent::Group {
        isolation: GroupIsolation::Isolated,
        closed: true,
    };
    let mut pass = Layer::group(30, "Pass through".into(), GroupIsolation::PassThrough);
    pass.parent_id = Some(20);
    pass.content = LayerContent::Group {
        isolation: GroupIsolation::PassThrough,
        closed: true,
    };
    let mut clip = leaf(40, Some(30), 2);
    clip.clipping = true;
    clip.alpha_locked = true;
    clip.set_first_mask(Some(mask()));
    let mut clipped_group = Layer::group(50, "Clipped group".into(), GroupIsolation::Isolated);
    clipped_group.parent_id = Some(20);
    clipped_group.clipping = true;
    clipped_group.opacity = 0.5;
    clipped_group.blend = BlendMode::Multiply;
    let mut linked = mask();
    linked.linked = true;
    linked.enabled = true;
    clipped_group.set_first_mask(Some(linked));
    let mut hidden = leaf(51, Some(50), 3);
    hidden.visible = false;
    let mut root_pass = Layer::group(60, "Hidden pass".into(), GroupIsolation::PassThrough);
    root_pass.visible = false;
    result.layers = vec![
        leaf(4, None, 2),
        outer,
        pass,
        leaf(35, Some(30), 1),
        clip,
        leaf(45, Some(20), 1),
        clipped_group,
        hidden,
        root_pass,
        leaf(61, Some(60), 3),
    ];
    result.active = 20;
    result.next_id = 62;
    result.assign_mask_ids().unwrap();
    result
}

#[test]
fn v6_nested_group_roundtrip_preserves_all_metadata_and_raw_planes_byte_for_byte() {
    for indexed in [false, true] {
        let doc = nested(indexed);
        doc.validate().unwrap();
        let bytes = encode_v6(&doc);
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&bytes).unwrap();
        let migrated = engine.save().unwrap();
        assert!(migrated.starts_with(b"PODOR\x0c"));
        assert_eq!(
            bincode::DefaultOptions::new()
                .serialize(&engine.document)
                .unwrap(),
            bincode::DefaultOptions::new().serialize(&doc).unwrap()
        );
        assert_eq!(engine.document.active, 20);
        assert_eq!(engine.state()["layers"][1]["kind"], "group");
        assert_eq!(engine.state()["layers"][1]["closed"], true);
        assert_eq!(engine.state()["layers"][2]["isolation"], "pass_through");
        assert_eq!(engine.state()["layers"][4]["clippingBase"], 35);
        assert_eq!(engine.state()["layers"][6]["clippingBase"], 45);
        assert_eq!(engine.document.palette, doc.palette);
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&engine.save().unwrap()).unwrap();
        assert_eq!(reopened.save().unwrap(), migrated);
    }
}

struct HistoryStates {
    initial: Vec<u8>,
    base: Vec<u8>,
    empty: Vec<u8>,
    painted: Vec<u8>,
}

fn with_history() -> (Engine, HistoryStates) {
    let mut engine = Engine::new(16, 16).unwrap();
    let initial = engine.save().unwrap();
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: [255, 0, 0, 255],
            tolerance: 0,
            contiguous: true,
            merged: false,
        })
        .unwrap();
    let base = engine.save().unwrap();
    engine.command(Command::AddLayer).unwrap();
    let empty = engine.save().unwrap();
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: [0, 0, 255, 255],
            tolerance: 0,
            contiguous: true,
            merged: false,
        })
        .unwrap();
    let painted = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    (
        engine,
        HistoryStates {
            initial,
            base,
            empty,
            painted,
        },
    )
}

fn assert_history(engine: &mut Engine, history: &HistoryStates) {
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), history.painted);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), history.empty);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), history.base);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), history.initial);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), history.base);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), history.empty);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), history.painted);
}

fn assert_rejected_projects(projects: Vec<Vec<u8>>) {
    let (mut engine, history) = with_history();
    let (mut control, _) = with_history();
    let pending_frame = control.frame();
    let before = engine.save().unwrap();
    let state = engine.state();
    assert_eq!(state["canUndo"], true);
    assert_eq!(state["canRedo"], true);
    for (index, bytes) in projects.into_iter().enumerate() {
        assert!(
            engine.load(&bytes).is_err(),
            "accepted invalid project {index}"
        );
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        let frame = engine.frame();
        if index == 0 {
            assert_eq!(frame, pending_frame);
        } else {
            assert_eq!(frame.len(), 16);
        }
    }
    assert_history(&mut engine, &history);
}

#[test]
fn invalid_v6_parent_order_cycles_and_depth_do_not_change_history_state_or_dirty_tiles() {
    let valid = nested(false);
    let mut candidates = Vec::new();
    let mut non_contiguous = valid.clone();
    non_contiguous.layers.swap(7, 8);
    candidates.push(non_contiguous);
    let mut self_parent = valid.clone();
    self_parent.layers[1].parent_id = Some(20);
    candidates.push(self_parent);
    let mut cycle = valid.clone();
    cycle.layers[1].parent_id = Some(30);
    cycle.layers[2].parent_id = Some(20);
    candidates.push(cycle);
    let mut unresolved = valid.clone();
    unresolved.layers[3].parent_id = Some(999);
    candidates.push(unresolved);
    let mut raster_parent = valid.clone();
    raster_parent.layers[3].parent_id = Some(4);
    candidates.push(raster_parent);
    let mut parent_later = valid;
    parent_later.layers.swap(1, 2);
    candidates.push(parent_later);
    let mut deep = Document::new(16, 16).unwrap();
    deep.layers = (1..=MAX_GROUP_DEPTH as u32 + 1)
        .map(|id| {
            let mut group = Layer::group(id, format!("Level {id}"), GroupIsolation::Isolated);
            group.parent_id = (id > 1).then_some(id - 1);
            group
        })
        .collect();
    deep.active = 1;
    deep.next_id = MAX_GROUP_DEPTH as u32 + 2;
    candidates.push(deep);
    for doc in &candidates {
        assert!(doc.validate().is_err());
    }
    assert_rejected_projects(candidates.iter().map(encode_v6).collect());
}

#[test]
fn invalid_v6_group_masks_modes_alpha_locks_and_clip_boundaries_are_rejected_atomically() {
    let mut candidates = Vec::new();
    let valid = nested(false);
    let mut alpha_locked = valid.clone();
    alpha_locked.layers[1].alpha_locked = true;
    candidates.push(alpha_locked);
    for property in 0..4 {
        let mut invalid = valid.clone();
        invalid.layers[1].content = LayerContent::Group {
            isolation: GroupIsolation::PassThrough,
            closed: false,
        };
        invalid.layers[1].opacity = 1.0;
        invalid.layers[1].blend = BlendMode::Normal;
        invalid.layers[1].set_first_mask(None);
        match property {
            0 => invalid.layers[1].opacity = 0.5,
            1 => invalid.layers[1].set_first_mask(Some(mask())),
            2 => invalid.layers[1].blend = BlendMode::Multiply,
            _ => invalid.layers[1].clipping = true,
        }
        invalid.assign_mask_ids().unwrap();
        candidates.push(invalid);
    }
    let mut crossing = valid.clone();
    crossing.layers[3].clipping = true;
    candidates.push(crossing);
    let mut group_base = valid;
    group_base.layers[5].clipping = true;
    candidates.push(group_base);
    for doc in &candidates {
        assert!(doc.validate().is_err());
    }
    assert_rejected_projects(candidates.iter().map(encode_v6).collect());
}

#[test]
fn future_and_corrupted_group_projects_do_not_discard_existing_undo_redo_or_pending_pixels() {
    let valid = nested(false);
    let current = encode_v6(&valid);
    let mut future = current.clone();
    future[5] = 13;
    let mut trailing = v6_payload(&valid);
    trailing.push(0);
    let mut truncated = current;
    truncated.truncate(12);
    let (mut engine, _) = with_history();
    assert!(engine.load(&future).unwrap_err().contains("版本"));
    assert_rejected_projects(vec![
        future,
        encode_payload(b"PODOR\x06", &trailing),
        truncated,
    ]);
}
