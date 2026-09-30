use podor_engine::{model::*, Command, Engine, LayerTransform, ResampleFilter};
use serde_json::{json, Value};
use std::sync::Arc;

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn source() -> Engine {
    let mut engine = Engine::new(8, 8).unwrap();
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: [200, 100, 50, 255],
            tolerance: 0,
            contiguous: true,
            merged: false,
        })
        .unwrap();
    let mut mask = LayerMask::new(
        MaskBounds {
            left: -2,
            top: -2,
            right: 4,
            bottom: 4,
        },
        255,
    );
    let mut tile = vec![255; MASK_TILE_BYTES];
    for y in 0..6 {
        for x in 0..6 {
            tile[y * TILE_SIZE as usize + x] = ((x + y * 6) * 7) as u8;
        }
    }
    mask.tiles.insert((0, 0), Arc::new(tile));
    engine.document.active_mut().set_first_mask(Some(mask));
    engine.document.assign_mask_ids().unwrap();
    engine.document.validate().unwrap();
    engine
}

#[test]
fn linked_and_unlinked_moves_preserve_negative_bounds_and_outside_pixels() {
    for linked in [false, true] {
        let mut engine = source();
        engine
            .document
            .active_mut()
            .first_mask_mut()
            .unwrap()
            .linked = linked;
        let original = engine.document.layers[0].first_mask().cloned().unwrap();
        let bytes = engine.save().unwrap();
        engine
            .command(Command::TranslateLayer {
                mask_id: None,
                id: 1,
                dx: 3,
                dy: 2,
            })
            .unwrap();
        let mask = engine.document.layers[0].first_mask().unwrap();
        assert_eq!(
            mask.bounds.left,
            original.bounds.left + if linked { 3 } else { 0 }
        );
        assert!(Arc::ptr_eq(&mask.tiles[&(0, 0)], &original.tiles[&(0, 0)]));
        for y in -2..4 {
            for x in -2..4 {
                assert_eq!(
                    mask.sample(
                        x + if linked { 3 } else { 0 },
                        y + if linked { 2 } else { 0 }
                    ),
                    original.sample(x, y)
                );
            }
        }
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), bytes);
        engine.command(Command::Redo).unwrap();
        assert_eq!(
            engine.document.layers[0].first_mask().unwrap().linked,
            linked
        );
    }
}

#[test]
fn mask_only_move_and_flip_leave_rgba_storage_untouched_even_with_alpha_lock() {
    let mut engine = source();
    engine.document.active_mut().alpha_locked = true;
    let pixels = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":true}),
    )
    .unwrap();
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 1,
            dy: -1,
        })
        .unwrap();
    assert!(Arc::ptr_eq(
        &pixels,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
    let original = engine.document.layers[0].first_mask().cloned().unwrap();
    let revision = engine.command(Command::State).unwrap()["revision"]
        .as_u64()
        .unwrap();
    engine
        .command(Command::TransformLayer {
            mask_id: None,
            id: 1,
            revision,
            transform: LayerTransform {
                width: 6,
                height: 6,
                dx: 0.0,
                dy: 0.0,
                angle: 0.0,
                flip_x: true,
                flip_y: false,
                filter: ResampleFilter::Nearest,
            },
        })
        .unwrap();
    let mask = engine.document.layers[0].first_mask().unwrap();
    for y in mask.bounds.top..mask.bounds.bottom {
        for x in mask.bounds.left..mask.bounds.right {
            assert_eq!(
                mask.sample(x, y),
                original.sample(original.bounds.left + original.bounds.right - x - 1, y)
            );
        }
    }
    assert!(Arc::ptr_eq(
        &pixels,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
}

#[test]
fn canvas_anchor_moves_linked_masks_and_image_resize_scales_all_mask_coordinates() {
    for linked in [false, true] {
        let mut engine = source();
        engine
            .document
            .active_mut()
            .first_mask_mut()
            .unwrap()
            .linked = linked;
        engine
            .command(Command::ResizeCanvas {
                width: 12,
                height: 10,
                anchor: 4,
                revision: 1,
            })
            .unwrap();
        let mask = engine.document.layers[0].first_mask().unwrap();
        assert_eq!(mask.bounds.left, if linked { 0 } else { -2 });
        assert_eq!(mask.bounds.top, if linked { -1 } else { -2 });
        engine.command(Command::Undo).unwrap();
        let original = engine.document.layers[0].first_mask().cloned().unwrap();
        let revision = engine.command(Command::State).unwrap()["revision"]
            .as_u64()
            .unwrap();
        engine
            .command(Command::ResizeImage {
                width: 16,
                height: 16,
                filter: ResampleFilter::Nearest,
                revision,
            })
            .unwrap();
        let mask = engine.document.layers[0].first_mask().unwrap();
        assert_eq!(
            mask.bounds,
            MaskBounds {
                left: -4,
                top: -4,
                right: 8,
                bottom: 8
            }
        );
        for y in -4..8 {
            for x in -4..8 {
                assert_eq!(
                    mask.sample(x, y),
                    original.sample(x.div_euclid(2), y.div_euclid(2))
                );
            }
        }
    }
}

#[test]
fn linked_layer_rotation_uses_the_same_source_center_and_rejects_invalid_geometry_atomically() {
    let mut engine = source();
    let original = engine.document.layers[0].first_mask().cloned().unwrap();
    let revision = engine.command(Command::State).unwrap()["revision"]
        .as_u64()
        .unwrap();
    engine
        .command(Command::TransformLayer {
            mask_id: None,
            id: 1,
            revision,
            transform: LayerTransform {
                width: 8,
                height: 8,
                dx: 0.0,
                dy: 0.0,
                angle: 180.0,
                flip_x: false,
                flip_y: false,
                filter: ResampleFilter::Nearest,
            },
        })
        .unwrap();
    let mask = engine.document.layers[0].first_mask().unwrap();
    for y in -2..4 {
        for x in -2..4 {
            assert_eq!(mask.sample(7 - x, 7 - y), original.sample(x, y));
        }
    }
    let before = engine.save().unwrap();
    let state = engine.state();
    assert!(engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: i32::MAX,
            dy: 0
        })
        .is_err());
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
}

#[test]
fn mask_allocation_is_included_in_document_budget_without_partial_pixels_or_history() {
    let mut engine = Engine::new(1024, 1024).unwrap();
    let tile = Arc::new(vec![255; TILE_BYTES]);
    for id in 1..=MAX_LAYERS as u32 {
        let mut layer = Layer::new(id, format!("{id}"));
        for y in 0..8 {
            for x in 0..8 {
                layer
                    .raster_mut()
                    .unwrap()
                    .tiles_mut()
                    .insert((x, y), tile.clone());
            }
        }
        if id == 1 {
            engine.document.layers[0] = layer;
        } else {
            engine.document.layers.push(layer);
        }
    }
    engine.document.next_id = MAX_LAYERS as u32 + 1;
    engine.document.validate().unwrap();
    assert_eq!(engine.document.pixel_bytes(), MAX_DOCUMENT_BYTES);
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 0,
                top: 0,
                right: 1,
                bottom: 1,
            }),
        })
        .unwrap();
    let state = engine.state();
    assert!(command(&mut engine, json!({"type":"add_mask","mode":"selection"})).is_err());
    assert!(engine.document.layers[0].masks.is_empty());
    assert_eq!(engine.state(), state);
    command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    let state = engine.state();
    command(&mut engine,json!({"type":"begin","brush":{"size":2,"opacity":1,"hardness":1,"color":[0,0,0],"eraser":false}})).unwrap();
    assert!(engine
        .samples(&[Sample {
            x: 0.5,
            y: 0.5,
            pressure: 1.0
        }])
        .is_err());
    assert_eq!(engine.state(), state);
    assert!(engine.document.layers[0]
        .first_mask()
        .unwrap()
        .tiles
        .is_empty());
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
}

#[test]
fn mask_strokes_preserve_untouched_buffers_and_release_revealed_tiles() {
    let mut engine = Engine::new(384, 128).unwrap();
    let rgba = Arc::new(vec![255; TILE_BYTES]);
    for x in 0..3 {
        engine
            .document
            .active_mut()
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert((x, 0), rgba.clone());
    }
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 384,
            bottom: 128,
        },
        255,
    );
    let mut untouched = vec![255; MASK_TILE_BYTES];
    untouched[MASK_TILE_BYTES - 1] = 0;
    let untouched = Arc::new(untouched);
    mask.tiles.insert((1, 0), untouched.clone());
    engine.document.active_mut().set_first_mask(Some(mask));
    engine.document.assign_mask_ids().unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":true}),
    )
    .unwrap();
    for color in [[0, 0, 0], [255, 255, 255]] {
        engine
            .command(Command::Begin {
                brush: Brush {
                    size: 16.0,
                    opacity: 1.0,
                    hardness: 1.0,
                    color,
                    raster: BrushRaster::Pixel,
                    ..Brush::default()
                },
                assistant: None,
            })
            .unwrap();
        engine
            .samples(&[Sample {
                x: 20.0,
                y: 20.0,
                pressure: 1.0,
            }])
            .unwrap();
        engine.command(Command::End).unwrap();
        assert!(Arc::ptr_eq(
            &engine.document.layers[0].first_mask().unwrap().tiles[&(1, 0)],
            &untouched
        ));
        for tile in engine.document.layers[0].raster().unwrap().tiles().values() {
            assert!(Arc::ptr_eq(tile, &rgba));
        }
    }
    assert!(!engine.document.layers[0]
        .first_mask()
        .unwrap()
        .tiles
        .contains_key(&(0, 0)));
    engine.command(Command::Undo).unwrap();
    assert_eq!(
        engine.document.layers[0]
            .first_mask()
            .unwrap()
            .sample(20, 20),
        0
    );
    engine.command(Command::Redo).unwrap();
    assert_eq!(
        engine.document.layers[0]
            .first_mask()
            .unwrap()
            .sample(20, 20),
        255
    );
}

#[test]
fn mask_target_allows_layer_metadata_but_rejects_pixel_adjustments_and_cut() {
    let mut engine = source();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":true}),
    )
    .unwrap();
    let before = engine.document.layers[0].first_mask().cloned();
    let rgba = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    let request = json!({"id":1,"revision":engine.state()["revision"],"settings":{"kind":"layer_blend","brightness":0,"contrast":0,"saturation":0,"sigma":0,"opacity":0.5,"blend":"multiply"}});
    engine
        .preview_adjustment(serde_json::from_value(request.clone()).unwrap())
        .unwrap();
    command(
        &mut engine,
        json!({"type":"apply_adjustment","request":request}),
    )
    .unwrap();
    assert_eq!(engine.document.layers[0].opacity, 0.5);
    assert_eq!(engine.document.layers[0].blend, BlendMode::Multiply);
    assert_eq!(engine.document.layers[0].first_mask().cloned(), before);
    assert!(Arc::ptr_eq(
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)],
        &rgba
    ));
    let bytes = engine.save().unwrap();
    let state = engine.state();
    let request = json!({"id":1,"revision":state["revision"],"settings":{"kind":"tone","brightness":1,"contrast":0,"saturation":0,"sigma":0}});
    assert!(engine
        .preview_adjustment(serde_json::from_value(request.clone()).unwrap())
        .is_err());
    assert!(command(
        &mut engine,
        json!({"type":"apply_adjustment","request":request})
    )
    .is_err());
    assert!(command(
        &mut engine,
        json!({"type":"cut_selection","revision":state["revision"]})
    )
    .is_err());
    assert_eq!(engine.save().unwrap(), bytes);
    assert_eq!(engine.state(), state);
}

#[test]
fn selected_mask_moves_inside_maximum_width_without_expanding_the_whole_canvas() {
    let mut engine = Engine::new(MAX_DIMENSION, 1).unwrap();
    let bounds = MaskBounds {
        left: 0,
        top: 0,
        right: MAX_DIMENSION as i32,
        bottom: 1,
    };
    let mut mask = LayerMask::new(bounds, 255);
    let mut tile = vec![255; MASK_TILE_BYTES];
    tile[0] = 0;
    mask.tiles.insert((0, 0), Arc::new(tile));
    engine.document.active_mut().set_first_mask(Some(mask));
    engine.document.assign_mask_ids().unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":true}),
    )
    .unwrap();
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 0,
                top: 0,
                right: 1,
                bottom: 1,
            }),
        })
        .unwrap();
    let before = engine.document.layers[0].first_mask().cloned().unwrap();
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 1,
            dy: 0,
        })
        .unwrap();
    let moved = engine.document.layers[0].first_mask().unwrap();
    assert_eq!(moved.bounds, bounds);
    assert!(moved.bounds.width() <= MAX_DIMENSION);
    assert_eq!(moved.sample(0, 0), 255);
    assert_eq!(moved.sample(1, 0), 0);
    assert_eq!(moved.sample(MAX_DIMENSION as i32 - 1, 0), 255);
    engine.command(Command::Undo).unwrap();
    assert_eq!(
        engine.document.layers[0].first_mask().cloned(),
        Some(before)
    );
    engine.command(Command::Redo).unwrap();
    assert_eq!(
        engine.document.layers[0].first_mask().unwrap().sample(1, 0),
        0
    );
}

#[test]
fn selected_mask_moves_union_only_source_and_shifted_region_with_negative_pixels() {
    let mut engine = source();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":true}),
    )
    .unwrap();
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 0,
                top: 0,
                right: 1,
                bottom: 1,
            }),
        })
        .unwrap();
    let original = engine.document.layers[0].first_mask().cloned().unwrap();
    let gray = original.sample(0, 0);
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: -3,
            dy: 0,
        })
        .unwrap();
    let moved = engine.document.layers[0].first_mask().unwrap();
    assert_eq!(
        moved.bounds,
        MaskBounds {
            left: -3,
            top: -2,
            right: 4,
            bottom: 4
        }
    );
    assert_eq!(moved.sample(-3, 0), gray);
    assert_eq!(moved.sample(0, 0), original.default);
    for y in -2..4 {
        for x in -2..4 {
            if x != 0 || y != 0 {
                assert_eq!(moved.sample(x, y), original.sample(x, y));
            }
        }
    }
    assert_eq!(moved.sample(-4, 0), original.default);
    engine.command(Command::Undo).unwrap();
    assert_eq!(
        engine.document.layers[0].first_mask().cloned(),
        Some(original)
    );
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 0,
                top: 0,
                right: 1,
                bottom: 1,
            }),
        })
        .unwrap();
    let bytes = engine.save().unwrap();
    let state = engine.state();
    for dx in [i32::MIN, i32::MAX] {
        assert!(engine
            .command(Command::TranslateLayer {
                mask_id: None,
                id: 1,
                dx,
                dy: 0
            })
            .is_err());
        assert_eq!(engine.save().unwrap(), bytes);
        assert_eq!(engine.state(), state);
    }
}

#[test]
#[ignore]
fn report_mask_stroke_latency_with_large_planes_and_many_layers() {
    for layer_count in [1, 24] {
        let mut engine = Engine::new(1024, 1024).unwrap();
        let rgba = Arc::new(vec![127; TILE_BYTES]);
        for id in 1..=layer_count {
            let mut layer = Layer::new(id, format!("{id}"));
            for y in 0..8 {
                for x in 0..8 {
                    layer
                        .raster_mut()
                        .unwrap()
                        .tiles_mut()
                        .insert((x, y), rgba.clone());
                }
            }
            if id == 1 {
                engine.document.layers[0] = layer;
            } else {
                engine.document.layers.push(layer);
            }
        }
        engine.document.next_id = layer_count + 1;
        let mut mask = LayerMask::new(
            MaskBounds {
                left: 0,
                top: 0,
                right: 4096,
                bottom: 4096,
            },
            255,
        );
        let mut gray = vec![255; MASK_TILE_BYTES];
        gray[MASK_TILE_BYTES - 1] = 254;
        let gray = Arc::new(gray);
        for y in 0..32 {
            for x in 0..32 {
                mask.tiles.insert((x, y), gray.clone());
            }
        }
        engine.document.active_mut().set_first_mask(Some(mask));
        engine.document.assign_mask_ids().unwrap();
        engine.document.validate().unwrap();
        command(
            &mut engine,
            json!({"type":"set_mask_editing","enabled":true}),
        )
        .unwrap();
        let start = std::time::Instant::now();
        engine
            .command(Command::Begin {
                brush: Brush {
                    size: 8.0,
                    hardness: 1.0,
                    color: [0, 0, 0],
                    raster: BrushRaster::Pixel,
                    ..Brush::default()
                },
                assistant: None,
            })
            .unwrap();
        let samples: Vec<_> = (0..100)
            .map(|i| Sample {
                x: 16.0 + i as f32 * 9.0,
                y: 16.0 + (i % 4) as f32 * 8.0,
                pressure: 1.0,
            })
            .collect();
        engine.samples(&samples).unwrap();
        engine.command(Command::End).unwrap();
        println!(
            "mask 4096² / {layer_count} RGBA layers / 100 samples / no frame: {:.3} ms",
            start.elapsed().as_secs_f64() * 1000.0
        );
        assert_eq!(engine.state()["canUndo"], true);
        for layer in &engine.document.layers {
            for tile in layer.raster().unwrap().tiles().values() {
                assert!(Arc::ptr_eq(tile, &rgba));
            }
        }
    }
}
