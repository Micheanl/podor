use podor_engine::{
    model::*, AdjustmentRequest, AdjustmentSettings, Command, Engine, GradientMap, GradientMapStop,
};
use serde_json::{json, Value};
use std::sync::Arc;

fn stops(colors: &[[u8; 3]]) -> GradientMap {
    GradientMap {
        stops: colors
            .iter()
            .enumerate()
            .map(|(index, &color)| GradientMapStop {
                position: index as f64 / (colors.len() - 1).max(1) as f64,
                color,
            })
            .collect(),
    }
}

fn request(engine: &Engine, settings: GradientMap) -> AdjustmentRequest {
    let state = engine.state();
    AdjustmentRequest {
        frame_id: None,
        cel_id: None,
        target_layer_id: None,
        id: engine.document.active,
        revision: state["revision"].as_u64().unwrap(),
        selection_id: Some(state["selectionId"].as_u64().unwrap()),
        settings: AdjustmentSettings::gradient_map(settings),
    }
}

fn apply(engine: &mut Engine, settings: GradientMap) -> Result<Value, String> {
    let state = engine.state();
    engine.command(Command::GradientMap {
        id: engine.document.active,
        revision: state["revision"].as_u64().unwrap(),
        selection_id: state["selectionId"].as_u64().unwrap(),
        settings,
    })
}

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    let tile =
        &engine.document.layers[0].raster().unwrap().tiles()[&(x / TILE_SIZE, y / TILE_SIZE)];
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tile[offset..offset + 4].try_into().unwrap()
}

fn fixture() -> Engine {
    let mut engine = Engine::new(256, 32).unwrap();
    for key in [(0, 0), (1, 0)] {
        engine.document.layers[0]
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert(key, Arc::new([80, 20, 40, 128].repeat(TILE_BYTES / 4)));
    }
    let mut other = Layer::new(2, "Other".into());
    other
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new([30, 40, 50, 255].repeat(TILE_BYTES / 4)));
    engine.document.layers.push(other);
    engine.document.next_id = 3;
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
    engine.frame_with_background(true);
    engine
}

#[test]
fn mapping_uses_straight_luminance_preserves_alpha_and_matches_actual_preview() {
    let mut engine = Engine::new(7, 1).unwrap();
    let mut tile = vec![0; TILE_BYTES];
    tile[..28].copy_from_slice(&[
        255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 255, 0, 0, 0, 255, 64, 0, 0,
        64, 8, 4, 2, 0,
    ]);
    let layer = &mut engine.document.layers[0];
    layer
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new(tile));
    layer.opacity = 0.6;
    layer.blend = BlendMode::Multiply;
    layer.alpha_locked = true;
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 7,
            bottom: 1,
        },
        255,
    );
    mask.tiles
        .insert((0, 0), Arc::new(vec![128; MASK_TILE_BYTES]));
    layer.set_first_mask(Some(mask.clone()));
    let mut other = Layer::new(2, "Other".into());
    other.opacity = 0.35;
    other
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new([30, 40, 50, 255].repeat(TILE_BYTES / 4)));
    engine.document.layers.push(other);
    engine.document.next_id = 3;
    engine.document.assign_mask_ids().unwrap();
    let before = engine.save().unwrap();
    engine.load(&before).unwrap();
    let baseline = engine.frame_with_background(true);
    let other = engine.document.layers[1].raster().unwrap().tiles()[&(0, 0)].clone();
    let state = engine.state();
    let settings = stops(&[[255, 0, 0], [0, 0, 255]]);
    let preview = engine
        .preview_adjustment(request(&engine, settings.clone()))
        .unwrap();
    assert_ne!(preview, baseline);
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    apply(&mut engine, settings).unwrap();
    assert_eq!(engine.frame_with_background(true), preview);
    for (x, expected) in [
        [201, 0, 54, 255],
        [73, 0, 182, 255],
        [237, 0, 18, 255],
        [0, 0, 255, 255],
        [255, 0, 0, 255],
        [50, 0, 14, 64],
        [8, 4, 2, 0],
    ]
    .into_iter()
    .enumerate()
    {
        assert_eq!(pixel(&engine, x as u32, 0), expected);
    }
    let active = &engine.document.layers[0];
    assert_eq!(active.opacity, 0.6);
    assert_eq!(active.blend, BlendMode::Multiply);
    assert!(active.alpha_locked);
    assert_eq!(active.first_mask(), Some(&mask));
    assert!(Arc::ptr_eq(
        &other,
        &engine.document.layers[1].raster().unwrap().tiles()[&(0, 0)]
    ));
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state()["canUndo"], false);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
}

#[test]
fn multiple_stops_keep_knots_and_clamp_uncovered_luminance_endpoints() {
    let mut engine = Engine::new(256, 1).unwrap();
    for tx in 0..2 {
        let mut tile = vec![0; TILE_BYTES];
        for x in 0..128 {
            let value = (tx * 128 + x) as u8;
            tile[x as usize * 4..x as usize * 4 + 4].copy_from_slice(&[value, value, value, 255]);
        }
        engine.document.layers[0]
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert((tx, 0), Arc::new(tile));
    }
    let settings = GradientMap {
        stops: [
            (64.0 / 255.0, [10, 20, 30]),
            (128.0 / 255.0, [80, 160, 240]),
            (192.0 / 255.0, [200, 40, 80]),
        ]
        .into_iter()
        .map(|(position, color)| GradientMapStop { position, color })
        .collect(),
    };
    apply(&mut engine, settings).unwrap();
    for x in [0, 32, 64] {
        assert_eq!(pixel(&engine, x, 0), [10, 20, 30, 255]);
    }
    assert_eq!(pixel(&engine, 96, 0), [45, 90, 135, 255]);
    assert_eq!(pixel(&engine, 128, 0), [80, 160, 240, 255]);
    assert_eq!(pixel(&engine, 160, 0), [140, 100, 160, 255]);
    for x in [192, 224, 255] {
        assert_eq!(pixel(&engine, x, 0), [200, 40, 80, 255]);
    }
}

#[test]
fn all_sixteen_stops_are_accepted_by_the_native_json_command() {
    let mut engine = Engine::new(256, 1).unwrap();
    for tx in 0..2 {
        let mut tile = vec![0; TILE_BYTES];
        for x in 0..128 {
            let value = (tx * 128 + x) as u8;
            tile[x as usize * 4..x as usize * 4 + 4].copy_from_slice(&[value, value, value, 255]);
        }
        engine.document.layers[0]
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert((tx, 0), Arc::new(tile));
    }
    let points: Vec<_> = (0u8..16)
        .map(|index| {
            json!({"position":f64::from(index)/15.0,"color":[index*16,255-index*12,index*8]})
        })
        .collect();
    command(
        &mut engine,
        json!({"type":"gradient_map","id":1,"revision":0,"selection_id":0,"settings":{"stops":points}}),
    )
    .unwrap();
    for index in 0u8..16 {
        assert_eq!(
            pixel(&engine, u32::from(index) * 17, 0),
            [index * 16, 255 - index * 12, index * 8, 255]
        );
    }
}

#[test]
fn antialiased_selection_edges_mix_only_rgb_without_changing_selection() {
    let mut engine = fixture();
    command(
        &mut engine,
        json!({"type":"select_shape","selection":{"kind":"ellipse","left":110,"top":3,"right":180,"bottom":29}}),
    )
    .unwrap();
    engine.document.layers[0].alpha_locked = true;
    let selection = engine.state()["selection"].clone();
    let mask = engine.selection_frame();
    let settings = stops(&[[0, 255, 0], [0, 255, 0]]);
    let preview = engine
        .preview_adjustment(request(&engine, settings.clone()))
        .unwrap();
    apply(&mut engine, settings).unwrap();
    assert_eq!(engine.frame_with_background(true), preview);
    assert_eq!(engine.state()["selection"], selection);
    assert_eq!(engine.selection_frame(), mask);
    let mut coverage = vec![0; 256 * 32];
    for tile in mask[8..].as_chunks::<{ 8 + TILE_BYTES }>().0 {
        let tx = u32::from_le_bytes(tile[..4].try_into().unwrap());
        let ty = u32::from_le_bytes(tile[4..8].try_into().unwrap());
        for y in 0..TILE_SIZE.min(32 - ty * TILE_SIZE) {
            for x in 0..TILE_SIZE {
                let offset = (y * TILE_SIZE + x) as usize * 4;
                coverage[(ty * TILE_SIZE + y) as usize * 256 + (tx * TILE_SIZE + x) as usize] =
                    tile[8 + offset + 3];
            }
        }
    }
    assert!(
        coverage
            .iter()
            .filter(|&&value| value > 0 && value < 255)
            .count()
            > 20
    );
    for y in 0..32 {
        for x in 0..256 {
            let amount = u32::from(coverage[y as usize * 256 + x as usize]);
            let expected: [u8; 3] = std::array::from_fn(|channel| {
                (([80u32, 20, 40][channel] * (255 - amount)
                    + [0u32, 128, 0][channel] * amount
                    + 127)
                    / 255) as u8
            });
            assert_eq!(
                pixel(&engine, x, y),
                [expected[0], expected[1], expected[2], 128]
            );
        }
    }
}

#[test]
fn invalid_stops_are_atomic_even_with_empty_selection() {
    let mut invalid = vec![
        Vec::new(),
        vec![GradientMapStop {
            position: 0.0,
            color: [0; 3],
        }],
        stops(&[[0; 3], [255; 3]]).stops,
        stops(&[[0; 3], [255; 3]]).stops,
        stops(&[[0; 3], [255; 3]]).stops,
        stops(&[[0; 3], [255; 3]]).stops,
        stops(&[[0; 3], [255; 3]]).stops,
        stops(&[[0; 3], [255; 3]]).stops,
        stops(&[[0; 3]; MAX_GRADIENT_MAP_STOPS + 1]).stops,
    ];
    for (stop, position) in
        invalid[2..8]
            .iter_mut()
            .zip([-0.01, 1.01, 1.0, f64::NAN, f64::INFINITY, f64::NEG_INFINITY])
    {
        stop[0].position = position;
    }
    let mut reversed = GradientMap::default().stops;
    reversed[0].position = 0.75;
    reversed[1].position = 0.25;
    invalid.push(reversed);
    for points in invalid {
        for empty in [false, true] {
            let mut engine = fixture();
            if empty {
                command(
                    &mut engine,
                    json!({"type":"select","rect":{"left":0,"top":0,"right":1,"bottom":1}}),
                )
                .unwrap();
                command(&mut engine, json!({"type":"combine_selection","mode":"subtract","selection":{"left":0,"top":0,"right":1,"bottom":1}})).unwrap();
            }
            let before = engine.save().unwrap();
            let state = engine.state();
            let source = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
            let settings = GradientMap {
                stops: points.clone(),
            };
            assert!(engine
                .preview_adjustment(request(&engine, settings.clone()))
                .is_err());
            assert!(apply(&mut engine, settings).is_err());
            assert_eq!(engine.save().unwrap(), before);
            assert_eq!(engine.state(), state);
            assert!(Arc::ptr_eq(
                &source,
                &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
            ));
        }
    }
}

#[test]
fn unavailable_layers_stale_selection_and_strokes_reject_preview_and_commit() {
    for fault in [
        "locked",
        "hidden",
        "active",
        "revision",
        "selection",
        "mask",
        "stroke",
    ] {
        let mut engine = fixture();
        let settings = stops(&[[255, 0, 0], [0, 0, 255]]);
        let mut value = request(&engine, settings.clone());
        match fault {
            "locked" => engine.document.layers[0].locked = true,
            "hidden" => engine.document.layers[0].visible = false,
            "active" => value.id = 2,
            "revision" => value.revision += 1,
            "selection" => {
                command(
                    &mut engine,
                    json!({"type":"select","rect":{"left":0,"top":0,"right":1,"bottom":1}}),
                )
                .unwrap();
            }
            "mask" => {
                command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
                command(
                    &mut engine,
                    json!({"type":"set_mask_editing","enabled":true}),
                )
                .unwrap();
                value = request(&engine, settings.clone());
            }
            _ => {
                engine
                    .command(Command::Begin {
                        brush: Brush::default(),
                        assistant: None,
                    })
                    .unwrap();
            }
        }
        let before = engine.save().unwrap();
        let state = engine.state();
        assert!(engine
            .preview_adjustment(AdjustmentRequest {
                frame_id: None,
                cel_id: None,
                target_layer_id: None,
                id: value.id,
                revision: value.revision,
                selection_id: value.selection_id,
                settings: value.settings.clone(),
            })
            .is_err());
        assert!(engine
            .command(Command::ApplyAdjustment { request: value })
            .is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
}

#[test]
fn unchanged_pixels_and_empty_selections_preserve_storage_and_history() {
    for empty in [false, true] {
        let mut engine = fixture();
        let gray = Arc::new([64, 64, 64, 128].repeat(TILE_BYTES / 4));
        engine.document.layers[0]
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert((0, 0), gray.clone());
        engine.document.layers[0]
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert((1, 0), gray.clone());
        if empty {
            command(
                &mut engine,
                json!({"type":"select","rect":{"left":0,"top":0,"right":1,"bottom":1}}),
            )
            .unwrap();
            command(&mut engine, json!({"type":"combine_selection","mode":"subtract","selection":{"left":0,"top":0,"right":1,"bottom":1}})).unwrap();
        }
        let settings = if empty {
            stops(&[[255, 0, 0], [0, 0, 255]])
        } else {
            GradientMap::default()
        };
        let state = engine.state();
        assert_eq!(
            engine
                .preview_adjustment(request(&engine, settings.clone()))
                .unwrap()
                .len(),
            16
        );
        apply(&mut engine, settings).unwrap();
        assert_eq!(engine.state(), state);
        assert!(engine.document.layers[0]
            .raster()
            .unwrap()
            .tiles()
            .values()
            .all(|tile| Arc::ptr_eq(tile, &gray)));
    }
    let mut engine = Engine::new(4, 4).unwrap();
    let settings = stops(&[[255, 0, 0], [0, 0, 255]]);
    let state = engine.state();
    apply(&mut engine, settings).unwrap();
    assert_eq!(engine.state(), state);
}

#[test]
fn history_preflight_rejects_large_replacements_but_counts_live_shared_tiles() {
    let mut engine = Engine::new(4097, 4094).unwrap();
    for ty in 0..32 {
        for tx in 0..33 {
            let mut tile = vec![0; TILE_BYTES];
            tile[..4].copy_from_slice(&[255, 0, 0, 255]);
            engine.document.layers[0]
                .raster_mut()
                .unwrap()
                .tiles_mut()
                .insert((tx, ty), Arc::new(tile));
        }
    }
    engine.document.validate().unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    let source = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    let settings = GradientMap::default();
    assert!(engine
        .preview_adjustment(request(&engine, settings.clone()))
        .is_err());
    assert!(apply(&mut engine, settings.clone()).is_err());
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    assert!(Arc::ptr_eq(
        &source,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
    let mut other = Layer::new(2, "Shared".into());
    for (&key, tile) in engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .iter()
        .take(33)
    {
        other
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert(key, tile.clone());
    }
    engine.document.layers.push(other);
    engine.document.next_id = 3;
    engine.document.validate().unwrap();
    apply(&mut engine, settings).unwrap();
    assert_eq!(pixel(&engine, 0, 0), [54, 54, 54, 255]);
    assert!(Arc::ptr_eq(
        &source,
        &engine.document.layers[1].raster().unwrap().tiles()[&(0, 0)]
    ));
    assert_eq!(engine.state()["canUndo"], true);
}
