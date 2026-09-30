use podor_engine::{model::*, Command, Engine, ExportOptions, LayerActionRequest};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::Arc,
};

fn command(engine: &mut Engine, mut value: Value) -> Result<Value, String> {
    if value.get("revision").is_none() {
        value["revision"] = engine.state()["revision"].clone();
    }
    if value.get("selection_id").is_none() {
        value["selection_id"] = engine.state()["selectionId"].clone();
    }
    engine.command(serde_json::from_value(value).unwrap())
}

fn artwork(width: u32, height: u32) -> Engine {
    let mut engine = Engine::new(width, height).unwrap();
    for y in 0..height {
        for x in 0..width {
            let tile = engine.document.layers[0]
                .raster_mut()
                .unwrap()
                .tiles_mut()
                .entry((x / TILE_SIZE, y / TILE_SIZE))
                .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
            let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&[255; 4]);
        }
    }
    engine
}

fn plane(bounds: MaskBounds, default: u8, gray: u8) -> LayerMask {
    let mut mask = LayerMask::new(bounds, default);
    for y in 0..bounds.height() {
        for x in 0..bounds.width() {
            let tile = mask
                .tiles
                .entry((x / TILE_SIZE, y / TILE_SIZE))
                .or_insert_with(|| Arc::new(vec![default; MASK_TILE_BYTES]));
            Arc::make_mut(tile)[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize] = gray;
        }
    }
    mask
}

fn install(engine: &mut Engine, planes: Vec<LayerMask>) -> Vec<u32> {
    engine.document.layers[0].masks = planes
        .into_iter()
        .enumerate()
        .map(|(index, plane)| MaskEntry {
            id: 0,
            name: format!("Mask {}", index + 1),
            plane,
        })
        .collect();
    engine.document.assign_mask_ids().unwrap();
    engine.document.validate().unwrap();
    engine.document.layers[0]
        .masks
        .iter()
        .map(|mask| mask.id)
        .collect()
}

fn pixels(engine: &Engine) -> Vec<u8> {
    let bytes = engine
        .export_image(ExportOptions {
            transparent: true,
            ..ExportOptions::default()
        })
        .unwrap();
    let mut reader = png::Decoder::new(bytes.as_slice()).read_info().unwrap();
    let mut pixels = vec![0; reader.output_buffer_size()];
    let frame = reader.next_frame(&mut pixels).unwrap();
    pixels.truncate(frame.buffer_size());
    pixels
}

fn tiles(packet: &[u8]) -> BTreeMap<TileKey, Vec<u8>> {
    let read = |offset| u32::from_le_bytes(packet[offset..offset + 4].try_into().unwrap());
    assert_eq!(packet.len(), 16 + read(12) as usize * (8 + TILE_BYTES));
    packet[16..]
        .as_chunks::<{ 8 + TILE_BYTES }>()
        .0
        .iter()
        .map(|record| {
            let x = u32::from_le_bytes(record[..4].try_into().unwrap());
            let y = u32::from_le_bytes(record[4..8].try_into().unwrap());
            ((x, y), record[8..].to_vec())
        })
        .collect()
}

fn coverage(values: &[u8]) -> u8 {
    if values.is_empty() {
        return 255;
    }
    let product = values
        .iter()
        .map(|value| u128::from(*value))
        .product::<u128>();
    let denominator = 255u128.pow(values.len() as u32 - 1);
    ((product + denominator / 2) / denominator) as u8
}

fn assert_coverage(engine: &Engine) {
    let actual = pixels(engine);
    for y in 0..engine.document.height {
        for x in 0..engine.document.width {
            let samples: Vec<_> = engine.document.layers[0]
                .masks
                .iter()
                .filter(|mask| mask.plane.enabled)
                .map(|mask| mask.plane.sample(x as i32, y as i32))
                .collect();
            let alpha = coverage(&samples);
            let expected = if alpha == 0 {
                [0; 4]
            } else {
                [255, 255, 255, alpha]
            };
            let offset = ((y * engine.document.width + x) * 4) as usize;
            assert_eq!(&actual[offset..offset + 4], &expected, "at {x},{y}");
        }
    }
}

fn select(engine: &mut Engine, mask_id: u32, enabled: bool) {
    command(
        engine,
        json!({"type":"set_mask_editing","id":1,"mask_id":mask_id,"enabled":enabled}),
    )
    .unwrap();
}

fn begin(engine: &mut Engine, gray: u8) {
    engine
        .command(Command::Begin {
            brush: Brush {
                color: [gray; 3],
                size: 1.0,
                opacity: 1.0,
                raster: BrushRaster::Pixel,
                size_pressure: 0.0,
                opacity_pressure: 0.0,
                stabilization: 0.0,
                ..Brush::default()
            },
            assistant: None,
        })
        .unwrap();
}

fn sample(engine: &mut Engine, x: f32, y: f32) -> Result<(), String> {
    engine
        .samples(&[Sample {
            x,
            y,
            pressure: 1.0,
        }])
        .map(|_| ())
}

#[test]
fn sixteen_gray_planes_use_one_product_rounding_and_every_reorder_is_pixel_identical() {
    let mut engine = artwork(4, 2);
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: 4,
        bottom: 2,
    };
    let values = [
        231, 247, 239, 251, 244, 233, 252, 245, 236, 249, 240, 253, 243, 235, 250, 242,
    ];
    let ids = install(
        &mut engine,
        values
            .iter()
            .map(|gray| plane(bounds, 255, *gray))
            .collect(),
    );
    assert_coverage(&engine);
    assert!(coverage(&values) > 0);
    let original = pixels(&engine);
    let saved = engine.save().unwrap();
    for id in &ids {
        command(
            &mut engine,
            json!({"type":"reorder_mask","id":1,"mask_id":id,"index":0}),
        )
        .unwrap();
        assert_eq!(pixels(&engine), original);
        assert_coverage(&engine);
        assert_eq!(engine.document.layers[0].masks[0].id, *id);
    }
    assert_ne!(engine.save().unwrap(), saved);
    assert_eq!(
        engine.document.layers[0]
            .masks
            .iter()
            .map(|mask| mask.id)
            .collect::<Vec<_>>(),
        ids.iter().copied().rev().collect::<Vec<_>>()
    );
}

#[test]
fn product_rounding_avoids_the_order_dependent_intermediate_byte_rounding() {
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: 1,
        bottom: 1,
    };
    for values in [vec![1, 128, 128], vec![127, 65, 31], vec![254; 16]] {
        let mut engine = artwork(1, 1);
        install(
            &mut engine,
            values
                .iter()
                .map(|gray| plane(bounds, 255, *gray))
                .collect(),
        );
        assert_coverage(&engine);
    }
    assert_eq!(coverage(&[1, 128, 128]), 0);
    let mix = |left: u16, right: u16| (left * right + 127) / 255;
    assert_eq!(mix(mix(1, 128), 128), 1);
}

#[test]
fn a_single_plane_preserves_all_legacy_gray_coverages() {
    let mut engine = artwork(256, 1);
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 256,
            bottom: 1,
        },
        255,
    );
    for x in 0..256u32 {
        let tile = mask
            .tiles
            .entry((x / TILE_SIZE, 0))
            .or_insert_with(|| Arc::new(vec![255; MASK_TILE_BYTES]));
        Arc::make_mut(tile)[(x % TILE_SIZE) as usize] = x as u8;
    }
    install(&mut engine, vec![mask]);
    let actual = pixels(&engine);
    for x in 0..256usize {
        assert_eq!(actual[x * 4 + 3], x as u8);
    }
    assert_coverage(&engine);
}

#[test]
fn group_and_child_masks_keep_separate_stack_coverage_at_each_compositing_level() {
    let mut engine = artwork(4, 2);
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: 4,
        bottom: 2,
    };
    install(
        &mut engine,
        vec![plane(bounds, 255, 128), plane(bounds, 255, 255)],
    );
    let mut leaf = engine.document.layers.remove(0);
    leaf.parent_id = Some(2);
    let mut group = Layer::group(2, "Group".into(), GroupIsolation::Isolated);
    group.masks = [128, 200]
        .into_iter()
        .map(|gray| MaskEntry {
            id: 0,
            name: format!("Group {gray}"),
            plane: plane(bounds, 255, gray),
        })
        .collect();
    engine.document.layers = vec![group, leaf];
    engine.document.next_id = 3;
    engine.document.assign_mask_ids().unwrap();
    engine.document.validate().unwrap();
    let alpha =
        ((u16::from(coverage(&[128, 200])) * u16::from(coverage(&[128, 255])) + 127) / 255) as u8;
    let original = pixels(&engine);
    for pixel in original.as_chunks::<4>().0 {
        assert_eq!(pixel, &[255, 255, 255, alpha]);
    }
    let id = engine.document.layers[0].masks[1].id;
    command(
        &mut engine,
        json!({"type":"reorder_mask","id":2,"mask_id":id,"index":0}),
    )
    .unwrap();
    assert_eq!(pixels(&engine), original);
}

#[test]
fn adjustment_mask_stack_blends_the_effect_with_combined_coverage_and_keeps_alpha() {
    let mut engine = artwork(4, 2);
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: 4,
        bottom: 2,
    };
    let settings: podor_engine::AdjustmentSpec = serde_json::from_value(
        json!({"kind":"curves","curves":{"rgb":{"points":[{"x":0,"y":255},{"x":255,"y":0}]}}}),
    )
    .unwrap();
    let mut adjustment = Layer::new(2, "Adjustment".into());
    adjustment.content = LayerContent::Adjustment {
        settings: settings.into(),
    };
    adjustment.masks = [128, 200]
        .into_iter()
        .map(|gray| MaskEntry {
            id: 0,
            name: format!("Effect {gray}"),
            plane: plane(bounds, 255, gray),
        })
        .collect();
    engine.document.layers.push(adjustment);
    engine.document.next_id = 3;
    engine.document.active = 2;
    engine.document.assign_mask_ids().unwrap();
    engine.document.validate().unwrap();
    let gray = 255 - coverage(&[128, 200]);
    for pixel in pixels(&engine).as_chunks::<4>().0 {
        assert_eq!(pixel, &[gray, gray, gray, 255]);
    }
    let id = engine.document.layers[1].masks[1].id;
    command(
        &mut engine,
        json!({"type":"set_mask","id":2,"mask_id":id,"enabled":false}),
    )
    .unwrap();
    for pixel in pixels(&engine).as_chunks::<4>().0 {
        assert_eq!(pixel, &[127, 127, 127, 255]);
    }
}

#[test]
fn selecting_and_painting_one_mask_preserves_other_planes_and_cancel_undo_redo() {
    let mut engine = artwork(8, 8);
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: 8,
        bottom: 8,
    };
    let ids = install(
        &mut engine,
        vec![plane(bounds, 255, 200), plane(bounds, 255, 255)],
    );
    let first = engine.document.layers[0].masks[0].clone();
    let raster = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    let state = engine.state();
    select(&mut engine, ids[1], true);
    assert_eq!(engine.state()["revision"], state["revision"]);
    assert_eq!(engine.state()["contentId"], state["contentId"]);
    assert_eq!(engine.state()["activeMaskId"], ids[1]);
    let before = engine.save().unwrap();
    let before_state = engine.state();
    begin(&mut engine, 0);
    sample(&mut engine, 3.5, 4.5).unwrap();
    engine.command(Command::Cancel).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), before_state);
    begin(&mut engine, 0);
    sample(&mut engine, 3.5, 4.5).unwrap();
    engine.command(Command::End).unwrap();
    assert_eq!(engine.document.layers[0].masks[1].plane.sample(3, 4), 0);
    assert_eq!(engine.document.layers[0].masks[1].plane.sample(4, 4), 255);
    assert_eq!(engine.document.layers[0].masks[0], first);
    assert!(Arc::ptr_eq(
        &first.plane.tiles[&(0, 0)],
        &engine.document.layers[0].masks[0].plane.tiles[&(0, 0)]
    ));
    assert!(Arc::ptr_eq(
        &raster,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
    assert_eq!(engine.document.active_mask_id, Some(ids[1]));
}

#[test]
fn each_signed_plane_keeps_its_own_default_and_disabled_masks_do_not_contribute() {
    let mut engine = artwork(6, 2);
    let first = plane(
        MaskBounds {
            left: -2,
            top: -1,
            right: 2,
            bottom: 2,
        },
        255,
        128,
    );
    let second = plane(
        MaskBounds {
            left: 0,
            top: 0,
            right: 4,
            bottom: 2,
        },
        0,
        200,
    );
    let mut disabled = plane(
        MaskBounds {
            left: -1,
            top: -1,
            right: 6,
            bottom: 2,
        },
        0,
        0,
    );
    disabled.enabled = false;
    let ids = install(&mut engine, vec![first, second, disabled]);
    assert_eq!(engine.document.layers[0].masks[0].plane.sample(-1, -1), 128);
    assert_eq!(engine.document.layers[0].masks[1].plane.sample(-1, -1), 0);
    assert_coverage(&engine);
    let actual = pixels(&engine);
    assert_eq!([actual[3], actual[11], actual[19]], [100, 200, 0]);
    command(
        &mut engine,
        json!({"type":"set_mask","id":1,"mask_id":ids[2],"enabled":true}),
    )
    .unwrap();
    assert!(pixels(&engine).iter().all(|byte| *byte == 0));
    engine.command(Command::Undo).unwrap();
    assert_eq!(pixels(&engine), actual);
}

#[test]
fn pixel_moves_all_linked_masks_and_mask_editing_moves_only_the_native_selected_id() {
    let mut engine = artwork(8, 8);
    let bounds = MaskBounds {
        left: -2,
        top: -2,
        right: 6,
        bottom: 6,
    };
    let linked = plane(bounds, 255, 200);
    let mut unlinked = plane(bounds, 255, 128);
    unlinked.linked = false;
    let ids = install(&mut engine, vec![linked, unlinked]);
    let initial = engine.document.clone();
    select(&mut engine, ids[0], false);
    command(
        &mut engine,
        json!({"type":"translate_layer","id":1,"dx":2,"dy":1}),
    )
    .unwrap();
    assert_eq!(engine.document.layers[0].masks[0].plane.bounds.left, 0);
    assert_eq!(
        engine.document.layers[0].masks[1],
        initial.layers[0].masks[1]
    );
    assert!(Arc::ptr_eq(
        &initial.layers[0].masks[0].plane.tiles[&(0, 0)],
        &engine.document.layers[0].masks[0].plane.tiles[&(0, 0)]
    ));
    let raster = engine.document.layers[0].raster().unwrap().tiles().clone();
    let first = engine.document.layers[0].masks[0].clone();
    select(&mut engine, ids[1], true);
    let before = engine.save().unwrap();
    command(
        &mut engine,
        json!({"type":"translate_layer","id":1,"mask_id":ids[1],"dx":-1,"dy":3}),
    )
    .unwrap();
    assert_eq!(engine.document.layers[0].masks[0], first);
    assert_eq!(engine.document.layers[0].masks[1].plane.bounds.left, -3);
    assert_eq!(engine.document.layers[0].masks[1].plane.bounds.top, 1);
    assert_eq!(engine.document.layers[0].raster().unwrap().tiles(), &raster);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn preview_rejects_a_stale_mask_identity_even_without_a_revision_change() {
    let mut engine = artwork(8, 8);
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: 8,
        bottom: 8,
    };
    let ids = install(
        &mut engine,
        vec![plane(bounds, 255, 128), plane(bounds, 255, 200)],
    );
    select(&mut engine, ids[0], true);
    let state = engine.state();
    let request = json!({"id":1,"revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":true,"mask_id":ids[0],"action":{"kind":"translate","dx":1,"dy":0}});
    select(&mut engine, ids[1], true);
    assert_eq!(engine.state()["revision"], state["revision"]);
    let mut combined = tiles(&engine.frame_with_background(true));
    let before = engine.save().unwrap();
    let before_state = engine.state();
    let stale: LayerActionRequest = serde_json::from_value(request).unwrap();
    assert!(engine.preview_layer_action(stale).is_err());
    for value in [
        json!({"type":"translate_layer","id":1,"mask_id":ids[0],"dx":1,"dy":0}),
        json!({"type":"transform_layer","id":1,"mask_id":ids[0],"transform":{"width":8,"height":8}}),
    ] {
        assert!(command(&mut engine, value).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), before_state);
    }
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), before_state);
    let valid: LayerActionRequest = serde_json::from_value(json!({"id":1,"revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":true,"mask_id":ids[1],"action":{"kind":"translate","dx":1,"dy":0}})).unwrap();
    combined.extend(tiles(&engine.preview_layer_action(valid).unwrap()));
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), before_state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    command(
        &mut engine,
        json!({"type":"translate_layer","id":1,"mask_id":ids[1],"dx":1,"dy":0}),
    )
    .unwrap();
    let mut committed = Engine::new(1, 1).unwrap();
    committed.load(&engine.save().unwrap()).unwrap();
    assert_eq!(combined, tiles(&committed.frame_with_background(true)));
}

#[test]
fn duplicate_has_a_fresh_id_and_copy_on_write_pixels_are_independent() {
    let mut engine = artwork(8, 8);
    command(
        &mut engine,
        json!({"type":"add_mask","mode":"reveal","name":"Original"}),
    )
    .unwrap();
    let original_id = engine.document.layers[0].masks[0].id;
    begin(&mut engine, 128);
    sample(&mut engine, 3.5, 4.5).unwrap();
    engine.command(Command::End).unwrap();
    command(
        &mut engine,
        json!({"type":"duplicate_mask","id":1,"mask_id":original_id}),
    )
    .unwrap();
    let masks = &engine.document.layers[0].masks;
    assert_eq!(masks.len(), 2);
    let duplicate_id = masks[1].id;
    assert!(duplicate_id > original_id);
    assert_eq!(masks[0].plane, masks[1].plane);
    assert!(Arc::ptr_eq(
        &masks[0].plane.tiles[&(0, 0)],
        &masks[1].plane.tiles[&(0, 0)]
    ));
    assert_eq!(engine.document.active_mask_id, Some(duplicate_id));
    let before = engine.save().unwrap();
    begin(&mut engine, 0);
    sample(&mut engine, 3.5, 4.5).unwrap();
    engine.command(Command::End).unwrap();
    assert_eq!(engine.document.layers[0].masks[0].plane.sample(3, 4), 128);
    assert_eq!(engine.document.layers[0].masks[1].plane.sample(3, 4), 0);
    assert!(!Arc::ptr_eq(
        &engine.document.layers[0].masks[0].plane.tiles[&(0, 0)],
        &engine.document.layers[0].masks[1].plane.tiles[&(0, 0)]
    ));
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    command(
        &mut engine,
        json!({"type":"delete_mask","id":1,"mask_id":duplicate_id}),
    )
    .unwrap();
    assert_eq!(engine.document.layers[0].masks.len(), 1);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.document.active_mask_id, Some(duplicate_id));
    engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
    let all_ids: Vec<_> = engine
        .document
        .layers
        .iter()
        .flat_map(|layer| &layer.masks)
        .map(|mask| mask.id)
        .collect();
    assert_eq!(
        all_ids.iter().copied().collect::<BTreeSet<_>>().len(),
        all_ids.len()
    );
    engine.document.validate().unwrap();
}

#[test]
fn applying_a_stack_bakes_its_combined_coverage_once_and_undo_restores_all_planes() {
    let mut engine = artwork(4, 2);
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: 4,
        bottom: 2,
    };
    let ids = install(
        &mut engine,
        vec![
            plane(bounds, 255, 127),
            plane(bounds, 255, 65),
            plane(bounds, 255, 31),
        ],
    );
    select(&mut engine, ids[1], true);
    let before = engine.save().unwrap();
    let rendered = pixels(&engine);
    command(&mut engine, json!({"type":"apply_mask","id":1})).unwrap();
    assert!(engine.document.layers[0].masks.is_empty());
    assert_eq!(engine.state()["maskEditing"], false);
    assert_eq!(pixels(&engine), rendered);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.document.active_mask_id, Some(ids[1]));
    assert_eq!(pixels(&engine), rendered);
}

#[test]
fn mask_limit_and_invalid_targets_leave_document_history_and_target_unchanged() {
    let mut engine = artwork(4, 2);
    for index in 0..MAX_LAYER_MASKS {
        command(
            &mut engine,
            json!({"type":"add_mask","mode":"reveal","name":format!("Mask {index}")}),
        )
        .unwrap();
    }
    let ids: Vec<_> = engine.document.layers[0]
        .masks
        .iter()
        .map(|mask| mask.id)
        .collect();
    assert_eq!(
        ids.iter().copied().collect::<BTreeSet<_>>().len(),
        MAX_LAYER_MASKS
    );
    let before = engine.save().unwrap();
    let state = engine.state();
    let revision = state["revision"].as_u64().unwrap();
    for value in [
        json!({"type":"add_mask","mode":"reveal"}),
        json!({"type":"duplicate_mask","id":1,"mask_id":ids[0]}),
        json!({"type":"delete_mask","id":1,"mask_id":u32::MAX}),
        json!({"type":"set_mask_editing","id":1,"mask_id":u32::MAX,"enabled":true}),
        json!({"type":"reorder_mask","id":1,"mask_id":ids[0],"index":MAX_LAYER_MASKS}),
        json!({"type":"set_mask","id":1,"mask_id":ids[0],"name":"x".repeat(MAX_LAYER_NAME_BYTES + 1)}),
        json!({"type":"reorder_mask","id":1,"mask_id":ids[0],"index":1,"revision":revision - 1}),
    ] {
        assert!(command(&mut engine, value).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
}

#[test]
fn duplicate_budget_rejection_is_atomic_even_when_plane_tiles_share_allocations() {
    let mut engine = Engine::new(4096, 4096).unwrap();
    let template = Arc::new(vec![255; MASK_TILE_BYTES]);
    let mask = LayerMask {
        bounds: MaskBounds {
            left: 0,
            top: 0,
            right: 4096,
            bottom: 4096,
        },
        default: 255,
        enabled: false,
        linked: false,
        tiles: (0..32)
            .flat_map(|y| (0..32).map(move |x| (x, y)))
            .map(|key| (key, template.clone()))
            .collect(),
    };
    let ids = install(&mut engine, vec![mask; 8]);
    assert_eq!(engine.document.pixel_bytes(), MAX_DOCUMENT_BYTES);
    let before = engine.document.clone();
    let state = engine.state();
    assert!(command(
        &mut engine,
        json!({"type":"duplicate_mask","id":1,"mask_id":ids[0]})
    )
    .is_err());
    assert!(engine.document == before);
    assert_eq!(engine.state(), state);
}

#[test]
fn painting_over_document_budget_rolls_back_the_independent_target_and_stroke() {
    let mut engine = Engine::new(4096, 4096).unwrap();
    let template = Arc::new(vec![255; TILE_BYTES]);
    for id in 1..=2 {
        let mut layer = Layer::new(id, format!("Layer {id}"));
        layer.raster_mut().unwrap().set_tiles(
            (0..32)
                .flat_map(|y| (0..32).map(move |x| (x, y)))
                .map(|key| (key, template.clone()))
                .collect(),
        );
        if id == 1 {
            engine.document.layers[0] = layer;
        } else {
            engine.document.layers.push(layer);
        }
    }
    engine.document.next_id = 3;
    command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    assert_eq!(engine.document.pixel_bytes(), MAX_DOCUMENT_BYTES);
    let before = engine.document.clone();
    let state = engine.state();
    begin(&mut engine, 0);
    assert!(sample(&mut engine, 3.5, 4.5).is_err());
    assert!(engine.document == before);
    assert_eq!(engine.state(), state);
    begin(&mut engine, 0);
    engine.command(Command::Cancel).unwrap();
    assert!(engine.document == before);
    assert_eq!(engine.state(), state);
}

#[test]
fn repeated_stroke_frames_match_fresh_compositors_for_root_child_and_group_mask_targets() {
    for target in ["root", "child", "group"] {
        let mut engine = artwork(256, 8);
        let bounds = MaskBounds {
            left: 0,
            top: 0,
            right: 256,
            bottom: 8,
        };
        install(
            &mut engine,
            vec![plane(bounds, 255, 128), plane(bounds, 255, 255)],
        );
        let mut leaf = engine.document.layers.remove(0);
        let mut lower = Layer::new(2, "Lower cache".into());
        let background = Arc::new([24, 40, 64, 255].repeat(TILE_BYTES / 4));
        lower
            .raster_mut()
            .unwrap()
            .set_tiles([((0, 0), background.clone()), ((1, 0), background)].into());
        let active = if target == "group" { 3 } else { 1 };
        if target == "root" {
            engine.document.layers = vec![lower, leaf];
            engine.document.next_id = 3;
        } else {
            leaf.parent_id = Some(3);
            let mut group = Layer::group(3, "Group".into(), GroupIsolation::Isolated);
            group.masks = [200, 255]
                .into_iter()
                .map(|gray| MaskEntry {
                    id: 0,
                    name: format!("Group {gray}"),
                    plane: plane(bounds, 255, gray),
                })
                .collect();
            engine.document.layers = vec![lower, group, leaf];
            engine.document.next_id = 4;
        }
        engine.document.active = active;
        engine.document.assign_mask_ids().unwrap();
        engine.document.validate().unwrap();
        let mask_id = engine.document.active_mut().masks[1].id;
        command(
            &mut engine,
            json!({"type":"set_mask_editing","id":active,"mask_id":mask_id,"enabled":true}),
        )
        .unwrap();
        let before = engine.save().unwrap();
        let mut rendered = tiles(&engine.frame_with_background(true));
        begin(&mut engine, 0);
        for (x, y) in [
            (1.5, 1.5),
            (1.5, 1.5),
            (3.5, 1.5),
            (130.5, 1.5),
            (132.5, 3.5),
        ] {
            sample(&mut engine, x, y).unwrap();
            rendered.extend(tiles(&engine.frame_with_background(true)));
            let mut fresh = Engine::new(1, 1).unwrap();
            fresh.load(&engine.save().unwrap()).unwrap();
            assert_eq!(
                rendered,
                tiles(&fresh.frame_with_background(true)),
                "target={target} at {x},{y}"
            );
        }
        engine.command(Command::Cancel).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        rendered.extend(tiles(&engine.frame_with_background(true)));
        let mut fresh = Engine::new(1, 1).unwrap();
        fresh.load(&before).unwrap();
        assert_eq!(rendered, tiles(&fresh.frame_with_background(true)));
    }
}

#[test]
fn unchanged_mask_metadata_and_reorder_do_not_change_history_or_dirty_frames() {
    let mut engine = artwork(4, 2);
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: 4,
        bottom: 2,
    };
    let ids = install(
        &mut engine,
        vec![plane(bounds, 255, 128), plane(bounds, 255, 200)],
    );
    select(&mut engine, ids[0], true);
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let state = engine.state();
    let name = engine.document.layers[0].masks[0].name.clone();
    for value in [
        json!({"type":"set_mask","id":1,"mask_id":ids[0]}),
        json!({"type":"set_mask","id":1,"mask_id":ids[0],"name":name,"enabled":true,"linked":true}),
        json!({"type":"reorder_mask","id":1,"mask_id":ids[0],"index":0}),
    ] {
        command(&mut engine, value).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame_with_background(true).len(), 16);
    }
}

#[test]
fn importing_and_pasting_images_clear_a_preferred_mask_target_and_undo_restores_it() {
    let mut image = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut image, 1, 1);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&[200, 100, 50, 128])
            .unwrap();
    }
    for paste in [false, true] {
        let mut engine = artwork(8, 8);
        let bounds = MaskBounds {
            left: 0,
            top: 0,
            right: 8,
            bottom: 8,
        };
        let ids = install(
            &mut engine,
            vec![plane(bounds, 255, 128), plane(bounds, 255, 200)],
        );
        select(&mut engine, ids[1], true);
        select(&mut engine, ids[1], false);
        assert_eq!(engine.document.active_mask_id, Some(ids[1]));
        assert_eq!(engine.state()["maskEditing"], false);
        let before = engine.save().unwrap();
        let old_masks = engine.document.layers[0].masks.clone();
        let old_raster = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
        if paste {
            let mut packet: Vec<_> = [8u32, 8, 3, 4]
                .into_iter()
                .flat_map(u32::to_le_bytes)
                .collect();
            packet.extend_from_slice(&image);
            engine.paste_image(&packet).unwrap();
        } else {
            engine.import_layer(&image, "Imported").unwrap();
        }
        assert_eq!(engine.document.layers.len(), 2);
        assert_eq!(engine.document.active, 2);
        assert_eq!(engine.document.active_mask_id, None);
        assert!(engine.state()["activeMaskId"].is_null());
        assert_eq!(engine.state()["maskEditing"], false);
        assert!(engine.document.layers[1].masks.is_empty());
        assert_eq!(engine.document.layers[0].masks, old_masks);
        assert!(Arc::ptr_eq(
            &old_raster,
            &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
        ));
        let offset = (((if paste { 4 } else { 3 }) * TILE_SIZE + 3) * 4) as usize;
        assert_eq!(
            &engine.document.layers[1].raster().unwrap().tiles()[&(0, 0)][offset..offset + 4],
            &[100, 50, 25, 128]
        );
        engine.document.validate().unwrap();
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.document.active_mask_id, Some(ids[1]));
        assert_eq!(engine.document.layers[0].masks, old_masks);
        assert_eq!(engine.state()["canUndo"], false);
    }
}

#[test]
fn merging_a_group_clears_its_preferred_mask_target_and_one_undo_restores_the_stack() {
    let mut engine = artwork(8, 8);
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: 8,
        bottom: 8,
    };
    install(
        &mut engine,
        vec![plane(bounds, 255, 128), plane(bounds, 255, 200)],
    );
    let mut leaf = engine.document.layers.remove(0);
    leaf.parent_id = Some(2);
    let mut group = Layer::group(2, "Group".into(), GroupIsolation::Isolated);
    group.masks = [231, 247]
        .into_iter()
        .map(|gray| MaskEntry {
            id: 0,
            name: format!("Group {gray}"),
            plane: plane(bounds, 255, gray),
        })
        .collect();
    engine.document.layers = vec![group, leaf];
    engine.document.active = 2;
    engine.document.next_id = 3;
    engine.document.assign_mask_ids().unwrap();
    let mask_id = engine.document.layers[0].masks[1].id;
    command(
        &mut engine,
        json!({"type":"set_mask_editing","id":2,"mask_id":mask_id,"enabled":true}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","id":2,"mask_id":mask_id,"enabled":false}),
    )
    .unwrap();
    assert_eq!(engine.document.active_mask_id, Some(mask_id));
    let before = engine.save().unwrap();
    let before_pixels = pixels(&engine);
    let old_masks: Vec<_> = engine
        .document
        .layers
        .iter()
        .map(|layer| layer.masks.clone())
        .collect();
    engine.command(Command::MergeVisible).unwrap();
    assert_eq!(engine.document.layers.len(), 1);
    assert_eq!(engine.document.active, 3);
    assert_eq!(engine.document.active_mask_id, None);
    assert!(engine.state()["activeMaskId"].is_null());
    assert_eq!(engine.state()["maskEditing"], false);
    assert!(engine.document.layers[0].masks.is_empty());
    assert_eq!(pixels(&engine), before_pixels);
    engine.document.validate().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.document.active_mask_id, Some(mask_id));
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| layer.masks.clone())
            .collect::<Vec<_>>(),
        old_masks
    );
    assert_eq!(pixels(&engine), before_pixels);
    assert_eq!(engine.state()["canUndo"], false);
}

#[test]
fn undoing_added_or_duplicated_masks_restores_an_unset_native_edit_target() {
    for duplicate in [false, true] {
        let mut source = artwork(8, 8);
        let bounds = MaskBounds {
            left: 0,
            top: 0,
            right: 8,
            bottom: 8,
        };
        let ids = install(
            &mut source,
            vec![plane(bounds, 255, 128), plane(bounds, 255, 200)],
        );
        if duplicate {
            source.document.layers.push(Layer::new(2, "Other".into()));
            source.document.active = 2;
            source.document.next_id = 3;
        }
        let before = source.save().unwrap();
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&before).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.document.active_mask_id, None);
        assert!(engine.state()["activeMaskId"].is_null());
        assert_eq!(engine.state()["maskEditing"], false);
        let old_active = engine.document.active;
        let state = engine.state();
        command(
            &mut engine,
            json!({"type":"set_mask_editing","id":old_active,"enabled":false}),
        )
        .unwrap();
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        let old_masks = engine.document.layers[0].masks.clone();
        let value = if duplicate {
            json!({"type":"duplicate_mask","id":1,"mask_id":ids[0]})
        } else {
            json!({"type":"add_mask","mode":"reveal"})
        };
        command(&mut engine, value).unwrap();
        let added_id = engine.document.active_mask_id.unwrap();
        assert!(!ids.contains(&added_id));
        assert_eq!(engine.document.layers[0].masks.len(), 3);
        assert_eq!(engine.document.active, 1);
        assert_eq!(engine.state()["activeMaskId"], added_id);
        assert_eq!(engine.state()["maskEditing"], true);
        let after = engine.save().unwrap();
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.document.active, old_active);
        assert_eq!(engine.document.layers[0].masks, old_masks);
        assert_eq!(engine.document.active_mask_id, None);
        assert!(engine.state()["activeMaskId"].is_null());
        assert_eq!(engine.state()["maskEditing"], false);
        assert_eq!(engine.state()["canUndo"], false);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), after);
        assert_eq!(engine.document.active_mask_id, Some(added_id));
        assert_eq!(engine.state()["activeMaskId"], added_id);
        assert!(engine.document.layers[0]
            .masks
            .iter()
            .any(|mask| mask.id == added_id));
    }
}
