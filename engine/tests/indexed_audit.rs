use podor_engine::{model::*, Command, Engine};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn create(width: u32, height: u32) -> Engine {
    let mut engine = Engine::new(1, 1).unwrap();
    command(&mut engine, json!({
        "type":"new_indexed", "width":width, "height":height,
        "palette":{"colors":[[0,0,0,0],[255,0,0,255],[0,0,255,255]],"transparent":0,"order":[0,1,2]}
    })).unwrap();
    engine
}

fn rgba_at(frame: &[u8], id: u32, x: u32, y: u32) -> [u8; 4] {
    let u32_at = |offset| u32::from_le_bytes(frame[offset..offset + 4].try_into().unwrap());
    let mut offset = 16;
    for _ in 0..u32_at(12) {
        let actual = u32_at(offset);
        let count = u32_at(offset + 4);
        offset += 8;
        for _ in 0..count {
            let key = (u32_at(offset), u32_at(offset + 4));
            offset += 8;
            let pixels = &frame[offset..offset + TILE_BYTES];
            offset += TILE_BYTES;
            if actual == id && key == (x / TILE_SIZE, y / TILE_SIZE) {
                let position = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                return pixels[position..position + 4].try_into().unwrap();
            }
        }
    }
    panic!("Missing layer pixel");
}

#[test]
fn cached_frames_refresh_palette_undo_redo_without_baking_masks_into_rgba_cache() {
    let mut engine = create(2, 2);
    command(
        &mut engine,
        json!({"type":"fill_indexed","x":0,"y":0,"index":1,"tolerance":0}),
    )
    .unwrap();
    command(&mut engine, json!({"type":"add_mask","mode":"hide"})).unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":true}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"fill","x":0,"y":0,"color":[128,128,128,255],"tolerance":0}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":false}),
    )
    .unwrap();
    let source = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    let frame = engine.frame_with_background(true);
    assert_eq!(&frame[24..28], &[128, 0, 0, 128]);
    let revision = engine.state()["revision"].clone();
    command(
        &mut engine,
        json!({"type":"set_palette_color","index":1,"color":[0,0,255,255],"revision":revision}),
    )
    .unwrap();
    assert_eq!(
        &engine.frame_with_background(true)[24..28],
        &[0, 0, 128, 128]
    );
    engine.command(Command::Undo).unwrap();
    assert_eq!(
        &engine.frame_with_background(true)[24..28],
        &[128, 0, 0, 128]
    );
    engine.command(Command::Redo).unwrap();
    assert_eq!(
        &engine.frame_with_background(true)[24..28],
        &[0, 0, 128, 128]
    );
    command(
        &mut engine,
        json!({"type":"set_mask","id":1,"enabled":false,"linked":true}),
    )
    .unwrap();
    assert_eq!(
        &engine.frame_with_background(true)[24..28],
        &[0, 0, 255, 255]
    );
    engine.command(Command::Undo).unwrap();
    assert_eq!(
        &engine.frame_with_background(true)[24..28],
        &[0, 0, 128, 128]
    );
    assert!(Arc::ptr_eq(
        &source,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
    assert!(source.iter().take(2).all(|&index| index == 1));
}

#[test]
fn layered_packets_expand_indices_into_rgba_and_keep_active_masks_independent() {
    let mut engine = create(128, 128);
    engine.document.palette.as_mut().unwrap().colors[1] = [20, 40, 80, 128];
    engine.document.layers[0].content =
        podor_engine::model::LayerContent::Raster(RasterPlane::Indexed(BTreeMap::from([(
            (0, 0),
            Arc::new(vec![1; INDEX_TILE_BYTES]),
        )])));
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 128,
            bottom: 128,
        },
        255,
    );
    let mut pixels = vec![255; MASK_TILE_BYTES];
    pixels[(5 * TILE_SIZE + 4) as usize] = 128;
    mask.tiles.insert((0, 0), Arc::new(pixels));
    engine.document.layers[0].set_first_mask(Some(mask));
    engine.document.assign_mask_ids().unwrap();
    engine.document.validate().unwrap();
    let source = engine.save().unwrap();
    let old = engine.layer_frame().unwrap();
    assert_eq!(old.len(), 32 + TILE_BYTES);
    assert_eq!(rgba_at(&old, 1, 4, 5), [5, 10, 20, 64]);
    let raw = engine.move_layer_frame(false).unwrap();
    assert_eq!(rgba_at(&raw, 1, 4, 5), [10, 20, 40, 128]);
    let extension = 32 + TILE_BYTES;
    assert_eq!(&raw[extension..extension + 4], &1u32.to_le_bytes());
    assert_eq!(raw[extension + 44 + (5 * TILE_SIZE + 4) as usize], 128);
    assert_eq!(raw.len(), extension + 44 + MASK_TILE_BYTES);
    command(
        &mut engine,
        json!({"type":"select","rect":{"left":4,"top":5,"right":5,"bottom":6}}),
    )
    .unwrap();
    let selected_state = engine.state();
    assert!(engine.move_layer_frame(true).is_err());
    assert!(engine.selection_move_frame().is_err());
    assert!(engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 1,
            dy: 0
        })
        .is_err());
    assert_eq!(selected_state, engine.state());
    assert_eq!(source, engine.save().unwrap());
}

#[repr(C)]
struct ReferenceBuffer {
    data: *mut u8,
    length: usize,
    error: u32,
}

extern "C" {
    fn podor_call(handle: u64, operation: u32, data: *const u8, length: usize) -> ReferenceBuffer;
    fn podor_free(buffer: ReferenceBuffer);
}

#[test]
fn indexed_png_reference_bridge_outputs_premultiplied_rgba_and_allows_lanczos_downscale() {
    let colors = [
        [190, 7, 88, 0],
        [7, 150, 233, 1],
        [40, 200, 80, 128],
        [250, 30, 40, 255],
    ];
    for (width, height) in [(129, 2), (4096, 2)] {
        let indices: Vec<u8> = if width > MAX_REFERENCE_EDGE {
            vec![2; (width * height) as usize]
        } else {
            (0..width * height).map(|value| (value % 4) as u8).collect()
        };
        let mut bytes = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut bytes, width, height);
            encoder.set_color(png::ColorType::Indexed);
            encoder.set_depth(png::BitDepth::Eight);
            encoder.set_palette(
                colors
                    .iter()
                    .flat_map(|color| color[..3].iter().copied())
                    .collect::<Vec<_>>(),
            );
            encoder.set_trns(colors.iter().map(|color| color[3]).collect::<Vec<_>>());
            encoder
                .write_header()
                .unwrap()
                .write_image_data(&indices)
                .unwrap();
        }
        assert_eq!(
            png::Decoder::new(bytes.as_slice())
                .read_info()
                .unwrap()
                .info()
                .color_type,
            png::ColorType::Indexed
        );
        let buffer = unsafe { podor_call(0, 18, bytes.as_ptr(), bytes.len()) };
        let status = buffer.error;
        let pixels = unsafe { std::slice::from_raw_parts(buffer.data, buffer.length) }.to_vec();
        unsafe { podor_free(buffer) };
        assert_eq!(status, 0);
        let u32_at = |offset| u32::from_le_bytes(pixels[offset..offset + 4].try_into().unwrap());
        assert_eq!((u32_at(0), u32_at(4)), (width, height));
        let expected = if width > MAX_REFERENCE_EDGE {
            assert_eq!((u32_at(8), u32_at(12)), (MAX_REFERENCE_EDGE, 1));
            [20, 100, 40, 128].repeat(MAX_REFERENCE_EDGE as usize)
        } else {
            assert_eq!((u32_at(8), u32_at(12)), (width, height));
            indices
                .iter()
                .flat_map(|&index| {
                    let color = colors[index as usize];
                    let alpha = u32::from(color[3]);
                    [
                        ((u32::from(color[0]) * alpha + 127) / 255) as u8,
                        ((u32::from(color[1]) * alpha + 127) / 255) as u8,
                        ((u32::from(color[2]) * alpha + 127) / 255) as u8,
                        color[3],
                    ]
                })
                .collect()
        };
        assert_eq!(&pixels[16..], expected);
    }
}

fn stroke(engine: &mut Engine, brush: Brush, points: &[Sample]) {
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    engine.samples(points).unwrap();
    engine.command(Command::End).unwrap();
}

#[test]
fn repeated_leaf_pixel_strokes_and_empty_eraser_preserve_revision_redo_and_index_buffers() {
    let mut engine = create(128, 128);
    let brush = Brush {
        size: 12.0,
        size_pressure: 0.0,
        opacity_pressure: 0.0,
        aspect: 0.5,
        tip: BrushTip::Leaf,
        follow_direction: true,
        raster: BrushRaster::PixelPerfect,
        index: Some(1),
        ..Brush::default()
    };
    let points = [
        Sample {
            x: 70.0,
            y: 70.0,
            pressure: 1.0,
        },
        Sample {
            x: 75.0,
            y: 75.0,
            pressure: 1.0,
        },
    ];
    stroke(&mut engine, brush, &points);
    let revision = engine.state()["revision"].clone();
    command(
        &mut engine,
        json!({"type":"set_palette_color","index":2,"color":[0,255,0,255],"revision":revision}),
    )
    .unwrap();
    engine.command(Command::Undo).unwrap();
    let source = engine.save().unwrap();
    let state = engine.state();
    let tile = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    assert_eq!(state["canRedo"], true);
    stroke(&mut engine, brush, &points);
    stroke(
        &mut engine,
        Brush {
            eraser: true,
            size: 1.0,
            index: None,
            tip: BrushTip::Round,
            ..brush
        },
        &[Sample {
            x: 5.0,
            y: 5.0,
            pressure: 1.0,
        }],
    );
    stroke(
        &mut engine,
        Brush {
            opacity: 0.0,
            ..brush
        },
        &points,
    );
    engine.document.validate().unwrap();
    assert_eq!(source, engine.save().unwrap());
    assert_eq!(state, engine.state());
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
}

#[test]
fn maximum_valid_indexed_document_rejects_oversized_layered_preview_before_expansion() {
    let mut engine = create(2048, 2048);
    let shared = Arc::new(vec![1; INDEX_TILE_BYTES]);
    let tiles: BTreeMap<_, _> = (0..16)
        .flat_map(|y| (0..16).map(move |x| (x, y)))
        .map(|key| (key, shared.clone()))
        .collect();
    engine.document.layers = (1..=MAX_LAYERS as u32)
        .map(|id| {
            let mut layer = Layer::new(id, format!("Layer {id}"));
            layer.content =
                podor_engine::model::LayerContent::Raster(RasterPlane::Indexed(tiles.clone()));
            layer
        })
        .collect();
    engine.document.next_id = MAX_LAYERS as u32 + 1;
    engine.document.validate().unwrap();
    assert_eq!(engine.document.pixel_bytes(), MAX_DOCUMENT_BYTES);
    let state = engine.state();
    assert!(engine.layer_frame().is_err());
    assert!(engine.move_layer_frame(false).is_err());
    assert!(engine.move_layer_frame(true).is_err());
    assert_eq!(state, engine.state());
    assert!(engine
        .document
        .layers
        .iter()
        .flat_map(|layer| layer.raster().unwrap().tiles().values())
        .all(|tile| Arc::ptr_eq(tile, &shared)));
}

#[test]
fn empty_indexed_clear_keeps_noop_history_but_still_respects_locks_and_mask_target() {
    let mut engine = create(2, 2);
    let state = engine.state();
    engine.command(Command::Clear).unwrap();
    assert_eq!(state, engine.state());
    for field in ["locked", "alpha_locked"] {
        let mut engine = create(2, 2);
        let mut protection = json!({"type":"set_protection","id":1});
        protection[field] = true.into();
        command(&mut engine, protection).unwrap();
        let before = engine.save().unwrap();
        let state = engine.state();
        assert!(engine.command(Command::Clear).is_err());
        assert_eq!(before, engine.save().unwrap());
        assert_eq!(state, engine.state());
    }
    command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":true}),
    )
    .unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    assert!(engine.command(Command::Clear).is_err());
    assert_eq!(before, engine.save().unwrap());
    assert_eq!(state, engine.state());
}

#[test]
fn selected_mask_packet_preserves_tiles_when_signed_local_origin_changes() {
    let mut engine = create(256, 256);
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 1,
            top: 1,
            right: 257,
            bottom: 257,
        },
        255,
    );
    mask.tiles
        .insert((0, 0), Arc::new(vec![0; MASK_TILE_BYTES]));
    engine.document.layers[0].set_first_mask(Some(mask));
    engine.document.assign_mask_ids().unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":true}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"select","rect":{"left":0,"top":0,"right":1,"bottom":1}}),
    )
    .unwrap();
    let before = engine.save().unwrap();
    let frame = engine.move_layer_frame(true).unwrap();
    let u32_at = |offset| u32::from_le_bytes(frame[offset..offset + 4].try_into().unwrap());
    assert_eq!(u32_at(24), 2);
    assert_eq!(u32_at(56), 4);
    let selected_count = 60 + 4 * (8 + TILE_BYTES);
    assert_eq!(u32_at(selected_count), 1);
    assert_eq!(frame.len(), selected_count + 4 + 8 + TILE_BYTES);
    assert_eq!(before, engine.save().unwrap());
}
