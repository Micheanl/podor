use bincode::Options;
use podor_engine::{
    animation::{AnimationSet, Cel, CelKind, CelSource, Frame, FrameTag, TagDirection},
    assistants::{Assistant, AssistantSet, Geometry as AssistantGeometry, Point},
    model::*,
    vector::{FillRule, Geometry, Segment, Style, VectorLayer, VectorObject},
    AdjustmentEffect, AdjustmentSpec, Command, Engine,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    io::{Read, Write},
    sync::Arc,
};

#[derive(Clone, Serialize)]
enum V10Content {
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

#[derive(Clone, Serialize)]
struct V10Layer<C> {
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

#[derive(Clone, Serialize)]
struct V10Document<C> {
    width: u32,
    height: u32,
    layers: Vec<V10Layer<C>>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
    next_mask_id: u32,
    active_mask_id: Option<u32>,
    assistants: Arc<AssistantSet>,
}

fn encode(header: &[u8], value: &impl Serialize) -> Vec<u8> {
    let bytes = bincode::DefaultOptions::new().serialize(value).unwrap();
    gzip(header, &bytes)
}

fn gzip(header: &[u8], bytes: &[u8]) -> Vec<u8> {
    let mut encoder = flate2::write::GzEncoder::new(header.to_vec(), flate2::Compression::fast());
    encoder.write_all(bytes).unwrap();
    encoder.finish().unwrap()
}

fn inflate(bytes: &[u8]) -> Vec<u8> {
    let mut decoded = Vec::new();
    flate2::read::GzDecoder::new(&bytes[6..])
        .read_to_end(&mut decoded)
        .unwrap();
    decoded
}

fn assistant_set() -> Arc<AssistantSet> {
    Arc::new(AssistantSet {
        next_id: 5,
        snap_id: Some(2),
        items: vec![Assistant {
            id: 2,
            name: "Legacy axis".into(),
            visible: false,
            geometry: AssistantGeometry::Parallel {
                a: Point { x: -40.0, y: 10.0 },
                b: Point { x: 90.0, y: 100.0 },
            },
        }],
    })
}

fn vector_object(id: u32) -> Arc<VectorObject> {
    Arc::new(VectorObject {
        id,
        name: format!("Editable {id}"),
        visible: true,
        geometry: Geometry::Path {
            segments: vec![
                Segment::MoveTo { x: 4.0, y: 4.0 },
                Segment::CubicTo {
                    c1x: 6.0,
                    c1y: 2.0,
                    c2x: 10.0,
                    c2y: 8.0,
                    x: 12.0,
                    y: 4.0,
                },
                Segment::LineTo { x: 12.0, y: 12.0 },
                Segment::Close,
            ],
        },
        transform: [1.0, 0.0, 0.0, 1.0, 0.0, 0.0],
        style: Style {
            fill: Some([80, 100, 180, 160]),
            stroke: None,
            fill_rule: FillRule::EvenOdd,
        },
    })
}

fn vector_source() -> Arc<VectorLayer> {
    Arc::new(VectorLayer {
        next_object_id: 5,
        objects: vec![vector_object(3)],
    })
}

fn mask(id: u32, tile: Tile) -> MaskEntry {
    let mut plane = LayerMask::new(
        MaskBounds {
            left: -1,
            top: 0,
            right: 127,
            bottom: 128,
        },
        255,
    );
    plane.enabled = id.is_multiple_of(2);
    plane.linked = !id.is_multiple_of(3);
    plane.tiles.insert((0, 0), tile);
    MaskEntry {
        id,
        name: format!("Mask {id}"),
        plane,
    }
}

fn old_fixture(indexed: bool) -> V10Document<V10Content> {
    let layer = |id, parent_id, content, masks, clipping| V10Layer {
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
    let raster = || {
        let tiles = BTreeMap::from([(
            (0, 0),
            Arc::new(if indexed {
                vec![1; INDEX_TILE_BYTES]
            } else {
                [40, 80, 120, 128].repeat(TILE_BYTES / 4)
            }),
        )]);
        if indexed {
            RasterPlane::Indexed(tiles)
        } else {
            RasterPlane::Rgba(tiles)
        }
    };
    let gray = || Arc::new(vec![128; MASK_TILE_BYTES]);
    let spec: AdjustmentSpec = serde_json::from_value(
        json!({"kind":"tone","brightness":0.2,"contrast":-0.1,"saturation":0.3}),
    )
    .unwrap();
    let mut old = V10Document {
        width: 128,
        height: 128,
        active: 5,
        next_id: 8,
        palette: indexed.then_some(IndexedPalette {
            colors: vec![[240, 160, 80, 255], [80, 160, 240, 128], [0; 4]],
            transparent: 2,
            order: vec![1, 2, 0],
        }),
        next_mask_id: 50,
        active_mask_id: Some(34),
        assistants: assistant_set(),
        layers: vec![
            layer(1, None, V10Content::Raster(raster()), vec![], false),
            layer(
                3,
                None,
                V10Content::Group {
                    isolation: GroupIsolation::Isolated,
                    closed: true,
                },
                vec![mask(11, gray()), mask(14, gray())],
                false,
            ),
            layer(4, Some(3), V10Content::Raster(raster()), vec![], false),
            layer(
                6,
                Some(3),
                V10Content::Raster(raster()),
                vec![mask(21, gray())],
                true,
            ),
            layer(
                5,
                Some(3),
                V10Content::Adjustment {
                    settings: spec.into(),
                },
                vec![mask(31, gray()), mask(34, gray())],
                false,
            ),
        ],
    };
    if !indexed {
        old.layers.push(layer(
            7,
            None,
            V10Content::Vector(vector_source()),
            vec![],
            false,
        ));
    }
    old
}

#[test]
fn independent_v10_static_dto_migrates_all_content_without_animation_copies() {
    for indexed in [false, true] {
        let old = old_fixture(indexed);
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&encode(b"PODOR\x0a", &old)).unwrap();
        assert!(engine.document.animation.is_none());
        assert_eq!(engine.state()["animation"], Value::Null);
        assert_eq!(engine.document.active, old.active);
        assert_eq!(engine.document.next_id, old.next_id);
        assert_eq!(engine.document.next_mask_id, old.next_mask_id);
        assert_eq!(engine.document.active_mask_id, old.active_mask_id);
        assert_eq!(engine.document.palette, old.palette);
        assert!(engine.document.assistants == old.assistants);
        assert_eq!(engine.document.layers.len(), old.layers.len());
        for (actual, expected) in engine.document.layers.iter().zip(&old.layers) {
            assert_eq!(actual.id, expected.id);
            assert_eq!(actual.name, expected.name);
            assert_eq!(actual.parent_id, expected.parent_id);
            assert_eq!(actual.clipping, expected.clipping);
            assert!(actual.masks == expected.masks);
            match (&actual.content, &expected.content) {
                (LayerContent::Raster(actual), V10Content::Raster(expected)) => {
                    assert!(actual == expected)
                }
                (
                    LayerContent::Group { isolation, closed },
                    V10Content::Group {
                        isolation: old_isolation,
                        closed: old_closed,
                    },
                ) => assert_eq!((isolation, closed), (old_isolation, old_closed)),
                (
                    LayerContent::Adjustment { settings },
                    V10Content::Adjustment { settings: old },
                ) => assert!(settings == old),
                (LayerContent::Vector(actual), V10Content::Vector(expected)) => {
                    assert!(actual == expected)
                }
                _ => panic!("legacy content changed"),
            }
        }
        let saved = engine.save().unwrap();
        assert_eq!(&saved[..6], b"PODOR\x0c");
        let source = engine.document.clone();
        engine.load(&saved).unwrap();
        assert!(engine.document == source);
        assert!(engine.save().unwrap() == saved);
    }
}

#[derive(Clone, Serialize, Deserialize)]
struct WireDocument {
    width: u32,
    height: u32,
    layers: Vec<WireLayer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
    next_mask_id: u32,
    active_mask_id: Option<u32>,
    assistants: Arc<AssistantSet>,
    animation: Option<WireAnimation>,
    buffers: Vec<Vec<u8>>,
    rasters: Vec<WireRaster>,
    objects: Vec<VectorObject>,
    vectors: Vec<WireVector>,
    aseprite_metadata: Option<Arc<podor_engine::aseprite::ProjectMetadata>>,
}

#[derive(Clone, Serialize, Deserialize)]
struct WireLayer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    content: WireContent,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    masks: Vec<WireMaskEntry>,
    clipping: bool,
}

#[derive(Clone, Serialize, Deserialize)]
enum WireContent {
    Raster(u32),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
    Adjustment {
        settings: AdjustmentEffect,
    },
    Vector(u32),
    CelTrack {
        kind: CelKind,
    },
}

#[derive(Clone, Serialize, Deserialize)]
struct WireRaster {
    indexed: bool,
    tiles: Vec<(TileKey, u32)>,
}

#[derive(Clone, Serialize, Deserialize)]
struct WireVector {
    next_object_id: u32,
    objects: Vec<u32>,
}

#[derive(Clone, Serialize, Deserialize)]
struct WireMaskEntry {
    id: u32,
    name: String,
    plane: WireMask,
}

#[derive(Clone, Serialize, Deserialize)]
struct WireMask {
    bounds: MaskBounds,
    default: u8,
    enabled: bool,
    linked: bool,
    tiles: Vec<(TileKey, u32)>,
}

#[derive(Clone, Serialize, Deserialize)]
struct WireAnimation {
    frames: Vec<WireFrame>,
    cels: Vec<(u32, WireCel)>,
    active_frame: u32,
    next_frame_id: u32,
    next_cel_id: u32,
    next_tag_id: u32,
    tags: Vec<FrameTag>,
}

#[derive(Clone, Serialize, Deserialize)]
struct WireFrame {
    id: u32,
    duration_ms: u32,
    exposures: Vec<(u32, u32)>,
}

#[derive(Clone, Serialize, Deserialize)]
struct WireCel {
    id: u32,
    layer_id: u32,
    source: WireSource,
    masks: Vec<WireMaskEntry>,
}

#[derive(Clone, Serialize, Deserialize)]
enum WireSource {
    Raster(u32),
    Vector(u32),
}

fn wire(bytes: &[u8]) -> WireDocument {
    bincode::DefaultOptions::new()
        .reject_trailing_bytes()
        .deserialize(&inflate(bytes))
        .unwrap()
}

fn fixture() -> Engine {
    let mut engine = Engine::new(128, 128).unwrap();
    let pixel = Arc::new([40, 80, 120, 128].repeat(TILE_BYTES / 4));
    let gray = Arc::new(vec![128; MASK_TILE_BYTES]);
    let raster = Arc::new(RasterPlane::Rgba(BTreeMap::from([((0, 0), pixel)])));
    let raster_copy = Arc::new(raster.as_ref().clone());
    let vector = vector_source();
    let vector_copy = Arc::new(VectorLayer {
        next_object_id: 6,
        objects: vec![vector.objects[0].clone(), vector_object(5)],
    });
    let mut group = Layer::group(3, "Global group".into(), GroupIsolation::Isolated);
    group.masks = vec![mask(10, gray.clone())];
    let mut raster_track = Layer::new(1, "Raster track".into());
    raster_track.content = LayerContent::CelTrack {
        kind: CelKind::Raster,
    };
    raster_track.parent_id = Some(3);
    let mut vector_track = Layer::new(2, "Vector track".into());
    vector_track.content = LayerContent::CelTrack {
        kind: CelKind::Vector,
    };
    vector_track.parent_id = Some(3);
    let spec: AdjustmentSpec =
        serde_json::from_value(json!({"kind":"tone","brightness":0.1,"contrast":0,"saturation":0}))
            .unwrap();
    let mut adjustment = Layer::new(4, "Global tone".into());
    adjustment.content = LayerContent::Adjustment {
        settings: spec.into(),
    };
    engine.document.layers = vec![group, raster_track, vector_track, adjustment];
    engine.document.active = 1;
    engine.document.next_id = 5;
    engine.document.next_mask_id = 20;
    engine.document.active_mask_id = Some(12);
    engine.document.assistants = assistant_set();
    let cel = |id, layer_id, source| {
        Arc::new(Cel {
            id,
            layer_id,
            source,
            masks: vec![mask(id + 10, gray.clone())],
        })
    };
    let cels = BTreeMap::from([
        (1, cel(1, 1, CelSource::Raster(raster.clone()))),
        (2, cel(2, 1, CelSource::Raster(raster))),
        (3, cel(3, 1, CelSource::Raster(raster_copy))),
        (4, cel(4, 2, CelSource::Vector(vector.clone()))),
        (5, cel(5, 2, CelSource::Vector(vector))),
        (6, cel(6, 2, CelSource::Vector(vector_copy))),
    ]);
    engine.document.animation = Some(Arc::new(AnimationSet {
        frames: vec![
            Frame {
                id: 2,
                duration_ms: 100,
                exposures: BTreeMap::from([(1, 1), (2, 4)]),
            },
            Frame {
                id: 7,
                duration_ms: 250,
                exposures: BTreeMap::from([(1, 2), (2, 5)]),
            },
            Frame {
                id: 4,
                duration_ms: 40,
                exposures: BTreeMap::from([(1, 1), (2, 6)]),
            },
            Frame {
                id: 9,
                duration_ms: 1,
                exposures: BTreeMap::from([(1, 3), (2, 4)]),
            },
        ],
        cels,
        active_frame: 7,
        next_frame_id: 10,
        next_cel_id: 10,
        next_tag_id: 8,
        tags: vec![FrameTag {
            id: 3,
            name: "Motion".into(),
            color: [140, 110, 240, 128],
            from_frame: 2,
            to_frame: 9,
            direction: TagDirection::PingPong,
            repeat: 3,
        }],
    }));
    engine.load(&engine.save().unwrap()).unwrap();
    engine
}

fn raster(cel: &Cel) -> &Arc<RasterPlane> {
    let CelSource::Raster(source) = &cel.source else {
        panic!("expected Raster source")
    };
    source
}

fn vector(cel: &Cel) -> &Arc<VectorLayer> {
    let CelSource::Vector(source) = &cel.source else {
        panic!("expected Vector source")
    };
    source
}

#[test]
fn v12_roundtrip_encodes_each_source_vector_object_and_buffer_once_and_restores_sharing() {
    let engine = fixture();
    let saved = engine.save().unwrap();
    let tables = wire(&saved);
    assert_eq!(
        (
            tables.buffers.len(),
            tables.rasters.len(),
            tables.objects.len(),
            tables.vectors.len()
        ),
        (2, 2, 2, 2)
    );
    let animation = engine.document.animation.as_ref().unwrap();
    assert_eq!(
        animation
            .frames
            .iter()
            .map(|frame| frame.id)
            .collect::<Vec<_>>(),
        [2, 7, 4, 9]
    );
    assert_eq!(animation.frames[2].exposures[&1], 1);
    assert_eq!(animation.next_frame_id, 10);
    assert_eq!(animation.next_cel_id, 10);
    assert_eq!(animation.next_tag_id, 8);
    assert_eq!(animation.tags[0].direction, TagDirection::PingPong);
    assert_eq!(animation.tags[0].repeat, 3);
    let cels = &animation.cels;
    assert!(Arc::ptr_eq(raster(&cels[&1]), raster(&cels[&2])));
    assert!(!Arc::ptr_eq(raster(&cels[&1]), raster(&cels[&3])));
    assert!(Arc::ptr_eq(
        &raster(&cels[&1]).tiles()[&(0, 0)],
        &raster(&cels[&3]).tiles()[&(0, 0)]
    ));
    assert!(Arc::ptr_eq(vector(&cels[&4]), vector(&cels[&5])));
    assert!(!Arc::ptr_eq(vector(&cels[&4]), vector(&cels[&6])));
    assert!(Arc::ptr_eq(
        &vector(&cels[&4]).objects[0],
        &vector(&cels[&6]).objects[0]
    ));
    let mask_tile = &engine.document.layers[0].masks[0].plane.tiles[&(0, 0)];
    for cel in cels.values() {
        assert!(Arc::ptr_eq(mask_tile, &cel.masks[0].plane.tiles[&(0, 0)]));
    }
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&saved).unwrap();
    assert!(reopened.document == engine.document);
    assert!(reopened.save().unwrap() == saved);
}

#[test]
fn indexed_cels_keep_nondefault_transparency_slots_and_shared_gray_buffers() {
    let mut engine = fixture();
    let doc = &mut engine.document;
    doc.layers.remove(2);
    doc.palette = Some(IndexedPalette {
        colors: vec![[240, 160, 80, 255], [80, 160, 240, 128], [0; 4]],
        transparent: 2,
        order: vec![1, 2, 0],
    });
    let tile = Arc::new(vec![1; INDEX_TILE_BYTES]);
    let source = Arc::new(RasterPlane::Indexed(BTreeMap::from([(
        (0, 0),
        tile.clone(),
    )])));
    doc.layers[0].masks[0].plane.tiles = BTreeMap::from([((0, 0), tile.clone())]);
    let animation = Arc::make_mut(doc.animation.as_mut().unwrap());
    animation.cels.retain(|_, cel| cel.layer_id == 1);
    for frame in &mut animation.frames {
        frame.exposures.remove(&2);
    }
    for cel in animation.cels.values_mut() {
        let cel = Arc::make_mut(cel);
        cel.source = CelSource::Raster(source.clone());
        cel.masks[0].plane.tiles = BTreeMap::from([((0, 0), tile.clone())]);
    }
    let saved = engine.save().unwrap();
    let tables = wire(&saved);
    assert_eq!(
        (
            tables.buffers.len(),
            tables.rasters.len(),
            tables.objects.len(),
            tables.vectors.len()
        ),
        (1, 1, 0, 0)
    );
    engine.load(&saved).unwrap();
    assert_eq!(engine.document.palette.as_ref().unwrap().transparent, 2);
    assert_eq!(engine.document.palette.as_ref().unwrap().order, [1, 2, 0]);
    let animation = engine.document.animation.as_ref().unwrap();
    for cel in animation.cels.values() {
        let source = raster(cel);
        assert!(source.is_indexed());
        assert!(source.tiles()[&(0, 0)].iter().all(|index| *index == 1));
        assert!(Arc::ptr_eq(
            &source.tiles()[&(0, 0)],
            &cel.masks[0].plane.tiles[&(0, 0)]
        ));
    }
    assert!(engine.save().unwrap() == saved);
}

#[test]
fn held_exposures_do_not_multiply_source_bytes_or_create_duplicate_cels() {
    let mut engine = fixture();
    let animation = Arc::make_mut(engine.document.animation.as_mut().unwrap());
    animation.frames = (1..=256)
        .map(|id| Frame {
            id,
            duration_ms: 100,
            exposures: BTreeMap::from([(1, 1), (2, 4)]),
        })
        .collect();
    animation.cels.retain(|id, _| [1, 4].contains(id));
    animation.active_frame = 7;
    animation.next_frame_id = 257;
    engine.document.active_mask_id = Some(11);
    let saved = engine.save().unwrap();
    let tables = wire(&saved);
    assert_eq!(tables.animation.as_ref().unwrap().cels.len(), 2);
    assert_eq!(
        (
            tables.buffers.len(),
            tables.rasters.len(),
            tables.objects.len(),
            tables.vectors.len()
        ),
        (2, 1, 1, 1)
    );
    assert!(inflate(&saved).len() < 100_000);
    engine.load(&saved).unwrap();
    let animation = engine.document.animation.as_ref().unwrap();
    assert_eq!(animation.frames.len(), 256);
    assert_eq!(animation.cels.len(), 2);
    assert!(animation
        .frames
        .iter()
        .all(|frame| frame.exposures[&1] == 1 && frame.exposures[&2] == 4));
    assert!(engine.save().unwrap() == saved);
}

fn atomic_rejections(candidates: Vec<Vec<u8>>) {
    let mut engine = Engine::new(8, 8).unwrap();
    let initial = engine.save().unwrap();
    engine
        .command(Command::SetLayer {
            id: 1,
            name: "Checkpoint".into(),
            visible: true,
            opacity: 1.0,
        })
        .unwrap();
    let checkpoint = engine.save().unwrap();
    engine
        .command(Command::SetLayer {
            id: 1,
            name: "Future".into(),
            visible: true,
            opacity: 1.0,
        })
        .unwrap();
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    engine.frame_with_background(true);
    let state = engine.state();
    for (i, bytes) in candidates.into_iter().enumerate() {
        assert!(engine.load(&bytes).is_err(), "accepted invalid fixture {i}");
        assert!(
            engine.save().unwrap() == checkpoint,
            "changed storage on invalid fixture {i}"
        );
        assert_eq!(
            engine.state(),
            state,
            "changed state on invalid fixture {i}"
        );
        assert_eq!(engine.frame_with_background(true).len(), 16);
    }
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == future);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == checkpoint);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == initial);
}

#[test]
fn foreign_duplicate_or_unreferenced_shared_tables_are_rejected_atomically() {
    let original = wire(&fixture().save().unwrap());
    let mut cases = Vec::new();
    let mut push = |change: fn(&mut WireDocument)| {
        let mut candidate = original.clone();
        change(&mut candidate);
        cases.push(encode(b"PODOR\x0c", &candidate));
    };
    push(|doc| doc.buffers[0].truncate(10));
    push(|doc| doc.rasters[0].tiles[0].1 = 999);
    push(|doc| doc.vectors[0].objects[0] = 999);
    push(|doc| doc.layers[0].masks[0].plane.tiles[0].1 = 999);
    push(|doc| doc.animation.as_mut().unwrap().cels[0].1.source = WireSource::Raster(999));
    push(|doc| doc.animation.as_mut().unwrap().cels[3].1.source = WireSource::Vector(999));
    push(|doc| doc.buffers.push(doc.buffers[0].clone()));
    push(|doc| doc.rasters.push(doc.rasters[0].clone()));
    push(|doc| doc.vectors.push(doc.vectors[0].clone()));
    push(|doc| doc.objects.push(doc.objects[0].clone()));
    push(|doc| doc.objects[0].id = 0);
    push(|doc| {
        let entry = doc.rasters[0].tiles[0];
        doc.rasters[0].tiles.push(entry);
    });
    push(|doc| {
        let entry = doc.layers[0].masks[0].plane.tiles[0];
        doc.layers[0].masks[0].plane.tiles.push(entry);
    });
    push(|doc| {
        let a = doc.animation.as_mut().unwrap();
        a.cels.push(a.cels[0].clone());
    });
    push(|doc| {
        let f = &mut doc.animation.as_mut().unwrap().frames[0];
        f.exposures.push(f.exposures[0]);
    });
    atomic_rejections(cases);
}

#[test]
fn invalid_animation_ids_refs_modes_times_tags_and_inactive_masks_are_rejected_atomically() {
    let original = wire(&fixture().save().unwrap());
    let mut cases = Vec::new();
    let mut push = |change: fn(&mut WireDocument)| {
        let mut candidate = original.clone();
        change(&mut candidate);
        cases.push(encode(b"PODOR\x0c", &candidate));
    };
    push(|doc| doc.animation.as_mut().unwrap().frames[1].id = 2);
    push(|doc| doc.animation.as_mut().unwrap().active_frame = 999);
    push(|doc| doc.animation.as_mut().unwrap().next_frame_id = 7);
    push(|doc| doc.animation.as_mut().unwrap().next_cel_id = 6);
    push(|doc| doc.animation.as_mut().unwrap().next_tag_id = 3);
    push(|doc| doc.animation.as_mut().unwrap().frames[0].duration_ms = 0);
    push(|doc| doc.animation.as_mut().unwrap().frames[0].duration_ms = 60_001);
    push(|doc| doc.animation.as_mut().unwrap().frames[0].exposures[0].0 = 999);
    push(|doc| doc.animation.as_mut().unwrap().frames[0].exposures[0].1 = 999);
    push(|doc| doc.animation.as_mut().unwrap().cels[0].1.layer_id = 2);
    push(|doc| doc.animation.as_mut().unwrap().cels[0].1.source = WireSource::Vector(0));
    push(|doc| doc.animation.as_mut().unwrap().cels[0].1.id = 7);
    push(|doc| doc.rasters[1].tiles[0].0 = (999, 999));
    push(|doc| {
        let a = doc.animation.as_mut().unwrap();
        let mut cel = a.cels[0].1.clone();
        cel.id = 7;
        cel.masks[0].id = 17;
        a.cels.push((7, cel));
    });
    push(|doc| doc.animation.as_mut().unwrap().tags[0].from_frame = 999);
    push(|doc| doc.animation.as_mut().unwrap().tags[0].to_frame = 999);
    push(|doc| doc.animation.as_mut().unwrap().tags[0].name.clear());
    push(|doc| {
        let a = doc.animation.as_mut().unwrap();
        a.tags.push(a.tags[0].clone());
    });
    push(|doc| doc.animation.as_mut().unwrap().cels[2].1.masks[0].id = 11);
    push(|doc| {
        doc.animation.as_mut().unwrap().cels[2].1.masks[0]
            .plane
            .bounds
            .right = 20_000
    });
    push(|doc| {
        let mask = doc.layers[0].masks[0].clone();
        doc.layers[1].masks.push(mask);
    });
    push(|doc| doc.animation.as_mut().unwrap().frames.clear());
    push(|doc| {
        let a = doc.animation.as_mut().unwrap();
        a.frames = (1..=257)
            .map(|id| WireFrame {
                id,
                duration_ms: 100,
                exposures: a.frames[((id - 1) % 4) as usize].exposures.clone(),
            })
            .collect();
        a.next_frame_id = 258;
    });
    atomic_rejections(cases);
}

fn forbidden_v10_content() -> Vec<u8> {
    let old = V10Document {
        width: 8,
        height: 8,
        active: 1,
        next_id: 2,
        palette: None,
        next_mask_id: 1,
        active_mask_id: None,
        assistants: Default::default(),
        layers: vec![V10Layer {
            id: 1,
            name: "Forbidden".into(),
            visible: true,
            opacity: 1.0,
            content: WireContent::CelTrack {
                kind: CelKind::Raster,
            },
            parent_id: None,
            blend: BlendMode::Normal,
            alpha_locked: false,
            locked: false,
            masks: vec![],
            clipping: false,
        }],
    };
    encode(b"PODOR\x0a", &old)
}

#[test]
fn v10_cannot_smuggle_animation_fields_or_new_content_and_future_versions_stay_atomic() {
    let old = old_fixture(false);
    let mut appended = bincode::DefaultOptions::new().serialize(&old).unwrap();
    appended.push(0);
    let mut future = fixture().save().unwrap();
    future[5] = 13;
    let mut truncated = fixture().save().unwrap();
    truncated.truncate(truncated.len() / 2);
    atomic_rejections(vec![
        gzip(b"PODOR\x0a", &appended),
        forbidden_v10_content(),
        future,
        truncated,
    ]);
}

fn command(engine: &mut Engine, mut value: Value) {
    value["revision"] = engine.state()["revision"].clone();
    engine
        .command(serde_json::from_value(value).unwrap())
        .unwrap();
}

#[test]
fn frame_duration_tag_and_independent_duplicate_save_load_and_undo_restore_canonical_data() {
    let mut engine = fixture();
    let initial = engine.save().unwrap();
    command(
        &mut engine,
        json!({"type":"set_frame_duration","frame_id":7,"duration_ms":321}),
    );
    let changed = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == initial);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == changed);
    command(
        &mut engine,
        json!({"type":"set_frame_tag","id":3,"tag":{"name":"Backward","from_frame":2,"to_frame":9,"direction":"ping_pong_reverse","repeat":1,"color":[40,80,160,255]}}),
    );
    let tagged = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == changed);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == tagged);
    command(
        &mut engine,
        json!({"type":"duplicate_frame","frame_id":7,"index":2,"linked":false,"select":true}),
    );
    let duplicated = engine.save().unwrap();
    let animation = engine.document.animation.as_ref().unwrap();
    assert_eq!(animation.frames.len(), 5);
    let old = animation.frames.iter().find(|frame| frame.id == 7).unwrap();
    let new = &animation.frames[2];
    assert_ne!(old.exposures[&1], new.exposures[&1]);
    let old = &animation.cels[&old.exposures[&1]];
    let new = &animation.cels[&new.exposures[&1]];
    assert!(Arc::ptr_eq(raster(old), raster(new)));
    assert_ne!(old.masks[0].id, new.masks[0].id);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == tagged);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == duplicated);
    let expected = engine.document.clone();
    engine.load(&duplicated).unwrap();
    assert!(engine.document == expected);
    assert!(engine.save().unwrap() == duplicated);
}
