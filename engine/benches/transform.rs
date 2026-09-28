use podor_engine::{Command, Engine, LayerTransform, ResampleFilter};
use std::time::Instant;

fn main() {
    for edge in [1024, 4096] {
        for (scale, angle) in [(1, 23.0), (2, 0.0), (2, 37.0)] {
            let mut engine = Engine::new(edge, edge).unwrap();
            engine
                .command(Command::Fill {
                    x: 0,
                    y: 0,
                    color: [139, 41, 66, 173],
                    tolerance: 0,
                })
                .unwrap();
            let revision = engine.state()["revision"].as_u64().unwrap();
            let transform = LayerTransform {
                width: edge / scale,
                height: edge / scale,
                dx: 0.0,
                dy: 0.0,
                angle,
                flip_x: false,
                flip_y: false,
                filter: ResampleFilter::Lanczos3,
            };
            let start = Instant::now();
            engine
                .command(Command::TransformLayer {
                    id: 1,
                    revision,
                    transform,
                })
                .unwrap();
            println!(
                "{edge}x{edge}, size / {scale}, {angle} degrees: {:.2} ms, {} tiles",
                start.elapsed().as_secs_f64() * 1000.0,
                engine.document.tile_count()
            );
        }
    }
}
