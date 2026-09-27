use podor_engine::Engine;
use std::{hint::black_box, time::Instant};

fn main() {
    for edge in [2048, 4096] {
        let mut rgb = Vec::with_capacity((edge * edge * 3) as usize);
        for y in 0..edge {
            for x in 0..edge {
                rgb.extend_from_slice(&[(x / 8) as u8, (y / 8) as u8, ((x + y) / 16) as u8]);
            }
        }
        let mut jpeg = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, 90)
            .encode(&rgb, edge, edge, image::ExtendedColorType::Rgb8)
            .unwrap();
        let mut webp = Vec::new();
        image::codecs::webp::WebPEncoder::new_lossless(&mut webp)
            .encode(&rgb, edge, edge, image::ExtendedColorType::Rgb8)
            .unwrap();
        drop(rgb);
        for (name, bytes) in [("JPEG", jpeg), ("WebP", webp)] {
            let mut samples = Vec::new();
            for run in 0..7 {
                let mut engine = Engine::new(1, 1).unwrap();
                let start = Instant::now();
                engine.load(black_box(&bytes)).unwrap();
                if run >= 2 {
                    samples.push(start.elapsed().as_secs_f64() * 1000.0);
                }
                black_box(&engine);
            }
            samples.sort_by(f64::total_cmp);
            println!(
                "{edge}x{edge} {name}: median {:.2} ms; {:.1} KiB encoded",
                samples[2],
                bytes.len() as f64 / 1024.0
            );
        }
    }
}
