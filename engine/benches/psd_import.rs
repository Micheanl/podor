use podor_engine::{Command, Engine, ExportFormat, ExportOptions};
use std::{hint::black_box, time::Instant};

fn main() {
    for (edge, count) in [(2048, 6), (4096, 2)] {
        let mut source = Engine::new(edge, edge).unwrap();
        source
            .command(Command::Fill {
                x: 0,
                y: 0,
                color: [80, 120, 200, 173],
                tolerance: 0,
            })
            .unwrap();
        for _ in 1..count {
            source.command(Command::DuplicateLayer { id: 1 }).unwrap();
        }
        let bytes = source
            .export_image(ExportOptions {
                format: ExportFormat::Psd,
                ..Default::default()
            })
            .unwrap();
        drop(source);
        let mut samples = Vec::new();
        for run in 0..6 {
            let mut engine = Engine::new(1, 1).unwrap();
            let start = Instant::now();
            engine.load(black_box(&bytes)).unwrap();
            if run > 0 {
                samples.push(start.elapsed().as_secs_f64() * 1000.0);
            }
            assert_eq!(engine.document.layers.len(), count);
            assert_eq!(
                engine.document.tile_count(),
                (edge / 128).pow(2) as usize * count
            );
            black_box(&engine);
        }
        samples.sort_by(f64::total_cmp);
        println!(
            "{edge} x {edge}, {count} layers: PSD import median {:.2} ms; {:.1} KiB encoded",
            samples[2],
            bytes.len() as f64 / 1024.0
        );
    }
}
