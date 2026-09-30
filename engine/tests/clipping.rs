use podor_engine::{
    model::*, AdjustmentRequest, Command, Engine, LayerAction, LayerActionRequest, LayerTransform,
    ResampleFilter,
};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

fn set(engine: &mut Engine, id: u32, x: u32, y: u32, pixel: [u8; 4]) {
    let layer = engine
        .document
        .layers
        .iter_mut()
        .find(|layer| layer.id == id)
        .unwrap();
    let tile = Arc::make_mut(
        layer
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .entry((x / TILE_SIZE, y / TILE_SIZE))
            .or_insert_with(|| Arc::new(vec![0; TILE_BYTES])),
    );
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tile[offset..offset + 4].copy_from_slice(&pixel);
}

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn tiles(bytes: &[u8]) -> BTreeMap<TileKey, Vec<u8>> {
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

fn frame(engine: &Engine) -> BTreeMap<TileKey, Vec<u8>> {
    let mut copy = Engine::new(1, 1).unwrap();
    copy.load(&engine.save().unwrap()).unwrap();
    tiles(&copy.frame_with_background(true))
}

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    let tiles = frame(engine);
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tiles
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| tile[offset..offset + 4].try_into().unwrap())
}

fn chain() -> Engine {
    let mut engine = Engine::new(384, 192).unwrap();
    for id in 2..=3 {
        let mut layer = Layer::new(id, format!("Layer {id}"));
        layer.clipping = true;
        engine.document.layers.push(layer);
    }
    engine.document.next_id = 4;
    engine.document.active = 3;
    engine
}

fn preview(engine: &Engine, action: Value) -> Result<Vec<u8>, String> {
    engine.preview_layer_action(serde_json::from_value(json!({"id":engine.document.active,"revision":engine.state()["revision"],"selection_id":engine.state()["selectionId"],"mask_editing":engine.state()["maskEditing"],"action":action})).unwrap())
}

#[test]
fn clipping_chain_keeps_base_alpha_and_retains_outside_pixels() {
    let mut engine = chain();
    set(&mut engine, 1, 4, 5, [64, 32, 16, 128]);
    set(&mut engine, 2, 4, 5, [0, 0, 255, 255]);
    set(&mut engine, 3, 4, 5, [128, 0, 0, 128]);
    set(&mut engine, 3, 130, 5, [0, 255, 0, 255]);
    assert_eq!(pixel(&engine, 4, 5), [64, 0, 64, 128]);
    assert_eq!(pixel(&engine, 130, 5), [0; 4]);
    assert_eq!(
        engine.document.layers[2].raster().unwrap().tiles()[&(1, 0)][(5 * 128 + 2) * 4 + 1],
        255
    );
    engine.document.layers[0].opacity = 0.5;
    assert_eq!(pixel(&engine, 4, 5), [32, 0, 32, 64]);
    assert_eq!(engine.state()["layers"][1]["clippingBase"], 1);
    assert_eq!(engine.state()["layers"][2]["clippingBase"], 1);
    assert!(engine.state()["layers"][0]["clippingBase"].is_null());
}

#[test]
fn all_clipped_blends_preserve_semitransparent_edge_coverage() {
    let expected = [
        [192, 64, 128],
        [48, 32, 96],
        [208, 160, 224],
        [96, 65, 192],
        [96, 96, 192],
        [64, 64, 128],
        [192, 128, 192],
        [128, 64, 64],
    ];
    let modes = [
        BlendMode::Normal,
        BlendMode::Multiply,
        BlendMode::Screen,
        BlendMode::Overlay,
        BlendMode::SoftLight,
        BlendMode::Darken,
        BlendMode::Lighten,
        BlendMode::Difference,
    ];
    for (mode, expected) in modes.into_iter().zip(expected) {
        let mut engine = chain();
        set(&mut engine, 1, 0, 0, [32, 64, 96, 128]);
        set(&mut engine, 2, 0, 0, [192, 64, 128, 255]);
        engine.document.layers[1].blend = mode;
        let result = pixel(&engine, 0, 0);
        assert_eq!(result[3], 128, "{mode:?}");
        for c in 0..3 {
            assert!(
                i32::from(result[c]).abs_diff((expected[c] * 128 + 127) / 255) <= 1,
                "{mode:?}: {result:?}"
            );
        }
    }
}

#[test]
fn base_blend_and_opacity_apply_once_to_the_complete_chain() {
    let mut engine = chain();
    engine.document.layers[1].clipping = false;
    engine.document.layers[1].blend = BlendMode::Multiply;
    engine.document.layers[1].opacity = 0.5;
    set(&mut engine, 1, 0, 0, [120, 200, 80, 255]);
    set(&mut engine, 2, 0, 0, [64, 0, 0, 128]);
    set(&mut engine, 3, 0, 0, [0, 255, 0, 255]);
    assert_eq!(pixel(&engine, 0, 0), [90, 200, 60, 255]);
}

#[test]
fn hidden_base_cannot_rebind_to_lower_visible_layer_or_leak_through_thumbnail() {
    let mut engine = chain();
    set(&mut engine, 1, 0, 0, [255, 255, 255, 255]);
    engine.document.layers[1].clipping = false;
    engine.document.layers[1].visible = false;
    set(&mut engine, 2, 0, 0, [255, 0, 0, 255]);
    set(&mut engine, 3, 0, 0, [0, 255, 0, 255]);
    assert_eq!(pixel(&engine, 0, 0), [255, 255, 255, 255]);
    engine.document.layers[0].visible = false;
    assert_eq!(pixel(&engine, 0, 0), [0; 4]);
    let mut loaded = Engine::new(1, 1).unwrap();
    loaded.load(&engine.save().unwrap()).unwrap();
    let mut thumbnails = loaded.previews().unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while thumbnails.is_empty() {
        assert!(std::time::Instant::now() < deadline);
        std::thread::sleep(std::time::Duration::from_millis(1));
        thumbnails = loaded.previews().unwrap();
    }
    assert_eq!(&thumbnails[16..20], &[0; 4]);
    assert!(
        thumbnails[20..20 + (PREVIEW_EDGE * PREVIEW_EDGE * 4) as usize]
            .iter()
            .all(|&value| value == 0)
    );
}

#[test]
fn masks_of_base_and_clip_are_independent_and_do_not_raise_group_alpha() {
    let mut engine = chain();
    set(&mut engine, 1, 0, 0, [128, 0, 0, 128]);
    set(&mut engine, 2, 0, 0, [0, 0, 255, 255]);
    for index in 0..2 {
        let mut mask = LayerMask::new(
            MaskBounds {
                left: 0,
                top: 0,
                right: 384,
                bottom: 192,
            },
            255,
        );
        mask.tiles
            .insert((0, 0), Arc::new(vec![128; MASK_TILE_BYTES]));
        engine.document.layers[index].set_first_mask(Some(mask));
        engine.document.assign_mask_ids().unwrap();
    }
    assert_eq!(pixel(&engine, 0, 0), [32, 0, 32, 64]);
    engine.document.layers[0].first_mask_mut().unwrap().enabled = false;
    assert_eq!(pixel(&engine, 0, 0), [64, 0, 64, 128]);
    engine.document.layers[1].first_mask_mut().unwrap().enabled = false;
    assert_eq!(pixel(&engine, 0, 0), [0, 0, 128, 128]);
}

#[test]
fn clipping_metadata_stale_bottom_and_noop_are_atomic_and_locked_metadata_is_allowed() {
    let mut engine = chain();
    let before = engine.save().unwrap();
    let state = engine.state();
    for (id, revision) in [(1, 0), (99, 0), (2, 1)] {
        assert!(engine
            .command(Command::SetClipping {
                id,
                clipping: true,
                revision
            })
            .is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
    engine
        .command(Command::SetClipping {
            id: 2,
            clipping: true,
            revision: 0,
        })
        .unwrap();
    assert_eq!(engine.state(), state);
    engine.document.layers[1].locked = true;
    let original = engine.save().unwrap();
    engine
        .command(Command::SetClipping {
            id: 2,
            clipping: false,
            revision: 0,
        })
        .unwrap();
    assert_eq!(engine.state()["revision"], 1);
    assert_eq!(engine.state()["layers"][2]["clippingBase"], 2);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), original);
    engine.command(Command::Redo).unwrap();
    assert!(!engine.document.layers[1].clipping);
}

#[test]
fn deleting_base_releases_chain_and_reordering_bottom_preserves_valid_topology_with_undo() {
    let mut engine = chain();
    let before = engine.save().unwrap();
    engine.command(Command::RemoveLayer { id: 1 }).unwrap();
    assert!(engine.document.layers.iter().all(|layer| !layer.clipping));
    engine.document.validate().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    let revision = engine.state()["revision"].as_u64().unwrap();
    engine
        .command(Command::ReorderLayer {
            id: 3,
            index: 0,
            revision,
        })
        .unwrap();
    assert!(!engine.document.layers[0].clipping);
    assert_eq!(engine.state()["layers"][2]["clippingBase"], 1);
    engine.document.validate().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn merge_visible_bakes_complete_chain_and_rejects_partial_hidden_dependencies() {
    let mut engine = chain();
    set(&mut engine, 1, 0, 0, [64, 0, 0, 128]);
    set(&mut engine, 2, 0, 0, [0, 0, 255, 255]);
    let expected = pixel(&engine, 0, 0);
    let before = engine.save().unwrap();
    engine.command(Command::MergeVisible).unwrap();
    assert_eq!(engine.document.layers.len(), 1);
    assert!(!engine.document.layers[0].clipping);
    assert_eq!(pixel(&engine, 0, 0), expected);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.document.layers[2].visible = false;
    let before = engine.save().unwrap();
    let state = engine.state();
    assert!(engine.command(Command::MergeVisible).is_err());
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
}

#[test]
fn base_move_reveals_retained_clip_pixels_without_moving_the_clip() {
    let mut engine = chain();
    set(&mut engine, 1, 1, 0, [128, 0, 0, 128]);
    set(&mut engine, 2, 1, 0, [0, 0, 255, 255]);
    set(&mut engine, 2, 130, 0, [0, 255, 0, 255]);
    engine.document.active = 1;
    let clip = engine.document.layers[1].raster().unwrap().tiles().clone();
    let before = engine.save().unwrap();
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 129,
            dy: 0,
        })
        .unwrap();
    assert_eq!(pixel(&engine, 1, 0), [0; 4]);
    assert_eq!(pixel(&engine, 130, 0), [0, 128, 0, 128]);
    assert_eq!(engine.document.layers[1].raster().unwrap().tiles(), &clip);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn canonical_translate_transform_gradient_and_mask_previews_equal_commits_without_mutation() {
    for id in [1, 2] {
        for kind in [
            "translate",
            "transform",
            "gradient",
            "mask_translate",
            "mask_transform",
        ] {
            let mut engine = chain();
            for x in 120..140 {
                for y in 2..6 {
                    set(&mut engine, 1, x, y, [64, 32, 16, 128]);
                    set(&mut engine, 2, x, y, [0, 0, 255, 255]);
                }
            }
            engine.document.active = id;
            if kind.starts_with("mask_") {
                engine
                    .command(Command::AddMask {
                        name: None,
                        revision: None,
                        selection_id: None,
                        mode: podor_engine::MaskMode::Reveal,
                    })
                    .unwrap();
                engine
                    .command(Command::SetMaskEditing {
                        mask_id: None,
                        id: Some(id),
                        enabled: true,
                    })
                    .unwrap();
            }
            engine.frame_with_background(true);
            let state = engine.state();
            let before = engine.save().unwrap();
            let revision = state["revision"].as_u64().unwrap();
            let action = match kind {
                "translate" | "mask_translate" => json!({"kind":"translate","dx":130,"dy":0}),
                "transform" | "mask_transform" => {
                    json!({"kind":"transform","transform":{"width":30,"height":8,"dx":30,"dy":20,"angle":0,"filter":"nearest"}})
                }
                _ => {
                    json!({"kind":"gradient","settings":{"start":[0,0],"end":[384,0],"from":[255,0,0,255],"to":[0,255,0,80],"opacity":0.7,"shape":"linear"}})
                }
            };
            let preview = preview(&engine, action.clone()).unwrap();
            assert_eq!(engine.state(), state);
            assert_eq!(engine.save().unwrap(), before);
            assert_eq!(engine.frame_with_background(true).len(), 16);
            match action["kind"].as_str().unwrap() {
                "translate" => {
                    engine
                        .command(Command::TranslateLayer {
                            mask_id: None,
                            id,
                            dx: 130,
                            dy: 0,
                        })
                        .unwrap();
                }
                "transform" => {
                    command(&mut engine,json!({"type":"transform_layer","id":id,"revision":revision,"transform":action["transform"]})).unwrap();
                }
                _ => {
                    command(&mut engine,json!({"type":"gradient","id":id,"revision":revision,"settings":action["settings"]})).unwrap();
                }
            }
            let actual = frame(&engine);
            for (key, tile) in tiles(&preview) {
                assert_eq!(
                    tile,
                    actual
                        .get(&key)
                        .cloned()
                        .unwrap_or_else(|| vec![0; TILE_BYTES]),
                    "{id} {kind} {key:?}"
                );
            }
            engine.command(Command::Undo).unwrap();
            assert_eq!(engine.save().unwrap(), before);
        }
    }
}

#[test]
fn selected_move_preview_checks_snapshot_and_clears_old_tiles_without_ghosts() {
    let mut engine = chain();
    set(&mut engine, 1, 2, 3, [128, 0, 0, 128]);
    set(&mut engine, 2, 2, 3, [0, 255, 0, 255]);
    set(&mut engine, 2, 130, 3, [0, 0, 255, 255]);
    engine.document.active = 1;
    command(&mut engine,json!({"type":"select_shape","selection":{"kind":"lasso","left":0,"top":0,"right":31,"bottom":31,"points":[{"x":0.2,"y":0.2},{"x":30.2,"y":0.2},{"x":30.2,"y":30.2},{"x":0.2,"y":30.2}]}})).unwrap();
    engine.frame_with_background(true);
    let mut request = json!({"id":1,"revision":engine.state()["revision"],"selection_id":engine.state()["selectionId"],"mask_editing":false,"action":{"kind":"translate","dx":128,"dy":0}});
    let before = engine.save().unwrap();
    let state = engine.state();
    for field in ["id", "revision", "selection_id", "mask_editing"] {
        let original = request[field].clone();
        request[field] = if field == "mask_editing" {
            json!(true)
        } else {
            json!(999)
        };
        assert!(engine
            .preview_layer_action(serde_json::from_value(request.clone()).unwrap())
            .is_err());
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), before);
        request[field] = original;
    }
    let view = engine
        .preview_layer_action(serde_json::from_value(request).unwrap())
        .unwrap();
    assert_eq!(tiles(&view)[&(0, 0)].iter().copied().max(), Some(0));
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 128,
            dy: 0,
        })
        .unwrap();
    assert_eq!(tiles(&view), frame(&engine));
    assert_eq!(pixel(&engine, 130, 3), [0, 0, 128, 128]);
}

#[test]
fn invalid_and_active_stroke_previews_leave_document_history_selection_and_dirty_unchanged() {
    let mut engine = chain();
    set(&mut engine, 1, 0, 0, [128, 0, 0, 128]);
    set(&mut engine, 2, 0, 0, [0, 255, 0, 255]);
    engine.document.active = 2;
    engine.frame_with_background(true);
    let before = engine.save().unwrap();
    let state = engine.state();
    let request = LayerActionRequest {
        frame_id: None,
        cel_id: None,
        target_layer_id: None,
        mask_id: None,
        id: 2,
        revision: 0,
        selection_id: 0,
        mask_editing: false,
        action: LayerAction::Transform {
            transform: LayerTransform {
                width: 2,
                height: 2,
                dx: f64::NAN,
                dy: 0.0,
                angle: 0.0,
                flip_x: false,
                flip_y: false,
                filter: ResampleFilter::Nearest,
            },
        },
    };
    assert!(engine.preview_layer_action(request).is_err());
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    engine
        .command(Command::Begin {
            brush: Brush::default(),
            assistant: None,
        })
        .unwrap();
    assert!(preview(&engine, json!({"kind":"translate","dx":1,"dy":0})).is_err());
    engine.command(Command::Cancel).unwrap();
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn action_diff_is_relative_to_original_reuses_unchanged_tiles_and_returns_no_tiles_at_origin() {
    let mut engine = chain();
    set(&mut engine, 1, 2, 3, [128, 0, 0, 128]);
    set(&mut engine, 2, 2, 3, [0, 255, 0, 255]);
    set(&mut engine, 2, 130, 3, [0, 0, 255, 255]);
    let mut layer = Layer::new(4, "Unaffected".into());
    layer
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((2, 0), Arc::new(vec![0; TILE_BYTES]));
    engine.document.layers.push(layer);
    engine.document.next_id = 5;
    set(&mut engine, 4, 300, 3, [255, 0, 255, 255]);
    engine.document.active = 1;
    engine.frame_with_background(true);
    let original = frame(&engine);
    let source = engine.save().unwrap();
    let state = engine.state();
    let first = tiles(&preview(&engine, json!({"kind":"translate","dx":128,"dy":0})).unwrap());
    assert_eq!(first.keys().copied().collect::<Vec<_>>(), [(0, 0), (1, 0)]);
    assert!(!first.contains_key(&(2, 0)));
    assert_eq!(
        preview(&engine, json!({"kind":"translate","dx":0,"dy":0}))
            .unwrap()
            .len(),
        16
    );
    let second = tiles(&preview(&engine, json!({"kind":"translate","dx":256,"dy":0})).unwrap());
    assert!(!second.contains_key(&(1, 0)));
    let mut merged = original.clone();
    merged.extend(second);
    assert_eq!(merged[&(1, 0)], original[&(1, 0)]);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), source);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 256,
            dy: 0,
        })
        .unwrap();
    assert_eq!(merged, frame(&engine));
}

#[test]
fn active_stroke_cache_never_splits_a_clip_chain_and_cancel_restores_raw_content() {
    for active in [1, 2, 3] {
        for eraser in [false, true] {
            let mut engine = chain();
            for id in 1..=3 {
                for key in [(0, 0), (1, 0)] {
                    engine.document.layers[id - 1]
                        .raster_mut()
                        .unwrap()
                        .tiles_mut()
                        .insert(key, Arc::new([30, 40, 50, 128].repeat(TILE_BYTES / 4)));
                }
            }
            engine.document.active = active;
            engine.document.layers[1].blend = BlendMode::SoftLight;
            let before = engine.save().unwrap();
            engine.frame_with_background(true);
            engine
                .command(Command::Begin {
                    brush: Brush {
                        size: 70.0,
                        opacity: 0.5,
                        color: [230, 70, 25],
                        eraser,
                        ..Brush::default()
                    },
                    assistant: None,
                })
                .unwrap();
            for x in [110.0, 127.0, 145.0] {
                engine
                    .samples(&[Sample {
                        x,
                        y: 40.0,
                        pressure: 0.8,
                    }])
                    .unwrap();
                let expected = frame(&engine);
                for (key, tile) in tiles(&engine.frame_with_background(true)) {
                    assert_eq!(tile, expected[&key], "{active} {eraser} {key:?}");
                }
            }
            engine.command(Command::Cancel).unwrap();
            assert_eq!(engine.save().unwrap(), before);
        }
    }
}

#[test]
fn opacity_blend_and_pixel_adjustment_previews_use_complete_clip_chain() {
    for id in [1, 2] {
        for kind in ["layer_blend", "tone", "blur"] {
            let mut engine = chain();
            for x in 120..140 {
                for y in 2..6 {
                    set(&mut engine, 1, x, y, [64, 32, 16, 128]);
                    set(&mut engine, 2, x, y, [0, 0, 255, 255]);
                }
            }
            engine.document.active = id;
            engine.frame_with_background(true);
            let request = json!({"id":id,"revision":engine.state()["revision"],"selection_id":engine.state()["selectionId"],"settings":{"kind":kind,"brightness":0.2,"contrast":0.1,"saturation":0.0,"sigma":2.0,"opacity":0.5,"blend":"multiply"}});
            let before = engine.save().unwrap();
            let state = engine.state();
            let view = engine
                .preview_adjustment(
                    serde_json::from_value::<AdjustmentRequest>(request.clone()).unwrap(),
                )
                .unwrap();
            assert_eq!(engine.state(), state);
            assert_eq!(engine.save().unwrap(), before);
            command(
                &mut engine,
                json!({"type":"apply_adjustment","request":request}),
            )
            .unwrap();
            let expected = frame(&engine);
            for (key, tile) in tiles(&view) {
                assert_eq!(tile, expected[&key], "{kind} {id}");
            }
        }
    }
}

#[test]
fn indexed_compositing_and_nearest_preview_use_same_clip_group_without_changing_slots() {
    let mut engine = Engine::new(256, 128).unwrap();
    let palette = IndexedPalette {
        colors: vec![
            [0, 0, 0, 0],
            [255, 0, 0, 128],
            [0, 0, 255, 255],
            [0, 255, 0, 255],
        ],
        transparent: 0,
        order: vec![0, 1, 2, 3],
    };
    engine
        .command(Command::NewIndexed {
            width: 256,
            height: 128,
            palette,
        })
        .unwrap();
    engine
        .command(Command::FillIndexed {
            x: 0,
            y: 0,
            index: 1,
            tolerance: 0,
            opacity: 1.0,
            contiguous: true,
            merged: false,
        })
        .unwrap();
    engine.command(Command::AddLayer).unwrap();
    engine
        .command(Command::FillIndexed {
            x: 0,
            y: 0,
            index: 2,
            tolerance: 0,
            opacity: 1.0,
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
    assert_eq!(pixel(&engine, 0, 0), [0, 0, 128, 128]);
    let cached = tiles(&engine.frame_with_background(true));
    assert_eq!(cached, frame(&engine));
    let source = engine.document.layers[1].raster().unwrap().tiles().clone();
    let view = preview(&engine, json!({"kind":"translate","dx":128,"dy":0})).unwrap();
    let mut merged = cached;
    merged.extend(tiles(&view));
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 2,
            dx: 128,
            dy: 0,
        })
        .unwrap();
    assert!(merged == frame(&engine));
    assert_eq!(pixel(&engine, 0, 0), [128, 0, 0, 128]);
    assert_eq!(pixel(&engine, 128, 0), [0, 0, 128, 128]);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.document.layers[1].raster().unwrap().tiles(), &source);
}
