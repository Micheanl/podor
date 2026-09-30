use podor_engine::{model::*, Engine, ExportFormat, ExportOptions};
use serde_json::{json, Value};
use std::sync::Arc;

const EDGE: u32 = 96;
const IMAGE_BYTES: usize = (EDGE * EDGE * 4) as usize;

fn send(engine: &mut Engine, mut value: Value) -> Value {
    let state = engine.state();
    value["revision"] = state["revision"].clone();
    if !state["animation"].is_null()
        && !matches!(
            value["type"].as_str().unwrap(),
            "enable_animation" | "add_frame" | "duplicate_frame" | "select_frame"
        )
    {
        value["frame_id"] = state["animation"]["activeFrameId"].clone();
        value["cel_id"] = state["animation"]["activeCelId"].clone();
        value["target_layer_id"] = state["active"].clone();
    }
    engine
        .command_request(serde_json::from_value(value).unwrap())
        .unwrap()
}

fn put(layer: &mut Layer, x: u32, y: u32, color: [u8; 4]) {
    let tile = layer
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .entry((x / TILE_SIZE, y / TILE_SIZE))
        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&color);
}

fn reload(engine: &mut Engine) {
    engine.document.assign_mask_ids().unwrap();
    engine.load(&engine.save().unwrap()).unwrap();
}

fn active(engine: &Engine) -> u32 {
    engine.state()["animation"]["activeFrameId"]
        .as_u64()
        .unwrap() as u32
}

fn enable(engine: &mut Engine) -> u32 {
    send(engine, json!({"type":"enable_animation","duration_ms":100}));
    active(engine)
}

fn add_frame(engine: &mut Engine, index: usize) -> u32 {
    send(
        engine,
        json!({"type":"add_frame","index":index,"duration_ms":100,"select":true}),
    );
    active(engine)
}

fn solid() -> Engine {
    let mut engine = Engine::new(2, 2).unwrap();
    for y in 0..2 {
        for x in 0..2 {
            put(&mut engine.document.layers[0], x, y, [255, 0, 0, 255]);
        }
    }
    reload(&mut engine);
    engine
}

fn frames() -> (Engine, [u32; 4]) {
    let mut engine = solid();
    let first = enable(&mut engine);
    send(
        &mut engine,
        json!({"type":"duplicate_frame","frame_id":first,"index":1,"linked":true,"select":true}),
    );
    let linked = active(&engine);
    send(
        &mut engine,
        json!({"type":"duplicate_frame","frame_id":first,"index":2,"linked":false,"select":true}),
    );
    let independent = active(&engine);
    send(
        &mut engine,
        json!({"type":"fill","x":0,"y":0,"color":[0,0,255,128],"tolerance":0}),
    );
    let empty = add_frame(&mut engine, 3);
    (engine, [first, linked, independent, empty])
}

fn decode(bytes: &[u8], revision: u64) -> Vec<(u32, Vec<u8>)> {
    assert!(bytes.len() >= 16);
    assert_eq!(u64::from_le_bytes(bytes[..8].try_into().unwrap()), revision);
    assert_eq!(u32::from_le_bytes(bytes[8..12].try_into().unwrap()), EDGE);
    let count = u32::from_le_bytes(bytes[12..16].try_into().unwrap()) as usize;
    assert_eq!(bytes.len(), 16 + count * (4 + IMAGE_BYTES));
    bytes[16..]
        .as_chunks::<{ 4 + IMAGE_BYTES }>()
        .0
        .iter()
        .map(|entry| {
            let id = u32::from_le_bytes(entry[..4].try_into().unwrap());
            assert_ne!(id, 0);
            (id, entry[4..].to_vec())
        })
        .collect()
}

fn previews(engine: &Engine, ids: &[u32]) -> Vec<(u32, Vec<u8>)> {
    let revision = engine.state()["revision"].as_u64().unwrap();
    let request = serde_json::from_value(json!({"revision":revision,"frame_ids":ids})).unwrap();
    decode(&engine.animation_previews(request).unwrap(), revision)
}

fn uniform(pixels: &[u8], expected: [u8; 4]) {
    for (index, pixel) in pixels.as_chunks::<4>().0.iter().enumerate() {
        assert_eq!(*pixel, expected, "thumbnail pixel {index}");
    }
}

fn at(pixels: &[u8], x: u32, y: u32) -> [u8; 4] {
    let offset = ((y * EDGE + x) * 4) as usize;
    pixels[offset..offset + 4].try_into().unwrap()
}

fn quiet(engine: &mut Engine) -> (Vec<u8>, Value) {
    engine.frame_with_background(true);
    (engine.save().unwrap(), engine.state())
}

fn unchanged(engine: &mut Engine, saved: &[u8], state: &Value) {
    assert!(engine.save().unwrap() == saved);
    assert_eq!(&engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

#[test]
fn thumbnails_keep_requested_order_and_render_actual_independent_linked_and_empty_cels() {
    let (mut engine, [first, linked, independent, empty]) = frames();
    let animation = engine.document.animation.as_ref().unwrap();
    let exposure = |id| {
        animation
            .frames
            .iter()
            .find(|frame| frame.id == id)
            .unwrap()
            .exposures
            .get(&1)
    };
    assert_eq!(exposure(first), exposure(linked));
    assert_ne!(exposure(first), exposure(independent));
    assert_eq!(exposure(empty), None);
    let (saved, state) = quiet(&mut engine);
    let batch = previews(&engine, &[independent, first, empty, linked]);
    assert_eq!(
        batch.iter().map(|(id, _)| *id).collect::<Vec<_>>(),
        [independent, first, empty, linked]
    );
    uniform(&batch[0].1, [0, 0, 128, 128]);
    uniform(&batch[1].1, [255, 0, 0, 255]);
    uniform(&batch[2].1, [0; 4]);
    uniform(&batch[3].1, [255, 0, 0, 255]);
    unchanged(&mut engine, &saved, &state);
    let stale = json!({"revision":state["revision"],"frame_ids":[first,linked]});
    send(&mut engine, json!({"type":"select_frame","frame_id":first}));
    send(
        &mut engine,
        json!({"type":"fill","x":0,"y":0,"color":[0,255,0,255],"tolerance":0}),
    );
    assert!(engine
        .animation_previews(serde_json::from_value(stale).unwrap())
        .is_err());
    let saved = engine.save().unwrap();
    let state = engine.state();
    let batch = previews(&engine, &[first, linked, independent]);
    uniform(&batch[0].1, [0, 255, 0, 255]);
    uniform(&batch[1].1, [0, 255, 0, 255]);
    uniform(&batch[2].1, [0, 0, 128, 128]);
    let pending = engine.frame_with_background(true);
    assert_eq!(u32::from_le_bytes(pending[12..16].try_into().unwrap()), 1);
    assert_eq!(&pending[24..28], &[0, 255, 0, 255]);
    unchanged(&mut engine, &saved, &state);
}

#[test]
fn area_thumbnail_preserves_premultiplied_average_and_transparent_letterboxing() {
    let mut engine = Engine::new(192, 96).unwrap();
    for y in 0..96 {
        for x in 0..192 {
            let color = match (x % 2, y % 2) {
                (0, 0) => [255, 0, 0, 255],
                (1, 0) => [0, 255, 0, 255],
                (0, 1) => [0, 0, 128, 128],
                _ => [0; 4],
            };
            put(&mut engine.document.layers[0], x, y, color);
        }
    }
    reload(&mut engine);
    let frame = enable(&mut engine);
    let (saved, state) = quiet(&mut engine);
    let batch = previews(&engine, &[frame]);
    for y in 0..96 {
        for x in 0..96 {
            let expected = if (24..72).contains(&y) {
                [64, 64, 32, 160]
            } else {
                [0; 4]
            };
            assert_eq!(at(&batch[0].1, x, y), expected, "at {x},{y}");
        }
    }
    unchanged(&mut engine, &saved, &state);
}

#[test]
fn thumbnails_use_real_blend_and_group_mask_composite_in_premultiplied_rgba() {
    let mut engine = Engine::new(2, 2).unwrap();
    let mut group = Layer::group(2, "Masked group".into(), GroupIsolation::Isolated);
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 2,
            bottom: 2,
        },
        255,
    );
    mask.tiles
        .insert((0, 0), Arc::new(vec![128; MASK_TILE_BYTES]));
    group.masks = vec![MaskEntry {
        id: 0,
        name: "Half coverage".into(),
        plane: mask,
    }];
    let mut base = Layer::new(1, "Base".into());
    base.parent_id = Some(2);
    let mut top = Layer::new(3, "Multiply".into());
    top.parent_id = Some(2);
    top.blend = BlendMode::Multiply;
    for y in 0..2 {
        for x in 0..2 {
            put(&mut base, x, y, [200, 100, 50, 255]);
            put(&mut top, x, y, [128, 128, 128, 255]);
        }
    }
    engine.document.layers = vec![group, base, top];
    engine.document.active = 1;
    engine.document.next_id = 4;
    reload(&mut engine);
    let frame = enable(&mut engine);
    let (saved, state) = quiet(&mut engine);
    let batch = previews(&engine, &[frame]);
    uniform(&batch[0].1, [50, 25, 13, 128]);
    unchanged(&mut engine, &saved, &state);
}

#[test]
fn thumbnail_caps_sizes_frame_ids_stale_revisions_and_still_requests_reject_atomically() {
    let mut engine = solid();
    let first = enable(&mut engine);
    let mut ids = vec![first];
    for index in 1..=32 {
        ids.push(add_frame(&mut engine, index));
    }
    assert_eq!(engine.state()["maxFrameThumbnails"], 32);
    let (saved, state) = quiet(&mut engine);
    for request in [
        json!({"revision":state["revision"],"frame_ids":[]}),
        json!({"revision":state["revision"],"frame_ids":[first,first]}),
        json!({"revision":state["revision"],"frame_ids":[0]}),
        json!({"revision":state["revision"],"frame_ids":[9999]}),
        json!({"revision":state["revision"],"frame_ids":ids}),
        json!({"revision":state["revision"],"frame_ids":[first],"size":0}),
        json!({"revision":state["revision"],"frame_ids":[first],"size":95}),
        json!({"revision":state["revision"],"frame_ids":[first],"size":192}),
        json!({"revision":0,"frame_ids":[first]}),
    ] {
        assert!(engine
            .animation_previews(serde_json::from_value(request).unwrap())
            .is_err());
        unchanged(&mut engine, &saved, &state);
    }
    let batch = previews(&engine, &ids[..32]);
    assert_eq!(batch.len(), 32);
    assert_eq!(
        batch.iter().map(|(id, _)| *id).collect::<Vec<_>>(),
        ids[..32]
    );
    uniform(&batch[0].1, [255, 0, 0, 255]);
    for (_, pixels) in &batch[1..] {
        uniform(pixels, [0; 4]);
    }
    unchanged(&mut engine, &saved, &state);
    let mut still = solid();
    let (saved, state) = quiet(&mut still);
    let request =
        serde_json::from_value(json!({"revision":state["revision"],"frame_ids":[1]})).unwrap();
    assert!(still.animation_previews(request).is_err());
    unchanged(&mut still, &saved, &state);
}

fn png_pixels(engine: &Engine, frame: Option<u32>) -> Vec<u8> {
    let bytes = engine
        .export_image(ExportOptions {
            format: ExportFormat::Png,
            transparent: true,
            frame_id: frame,
            ..ExportOptions::default()
        })
        .unwrap();
    let mut reader = png::Decoder::new(bytes.as_slice()).read_info().unwrap();
    let mut pixels = vec![0; reader.output_buffer_size()];
    let image = reader.next_frame(&mut pixels).unwrap();
    assert_eq!((image.width, image.height), (2, 2));
    assert_eq!(image.color_type, png::ColorType::Rgba);
    pixels.truncate(image.buffer_size());
    pixels
}

#[test]
fn png_export_requires_an_explicit_animation_frame_and_encodes_its_actual_pixels() {
    let (mut engine, [first, linked, independent, empty]) = frames();
    assert_eq!(active(&engine), empty);
    let (saved, state) = quiet(&mut engine);
    for frame in [None, Some(9999)] {
        assert!(engine
            .export_image(ExportOptions {
                format: ExportFormat::Png,
                transparent: true,
                frame_id: frame,
                ..ExportOptions::default()
            })
            .is_err());
        unchanged(&mut engine, &saved, &state);
    }
    assert!(engine.export_png().is_err());
    for (frame, expected) in [
        (first, [255, 0, 0, 255]),
        (linked, [255, 0, 0, 255]),
        (independent, [0, 0, 255, 128]),
        (empty, [0; 4]),
    ] {
        uniform(&png_pixels(&engine, Some(frame)), expected);
        unchanged(&mut engine, &saved, &state);
    }
    assert!(state["animation"]["activeFrameId"] == empty);
    let mut still = solid();
    let (saved, state) = quiet(&mut still);
    uniform(&png_pixels(&still, None), [255, 0, 0, 255]);
    unchanged(&mut still, &saved, &state);
}

#[test]
fn opaque_frame_preview_covers_blank_tiles_and_matches_normal_frame_without_mutation() {
    let render = |engine: &Engine, frame_id, transparent, region| {
        engine
            .render_animation_frame(podor_engine::FrameRenderRequest {
                revision: engine.state()["revision"].as_u64().unwrap(),
                frame_id,
                transparent,
                region,
            })
            .unwrap()
    };
    let decode_tiles = |bytes: &[u8]| {
        assert_eq!(u32::from_le_bytes(bytes[..4].try_into().unwrap()), 256);
        assert_eq!(u32::from_le_bytes(bytes[4..8].try_into().unwrap()), 256);
        assert_eq!(
            u32::from_le_bytes(bytes[8..12].try_into().unwrap()),
            TILE_SIZE
        );
        let count = u32::from_le_bytes(bytes[12..16].try_into().unwrap()) as usize;
        assert_eq!(bytes.len(), 16 + count * (8 + TILE_BYTES));
        let mut tiles = std::collections::BTreeMap::new();
        for entry in bytes[16..].as_chunks::<{ 8 + TILE_BYTES }>().0 {
            let key = (
                u32::from_le_bytes(entry[..4].try_into().unwrap()),
                u32::from_le_bytes(entry[4..8].try_into().unwrap()),
            );
            assert!(tiles.insert(key, entry[8..].to_vec()).is_none());
        }
        tiles
    };
    let mut engine = Engine::new(256, 256).unwrap();
    put(&mut engine.document.layers[0], 2, 3, [210, 40, 70, 255]);
    reload(&mut engine);
    let colored = enable(&mut engine);
    let blank = add_frame(&mut engine, 1);
    let bottom_right = Some(Rect {
        left: 128,
        top: 128,
        right: 256,
        bottom: 256,
    });
    for frame in [blank, colored] {
        if active(&engine) != frame {
            send(&mut engine, json!({"type":"select_frame","frame_id":frame}));
        }
        let saved = engine.save().unwrap();
        let state = engine.state();
        let opaque = render(&engine, frame, false, None);
        let opaque_tiles = decode_tiles(&opaque);
        assert_eq!(
            opaque_tiles.keys().copied().collect::<Vec<_>>(),
            [(0, 0), (0, 1), (1, 0), (1, 1)]
        );
        for (&(tx, ty), pixels) in &opaque_tiles {
            for (index, pixel) in pixels.as_chunks::<4>().0.iter().enumerate() {
                let x = tx * TILE_SIZE + index as u32 % TILE_SIZE;
                let y = ty * TILE_SIZE + index as u32 / TILE_SIZE;
                let expected = if frame == colored && (x, y) == (2, 3) {
                    [210, 40, 70, 255]
                } else {
                    [255; 4]
                };
                assert_eq!(*pixel, expected, "opaque frame {frame} at {x},{y}");
            }
        }
        let roi = decode_tiles(&render(&engine, frame, false, bottom_right));
        assert_eq!(roi.keys().copied().collect::<Vec<_>>(), [(1, 1)]);
        uniform(&roi[&(1, 1)], [255; 4]);
        let transparent = decode_tiles(&render(&engine, frame, true, None));
        if frame == blank {
            assert!(transparent.is_empty());
        } else {
            assert_eq!(transparent.keys().copied().collect::<Vec<_>>(), [(0, 0)]);
            for (index, pixel) in transparent[&(0, 0)].as_chunks::<4>().0.iter().enumerate() {
                let expected = if index == (3 * TILE_SIZE + 2) as usize {
                    [210, 40, 70, 255]
                } else {
                    [0; 4]
                };
                assert_eq!(*pixel, expected, "transparent pixel {index}");
            }
        }
        assert!(engine.save().unwrap() == saved);
        assert_eq!(engine.state(), state);
        let incremental = decode_tiles(&engine.frame_with_background(false));
        assert_eq!(incremental.keys().copied().collect::<Vec<_>>(), [(0, 0)]);
        for (key, pixels) in incremental {
            assert!(pixels == opaque_tiles[&key], "incremental tile {key:?}");
        }
        assert_eq!(engine.frame_with_background(false).len(), 16);
        assert!(engine.save().unwrap() == saved);
        assert_eq!(engine.state(), state);
    }
}
