use bincode::Options;
use podor_engine::{model::*, Command, Engine};
use std::{collections::BTreeMap, io::Write, sync::Arc};

#[derive(serde::Serialize)]
struct V4Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    raster: RasterPlane,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    mask: Option<LayerMask>,
}

#[derive(serde::Serialize)]
struct V4Document {
    width: u32,
    height: u32,
    layers: Vec<V4Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
}

fn encode(header: &[u8], value: &impl serde::Serialize) -> Vec<u8> {
    let bytes = bincode::DefaultOptions::new().serialize(value).unwrap();
    let mut encoder = flate2::write::GzEncoder::new(header.to_vec(), flate2::Compression::fast());
    encoder.write_all(&bytes).unwrap();
    encoder.finish().unwrap()
}

#[test]
fn explicit_v4_migration_preserves_index_slots_masks_and_protection_and_defaults_to_no_clipping() {
    for indexed in [false, true] {
        let mut mask = LayerMask::new(
            MaskBounds {
                left: -3,
                top: 2,
                right: 6,
                bottom: 7,
            },
            255,
        );
        mask.enabled = false;
        mask.linked = false;
        mask.tiles
            .insert((0, 0), Arc::new(vec![80; MASK_TILE_BYTES]));
        let raster = if indexed {
            RasterPlane::Indexed(BTreeMap::from([(
                (0, 0),
                Arc::new(vec![2; INDEX_TILE_BYTES]),
            )]))
        } else {
            RasterPlane::Rgba(BTreeMap::from([(
                (0, 0),
                Arc::new([1, 1, 0, 1].repeat(TILE_BYTES / 4)),
            )]))
        };
        let palette = indexed.then_some(IndexedPalette {
            colors: vec![[0, 0, 0, 0], [255, 255, 0, 1], [34, 55, 66, 0]],
            transparent: 0,
            order: vec![2, 0, 1],
        });
        let old = V4Document {
            width: 32,
            height: 24,
            active: 2,
            next_id: 3,
            palette: palette.clone(),
            layers: vec![
                V4Layer {
                    id: 1,
                    name: "Base".into(),
                    visible: false,
                    opacity: 0.25,
                    raster: raster.clone(),
                    blend: BlendMode::Screen,
                    alpha_locked: false,
                    locked: false,
                    mask: None,
                },
                V4Layer {
                    id: 2,
                    name: "V4 indexed plane".into(),
                    visible: true,
                    opacity: 0.7,
                    raster: raster.clone(),
                    blend: BlendMode::Multiply,
                    alpha_locked: true,
                    locked: true,
                    mask: Some(mask.clone()),
                },
            ],
        };
        let bytes = encode(b"PODOR\x04", &old);
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&bytes).unwrap();
        assert_eq!(engine.document.palette, palette);
        assert_eq!(*engine.document.layers[1].raster().unwrap(), raster);
        assert_eq!(engine.document.layers[1].first_mask(), Some(&mask));
        assert!(engine.document.layers[1].locked && engine.document.layers[1].alpha_locked);
        assert!(engine.document.layers.iter().all(|layer| !layer.clipping));
        assert!(engine.save().unwrap().starts_with(b"PODOR\x0c"));
    }
}

#[test]
fn v5_roundtrip_persists_clipping_relations_and_canonical_pixels_without_baking() {
    let mut engine = Engine::new(32, 24).unwrap();
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: [255, 0, 0, 128],
            tolerance: 0,
            contiguous: true,
            merged: false,
        })
        .unwrap();
    engine.command(Command::AddLayer).unwrap();
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
    let revision = engine.state()["revision"].as_u64().unwrap();
    engine
        .command(Command::SetClipping {
            id: 2,
            clipping: true,
            revision,
        })
        .unwrap();
    let raw = engine.document.layers[1].content.clone();
    let mask = LayerMask::new(
        MaskBounds {
            left: -10,
            top: -10,
            right: 30,
            bottom: 30,
        },
        255,
    );
    engine.document.layers[1].set_first_mask(Some(mask.clone()));
    engine.document.assign_mask_ids().unwrap();
    let original = engine.save().unwrap();
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&original).unwrap();
    assert_eq!(reopened.save().unwrap(), original);
    assert_eq!(reopened.document.layers[1].content, raw);
    assert_eq!(reopened.document.layers[1].first_mask(), Some(&mask));
    assert!(reopened.document.layers[1].clipping);
    assert_eq!(reopened.state()["layers"][1]["clippingBase"], 1);
}

#[test]
fn orphaned_clipping_and_future_schema_are_rejected_without_replacing_document_or_history() {
    let mut engine = Engine::new(8, 8).unwrap();
    engine.command(Command::AddLayer).unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    let mut invalid = engine.document.clone();
    invalid.layers[0].clipping = true;
    let old_layers: Vec<_> = invalid
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
    let old = (
        invalid.width,
        invalid.height,
        old_layers,
        invalid.active,
        invalid.next_id,
        &invalid.palette,
    );
    assert!(engine.load(&encode(b"PODOR\x06", &old)).is_err());
    let mut future = before.clone();
    future[5] = 13;
    assert!(engine.load(&future).unwrap_err().contains("版本"));
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.document.layers.len(), 1);
}
