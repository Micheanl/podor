use podor_engine::{model::*, Command, Engine, LayerTransform, ResampleFilter};
use std::sync::Arc;

fn set_pixel(engine: &mut Engine, x: u32, y: u32, color: [u8; 4]) {
    let tile = engine
        .document
        .active_mut()
        .tiles
        .entry((x / TILE_SIZE, y / TILE_SIZE))
        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
    let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    Arc::make_mut(tile)[i..i + 4].copy_from_slice(&color);
}

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == engine.document.active)
        .unwrap()
        .tiles
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| {
            let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[i..i + 4].try_into().unwrap()
        })
}

fn options(width: u32, height: u32) -> LayerTransform {
    LayerTransform {
        width,
        height,
        dx: 0.0,
        dy: 0.0,
        angle: 0.0,
        flip_x: false,
        flip_y: false,
        filter: ResampleFilter::Lanczos3,
    }
}

fn apply(engine: &mut Engine, transform: LayerTransform) -> Result<(), String> {
    engine
        .command(Command::TransformLayer {
            id: engine.document.active,
            revision: engine.state()["revision"].as_u64().unwrap(),
            transform,
        })
        .map(|_| ())
}

#[test]
fn bounds_use_opaque_pixels_and_identity_keeps_tile_sharing_and_history() {
    let mut engine = Engine::new(280, 270).unwrap();
    assert!(engine.layer_bounds().is_err());
    set_pixel(&mut engine, 127, 129, [60, 20, 40, 128]);
    set_pixel(&mut engine, 256, 264, [10, 20, 30, 255]);
    assert_eq!(
        engine.layer_bounds().unwrap(),
        Rect {
            left: 127,
            top: 129,
            right: 257,
            bottom: 265
        }
    );
    let tile = engine.document.layers[0].tiles[&(0, 1)].clone();
    let state = engine.state();
    apply(&mut engine, options(130, 136)).unwrap();
    assert_eq!(engine.state(), state);
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].tiles[&(0, 1)]
    ));
}

#[test]
fn quarter_turns_and_flips_preserve_exact_colors_properties_and_undo() {
    for angle in [0.0, 90.0, 180.0, -90.0] {
        for (flip_x, flip_y) in [(false, false), (true, false), (false, true), (true, true)] {
            let mut engine = Engine::new(140, 140).unwrap();
            let colors = [
                [100, 0, 0, 255],
                [0, 100, 0, 255],
                [0, 0, 100, 255],
                [30, 20, 40, 128],
            ];
            for (i, color) in colors.into_iter().enumerate() {
                set_pixel(&mut engine, 127 + i as u32 % 2, 127 + i as u32 / 2, color);
            }
            engine.document.active_mut().name = "ink".into();
            engine.document.active_mut().blend = BlendMode::Multiply;
            engine.document.active_mut().opacity = 0.4;
            let before = engine.save().unwrap();
            let revision = engine.state()["revision"].as_u64().unwrap();
            apply(
                &mut engine,
                LayerTransform {
                    angle,
                    flip_x,
                    flip_y,
                    ..options(2, 2)
                },
            )
            .unwrap();
            for (i, color) in colors.into_iter().enumerate() {
                let mut x = i as i32 % 2;
                let mut y = i as i32 / 2;
                if flip_x {
                    x = 1 - x;
                }
                if flip_y {
                    y = 1 - y;
                }
                let (x, y) = match angle as i32 {
                    90 => (1 - y, x),
                    180 => (1 - x, 1 - y),
                    -90 => (y, 1 - x),
                    _ => (x, y),
                };
                assert_eq!(pixel(&engine, 127 + x as u32, 127 + y as u32), color);
            }
            assert_eq!(engine.document.layers[0].name, "ink");
            assert_eq!(engine.document.layers[0].blend, BlendMode::Multiply);
            assert_eq!(engine.document.layers[0].opacity, 0.4);
            let after = engine.save().unwrap();
            if engine.state()["revision"].as_u64().unwrap() != revision {
                engine.command(Command::Undo).unwrap();
                assert_eq!(before, engine.save().unwrap());
                engine.command(Command::Redo).unwrap();
                assert_eq!(after, engine.save().unwrap());
            }
        }
    }
}

#[test]
fn nearest_resize_matches_independent_pixel_mapping_and_crops_canvas_edges() {
    let mut engine = Engine::new(180, 150).unwrap();
    for y in 0..8 {
        for x in 0..10 {
            set_pixel(
                &mut engine,
                120 + x,
                123 + y,
                [x as u8 * 20, y as u8 * 20, 30, 255],
            );
        }
    }
    apply(
        &mut engine,
        LayerTransform {
            dx: 45.0,
            dy: 18.0,
            filter: ResampleFilter::Nearest,
            ..options(20, 16)
        },
    )
    .unwrap();
    for y in 0..150 {
        for x in 0..180 {
            let expected = if (160..180).contains(&x) && (137..150).contains(&y) {
                [
                    ((x - 160) / 2) as u8 * 20,
                    ((y - 137) / 2) as u8 * 20,
                    30,
                    255,
                ]
            } else {
                [0; 4]
            };
            assert_eq!(pixel(&engine, x, y), expected);
        }
    }
    engine.document.validate().unwrap();
}

#[test]
fn smooth_rotation_uses_premultiplied_coverage_across_tile_boundaries() {
    let mut engine = Engine::new(270, 270).unwrap();
    for y in 120..136 {
        for x in 120..136 {
            set_pixel(&mut engine, x, y, [120, 40, 80, 160]);
        }
    }
    apply(
        &mut engine,
        LayerTransform {
            angle: 31.0,
            dx: 0.25,
            dy: -0.5,
            ..options(16, 16)
        },
    )
    .unwrap();
    let (s, c) = 31f64.to_radians().sin_cos();
    let mut partial = 0;
    for y in 108..146 {
        for x in 108..146 {
            let dx = f64::from(x) + 0.5 - 128.25;
            let dy = f64::from(y) + 0.5 - 127.5;
            let sx = dx * c + dy * s + 7.5;
            let sy = -dx * s + dy * c + 7.5;
            let mut coverage = 0.0;
            for py in 0..16 {
                for px in 0..16 {
                    coverage += (1.0 - (sx - f64::from(px)).abs()).max(0.0)
                        * (1.0 - (sy - f64::from(py)).abs()).max(0.0);
                }
            }
            let expected: [u8; 4] =
                [120.0, 40.0, 80.0, 160.0].map(|v| (v * coverage).round() as u8);
            let actual = pixel(&engine, x, y);
            assert_eq!(actual, expected, "{x},{y}");
            if actual[3] > 0 && actual[3] < 160 {
                partial += 1;
            }
        }
    }
    assert!(partial > 10);
    engine.document.validate().unwrap();
}

#[test]
fn smooth_reduction_filters_alternating_lines_instead_of_aliasing() {
    let mut engine = Engine::new(256, 256).unwrap();
    for y in 32..224 {
        for x in 32..224 {
            set_pixel(
                &mut engine,
                x,
                y,
                if x % 2 == 0 { [128; 4] } else { [0; 4] },
            );
        }
    }
    apply(&mut engine, options(16, 16)).unwrap();
    for y in 122..134 {
        for x in 122..134 {
            let pixel = pixel(&engine, x, y);
            assert!((62..=66).contains(&pixel[3]));
            assert_eq!(pixel[0], pixel[3]);
        }
    }
}

#[test]
fn locked_stale_selected_and_invalid_transforms_are_atomic() {
    let mut engine = Engine::new(20, 20).unwrap();
    set_pixel(&mut engine, 5, 6, [60, 30, 40, 180]);
    for bad in [
        LayerTransform {
            angle: f64::NAN,
            ..options(1, 1)
        },
        options(0, 1),
        options(8192, 8192),
        LayerTransform {
            dx: 1e10,
            ..options(1, 1)
        },
    ] {
        let before = engine.save().unwrap();
        let state = engine.state();
        assert!(apply(&mut engine, bad).is_err());
        assert_eq!(before, engine.save().unwrap());
        assert_eq!(state, engine.state());
    }
    for protection in 0..3 {
        engine.document.active_mut().locked = protection == 0;
        engine.document.active_mut().alpha_locked = protection == 1;
        engine.document.active_mut().visible = protection != 2;
        let before = engine.save().unwrap();
        assert!(apply(&mut engine, options(2, 2)).is_err());
        assert_eq!(before, engine.save().unwrap());
    }
    engine.document.active_mut().visible = true;
    let before = engine.save().unwrap();
    assert!(engine
        .command(Command::TransformLayer {
            id: 1,
            revision: 3,
            transform: options(2, 2)
        })
        .is_err());
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 0,
                top: 0,
                right: 10,
                bottom: 10,
            }),
        })
        .unwrap();
    let state = engine.state();
    assert!(apply(&mut engine, options(2, 2)).is_err());
    assert_eq!(before, engine.save().unwrap());
    assert_eq!(state, engine.state());
}

#[test]
fn history_budget_counts_shared_source_tiles_and_failure_keeps_original_storage() {
    let mut engine = Engine::new(4097, 4094).unwrap();
    for ty in 0..engine.document.height.div_ceil(TILE_SIZE) {
        for tx in 0..engine.document.width.div_ceil(TILE_SIZE) {
            let mut pixels = vec![0; TILE_BYTES];
            for y in 0..TILE_SIZE.min(engine.document.height - ty * TILE_SIZE) {
                for x in 0..TILE_SIZE.min(engine.document.width - tx * TILE_SIZE) {
                    let i = ((y * TILE_SIZE + x) * 4) as usize;
                    pixels[i..i + 4].copy_from_slice(&[100, 80, 60, 255]);
                }
            }
            engine
                .document
                .active_mut()
                .tiles
                .insert((tx, ty), Arc::new(pixels));
        }
    }
    engine.document.validate().unwrap();
    let before = engine.document.clone();
    let state = engine.state();
    let transform = LayerTransform {
        filter: ResampleFilter::Nearest,
        ..options(2048, 2048)
    };
    assert!(apply(&mut engine, transform).is_err());
    assert_eq!(state, engine.state());
    for (key, pixels) in &before.layers[0].tiles {
        assert!(Arc::ptr_eq(pixels, &engine.document.layers[0].tiles[key]));
    }
    let mut shared = Layer::new(2, "shared".into());
    shared.tiles = before.layers[0]
        .tiles
        .iter()
        .take(64)
        .map(|(&key, tile)| (key, tile.clone()))
        .collect();
    engine.document.layers.push(shared);
    engine.document.next_id = 3;
    engine.document.validate().unwrap();
    apply(&mut engine, transform).unwrap();
    engine.document.validate().unwrap();
    assert!(engine.state()["canUndo"].as_bool().unwrap());
    engine.command(Command::Undo).unwrap();
    for (key, pixels) in &before.layers[0].tiles {
        assert!(Arc::ptr_eq(pixels, &engine.document.layers[0].tiles[key]));
    }
}
