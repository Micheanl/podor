use podor_engine::{model::BlendMode, Command, Engine};
use std::{
    hint::black_box,
    time::{Duration, Instant},
};

fn main() {
    let mut engine = Engine::new(2048, 2048).unwrap();
    for (id, color) in [(1, [64, 128, 192, 255]), (2, [192, 64, 128, 255])] {
        if id == 2 {
            engine.command(Command::AddLayer).unwrap();
        }
        engine
            .command(Command::Fill {
                contiguous: true,
                merged: false,
                x: 0,
                y: 0,
                color,
                tolerance: 0,
            })
            .unwrap();
    }
    for opacity in [1.0, 0.5] {
        engine
            .command(Command::SetLayer {
                id: 2,
                visible: true,
                opacity,
                name: "top".into(),
            })
            .unwrap();
        for mode in [
            BlendMode::Normal,
            BlendMode::Multiply,
            BlendMode::Screen,
            BlendMode::Overlay,
            BlendMode::SoftLight,
            BlendMode::Darken,
            BlendMode::Lighten,
            BlendMode::Difference,
        ] {
            let mut samples = Vec::new();
            for run in 0..7 {
                engine.command(Command::SetBlend { id: 2, mode }).unwrap();
                let start = Instant::now();
                black_box(engine.frame());
                if run >= 2 {
                    samples.push(start.elapsed().as_secs_f64() * 1000.0);
                }
            }
            samples.sort_by(f64::total_cmp);
            println!(
                "2048x2048, 2 layers, opacity {opacity:.1}, {mode:?}: median {:.2} ms",
                samples[2]
            );
        }
    }
    engine
        .command(Command::SetBlend {
            id: 2,
            mode: BlendMode::SoftLight,
        })
        .unwrap();
    let start = Instant::now();
    engine.previews().unwrap();
    let request_ms = start.elapsed().as_secs_f64() * 1000.0;
    let bytes = loop {
        let bytes = engine.previews().unwrap();
        if !bytes.is_empty() {
            break bytes;
        }
        std::thread::sleep(Duration::from_millis(1));
    };
    println!(
        "2048x2048, 2 layers, opacity 0.5, SoftLight preview: request {request_ms:.3} ms, background {:.2} ms, {:.1} KiB",
        start.elapsed().as_secs_f64() * 1000.0,
        bytes.len() as f64 / 1024.0
    );
}
