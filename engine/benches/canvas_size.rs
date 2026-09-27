use podor_engine::{Command, Engine};
use std::{hint::black_box, time::Instant};

fn main() {
    for (width, height) in [(2048, 1536), (4096, 3072)] {
        let mut engine = Engine::new(width, height).unwrap();
        engine
            .command(Command::Fill {
                x: 0,
                y: 0,
                color: [100, 60, 90, 200],
                tolerance: 0,
            })
            .unwrap();
        engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
        for extra in [256, 2] {
            let mut samples = Vec::new();
            for run in 0..6 {
                let revision = engine.state()["revision"].as_u64().unwrap();
                let start = Instant::now();
                engine
                    .command(Command::ResizeCanvas {
                        width,
                        height: height + extra,
                        anchor: 4,
                        revision,
                    })
                    .unwrap();
                if run > 0 {
                    samples.push(start.elapsed().as_secs_f64() * 1000.0);
                }
                black_box(&engine);
                engine.command(Command::Undo).unwrap();
            }
            samples.sort_by(f64::total_cmp);
            println!(
                "{width}x{height}, 2 layers, add {extra} rows: median {:.2} ms",
                samples[2]
            );
        }
    }
}
