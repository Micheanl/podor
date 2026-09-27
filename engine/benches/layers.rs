use podor_engine::{Command, Engine};
use std::time::Instant;

fn main() {
    for edge in [2048, 4096] {
        let mut engine = Engine::new(edge, edge).unwrap();
        engine
            .command(Command::Fill {
                x: 0,
                y: 0,
                color: [139, 41, 66, 255],
                tolerance: 0,
            })
            .unwrap();
        let start = Instant::now();
        engine.command(Command::DuplicateLayer { id: 1 }).unwrap();
        let duplicate = start.elapsed();
        let start = Instant::now();
        engine.command(Command::MergeVisible).unwrap();
        let merge = start.elapsed();
        let start = Instant::now();
        let thumbnail = engine.thumbnail();
        println!(
            "{edge}x{edge}: duplicate {:.2} ms, merge {:.2} ms, thumbnail {:.2} ms, {} bytes",
            duplicate.as_secs_f64() * 1000.0,
            merge.as_secs_f64() * 1000.0,
            start.elapsed().as_secs_f64() * 1000.0,
            thumbnail.len()
        );
    }
}
