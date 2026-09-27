use podor_engine::{
    model::{Brush, BrushTip, Sample},
    Command, Engine,
};
use std::time::Instant;

fn main() {
    for stabilization in [0.0, 0.5, 1.0] {
        painting(stabilization, false, false, false);
    }
    painting(0.5, true, false, false);
    painting(0.5, true, true, false);
    painting(0.5, false, false, true);
}

fn painting(stabilization: f32, flat: bool, follow_direction: bool, alpha_locked: bool) {
    let mut engine = Engine::new(2048, 2048).unwrap();
    if alpha_locked {
        engine
            .command(Command::Fill {
                x: 0,
                y: 0,
                color: [139, 41, 66, 128],
                tolerance: 0,
            })
            .unwrap();
        engine
            .command(Command::SetProtection {
                id: 1,
                alpha_locked: Some(alpha_locked),
                locked: Some(false),
            })
            .unwrap();
        engine.frame();
    }
    let brush = Brush {
        size: 24.0,
        opacity: 1.0,
        hardness: 0.8,
        color: [30, 90, 240],
        eraser: false,
        stabilization,
        tip: if flat {
            BrushTip::Flat
        } else {
            BrushTip::Round
        },
        aspect: if flat { 0.25 } else { 1.0 },
        angle: if flat { 90.0 } else { 0.0 },
        follow_direction,
        ..Brush::default()
    };
    let start = Instant::now();
    let mut bytes = 0;
    for stroke in 0..100 {
        engine.command(Command::Begin { brush }).unwrap();
        let samples: Vec<_> = (0..120)
            .map(|i| Sample {
                x: 100.0 + i as f32 * 12.0,
                y: 100.0 + stroke as f32 * 15.0 + (i as f32 * 0.1).sin() * 40.0,
                pressure: 0.7,
            })
            .collect();
        for batch in samples.chunks(8) {
            engine.samples(batch).unwrap();
            bytes += engine.frame().len();
        }
        engine.command(Command::End).unwrap();
        bytes += engine.frame().len();
    }
    println!(
        "stabilization={stabilization:.1}, flat={flat}, follow={follow_direction}, alpha_lock={alpha_locked}, 100 strokes, 12000 samples, 1600 frames: {:.2} ms; {:.1} MiB transferred",
        start.elapsed().as_secs_f64() * 1000.0,
        bytes as f64 / 1048576.0
    );
}
