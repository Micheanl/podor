use podor_engine::{model::*, Command, Engine};
use std::time::Instant;

fn main() {
    for texture in [
        BrushTexture::Smooth,
        BrushTexture::Graphite,
        BrushTexture::Charcoal,
        BrushTexture::Bristle,
        BrushTexture::DryBristle,
        BrushTexture::Pigment,
        BrushTexture::Canvas,
        BrushTexture::Wash,
    ] {
        let mut engine = Engine::new(1024, 512).unwrap();
        let brush = Brush {
            size: 32.0,
            hardness: 0.8,
            opacity: 0.7,
            texture,
            ..Brush::default()
        };
        let samples = (0..=160)
            .map(|i| Sample {
                x: 32.0 + i as f32 * 6.0,
                y: 64.0 + (i as f32 * 0.09).sin() * 16.0,
                pressure: 0.8,
            })
            .collect::<Vec<_>>();
        let start = Instant::now();
        let mut bytes = 0;
        for _ in 0..10 {
            engine
                .command(Command::Begin {
                    brush,
                    assistant: None,
                })
                .unwrap();
            for chunk in samples.chunks(8) {
                engine.samples(chunk).unwrap();
                bytes += engine.frame().len();
            }
            engine.command(Command::End).unwrap();
            bytes += engine.frame().len();
        }
        println!(
            "{texture:?}: {:.2} ms for 10 strokes/1610 samples/220 frames; {:.2} MiB transferred",
            start.elapsed().as_secs_f64() * 1000.0,
            bytes as f64 / 1048576.0
        );
    }
}
