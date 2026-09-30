use podor_engine::{model::*, Command, Engine, LayerTransform, ResampleFilter};
use std::{collections::BTreeMap, sync::Arc};

fn frame(engine: &mut Engine) -> BTreeMap<TileKey, Vec<u8>> {
    let packet = engine.frame_with_background(true);
    let count = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
    let mut result = BTreeMap::new();
    let mut offset = 16;
    for _ in 0..count {
        let x = u32::from_le_bytes(packet[offset..offset + 4].try_into().unwrap());
        let y = u32::from_le_bytes(packet[offset + 4..offset + 8].try_into().unwrap());
        result.insert((x, y), packet[offset + 8..offset + 8 + TILE_BYTES].to_vec());
        offset += 8 + TILE_BYTES;
    }
    result.retain(|_, pixels| pixels.iter().any(|&value| value != 0));
    result
}

fn rgba_leaf(id: u32, x: u32, y: u32, color: [u8; 4]) -> Layer {
    let mut layer = Layer::new(id, format!("Leaf {id}"));
    let mut pixels = vec![0; TILE_BYTES];
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    pixels[offset..offset + 4].copy_from_slice(&color);
    layer
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((x / TILE_SIZE, y / TILE_SIZE), Arc::new(pixels));
    layer
}

#[test]
fn sparse_maximum_canvas_group_flip_preserves_each_leaf_and_index_identity() {
    for indexed in [false, true] {
        let mut engine = Engine::new(8192, 2048).unwrap();
        let group = Layer::group(1, "Sparse".into(), GroupIsolation::Isolated);
        engine.document.layers = vec![group];
        if indexed {
            engine.document.palette = Some(IndexedPalette {
                colors: vec![[0, 0, 0, 0], [255, 0, 0, 255], [0, 0, 0, 0]],
                transparent: 0,
                order: vec![0, 1, 2],
            });
        }
        for offset in 0..MAX_LAYERS {
            let x = 8191 * offset as u32 / (MAX_LAYERS - 1) as u32;
            let y = 2047 * offset as u32 / (MAX_LAYERS - 1) as u32;
            let mut leaf = rgba_leaf(offset as u32 + 2, x, y, [255, 0, 0, 255]);
            leaf.parent_id = Some(1);
            if indexed {
                let mut pixels = vec![0; INDEX_TILE_BYTES];
                pixels[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize] =
                    if offset % 2 == 0 { 1 } else { 2 };
                leaf.content = LayerContent::Raster(RasterPlane::Indexed(BTreeMap::from([(
                    (x / TILE_SIZE, y / TILE_SIZE),
                    Arc::new(pixels),
                )])));
            }
            engine.document.layers.push(leaf);
        }
        engine.document.next_id = MAX_LAYERS as u32 + 2;
        engine.document.validate().unwrap();
        let original = engine.save().unwrap();
        engine
            .command(Command::TransformLayer {
                mask_id: None,
                id: 1,
                revision: 0,
                transform: LayerTransform {
                    width: 8192,
                    height: 2048,
                    dx: 0.0,
                    dy: 0.0,
                    angle: 0.0,
                    flip_x: true,
                    flip_y: false,
                    filter: ResampleFilter::Nearest,
                },
            })
            .unwrap();
        for (offset, leaf) in engine.document.layers[1..].iter().enumerate() {
            let x = 8191 - 8191 * offset as u32 / (MAX_LAYERS - 1) as u32;
            let y = 2047 * offset as u32 / (MAX_LAYERS - 1) as u32;
            let raster = leaf.raster().unwrap();
            assert_eq!(raster.tiles().len(), 1);
            let tile = &raster.tiles()[&(x / TILE_SIZE, y / TILE_SIZE)];
            if indexed {
                assert_eq!(
                    tile[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize],
                    if offset % 2 == 0 { 1 } else { 2 }
                );
            } else {
                let start = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                assert_eq!(&tile[start..start + 4], &[255, 0, 0, 255]);
            }
        }
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), original);
    }
}

#[test]
fn group_world_transform_keeps_soft_filter_edges_identical_to_independent_full_frame_reference() {
    for filter in [ResampleFilter::Nearest, ResampleFilter::Lanczos3] {
        for (width, height, angle, flip_x, flip_y) in [
            (120, 100, 0.0, false, false),
            (200, 170, 17.0, false, true),
            (100, 80, 90.0, true, false),
        ] {
            let mut group = Engine::new(512, 384).unwrap();
            let mut red = rgba_leaf(2, 120, 105, [200, 0, 0, 200]);
            let mut blue = rgba_leaf(3, 260, 210, [0, 0, 150, 150]);
            red.parent_id = Some(1);
            blue.parent_id = Some(1);
            group.document.layers = vec![
                Layer::group(1, "Reference".into(), GroupIsolation::Isolated),
                red,
                blue,
            ];
            group.document.next_id = 4;
            let mut reference = Engine::new(512, 384).unwrap();
            let mut combined = rgba_leaf(1, 120, 105, [200, 0, 0, 200]);
            let blue = rgba_leaf(2, 260, 210, [0, 0, 150, 150]);
            for (&key, tile) in blue.raster().unwrap().tiles() {
                combined
                    .raster_mut()
                    .unwrap()
                    .tiles_mut()
                    .insert(key, tile.clone());
            }
            reference.document.layers = vec![combined];
            let transform = LayerTransform {
                width,
                height,
                dx: 3.25,
                dy: -2.5,
                angle,
                flip_x,
                flip_y,
                filter,
            };
            group
                .command(Command::TransformLayer {
                    mask_id: None,
                    id: 1,
                    revision: 0,
                    transform,
                })
                .unwrap();
            reference
                .command(Command::TransformLayer {
                    mask_id: None,
                    id: 1,
                    revision: 0,
                    transform,
                })
                .unwrap();
            assert_eq!(
                frame(&mut group),
                frame(&mut reference),
                "{filter:?}/{width}/{angle}"
            );
        }
    }
}
