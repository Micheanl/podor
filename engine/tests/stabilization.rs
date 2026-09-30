use podor_engine::{model::*, Command, Engine};

fn brush(stabilization: f32) -> Brush {
    Brush {
        size: 3.0,
        hardness: 1.0,
        stabilization,
        ..Brush::default()
    }
}

fn point(x: f32, y: f32) -> Sample {
    Sample {
        x,
        y,
        pressure: 1.0,
    }
}

fn alpha(engine: &Engine, x: u32, y: u32) -> f64 {
    engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or(0.0, |tile| {
            f64::from(tile[(((y % TILE_SIZE) * TILE_SIZE + x % TILE_SIZE) * 4 + 3) as usize])
        })
}

fn draw(samples: &[Sample], amount: f32, batch: usize) -> Engine {
    let mut engine = Engine::new(512, 128).unwrap();
    engine
        .command(Command::Begin {
            brush: brush(amount),
            assistant: None,
        })
        .unwrap();
    for points in samples.chunks(batch) {
        engine.samples(points).unwrap();
    }
    engine.command(Command::End).unwrap();
    engine
}

#[test]
fn stabilization_reduces_visible_jitter_without_breaking_the_line() {
    let points: Vec<_> = (0..400)
        .map(|i| point(20.5 + i as f32, 64.5 + (i as f32 * 1.3).sin() * 4.0))
        .collect();
    let raw = draw(&points, 0.0, 8);
    let smooth = draw(&points, 0.6, 8);
    let deviation = |engine: &Engine| -> f64 {
        (70..370)
            .map(|x| {
                let weight: f64 = (40..90).map(|y| alpha(engine, x, y)).sum();
                assert!(weight > 0.0, "gap at column {x}");
                let center: f64 = (40..90)
                    .map(|y| (f64::from(y) + 0.5) * alpha(engine, x, y))
                    .sum::<f64>()
                    / weight;
                (center - 64.5).abs()
            })
            .sum::<f64>()
            / 300.0
    };
    let raw_error = deviation(&raw);
    let smooth_error = deviation(&smooth);
    assert!(
        smooth_error < raw_error * 0.25,
        "raw={raw_error}, stabilized={smooth_error}"
    );
}

#[test]
fn stabilized_pixels_do_not_depend_on_batch_size() {
    let points: Vec<_> = (0..160)
        .map(|i| Sample {
            x: 20.0 + i as f32 * 2.5,
            y: 64.0 + (i as f32 * 0.13).sin() * 25.0,
            pressure: 0.2 + i as f32 / 200.0,
        })
        .collect();
    let expected = draw(&points, 0.75, 1).export_png().unwrap();
    for batch in [8, 64, 160] {
        assert_eq!(expected, draw(&points, 0.75, batch).export_png().unwrap());
    }
}

#[test]
fn pen_up_completes_the_endpoint_and_history_restores_the_whole_stroke() {
    let mut engine = Engine::new(256, 128).unwrap();
    engine
        .command(Command::Begin {
            brush: brush(1.0),
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[point(20.5, 64.5), point(210.5, 64.5)])
        .unwrap();
    assert_eq!(alpha(&engine, 210, 64), 0.0);
    engine.command(Command::End).unwrap();
    assert!(alpha(&engine, 210, 64) > 200.0);
    let complete = engine.export_png().unwrap();
    engine.command(Command::End).unwrap();
    assert_eq!(complete, engine.export_png().unwrap());
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.document.tile_count(), 0);
    engine.command(Command::Redo).unwrap();
    assert_eq!(complete, engine.export_png().unwrap());
    let mut restored = Engine::new(1, 1).unwrap();
    restored.load(&engine.save().unwrap()).unwrap();
    assert_eq!(complete, restored.export_png().unwrap());
}

#[test]
fn canceled_and_single_point_strokes_do_not_leave_a_tail() {
    let mut engine = Engine::new(256, 128).unwrap();
    engine
        .command(Command::Begin {
            brush: brush(1.0),
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[point(20.0, 64.0), point(210.0, 64.0)])
        .unwrap();
    engine.command(Command::Cancel).unwrap();
    engine.command(Command::End).unwrap();
    assert_eq!(engine.document.tile_count(), 0);
    assert_eq!(engine.state()["canUndo"], false);
    let points = [point(20.0, 64.0); 20];
    assert_eq!(
        draw(&points, 0.0, 1).export_png().unwrap(),
        draw(&points, 1.0, 1).export_png().unwrap()
    );
}

#[test]
fn finishing_a_stabilized_eraser_respects_the_selection() {
    let mut engine = Engine::new(256, 128).unwrap();
    engine
        .command(Command::Fill {
            contiguous: true,
            merged: false,
            x: 0,
            y: 0,
            color: [139, 41, 66, 255],
            tolerance: 0,
        })
        .unwrap();
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 60,
                top: 40,
                right: 180,
                bottom: 90,
            }),
        })
        .unwrap();
    engine
        .command(Command::Begin {
            brush: Brush {
                size: 12.0,
                eraser: true,
                ..brush(1.0)
            },
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[point(30.0, 64.0), point(230.0, 64.0)])
        .unwrap();
    engine.command(Command::End).unwrap();
    assert_eq!(alpha(&engine, 40, 64), 255.0);
    assert_eq!(alpha(&engine, 179, 64), 0.0);
    assert_eq!(alpha(&engine, 180, 64), 255.0);
    assert_eq!(alpha(&engine, 230, 64), 255.0);
}

#[test]
fn invalid_stabilization_cannot_start_or_mutate_a_stroke() {
    for amount in [-0.01, 1.01, f32::NAN, f32::INFINITY] {
        let mut engine = Engine::new(128, 128).unwrap();
        assert!(engine
            .command(Command::Begin {
                brush: brush(amount),
                assistant: None
            })
            .is_err());
        assert_eq!(engine.document.tile_count(), 0);
        engine
            .command(Command::Begin {
                brush: brush(0.0),
                assistant: None,
            })
            .unwrap();
    }
}
