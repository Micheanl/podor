use podor_engine::{model::*, Command, Engine};
use serde_json::json;
use std::{collections::BTreeMap, sync::Arc};

struct Packet {
    layers: Vec<(u32, BTreeMap<TileKey, Vec<u8>>)>,
    mode: u32,
    metadata: [i32; 7],
    primary: BTreeMap<TileKey, Vec<u8>>,
    selected: BTreeMap<TileKey, Vec<u8>>,
}

fn integer(bytes: &[u8], offset: &mut usize) -> u32 {
    let value = u32::from_le_bytes(bytes[*offset..*offset + 4].try_into().unwrap());
    *offset += 4;
    value
}

fn tiles(bytes: &[u8], offset: &mut usize, channels: usize) -> BTreeMap<TileKey, Vec<u8>> {
    let count = integer(bytes, offset);
    let mut output = BTreeMap::new();
    for _ in 0..count {
        let x = integer(bytes, offset);
        let y = integer(bytes, offset);
        let length = MASK_TILE_BYTES * channels;
        output.insert((x, y), bytes[*offset..*offset + length].to_vec());
        *offset += length;
    }
    output
}

fn packet(bytes: &[u8]) -> Packet {
    let mut offset = 0;
    let _width = integer(bytes, &mut offset);
    let _height = integer(bytes, &mut offset);
    assert_eq!(integer(bytes, &mut offset), TILE_SIZE);
    let count = integer(bytes, &mut offset);
    let mut layers = Vec::new();
    for _ in 0..count {
        let id = integer(bytes, &mut offset);
        layers.push((id, tiles(bytes, &mut offset, 4)));
    }
    let mode = integer(bytes, &mut offset);
    let mut metadata = [0; 7];
    let mut primary = BTreeMap::new();
    let mut selected = BTreeMap::new();
    if mode != 0 {
        for value in &mut metadata {
            *value = integer(bytes, &mut offset) as i32;
        }
        primary = tiles(bytes, &mut offset, if mode == 2 { 4 } else { 1 });
        if mode == 2 {
            selected = tiles(bytes, &mut offset, 4);
        }
    }
    assert_eq!(offset, bytes.len());
    Packet {
        layers,
        mode,
        metadata,
        primary,
        selected,
    }
}

fn source() -> Engine {
    let mut engine = Engine::new(8, 8).unwrap();
    engine
        .command(
            serde_json::from_value(
                json!({"type":"fill", "x":0,"y":0,"color":[200,100,50,255],"tolerance":0}),
            )
            .unwrap(),
        )
        .unwrap();
    let mut mask = LayerMask::new(
        MaskBounds {
            left: -2,
            top: -2,
            right: 10,
            bottom: 10,
        },
        255,
    );
    let mut tile = vec![255; MASK_TILE_BYTES];
    for y in 0..12 {
        for x in 0..12 {
            tile[y * TILE_SIZE as usize + x] = ((x * 19 + y * 11) % 256) as u8;
        }
    }
    mask.tiles.insert((0, 0), Arc::new(tile));
    engine.document.active_mut().set_first_mask(Some(mask));
    engine.document.assign_mask_ids().unwrap();
    engine
}

#[test]
fn move_frame_keeps_active_pixels_and_signed_mask_independent() {
    let mut engine = source();
    let mut hidden = engine.document.layers[0].clone();
    hidden.id = 2;
    for entry in &mut hidden.masks {
        entry.id = 0;
    }
    hidden.first_mask_mut().unwrap().default = 0;
    hidden.first_mask_mut().unwrap().tiles.clear();
    engine.document.layers.insert(0, hidden);
    engine.document.next_id = 3;
    engine.document.assign_mask_ids().unwrap();
    let before = engine.save().unwrap();
    let decoded = packet(&engine.move_layer_frame(false).unwrap());
    assert_eq!(decoded.layers[0].0, 2);
    assert_eq!(&decoded.layers[0].1[&(0, 0)][..4], &[0, 0, 0, 0]);
    assert_eq!(decoded.layers[1].0, 1);
    assert_eq!(&decoded.layers[1].1[&(0, 0)][..4], &[200, 100, 50, 255]);
    assert_eq!(decoded.mode, 1);
    assert_eq!(decoded.metadata, [255, 1, 1, -2, -2, 10, 10]);
    assert_eq!(decoded.primary[&(0, 0)][0], 0);
    assert_eq!(decoded.primary[&(0, 0)][2 * TILE_SIZE as usize + 2], 60);
    assert_eq!(engine.save().unwrap(), before);
    engine
        .command(
            serde_json::from_value(
                json!({"type":"select","rect":{"left":1,"top":1,"right":3,"bottom":3}}),
            )
            .unwrap(),
        )
        .unwrap();
    let decoded = packet(&engine.move_layer_frame(true).unwrap());
    assert_eq!(
        decoded.layers.iter().map(|(id, _)| *id).collect::<Vec<_>>(),
        vec![2, 0, 1]
    );
    let at = ((TILE_SIZE + 1) * 4) as usize;
    assert_eq!(&decoded.layers[1].1[&(0, 0)][at..at + 4], &[0, 0, 0, 0]);
    assert_eq!(
        &decoded.layers[2].1[&(0, 0)][at..at + 4],
        &[200, 100, 50, 255]
    );
}

#[test]
fn selected_mask_gpu_planes_match_committed_antialiased_move() {
    let mut engine = source();
    for value in [
        json!({"type":"set_mask_editing","enabled":true}),
        json!({"type":"select_shape","selection":{"kind":"ellipse","left":1,"top":1,"right":6,"bottom":6}}),
    ] {
        engine
            .command(serde_json::from_value(value).unwrap())
            .unwrap();
    }
    let decoded = packet(&engine.move_layer_frame(true).unwrap());
    assert_eq!(decoded.mode, 2);
    assert_eq!(decoded.layers.len(), 1);
    assert_eq!(decoded.layers[0].0, 1);
    let mask = engine.document.layers[0].first_mask().cloned().unwrap();
    let bounds = MaskBounds {
        left: decoded.metadata[3],
        top: decoded.metadata[4],
        right: decoded.metadata[5],
        bottom: decoded.metadata[6],
    };
    let stationary = |x: i32, y: i32| -> u8 {
        if x < bounds.left || x >= bounds.right || y < bounds.top || y >= bounds.bottom {
            return decoded.metadata[0] as u8;
        }
        let lx = (x - bounds.left) as u32;
        let ly = (y - bounds.top) as u32;
        decoded
            .primary
            .get(&(lx / TILE_SIZE, ly / TILE_SIZE))
            .map_or(decoded.metadata[0] as u8, |tile| {
                tile[((ly % TILE_SIZE * TILE_SIZE + lx % TILE_SIZE) * 4) as usize]
            })
    };
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 1,
            dy: 1,
        })
        .unwrap();
    let result = engine.document.layers[0].first_mask().unwrap();
    let mut partial = false;
    for y in 0..8 {
        for x in 0..8 {
            let mut preview = stationary(x, y);
            let sx = x - 1;
            let sy = y - 1;
            if sx >= 0 && sy >= 0 {
                if let Some(tile) = decoded
                    .selected
                    .get(&(sx as u32 / TILE_SIZE, sy as u32 / TILE_SIZE))
                {
                    let start =
                        ((sy as u32 % TILE_SIZE * TILE_SIZE + sx as u32 % TILE_SIZE) * 4) as usize;
                    let alpha = tile[start + 3];
                    partial |= alpha > 0 && alpha < 255;
                    preview = (u32::from(tile[start])
                        + (u32::from(preview) * (255 - u32::from(alpha)) + 127) / 255)
                        .min(255) as u8;
                }
            }
            assert!(
                preview.abs_diff(result.sample(x, y)) <= 1,
                "at {x},{y}: {preview} != {}",
                result.sample(x, y)
            );
        }
    }
    assert!(partial);
    assert_eq!(result.default, mask.default);
    engine.command(Command::Undo).unwrap();
    assert_eq!(
        engine.document.layers[0].first_mask().unwrap().tiles,
        mask.tiles
    );
}

#[test]
fn empty_mask_planes_preserve_defaults_and_signed_transform_bounds() {
    let mut engine = source();
    engine
        .document
        .active_mut()
        .set_first_mask(Some(LayerMask::new(
            MaskBounds {
                left: -4,
                top: -3,
                right: -4,
                bottom: 2,
            },
            0,
        )));
    engine.document.assign_mask_ids().unwrap();
    engine
        .command(serde_json::from_value(json!({"type":"set_mask_editing","enabled":true})).unwrap())
        .unwrap();
    let decoded = packet(&engine.move_layer_frame(true).unwrap());
    assert_eq!(decoded.mode, 1);
    assert_eq!(decoded.metadata, [0, 1, 1, -4, -3, -4, 2]);
    assert!(decoded.primary.is_empty());
    assert!(engine.layer_transform_bounds().is_err());
    engine
        .document
        .active_mut()
        .first_mask_mut()
        .unwrap()
        .bounds
        .right = 7;
    assert_eq!(
        engine.layer_transform_bounds().unwrap(),
        MaskBounds {
            left: -4,
            top: -3,
            right: 7,
            bottom: 2
        }
    );
}

#[test]
fn sparse_mask_thumbnails_average_actual_plane_over_the_default() {
    let mut engine = Engine::new(192, 192).unwrap();
    let mut mask = LayerMask::new(
        MaskBounds {
            left: -1,
            top: -1,
            right: 2,
            bottom: 2,
        },
        255,
    );
    let mut tile = vec![255; MASK_TILE_BYTES];
    tile[TILE_SIZE as usize + 1] = 0;
    mask.tiles.insert((0, 0), Arc::new(tile));
    mask.enabled = false;
    engine.document.active_mut().set_first_mask(Some(mask));
    engine.document.assign_mask_ids().unwrap();
    let bytes = engine.mask_previews();
    assert_eq!(
        u32::from_le_bytes(bytes[8..12].try_into().unwrap()),
        PREVIEW_EDGE
    );
    assert_eq!(u32::from_le_bytes(bytes[12..16].try_into().unwrap()), 1);
    assert_eq!(u32::from_le_bytes(bytes[16..20].try_into().unwrap()), 1);
    assert_eq!(&bytes[20..24], &[191, 191, 191, 255]);
    assert_eq!(&bytes[24..28], &[255, 255, 255, 255]);
    assert_eq!(&bytes[bytes.len() - 4..], &[255, 255, 255, 255]);
}

#[test]
fn empty_selections_keep_the_entire_layer_and_mask_stationary() {
    let mut engine = source();
    for value in [
        json!({"type":"select","rect":{"left":1,"top":1,"right":3,"bottom":3}}),
        json!({"type":"combine_selection","mode":"subtract","selection":{"kind":"rectangle","left":0,"top":0,"right":8,"bottom":8}}),
    ] {
        engine
            .command(serde_json::from_value(value).unwrap())
            .unwrap();
    }
    let pixels = packet(&engine.move_layer_frame(true).unwrap());
    assert_eq!(
        pixels.layers.iter().map(|(id, _)| *id).collect::<Vec<_>>(),
        vec![0, 1]
    );
    assert_eq!(&pixels.layers[0].1[&(0, 0)][..4], &[200, 100, 50, 255]);
    assert!(pixels.layers[1].1.is_empty());
    engine
        .command(serde_json::from_value(json!({"type":"set_mask_editing","enabled":true})).unwrap())
        .unwrap();
    let masks = packet(&engine.move_layer_frame(true).unwrap());
    assert_eq!(masks.mode, 2);
    assert!(masks.selected.is_empty());
    assert_eq!(masks.metadata, [255, 1, 1, -2, -2, 10, 10]);
    assert_eq!(&masks.primary[&(0, 0)][..4], &[0, 0, 0, 255]);
    let state = engine.state();
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 1,
            dy: 1,
        })
        .unwrap();
    assert_eq!(engine.state(), state);
}
