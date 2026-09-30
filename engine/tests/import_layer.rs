use podor_engine::{model::*, Command, Engine};
use std::sync::Arc;

fn png(width: u32, height: u32, pixels: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut bytes, width, height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(pixels)
            .unwrap();
    }
    bytes
}

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

#[test]
fn import_centers_above_the_active_layer_and_undo_restores_the_saved_document() {
    let bytes = png(2, 1, &[200, 100, 50, 128, 255, 0, 0, 0]);
    let mut engine = Engine::new(260, 259).unwrap();
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
    engine
        .command(Command::SetProtection {
            id: 1,
            alpha_locked: None,
            locked: Some(true),
        })
        .unwrap();
    engine.command(Command::AddLayer).unwrap();
    engine.command(Command::SelectLayer { id: 1 }).unwrap();
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
    engine.frame();
    let before = engine.save().unwrap();
    let old_content = engine.state()["contentId"].clone();
    let old_tile = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    engine.import_layer(&bytes, "参考 · 红色").unwrap();
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| layer.id)
            .collect::<Vec<_>>(),
        [1, 3, 2]
    );
    assert_eq!(engine.document.active, 3);
    let imported = &engine.document.layers[1];
    assert_eq!(imported.name, "参考 · 红色");
    assert_eq!(pixel(imported, 129, 129), [100, 50, 25, 128]);
    assert_eq!(pixel(imported, 130, 129), [0; 4]);
    assert_eq!(imported.raster().unwrap().tiles().len(), 1);
    assert!(!imported.locked && !imported.alpha_locked && imported.visible);
    assert!(Arc::ptr_eq(
        &old_tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
    assert!(engine.state()["selection"].is_null());
    let frame = engine.frame();
    assert_eq!(u32::from_le_bytes(frame[12..16].try_into().unwrap()), 1);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state()["contentId"], old_content);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&after).unwrap();
    assert_eq!(reopened.save().unwrap(), after);
}

#[test]
fn area_resampling_preserves_transparent_edges_and_non_integer_coverage() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine
        .import_layer(&png(2, 1, &[255, 0, 0, 255, 0, 255, 0, 0]), "edge")
        .unwrap();
    assert_eq!(pixel(&engine.document.layers[1], 0, 0), [128, 0, 0, 128]);
    let mut engine = Engine::new(2, 3).unwrap();
    let bytes = png(
        5,
        1,
        &[
            0, 0, 0, 255, 50, 50, 50, 255, 100, 100, 100, 255, 150, 150, 150, 255, 200, 200, 200,
            255,
        ],
    );
    engine.import_layer(&bytes, "coverage").unwrap();
    assert_eq!(pixel(&engine.document.layers[1], 0, 1), [40, 40, 40, 255]);
    assert_eq!(
        pixel(&engine.document.layers[1], 1, 1),
        [160, 160, 160, 255]
    );
    assert_eq!(pixel(&engine.document.layers[1], 0, 0), [0; 4]);
    let mut portrait = Engine::new(3, 2).unwrap();
    let bytes = png(
        1,
        5,
        &[
            0, 0, 0, 255, 50, 50, 50, 255, 100, 100, 100, 255, 150, 150, 150, 255, 200, 200, 200,
            255,
        ],
    );
    portrait.import_layer(&bytes, "vertical").unwrap();
    assert_eq!(pixel(&portrait.document.layers[1], 1, 0), [40, 40, 40, 255]);
    assert_eq!(
        pixel(&portrait.document.layers[1], 1, 1),
        [160, 160, 160, 255]
    );
}

#[test]
fn jpeg_and_webp_use_the_existing_decoder_without_resizing_small_images() {
    for bytes in [
        include_bytes!("fixtures/progressive.jpg").as_slice(),
        include_bytes!("fixtures/lossy-alpha.webp").as_slice(),
    ] {
        let mut source = Engine::new(1, 1).unwrap();
        source.load(bytes).unwrap();
        let mut target =
            Engine::new(source.document.width + 256, source.document.height + 256).unwrap();
        target.import_layer(bytes, "photo").unwrap();
        for y in 0..source.document.height {
            for x in 0..source.document.width {
                assert_eq!(
                    pixel(&target.document.layers[1], x + 128, y + 128),
                    pixel(&source.document.layers[0], x, y)
                );
            }
        }
    }
}

#[test]
fn cancelled_invalid_and_excessive_imports_do_not_change_pixels_or_history() {
    let bytes = png(1, 1, &[20, 40, 60, 255]);
    let mut engine = Engine::new(8, 8).unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    for (input, name) in [
        (b"broken".as_slice(), "broken"),
        (&before, "project"),
        (&bytes, ""),
        (&bytes, "  "),
    ] {
        assert!(engine.import_layer(input, name).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
    assert!(engine.import_layer(&bytes, &"画".repeat(100)).is_err());
    assert!(engine
        .import_layer(include_bytes!("fixtures/animated.webp"), "animation")
        .is_err());
    engine
        .command(Command::Begin {
            brush: Brush::default(),
            assistant: None,
        })
        .unwrap();
    assert_eq!(
        engine.import_layer(&bytes, "stroke").unwrap_err(),
        "请先结束当前笔画"
    );
    engine.command(Command::Cancel).unwrap();
    for _ in 1..MAX_LAYERS {
        engine.command(Command::AddLayer).unwrap();
    }
    let state = engine.state();
    assert_eq!(
        engine.import_layer(&bytes, "full").unwrap_err(),
        "已达到图层上限"
    );
    assert_eq!(engine.state(), state);
}

#[test]
fn document_budget_rejects_import_without_allocating_more_document_tiles() {
    let mut engine = Engine::new(4096, 4096).unwrap();
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color: [30, 60, 90, 255],
            tolerance: 0,
        })
        .unwrap();
    engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
    let state = engine.state();
    let tile = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    assert_eq!(
        engine
            .import_layer(&png(1, 1, &[255; 4]), "full")
            .unwrap_err(),
        "工程像素超过内存限制"
    );
    assert_eq!(engine.state(), state);
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
}

#[test]
fn two_axis_resampling_matches_integer_coverage_and_premultiplied_alpha() {
    let mut rgba = Vec::new();
    for y in 0..13u8 {
        for x in 0..17u8 {
            rgba.extend([x * 15, y * 19, (x + y) * 8, x * 13 + y * 3]);
        }
    }
    let mut engine = Engine::new(7, 9).unwrap();
    engine.import_layer(&png(17, 13, &rgba), "area").unwrap();
    for dy in 0..5u32 {
        for dx in 0..7u32 {
            let mut sum = [0u64; 4];
            for sy in 0..13u32 {
                let wy = ((sy + 1) * 5)
                    .min((dy + 1) * 13)
                    .saturating_sub((sy * 5).max(dy * 13));
                for sx in 0..17u32 {
                    let wx = ((sx + 1) * 7)
                        .min((dx + 1) * 17)
                        .saturating_sub((sx * 7).max(dx * 17));
                    let index = ((sy * 17 + sx) * 4) as usize;
                    let alpha = u64::from(rgba[index + 3]);
                    for channel in 0..4 {
                        let value = if channel == 3 {
                            alpha
                        } else {
                            (u64::from(rgba[index + channel]) * alpha + 127) / 255
                        };
                        sum[channel] += value * u64::from(wx * wy);
                    }
                }
            }
            let expected = sum.map(|channel| ((channel + 110) / 221) as u8);
            assert_eq!(pixel(&engine.document.layers[1], dx, dy + 2), expected);
        }
    }
}

#[test]
fn animated_png_is_rejected_without_importing_only_the_first_frame() {
    let mut bytes = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut bytes, 1, 1);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder.set_animated(2, 0).unwrap();
        let mut writer = encoder.write_header().unwrap();
        writer.write_image_data(&[255, 0, 0, 255]).unwrap();
        writer.write_image_data(&[0, 0, 255, 255]).unwrap();
    }
    let mut engine = Engine::new(16, 16).unwrap();
    let state = engine.state();
    assert_eq!(
        engine.import_layer(&bytes, "animation").unwrap_err(),
        "暂不支持动画 PNG，请先导出为静态图片"
    );
    assert_eq!(engine.state(), state);
}
