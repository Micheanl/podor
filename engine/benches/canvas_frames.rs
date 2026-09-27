use podor_engine::{
    model::{BlendMode, Brush, Layer, Sample, TILE_BYTES, TILE_SIZE},
    Command, Engine,
};
use std::{hint::black_box, sync::Arc, time::Instant};

fn main() {
    for (edge, layers) in [(2048, 1), (2048, 8), (4096, 2)] {
        for size in [64.0, 256.0] {
            measure(edge, layers, size);
        }
    }
}

fn measure(edge: u32, layers: u32, size: f32) {
    let mut engine = Engine::new(edge, edge).unwrap();
    engine.document.layers.clear();
    for id in 1..=layers {
        let mut layer = Layer::new(id, format!("Layer {id}"));
        layer.blend = match id % 3 {
            0 => BlendMode::Multiply,
            2 => BlendMode::SoftLight,
            _ => BlendMode::Normal,
        };
        layer.opacity = if id == 1 { 1.0 } else { 0.7 };
        for y in 0..edge / TILE_SIZE {
            for x in 0..edge / TILE_SIZE {
                let mut tile = vec![0; TILE_BYTES];
                for (index, pixel) in tile.as_chunks_mut::<4>().0.iter_mut().enumerate() {
                    let alpha = if id == 1 {
                        255
                    } else {
                        128 + (index % 128) as u8
                    };
                    pixel.copy_from_slice(&[
                        ((x * 23 + id * 37) % u32::from(alpha)) as u8,
                        ((y * 41 + id * 13) % u32::from(alpha)) as u8,
                        alpha / 2,
                        alpha,
                    ]);
                }
                layer.tiles.insert((x, y), Arc::new(tile));
            }
        }
        engine.document.layers.push(layer);
    }
    engine.document.active = layers;
    engine.document.next_id = layers + 1;
    engine.document.validate().unwrap();
    let mut samples_ms = Vec::new();
    let mut frames_ms = Vec::new();
    let mut bytes = 0;
    for stroke in 0..5 {
        engine
            .command(Command::Begin {
                brush: Brush {
                    size,
                    color: [137, 58, 85],
                    opacity: 0.6,
                    hardness: 0.5,
                    ..Brush::default()
                },
            })
            .unwrap();
        for batch in 0..60 {
            let points: Vec<_> = (0..8)
                .map(|index| {
                    let t = (batch * 8 + index) as f32 / 480.0;
                    Sample {
                        x: 256.0 + t * (edge as f32 - 512.0),
                        y: edge as f32 / 2.0 + (t * 8.0).sin() * 200.0,
                        pressure: 0.75 + 0.2 * (t * 12.0).sin(),
                    }
                })
                .collect();
            let start = Instant::now();
            engine.samples(&points).unwrap();
            let sampled = start.elapsed().as_secs_f64() * 1000.0;
            let start = Instant::now();
            bytes += black_box(engine.frame()).len();
            let framed = start.elapsed().as_secs_f64() * 1000.0;
            if stroke > 0 {
                samples_ms.push(sampled);
                frames_ms.push(framed);
            }
        }
        engine.command(Command::End).unwrap();
        black_box(engine.frame());
    }
    samples_ms.sort_by(f64::total_cmp);
    frames_ms.sort_by(f64::total_cmp);
    let median = samples_ms.len() / 2;
    let p95 = samples_ms.len() * 95 / 100;
    println!(
        "{edge}x{edge}, {layers} layers, brush {size:.0}: samples median/p95 {:.2}/{:.2} ms; frame median/p95 {:.2}/{:.2} ms; {:.1} MiB. CPU engine only, not UI FPS.",
        samples_ms[median], samples_ms[p95], frames_ms[median], frames_ms[p95], bytes as f64 / 1048576.0,
    );
}
