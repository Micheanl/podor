use podor_engine::{
    model::*, AdjustmentEffect, AdjustmentSpec, Command, Engine, ExportFormat, ExportOptions,
    IndexedExportPolicy, LayerActionRequest,
};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

fn command(engine: &mut Engine, mut value: Value) -> Result<Value, String> {
    if value.get("revision").is_none() {
        value["revision"] = engine.state()["revision"].clone();
    }
    if value.get("selection_id").is_none() {
        value["selection_id"] = engine.state()["selectionId"].clone();
    }
    engine.command(serde_json::from_value(value).unwrap())
}

fn effect(settings: Value) -> AdjustmentEffect {
    let spec: AdjustmentSpec = serde_json::from_value(settings).unwrap();
    spec.into()
}

fn node(id: u32, settings: Value) -> Layer {
    let mut layer = Layer::new(id, "Adjustment".into());
    layer.content = LayerContent::Adjustment {
        settings: effect(settings),
    };
    layer
}

fn tone(brightness: f32) -> Value {
    json!({"kind":"tone","brightness":brightness,"contrast":0.0,"saturation":0.0})
}

fn inverse() -> Value {
    json!({"kind":"curves","curves":{"rgb":{"points":[{"x":0,"y":255},{"x":255,"y":0}]}}})
}

fn gradient() -> Value {
    json!({"kind":"gradient_map","gradient_map":{"stops":[{"position":0.0,"color":[255,0,0]},{"position":1.0,"color":[0,0,255]}]}})
}

fn put(layer: &mut Layer, x: u32, color: [u8; 4]) {
    let tile = layer
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .entry((x / TILE_SIZE, 0))
        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
    let offset = (x % TILE_SIZE * 4) as usize;
    Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&color);
}

fn reload(engine: &mut Engine) {
    engine.document.assign_mask_ids().unwrap();
    engine.document.next_id = engine
        .document
        .layers
        .iter()
        .map(|layer| layer.id)
        .max()
        .unwrap()
        + 1;
    engine.load(&engine.save().unwrap()).unwrap();
    engine.frame_with_background(true);
}

fn tiles(bytes: &[u8]) -> BTreeMap<TileKey, Vec<u8>> {
    assert_eq!(
        bytes.len(),
        16 + u32::from_le_bytes(bytes[12..16].try_into().unwrap()) as usize * (8 + TILE_BYTES)
    );
    bytes[16..]
        .as_chunks::<{ 8 + TILE_BYTES }>()
        .0
        .iter()
        .map(|record| {
            (
                (
                    u32::from_le_bytes(record[..4].try_into().unwrap()),
                    u32::from_le_bytes(record[4..8].try_into().unwrap()),
                ),
                record[8..].to_vec(),
            )
        })
        .collect()
}

fn canonical(engine: &Engine) -> BTreeMap<TileKey, Vec<u8>> {
    let mut copy = Engine::new(1, 1).unwrap();
    copy.load(&engine.save().unwrap()).unwrap();
    tiles(&copy.frame_with_background(true))
}

fn pixel(engine: &Engine, x: u32) -> [u8; 4] {
    canonical(engine)
        .get(&(x / TILE_SIZE, 0))
        .map_or([0; 4], |tile| {
            tile[(x % TILE_SIZE * 4) as usize..(x % TILE_SIZE * 4 + 4) as usize]
                .try_into()
                .unwrap()
        })
}

fn preview(engine: &Engine, settings: Value) -> Result<Vec<u8>, String> {
    let state = engine.state();
    let request: LayerActionRequest = serde_json::from_value(json!({"id":engine.document.active,"revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":false,"action":{"kind":"adjustment","settings":settings}})).unwrap();
    engine.preview_layer_action(request)
}

fn atomic(engine: &mut Engine, value: Value) {
    engine.frame_with_background(true);
    let bytes = engine.save().unwrap();
    let state = engine.state();
    assert!(command(engine, value).is_err());
    assert_eq!(engine.save().unwrap(), bytes);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

#[test]
fn tone_modifies_only_accumulated_backdrop_preserves_alpha_and_original_planes() {
    let mut engine = Engine::new(4, 1).unwrap();
    put(&mut engine.document.layers[0], 0, [20, 40, 60, 128]);
    put(&mut engine.document.layers[0], 1, [10, 30, 50, 255]);
    let original = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    engine.document.layers.push(node(2, tone(0.2)));
    let mut above = Layer::new(3, "Above".into());
    put(&mut above, 1, [3, 80, 9, 255]);
    engine.document.layers.push(above);
    reload(&mut engine);
    assert_eq!(pixel(&engine, 0), [46, 66, 86, 128]);
    assert_eq!(pixel(&engine, 1), [3, 80, 9, 255]);
    assert_eq!(pixel(&engine, 2), [0; 4]);
    let snapshot = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    command(
        &mut engine,
        json!({"type":"set_adjustment","id":2,"settings":tone(-0.1)}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 0), [7, 27, 47, 128]);
    assert!(Arc::ptr_eq(
        &snapshot,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
    assert_eq!(*snapshot, *original);
}

#[test]
fn curves_and_gradient_map_use_straight_color_and_preserve_semtransparent_alpha() {
    let mut engine = Engine::new(7, 1).unwrap();
    for (x, color) in [
        [255, 0, 0, 255],
        [0, 255, 0, 255],
        [0, 0, 255, 255],
        [255, 255, 255, 255],
        [0, 0, 0, 255],
        [64, 0, 0, 64],
    ]
    .into_iter()
    .enumerate()
    {
        put(&mut engine.document.layers[0], x as u32, color);
    }
    engine.document.layers.push(node(2, inverse()));
    engine.document.active = 2;
    reload(&mut engine);
    for (x, expected) in [
        [0, 255, 255, 255],
        [255, 0, 255, 255],
        [255, 255, 0, 255],
        [0, 0, 0, 255],
        [255, 255, 255, 255],
        [0, 64, 64, 64],
    ]
    .into_iter()
    .enumerate()
    {
        assert_eq!(pixel(&engine, x as u32), expected);
    }
    command(
        &mut engine,
        json!({"type":"set_adjustment","id":2,"settings":gradient()}),
    )
    .unwrap();
    for (x, expected) in [
        [201, 0, 54, 255],
        [73, 0, 182, 255],
        [237, 0, 18, 255],
        [0, 0, 255, 255],
        [255, 0, 0, 255],
        [50, 0, 14, 64],
    ]
    .into_iter()
    .enumerate()
    {
        assert_eq!(pixel(&engine, x as u32), expected);
    }
    assert_eq!(pixel(&engine, 6), [0; 4]);
}

#[test]
fn isolated_and_pass_through_groups_have_distinct_backdrop_scope() {
    for isolation in [GroupIsolation::Isolated, GroupIsolation::PassThrough] {
        let mut engine = Engine::new(4, 1).unwrap();
        put(&mut engine.document.layers[0], 0, [20, 40, 60, 255]);
        put(&mut engine.document.layers[0], 1, [20, 40, 60, 255]);
        let group = Layer::group(2, "Scope".into(), isolation);
        let mut paint = Layer::new(3, "Inside".into());
        paint.parent_id = Some(2);
        put(&mut paint, 0, [10, 30, 50, 255]);
        let mut adjustment = node(4, inverse());
        adjustment.parent_id = Some(2);
        engine.document.layers.extend([group, paint, adjustment]);
        reload(&mut engine);
        assert_eq!(pixel(&engine, 0), [245, 225, 205, 255]);
        assert_eq!(
            pixel(&engine, 1),
            if isolation == GroupIsolation::Isolated {
                [20, 40, 60, 255]
            } else {
                [235, 215, 195, 255]
            }
        );
    }
}

#[test]
fn clipped_adjustment_affects_base_chain_without_touching_external_backdrop_or_alpha_gate() {
    let mut engine = Engine::new(3, 1).unwrap();
    put(&mut engine.document.layers[0], 0, [20, 40, 60, 255]);
    put(&mut engine.document.layers[0], 1, [20, 40, 60, 255]);
    let mut base = Layer::new(2, "Base".into());
    base.opacity = 0.5;
    put(&mut base, 0, [128, 0, 0, 128]);
    let mut adjustment = node(3, inverse());
    adjustment.clipping = true;
    let mut upper = Layer::new(4, "Upper clip".into());
    upper.clipping = true;
    upper.opacity = 0.5;
    put(&mut upper, 0, [0, 0, 255, 255]);
    engine.document.layers.extend([base, adjustment, upper]);
    reload(&mut engine);
    assert_eq!(pixel(&engine, 0), [15, 62, 109, 255]);
    assert_eq!(pixel(&engine, 1), [20, 40, 60, 255]);
    assert_eq!(
        engine.document.layers[1].raster().unwrap().tiles()[&(0, 0)][3],
        128
    );
}

#[test]
fn own_gray_mask_and_opacity_blend_effect_once_while_group_mask_preserves_result_alpha() {
    let mut engine = Engine::new(3, 1).unwrap();
    let mut group = Layer::group(2, "Masked group".into(), GroupIsolation::Isolated);
    group.opacity = 0.5;
    group.set_first_mask(Some(LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 3,
            bottom: 1,
        },
        255,
    )));
    let mut paint = Layer::new(3, "Paint".into());
    paint.parent_id = Some(2);
    put(&mut paint, 0, [20, 40, 60, 128]);
    let mut adjustment = node(4, inverse());
    adjustment.parent_id = Some(2);
    adjustment.opacity = 0.5;
    adjustment.set_first_mask(Some(LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 3,
            bottom: 1,
        },
        255,
    )));
    group
        .first_mask_mut()
        .unwrap()
        .tiles
        .insert((0, 0), Arc::new(vec![128; MASK_TILE_BYTES]));
    adjustment
        .first_mask_mut()
        .unwrap()
        .tiles
        .insert((0, 0), Arc::new(vec![128; MASK_TILE_BYTES]));
    engine.document.layers.extend([group, paint, adjustment]);
    reload(&mut engine);
    assert_eq!(pixel(&engine, 0), [11, 13, 16, 32]);
    command(
        &mut engine,
        json!({"type":"set_mask","id":4,"enabled":false}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 0), [16, 16, 16, 32]);
    command(
        &mut engine,
        json!({"type":"set_layer","id":4,"name":"Adjustment","visible":false,"opacity":0.5}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 0), [5, 10, 15, 32]);
}

#[test]
fn canonical_preview_is_parameter_specific_atomic_cancelable_and_exactly_matches_commit() {
    let mut engine = Engine::new(256, 1).unwrap();
    put(&mut engine.document.layers[0], 0, [20, 40, 60, 128]);
    put(&mut engine.document.layers[0], 129, [10, 30, 50, 255]);
    engine.document.layers.push(node(2, inverse()));
    engine.document.active = 2;
    reload(&mut engine);
    let original = engine.save().unwrap();
    let state = engine.state();
    let zero = preview(&engine, inverse()).unwrap();
    assert_eq!(zero.len(), 16);
    let first = preview(&engine, tone(0.2)).unwrap();
    let second = preview(&engine, gradient()).unwrap();
    assert_ne!(first, second);
    assert_eq!(preview(&engine, inverse()).unwrap().len(), 16);
    assert_eq!(engine.save().unwrap(), original);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    command(
        &mut engine,
        json!({"type":"set_adjustment","id":2,"settings":gradient()}),
    )
    .unwrap();
    assert_eq!(engine.frame_with_background(true), second);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), original);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
    engine.frame_with_background(true);
    let state = engine.state();
    command(
        &mut engine,
        json!({"type":"set_adjustment","id":2,"settings":gradient()}),
    )
    .unwrap();
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

#[test]
fn hidden_adjustment_settings_still_commit_but_locked_or_stale_requests_are_atomic() {
    let mut engine = Engine::new(4, 1).unwrap();
    put(&mut engine.document.layers[0], 0, [20, 40, 60, 255]);
    let mut adjustment = node(2, inverse());
    adjustment.visible = false;
    engine.document.layers.push(adjustment);
    engine.document.active = 2;
    reload(&mut engine);
    assert_eq!(preview(&engine, gradient()).unwrap().len(), 16);
    let before = engine.save().unwrap();
    command(
        &mut engine,
        json!({"type":"set_adjustment","id":2,"settings":gradient()}),
    )
    .unwrap();
    assert_ne!(engine.save().unwrap(), before);
    assert_eq!(pixel(&engine, 0), [20, 40, 60, 255]);
    atomic(
        &mut engine,
        json!({"type":"set_adjustment","id":2,"revision":0,"settings":inverse()}),
    );
    atomic(
        &mut engine,
        json!({"type":"set_adjustment","id":2,"settings":tone(1.1)}),
    );
    command(
        &mut engine,
        json!({"type":"set_protection","id":2,"locked":true}),
    )
    .unwrap();
    atomic(
        &mut engine,
        json!({"type":"set_adjustment","id":2,"settings":inverse()}),
    );
    assert!(preview(&engine, inverse()).is_err());
}

#[test]
fn create_snapshots_selection_into_its_own_mask_and_uses_one_undo_for_closed_ancestor_reveal() {
    let mut engine = Engine::new(8, 4).unwrap();
    let mut group = Layer::group(2, "Group".into(), GroupIsolation::Isolated);
    if let LayerContent::Group { closed, .. } = &mut group.content {
        *closed = true;
    }
    let mut paint = Layer::new(3, "Paint".into());
    paint.parent_id = Some(2);
    put(&mut paint, 0, [20, 40, 60, 255]);
    put(&mut paint, 4, [20, 40, 60, 255]);
    engine.document.layers.extend([group, paint]);
    engine.document.active = 2;
    reload(&mut engine);
    command(
        &mut engine,
        json!({"type":"select","rect":{"left":0,"top":0,"right":2,"bottom":2}}),
    )
    .unwrap();
    let before = engine.save().unwrap();
    command(&mut engine,json!({"type":"create_adjustment","name":"Invert","parent_id":2,"index":1,"settings":inverse()})).unwrap();
    assert_eq!(engine.document.active, 4);
    assert_eq!(engine.state()["layers"][3]["kind"], "adjustment");
    assert_eq!(engine.state()["layers"][3]["adjustment"]["kind"], "curves");
    assert_eq!(engine.state()["layers"][1]["closed"], false);
    assert_eq!(pixel(&engine, 0), [235, 215, 195, 255]);
    assert_eq!(pixel(&engine, 4), [20, 40, 60, 255]);
    let mask = engine.document.layers[3].first_mask().cloned();
    command(&mut engine, json!({"type":"select","rect":null})).unwrap();
    assert_eq!(engine.document.layers[3].first_mask().cloned(), mask);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn indexed_source_indices_are_immutable_and_adjustment_output_requires_explicit_png_quantization() {
    let mut engine = Engine::new(3, 1).unwrap();
    command(&mut engine,json!({"type":"new_indexed","width":3,"height":1,"palette":{"colors":[[0,0,0,0],[20,40,60,255],[80,100,120,128]],"transparent":0,"order":[0,1,2]}})).unwrap();
    let mut indices = vec![0; MASK_TILE_BYTES];
    indices[..3].copy_from_slice(&[1, 2, 0]);
    engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new(indices));
    engine.document.layers.push(node(2, inverse()));
    engine.document.active = 2;
    reload(&mut engine);
    let original = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    assert_eq!(pixel(&engine, 0), [235, 215, 195, 255]);
    assert_eq!(pixel(&engine, 1), [88, 78, 68, 128]);
    assert!(engine
        .export_image(ExportOptions {
            format: ExportFormat::IndexedPng,
            transparent: true,
            ..Default::default()
        })
        .unwrap_err()
        .contains("量化"));
    let bytes = engine
        .export_image(ExportOptions {
            format: ExportFormat::IndexedPng,
            transparent: true,
            indexed_policy: IndexedExportPolicy::Quantize,
            ..Default::default()
        })
        .unwrap();
    assert_eq!(bytes[25], 3);
    command(
        &mut engine,
        json!({"type":"set_adjustment","id":2,"settings":tone(0.0)}),
    )
    .unwrap();
    assert!(Arc::ptr_eq(
        &original,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
    command(
        &mut engine,
        json!({"type":"set_palette_color","index":1,"color":[10,30,50,255]}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 0), [10, 30, 50, 255]);
    assert_eq!(
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)][..3],
        &[1, 2, 0]
    );
}

#[test]
fn adjustment_nodes_reject_pixel_tools_and_unsupported_metadata_without_mutating_the_document() {
    let mut engine = Engine::new(4, 1).unwrap();
    engine.document.layers.push(node(2, inverse()));
    engine.document.active = 2;
    reload(&mut engine);
    for value in [
        json!({"type":"begin","brush":{"size":1,"opacity":1.0,"hardness":1.0,"color":[0,0,0],"eraser":false}}),
        json!({"type":"fill","x":0,"y":0,"color":[10,20,30,255],"tolerance":0}),
        json!({"type":"translate_layer","id":2,"dx":1,"dy":0}),
        json!({"type":"set_blend","id":2,"mode":"multiply"}),
        json!({"type":"set_protection","id":2,"alpha_locked":true}),
        json!({"type":"set_clipping","id":1,"clipping":true}),
    ] {
        atomic(&mut engine, value);
    }
    assert!(engine.move_layer_frame(false).is_err());
    assert!(engine.layer_frame().is_err());
}

#[test]
fn active_stroke_under_adjustments_renders_actual_effect_and_cancel_undo_restore_raw_paint() {
    let mut engine = Engine::new(8, 4).unwrap();
    put(&mut engine.document.layers[0], 0, [10, 30, 50, 255]);
    engine.document.layers.push(node(2, inverse()));
    reload(&mut engine);
    let before = engine.save().unwrap();
    let original = canonical(&engine);
    let brush = Brush {
        size: 1.0,
        size_pressure: 0.0,
        raster: BrushRaster::Pixel,
        color: [20, 40, 60],
        ..Brush::default()
    };
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[
            Sample {
                x: 0.5,
                y: 0.5,
                pressure: 1.0,
            },
            Sample {
                x: 2.5,
                y: 0.5,
                pressure: 1.0,
            },
        ])
        .unwrap();
    assert_eq!(
        tiles(&engine.frame_with_background(true)),
        canonical(&engine)
    );
    assert_eq!(pixel(&engine, 1), [235, 215, 195, 255]);
    engine.command(Command::Cancel).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(canonical(&engine), original);
    engine.frame_with_background(true);
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x: 0.5,
            y: 0.5,
            pressure: 1.0,
        }])
        .unwrap();
    engine.command(Command::End).unwrap();
    let painted = engine.save().unwrap();
    assert_eq!(pixel(&engine, 0), [235, 215, 195, 255]);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), painted);
}

#[test]
fn gray_adjustment_mask_brush_is_independent_of_index_palette_and_updates_only_its_effect() {
    for indexed in [false, true] {
        let mut engine = Engine::new(4, 2).unwrap();
        if indexed {
            command(&mut engine,json!({"type":"new_indexed","width":4,"height":2,"palette":{"colors":[[0,0,0,0],[20,40,60,255]],"transparent":0,"order":[0,1]}})).unwrap();
            engine.document.layers[0]
                .raster_mut()
                .unwrap()
                .tiles_mut()
                .insert((0, 0), Arc::new(vec![1; INDEX_TILE_BYTES]));
        } else {
            put(&mut engine.document.layers[0], 0, [20, 40, 60, 255]);
        }
        let mut adjustment = node(2, inverse());
        adjustment.set_first_mask(Some(LayerMask::new(
            MaskBounds {
                left: 0,
                top: 0,
                right: 4,
                bottom: 2,
            },
            255,
        )));
        engine.document.layers.push(adjustment);
        engine.document.active = 2;
        reload(&mut engine);
        let original = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
        engine
            .command(Command::SetMaskEditing {
                mask_id: None,
                id: Some(2),
                enabled: true,
            })
            .unwrap();
        let before = engine.save().unwrap();
        engine
            .command(Command::Begin {
                brush: Brush {
                    size: 1.0,
                    size_pressure: 0.0,
                    raster: BrushRaster::Pixel,
                    color: [0, 0, 0],
                    ..Brush::default()
                },
                assistant: None,
            })
            .unwrap();
        engine
            .samples(&[Sample {
                x: 0.5,
                y: 0.5,
                pressure: 1.0,
            }])
            .unwrap();
        assert_eq!(
            tiles(&engine.frame_with_background(true)),
            canonical(&engine)
        );
        assert_eq!(pixel(&engine, 0), [20, 40, 60, 255]);
        engine.command(Command::End).unwrap();
        assert!(Arc::ptr_eq(
            &original,
            &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
        ));
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
    }
}

#[test]
fn global_resize_and_color_conversion_keep_live_effects_and_independent_linked_masks() {
    let mut engine = Engine::new(4, 2).unwrap();
    put(&mut engine.document.layers[0], 0, [20, 40, 60, 255]);
    let mut adjustment = node(2, inverse());
    adjustment.set_first_mask(Some(LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 4,
            bottom: 2,
        },
        255,
    )));
    engine.document.layers.push(adjustment);
    reload(&mut engine);
    let before = engine.save().unwrap();
    command(
        &mut engine,
        json!({"type":"resize_canvas","width":6,"height":2,"anchor":4}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 1), [235, 215, 195, 255]);
    assert_eq!(
        engine.document.layers[1].first_mask().unwrap().bounds.left,
        1
    );
    command(
        &mut engine,
        json!({"type":"resize_image","width":12,"height":4,"filter":"nearest"}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 2), [235, 215, 195, 255]);
    assert!(engine.document.layers[1].is_adjustment());
    command(&mut engine,json!({"type":"convert_color_mode","mode":"indexed","palette":{"colors":[[0,0,0,0],[20,40,60,255],[235,215,195,255]],"transparent":0,"order":[0,1,2]}})).unwrap();
    assert_eq!(pixel(&engine, 2), [235, 215, 195, 255]);
    command(
        &mut engine,
        json!({"type":"remove_palette_color","index":2,"replacement":1}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 2), [235, 215, 195, 255]);
    command(
        &mut engine,
        json!({"type":"resize_image","width":6,"height":2,"filter":"nearest"}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 1), [235, 215, 195, 255]);
    command(
        &mut engine,
        json!({"type":"convert_color_mode","mode":"rgba"}),
    )
    .unwrap();
    assert_eq!(pixel(&engine, 1), [235, 215, 195, 255]);
    for _ in 0..6 {
        engine.command(Command::Undo).unwrap();
    }
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn pass_through_effect_preview_reaches_external_tiles_and_stale_selection_or_mask_target_rejects() {
    let mut engine = Engine::new(256, 2).unwrap();
    put(&mut engine.document.layers[0], 129, [20, 40, 60, 255]);
    let group = Layer::group(2, "Pass".into(), GroupIsolation::PassThrough);
    let mut adjustment = node(3, tone(0.0));
    adjustment.parent_id = Some(2);
    adjustment.set_first_mask(Some(LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 256,
            bottom: 2,
        },
        255,
    )));
    engine.document.layers.extend([group, adjustment]);
    engine.document.active = 3;
    reload(&mut engine);
    let output = preview(&engine, inverse()).unwrap();
    assert_eq!(
        tiles(&output).keys().copied().collect::<Vec<_>>(),
        vec![(1, 0)]
    );
    let state = engine.state();
    command(
        &mut engine,
        json!({"type":"select","rect":{"left":128,"top":0,"right":256,"bottom":2}}),
    )
    .unwrap();
    let stale:LayerActionRequest=serde_json::from_value(json!({"id":3,"revision":engine.state()["revision"],"selection_id":state["selectionId"],"mask_editing":false,"action":{"kind":"adjustment","settings":inverse()}})).unwrap();
    assert!(engine.preview_layer_action(stale).is_err());
    command(&mut engine, json!({"type":"select","rect":null})).unwrap();
    engine
        .command(Command::SetMaskEditing {
            mask_id: None,
            id: Some(3),
            enabled: true,
        })
        .unwrap();
    let masked:LayerActionRequest=serde_json::from_value(json!({"id":3,"revision":engine.state()["revision"],"selection_id":engine.state()["selectionId"],"mask_editing":true,"action":{"kind":"adjustment","settings":inverse()}})).unwrap();
    assert!(engine.preview_layer_action(masked).is_err());
    engine
        .command(Command::SetMaskEditing {
            mask_id: None,
            id: Some(3),
            enabled: false,
        })
        .unwrap();
    command(
        &mut engine,
        json!({"type":"set_adjustment","id":3,"settings":inverse()}),
    )
    .unwrap();
    assert_eq!(engine.frame_with_background(true), output);
}

#[test]
fn merge_visible_explicitly_bakes_effects_once_and_thumbnails_show_the_processed_backdrop() {
    let mut engine = Engine::new(2, 1).unwrap();
    put(&mut engine.document.layers[0], 0, [20, 40, 60, 255]);
    put(&mut engine.document.layers[0], 1, [30, 50, 70, 255]);
    engine.document.layers.push(node(2, inverse()));
    reload(&mut engine);
    let before = engine.save().unwrap();
    let original = canonical(&engine);
    let thumbnails = loop {
        let bytes = engine.previews().unwrap();
        if !bytes.is_empty() {
            break bytes;
        }
        std::thread::sleep(std::time::Duration::from_millis(1));
    };
    let stride = 4 + (PREVIEW_EDGE * PREVIEW_EDGE * 4) as usize;
    let adjustment_pixels = &thumbnails[16 + 2 * stride + 4..16 + 3 * stride];
    assert!(adjustment_pixels
        .as_chunks::<4>()
        .0
        .contains(&[235, 215, 195, 255]));
    engine.command(Command::MergeVisible).unwrap();
    assert_eq!(engine.document.layers.len(), 1);
    assert!(engine.document.layers[0].raster_opt().is_some());
    assert_eq!(canonical(&engine), original);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn invalid_adjustment_clip_bases_and_reorders_reject_atomically_and_valid_reorder_dirties_backdrop()
{
    let mut engine = Engine::new(256, 1).unwrap();
    put(&mut engine.document.layers[0], 0, [20, 40, 60, 255]);
    let mut paint = Layer::new(2, "Second".into());
    put(&mut paint, 129, [10, 30, 50, 255]);
    engine.document.layers.extend([paint, node(3, inverse())]);
    reload(&mut engine);
    command(
        &mut engine,
        json!({"type":"reorder_layer","id":3,"index":1}),
    )
    .unwrap();
    assert_eq!(
        tiles(&engine.frame_with_background(true)),
        canonical(&engine)
    );
    assert_eq!(pixel(&engine, 0), [235, 215, 195, 255]);
    assert_eq!(pixel(&engine, 129), [10, 30, 50, 255]);
    atomic(
        &mut engine,
        json!({"type":"set_clipping","id":2,"clipping":true}),
    );
    command(
        &mut engine,
        json!({"type":"reorder_layer","id":3,"index":2}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"set_clipping","id":2,"clipping":true}),
    )
    .unwrap();
    atomic(
        &mut engine,
        json!({"type":"reorder_layer","id":3,"index":1}),
    );
}

#[test]
fn adjustment_mask_translation_and_transform_commit_dirty_tiles_equal_canonical_preview() {
    let mut engine = Engine::new(256, 2).unwrap();
    put(&mut engine.document.layers[0], 0, [20, 40, 60, 255]);
    put(&mut engine.document.layers[0], 129, [10, 30, 50, 255]);
    let mut adjustment = node(2, inverse());
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 256,
            bottom: 2,
        },
        0,
    );
    let mut coverage = vec![0; MASK_TILE_BYTES];
    coverage[0] = 255;
    mask.tiles.insert((0, 0), Arc::new(coverage));
    adjustment.set_first_mask(Some(mask));
    engine.document.layers.push(adjustment);
    engine.document.active = 2;
    reload(&mut engine);
    engine
        .command(Command::SetMaskEditing {
            mask_id: None,
            id: Some(2),
            enabled: true,
        })
        .unwrap();
    let state = engine.state();
    let request:LayerActionRequest=serde_json::from_value(json!({"id":2,"revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":true,"action":{"kind":"translate","dx":129,"dy":0}})).unwrap();
    let output = engine.preview_layer_action(request).unwrap();
    command(
        &mut engine,
        json!({"type":"translate_layer","id":2,"dx":129,"dy":0}),
    )
    .unwrap();
    let dirty = tiles(&engine.frame_with_background(true));
    for (key, pixels) in tiles(&output) {
        assert_eq!(dirty[&key], pixels);
    }
    assert_eq!(dirty, canonical(&engine));
    assert_eq!(pixel(&engine, 0), [20, 40, 60, 255]);
    assert_eq!(pixel(&engine, 129), [245, 225, 205, 255]);
    let state = engine.state();
    let transform = json!({"width":256,"height":2,"dx":-129.0,"dy":0.0,"angle":0.0,"flip_x":false,"flip_y":false,"filter":"nearest"});
    let request:LayerActionRequest=serde_json::from_value(json!({"id":2,"revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":true,"action":{"kind":"transform","transform":transform}})).unwrap();
    let output = engine.preview_layer_action(request).unwrap();
    command(
        &mut engine,
        json!({"type":"transform_layer","id":2,"transform":transform}),
    )
    .unwrap();
    let dirty = tiles(&engine.frame_with_background(true));
    for (key, pixels) in tiles(&output) {
        assert_eq!(dirty[&key], pixels);
    }
    assert_eq!(dirty, canonical(&engine));
}

#[test]
fn whole_group_geometry_includes_pass_through_adjustment_external_backdrop_dependencies() {
    for linked in [false, true] {
        for action in [
            json!({"kind":"translate","dx":8,"dy":0}),
            json!({"kind":"transform","transform":{"width":1,"height":1,"dx":8.0,"dy":0.0,"angle":0.0,"flip_x":false,"flip_y":false,"filter":"nearest"}}),
        ] {
            let mut engine = Engine::new(256, 2).unwrap();
            put(&mut engine.document.layers[0], 129, [20, 40, 60, 255]);
            let group = Layer::group(2, "Pass".into(), GroupIsolation::PassThrough);
            let mut paint = Layer::new(3, "Paint".into());
            paint.parent_id = Some(2);
            put(&mut paint, 1, [10, 30, 50, 255]);
            let mut adjustment = node(4, inverse());
            adjustment.parent_id = Some(2);
            let mut mask = LayerMask::new(
                MaskBounds {
                    left: 0,
                    top: 0,
                    right: 256,
                    bottom: 2,
                },
                0,
            );
            mask.linked = linked;
            let mut coverage = vec![0; MASK_TILE_BYTES];
            coverage[1] = 255;
            mask.tiles.insert((1, 0), Arc::new(coverage));
            adjustment.set_first_mask(Some(mask.clone()));
            engine.document.layers.extend([group, paint, adjustment]);
            engine.document.active = 2;
            reload(&mut engine);
            assert_eq!(pixel(&engine, 129), [235, 215, 195, 255]);
            let before = engine.save().unwrap();
            let state = engine.state();
            let request:LayerActionRequest=serde_json::from_value(json!({"id":2,"revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":false,"action":action})).unwrap();
            let output = engine.preview_layer_action(request).unwrap();
            let value = if action["kind"] == "translate" {
                json!({"type":"translate_layer","id":2,"dx":8,"dy":0})
            } else {
                json!({"type":"transform_layer","id":2,"transform":action["transform"]})
            };
            command(&mut engine, value).unwrap();
            let dirty = tiles(&engine.frame_with_background(true));
            for (key, pixels) in tiles(&output) {
                assert_eq!(dirty[&key], pixels);
            }
            assert_eq!(dirty, canonical(&engine));
            assert_eq!(
                pixel(&engine, 129),
                if linked {
                    [20, 40, 60, 255]
                } else {
                    [235, 215, 195, 255]
                }
            );
            assert_eq!(pixel(&engine, 9), [10, 30, 50, 255]);
            if !linked {
                assert_eq!(engine.document.layers[3].first_mask().unwrap(), &mask);
            }
            engine.command(Command::Undo).unwrap();
            assert_eq!(engine.save().unwrap(), before);
            command(
                &mut engine,
                json!({"type":"set_protection","id":4,"locked":true}),
            )
            .unwrap();
            atomic(
                &mut engine,
                json!({"type":"translate_layer","id":2,"dx":8,"dy":0}),
            );
        }
    }
}

#[test]
fn legacy_layer_blend_preview_changes_adjustment_opacity_without_baking_parameters() {
    use podor_engine::AdjustmentRequest;
    let mut engine = Engine::new(4, 1).unwrap();
    put(&mut engine.document.layers[0], 0, [20, 40, 60, 128]);
    engine.document.layers.push(node(2, inverse()));
    engine.document.active = 2;
    reload(&mut engine);
    let before = engine.save().unwrap();
    let state = engine.state();
    let request = json!({"id":2,"revision":state["revision"],"selection_id":state["selectionId"],"settings":{"kind":"layer_blend","opacity":0.5,"blend":"normal","brightness":0.0,"contrast":0.0,"saturation":0.0,"sigma":0.0}});
    let parsed: AdjustmentRequest = serde_json::from_value(request.clone()).unwrap();
    let output = engine.preview_adjustment(parsed).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    command(
        &mut engine,
        json!({"type":"apply_adjustment","request":request}),
    )
    .unwrap();
    assert_eq!(engine.frame_with_background(true), output);
    assert_eq!(pixel(&engine, 0), [64, 64, 64, 128]);
    assert_eq!(engine.state()["layers"][1]["adjustment"]["kind"], "curves");
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
}
