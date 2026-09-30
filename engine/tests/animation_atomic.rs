use podor_engine::{
    animation::{AnimationSet, Cel, CelKind, CelSource, Frame},
    model::*,
    vector::{FillRule, Geometry, Style, VectorLayer, VectorObject},
    Command, Engine, FrameRenderRequest,
};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

fn send(engine: &mut Engine, mut value: Value) -> Result<Value, String> {
    let state = engine.state();
    value["revision"] = state["revision"].clone();
    if !state["animation"].is_null()
        && !matches!(
            value["type"].as_str().unwrap(),
            "enable_animation"
                | "add_frame"
                | "duplicate_frame"
                | "new_cel"
                | "clear_cel"
                | "link_cel"
                | "unlink_cel"
        )
    {
        value["frame_id"] = state["animation"]["activeFrameId"].clone();
        value["cel_id"] = state["animation"]["activeCelId"].clone();
        value["target_layer_id"] = state["active"].clone();
    }
    engine.command_request(serde_json::from_value(value).unwrap())
}

fn palette() -> IndexedPalette {
    IndexedPalette {
        colors: vec![[0, 0, 0, 255], [40, 80, 120, 255], [0; 4]],
        transparent: 2,
        order: vec![2, 1, 0],
    }
}

fn still(indexed: bool, grouped: bool) -> Engine {
    let mut engine = Engine::new(16, 12).unwrap();
    let mut pixel = vec![0; TILE_BYTES];
    let offset = (3 * TILE_SIZE + 2) as usize * 4;
    pixel[offset..offset + 4].copy_from_slice(&[40, 80, 120, 255]);
    let mut leaf = Layer::new(if grouped { 3 } else { 1 }, "Paint".into());
    leaf.content = if indexed {
        let mut indices = vec![2; INDEX_TILE_BYTES];
        indices[offset / 4] = 1;
        engine.document.palette = Some(palette());
        LayerContent::Raster(RasterPlane::Indexed(BTreeMap::from([(
            (0, 0),
            Arc::new(indices),
        )])))
    } else {
        LayerContent::Raster(RasterPlane::Rgba(BTreeMap::from([(
            (0, 0),
            Arc::new(pixel),
        )])))
    };
    if grouped {
        leaf.parent_id = Some(2);
        engine.document.layers = vec![
            Layer::group(2, "Group".into(), GroupIsolation::Isolated),
            leaf,
        ];
        engine.document.active = 3;
        engine.document.next_id = 4;
    } else {
        engine.document.layers = vec![leaf];
    }
    engine.load(&engine.save().unwrap()).unwrap();
    engine
}

fn animated(indexed: bool, grouped: bool) -> Engine {
    let mut engine = still(indexed, grouped);
    send(
        &mut engine,
        json!({"type":"enable_animation","duration_ms":100}),
    )
    .unwrap();
    engine
}

fn assert_unchanged(engine: &mut Engine, value: Value, rejected: bool) {
    engine.frame_with_background(true);
    let saved = engine.save().unwrap();
    let state = engine.state();
    let selection = engine.selection_frame();
    let result = send(engine, value);
    assert_eq!(result.is_err(), rejected);
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.state(), state);
    assert!(engine.selection_frame() == selection);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

fn select_pixels(engine: &mut Engine) {
    send(
        engine,
        json!({"type":"select","rect":{"left":1,"top":1,"right":6,"bottom":7}}),
    )
    .unwrap();
    let selection = engine.state()["selection"].clone();
    assert!(!selection.is_null());
    for (key, expected) in [("left", 1), ("top", 1), ("right", 6), ("bottom", 7)] {
        assert_eq!(selection[key], expected);
    }
}

#[test]
fn same_geometry_and_color_mode_preserve_selection_dirty_history_and_ids() {
    let mut engine = animated(false, false);
    select_pixels(&mut engine);
    for request in [
        json!({"type":"resize_canvas","width":16,"height":12,"anchor":4}),
        json!({"type":"resize_image","width":16,"height":12,"filter":"nearest"}),
        json!({"type":"convert_color_mode","mode":"rgba"}),
    ] {
        assert_unchanged(&mut engine, request, false);
    }
    assert_unchanged(
        &mut engine,
        json!({"type":"resize_canvas","width":16,"height":12,"anchor":9}),
        true,
    );
    engine.command(Command::Undo).unwrap();
    assert!(engine.document.animation.is_none());
}

#[test]
fn indexed_same_mode_uses_existing_palette_and_does_not_clear_selection() {
    let mut engine = animated(true, false);
    select_pixels(&mut engine);
    assert_unchanged(
        &mut engine,
        json!({"type":"convert_color_mode","mode":"indexed"}),
        false,
    );
    assert_eq!(engine.document.palette.as_ref().unwrap().transparent, 2);
    engine.command(Command::Undo).unwrap();
    assert!(engine.document.animation.is_none());
}

#[test]
fn rgba_mode_rejects_palette_in_both_still_and_animated_requests() {
    for animation in [false, true] {
        let mut engine = if animation {
            animated(false, false)
        } else {
            still(false, false)
        };
        assert_unchanged(
            &mut engine,
            json!({"type":"convert_color_mode","mode":"rgba","palette":palette()}),
            true,
        );
    }
}

#[test]
fn whole_group_geometry_rejects_stale_mask_targets_instead_of_moving_every_cel() {
    let mut engine = animated(false, true);
    send(&mut engine, json!({"type":"select_layer","id":2})).unwrap();
    for request in [
        json!({"type":"translate_layer","id":2,"dx":1,"dy":0,"mask_id":999}),
        json!({"type":"transform_layer","id":2,"mask_id":999,"transform":{"width":1,"height":1,"dx":1,"dy":0,"filter":"nearest"}}),
    ] {
        assert_unchanged(&mut engine, request, true);
    }
}

#[test]
fn group_transform_requires_its_group_to_be_the_actual_active_layer() {
    let mut engine = animated(false, true);
    assert_eq!(engine.document.active, 3);
    assert_unchanged(
        &mut engine,
        json!({"type":"transform_layer","id":2,"transform":{"width":1,"height":1,"dx":1,"dy":0,"filter":"nearest"}}),
        true,
    );
}

fn indexed_inactive_sources(keys: u32) -> Engine {
    let mut engine = Engine::new(8192, 2048).unwrap();
    engine.document.palette = Some(palette());
    engine.document.layers[0].content = LayerContent::CelTrack {
        kind: CelKind::Raster,
    };
    let tile = Arc::new(vec![1; INDEX_TILE_BYTES]);
    let mut cels = BTreeMap::new();
    let mut frames = vec![Frame {
        id: 1,
        duration_ms: 100,
        exposures: BTreeMap::new(),
    }];
    for id in 1..=3 {
        let source = RasterPlane::Indexed(
            (0..keys)
                .map(|key| ((key % 64, key / 64), tile.clone()))
                .collect(),
        );
        cels.insert(
            id,
            Arc::new(Cel {
                id,
                layer_id: 1,
                source: CelSource::Raster(Arc::new(source)),
                masks: vec![],
            }),
        );
        frames.push(Frame {
            id: id + 1,
            duration_ms: 100,
            exposures: BTreeMap::from([(1, id)]),
        });
    }
    engine.document.animation = Some(Arc::new(AnimationSet {
        frames,
        cels,
        active_frame: 1,
        next_frame_id: 5,
        next_cel_id: 4,
        next_tag_id: 1,
        tags: vec![],
    }));
    assert!(engine.document.pixel_bytes() < 1024 * 1024);
    engine.document.validate().unwrap();
    engine.load(&engine.save().unwrap()).unwrap();
    engine
}

#[test]
fn inactive_indexed_conversion_reserves_all_target_sources_and_preserves_redo_on_rejection() {
    let mut small = indexed_inactive_sources(1);
    send(
        &mut small,
        json!({"type":"convert_color_mode","mode":"rgba"}),
    )
    .unwrap();
    assert!(small.document.palette.is_none());
    assert!(small
        .document
        .animation
        .as_ref()
        .unwrap()
        .cels
        .values()
        .all(|cel| matches!(&cel.source,CelSource::Raster(source) if !source.is_indexed())));

    let mut engine = indexed_inactive_sources(1024);
    let initial = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"set_layer","id":1,"name":"Checkpoint","opacity":1,"visible":true}),
    )
    .unwrap();
    let checkpoint = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"set_layer","id":1,"name":"Future","opacity":1,"visible":true}),
    )
    .unwrap();
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    select_pixels(&mut engine);
    let converted_bytes: usize = engine
        .document
        .animation
        .as_ref()
        .unwrap()
        .cels
        .values()
        .map(|cel| match &cel.source {
            CelSource::Raster(source) => source.tiles().len() * TILE_BYTES,
            CelSource::Vector(_) => 0,
        })
        .sum();
    assert!(converted_bytes > MAX_DOCUMENT_BYTES);
    assert_unchanged(
        &mut engine,
        json!({"type":"convert_color_mode","mode":"rgba"}),
        true,
    );
    assert!(engine.save().unwrap() == checkpoint);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == future);
    engine.command(Command::Undo).unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == initial);
}

fn stroke(engine: &mut Engine) {
    send(engine, json!({"type":"begin","brush":{"size":1,"opacity":1,"hardness":1,"color":[0,0,255],"eraser":false,"raster":"pixel","size_pressure":0,"opacity_pressure":0,"stabilization":0}})).unwrap();
    engine
        .samples(&[Sample {
            x: 5.5,
            y: 6.5,
            pressure: 1.0,
        }])
        .unwrap();
}

fn packet_pixel(packet: &[u8], x: u32, y: u32) -> [u8; 4] {
    let key = (x / TILE_SIZE, y / TILE_SIZE);
    for tile in packet[16..].as_chunks::<{ 8 + TILE_BYTES }>().0 {
        if (
            u32::from_le_bytes(tile[..4].try_into().unwrap()),
            u32::from_le_bytes(tile[4..8].try_into().unwrap()),
        ) == key
        {
            let offset = 8 + ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            return tile[offset..offset + 4].try_into().unwrap();
        }
    }
    [0; 4]
}

#[test]
fn readonly_live_stroke_snapshots_on_blank_cels_do_not_allocate_canonical_ids_or_consume_dirty() {
    let mut engine = animated(false, false);
    send(
        &mut engine,
        json!({"type":"add_frame","index":1,"duration_ms":100,"select":true}),
    )
    .unwrap();
    let before = engine.save().unwrap();
    let original_state = engine.state();
    let original_document = engine.document.clone();
    let mut control = Engine::new(1, 1).unwrap();
    control.load(&before).unwrap();
    let frame = original_state["animation"]["activeFrameId"]
        .as_u64()
        .unwrap() as u32;
    engine.frame_with_background(true);
    control.frame_with_background(true);
    stroke(&mut engine);
    stroke(&mut control);
    let state = engine.state();
    let live_save = engine.save().unwrap();
    let pending = control.frame_with_background(true);
    for _ in 0..3 {
        let packet = engine
            .render_animation_frame(FrameRenderRequest {
                revision: state["revision"].as_u64().unwrap(),
                frame_id: frame,
                transparent: true,
                region: None,
            })
            .unwrap();
        assert_eq!(packet_pixel(&packet, 5, 6), [0, 0, 255, 255]);
        assert_eq!(engine.state(), state);
        assert!(engine.save().unwrap() == live_save);
        assert!(engine.document == original_document);
    }
    assert!(engine.frame_with_background(true) == pending);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    assert_eq!(packet_pixel(&pending, 5, 6), [0, 0, 255, 255]);
    send(&mut engine, json!({"type":"cancel"})).unwrap();
    assert!(engine.save().unwrap() == before);
    assert_eq!(engine.state(), original_state);
}

#[test]
fn locked_ancestors_guard_timeline_cel_operations_in_inactive_frames() {
    let mut engine = animated(false, true);
    send(
        &mut engine,
        json!({"type":"duplicate_frame","frame_id":1,"index":1,"linked":true,"select":false}),
    )
    .unwrap();
    send(
        &mut engine,
        json!({"type":"add_frame","index":2,"duration_ms":100,"select":false}),
    )
    .unwrap();
    send(
        &mut engine,
        json!({"type":"set_protection","id":2,"locked":true}),
    )
    .unwrap();
    let cel = engine.document.animation.as_ref().unwrap().frames[0].exposures[&3];
    for request in [
        json!({"type":"new_cel","frame_id":3,"layer_id":3}),
        json!({"type":"clear_cel","frame_id":2,"layer_id":3,"cel_id":cel}),
        json!({"type":"link_cel","frame_id":3,"layer_id":3,"source_frame_id":1}),
        json!({"type":"unlink_cel","frame_id":2,"layer_id":3,"cel_id":cel}),
    ] {
        assert_unchanged(&mut engine, request, true);
    }
}

fn near_metadata_limit() -> Engine {
    let mut engine = Engine::new(8192, 2048).unwrap();
    engine.document.layers[0].content = LayerContent::CelTrack {
        kind: CelKind::Raster,
    };
    let tile = Arc::new([1, 0, 0, 1].repeat(TILE_BYTES / 4));
    let source = |count| {
        CelSource::Raster(Arc::new(RasterPlane::Rgba(
            (0..count)
                .map(|key| ((key % 64, key / 64), tile.clone()))
                .collect(),
        )))
    };
    let mut cels = BTreeMap::new();
    let mut frames = Vec::new();
    for id in 1..=33 {
        cels.insert(
            id,
            Arc::new(Cel {
                id,
                layer_id: 1,
                source: source(if id == 1 || id == 33 { 1 } else { 1024 }),
                masks: vec![],
            }),
        );
        frames.push(Frame {
            id,
            duration_ms: 100,
            exposures: BTreeMap::from([(1, id)]),
        });
    }
    let mut animation = AnimationSet {
        frames,
        cels,
        active_frame: 1,
        next_frame_id: 34,
        next_cel_id: 34,
        next_tag_id: 1,
        tags: vec![],
    };
    let mut low = 0u32;
    let mut high = 1024u32;
    while low < high {
        let mid = (low + high).div_ceil(2);
        Arc::make_mut(animation.cels.get_mut(&33).unwrap()).source = source(mid);
        if animation.metadata_bytes() <= MAX_ANIMATION_METADATA_BYTES - MAX_LAYER_NAME_BYTES / 2 {
            low = mid;
        } else {
            high = mid - 1;
        }
    }
    Arc::make_mut(animation.cels.get_mut(&33).unwrap()).source = source(low);
    assert!(animation.metadata_bytes() <= MAX_ANIMATION_METADATA_BYTES);
    assert!(animation.metadata_bytes() + MAX_LAYER_NAME_BYTES > MAX_ANIMATION_METADATA_BYTES);
    engine.document.animation = Some(Arc::new(animation));
    engine.document.validate().unwrap();
    engine.load(&engine.save().unwrap()).unwrap();
    engine
}

#[test]
fn late_inactive_metadata_rejection_restores_the_original_view_without_new_dirty_tiles() {
    let mut engine = near_metadata_limit();
    select_pixels(&mut engine);
    let mut candidate = engine.document.clone();
    let next_mask_id = candidate.next_mask_id;
    candidate.next_mask_id += 1;
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: 8192,
        bottom: 2048,
    };
    Arc::make_mut(
        Arc::make_mut(candidate.animation.as_mut().unwrap())
            .cels
            .get_mut(&1)
            .unwrap(),
    )
    .masks
    .push(MaskEntry {
        id: next_mask_id,
        name: "x".repeat(MAX_LAYER_NAME_BYTES),
        plane: LayerMask::new(bounds, 255),
    });
    assert!(candidate.animation.as_ref().unwrap().metadata_bytes() > MAX_ANIMATION_METADATA_BYTES);
    assert!(candidate.validate().is_err());
    assert_unchanged(
        &mut engine,
        json!({"type":"add_mask","mode":"reveal","name":"x".repeat(MAX_LAYER_NAME_BYTES)}),
        true,
    );
    assert_eq!(engine.document.next_mask_id, next_mask_id);
}

#[test]
fn rasterize_vector_rejects_selection_and_mask_editing_in_still_and_animation() {
    for animation in [false, true] {
        for mask_editing in [false, true] {
            let mut engine = still(false, false);
            engine.document.layers[0].content = LayerContent::Vector(Arc::new(VectorLayer {
                next_object_id: 2,
                objects: vec![Arc::new(VectorObject {
                    id: 1,
                    name: "Rectangle".into(),
                    visible: true,
                    geometry: Geometry::Rect {
                        x: 2.0,
                        y: 3.0,
                        width: 8.0,
                        height: 6.0,
                    },
                    transform: [1.0, 0.0, 0.0, 1.0, 0.0, 0.0],
                    style: Style {
                        fill: Some([28, 114, 176, 255]),
                        stroke: None,
                        fill_rule: FillRule::NonZero,
                    },
                })],
            }));
            if mask_editing {
                engine.document.layers[0].masks.push(MaskEntry {
                    id: 1,
                    name: "Vector mask".into(),
                    plane: LayerMask::new(
                        MaskBounds {
                            left: 0,
                            top: 0,
                            right: 16,
                            bottom: 12,
                        },
                        255,
                    ),
                });
                engine.document.next_mask_id = 2;
            }
            engine.load(&engine.save().unwrap()).unwrap();
            if animation {
                send(
                    &mut engine,
                    json!({"type":"enable_animation","duration_ms":100}),
                )
                .unwrap();
            }
            let previous = engine.save().unwrap();
            send(
                &mut engine,
                json!({"type":"set_layer","id":1,"name":"Checkpoint","opacity":1,"visible":true}),
            )
            .unwrap();
            if mask_editing {
                send(
                    &mut engine,
                    json!({"type":"set_mask_editing","id":1,"mask_id":1,"enabled":true}),
                )
                .unwrap();
                assert_eq!(engine.state()["maskEditing"], true);
            } else {
                select_pixels(&mut engine);
            }
            let checkpoint = engine.save().unwrap();
            let original = engine.document.clone();
            assert_unchanged(&mut engine, json!({"type":"rasterize_vector","id":1}), true);
            assert!(engine.document == original);
            if let Some(animation) = &engine.document.animation {
                assert!(animation
                    .cels
                    .values()
                    .all(|cel| matches!(&cel.source, CelSource::Vector(_))));
            } else {
                assert!(engine.document.layers[0].is_vector());
            }
            engine.command(Command::Undo).unwrap();
            assert!(engine.save().unwrap() == previous);
            engine.command(Command::Redo).unwrap();
            assert!(engine.save().unwrap() == checkpoint);
        }
    }
}

#[test]
fn live_strokes_reject_history_and_new_documents_but_allow_readonly_state_and_pick() {
    for animation in [false, true] {
        for request in [
            json!({"type":"undo"}),
            json!({"type":"redo"}),
            json!({"type":"new","width":9,"height":7}),
            json!({"type":"new_indexed","width":9,"height":7,"palette":palette()}),
        ] {
            let mut engine = if animation {
                animated(false, false)
            } else {
                still(false, false)
            };
            send(
                &mut engine,
                json!({"type":"set_layer","id":1,"name":"Checkpoint","opacity":1,"visible":true}),
            )
            .unwrap();
            let checkpoint = engine.save().unwrap();
            send(
                &mut engine,
                json!({"type":"set_layer","id":1,"name":"Future","opacity":1,"visible":true}),
            )
            .unwrap();
            let future = engine.save().unwrap();
            engine.command(Command::Undo).unwrap();
            assert!(engine.save().unwrap() == checkpoint);
            assert_eq!(engine.state()["canUndo"], true);
            assert_eq!(engine.state()["canRedo"], true);
            let state = engine.state();
            stroke(&mut engine);
            assert_unchanged(&mut engine, request, true);
            let live = engine.save().unwrap();
            let live_state = engine.state();
            let read = send(&mut engine, json!({"type":"state"})).unwrap();
            assert_eq!(read, live_state);
            let picked = send(&mut engine, json!({"type":"pick","x":5,"y":6})).unwrap();
            assert_eq!(picked["color"], json!([0, 0, 255]));
            assert!(engine.save().unwrap() == live);
            assert_eq!(engine.state(), live_state);
            send(&mut engine, json!({"type":"cancel"})).unwrap();
            assert!(engine.save().unwrap() == checkpoint);
            assert_eq!(engine.state(), state);
            engine.command(Command::Redo).unwrap();
            assert!(engine.save().unwrap() == future);
            engine.command(Command::Undo).unwrap();
            assert!(engine.save().unwrap() == checkpoint);
        }
    }
}
