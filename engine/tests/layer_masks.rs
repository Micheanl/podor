use podor_engine::{model::*, Command, Engine, ExportOptions};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn artwork() -> Engine {
    let mut engine = Engine::new(32, 24).unwrap();
    command(
        &mut engine,
        json!({"type":"fill","x":0,"y":0,"color":[200,80,40,255],"tolerance":0}),
    )
    .unwrap();
    engine
}

fn rendered(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    let bytes = engine
        .export_image(ExportOptions {
            transparent: true,
            ..ExportOptions::default()
        })
        .unwrap();
    let mut reader = png::Decoder::new(bytes.as_slice()).read_info().unwrap();
    let width = reader.info().width;
    let mut pixels = vec![0; reader.output_buffer_size()];
    reader.next_frame(&mut pixels).unwrap();
    let offset = ((y * width + x) * 4) as usize;
    pixels[offset..offset + 4].try_into().unwrap()
}

fn mask(engine: &Engine, x: i32, y: i32) -> u8 {
    engine.document.layers[0].first_mask().unwrap().sample(x, y)
}

fn draw(engine: &mut Engine, color: [u8; 3], opacity: f32, eraser: bool, x: f32, y: f32) {
    engine
        .command(Command::Begin {
            brush: Brush {
                color,
                opacity,
                eraser,
                size: 1.0,
                size_pressure: 0.0,
                opacity_pressure: 0.0,
                stabilization: 0.0,
                raster: BrushRaster::Pixel,
                ..Brush::default()
            },
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x,
            y,
            pressure: 1.0,
        }])
        .unwrap();
    engine.command(Command::End).unwrap();
}

fn assert_same_pixels(engine: &Engine, original: &BTreeMap<TileKey, Tile>) {
    assert_eq!(
        engine.document.layers[0].raster().unwrap().tiles().len(),
        original.len()
    );
    for (key, tile) in original {
        assert!(Arc::ptr_eq(
            tile,
            &engine.document.layers[0].raster().unwrap().tiles()[key]
        ));
    }
}

#[test]
fn independent_mask_brushes_reveal_hide_and_preserve_original_pixels() {
    let mut engine = artwork();
    let original = engine.document.layers[0].raster().unwrap().tiles().clone();
    command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    assert_eq!(engine.state()["maskEditing"], true);
    assert_eq!(mask(&engine, 8, 9), 255);
    assert_eq!(rendered(&engine, 8, 9), [200, 80, 40, 255]);
    draw(&mut engine, [0; 3], 1.0, false, 8.5, 9.5);
    assert_eq!(mask(&engine, 8, 9), 0);
    assert_eq!(mask(&engine, 9, 9), 255);
    assert_eq!(rendered(&engine, 8, 9), [0; 4]);
    assert_same_pixels(&engine, &original);
    draw(&mut engine, [255; 3], 0.5, false, 8.5, 9.5);
    assert!((127..=128).contains(&mask(&engine, 8, 9)));
    assert!((127..=128).contains(&rendered(&engine, 8, 9)[3]));
    assert_same_pixels(&engine, &original);
    draw(&mut engine, [255; 3], 1.0, true, 8.5, 9.5);
    assert_eq!(mask(&engine, 8, 9), 0);
    assert_same_pixels(&engine, &original);
    command(
        &mut engine,
        json!({"type":"set_mask","id":1,"enabled":false}),
    )
    .unwrap();
    assert_eq!(rendered(&engine, 8, 9), [200, 80, 40, 255]);
    command(&mut engine, json!({"type":"delete_mask","id":1})).unwrap();
    assert_eq!(engine.state()["maskEditing"], false);
    assert!(engine.document.layers[0].masks.is_empty());
    assert_same_pixels(&engine, &original);
}

#[test]
fn mask_target_changes_are_atomic_and_do_not_enter_history() {
    let mut engine = artwork();
    let state = engine.state();
    assert!(command(
        &mut engine,
        json!({"type":"set_mask_editing","id":99,"enabled":true})
    )
    .is_err());
    assert_eq!(engine.state(), state);
    assert!(command(
        &mut engine,
        json!({"type":"set_mask_editing","id":1,"enabled":true})
    )
    .is_err());
    assert_eq!(engine.state(), state);
    command(&mut engine, json!({"type":"add_mask","mode":"hide"})).unwrap();
    let state = engine.state();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","id":1,"enabled":false}),
    )
    .unwrap();
    assert_eq!(engine.state()["revision"], state["revision"]);
    assert_eq!(engine.state()["contentId"], state["contentId"]);
    assert_eq!(engine.state()["maskEditing"], false);
    command(
        &mut engine,
        json!({"type":"set_mask_editing","id":1,"enabled":true}),
    )
    .unwrap();
    assert_eq!(engine.state(), state);
    engine.command(Command::AddLayer).unwrap();
    command(
        &mut engine,
        json!({"type":"set_mask_editing","id":1,"enabled":true}),
    )
    .unwrap();
    assert_eq!(engine.document.active, 1);
    assert_eq!(engine.state()["maskEditing"], true);
    command(&mut engine, json!({"type":"select_layer","id":2})).unwrap();
    assert_eq!(engine.state()["maskEditing"], false);
}

#[test]
fn selection_masks_retain_antialiasing_and_undo_restores_mask_edits() {
    let mut engine = artwork();
    assert!(command(&mut engine, json!({"type":"add_mask","mode":"selection"})).is_err());
    command(
        &mut engine,
        json!({
            "type":"select_shape","selection":{
                "left":3,"top":2,"right":26,"bottom":21,
                "kind":"ellipse","points":[]
            }
        }),
    )
    .unwrap();
    let selection = engine.state()["selection"].clone();
    let frame = engine.selection_frame();
    command(&mut engine, json!({"type":"add_mask","mode":"selection"})).unwrap();
    let mut partial = 0;
    for y in 0..24 {
        for x in 0..32 {
            let expected = frame[16 + ((y * TILE_SIZE + x) * 4 + 3) as usize];
            assert_eq!(mask(&engine, x as i32, y as i32), expected);
            partial += usize::from(expected > 0 && expected < 255);
        }
    }
    assert!(partial > 0);
    assert_eq!(engine.state()["selection"], selection);
    let before = engine.save().unwrap();
    command(&mut engine, json!({"type":"invert_mask","id":1})).unwrap();
    let after = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
    assert_eq!(engine.state()["selection"], selection);
}

#[test]
fn fill_and_apply_operate_on_the_independent_gray_plane() {
    let mut engine = artwork();
    let original = engine.document.layers[0].raster().unwrap().tiles().clone();
    command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    command(
        &mut engine,
        json!({
            "type":"fill","x":2,"y":2,"color":[128,128,128,255],"tolerance":0
        }),
    )
    .unwrap();
    assert_eq!(mask(&engine, 2, 2), 128);
    assert_eq!(mask(&engine, 30, 20), 128);
    assert_same_pixels(&engine, &original);
    let before = engine.save().unwrap();
    let composite = rendered(&engine, 12, 12);
    command(&mut engine, json!({"type":"apply_mask","id":1})).unwrap();
    assert!(engine.document.layers[0].masks.is_empty());
    assert_eq!(engine.state()["maskEditing"], false);
    assert_eq!(rendered(&engine, 12, 12), composite);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_same_pixels(&engine, &original);
    command(
        &mut engine,
        json!({"type":"set_mask_editing","id":1,"enabled":true}),
    )
    .unwrap();
    let state = engine.state();
    assert!(command(
        &mut engine,
        json!({
            "type":"fill","x":2,"y":2,"color":[0,0,0,255],"tolerance":0,"merged":true
        })
    )
    .is_err());
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn mask_persists_with_flags_and_gray_pixels_in_new_project_format() {
    let mut engine = artwork();
    command(&mut engine, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    draw(&mut engine, [128; 3], 1.0, false, 8.5, 9.5);
    command(
        &mut engine,
        json!({"type":"set_mask","id":1,"enabled":false,"linked":false}),
    )
    .unwrap();
    let bytes = engine.save().unwrap();
    assert_eq!(&bytes[..6], b"PODOR\x0c");
    let mut restored = Engine::new(1, 1).unwrap();
    restored.load(&bytes).unwrap();
    assert_eq!(restored.save().unwrap(), bytes);
    assert_eq!(mask(&restored, 8, 9), 128);
    assert_eq!(
        restored.state()["layers"][0]["mask"],
        json!({"id":1,"name":"Mask 1","enabled":false,"linked":false})
    );
    assert_eq!(restored.state()["maskEditing"], false);
    assert_eq!(rendered(&restored, 8, 9), [200, 80, 40, 255]);
}

#[derive(serde::Serialize)]
struct LegacyLayerV1 {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    tiles: BTreeMap<TileKey, Tile>,
    blend: BlendMode,
}

#[derive(serde::Serialize)]
struct LegacyLayerV2 {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    tiles: BTreeMap<TileKey, Tile>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
}

#[derive(serde::Serialize)]
struct LegacyDocument<T> {
    width: u32,
    height: u32,
    layers: Vec<T>,
    active: u32,
    next_id: u32,
}

fn legacy<T: serde::Serialize>(version: u8, layer: T) -> Vec<u8> {
    use bincode::Options;
    use std::io::Write;
    let document = LegacyDocument {
        width: 32,
        height: 24,
        layers: vec![layer],
        active: 1,
        next_id: 2,
    };
    let bytes = bincode::DefaultOptions::new().serialize(&document).unwrap();
    let mut header = b"PODOR".to_vec();
    header.push(version);
    let mut gzip = flate2::write::GzEncoder::new(header, flate2::Compression::fast());
    gzip.write_all(&bytes).unwrap();
    gzip.finish().unwrap()
}

#[test]
fn explicit_v1_and_v2_migrations_preserve_artwork_and_protection() {
    let original = artwork().document.layers.remove(0);
    let v1 = legacy(
        1,
        LegacyLayerV1 {
            id: original.id,
            name: original.name.clone(),
            visible: original.visible,
            opacity: original.opacity,
            tiles: original.raster().unwrap().tiles().clone(),
            blend: original.blend,
        },
    );
    let v2 = legacy(
        2,
        LegacyLayerV2 {
            id: original.id,
            name: original.name.clone(),
            visible: original.visible,
            opacity: original.opacity,
            tiles: original.raster().unwrap().tiles().clone(),
            blend: original.blend,
            alpha_locked: true,
            locked: true,
        },
    );
    for (bytes, protected) in [(v1, false), (v2, true)] {
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&bytes).unwrap();
        assert!(engine.document.layers[0].masks.is_empty());
        assert_eq!(engine.document.layers[0].alpha_locked, protected);
        assert_eq!(engine.document.layers[0].locked, protected);
        assert_eq!(rendered(&engine, 8, 9), [200, 80, 40, 255]);
        assert_eq!(&engine.save().unwrap()[..6], b"PODOR\x0c");
    }
}
