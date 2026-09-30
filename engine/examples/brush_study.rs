use podor_engine::{brush_preview, model::*};
use std::{fs, path::PathBuf, time::Instant};

fn main() {
    let destination = PathBuf::from(
        std::env::args()
            .nth(1)
            .unwrap_or_else(|| ".tools/brush-study".into()),
    );
    fs::create_dir_all(&destination).unwrap();
    let cases = [
        ("smooth", Brush::default()),
        (
            "graphite",
            Brush {
                texture: BrushTexture::Graphite,
                size: 14.0,
                ..Brush::default()
            },
        ),
        (
            "charcoal",
            Brush {
                texture: BrushTexture::Charcoal,
                size: 72.0,
                hardness: 0.7,
                aspect: 0.6,
                ..Brush::default()
            },
        ),
        (
            "bristle",
            Brush {
                texture: BrushTexture::Bristle,
                size: 84.0,
                tip: BrushTip::Flat,
                angle: 90.0,
                aspect: 0.4,
                follow_direction: true,
                ..Brush::default()
            },
        ),
        (
            "dry-bristle",
            Brush {
                texture: BrushTexture::DryBristle,
                size: 84.0,
                tip: BrushTip::Flat,
                angle: 90.0,
                aspect: 0.4,
                follow_direction: true,
                ..Brush::default()
            },
        ),
        (
            "pigment",
            Brush {
                texture: BrushTexture::Pigment,
                size: 88.0,
                opacity: 0.15,
                hardness: 0.2,
                ..Brush::default()
            },
        ),
        (
            "canvas",
            Brush {
                texture: BrushTexture::Canvas,
                size: 88.0,
                hardness: 0.75,
                ..Brush::default()
            },
        ),
        (
            "wash",
            Brush {
                texture: BrushTexture::Wash,
                size: 110.0,
                opacity: 0.1,
                hardness: 0.65,
                ..Brush::default()
            },
        ),
        (
            "lance",
            Brush {
                size: 64.0,
                tip: BrushTip::Leaf,
                aspect: 0.35,
                follow_direction: true,
                ..Brush::default()
            },
        ),
        (
            "willow",
            Brush {
                size: 80.0,
                tip: BrushTip::Leaf,
                aspect: 0.28,
                texture: BrushTexture::Bristle,
                follow_direction: true,
                ..Brush::default()
            },
        ),
    ];
    for (name, brush) in cases {
        let start = Instant::now();
        let rgba = brush_preview(brush).unwrap();
        let elapsed = start.elapsed();
        let pixels = rgba
            .as_chunks::<4>()
            .0
            .iter()
            .flat_map(|pixel| [255 - pixel[3]; 3])
            .collect::<Vec<_>>();
        let file = fs::File::create(destination.join(format!("{name}.png"))).unwrap();
        let mut encoder = png::Encoder::new(file, BRUSH_PREVIEW_WIDTH, BRUSH_PREVIEW_HEIGHT);
        encoder.set_color(png::ColorType::Rgb);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&pixels)
            .unwrap();
        println!("{name}: {:.2} ms", elapsed.as_secs_f64() * 1000.0);
    }
}
