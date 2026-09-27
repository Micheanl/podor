use podor_engine::{
    model::{Brush, Sample},
    Command, Engine,
};
use std::time::Instant;

fn main() {
    let mut engine = Engine::new(2048, 2048).unwrap();
    let brush = Brush {
        size: 24.0,
        opacity: 1.0,
        hardness: 0.8,
        color: [30, 90, 240],
        eraser: false,
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
    }
    println!(
        "100 strokes, 12000 samples, 1500 dirty frames: {:.2} ms; {:.1} MiB transferred",
        start.elapsed().as_secs_f64() * 1000.0,
        bytes as f64 / 1048576.0
    );
}
