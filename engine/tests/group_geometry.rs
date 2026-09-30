use podor_engine::{model::*, Command, Engine, LayerActionRequest};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

type Frame = BTreeMap<TileKey, Vec<u8>>;

fn command(engine: &mut Engine, mut value: Value) -> Result<Value, String> {
    if value.get("revision").is_none() {
        value["revision"] = engine.state()["revision"].clone();
    }
    engine.command(serde_json::from_value(value).unwrap())
}

fn put_rect(layer: &mut Layer, area: Rect, color: [u8; 4]) {
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
            let index = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[index..index + 4].copy_from_slice(&color);
        }
    }
}

fn mask(linked: bool) -> LayerMask {
    let mut mask = LayerMask::new(
        MaskBounds {
            left: -16,
            top: -8,
            right: 240,
            bottom: 120,
        },
        255,
    );
    mask.linked = linked;
    for (x, y, gray) in [
        (-14, -6, 32),
        (40, 20, 128),
        (150, 70, 0),
        (151, 70, 0),
        (150, 71, 0),
        (151, 71, 0),
    ] {
        let x = (x - mask.bounds.left) as u32;
        let y = (y - mask.bounds.top) as u32;
        let tile = Arc::make_mut(
            mask.tiles
                .entry((x / TILE_SIZE, y / TILE_SIZE))
                .or_insert_with(|| Arc::new(vec![255; MASK_TILE_BYTES])),
        );
        tile[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize] = gray;
    }
    mask
}

fn fixture(linked: bool) -> Engine {
    let mut engine = Engine::new(256, 128).unwrap();
    put_rect(
        &mut engine.document.layers[0],
        Rect {
            left: 0,
            top: 0,
            right: 256,
            bottom: 128,
        },
        [12, 24, 36, 64],
    );
    let mut outer = Layer::group(2, "Outer".into(), GroupIsolation::Isolated);
    outer.set_first_mask(Some(mask(linked)));
    let mut visible = Layer::new(3, "Visible".into());
    visible.parent_id = Some(2);
    visible.set_first_mask(Some(mask(linked)));
    put_rect(
        &mut visible,
        Rect {
            left: 40,
            top: 20,
            right: 42,
            bottom: 22,
        },
        [200, 40, 20, 255],
    );
    let mut inner = Layer::group(4, "Inner".into(), GroupIsolation::Isolated);
    inner.parent_id = Some(2);
    inner.set_first_mask(Some(mask(!linked)));
    let mut hidden = Layer::new(5, "Hidden".into());
    hidden.parent_id = Some(4);
    hidden.visible = false;
    hidden.set_first_mask(Some(mask(!linked)));
    put_rect(
        &mut hidden,
        Rect {
            left: 80,
            top: 30,
            right: 82,
            bottom: 32,
        },
        [20, 200, 40, 255],
    );
    let mut gated = Layer::new(6, "Outside visible gate".into());
    gated.parent_id = Some(2);
    gated.set_first_mask(Some(mask(linked)));
    put_rect(
        &mut gated,
        Rect {
            left: 150,
            top: 70,
            right: 152,
            bottom: 72,
        },
        [20, 40, 200, 255],
    );
    engine
        .document
        .layers
        .extend([outer, visible, inner, hidden, gated]);
    engine.document.active = 2;
    engine.document.next_id = 7;
    engine.document.assign_mask_ids().unwrap();
    engine.load(&engine.save().unwrap()).unwrap();
    engine.frame();
    engine
}

fn pixel(layer: &Layer, x: u32, y: u32) -> [u8; 4] {
    layer
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| {
            let index = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[index..index + 4].try_into().unwrap()
        })
}

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
    let output: Frame = records
        .iter()
        .map(|record| ((read(record, 0), read(record, 4)), record[8..].to_vec()))
        .collect();
    assert_eq!(output.len(), records.len());
    output
}

fn canonical(engine: &Engine, transparent: bool) -> Frame {
    let mut copy = Engine::new(1, 1).unwrap();
    copy.load(&engine.save().unwrap()).unwrap();
    let mut output = tiles(
        &copy.frame_with_background(transparent),
        engine.document.width,
        engine.document.height,
    );
    for y in 0..engine.document.height.div_ceil(TILE_SIZE) {
        for x in 0..engine.document.width.div_ceil(TILE_SIZE) {
            output
                .entry((x, y))
                .or_insert_with(|| vec![if transparent { 0 } else { 255 }; TILE_BYTES]);
        }
    }
    output
}

fn verify_preview(engine: &mut Engine, transparent: bool, mask_editing: bool, actions: &[Value]) {
    command(
        engine,
        json!({"type":"set_mask_editing","id":2,"enabled":mask_editing}),
    )
    .unwrap();
    engine.frame_with_background(transparent);
    let saved = engine.save().unwrap();
    let state = engine.state();
    let outline = engine.selection_outline_mask();
    let original = canonical(engine, transparent);
    for action in actions {
        let request: LayerActionRequest = serde_json::from_value(json!({"id":2,"revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":mask_editing,"action":action})).unwrap();
        let preview = engine.preview_layer_action(request).unwrap();
        let diff = tiles(&preview, engine.document.width, engine.document.height);
        if action["kind"] == "translate" && action["dx"] == 0 && action["dy"] == 0 {
            assert!(diff.is_empty());
        }
        for (key, tile) in &diff {
            assert_ne!(tile, &original[key]);
        }
        assert_eq!(engine.save().unwrap(), saved);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.selection_outline_mask(), outline);
        assert_eq!(engine.frame_with_background(transparent).len(), 16);
        let mut committed = Engine::new(1, 1).unwrap();
        committed.load(&saved).unwrap();
        command(
            &mut committed,
            json!({"type":"set_mask_editing","id":2,"enabled":mask_editing}),
        )
        .unwrap();
        if action["kind"] == "translate" {
            command(
                &mut committed,
                json!({"type":"translate_layer","id":2,"dx":action["dx"],"dy":action["dy"]}),
            )
            .unwrap();
        } else {
            command(
                &mut committed,
                json!({"type":"transform_layer","id":2,"transform":action["transform"]}),
            )
            .unwrap();
        }
        let mut combined = original.clone();
        combined.extend(diff);
        assert_eq!(
            combined,
            canonical(&committed, transparent),
            "mask={mask_editing} action={action}"
        );
        for (before, after) in engine
            .document
            .layers
            .iter()
            .zip(&committed.document.layers)
        {
            assert_eq!(before.visible, after.visible);
            assert_eq!(before.parent_id, after.parent_id);
            if mask_editing || before.id == 1 {
                assert_eq!(before.raster_opt(), after.raster_opt());
            }
            if before.id == 1
                || (mask_editing && before.id != 2)
                || (!mask_editing && before.first_mask().is_some_and(|mask| !mask.linked))
            {
                assert_eq!(before.first_mask().cloned(), after.first_mask().cloned());
            }
        }
        if committed.save().unwrap() != saved {
            let result = committed.save().unwrap();
            committed.command(Command::Undo).unwrap();
            assert_eq!(committed.save().unwrap(), saved);
            committed.command(Command::Redo).unwrap();
            assert_eq!(committed.save().unwrap(), result);
        }
    }
}

#[test]
fn group_translation_moves_hidden_and_gated_raw_pixels_and_all_linked_level_masks() {
    for linked in [false, true] {
        let mut engine = fixture(linked);
        let saved = engine.save().unwrap();
        let masks: Vec<_> = engine
            .document
            .layers
            .iter()
            .map(|layer| layer.first_mask().cloned())
            .collect();
        command(
            &mut engine,
            json!({"type":"translate_layer","id":2,"dx":13,"dy":7}),
        )
        .unwrap();
        for (index, x, y, color) in [
            (2, 40, 20, [200, 40, 20, 255]),
            (4, 80, 30, [20, 200, 40, 255]),
            (5, 150, 70, [20, 40, 200, 255]),
        ] {
            assert_eq!(pixel(&engine.document.layers[index], x + 13, y + 7), color);
            assert_eq!(pixel(&engine.document.layers[index], x, y), [0; 4]);
        }
        assert!(!engine.document.layers[4].visible);
        for (before, layer) in masks.iter().zip(&engine.document.layers) {
            if let Some(before) = before {
                let after = layer.first_mask().unwrap();
                if before.linked {
                    assert_eq!(
                        after.bounds,
                        MaskBounds {
                            left: before.bounds.left + 13,
                            top: before.bounds.top + 7,
                            right: before.bounds.right + 13,
                            bottom: before.bounds.bottom + 7
                        }
                    );
                    assert_eq!(after.tiles, before.tiles);
                    assert_eq!(after.sample(-1, 1), before.sample(-14, -6));
                } else {
                    assert_eq!(after, before);
                }
            }
        }
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), saved);
    }
}

#[test]
fn group_flip_uses_one_union_world_center_including_hidden_and_mask_gated_pixels() {
    let mut engine = fixture(true);
    assert_eq!(
        engine.layer_transform_bounds().unwrap(),
        MaskBounds {
            left: 40,
            top: 20,
            right: 152,
            bottom: 72
        }
    );
    let source = engine.document.clone();
    command(&mut engine, json!({"type":"transform_layer","id":2,"transform":{"width":112,"height":52,"flip_x":true,"filter":"nearest"}})).unwrap();
    for (index, x, y, color) in [
        (2, 40, 20, [200, 40, 20, 255]),
        (4, 80, 30, [20, 200, 40, 255]),
        (5, 150, 70, [20, 40, 200, 255]),
    ] {
        assert_eq!(pixel(&engine.document.layers[index], 191 - x, y), color);
        assert_eq!(pixel(&engine.document.layers[index], x, y), [0; 4]);
    }
    assert!(!engine.document.layers[4].visible);
    for (before, after) in source.layers.iter().zip(&engine.document.layers) {
        if let Some(mask) = before.first_mask() {
            let result = after.first_mask().unwrap();
            if mask.linked {
                assert_eq!(
                    result.bounds,
                    MaskBounds {
                        left: 192 - mask.bounds.right,
                        top: mask.bounds.top,
                        right: 192 - mask.bounds.left,
                        bottom: mask.bounds.bottom
                    }
                );
                for (x, y) in [(-14, -6), (40, 20), (150, 70)] {
                    assert_eq!(result.sample(191 - x, y), mask.sample(x, y));
                }
            } else {
                assert_eq!(result, mask);
            }
        }
    }
}

#[test]
fn group_geometry_preview_diffs_merge_with_fixed_original_for_both_backgrounds() {
    let actions = [
        json!({"kind":"translate","dx":0,"dy":0}),
        json!({"kind":"translate","dx":91,"dy":7}),
        json!({"kind":"translate","dx":-17,"dy":-9}),
        json!({"kind":"transform","transform":{"width":112,"height":52,"flip_x":true,"filter":"nearest"}}),
    ];
    for linked in [false, true] {
        for transparent in [false, true] {
            verify_preview(&mut fixture(linked), transparent, false, &actions);
        }
    }
}

#[test]
fn group_mask_only_geometry_preserves_every_child_and_has_independent_canonical_previews() {
    let actions = [
        json!({"kind":"translate","dx":0,"dy":0}),
        json!({"kind":"translate","dx":9,"dy":5}),
        json!({"kind":"transform","transform":{"width":256,"height":128,"flip_x":true,"filter":"nearest"}}),
    ];
    for linked in [false, true] {
        for transparent in [false, true] {
            verify_preview(&mut fixture(linked), transparent, true, &actions);
        }
    }
}

#[test]
fn selection_and_protected_descendants_reject_group_geometry_before_partial_writes() {
    for protected in ["selection", "locked", "alpha_locked"] {
        let mut engine = fixture(true);
        if protected == "selection" {
            command(&mut engine,json!({"type":"select_shape","selection":{"kind":"rectangle","left":40,"top":20,"right":41,"bottom":21}})).unwrap();
        } else {
            let child = &mut engine.document.layers[4];
            if protected == "locked" {
                child.locked = true;
            } else {
                child.alpha_locked = true;
            }
            engine.load(&engine.save().unwrap()).unwrap();
        }
        engine.frame();
        let saved = engine.save().unwrap();
        let state = engine.state();
        for action in [
            json!({"kind":"translate","dx":13,"dy":7}),
            json!({"kind":"transform","transform":{"width":112,"height":52,"flip_x":true,"filter":"nearest"}}),
        ] {
            let request:LayerActionRequest=serde_json::from_value(json!({"id":2,"revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":false,"action":action})).unwrap();
            assert!(engine.preview_layer_action(request).is_err());
            let mutation = if action["kind"] == "translate" {
                json!({"type":"translate_layer","id":2,"dx":13,"dy":7})
            } else {
                json!({"type":"transform_layer","id":2,"transform":action["transform"]})
            };
            assert!(command(&mut engine, mutation).is_err());
            assert_eq!(engine.save().unwrap(), saved);
            assert_eq!(engine.state(), state);
            assert_eq!(engine.frame().len(), 16);
        }
    }
}
