use podor_engine::{model::*, Command, CopyMode, Engine};
use serde_json::{json, Value};

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn rectangle(left: u32, top: u32, right: u32, bottom: u32) -> Value {
    json!({"left":left,"top":top,"right":right,"bottom":bottom})
}

fn select(engine: &mut Engine, selection: Value, mode: &str) {
    command(
        engine,
        json!({"type":"combine_selection","selection":selection,"mode":mode}),
    )
    .unwrap();
}

fn mask(engine: &Engine) -> Vec<u8> {
    let mut mask = vec![0; (engine.document.width * engine.document.height) as usize];
    let data = engine.selection_frame();
    let read = |offset| u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap());
    let count = read(4);
    if count == 0 && engine.state()["selection"]["empty"] == false {
        let selection = &engine.state()["selection"];
        for y in selection["top"].as_u64().unwrap()..selection["bottom"].as_u64().unwrap() {
            for x in selection["left"].as_u64().unwrap()..selection["right"].as_u64().unwrap() {
                mask[y as usize * engine.document.width as usize + x as usize] = 255;
            }
        }
    }
    for index in 0..count as usize {
        let offset = 8 + index * (8 + TILE_BYTES);
        let left = read(offset) * TILE_SIZE;
        let top = read(offset + 4) * TILE_SIZE;
        for y in top..(top + TILE_SIZE).min(engine.document.height) {
            for x in left..(left + TILE_SIZE).min(engine.document.width) {
                mask[(y * engine.document.width + x) as usize] =
                    data[offset + 8 + (((y - top) * TILE_SIZE + x - left) * 4) as usize + 3];
            }
        }
    }
    mask
}

#[test]
fn boolean_modes_preserve_fractional_coverage_and_do_not_modify_artwork() {
    let a = json!({"kind":"ellipse","left":3,"top":4,"right":43,"bottom":38});
    let b = json!({"kind":"lasso","left":0,"top":0,"right":64,"bottom":48,"points":[{"x":12.3,"y":2.1},{"x":60.7,"y":30.8},{"x":15.8,"y":45.4}]});
    let mut engine = Engine::new(64, 48).unwrap();
    let original = engine.save().unwrap();
    select(&mut engine, a.clone(), "replace");
    let first = mask(&engine);
    select(&mut engine, b.clone(), "replace");
    let second = mask(&engine);
    assert!(first.iter().any(|&a| a > 0 && a < 255));
    for mode in ["add", "subtract", "intersect"] {
        select(&mut engine, a.clone(), "replace");
        select(&mut engine, b.clone(), mode);
        let actual = mask(&engine);
        for ((&a, &b), &value) in first.iter().zip(&second).zip(&actual) {
            assert_eq!(
                value,
                match mode {
                    "add" => a.max(b),
                    "subtract" => a.saturating_sub(b),
                    _ => a.min(b),
                }
            );
        }
        assert_eq!(engine.state()["revision"], 0);
        assert_eq!(engine.save().unwrap(), original);
        assert!(!engine.state()["canUndo"].as_bool().unwrap());
    }
    let before = mask(&engine);
    engine.command(Command::InvertSelection).unwrap();
    assert_eq!(
        mask(&engine),
        before.iter().map(|&a| 255 - a).collect::<Vec<_>>()
    );
    engine.command(Command::InvertSelection).unwrap();
    assert_eq!(mask(&engine), before);
}

#[test]
fn subtraction_keeps_holes_in_strokes_copy_gradient_and_saved_pixels() {
    let mut engine = Engine::new(64, 64).unwrap();
    select(&mut engine, rectangle(4, 4, 60, 60), "replace");
    select(&mut engine, rectangle(20, 20, 40, 40), "subtract");
    let outline = engine.selection_outline();
    assert_eq!(&outline[..4], &0u32.to_le_bytes());
    assert_eq!((outline.len() - 4) / 16, 8);
    let selected = mask(&engine);
    engine
        .command(Command::Begin {
            brush: Brush {
                size: 200.0,
                hardness: 1.0,
                color: [180, 30, 60],
                ..Default::default()
            },
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x: 32.0,
            y: 32.0,
            pressure: 1.0,
        }])
        .unwrap();
    engine.command(Command::End).unwrap();
    let tile = engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .get(&(0, 0))
        .unwrap();
    for y in 0..64 {
        for x in 0..64 {
            assert_eq!(tile[(y * 128 + x) * 4 + 3], selected[y * 64 + x]);
        }
    }
    let saved = engine.save().unwrap();
    let copy = engine.copy_selection(CopyMode::Layer).unwrap();
    let mut reader = png::Decoder::new(std::io::Cursor::new(&copy[16..]))
        .read_info()
        .unwrap();
    let mut image = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut image).unwrap();
    assert_eq!((info.width, info.height), (56, 56));
    assert_eq!(image[(20 * 56 + 20) * 4 + 3], 0);
    engine.command(Command::Undo).unwrap();
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), saved);
    let revision = engine.state()["revision"].clone();
    command(&mut engine, json!({"type":"gradient","id":1,"revision":revision,"settings":{"start":[0,0],"end":[64,64],"from":[0,0,0,255],"to":[255,255,255,255],"opacity":1,"shape":"linear"}})).unwrap();
    assert_eq!(
        engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)][(30 * 128 + 30) * 4 + 3],
        0
    );
    let project = engine.save().unwrap();
    engine.load(&project).unwrap();
    assert_eq!(engine.state()["selection"], Value::Null);
    assert_eq!(
        engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)][(30 * 128 + 30) * 4 + 3],
        0
    );
}

#[test]
fn empty_selection_never_becomes_unrestricted_painting_or_creates_history() {
    let mut engine = Engine::new(64, 64).unwrap();
    select(&mut engine, rectangle(2, 2, 12, 12), "replace");
    select(&mut engine, rectangle(40, 40, 50, 50), "intersect");
    assert_eq!(engine.state()["selection"]["empty"], true);
    let before = engine.state();
    let saved = engine.save().unwrap();
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color: [255, 0, 0, 255],
            tolerance: 255,
        })
        .unwrap();
    command(
        &mut engine,
        json!({"type":"tone","settings":{"brightness":0.5,"contrast":0,"saturation":0}}),
    )
    .unwrap();
    engine.command(Command::Blur { sigma: 4.0 }).unwrap();
    engine
        .command(Command::Begin {
            brush: Brush::default(),
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x: 0.0,
            y: 0.0,
            pressure: 1.0,
        }])
        .unwrap();
    engine.command(Command::End).unwrap();
    assert_eq!(engine.state(), before);
    assert_eq!(engine.save().unwrap(), saved);
    assert!(engine.copy_selection(CopyMode::Visible).is_err());
    engine.command(Command::InvertSelection).unwrap();
    assert!(mask(&engine).iter().all(|&a| a == 255));
    select(&mut engine, rectangle(0, 0, 64, 64), "subtract");
    assert_eq!(engine.state()["selection"]["empty"], true);
    select(&mut engine, rectangle(10, 10, 20, 20), "add");
    assert_eq!(mask(&engine).iter().filter(|&&a| a != 0).count(), 100);
}

#[test]
fn invalid_modes_and_shapes_leave_the_existing_selection_in_place() {
    let mut engine = Engine::new(64, 48).unwrap();
    for mode in ["subtract", "intersect"] {
        assert!(command(
            &mut engine,
            json!({"type":"combine_selection","mode":mode,"selection":rectangle(1,1,10,10)})
        )
        .is_err());
        assert_eq!(engine.state()["selection"], Value::Null);
    }
    select(&mut engine, rectangle(4, 4, 20, 20), "add");
    let before = engine.state();
    assert!(command(
        &mut engine,
        json!({"type":"combine_selection","mode":"add","selection":rectangle(0,0,100,100)})
    )
    .is_err());
    assert_eq!(engine.state(), before);
}

#[test]
fn complex_outline_falls_back_to_the_exact_tiled_mask() {
    let mut points = vec![
        json!({"x":0,"y":0}),
        json!({"x":512,"y":0}),
        json!({"x":512,"y":512}),
    ];
    for x in (1..512).rev() {
        let (first, last) = if x % 2 == 1 { (512, 1) } else { (1, 512) };
        points.push(json!({"x":x,"y":first}));
        points.push(json!({"x":x,"y":last}));
    }
    points.push(json!({"x":0,"y":1}));
    let transpose: Vec<_> = points
        .iter()
        .map(|p| json!({"x":p["y"],"y":p["x"]}))
        .collect();
    for (width, height, dx, dy, tile_count) in [(512, 512, 0, 0, 1), (768, 640, 173, 91, 4)] {
        let mut engine = Engine::new(width, height).unwrap();
        for (points, mode) in [(&points, "replace"), (&transpose, "intersect")] {
            let points: Vec<_> = points.iter().map(|p| {
                json!({"x":p["x"].as_u64().unwrap() + dx,"y":p["y"].as_u64().unwrap() + dy})
            }).collect();
            select(
                &mut engine,
                json!({"kind":"lasso","left":0,"top":0,"right":width,"bottom":height,"points":points}),
                mode,
            );
        }
        let outline = engine.selection_outline();
        let read = |offset| u32::from_le_bytes(outline[offset..offset + 4].try_into().unwrap());
        assert_eq!(read(0), 1);
        assert_eq!(read(4), 512);
        assert_eq!(read(8), tile_count);
        let size = read(4) as usize;
        let mut preview = vec![0; (width * height) as usize];
        for tile in 0..tile_count as usize {
            let offset = 12 + tile * (8 + size * size);
            let left = read(offset) as usize * size;
            let top = read(offset + 4) as usize * size;
            for y in top..(top + size).min(height as usize) {
                for x in left..(left + size).min(width as usize) {
                    preview[y * width as usize + x] =
                        outline[offset + 8 + (y - top) * size + x - left];
                }
            }
        }
        let values = mask(&engine);
        assert_eq!(preview, values);
        assert!(values.contains(&0) && values.contains(&255));
        assert_eq!(outline.len(), 12 + tile_count as usize * (8 + size * size));
    }
}
