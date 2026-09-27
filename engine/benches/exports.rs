use podor_engine::{model::*, Command, Engine, ExportFormat, ExportOptions};
use std::time::Instant;

fn main() {
    for dense in [false, true] {
        let mut engine = if dense {
            Engine::new(2048, 1024)
        } else {
            Engine::new(8192, 2048)
        }
        .unwrap();
        let layers = if dense { 8 } else { 32 };
        for index in 0..layers {
            if index > 0 {
                engine.command(Command::AddLayer).unwrap();
            }
            if dense {
                engine
                    .command(Command::Fill {
                        x: 0,
                        y: 0,
                        color: [40 + index as u8 * 20, 90, 180, 180],
                        tolerance: 0,
                    })
                    .unwrap();
            } else {
                engine
                    .command(Command::Begin {
                        brush: Brush {
                            size: 24.0,
                            ..Brush::default()
                        },
                    })
                    .unwrap();
                engine
                    .samples(&[Sample {
                        x: 64.0 + index as f32 * 240.0,
                        y: 64.0 + index as f32 * 56.0,
                        pressure: 1.0,
                    }])
                    .unwrap();
                engine.command(Command::End).unwrap();
            }
        }
        for format in [
            ExportFormat::Ora,
            ExportFormat::Tiff,
            ExportFormat::Bmp,
            ExportFormat::Psd,
        ] {
            let start = Instant::now();
            let output = engine
                .export_image(ExportOptions {
                    format,
                    ..Default::default()
                })
                .unwrap();
            println!(
                "{format:?}, {}x{}, {layers} {} layers: {:.2} ms, {:.2} MiB output",
                engine.document.width,
                engine.document.height,
                if dense { "filled" } else { "sparse" },
                start.elapsed().as_secs_f64() * 1000.0,
                output.len() as f64 / 1048576.0
            );
        }
    }
}
