use podor_engine::{
    model::{BlendMode, Brush, Layer, Sample, TILE_BYTES, TILE_SIZE},
    Command, Engine,
};
use std::{collections::BTreeMap, sync::Arc};

const MODES: [BlendMode; 8] = [
    BlendMode::Normal,
    BlendMode::Multiply,
    BlendMode::Screen,
    BlendMode::Overlay,
    BlendMode::SoftLight,
    BlendMode::Darken,
    BlendMode::Lighten,
    BlendMode::Difference,
];

fn artwork(mode: BlendMode, active: u32) -> Engine {
    let mut engine = Engine::new(261, 137).unwrap();
    engine.document.layers.clear();
    for id in 1..=4 {
        let mut layer = Layer::new(id, format!("Layer {id}"));
        layer.blend = if id == 2 { mode } else { BlendMode::Normal };
        layer.opacity = if id == active { 0.7 } else { 1.0 };
        layer.visible = id != 3;
        for key in [(0, 0), (1, 0), (2, 0), (1, 1)] {
            if id == 1 && key == (1, 0) {
                continue;
            }
            let mut pixels = vec![0; TILE_BYTES];
            for (index, pixel) in pixels.as_chunks_mut::<4>().0.iter_mut().enumerate() {
                let alpha = ((index + id as usize * 43) % 256) as u8;
                pixel.copy_from_slice(&[alpha / 2, alpha / 3, alpha / 4, alpha]);
            }
            layer.tiles.insert(key, Arc::new(pixels));
        }
        engine.document.layers.push(layer);
    }
    engine.document.active = active;
    engine.document.next_id = 5;
    engine
}

fn tiles(bytes: &[u8]) -> BTreeMap<(u32, u32), &[u8]> {
    bytes[16..]
        .as_chunks::<{ 8 + TILE_BYTES }>()
        .0
        .iter()
        .map(|tile| {
            (
                (
                    u32::from_le_bytes(tile[..4].try_into().unwrap()),
                    u32::from_le_bytes(tile[4..8].try_into().unwrap()),
                ),
                &tile[8..],
            )
        })
        .collect()
}

fn matches_full_composite(engine: &mut Engine) {
    let frame = engine.frame();
    let mut reference = Engine::new(1, 1).unwrap();
    reference.load(&engine.save().unwrap()).unwrap();
    let reference = reference.frame();
    let expected = tiles(&reference);
    for (key, pixels) in tiles(&frame) {
        let matches = expected.get(&key).map_or_else(
            || pixels.iter().all(|&channel| channel == 255),
            |reference| pixels == *reference,
        );
        assert!(matches, "Composite differs at {key:?}");
    }
}

fn stroke(engine: &mut Engine, eraser: bool) {
    engine
        .command(Command::Begin {
            brush: Brush {
                size: 90.0,
                opacity: 0.6,
                hardness: 0.4,
                color: [210, 78, 42],
                eraser,
                ..Brush::default()
            },
        })
        .unwrap();
    for (x, y) in [(115.0, 60.0), (132.0, 68.0), (143.0, 74.0), (250.0, 135.0)] {
        engine
            .samples(&[Sample {
                x,
                y,
                pressure: 0.8,
            }])
            .unwrap();
        matches_full_composite(engine);
    }
}

#[test]
fn incremental_stroke_frames_match_all_blends_and_active_layer_positions() {
    for mode in MODES {
        for active in [1, 2, 4] {
            for eraser in [false, true] {
                let mut engine = artwork(mode, active);
                stroke(&mut engine, eraser);
                engine.command(Command::End).unwrap();
                matches_full_composite(&mut engine);
            }
        }
    }
}

#[test]
fn frames_refresh_after_cancel_undo_layer_edits_and_document_replacement() {
    let mut engine = artwork(BlendMode::SoftLight, 4);
    let original = engine.save().unwrap();
    stroke(&mut engine, false);
    engine.command(Command::Cancel).unwrap();
    assert!(
        original == engine.save().unwrap(),
        "Cancel did not restore document"
    );
    matches_full_composite(&mut engine);
    stroke(&mut engine, false);
    engine.command(Command::End).unwrap();
    let painted = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(
        original == engine.save().unwrap(),
        "Undo did not restore document"
    );
    matches_full_composite(&mut engine);
    engine.command(Command::Redo).unwrap();
    assert!(
        painted == engine.save().unwrap(),
        "Redo did not restore document"
    );
    matches_full_composite(&mut engine);
    engine
        .command(Command::SetBlend {
            id: 2,
            mode: BlendMode::Difference,
        })
        .unwrap();
    engine
        .command(Command::SetLayer {
            id: 1,
            visible: false,
            opacity: 0.4,
            name: "Hidden".into(),
        })
        .unwrap();
    engine
        .command(Command::MoveLayer {
            id: 2,
            direction: 1,
        })
        .unwrap();
    engine.command(Command::SelectLayer { id: 2 }).unwrap();
    stroke(&mut engine, false);
    engine.load(&original).unwrap();
    matches_full_composite(&mut engine);
    stroke(&mut engine, true);
    engine.command(Command::End).unwrap();
    matches_full_composite(&mut engine);
}

#[test]
fn cache_turnover_and_returning_to_earlier_tiles_preserve_pixels() {
    let mut engine = Engine::new(8192, 256).unwrap();
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: [90, 120, 180, 128],
            tolerance: 0,
        })
        .unwrap();
    engine.command(Command::AddLayer).unwrap();
    engine
        .command(Command::SetBlend {
            id: 2,
            mode: BlendMode::Overlay,
        })
        .unwrap();
    engine.frame();
    engine
        .command(Command::Begin {
            brush: Brush {
                size: 10.0,
                ..Brush::default()
            },
        })
        .unwrap();
    for row in [32.0, 160.0] {
        for column in 0..64 {
            engine
                .samples(&[Sample {
                    x: column as f32 * TILE_SIZE as f32 + 32.0,
                    y: row,
                    pressure: 1.0,
                }])
                .unwrap();
            engine.frame();
        }
    }
    engine
        .samples(&[Sample {
            x: 32.0,
            y: 32.0,
            pressure: 1.0,
        }])
        .unwrap();
    matches_full_composite(&mut engine);
    engine.command(Command::End).unwrap();
    matches_full_composite(&mut engine);
}
