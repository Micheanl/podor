use bincode::Options;
use podor_engine::{
    animation::{CelKind, CelSource, FrameTag},
    aseprite::{GridMetadata, PaletteMetadata, ProjectMetadata},
    assistants::AssistantSet,
    model::*,
    vector::VectorObject,
    AdjustmentEffect, Command, Engine,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    io::{Read, Write},
    sync::Arc,
};

#[derive(Clone, Serialize, Deserialize)]
struct V11Document {
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
}

#[derive(Clone, Serialize, Deserialize)]
struct V12Document {
    old: V11Document,
    aseprite_metadata: Option<Arc<ProjectMetadata>>,
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
    tiles: BTreeMap<TileKey, u32>,
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
    tiles: BTreeMap<TileKey, u32>,
}

#[derive(Clone, Serialize, Deserialize)]
struct WireAnimation {
    frames: Vec<WireFrame>,
    cels: BTreeMap<u32, WireCel>,
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
    exposures: BTreeMap<u32, u32>,
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

fn encode(header: &[u8], value: &impl Serialize) -> Vec<u8> {
    let bytes = bincode::DefaultOptions::new().serialize(value).unwrap();
    let mut encoder = flate2::write::GzEncoder::new(header.to_vec(), flate2::Compression::fast());
    encoder.write_all(&bytes).unwrap();
    encoder.finish().unwrap()
}

fn wire(bytes: &[u8]) -> V12Document {
    assert_eq!(&bytes[..6], b"PODOR\x0c");
    let mut decoded = Vec::new();
    flate2::read::GzDecoder::new(&bytes[6..])
        .read_to_end(&mut decoded)
        .unwrap();
    bincode::DefaultOptions::new()
        .reject_trailing_bytes()
        .deserialize(&decoded)
        .unwrap()
}

fn metadata() -> Arc<ProjectMetadata> {
    Arc::new(ProjectMetadata {
        companion_palette: Some(PaletteMetadata {
            colors: vec![[170, 40, 70, 255], [170, 40, 70, 255], [20, 110, 180, 64]],
            names: vec![Some("Rose".into()), Some("玫瑰副色".into()), None],
        }),
        indexed_names: Vec::new(),
        grid: GridMetadata {
            x: -7,
            y: 3,
            width: 16,
            height: 24,
        },
        srgb: true,
    })
}

fn legacy() -> V11Document {
    let mask = |id| WireMaskEntry {
        id,
        name: format!("Mask {id}"),
        plane: WireMask {
            bounds: MaskBounds {
                left: 0,
                top: 0,
                right: 128,
                bottom: 128,
            },
            default: 255,
            enabled: true,
            linked: true,
            tiles: BTreeMap::from([((0, 0), 1)]),
        },
    };
    let cel = |id, mask_id| WireCel {
        id,
        layer_id: 1,
        source: WireSource::Raster(0),
        masks: vec![mask(mask_id)],
    };
    V11Document {
        width: 128,
        height: 128,
        layers: vec![WireLayer {
            id: 1,
            name: "Held colors".into(),
            visible: true,
            opacity: 1.0,
            content: WireContent::CelTrack {
                kind: CelKind::Raster,
            },
            parent_id: None,
            blend: BlendMode::Normal,
            alpha_locked: false,
            locked: false,
            masks: Vec::new(),
            clipping: false,
        }],
        active: 1,
        next_id: 2,
        palette: None,
        next_mask_id: 20,
        active_mask_id: Some(17),
        assistants: Default::default(),
        animation: Some(WireAnimation {
            frames: vec![
                WireFrame {
                    id: 3,
                    duration_ms: 40,
                    exposures: BTreeMap::from([(1, 4)]),
                },
                WireFrame {
                    id: 7,
                    duration_ms: 210,
                    exposures: BTreeMap::from([(1, 8)]),
                },
                WireFrame {
                    id: 5,
                    duration_ms: 90,
                    exposures: BTreeMap::from([(1, 4)]),
                },
            ],
            cels: BTreeMap::from([(4, cel(4, 13)), (8, cel(8, 17))]),
            active_frame: 7,
            next_frame_id: 8,
            next_cel_id: 9,
            next_tag_id: 1,
            tags: Vec::new(),
        }),
        buffers: vec![
            [120, 80, 20, 255].repeat(TILE_BYTES / 4),
            vec![160; MASK_TILE_BYTES],
        ],
        rasters: vec![WireRaster {
            indexed: false,
            tiles: BTreeMap::from([((0, 0), 0)]),
        }],
        objects: Vec::new(),
        vectors: Vec::new(),
    }
}

#[test]
fn true_v11_fixture_migrates_without_metadata_or_renumbering_masks() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&encode(b"PODOR\x0b", &legacy())).unwrap();
    assert!(engine.document.aseprite_metadata.is_none());
    assert_eq!(engine.document.active_mask_id, Some(17));
    assert_eq!(engine.document.next_mask_id, 20);
    let animation = engine.document.animation.as_ref().unwrap();
    assert_eq!(animation.active_frame, 7);
    assert_eq!(animation.frames[2].exposures[&1], 4);
    assert_eq!(animation.cels.len(), 2);
    let (CelSource::Raster(a), CelSource::Raster(b)) =
        (&animation.cels[&4].source, &animation.cels[&8].source)
    else {
        panic!("expected raster sources");
    };
    assert!(Arc::ptr_eq(a, b));
    assert!(Arc::ptr_eq(
        &animation.cels[&4].masks[0].plane.tiles[&(0, 0)],
        &animation.cels[&8].masks[0].plane.tiles[&(0, 0)]
    ));
    let saved = engine.save().unwrap();
    let stored = wire(&saved);
    assert!(stored.aseprite_metadata.is_none());
    assert_eq!(stored.old.buffers.len(), 2);
    assert_eq!(stored.old.rasters.len(), 1);
    let expected = engine.document.clone();
    engine.load(&saved).unwrap();
    assert!(engine.document == expected);
    assert!(engine.save().unwrap() == saved);
}

#[test]
fn rgb_companion_palette_names_grid_and_srgb_roundtrip_without_changing_color_mode() {
    let mut engine = Engine::new(128, 128).unwrap();
    engine.document.aseprite_metadata = Some(metadata());
    let tile = Arc::new([120, 80, 20, 255].repeat(TILE_BYTES / 4));
    engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), tile);
    let saved = engine.save().unwrap();
    let stored = wire(&saved);
    assert!(stored.aseprite_metadata == engine.document.aseprite_metadata);
    assert!(stored.old.palette.is_none());
    assert_eq!(stored.old.buffers.len(), 1);
    assert_eq!(stored.old.rasters.len(), 1);
    let expected = engine.document.clone();
    engine.load(&saved).unwrap();
    assert!(engine.document == expected);
    assert!(engine.save().unwrap() == saved);
}

#[test]
fn indexed_slot_names_duplicate_colors_and_nonzero_transparency_roundtrip() {
    let mut engine = Engine::new(128, 128).unwrap();
    engine.document.palette = Some(IndexedPalette {
        colors: vec![[200, 70, 30, 255], [200, 70, 30, 255], [0; 4]],
        transparent: 2,
        order: vec![1, 2, 0],
    });
    engine.document.layers[0].content = LayerContent::Raster(RasterPlane::Indexed(BTreeMap::from(
        [((0, 0), Arc::new(vec![1; INDEX_TILE_BYTES]))],
    )));
    engine.document.aseprite_metadata = Some(Arc::new(ProjectMetadata {
        companion_palette: None,
        indexed_names: vec![Some("Orange".into()), Some("Duplicate orange".into()), None],
        grid: GridMetadata {
            x: 4,
            y: -2,
            width: 8,
            height: 8,
        },
        srgb: false,
    }));
    let saved = engine.save().unwrap();
    let expected = engine.document.clone();
    engine.load(&saved).unwrap();
    assert!(engine.document == expected);
    assert!(engine.save().unwrap() == saved);
    let stored = wire(&saved);
    assert_eq!(stored.old.palette.unwrap().transparent, 2);
    assert!(stored.old.rasters[0].indexed);
    assert!(stored.old.buffers[0].iter().all(|&index| index == 1));
}

#[test]
fn animation_metadata_keeps_distinct_cel_ids_and_one_shared_source_table() {
    let original = V12Document {
        old: legacy(),
        aseprite_metadata: Some(metadata()),
    };
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&encode(b"PODOR\x0c", &original)).unwrap();
    let saved = engine.save().unwrap();
    let stored = wire(&saved);
    assert!(stored.aseprite_metadata == original.aseprite_metadata);
    assert_eq!(stored.old.buffers.len(), 2);
    assert_eq!(stored.old.rasters.len(), 1);
    let animation = stored.old.animation.unwrap();
    assert_eq!(animation.cels.len(), 2);
    assert_eq!(animation.frames[0].exposures[&1], 4);
    assert_eq!(animation.frames[1].exposures[&1], 8);
    assert_eq!(animation.frames[2].exposures[&1], 4);
    let source = engine.document.clone();
    engine.load(&saved).unwrap();
    assert!(engine.document == source);
    assert!(engine.save().unwrap() == saved);
}

fn reject_atomically(cases: Vec<Vec<u8>>) {
    let mut engine = Engine::new(8, 8).unwrap();
    engine.document.aseprite_metadata = Some(metadata());
    let initial = engine.save().unwrap();
    engine
        .command(Command::SetLayer {
            id: 1,
            name: "Checkpoint".into(),
            visible: true,
            opacity: 1.0,
        })
        .unwrap();
    let changed = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    engine.frame_with_background(true);
    let state = engine.state();
    for (i, bytes) in cases.into_iter().enumerate() {
        assert!(
            engine.load(&bytes).is_err(),
            "accepted invalid metadata fixture {i}"
        );
        assert!(
            engine.save().unwrap() == initial,
            "changed SAVE on fixture {i}"
        );
        assert_eq!(engine.state(), state, "changed state on fixture {i}");
    }
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == changed);
    assert!(engine.document.aseprite_metadata == Some(metadata()));
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == initial);
}

#[test]
fn v11_and_v12_are_strict_typed_schemas_instead_of_header_aliases() {
    let old = legacy();
    let current = V12Document {
        old: old.clone(),
        aseprite_metadata: Some(metadata()),
    };
    let mut future = encode(b"PODOR\x0c", &current);
    future[5] = 13;
    reject_atomically(vec![
        encode(b"PODOR\x0c", &old),
        encode(b"PODOR\x0b", &current),
        future,
    ]);
}

#[test]
fn malformed_palette_metadata_is_rejected_without_touching_history_or_saved_project() {
    let original = V12Document {
        old: legacy(),
        aseprite_metadata: Some(metadata()),
    };
    let mut missing_name_slot = original.clone();
    let names = &mut Arc::make_mut(missing_name_slot.aseprite_metadata.as_mut().unwrap())
        .companion_palette
        .as_mut()
        .unwrap()
        .names;
    names.remove(0);
    let mut foreign_indexed_names = original;
    Arc::make_mut(foreign_indexed_names.aseprite_metadata.as_mut().unwrap())
        .indexed_names
        .push(Some("No indexed slot".into()));
    reject_atomically(vec![
        encode(b"PODOR\x0c", &missing_name_slot),
        encode(b"PODOR\x0c", &foreign_indexed_names),
    ]);
}

#[test]
fn new_documents_without_exchange_metadata_are_saved_as_v12() {
    let mut engine = Engine::new(8, 8).unwrap();
    let saved = engine.save().unwrap();
    assert!(wire(&saved).aseprite_metadata.is_none());
    engine.load(&saved).unwrap();
    assert!(engine.document.aseprite_metadata.is_none());
    assert!(engine.save().unwrap() == saved);
}
