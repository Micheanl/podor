use podor_engine::{model::*, Command, Engine, ExportOptions, ResampleFilter};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

fn palette() -> IndexedPalette {
    IndexedPalette {
        colors: vec![
            [0; 4],
            [220, 30, 70, 255],
            [220, 30, 70, 255],
            [40, 110, 210, 255],
            [30, 80, 120, 128],
        ],
        transparent: 0,
        order: vec![0, 1, 2, 3, 4],
    }
}

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn create(width: u32, height: u32) -> Engine {
    let mut engine = Engine::new(width, height).unwrap();
    command(
        &mut engine,
        json!({"type":"new_indexed","width":width,"height":height,"palette":palette()}),
    )
    .unwrap();
    engine
}

fn put(engine: &mut Engine, layer: usize, x: u32, y: u32, index: u8) {
    let transparent = engine.document.palette.as_ref().unwrap().transparent;
    let tile = engine.document.layers[layer]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .entry((x / TILE_SIZE, y / TILE_SIZE))
        .or_insert_with(|| Arc::new(vec![transparent; INDEX_TILE_BYTES]));
    Arc::make_mut(tile)[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize] = index;
}

fn index(engine: &Engine, layer: usize, x: u32, y: u32) -> u8 {
    engine.document.layers[layer]
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or(
            engine.document.palette.as_ref().unwrap().transparent,
            |tile| tile[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize],
        )
}

fn rendered(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    let bytes = engine
        .export_image(ExportOptions {
            transparent: true,
            ..Default::default()
        })
        .unwrap();
    let mut reader = png::Decoder::new(bytes.as_slice()).read_info().unwrap();
    let mut pixels = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut pixels).unwrap();
    let offset = ((y * info.width + x) * 4) as usize;
    pixels[offset..offset + 4].try_into().unwrap()
}

fn brush(index: Option<u8>, eraser: bool, raster: BrushRaster) -> Brush {
    Brush {
        size: 1.0,
        size_pressure: 0.0,
        opacity: 1.0,
        index,
        eraser,
        raster,
        ..Default::default()
    }
}

fn stroke(engine: &mut Engine, brush: Brush, points: &[Sample], batch: usize) {
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    for points in points.chunks(batch) {
        engine.samples(points).unwrap();
    }
    engine.command(Command::End).unwrap();
}

fn sample(x: f32, y: f32) -> Sample {
    Sample {
        x,
        y,
        pressure: 1.0,
    }
}

fn atomic(engine: &mut Engine, value: Value) {
    let state = engine.state();
    let saved = engine.save().unwrap();
    assert!(command(engine, value).is_err());
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), saved);
}

#[test]
fn indexed_creation_validates_complete_palette_and_keeps_old_new_rgba() {
    let mut engine = create(128, 64);
    assert_eq!(engine.state()["colorMode"], "indexed");
    assert_eq!(engine.state()["indexedPalette"], json!(palette()));
    assert!(!engine.state()["canUndo"].as_bool().unwrap());
    for palette in [
        json!({"colors":[[0,0,0,0]],"transparent":0,"order":[0]}),
        json!({"colors":[[0,0,0,255],[255,255,255,255]],"transparent":0,"order":[0,1]}),
        json!({"colors":[[0,0,0,0],[255,255,255,255]],"transparent":0,"order":[0,0]}),
        json!({"colors":[[0,0,0,0],[255,255,255,255]],"transparent":2,"order":[0,1]}),
    ] {
        atomic(
            &mut engine,
            json!({"type":"new_indexed","width":16,"height":16,"palette":palette}),
        );
    }
    engine
        .command(Command::New {
            width: 32,
            height: 32,
        })
        .unwrap();
    assert_eq!(engine.state()["colorMode"], "rgba");
    assert!(engine.state()["indexedPalette"].is_null());
}

#[test]
fn pixel_perfect_indices_are_batch_consistent_and_cancel_undo_redo_exact() {
    let points = [
        sample(124.1, 2.9),
        sample(125.3, 2.8),
        sample(125.2, 3.4),
        sample(127.7, 5.6),
        sample(132.3, 7.4),
    ];
    let mut whole = create(256, 32);
    let empty = whole.save().unwrap();
    let mut batches = create(256, 32);
    stroke(
        &mut whole,
        brush(Some(2), false, BrushRaster::PixelPerfect),
        &points,
        points.len(),
    );
    stroke(
        &mut batches,
        brush(Some(2), false, BrushRaster::PixelPerfect),
        &points,
        1,
    );
    assert_eq!(whole.save().unwrap(), batches.save().unwrap());
    assert_eq!(index(&whole, 0, 124, 2), 2);
    assert_eq!(index(&whole, 0, 125, 2), 0);
    assert_eq!(index(&whole, 0, 125, 3), 2);
    assert!(whole.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .values()
        .all(|tile| tile.len() == INDEX_TILE_BYTES));
    let painted = whole.save().unwrap();
    whole
        .command(Command::Begin {
            brush: brush(Some(3), false, BrushRaster::Pixel),
            assistant: None,
        })
        .unwrap();
    whole
        .samples(&[sample(2.0, 2.0), sample(30.0, 7.0)])
        .unwrap();
    whole.command(Command::Cancel).unwrap();
    assert_eq!(whole.save().unwrap(), painted);
    whole.command(Command::Undo).unwrap();
    assert_eq!(whole.save().unwrap(), empty);
    whole.command(Command::Redo).unwrap();
    assert_eq!(whole.save().unwrap(), painted);
    assert_eq!(rendered(&whole, 124, 2), [220, 30, 70, 255]);
}

#[test]
fn selected_pixel_eraser_and_symmetry_preserve_index_and_alpha_lock() {
    let mut engine = create(32, 24);
    stroke(
        &mut engine,
        Brush {
            symmetry: Symmetry {
                mode: SymmetryMode::Vertical,
                x: 0.5,
                y: 0.5,
            },
            ..brush(Some(2), false, BrushRaster::Pixel)
        },
        &[sample(4.2, 5.2)],
        1,
    );
    assert_eq!(index(&engine, 0, 4, 5), 2);
    assert_eq!(index(&engine, 0, 27, 5), 2);
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 0,
                top: 0,
                right: 16,
                bottom: 24,
            }),
        })
        .unwrap();
    stroke(
        &mut engine,
        brush(None, true, BrushRaster::Pixel),
        &[sample(4.0, 5.0), sample(27.0, 5.0)],
        1,
    );
    assert_eq!(index(&engine, 0, 4, 5), 0);
    assert_eq!(index(&engine, 0, 27, 5), 2);
    engine.command(Command::Select { rect: None }).unwrap();
    put(&mut engine, 0, 8, 8, 4);
    engine
        .command(Command::SetProtection {
            id: 1,
            alpha_locked: Some(true),
            locked: None,
        })
        .unwrap();
    let alpha = rendered(&engine, 8, 8)[3];
    stroke(
        &mut engine,
        brush(Some(3), false, BrushRaster::Pixel),
        &[sample(8.0, 8.0), sample(9.0, 8.0)],
        1,
    );
    assert_eq!(rendered(&engine, 8, 8)[3], alpha);
    assert_eq!(index(&engine, 0, 9, 8), 0);
    let state = engine.state();
    assert!(engine
        .command(Command::Begin {
            brush: brush(None, true, BrushRaster::Pixel),
            assistant: None
        })
        .is_err());
    assert_eq!(engine.state(), state);
}

#[test]
fn indexed_picker_returns_original_slot_even_with_duplicate_colors_and_hidden_or_masked_layer() {
    let mut engine = create(8, 8);
    put(&mut engine, 0, 2, 3, 2);
    command(&mut engine, json!({"type":"add_mask","mode":"hide"})).unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":false}),
    )
    .unwrap();
    let before = engine.save().unwrap();
    assert_eq!(
        engine.command(Command::Pick { x: 2, y: 3 }).unwrap(),
        json!({"color":[220,30,70],"index":2})
    );
    assert_eq!(
        engine.command(Command::Pick { x: 0, y: 0 }).unwrap(),
        json!({"color":[0,0,0],"index":0})
    );
    assert_eq!(engine.save().unwrap(), before);
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":true}),
    )
    .unwrap();
    let picked = engine.command(Command::Pick { x: 2, y: 3 }).unwrap();
    assert_eq!(picked, json!({"color":[0,0,0]}));
    assert!(picked.get("index").is_none());
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":false}),
    )
    .unwrap();
    engine.document.layers[0].visible = false;
    assert_eq!(
        engine.command(Command::Pick { x: 2, y: 3 }).unwrap()["index"],
        2
    );
}

#[test]
fn palette_edits_recolor_existing_pixels_without_replacing_index_buffers() {
    let mut engine = create(256, 32);
    put(&mut engine, 0, 130, 5, 2);
    let tile = engine.document.layers[0].raster().unwrap().tiles()[&(1, 0)].clone();
    let before = engine.save().unwrap();
    let revision = engine.state()["revision"].clone();
    command(
        &mut engine,
        json!({"type":"set_palette_color","index":2,"color":[20,160,90,255],"revision":revision}),
    )
    .unwrap();
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(1, 0)]
    ));
    assert_eq!(rendered(&engine, 130, 5), [20, 160, 90, 255]);
    let edited = engine.save().unwrap();
    let revision = engine.state()["revision"].clone();
    command(
        &mut engine,
        json!({"type":"reorder_palette","order":[4,3,2,1,0],"revision":revision}),
    )
    .unwrap();
    assert_eq!(index(&engine, 0, 130, 5), 2);
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(1, 0)]
    ));
    let state = engine.state();
    command(
        &mut engine,
        json!({"type":"reorder_palette","order":[4,3,2,1,0],"revision":state["revision"]}),
    )
    .unwrap();
    assert_eq!(engine.state(), state);
    atomic(
        &mut engine,
        json!({"type":"set_palette_color","index":0,"color":[1,2,3,255],"revision":state["revision"]}),
    );
    atomic(
        &mut engine,
        json!({"type":"reorder_palette","order":[0,1,2,3,3],"revision":state["revision"]}),
    );
    atomic(
        &mut engine,
        json!({"type":"set_palette_color","index":2,"color":[1,2,3,255],"revision":0}),
    );
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), edited);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn palette_addition_accepts_duplicate_entries_to_256_and_rejects_overflow() {
    let mut engine = create(8, 8);
    for _ in 5..256 {
        let revision = engine.state()["revision"].clone();
        command(
            &mut engine,
            json!({"type":"add_palette_color","color":[220,30,70,255],"revision":revision}),
        )
        .unwrap();
    }
    assert_eq!(engine.document.palette.as_ref().unwrap().colors.len(), 256);
    stroke(
        &mut engine,
        brush(Some(255), false, BrushRaster::Pixel),
        &[sample(2.0, 2.0)],
        1,
    );
    assert_eq!(index(&engine, 0, 2, 2), 255);
    let revision = engine.state()["revision"].clone();
    atomic(
        &mut engine,
        json!({"type":"add_palette_color","color":[1,2,3,255],"revision":revision}),
    );
}

#[test]
fn deleting_palette_color_replaces_all_layer_references_and_preserves_other_colors() {
    let mut engine = create(256, 128);
    put(&mut engine, 0, 1, 2, 1);
    put(&mut engine, 0, 129, 2, 2);
    put(&mut engine, 0, 130, 2, 3);
    engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
    let before = engine.save().unwrap();
    let revision = engine.state()["revision"].clone();
    command(
        &mut engine,
        json!({"type":"remove_palette_color","index":1,"replacement":3,"revision":revision}),
    )
    .unwrap();
    for layer in 0..2 {
        assert_eq!(index(&engine, layer, 1, 2), 2);
        assert_eq!(index(&engine, layer, 129, 2), 1);
        assert_eq!(index(&engine, layer, 130, 2), 2);
    }
    assert_eq!(
        engine.document.palette.as_ref().unwrap().colors[1],
        [220, 30, 70, 255]
    );
    assert_eq!(rendered(&engine, 129, 2), [220, 30, 70, 255]);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
    let revision = engine.state()["revision"].clone();
    atomic(
        &mut engine,
        json!({"type":"remove_palette_color","index":0,"replacement":1,"revision":revision}),
    );
}

#[test]
fn indexed_fill_compares_indices_even_when_rgb_is_identical_and_supports_aa_selection() {
    let mut engine = create(16, 8);
    for y in 0..8 {
        for x in 0..16 {
            put(
                &mut engine,
                0,
                x,
                y,
                if !(4..=7).contains(&x) { 1 } else { 2 },
            );
        }
    }
    command(
        &mut engine,
        json!({"type":"fill_indexed","x":0,"y":0,"index":3,"tolerance":0}),
    )
    .unwrap();
    assert_eq!(index(&engine, 0, 3, 1), 3);
    assert_eq!(index(&engine, 0, 4, 1), 2);
    assert_eq!(index(&engine, 0, 12, 1), 1);
    command(
        &mut engine,
        json!({"type":"fill_indexed","x":12,"y":1,"index":3,"tolerance":0,"contiguous":false}),
    )
    .unwrap();
    assert_eq!(index(&engine, 0, 12, 1), 3);
    let state = engine.state();
    command(
        &mut engine,
        json!({"type":"fill_indexed","x":12,"y":1,"index":3,"tolerance":0}),
    )
    .unwrap();
    assert_eq!(engine.state(), state);
    command(&mut engine,json!({"type":"select_shape","selection":{"kind":"ellipse","left":2,"top":0,"right":15,"bottom":8}})).unwrap();
    let selected = engine.state()["selection"].clone();
    let selection_id = engine.state()["selectionId"].clone();
    command(
        &mut engine,
        json!({"type":"fill_indexed","x":10,"y":3,"index":1,"tolerance":255,"contiguous":false}),
    )
    .unwrap();
    assert_eq!(index(&engine, 0, 0, 0), 3);
    assert_eq!(engine.state()["selection"], selected);
    assert_eq!(engine.state()["selectionId"], selection_id);
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .values()
        .flat_map(|tile| tile.iter())
        .all(|&index| index < 5));
}

#[test]
fn indexed_fill_opacity_combines_with_selection_coverage_and_preserves_history_on_noop() {
    let mut engine = Engine::new(8, 8).unwrap();
    let colors: Vec<_> = (0..=255).map(|alpha| [255, 0, 0, alpha]).collect();
    command(&mut engine, json!({"type":"new_indexed","width":8,"height":8,"palette":{"colors":colors,"transparent":0,"order":(0..=255).collect::<Vec<u8>>()}})).unwrap();
    command(&mut engine, json!({"type":"select_shape","selection":{"kind":"ellipse","left":0,"top":0,"right":8,"bottom":8}})).unwrap();
    let selected = engine.selection_frame();
    let selection_id = engine.state()["selectionId"].clone();
    let before = engine.save().unwrap();
    command(
        &mut engine,
        json!({"type":"fill_indexed","x":4,"y":4,"index":255,"tolerance":0,"opacity":0.5}),
    )
    .unwrap();
    for y in 0..8 {
        for x in 0..8 {
            let coverage = selected[16 + ((y * TILE_SIZE + x) * 4 + 3) as usize];
            assert_eq!(
                index(&engine, 0, x, y),
                (f32::from(coverage) * 0.5).round() as u8
            );
        }
    }
    assert_eq!(engine.state()["selectionId"], selection_id);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    let state = engine.state();
    command(
        &mut engine,
        json!({"type":"fill_indexed","x":4,"y":4,"index":255,"tolerance":0,"opacity":0.0}),
    )
    .unwrap();
    assert_eq!(engine.state(), state);
    for opacity in [-0.01, 1.01, f32::NAN, f32::INFINITY] {
        assert!(engine
            .command(Command::FillIndexed {
                x: 4,
                y: 4,
                index: 255,
                tolerance: 0,
                opacity,
                contiguous: true,
                merged: false
            })
            .is_err());
        assert_eq!(engine.state(), state);
        assert_eq!(engine.save().unwrap(), before);
    }
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
}

#[test]
fn indexed_brush_composites_semtransparent_palette_and_opacity_before_quantizing() {
    let mut engine = Engine::new(8, 8).unwrap();
    command(&mut engine, json!({"type":"new_indexed","width":8,"height":8,"palette":{"colors":[[0,0,0,0],[255,0,0,255],[0,0,255,255],[128,0,127,255],[255,0,0,128],[255,0,0,64]],"transparent":0,"order":[0,1,2,3,4,5]}})).unwrap();
    put(&mut engine, 0, 1, 1, 2);
    stroke(
        &mut engine,
        brush(Some(4), false, BrushRaster::Pixel),
        &[sample(1.0, 1.0)],
        1,
    );
    assert_eq!(index(&engine, 0, 1, 1), 3);
    stroke(
        &mut engine,
        Brush {
            opacity: 0.5,
            ..brush(Some(1), false, BrushRaster::Pixel)
        },
        &[sample(2.0, 1.0)],
        1,
    );
    assert_eq!(index(&engine, 0, 2, 1), 4);
    stroke(
        &mut engine,
        Brush {
            opacity: 0.5,
            ..brush(None, true, BrushRaster::Pixel)
        },
        &[sample(2.0, 1.0)],
        1,
    );
    assert_eq!(index(&engine, 0, 2, 1), 5);
    let state = engine.state();
    stroke(
        &mut engine,
        brush(Some(0), false, BrushRaster::Pixel),
        &[sample(1.0, 1.0)],
        1,
    );
    assert_eq!(engine.state(), state);
    put(&mut engine, 0, 3, 1, 2);
    command(
        &mut engine,
        json!({"type":"fill_indexed","x":3,"y":1,"index":1,"tolerance":0,"opacity":0.5}),
    )
    .unwrap();
    assert_eq!(index(&engine, 0, 3, 1), 3);
}

#[test]
fn nearest_resize_transform_translation_keep_original_indices_and_mask_metadata() {
    let mut engine = create(256, 128);
    put(&mut engine, 0, 127, 3, 1);
    put(&mut engine, 0, 128, 3, 2);
    command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask","id":1,"enabled":false,"linked":false}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":false}),
    )
    .unwrap();
    let mask = engine.document.layers[0].first_mask().cloned();
    engine
        .command(Command::TranslateLayer {
            mask_id: None,
            id: 1,
            dx: 2,
            dy: 1,
        })
        .unwrap();
    assert_eq!(index(&engine, 0, 129, 4), 1);
    assert_eq!(index(&engine, 0, 130, 4), 2);
    assert_eq!(engine.document.layers[0].first_mask().cloned(), mask);
    let revision = engine.state()["revision"].clone();
    command(&mut engine,json!({"type":"transform_layer","id":1,"revision":revision,"transform":{"width":2,"height":1,"flip_x":true,"filter":"nearest"}})).unwrap();
    assert_eq!(index(&engine, 0, 129, 4), 2);
    assert_eq!(index(&engine, 0, 130, 4), 1);
    let revision = engine.state()["revision"].as_u64().unwrap();
    engine
        .command(Command::ResizeImage {
            width: 512,
            height: 256,
            filter: ResampleFilter::Nearest,
            revision,
        })
        .unwrap();
    assert_eq!(index(&engine, 0, 258, 8), 2);
    assert_eq!(index(&engine, 0, 259, 9), 2);
    assert_eq!(index(&engine, 0, 260, 8), 1);
    let revision = engine.state()["revision"].clone();
    atomic(
        &mut engine,
        json!({"type":"resize_image","width":128,"height":64,"revision":revision,"filter":"lanczos3"}),
    );
}

#[test]
fn nearest_transform_preserves_nondefault_transparent_slots_for_future_palette_edits() {
    let mut engine = create(16, 8);
    let revision = engine.state()["revision"].clone();
    command(
        &mut engine,
        json!({"type":"add_palette_color","color":[30,80,120,0],"revision":revision}),
    )
    .unwrap();
    put(&mut engine, 0, 2, 3, 5);
    put(&mut engine, 0, 5, 3, 2);
    assert_eq!(
        engine.layer_bounds().unwrap(),
        Rect {
            left: 2,
            top: 3,
            right: 6,
            bottom: 4
        }
    );
    let before = engine.save().unwrap();
    let revision = engine.state()["revision"].clone();
    command(&mut engine,json!({"type":"transform_layer","id":1,"revision":revision,"transform":{"width":4,"height":1,"flip_x":true,"filter":"nearest"}})).unwrap();
    assert_eq!(index(&engine, 0, 2, 3), 2);
    assert_eq!(index(&engine, 0, 5, 3), 5);
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
    let revision = engine.state()["revision"].clone();
    command(
        &mut engine,
        json!({"type":"set_palette_color","index":5,"color":[30,80,120,255],"revision":revision}),
    )
    .unwrap();
    assert_eq!(rendered(&engine, 5, 3), [30, 80, 120, 255]);
}

#[test]
fn conversions_roundtrip_representable_rgba_mask_and_podor04_without_dual_storage() {
    let mut engine = create(256, 64);
    put(&mut engine, 0, 2, 4, 1);
    put(&mut engine, 0, 130, 4, 4);
    command(&mut engine, json!({"type":"add_mask","mode":"hide"})).unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask","id":1,"enabled":false,"linked":false}),
    )
    .unwrap();
    let mask = engine.document.layers[0].first_mask().cloned();
    let revision = engine.state()["revision"].clone();
    command(
        &mut engine,
        json!({"type":"convert_color_mode","mode":"rgba","revision":revision}),
    )
    .unwrap();
    assert!(engine.document.palette.is_none());
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .values()
        .all(|tile| tile.len() == TILE_BYTES));
    let rgba = engine.save().unwrap();
    let revision = engine.state()["revision"].clone();
    command(&mut engine,json!({"type":"convert_color_mode","mode":"indexed","palette":palette(),"revision":revision})).unwrap();
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .values()
        .all(|tile| tile.len() == INDEX_TILE_BYTES));
    assert_eq!(engine.document.layers[0].first_mask().cloned(), mask);
    assert_eq!(rendered(&engine, 2, 4), [220, 30, 70, 255]);
    assert_eq!(rendered(&engine, 130, 4)[3], 128);
    let saved = engine.save().unwrap();
    assert!(saved.starts_with(b"PODOR\x0c"));
    let mut restored = Engine::new(1, 1).unwrap();
    restored.load(&saved).unwrap();
    assert_eq!(restored.save().unwrap(), saved);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), rgba);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), saved);
}

#[test]
fn indexed_unsupported_tools_reject_atomically_but_mask_soft_paint_remains_independent() {
    let mut engine = create(32, 32);
    put(&mut engine, 0, 4, 4, 2);
    for value in [
        json!({"type":"fill","x":4,"y":4,"color":[1,2,3,255],"tolerance":0}),
        json!({"type":"tone","settings":{"brightness":0.2,"contrast":0.0,"saturation":0.0}}),
        json!({"type":"blur","sigma":1.0}),
        json!({"type":"merge_visible"}),
    ] {
        atomic(&mut engine, value);
    }
    assert!(engine
        .command(Command::Begin {
            brush: Brush::default(),
            assistant: None
        })
        .is_err());
    command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    let tile = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","enabled":true}),
    )
    .unwrap();
    stroke(
        &mut engine,
        Brush {
            size: 8.0,
            color: [0, 0, 0],
            opacity: 0.5,
            size_pressure: 0.0,
            ..Default::default()
        },
        &[sample(4.5, 4.5)],
        1,
    );
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
    assert!(engine.document.layers[0].first_mask().unwrap().sample(4, 4) < 255);
    command(
        &mut engine,
        json!({"type":"fill","x":20,"y":20,"color":[128,128,128,255],"tolerance":0}),
    )
    .unwrap();
    assert!(Arc::ptr_eq(
        &tile,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
}

#[test]
fn document_budget_failures_rollback_index_strokes_and_reject_oversized_rgba_conversion() {
    let mut engine = create(4096, 4096);
    let template = Arc::new(vec![255; MASK_TILE_BYTES]);
    let mask = LayerMask {
        bounds: MaskBounds {
            left: 0,
            top: 0,
            right: 4096,
            bottom: 4096,
        },
        default: 255,
        enabled: false,
        linked: false,
        tiles: (0..32)
            .flat_map(|y| (0..32).map(move |x| (x, y)))
            .map(|key| (key, template.clone()))
            .collect(),
    };
    for id in 1..=8 {
        let mut layer = Layer::new(id, format!("Layer {id}"));
        layer.content =
            podor_engine::model::LayerContent::Raster(RasterPlane::Indexed(BTreeMap::new()));
        layer.set_first_mask(Some(mask.clone()));
        if id == 1 {
            engine.document.layers[0] = layer;
        } else {
            engine.document.layers.push(layer);
        }
    }
    engine.document.next_id = 9;
    engine.document.assign_mask_ids().unwrap();
    engine.document.validate().unwrap();
    assert_eq!(engine.document.pixel_bytes(), MAX_DOCUMENT_BYTES);
    let state = engine.state();
    engine
        .command(Command::Begin {
            brush: brush(Some(1), false, BrushRaster::Pixel),
            assistant: None,
        })
        .unwrap();
    assert!(engine.samples(&[sample(2.0, 2.0)]).is_err());
    assert_eq!(engine.state(), state);
    assert!(engine
        .document
        .layers
        .iter()
        .all(|layer| layer.raster().unwrap().tiles().is_empty()));
    engine
        .command(Command::Begin {
            brush: brush(Some(1), false, BrushRaster::Pixel),
            assistant: None,
        })
        .unwrap();
    engine.command(Command::Cancel).unwrap();
    for layer in &mut engine.document.layers {
        layer.set_first_mask(None);
        layer.content = podor_engine::model::LayerContent::Raster(RasterPlane::Indexed(
            (0..32)
                .flat_map(|y| (0..32).map(move |x| (x, y)))
                .map(|key| (key, Arc::new(vec![1; INDEX_TILE_BYTES])))
                .collect(),
        ));
    }
    engine.document.validate().unwrap();
    let state = engine.state();
    let first = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    let revision = engine.state()["revision"].clone();
    assert!(command(
        &mut engine,
        json!({"type":"convert_color_mode","mode":"rgba","revision":revision})
    )
    .is_err());
    assert_eq!(engine.state(), state);
    assert!(Arc::ptr_eq(
        &first,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
}

#[test]
fn explicit_v3_migration_preserves_rgba_and_signed_independent_mask() {
    use bincode::Options;
    use std::io::Write;
    #[derive(serde::Serialize)]
    struct OldLayer {
        id: u32,
        name: String,
        visible: bool,
        opacity: f32,
        tiles: BTreeMap<TileKey, Tile>,
        blend: BlendMode,
        alpha_locked: bool,
        locked: bool,
        mask: Option<LayerMask>,
    }
    #[derive(serde::Serialize)]
    struct OldDocument {
        width: u32,
        height: u32,
        layers: Vec<OldLayer>,
        active: u32,
        next_id: u32,
    }
    let mut tile = vec![0; TILE_BYTES];
    tile[..4].copy_from_slice(&[40, 80, 120, 255]);
    let mask = LayerMask {
        bounds: MaskBounds {
            left: -3,
            top: 2,
            right: 6,
            bottom: 7,
        },
        default: 255,
        enabled: false,
        linked: false,
        tiles: BTreeMap::from([((0, 0), Arc::new(vec![80; MASK_TILE_BYTES]))]),
    };
    let old = OldDocument {
        width: 32,
        height: 24,
        active: 1,
        next_id: 2,
        layers: vec![OldLayer {
            id: 1,
            name: "Old layer".into(),
            visible: true,
            opacity: 0.7,
            tiles: BTreeMap::from([((0, 0), Arc::new(tile))]),
            blend: BlendMode::Multiply,
            alpha_locked: true,
            locked: true,
            mask: Some(mask.clone()),
        }],
    };
    let decoded = bincode::DefaultOptions::new().serialize(&old).unwrap();
    let mut encoder =
        flate2::write::GzEncoder::new(b"PODOR\x03".to_vec(), flate2::Compression::fast());
    encoder.write_all(&decoded).unwrap();
    let bytes = encoder.finish().unwrap();
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&bytes).unwrap();
    assert!(engine.document.palette.is_none());
    assert_eq!(engine.document.layers[0].first_mask(), Some(&mask));
    assert!(engine.document.layers[0].locked && engine.document.layers[0].alpha_locked);
    assert_eq!(
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)][..4],
        &[40, 80, 120, 255]
    );
    assert!(engine.save().unwrap().starts_with(b"PODOR\x0c"));
}
