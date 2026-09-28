use podor_engine::{model::*, Command, Engine, ResampleFilter};
use std::{hint::black_box, sync::Arc, time::Instant};

fn main() {
    for (width, height, target_width, target_height, layers, sparse) in [
        (2048, 1536, 1024, 768, 2, false),
        (4096, 3072, 2048, 1536, 1, false),
        (1600, 1200, 3200, 2400, 1, false),
        (4096, 1024, 8192, 2048, 32, true),
        (1, 8192, 8192, 1, 1, false),
    ] {
        let mut engine = Engine::new(width, height).unwrap();
        if sparse {
            engine.document.layers.clear();
            for id in 1..=layers {
                let mut layer = Layer::new(id, format!("Layer {id}"));
                let mut tile = vec![0; TILE_BYTES];
                tile[..4].copy_from_slice(&[128, 32, 64, 160]);
                layer.tiles.insert(
                    (
                        (id - 1) % (width / TILE_SIZE),
                        (id - 1) % (height / TILE_SIZE),
                    ),
                    Arc::new(tile),
                );
                engine.document.layers.push(layer);
            }
            engine.document.active = 1;
            engine.document.next_id = layers + 1;
        } else {
            for id in 1..=layers {
                if id > 1 {
                    engine.command(Command::AddLayer).unwrap();
                }
                engine
                    .command(Command::Fill {
                        x: 0,
                        y: 0,
                        color: [100, 60, 90, 200],
                        tolerance: 0,
                    })
                    .unwrap();
            }
        }
        for filter in [ResampleFilter::Nearest, ResampleFilter::Lanczos3] {
            let mut times = Vec::new();
            for run in 0..4 {
                let revision = engine.state()["revision"].as_u64().unwrap();
                let start = Instant::now();
                engine
                    .command(Command::ResizeImage {
                        width: target_width,
                        height: target_height,
                        filter,
                        revision,
                    })
                    .unwrap();
                if run > 0 {
                    times.push(start.elapsed().as_secs_f64() * 1000.0);
                }
                black_box(&engine);
                engine.command(Command::Undo).unwrap();
            }
            times.sort_by(f64::total_cmp);
            println!("{width}x{height} -> {target_width}x{target_height}, {layers} layers, sparse={sparse}, {filter:?}: median {:.2} ms", times[1]);
        }
    }
}
