use podor_engine::{model::*, Command, Engine};
use std::sync::Arc;

fn pixel(layer: &Layer, x: u32, y: u32) -> [u8; 4] {
    let Some(tile) = layer
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
    else {
        return [0; 4];
    };
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tile[offset..offset + 4].try_into().unwrap()
}

fn resize(
    engine: &mut Engine,
    width: u32,
    height: u32,
    anchor: u8,
) -> Result<serde_json::Value, String> {
    engine.command(Command::ResizeCanvas {
        width,
        height,
        anchor,
        revision: engine.state()["revision"].as_u64().unwrap(),
    })
}

fn patterned() -> Engine {
    let mut engine = Engine::new(259, 193).unwrap();
    for y in 0..193 {
        for x in 0..259 {
            let tile = engine.document.layers[0]
                .raster_mut()
                .unwrap()
                .tiles_mut()
                .entry((x / TILE_SIZE, y / TILE_SIZE))
                .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
            let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            let alpha = ((x + y) % 192 + 32) as u8;
            Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&[
                alpha / 2,
                alpha / 3,
                alpha,
                alpha,
            ]);
        }
    }
    engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
    engine
        .command(Command::SetLayer {
            id: 2,
            name: "隐藏 · 底稿".into(),
            visible: false,
            opacity: 0.4,
        })
        .unwrap();
    engine
        .command(Command::SetBlend {
            id: 2,
            mode: BlendMode::Multiply,
        })
        .unwrap();
    engine
        .command(Command::SetProtection {
            id: 2,
            alpha_locked: Some(true),
            locked: Some(true),
        })
        .unwrap();
    engine
}

#[test]
fn all_nine_anchors_preserve_pixels_layer_properties_and_history() {
    let original = patterned();
    let saved = original.save().unwrap();
    for (width, height) in [(389, 320), (127, 129), (129, 260)] {
        for anchor in 0..9 {
            let mut engine = Engine::new(1, 1).unwrap();
            engine.load(&saved).unwrap();
            engine.frame();
            resize(&mut engine, width, height, anchor).unwrap();
            engine.document.validate().unwrap();
            let dx = (width as i32 - 259) * i32::from(anchor % 3) / 2;
            let dy = (height as i32 - 193) * i32::from(anchor / 3) / 2;
            for y in 0..height {
                for x in 0..width {
                    let (sx, sy) = (x as i32 - dx, y as i32 - dy);
                    let expected = if (0..259).contains(&sx) && (0..193).contains(&sy) {
                        pixel(&original.document.layers[0], sx as u32, sy as u32)
                    } else {
                        [0; 4]
                    };
                    for layer in &engine.document.layers {
                        assert_eq!(
                            pixel(layer, x, y),
                            expected,
                            "anchor {anchor}, {width}x{height}, {x},{y}"
                        );
                    }
                }
            }
            assert_eq!(engine.state()["layers"], original.state()["layers"]);
            assert_eq!(engine.document.active, original.document.active);
            let resized = engine.save().unwrap();
            let frame = engine.frame();
            for tile in frame[16..].as_chunks::<{ 8 + TILE_BYTES }>().0 {
                assert!(
                    u32::from_le_bytes(tile[..4].try_into().unwrap()) < width.div_ceil(TILE_SIZE)
                );
                assert!(
                    u32::from_le_bytes(tile[4..8].try_into().unwrap()) < height.div_ceil(TILE_SIZE)
                );
            }
            engine.command(Command::Undo).unwrap();
            assert_eq!(engine.save().unwrap(), saved);
            engine.command(Command::Redo).unwrap();
            assert_eq!(engine.save().unwrap(), resized);
            let mut reopened = Engine::new(1, 1).unwrap();
            reopened.load(&resized).unwrap();
            assert_eq!(reopened.save().unwrap(), resized);
        }
    }
}

#[test]
fn expanding_aligned_tiles_reuses_pixels_and_cropped_edges_do_not_return() {
    let mut engine = Engine::new(256, 128).unwrap();
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color: [255, 0, 0, 128],
            tolerance: 0,
        })
        .unwrap();
    let tile = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    resize(&mut engine, 512, 384, 4).unwrap();
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(1, 1)]
    ));
    assert_eq!(engine.document.tile_count(), 2);
    engine.command(Command::Undo).unwrap();
    resize(&mut engine, 129, 1, 0).unwrap();
    resize(&mut engine, 256, 128, 0).unwrap();
    assert_eq!(pixel(&engine.document.layers[0], 128, 0), [128, 0, 0, 128]);
    assert_eq!(pixel(&engine.document.layers[0], 129, 0), [0; 4]);
    assert_eq!(pixel(&engine.document.layers[0], 0, 1), [0; 4]);
}

#[test]
fn invalid_stale_or_noop_requests_preserve_document_history_and_selection() {
    let mut engine = patterned();
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 0,
                top: 0,
                right: 100,
                bottom: 100,
            }),
        })
        .unwrap();
    let saved = engine.save().unwrap();
    let state = engine.state();
    for (width, height, anchor) in [(0, 100, 4), (9000, 100, 4), (8192, 8192, 4), (100, 100, 9)] {
        assert!(resize(&mut engine, width, height, anchor).is_err());
        assert_eq!(engine.save().unwrap(), saved);
        assert_eq!(engine.state(), state);
    }
    assert!(engine
        .command(Command::ResizeCanvas {
            width: 100,
            height: 100,
            anchor: 4,
            revision: 0
        })
        .is_err());
    resize(&mut engine, 259, 193, 4).unwrap();
    assert_eq!(engine.state(), state);
    resize(&mut engine, 64, 64, 4).unwrap();
    assert!(engine.state()["selection"].is_null());
    engine.command(Command::Undo).unwrap();
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 128,
                top: 128,
                right: 129,
                bottom: 129,
            }),
        })
        .unwrap();
    engine.command(Command::Redo).unwrap();
    assert!(engine.state()["selection"].is_null());
}

#[test]
fn resizing_rejects_history_overflow_without_losing_the_artwork() {
    let mut engine = Engine::new(4096, 4096).unwrap();
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color: [20, 40, 60, 255],
            tolerance: 0,
        })
        .unwrap();
    engine.command(Command::AddLayer).unwrap();
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color: [70, 80, 90, 255],
            tolerance: 0,
        })
        .unwrap();
    let state = engine.state();
    let tile = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    assert_eq!(
        resize(&mut engine, 1, 1, 4).unwrap_err(),
        "调整画布会超出撤销内存限制"
    );
    assert_eq!(engine.state(), state);
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
}
