use podor_engine::{model::*, ColorSelection, Command, Engine, SelectionMode};
use std::{collections::VecDeque, sync::Arc};

fn set_pixel(engine: &mut Engine, x: u32, y: u32, pixel: [u8; 4]) {
    let tile = engine
        .document
        .active_mut()
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .entry((x / TILE_SIZE, y / TILE_SIZE))
        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&pixel);
}

fn select(
    engine: &mut Engine,
    x: u32,
    y: u32,
    tolerance: u8,
    contiguous: bool,
    merged: bool,
    mode: SelectionMode,
) {
    engine
        .command(Command::SelectColor {
            settings: ColorSelection {
                x,
                y,
                tolerance,
                contiguous,
                merged,
            },
            mode,
        })
        .unwrap();
}

fn mask(engine: &Engine) -> Vec<u8> {
    let mut result = vec![0; (engine.document.width * engine.document.height) as usize];
    let data = engine.selection_frame();
    let read = |offset| u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap());
    for index in 0..read(4) as usize {
        let offset = 8 + index * (8 + TILE_BYTES);
        let left = read(offset) * TILE_SIZE;
        let top = read(offset + 4) * TILE_SIZE;
        for y in top..(top + TILE_SIZE).min(engine.document.height) {
            for x in left..(left + TILE_SIZE).min(engine.document.width) {
                result[(y * engine.document.width + x) as usize] =
                    data[offset + 8 + (((y - top) * TILE_SIZE + x - left) * 4) as usize + 3];
            }
        }
    }
    result
}

#[test]
fn connected_and_global_regions_match_an_independent_pixel_search() {
    let (width, height) = (263, 147);
    let mut engine = Engine::new(width, height).unwrap();
    let mut colors = Vec::new();
    let mut random = 317_u32;
    for y in 0..height {
        for x in 0..width {
            random = random.wrapping_mul(1664525).wrapping_add(1013904223);
            let value = ((random >> 24) / 32) * 32;
            colors.push(value as u8);
            set_pixel(
                &mut engine,
                x,
                y,
                [value as u8, value as u8, value as u8, 255],
            );
        }
    }
    for tolerance in [0, 32, 96, 255] {
        for (x, y) in [(0, 0), (129, 127), (262, 146)] {
            let seed = (y * width + x) as usize;
            for contiguous in [true, false] {
                let mut expected = vec![0; colors.len()];
                if contiguous {
                    let mut queue = VecDeque::from([seed]);
                    expected[seed] = 255;
                    while let Some(index) = queue.pop_front() {
                        let x = index % width as usize;
                        let y = index / width as usize;
                        for neighbor in [
                            (x > 0).then(|| index - 1),
                            (x + 1 < width as usize).then_some(index + 1),
                            (y > 0).then(|| index - width as usize),
                            (y + 1 < height as usize).then_some(index + width as usize),
                        ]
                        .into_iter()
                        .flatten()
                        {
                            if expected[neighbor] == 0
                                && colors[neighbor].abs_diff(colors[seed]) <= tolerance
                            {
                                expected[neighbor] = 255;
                                queue.push_back(neighbor);
                            }
                        }
                    }
                } else {
                    for (value, &color) in expected.iter_mut().zip(&colors) {
                        *value = if color.abs_diff(colors[seed]) <= tolerance {
                            255
                        } else {
                            0
                        };
                    }
                }
                select(
                    &mut engine,
                    x,
                    y,
                    tolerance,
                    contiguous,
                    false,
                    SelectionMode::Replace,
                );
                assert_eq!(
                    mask(&engine),
                    expected,
                    "{x}, {y}, tolerance {tolerance}, contiguous {contiguous}"
                );
            }
        }
    }
}

#[test]
fn alpha_and_unpremultiplied_colors_control_similarity() {
    let mut engine = Engine::new(260, 2).unwrap();
    set_pixel(&mut engine, 127, 0, [100, 0, 0, 128]);
    set_pixel(&mut engine, 128, 0, [101, 0, 0, 129]);
    set_pixel(&mut engine, 129, 0, [100, 0, 0, 255]);
    select(&mut engine, 127, 0, 2, true, false, SelectionMode::Replace);
    let selected = mask(&engine);
    assert_eq!(&selected[126..131], &[0, 255, 255, 0, 0]);
    select(&mut engine, 0, 0, 0, true, false, SelectionMode::Replace);
    let transparent = mask(&engine);
    assert_eq!(transparent.iter().filter(|&&v| v == 255).count(), 517);
    assert_eq!(transparent[127], 0);
    assert_eq!(transparent[519], 255);
}

#[test]
fn merged_sampling_uses_visible_layers_blend_and_opacity_without_white_background() {
    let mut engine = Engine::new(8, 2).unwrap();
    for x in 0..8 {
        set_pixel(&mut engine, x, 0, [200, 100, 50, 255]);
    }
    engine.command(Command::AddLayer).unwrap();
    for x in 0..4 {
        set_pixel(&mut engine, x, 0, [128, 255, 128, 255]);
    }
    let layer = engine.document.active_mut();
    layer.blend = BlendMode::Multiply;
    layer.opacity = 0.5;
    select(&mut engine, 6, 0, 0, true, true, SelectionMode::Replace);
    assert_eq!(&mask(&engine)[..8], &[0, 0, 0, 0, 255, 255, 255, 255]);
    select(&mut engine, 6, 0, 0, true, false, SelectionMode::Replace);
    assert_eq!(mask(&engine).iter().filter(|&&v| v != 0).count(), 12);
    engine.document.active_mut().visible = false;
    select(&mut engine, 6, 0, 0, true, true, SelectionMode::Replace);
    assert_eq!(&mask(&engine)[..8], &[255; 8]);
    select(&mut engine, 6, 1, 0, true, true, SelectionMode::Replace);
    assert_eq!(&mask(&engine)[8..], &[255; 8]);
    assert_eq!(&mask(&engine)[..8], &[0; 8]);
}

#[test]
fn combinations_and_invalid_seed_preserve_artwork_history_and_dirty_queue() {
    let mut engine = Engine::new(9, 3).unwrap();
    for x in [1, 2, 5, 6] {
        set_pixel(&mut engine, x, 1, [140, 25, 50, 255]);
    }
    engine.document.active_mut().locked = true;
    let original = engine.save().unwrap();
    engine.frame();
    select(&mut engine, 1, 1, 0, true, false, SelectionMode::Replace);
    select(&mut engine, 5, 1, 0, true, false, SelectionMode::Add);
    assert_eq!(mask(&engine).iter().filter(|&&v| v != 0).count(), 4);
    select(&mut engine, 1, 1, 0, true, false, SelectionMode::Subtract);
    let before = mask(&engine);
    assert_eq!(before.iter().filter(|&&v| v != 0).count(), 2);
    assert!(engine
        .command(Command::SelectColor {
            settings: ColorSelection {
                x: 9,
                y: 0,
                tolerance: 0,
                contiguous: true,
                merged: false
            },
            mode: SelectionMode::Replace
        })
        .is_err());
    assert_eq!(mask(&engine), before);
    select(&mut engine, 1, 1, 0, true, false, SelectionMode::Intersect);
    assert!(engine.state()["selection"]["empty"].as_bool().unwrap());
    assert!(mask(&engine).iter().all(|&v| v == 0));
    assert_eq!(engine.save().unwrap(), original);
    assert_eq!(engine.state()["revision"], 0);
    assert_eq!(engine.state()["canUndo"], false);
    assert_eq!(engine.frame().len(), 16);
}

#[test]
fn selected_disconnected_island_clips_fill_and_can_be_undone() {
    let mut engine = Engine::new(8, 3).unwrap();
    set_pixel(&mut engine, 1, 1, [180, 20, 30, 255]);
    set_pixel(&mut engine, 6, 1, [180, 20, 30, 255]);
    let original = engine.save().unwrap();
    select(&mut engine, 1, 1, 0, true, false, SelectionMode::Replace);
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 1,
            y: 1,
            color: [30, 40, 210, 255],
            tolerance: 0,
        })
        .unwrap();
    let tile = engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .get(&(0, 0))
        .unwrap();
    assert_eq!(&tile[(128 + 1) * 4..(128 + 2) * 4], &[30, 40, 210, 255]);
    assert_eq!(&tile[(128 + 6) * 4..(128 + 7) * 4], &[180, 20, 30, 255]);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), original);
}
