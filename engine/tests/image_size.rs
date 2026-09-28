use image::{
    imageops::{resize as reference_resize, FilterType},
    Rgba, RgbaImage,
};
use podor_engine::{model::*, Command, Engine, ResampleFilter};
use std::sync::Arc;

fn scale(
    engine: &mut Engine,
    width: u32,
    height: u32,
    filter: ResampleFilter,
) -> Result<serde_json::Value, String> {
    engine.command(Command::ResizeImage {
        width,
        height,
        filter,
        revision: engine.state()["revision"].as_u64().unwrap(),
    })
}

fn pixel(layer: &Layer, x: u32, y: u32) -> [u8; 4] {
    let Some(tile) = layer.tiles.get(&(x / TILE_SIZE, y / TILE_SIZE)) else {
        return [0; 4];
    };
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tile[offset..offset + 4].try_into().unwrap()
}

fn from_image(image: &RgbaImage) -> Engine {
    let mut engine = Engine::new(image.width(), image.height()).unwrap();
    for (x, y, &Rgba(color)) in image.enumerate_pixels() {
        if color[3] == 0 {
            continue;
        }
        let tile = engine.document.layers[0]
            .tiles
            .entry((x / TILE_SIZE, y / TILE_SIZE))
            .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
        let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
        Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&color);
    }
    engine
}

#[test]
fn tiled_resampling_matches_an_independent_resizer_in_both_axis_orders() {
    let source = RgbaImage::from_fn(129, 131, |x, y| {
        let alpha = if (x / 13 + y / 17) % 3 == 0 {
            0
        } else {
            ((x * 17 + y * 7) % 224 + 32) as u8
        };
        Rgba([alpha / 2, alpha / 3, alpha, alpha])
    });
    for (filter, reference_filter) in [
        (ResampleFilter::Nearest, FilterType::Nearest),
        (ResampleFilter::Lanczos3, FilterType::Lanczos3),
    ] {
        for (width, height) in [(259, 93), (61, 271), (17, 5), (1, 1), (129, 79), (191, 131)] {
            let expected = reference_resize(&source, width, height, reference_filter);
            let mut engine = from_image(&source);
            scale(&mut engine, width, height, filter).unwrap();
            engine.document.validate().unwrap();
            for (x, y, &Rgba(mut color)) in expected.enumerate_pixels() {
                for channel in 0..3 {
                    color[channel] = color[channel].min(color[3]);
                }
                let actual = pixel(&engine.document.layers[0], x, y);
                for (a, b) in actual.into_iter().zip(color) {
                    assert!(
                        a.abs_diff(b) <= u8::from(filter == ResampleFilter::Lanczos3),
                        "{filter:?}, {width}x{height}, {x},{y}: {actual:?} != {color:?}"
                    );
                }
            }
        }
    }
}

#[test]
fn pixel_art_scaling_preserves_layers_properties_history_and_saved_pixels() {
    let source = RgbaImage::from_fn(4, 3, |x, y| Rgba([x as u8 * 50, y as u8 * 80, 40, 255]));
    let mut engine = from_image(&source);
    let mut hidden = engine.document.layers[0].clone();
    hidden.id = 2;
    hidden.name = "隐藏底稿".into();
    hidden.visible = false;
    hidden.opacity = 0.4;
    hidden.blend = BlendMode::Multiply;
    hidden.locked = true;
    hidden.alpha_locked = true;
    engine.document.layers.push(hidden);
    engine.document.layers.push(Layer::new(3, "空白".into()));
    engine.document.active = 2;
    engine.document.next_id = 4;
    let before = engine.save().unwrap();
    let layers = engine.state()["layers"].clone();
    scale(&mut engine, 12, 9, ResampleFilter::Nearest).unwrap();
    for y in 0..9 {
        for x in 0..12 {
            let expected = source.get_pixel(x / 3, y / 3).0;
            assert_eq!(pixel(&engine.document.layers[0], x, y), expected);
            assert_eq!(pixel(&engine.document.layers[1], x, y), expected);
        }
    }
    assert!(engine.document.layers[2].tiles.is_empty());
    assert_eq!(engine.state()["layers"], layers);
    assert_eq!(engine.document.active, 2);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state()["canUndo"], false);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
    let mut restored = Engine::new(1, 1).unwrap();
    restored.load(&after).unwrap();
    assert_eq!(restored.save().unwrap(), after);
}

#[test]
fn smooth_edges_do_not_introduce_dark_fringe_and_minification_avoids_aliasing() {
    let source = RgbaImage::from_fn(17, 13, |x, y| {
        let alpha = if (4..13).contains(&x) && (3..10).contains(&y) {
            160
        } else {
            0
        };
        Rgba([alpha, 0, 0, alpha])
    });
    let mut engine = from_image(&source);
    scale(&mut engine, 79, 61, ResampleFilter::Lanczos3).unwrap();
    let mut feather = 0;
    for y in 0..61 {
        for x in 0..79 {
            let value = pixel(&engine.document.layers[0], x, y);
            assert_eq!(value[0], value[3]);
            assert_eq!(&value[1..3], &[0, 0]);
            if value[3] > 0 && value[3] < 160 {
                feather += 1;
            }
        }
    }
    assert!(feather > 100);
    let checker = RgbaImage::from_fn(64, 64, |x, y| {
        let v = if (x + y).is_multiple_of(2) { 0 } else { 255 };
        Rgba([v, v, v, 255])
    });
    let mut engine = from_image(&checker);
    scale(&mut engine, 1, 1, ResampleFilter::Lanczos3).unwrap();
    let average = pixel(&engine.document.layers[0], 0, 0);
    assert!((127..=128).contains(&average[0]));
    assert_eq!(average[3], 255);
}

#[test]
fn single_pixel_axes_sparse_layers_and_transparent_images_remain_valid() {
    for filter in [ResampleFilter::Nearest, ResampleFilter::Lanczos3] {
        for (width, height) in [(1, 8192), (8192, 1)] {
            let source = RgbaImage::from_pixel(width, height, Rgba([16, 32, 64, 128]));
            let mut engine = from_image(&source);
            scale(&mut engine, height, width, filter).unwrap();
            engine.document.validate().unwrap();
            for y in 0..width {
                for x in 0..height {
                    assert_eq!(pixel(&engine.document.layers[0], x, y), [16, 32, 64, 128]);
                }
            }
        }
        let source = RgbaImage::from_fn(513, 257, |x, y| {
            if x == 300 && y == 200 {
                Rgba([255, 0, 0, 255])
            } else {
                Rgba([0; 4])
            }
        });
        let mut engine = from_image(&source);
        scale(&mut engine, 1026, 514, filter).unwrap();
        assert!(pixel(&engine.document.layers[0], 600, 400)[3] > 0);
        assert_eq!(pixel(&engine.document.layers[0], 0, 0), [0; 4]);
        let mut empty = Engine::new(64, 64).unwrap();
        scale(&mut empty, 8192, 2048, filter).unwrap();
        assert_eq!(empty.document.tile_count(), 0);
        assert_eq!((empty.document.width, empty.document.height), (8192, 2048));
    }
}

#[test]
fn invalid_stale_and_noop_requests_leave_work_unchanged_and_resize_clears_selection() {
    let mut engine = from_image(&RgbaImage::from_pixel(129, 130, Rgba([1, 2, 3, 255])));
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 128,
                top: 128,
                right: 129,
                bottom: 130,
            }),
        })
        .unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    for (width, height) in [(0, 100), (9000, 100), (8192, 8192)] {
        assert!(scale(&mut engine, width, height, ResampleFilter::Lanczos3).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
    assert!(engine
        .command(Command::ResizeImage {
            width: 64,
            height: 64,
            filter: ResampleFilter::Nearest,
            revision: 99
        })
        .is_err());
    assert_eq!(engine.state(), state);
    scale(&mut engine, 129, 130, ResampleFilter::Nearest).unwrap();
    assert_eq!(engine.state(), state);
    scale(&mut engine, 64, 32, ResampleFilter::Lanczos3).unwrap();
    assert!(engine.state()["selection"].is_null());
    let frame = engine.frame();
    assert_eq!(u32::from_le_bytes(frame[12..16].try_into().unwrap()), 1);
    assert_eq!(&frame[16..24], &[0; 8]);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert!(engine.state()["selection"].is_null());
}

#[test]
fn exceeding_document_or_undo_memory_does_not_mutate_the_source() {
    let source = RgbaImage::from_pixel(128, 128, Rgba([255; 4]));
    let mut engine = from_image(&source);
    for id in 2..=3 {
        let mut layer = engine.document.layers[0].clone();
        layer.id = id;
        engine.document.layers.push(layer);
    }
    engine.document.next_id = 4;
    let before = engine.save().unwrap();
    let state = engine.state();
    assert_eq!(
        scale(&mut engine, 4096, 3072, ResampleFilter::Nearest).unwrap_err(),
        "工程像素超过内存限制"
    );
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    let mut engine = Engine::new(4096, 4096).unwrap();
    for id in 1..=2 {
        if id > 1 {
            engine.command(Command::AddLayer).unwrap();
        }
        engine
            .command(Command::Fill {
                x: 0,
                y: 0,
                color: [0, 0, 0, 255],
                tolerance: 0,
            })
            .unwrap();
    }
    let original = engine.document.layers[0].tiles[&(0, 0)].clone();
    let state = engine.state();
    assert_eq!(
        scale(&mut engine, 2, 2, ResampleFilter::Lanczos3).unwrap_err(),
        "缩放图像会超出撤销内存限制"
    );
    assert_eq!(engine.state(), state);
    assert!(Arc::ptr_eq(
        &original,
        &engine.document.layers[0].tiles[&(0, 0)]
    ));
}
