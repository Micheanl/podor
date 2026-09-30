use crate::{model::*, Command, Engine};

pub fn render(brush: Brush) -> Result<Vec<u8>, String> {
    let brush = brush.validate()?;
    let mut engine = Engine::new(BRUSH_PREVIEW_WIDTH, BRUSH_PREVIEW_HEIGHT)?;
    let max_size = if matches!(brush.tip, BrushTip::Flat | BrushTip::Comb) {
        44.0
    } else {
        60.0
    };
    let brush = Brush {
        size: if brush.raster == BrushRaster::Antialiased {
            (brush.size * 0.65).clamp(5.0, max_size)
        } else {
            brush.size.clamp(1.0, max_size)
        },
        color: [255, 255, 255],
        eraser: false,
        smudge: false,
        symmetry: Symmetry::default(),
        ..brush
    };
    let radius = brush.size_at_pressure(0.15) * 0.5;
    let extent = if matches!(brush.tip, BrushTip::Flat | BrushTip::Comb) {
        radius * std::f32::consts::SQRT_2
    } else {
        radius
    };
    let flick = if brush.tip == BrushTip::Leaf && brush.follow_direction {
        brush.size_at_pressure(0.15) * 0.75
    } else {
        0.0
    };
    let start = (extent + 6.0).max(22.0);
    let span = (270.0 - extent - flick).min(242.0) - start;
    engine.command(Command::Begin {
        brush,
        assistant: None,
    })?;
    let points = (0..=160)
        .map(|i| {
            let t = i as f32 / 160.0;
            let wave = (t * std::f32::consts::TAU).sin();
            Sample {
                x: start + t * span,
                y: 48.0 - wave * 14.0,
                pressure: 0.15 + 0.85 * (std::f32::consts::PI * t).sin().max(0.0).powf(0.65),
            }
        })
        .collect::<Vec<_>>();
    engine.samples(&points)?;
    engine.command(Command::End)?;
    engine.command(Command::Begin {
        assistant: None,
        brush: Brush {
            size: if brush.raster == BrushRaster::Antialiased {
                (brush.size * 0.45).clamp(9.0, 24.0)
            } else {
                brush.size.clamp(1.0, 24.0)
            },
            follow_direction: false,
            stabilization: 0.0,
            ..brush
        },
    })?;
    engine.samples(&[Sample {
        x: 296.0,
        y: 48.0,
        pressure: 1.0,
    }])?;
    engine.command(Command::End)?;
    let mut pixels = vec![0; (BRUSH_PREVIEW_WIDTH * BRUSH_PREVIEW_HEIGHT * 4) as usize];
    let raster = engine.document.layers[0].raster()?;
    for y in 0..BRUSH_PREVIEW_HEIGHT {
        for x in 0..BRUSH_PREVIEW_WIDTH {
            if let Some(tile) = raster.tiles().get(&(x / TILE_SIZE, y / TILE_SIZE)) {
                let source = (((y % TILE_SIZE) * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                let target = ((y * BRUSH_PREVIEW_WIDTH + x) * 4) as usize;
                pixels[target..target + 4].copy_from_slice(&tile[source..source + 4]);
            }
        }
    }
    Ok(pixels)
}
