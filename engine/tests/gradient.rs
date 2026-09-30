use podor_engine::{
    model::*, Command, Engine, Gradient, GradientShape, SelectionKind, SelectionSpec,
};
use std::sync::Arc;

fn options() -> Gradient {
    Gradient {
        start: [0.5, 0.5],
        end: [256.5, 0.5],
        from: [20, 40, 80, 255],
        to: [220, 140, 0, 255],
        opacity: 1.0,
        shape: GradientShape::Linear,
    }
}
fn apply(engine: &mut Engine, settings: Gradient) -> Result<(), String> {
    engine
        .command(Command::Gradient {
            id: engine.document.active,
            revision: engine.state()["revision"].as_u64().unwrap(),
            settings,
        })
        .map(|_| ())
}
fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |p| {
            let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            p[i..i + 4].try_into().unwrap()
        })
}

#[test]
fn linear_gradient_crosses_tiles_and_restores_with_one_undo() {
    let mut e = Engine::new(270, 140).unwrap();
    let original = e.save().unwrap();
    apply(&mut e, options()).unwrap();
    for y in 0..140 {
        for x in 0..270 {
            let t = (f64::from(x) / 256.0).min(1.0);
            assert_eq!(
                pixel(&e, x, y),
                [
                    (20.0 + 200.0 * t).round() as u8,
                    (40.0 + 100.0 * t).round() as u8,
                    (80.0 * (1.0 - t)).round() as u8,
                    255
                ]
            );
        }
    }
    let changed = e.save().unwrap();
    e.command(Command::Undo).unwrap();
    assert_eq!(e.save().unwrap(), original);
    e.command(Command::Redo).unwrap();
    assert_eq!(e.save().unwrap(), changed);
    e.document.validate().unwrap();
}

#[test]
fn radial_transparency_composites_over_existing_premultiplied_pixels() {
    let mut e = Engine::new(260, 260).unwrap();
    e.command(Command::Fill {
        contiguous: true,
        merged: false,
        x: 0,
        y: 0,
        color: [40, 80, 120, 128],
        tolerance: 0,
    })
    .unwrap();
    let base = pixel(&e, 0, 0);
    apply(
        &mut e,
        Gradient {
            start: [128.5, 128.5],
            end: [228.5, 128.5],
            from: [200, 60, 30, 255],
            to: [200, 60, 30, 0],
            opacity: 0.6,
            shape: GradientShape::Radial,
        },
    )
    .unwrap();
    for y in 0..260 {
        for x in 0..260 {
            let a = (1.0
                - (f64::from(x) - 128.0)
                    .hypot(f64::from(y) - 128.0)
                    .min(100.0)
                    / 100.0)
                * 0.6;
            let actual = pixel(&e, x, y);
            for c in 0..4 {
                let source = [200., 60., 30., 255.][c];
                let expected = (source * a + f64::from(base[c]) * (1.0 - a)).round() as u8;
                assert!(actual[c].abs_diff(expected) <= 1);
            }
        }
    }
}

#[test]
fn ellipse_mask_packet_matches_applied_coverage_and_alpha_lock() {
    let mut e = Engine::new(260, 260).unwrap();
    e.command(Command::SelectShape {
        selection: SelectionSpec {
            bounds: Rect {
                left: 7,
                top: 9,
                right: 251,
                bottom: 253,
            },
            kind: SelectionKind::Ellipse,
            points: vec![],
        },
    })
    .unwrap();
    let mask = e.selection_frame();
    let mut coverage = vec![0; 260 * 260];
    for chunk in mask[8..].as_chunks::<{ 8 + TILE_BYTES }>().0 {
        let tx = u32::from_le_bytes(chunk[0..4].try_into().unwrap());
        let ty = u32::from_le_bytes(chunk[4..8].try_into().unwrap());
        for y in 0..TILE_SIZE {
            for x in 0..TILE_SIZE {
                if tx * TILE_SIZE + x < 260 && ty * TILE_SIZE + y < 260 {
                    let i = ((y * TILE_SIZE + x) * 4) as usize + 8;
                    assert_eq!(&chunk[i..i + 4], &[chunk[i]; 4]);
                    coverage[((ty * TILE_SIZE + y) * 260 + tx * TILE_SIZE + x) as usize] = chunk[i];
                }
            }
        }
    }
    apply(
        &mut e,
        Gradient {
            from: [255; 4],
            to: [255; 4],
            ..options()
        },
    )
    .unwrap();
    let mut soft = 0;
    for y in 0..260 {
        for x in 0..260 {
            let a = coverage[(y * 260 + x) as usize];
            if a > 0 && a < 255 {
                soft += 1;
            }
            assert_eq!(pixel(&e, x, y), [a; 4]);
        }
    }
    assert!(soft > 100);
    e.command(Command::Select { rect: None }).unwrap();
    e.command(Command::SetProtection {
        id: 1,
        alpha_locked: Some(true),
        locked: None,
    })
    .unwrap();
    apply(
        &mut e,
        Gradient {
            from: [200, 0, 0, 255],
            to: [200, 0, 0, 255],
            ..options()
        },
    )
    .unwrap();
    for y in 0..260 {
        for x in 0..260 {
            let a = coverage[(y * 260 + x) as usize];
            assert_eq!(
                pixel(&e, x, y),
                [(f64::from(a) * 200.0 / 255.0).round() as u8, 0, 0, a]
            );
        }
    }
}

#[test]
fn no_op_and_failed_gradients_preserve_history_and_tile_storage() {
    let mut e = Engine::new(256, 128).unwrap();
    apply(&mut e, options()).unwrap();
    let original = e.save().unwrap();
    let state = e.state();
    let tile = e.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    apply(
        &mut e,
        Gradient {
            opacity: 0.0,
            ..options()
        },
    )
    .unwrap();
    assert_eq!(e.state(), state);
    assert!(Arc::ptr_eq(
        &tile,
        &e.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
    for invalid in [
        Gradient {
            end: [0.5, 0.5],
            ..options()
        },
        Gradient {
            opacity: f64::NAN,
            ..options()
        },
        Gradient {
            start: [f64::INFINITY, 0.],
            ..options()
        },
        Gradient {
            start: [-1e8, 0.],
            ..options()
        },
    ] {
        assert!(apply(&mut e, invalid).is_err());
        assert_eq!(e.save().unwrap(), original);
        assert_eq!(e.state(), state);
    }
    assert!(e
        .command(Command::Gradient {
            id: 1,
            revision: 0,
            settings: options()
        })
        .is_err());
    e.command(Command::SetProtection {
        id: 1,
        alpha_locked: None,
        locked: Some(true),
    })
    .unwrap();
    assert!(apply(&mut e, options()).is_err());
}

#[test]
fn document_and_undo_budgets_fail_atomically() {
    let mut e = Engine::new(4096, 4096).unwrap();
    let pixels = Arc::new(vec![255; TILE_BYTES]);
    for id in [2, 3] {
        let mut layer = Layer::new(id, format!("{id}"));
        for y in 0..32 {
            for x in 0..32 {
                layer
                    .raster_mut()
                    .unwrap()
                    .tiles_mut()
                    .insert((x, y), pixels.clone());
            }
        }
        e.document.layers.push(layer);
    }
    e.document.next_id = 4;
    assert!(apply(&mut e, options()).is_err());
    assert!(e.document.layers[0].raster().unwrap().tiles().is_empty());
    assert!(!e.state()["canUndo"].as_bool().unwrap());
    let mut e = Engine::new(4097, 4094).unwrap();
    for y in 0..32 {
        for x in 0..33 {
            let mut tile = vec![0; TILE_BYTES];
            for py in 0..TILE_SIZE {
                for px in 0..TILE_SIZE {
                    if x * TILE_SIZE + px < 4097 && y * TILE_SIZE + py < 4094 {
                        let i = ((py * TILE_SIZE + px) * 4) as usize;
                        tile[i..i + 4].copy_from_slice(&[0, 0, 200, 255]);
                    }
                }
            }
            e.document
                .active_mut()
                .raster_mut()
                .unwrap()
                .tiles_mut()
                .insert((x, y), Arc::new(tile));
        }
    }
    let before = e.document.layers[0].raster().unwrap().tiles().clone();
    assert!(apply(&mut e, options()).is_err());
    for (key, tile) in before {
        assert!(Arc::ptr_eq(
            &tile,
            &e.document.layers[0].raster().unwrap().tiles()[&key]
        ));
    }
    assert!(!e.state()["canUndo"].as_bool().unwrap());
}
