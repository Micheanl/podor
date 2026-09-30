use podor_engine::{model::*, Command, Engine};
use std::sync::Arc;

#[test]
fn extracts_dominant_colors_without_background_and_keeps_the_document_unchanged() {
    let mut engine = Engine::new(128, 128).unwrap();
    let mut tile = vec![0; TILE_BYTES];
    for (i, pixel) in tile.as_chunks_mut::<4>().0.iter_mut().enumerate() {
        pixel.copy_from_slice(if i < 8192 {
            &[144, 24, 64, 255]
        } else if i < 12288 {
            &[32, 160, 192, 255]
        } else {
            &[0, 0, 0, 0]
        });
    }
    engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new(tile));
    let before = engine.save().unwrap();
    let state = engine.state();
    engine.frame();
    assert_eq!(
        engine.extract_palette(12).unwrap(),
        vec![[144, 24, 64], [32, 160, 192]]
    );
    assert_eq!(engine.extract_palette(1).unwrap(), vec![[107, 69, 107]]);
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    assert_eq!(
        u32::from_le_bytes(engine.frame()[12..16].try_into().unwrap()),
        0
    );
}

#[test]
fn uses_visible_blended_colors_and_respects_opacity_and_tile_edges() {
    let mut engine = Engine::new(3, 5).unwrap();
    let tile: Vec<_> = [200, 100, 50, 255]
        .into_iter()
        .cycle()
        .take(TILE_BYTES)
        .collect();
    engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new(tile));
    engine.command(Command::AddLayer).unwrap();
    let top: Vec<_> = [128, 128, 128, 255]
        .into_iter()
        .cycle()
        .take(TILE_BYTES)
        .collect();
    engine.document.layers[1]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new(top));
    engine.document.layers[1].blend = BlendMode::Multiply;
    assert_eq!(engine.extract_palette(12).unwrap(), vec![[100, 50, 25]]);
    engine.document.layers[1].visible = false;
    assert_eq!(engine.extract_palette(12).unwrap(), vec![[200, 100, 50]]);
    engine.document.layers[1].visible = true;
    engine.document.layers[1].opacity = 0.0;
    assert_eq!(engine.extract_palette(12).unwrap(), vec![[200, 100, 50]]);
    engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .clear();
    let mut edge = vec![0; TILE_BYTES];
    edge[..4].copy_from_slice(&[64, 0, 32, 128]);
    edge[16..20].copy_from_slice(&[0, 255, 0, 255]);
    engine.document.layers[0]
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new(edge));
    assert_eq!(engine.extract_palette(12).unwrap(), vec![[128, 0, 64]]);
}

#[test]
fn empty_canvas_limits_and_active_strokes_are_handled() {
    let mut engine = Engine::new(1, 1).unwrap();
    assert!(engine.extract_palette(12).unwrap().is_empty());
    assert!(engine.extract_palette(0).is_err());
    assert!(engine.extract_palette(MAX_PALETTE_COLORS + 1).is_err());
    engine
        .command(Command::Begin {
            brush: Brush::default(),
            assistant: None,
        })
        .unwrap();
    assert!(engine.extract_palette(12).is_err());
    engine.command(Command::Cancel).unwrap();
    assert!(engine.extract_palette(12).unwrap().is_empty());
}

#[test]
fn gradient_quantization_is_bounded_deterministic_and_preserves_color_range() {
    let mut engine = Engine::new(256, 128).unwrap();
    for tx in 0..2 {
        let mut tile = vec![0; TILE_BYTES];
        for y in 0..128 {
            for x in 0..128 {
                let i = ((y * 128 + x) * 4) as usize;
                tile[i..i + 4].copy_from_slice(&[(tx * 128 + x) as u8, (y * 2) as u8, 80, 255]);
            }
        }
        engine.document.layers[0]
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert((tx, 0), Arc::new(tile));
    }
    let palette = engine.extract_palette(12).unwrap();
    assert_eq!(palette.len(), 12);
    assert_eq!(palette, engine.extract_palette(12).unwrap());
    assert!(palette.iter().any(|c| c[0] < 50));
    assert!(palette.iter().any(|c| c[0] > 200));
    assert!(palette.iter().all(|c| c[2] == 80));
}
