use podor_engine::{Command, Engine};
use std::time::{Duration, Instant};

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
            "{edge}x{edge}: request {request_ms:.3} ms, background {:.2} ms, {:.1} KiB",
            start.elapsed().as_secs_f64() * 1000.0,
            bytes.len() as f64 / 1024.0
        );
    }
}
