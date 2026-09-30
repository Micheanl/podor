use podor_engine::{model::*, Command, Engine, LayerAction, LayerActionRequest};
use serde_json::json;
use std::{collections::BTreeMap, sync::Arc};

type Frame = BTreeMap<TileKey, Vec<u8>>;

fn read(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}

fn tiles(bytes: &[u8], width: u32, height: u32) -> Frame {
    assert_eq!(
        [read(bytes, 0), read(bytes, 4), read(bytes, 8)],
        [width, height, TILE_SIZE]
    );
    let (records, remainder) = bytes[16..].as_chunks::<{ 8 + TILE_BYTES }>();
    assert!(remainder.is_empty());
    assert_eq!(records.len(), read(bytes, 12) as usize);
    let result: Frame = records
        .iter()
        .map(|record| ((read(record, 0), read(record, 4)), record[8..].to_vec()))
        .collect();
    assert_eq!(result.len(), records.len());
    result
}

fn canonical(engine: &Engine, transparent: bool) -> Frame {
    let mut copy = Engine::new(1, 1).unwrap();
    copy.load(&engine.save().unwrap()).unwrap();
    let mut result = tiles(
        &copy.frame_with_background(transparent),
        engine.document.width,
        engine.document.height,
    );
    for y in 0..engine.document.height.div_ceil(TILE_SIZE) {
        for x in 0..engine.document.width.div_ceil(TILE_SIZE) {
            result
                .entry((x, y))
                .or_insert_with(|| vec![if transparent { 0 } else { 255 }; TILE_BYTES]);
        }
    }
    result
}

fn rectangle(engine: &mut Engine, id: u32, area: Rect, color: [u8; 4]) {
    let layer = engine
        .document
        .layers
        .iter_mut()
        .find(|layer| layer.id == id)
        .unwrap();
    for y in area.top..area.bottom {
        for x in area.left..area.right {
            let tile = Arc::make_mut(
                layer
                    .raster_mut()
                    .unwrap()
                    .tiles_mut()
                    .entry((x / TILE_SIZE, y / TILE_SIZE))
                    .or_insert_with(|| Arc::new(vec![0; TILE_BYTES])),
            );
            let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[offset..offset + 4].copy_from_slice(&color);
        }
    }
}

fn gray_rectangle(mask: &mut LayerMask, area: Rect, coverage: u8) {
    for y in area.top..area.bottom {
        for x in area.left..area.right {
            let x = (x as i32 - mask.bounds.left) as u32;
            let y = (y as i32 - mask.bounds.top) as u32;
            let tile = Arc::make_mut(
                mask.tiles
                    .entry((x / TILE_SIZE, y / TILE_SIZE))
                    .or_insert_with(|| Arc::new(vec![mask.default; MASK_TILE_BYTES])),
            );
            tile[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize] = coverage;
        }
    }
}

fn gray_mask() -> LayerMask {
    let mut mask = LayerMask::new(
        MaskBounds {
            left: -8,
            top: -8,
            right: 376,
            bottom: 184,
        },
        255,
    );
    gray_rectangle(
        &mut mask,
        Rect {
            left: 126,
            top: 24,
            right: 143,
            bottom: 51,
        },
        128,
    );
    gray_rectangle(
        &mut mask,
        Rect {
            left: 116,
            top: 40,
            right: 124,
            bottom: 55,
        },
        0,
    );
    gray_rectangle(
        &mut mask,
        Rect {
            left: 280,
            top: 140,
            right: 300,
            bottom: 160,
        },
        128,
    );
    mask
}

fn fixture(active: u32, enabled: bool, linked: bool, mask_editing: bool) -> Engine {
    let mut engine = Engine::new(384, 192).unwrap();
    for id in 2..=4 {
        engine
            .document
            .layers
            .push(Layer::new(id, format!("Layer {id}")));
    }
    engine.document.next_id = 5;
    engine.document.active = active;
    rectangle(
        &mut engine,
        1,
        Rect {
            left: 0,
            top: 0,
            right: 384,
            bottom: 192,
        },
        [24, 40, 64, 128],
    );
    rectangle(
        &mut engine,
        2,
        Rect {
            left: 112,
            top: 24,
            right: 156,
            bottom: 56,
        },
        [96, 32, 16, 128],
    );
    rectangle(
        &mut engine,
        2,
        Rect {
            left: 270,
            top: 136,
            right: 305,
            bottom: 162,
        },
        [24, 72, 36, 96],
    );
    rectangle(
        &mut engine,
        3,
        Rect {
            left: 100,
            top: 16,
            right: 178,
            bottom: 68,
        },
        [0, 0, 192, 192],
    );
    rectangle(
        &mut engine,
        3,
        Rect {
            left: 258,
            top: 124,
            right: 316,
            bottom: 174,
        },
        [64, 128, 0, 160],
    );
    rectangle(
        &mut engine,
        4,
        Rect {
            left: 146,
            top: 16,
            right: 151,
            bottom: 36,
        },
        [25, 40, 20, 64],
    );
    engine.document.layers[1].blend = BlendMode::Multiply;
    engine.document.layers[2].blend = BlendMode::Screen;
    engine.document.layers[2].clipping = true;
    engine.document.layers[3].opacity = 0.7;
    for layer in &mut engine.document.layers[1..=2] {
        layer.set_first_mask(Some(gray_mask()));
    }
    let mask = engine.document.layers[(active - 1) as usize]
        .first_mask_mut()
        .unwrap();
    mask.enabled = enabled;
    mask.linked = linked;
    engine.document.assign_mask_ids().unwrap();
    engine.document.validate().unwrap();
    engine
        .command(Command::SetMaskEditing {
            mask_id: None,
            id: Some(active),
            enabled: mask_editing,
        })
        .unwrap();
    engine
}

fn select_aa(engine: &mut Engine) {
    engine
        .command(
            serde_json::from_value(json!({
                "type":"select_shape",
                "selection":{
                    "kind":"lasso","left":110,"top":22,"right":155,"bottom":55,
                    "points":[
                        {"x":110.25,"y":22.25}, {"x":154.75,"y":23.75},
                        {"x":153.25,"y":54.75}, {"x":111.75,"y":53.25}
                    ]
                }
            }))
            .unwrap(),
        )
        .unwrap();
    let mask = engine.selection_outline_mask();
    let (records, remainder) = mask[12..]
        .as_chunks::<{ 8 + (SELECTION_PREVIEW_TILE_SIZE * SELECTION_PREVIEW_TILE_SIZE) as usize }>(
        );
    assert!(remainder.is_empty());
    assert!(records
        .iter()
        .flat_map(|record| &record[8..])
        .any(|value| *value > 0 && *value < 255));
}

fn verify_moves(
    engine: &mut Engine,
    transparent: bool,
    mask_editing: bool,
    selected: bool,
    disabled: bool,
) {
    if selected {
        select_aa(engine);
    }
    let original = canonical(engine, transparent);
    engine.frame_with_background(transparent);
    assert_eq!(engine.frame_with_background(transparent).len(), 16);
    let state = engine.state();
    let saved = engine.save().unwrap();
    let selection = engine.selection_outline_mask();
    let active = engine.document.active;
    for (dx, dy) in [(0, 0), (19, 7), (-17, -9), (131, 4)] {
        let preview = engine
            .preview_layer_action(LayerActionRequest {
                frame_id: None,
                cel_id: None,
                target_layer_id: None,
                mask_id: None,
                id: active,
                revision: state["revision"].as_u64().unwrap(),
                selection_id: state["selectionId"].as_u64().unwrap(),
                mask_editing,
                action: LayerAction::Translate { dx, dy },
            })
            .unwrap();
        let diff = tiles(&preview, engine.document.width, engine.document.height);
        if (dx, dy) == (0, 0) || (mask_editing && disabled) {
            assert!(
                diff.is_empty(),
                "active={active} mask={mask_editing} selected={selected} offset={dx},{dy}"
            );
        } else {
            assert!(
                !diff.is_empty(),
                "active={active} mask={mask_editing} selected={selected} offset={dx},{dy}"
            );
        }
        for (key, tile) in &diff {
            assert_ne!(tile, &original[key]);
        }
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), saved);
        assert_eq!(engine.selection_outline_mask(), selection);
        assert_eq!(engine.frame_with_background(transparent).len(), 16);
        let mut committed = Engine::new(1, 1).unwrap();
        committed.load(&saved).unwrap();
        committed
            .command(Command::SetMaskEditing {
                mask_id: None,
                id: Some(active),
                enabled: mask_editing,
            })
            .unwrap();
        if selected {
            select_aa(&mut committed);
        }
        committed
            .command(Command::TranslateLayer {
                mask_id: None,
                id: active,
                dx,
                dy,
            })
            .unwrap();
        for (before, after) in engine
            .document
            .layers
            .iter()
            .zip(&committed.document.layers)
        {
            assert_eq!(before.id, after.id);
            if mask_editing || before.id != active {
                assert_eq!(
                    before.raster().unwrap().tiles(),
                    after.raster().unwrap().tiles()
                );
            }
            if before.id != active
                || (!mask_editing && (selected || !before.first_mask().unwrap().linked))
            {
                assert_eq!(before.first_mask().cloned(), after.first_mask().cloned());
            }
        }
        let mut combined = original.clone();
        combined.extend(diff);
        assert_eq!(combined, canonical(&committed, transparent), "active={active} mask={mask_editing} selected={selected} transparent={transparent} offset={dx},{dy}");
    }
}

#[test]
fn pixel_moves_with_linked_and_unlinked_enabled_and_disabled_masks_return_independent_diffs() {
    for active in [2, 3] {
        for enabled in [false, true] {
            for linked in [false, true] {
                for transparent in [false, true] {
                    let mut engine = fixture(active, enabled, linked, false);
                    verify_moves(&mut engine, transparent, false, false, !enabled);
                }
            }
        }
    }
}

#[test]
fn mask_only_moves_preserve_other_pixels_and_disabled_masks_produce_no_diff() {
    for active in [2, 3] {
        for enabled in [false, true] {
            for linked in [false, true] {
                for transparent in [false, true] {
                    let mut engine = fixture(active, enabled, linked, true);
                    verify_moves(&mut engine, transparent, true, false, !enabled);
                }
            }
        }
    }
}

#[test]
fn antialiased_selected_pixel_and_mask_moves_merge_against_the_fixed_original_frame() {
    for active in [2, 3] {
        for mask_editing in [false, true] {
            for enabled in [false, true] {
                for transparent in [false, true] {
                    let mut engine = fixture(active, enabled, true, mask_editing);
                    verify_moves(&mut engine, transparent, mask_editing, true, !enabled);
                }
            }
        }
    }
}
