use podor_engine::{Command, Engine, ExportFormat, ExportOptions};
use std::{hint::black_box, time::Instant};

fn main() {
    for edge in [2048, 4096] {
        let mut source = Engine::new(edge, edge).unwrap();
        source
            .command(Command::Fill {
                x: 0,
                y: 0,
                color: [80, 120, 200, 192],
                tolerance: 0,
            })
            .unwrap();
        source.command(Command::DuplicateLayer { id: 1 }).unwrap();
        let bytes = source
            .export_image(ExportOptions {
                format: ExportFormat::Ora,
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
            assert_eq!(engine.document.layers.len(), 2);
            black_box(&engine);
        }
        samples.sort_by(f64::total_cmp);
        println!(
            "{edge}x{edge}, 2 layers: ORA import median {:.2} ms; {:.1} KiB encoded",
            samples[2],
            bytes.len() as f64 / 1024.0
        );
    }
}
