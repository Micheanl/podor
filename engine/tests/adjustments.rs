use podor_engine::{model::*, Command, Engine};
use serde_json::json;
use std::sync::Arc;

fn command(engine: &mut Engine, value: serde_json::Value) {
    engine
        .command(serde_json::from_value(value).unwrap())
        .unwrap();
}

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    let layer = engine
        .document
        .layers
        .iter()
        .find(|l| l.id == engine.document.active)
        .unwrap();
    layer
        .tiles
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |t| {
            let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            t[i..i + 4].try_into().unwrap()
        })
}

fn put(engine: &mut Engine, x: u32, y: u32, color: [u8; 4]) {
    let tile = engine
        .document
        .active_mut()
        .tiles
        .entry((x / TILE_SIZE, y / TILE_SIZE))
        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
    let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    Arc::make_mut(tile)[i..i + 4].copy_from_slice(&color);
}

#[test]
fn flood_fill_stops_at_boundaries_and_undo_restores_transparency() {
    let mut e = Engine::new(256, 128).unwrap();
    for y in 0..128 {
        put(&mut e, 127, y, [0, 0, 0, 255]);
    }
    command(
        &mut e,
        json!({"type":"fill","x":0,"y":0,"color":[200,60,80,128],"tolerance":0}),
    );
    assert_eq!(pixel(&e, 126, 127), [100, 30, 40, 128]);
    assert_eq!(pixel(&e, 127, 50), [0, 0, 0, 255]);
    assert_eq!(pixel(&e, 128, 50), [0; 4]);
    e.command(Command::Undo).unwrap();
    assert_eq!(pixel(&e, 10, 10), [0; 4]);
    assert_eq!(pixel(&e, 127, 50), [0, 0, 0, 255]);
}

#[test]
fn tolerance_and_selection_restrict_fill() {
    let mut e = Engine::new(8, 8).unwrap();
    put(&mut e, 1, 1, [80, 80, 80, 255]);
    put(&mut e, 2, 1, [90, 80, 80, 255]);
    put(&mut e, 3, 1, [110, 80, 80, 255]);
    command(
        &mut e,
        json!({"type":"select","rect":{"left":1,"top":1,"right":4,"bottom":2}}),
    );
    command(
        &mut e,
        json!({"type":"fill","x":1,"y":1,"color":[255,0,0,255],"tolerance":10}),
    );
    assert_eq!(pixel(&e, 2, 1), [255, 0, 0, 255]);
    assert_eq!(pixel(&e, 3, 1), [110, 80, 80, 255]);
    assert_eq!(pixel(&e, 0, 0), [0; 4]);
    let before = e.save().unwrap();
    assert!(e
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: [255; 4],
            tolerance: 255
        })
        .is_err());
    assert_eq!(e.save().unwrap(), before);
}

#[test]
fn selected_brush_does_not_change_pixels_outside_rectangle() {
    let mut e = Engine::new(32, 32).unwrap();
    e.command(Command::Select {
        rect: Some(Rect {
            left: 16,
            top: 0,
            right: 32,
            bottom: 32,
        }),
    })
    .unwrap();
    e.command(Command::Begin {
        brush: Brush {
            size: 24.0,
            opacity: 1.0,
            hardness: 1.0,
            color: [139, 41, 66],
            eraser: false,
            ..Brush::default()
        },
    })
    .unwrap();
    e.samples(&[Sample {
        x: 16.0,
        y: 16.0,
        pressure: 1.0,
    }])
    .unwrap();
    e.command(Command::End).unwrap();
    assert_eq!(pixel(&e, 15, 16), [0; 4]);
    assert_eq!(pixel(&e, 16, 16), [139, 41, 66, 255]);
}

#[test]
fn tone_preserves_alpha_and_selection_and_supports_undo() {
    let mut e = Engine::new(128, 128).unwrap();
    put(&mut e, 1, 1, [100, 30, 40, 128]);
    put(&mut e, 2, 1, [100, 30, 40, 128]);
    let before = e.save().unwrap();
    command(
        &mut e,
        json!({"type":"select","rect":{"left":1,"top":1,"right":2,"bottom":2}}),
    );
    command(
        &mut e,
        json!({"type":"tone","settings":{"brightness":0,"contrast":0,"saturation":-1}}),
    );
    let p = pixel(&e, 1, 1);
    assert_eq!(p[0], p[1]);
    assert_eq!(p[1], p[2]);
    assert_eq!(p[3], 128);
    assert_eq!(pixel(&e, 2, 1), [100, 30, 40, 128]);
    assert_eq!(pixel(&e, 0, 0), [0; 4]);
    e.command(Command::Undo).unwrap();
    assert_eq!(e.save().unwrap(), before);
}

#[test]
fn blur_crosses_tile_edges_without_dark_alpha_fringes() {
    let mut e = Engine::new(256, 128).unwrap();
    for y in 30..90 {
        for x in 110..146 {
            put(&mut e, x, y, [255, 0, 0, 255]);
        }
    }
    let original = e.save().unwrap();
    command(&mut e, json!({"type":"blur","sigma":4.0}));
    assert!(pixel(&e, 108, 60)[3] > 0);
    for y in 0..128 {
        for x in 0..256 {
            let p = pixel(&e, x, y);
            assert_eq!(p[0], p[3]);
            assert_eq!(p[1], 0);
            assert_eq!(p, pixel(&e, 255 - x, y));
        }
    }
    e.command(Command::Undo).unwrap();
    assert_eq!(e.save().unwrap(), original);
}

#[test]
fn blur_selection_uses_neighbor_pixels_but_only_writes_selection() {
    let mut e = Engine::new(16, 16).unwrap();
    for y in 0..16 {
        for x in 0..8 {
            put(&mut e, x, y, [255; 4]);
        }
    }
    command(
        &mut e,
        json!({"type":"select","rect":{"left":8,"top":0,"right":16,"bottom":16}}),
    );
    command(&mut e, json!({"type":"blur","sigma":2.0}));
    assert_eq!(pixel(&e, 7, 8), [255; 4]);
    assert!(pixel(&e, 8, 8)[3] > 0);
    assert!(pixel(&e, 8, 8)[3] < 255);
    assert!(e.command(Command::Blur { sigma: f32::NAN }).is_err());
}

#[test]
fn png_import_handles_alpha_and_rejects_bad_data_without_replacing_document() {
    let mut bytes = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut bytes, 2, 1);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().unwrap();
        writer
            .write_image_data(&[200, 60, 80, 128, 255, 255, 255, 0])
            .unwrap();
    }
    let mut e = Engine::new(8, 8).unwrap();
    e.load(&bytes).unwrap();
    assert_eq!(e.document.width, 2);
    assert_eq!(pixel(&e, 0, 0), [100, 30, 40, 128]);
    assert_eq!(pixel(&e, 1, 0), [0; 4]);
    let before = e.save().unwrap();
    assert!(e.load(&bytes[..32]).is_err());
    assert_eq!(e.save().unwrap(), before);
}
