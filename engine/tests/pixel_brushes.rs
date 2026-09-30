use podor_engine::{brush_preview, model::*, Command, Engine};
use serde_json::json;
use std::collections::BTreeSet;

fn brush(raster: BrushRaster) -> Brush {
    Brush {
        size: 1.0,
        size_pressure: 0.0,
        opacity: 1.0,
        opacity_pressure: 0.0,
        color: [36, 108, 180],
        raster,
        ..Brush::default()
    }
}

fn point(x: f32, y: f32) -> Sample {
    Sample {
        x,
        y,
        pressure: 1.0,
    }
}

fn pixel(engine: &Engine, x: u32, y: u32) -> [u8; 4] {
    engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| {
            let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[offset..offset + 4].try_into().unwrap()
        })
}

fn cells(engine: &Engine) -> BTreeSet<(u32, u32)> {
    (0..engine.document.height)
        .flat_map(|y| (0..engine.document.width).map(move |x| (x, y)))
        .filter(|&(x, y)| pixel(engine, x, y)[3] != 0)
        .collect()
}

fn draw(engine: &mut Engine, brush: Brush, points: &[Sample], packet: usize) {
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    for chunk in points.chunks(packet) {
        engine.samples(chunk).unwrap();
        engine.frame();
    }
    engine.command(Command::End).unwrap();
}

#[test]
fn single_pixel_uses_floor_coordinates_and_never_creates_soft_edges() {
    for raster in [BrushRaster::Pixel, BrushRaster::PixelPerfect] {
        for tip in [
            BrushTip::Round,
            BrushTip::Flat,
            BrushTip::Leaf,
            BrushTip::Comb,
        ] {
            let mut engine = Engine::new(32, 32).unwrap();
            draw(
                &mut engine,
                Brush {
                    tip,
                    texture: BrushTexture::Wash,
                    hardness: 0.0,
                    grain: 1.0,
                    paper: 1.0,
                    ..brush(raster)
                },
                &[point(7.9, 11.2)],
                1,
            );
            assert_eq!(cells(&engine), BTreeSet::from([(7, 11)]));
            assert_eq!(pixel(&engine, 7, 11), [36, 108, 180, 255]);
        }
    }
}

#[test]
fn narrow_flat_and_chisel_nibs_quantize_both_axes_and_never_disappear() {
    for raster in [BrushRaster::Pixel, BrushRaster::PixelPerfect] {
        for angle in [0.0, 90.0, 180.0, -90.0] {
            let mut engine = Engine::new(32, 32).unwrap();
            draw(
                &mut engine,
                Brush {
                    size: 2.0,
                    tip: BrushTip::Flat,
                    aspect: 0.1,
                    angle,
                    ..brush(raster)
                },
                &[point(16.2, 16.8)],
                1,
            );
            let expected = if angle == 0.0 || angle == 180.0 {
                BTreeSet::from([(15, 16), (16, 16)])
            } else {
                BTreeSet::from([(16, 15), (16, 16)])
            };
            assert_eq!(cells(&engine), expected);
            let state = engine.state();
            let saved = engine.save().unwrap();
            draw(
                &mut engine,
                Brush {
                    size: 2.0,
                    tip: BrushTip::Flat,
                    aspect: 0.1,
                    angle,
                    ..brush(raster)
                },
                &[point(16.2, 16.8)],
                1,
            );
            assert_eq!(engine.state(), state);
            assert_eq!(engine.save().unwrap(), saved);
        }
        for tip in [
            BrushTip::Round,
            BrushTip::Flat,
            BrushTip::Leaf,
            BrushTip::Comb,
        ] {
            for size in 1..=8 {
                for angle in [0.0, 27.0, 45.0, 90.0] {
                    let mut engine = Engine::new(32, 32).unwrap();
                    draw(
                        &mut engine,
                        Brush {
                            size: size as f32,
                            tip,
                            aspect: 0.1,
                            angle,
                            ..brush(raster)
                        },
                        &[point(16.0, 16.0)],
                        1,
                    );
                    assert!(
                        !cells(&engine).is_empty(),
                        "tip{} size{size} angle{angle}",
                        tip as u8
                    );
                    assert!(cells(&engine)
                        .iter()
                        .all(|&(x, y)| pixel(&engine, x, y)[3] == 255));
                }
            }
        }
    }
}

#[test]
fn bresenham_fills_long_segments_in_every_octant_without_a_gap() {
    for (dx, dy) in [
        (19i32, 7i32),
        (7, 19),
        (-19, 7),
        (-7, 19),
        (19, -7),
        (7, -19),
        (-19, -7),
        (-7, -19),
        (19, 0),
        (0, -19),
    ] {
        let mut engine = Engine::new(64, 64).unwrap();
        let target = ((32 + dx) as u32, (32 + dy) as u32);
        draw(
            &mut engine,
            brush(BrushRaster::Pixel),
            &[
                point(32.4, 32.6),
                point(target.0 as f32 + 0.2, target.1 as f32 + 0.8),
            ],
            1,
        );
        let painted = cells(&engine);
        assert_eq!(painted.len(), dx.abs().max(dy.abs()) as usize + 1);
        assert!(painted.contains(&(32, 32)) && painted.contains(&target));
        for &(x, y) in &painted {
            assert_eq!(pixel(&engine, x, y), [36, 108, 180, 255]);
            assert!(
                painted.len() == 1
                    || painted.iter().any(|&(nx, ny)| (nx, ny) != (x, y)
                        && nx.abs_diff(x) <= 1
                        && ny.abs_diff(y) <= 1)
            );
        }
    }
}

#[test]
fn pixel_perfect_removes_corner_pixels_but_retains_endpoints() {
    let points = [
        point(5.0, 5.0),
        point(6.0, 5.0),
        point(6.0, 6.0),
        point(7.0, 6.0),
        point(7.0, 7.0),
    ];
    let mut plain = Engine::new(24, 24).unwrap();
    let mut perfect = Engine::new(24, 24).unwrap();
    draw(&mut plain, brush(BrushRaster::Pixel), &points, 1);
    draw(&mut perfect, brush(BrushRaster::PixelPerfect), &points, 2);
    assert_eq!(
        cells(&plain),
        BTreeSet::from([(5, 5), (6, 5), (6, 6), (7, 6), (7, 7)])
    );
    assert_eq!(cells(&perfect), BTreeSet::from([(5, 5), (6, 6), (7, 7)]));
}

#[test]
fn pressure_changes_integer_width_and_large_nibs_do_not_skip_corners() {
    let mut engine = Engine::new(32, 32).unwrap();
    draw(
        &mut engine,
        Brush {
            size: 4.0,
            tip: BrushTip::Flat,
            ..brush(BrushRaster::PixelPerfect)
        },
        &[point(12.0, 12.0), point(13.0, 12.0), point(13.0, 13.0)],
        1,
    );
    assert_eq!(pixel(&engine, 14, 10)[3], 255);
    assert_eq!(pixel(&engine, 10, 10)[3], 255);
    assert_eq!(pixel(&engine, 15, 13)[3], 0);
    let mut pressure = Engine::new(32, 32).unwrap();
    draw(
        &mut pressure,
        Brush {
            size: 7.0,
            size_pressure: 1.0,
            ..brush(BrushRaster::Pixel)
        },
        &[
            Sample {
                pressure: 0.0,
                ..point(8.0, 12.0)
            },
            point(22.0, 12.0),
        ],
        1,
    );
    assert_eq!(pixel(&pressure, 8, 12)[3], 255);
    assert_eq!(pixel(&pressure, 8, 11)[3], 0);
    assert_eq!(pixel(&pressure, 22, 9)[3], 255);
    assert!(cells(&pressure)
        .into_iter()
        .all(|(x, y)| pixel(&pressure, x, y)[3] == 255));
}

#[test]
fn increasing_stationary_pressure_updates_the_nib_without_repeating_unchanged_dabs() {
    for raster in [BrushRaster::Pixel, BrushRaster::PixelPerfect] {
        let mut engine = Engine::new(32, 32).unwrap();
        draw(
            &mut engine,
            Brush {
                size: 7.0,
                size_pressure: 1.0,
                ..brush(raster)
            },
            &[
                Sample {
                    pressure: 0.0,
                    ..point(12.1, 12.1)
                },
                point(12.9, 12.9),
                point(12.4, 12.4),
            ],
            1,
        );
        assert_eq!(pixel(&engine, 12, 9)[3], 255);
        assert_eq!(pixel(&engine, 12, 8)[3], 0);
    }
}

#[test]
fn stationary_samples_do_not_darken_and_packets_preserve_identical_pixels() {
    let points = [
        point(5.1, 5.2),
        point(5.8, 5.9),
        point(6.0, 5.0),
        point(6.0, 6.0),
        point(18.0, 10.0),
        point(21.0, 22.0),
    ];
    for raster in [BrushRaster::Pixel, BrushRaster::PixelPerfect] {
        let nib = Brush {
            opacity: 0.5,
            stabilization: 0.8,
            spacing: 1.0,
            ..brush(raster)
        };
        let mut first = Engine::new(32, 32).unwrap();
        let mut second = Engine::new(32, 32).unwrap();
        draw(&mut first, nib, &points, 1);
        draw(&mut second, nib, &points, points.len());
        assert_eq!(first.export_png().unwrap(), second.export_png().unwrap());
        assert_eq!(pixel(&first, 5, 5)[3], 128);
    }
}

#[test]
fn cancel_and_undo_redo_restore_exact_pixels_and_content_identity() {
    for raster in [BrushRaster::Pixel, BrushRaster::PixelPerfect] {
        let mut engine = Engine::new(64, 64).unwrap();
        let empty = engine.save().unwrap();
        let state = engine.state();
        engine
            .command(Command::Begin {
                brush: brush(raster),
                assistant: None,
            })
            .unwrap();
        engine
            .samples(&[point(8.0, 9.0), point(54.0, 42.0)])
            .unwrap();
        engine.command(Command::Cancel).unwrap();
        assert_eq!(engine.save().unwrap(), empty);
        assert_eq!(engine.state(), state);
        draw(
            &mut engine,
            brush(raster),
            &[point(8.0, 9.0), point(54.0, 42.0)],
            1,
        );
        let painted = engine.save().unwrap();
        assert!(engine.state()["canUndo"].as_bool().unwrap());
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), empty);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), painted);
    }
}

#[test]
fn ellipse_selection_is_binary_for_pixel_brushes_and_erasers() {
    let mut engine = Engine::new(32, 32).unwrap();
    engine.command(serde_json::from_value(json!({"type":"select_shape","selection":{"kind":"ellipse","left":6,"top":6,"right":26,"bottom":26,"points":[]}})).unwrap()).unwrap();
    draw(
        &mut engine,
        Brush {
            size: 32.0,
            tip: BrushTip::Flat,
            ..brush(BrushRaster::Pixel)
        },
        &[point(16.0, 16.0)],
        1,
    );
    let selected = cells(&engine);
    assert!(selected.len() > 290 && selected.len() < 330);
    assert!(selected
        .iter()
        .all(|&(x, y)| pixel(&engine, x, y)[3] == 255));
    draw(
        &mut engine,
        Brush {
            size: 32.0,
            tip: BrushTip::Flat,
            eraser: true,
            ..brush(BrushRaster::PixelPerfect)
        },
        &[point(16.0, 16.0)],
        1,
    );
    assert!(cells(&engine).is_empty());
    engine.command(Command::Undo).unwrap();
    assert_eq!(cells(&engine), selected);
}

#[test]
fn symmetry_mirrors_integer_cells_without_double_blending_on_axes() {
    for raster in [BrushRaster::Pixel, BrushRaster::PixelPerfect] {
        let mut engine = Engine::new(31, 31).unwrap();
        draw(
            &mut engine,
            Brush {
                opacity: 0.5,
                symmetry: Symmetry {
                    mode: SymmetryMode::Quadrant,
                    ..Symmetry::default()
                },
                ..brush(raster)
            },
            &[point(5.0, 9.0), point(15.0, 15.0)],
            1,
        );
        for y in 0..31 {
            for x in 0..31 {
                assert_eq!(pixel(&engine, x, y), pixel(&engine, 30 - x, y));
                assert_eq!(pixel(&engine, x, y), pixel(&engine, x, 30 - y));
            }
        }
        assert_eq!(pixel(&engine, 15, 15)[3], 128);
    }
}

#[test]
fn transparent_noop_strokes_leave_history_and_tiles_untouched() {
    let mut engine = Engine::new(32, 32).unwrap();
    draw(
        &mut engine,
        brush(BrushRaster::Pixel),
        &[point(5.0, 5.0)],
        1,
    );
    engine.command(Command::Undo).unwrap();
    let state = engine.state();
    let saved = engine.save().unwrap();
    draw(
        &mut engine,
        Brush {
            opacity: 0.0,
            ..brush(BrushRaster::PixelPerfect)
        },
        &[point(10.0, 10.0), point(20.0, 20.0)],
        1,
    );
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), saved);
    assert_eq!(engine.document.tile_count(), 0);
}

#[test]
fn subpixel_selection_fragments_do_not_create_empty_tiles_or_history() {
    let mut engine = Engine::new(32, 32).unwrap();
    engine.command(serde_json::from_value(json!({"type":"select_shape","selection":{"kind":"lasso","left":10,"top":10,"right":11,"bottom":11,"points":[{"x":10.1,"y":10.1},{"x":10.4,"y":10.1},{"x":10.4,"y":10.9},{"x":10.1,"y":10.9}]}})).unwrap()).unwrap();
    let state = engine.state();
    let saved = engine.save().unwrap();
    draw(
        &mut engine,
        brush(BrushRaster::Pixel),
        &[point(10.0, 10.0)],
        1,
    );
    assert_eq!(engine.document.tile_count(), 0);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), saved);
}

#[test]
fn pixel_smudge_transports_existing_palette_colors_without_interpolation() {
    for raster in [BrushRaster::Pixel, BrushRaster::PixelPerfect] {
        let mut engine = Engine::new(32, 32).unwrap();
        draw(&mut engine, brush(raster), &[point(5.0, 5.0)], 1);
        draw(
            &mut engine,
            Brush {
                smudge: true,
                ..brush(raster)
            },
            &[point(5.2, 5.3), point(10.9, 8.1)],
            1,
        );
        assert_eq!(pixel(&engine, 10, 8), [36, 108, 180, 255]);
        assert!(cells(&engine)
            .iter()
            .all(|&(x, y)| pixel(&engine, x, y) == [36, 108, 180, 255]));
        let painted = engine.save().unwrap();
        engine.command(Command::Undo).unwrap();
        assert_eq!(cells(&engine), BTreeSet::from([(5, 5)]));
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), painted);
    }
}

#[test]
fn invalid_pixel_samples_reject_the_entire_packet_before_drawing() {
    let mut engine = Engine::new(32, 32).unwrap();
    engine
        .command(Command::Begin {
            brush: brush(BrushRaster::Pixel),
            assistant: None,
        })
        .unwrap();
    assert!(engine
        .samples(&[point(5.0, 5.0), point(f32::NAN, 12.0)])
        .is_err());
    engine.command(Command::End).unwrap();
    assert!(cells(&engine).is_empty());
    assert!(!engine.state()["canUndo"].as_bool().unwrap());
}

#[test]
fn pixel_previews_keep_the_real_one_pixel_nib_and_old_brushes_default_to_antialiasing() {
    let old: Brush = serde_json::from_value(
        json!({"size":1,"opacity":1,"hardness":1,"color":[0,0,0],"eraser":false}),
    )
    .unwrap();
    assert_eq!(old.raster, BrushRaster::Antialiased);
    for raster in [BrushRaster::Pixel, BrushRaster::PixelPerfect] {
        let preview = brush_preview(brush(raster)).unwrap();
        assert!(preview
            .as_chunks::<4>()
            .0
            .iter()
            .all(|p| p[3] == 0 || p[3] == 255));
        let imprint = (0..BRUSH_PREVIEW_HEIGHT)
            .flat_map(|y| (280..BRUSH_PREVIEW_WIDTH).map(move |x| (x, y)))
            .filter(|&(x, y)| preview[((y * BRUSH_PREVIEW_WIDTH + x) * 4 + 3) as usize] != 0)
            .collect::<Vec<_>>();
        assert_eq!(imprint, vec![(296, 48)]);
        assert!(
            preview
                .as_chunks::<4>()
                .0
                .iter()
                .filter(|p| p[3] > 0)
                .count()
                < 260
        );
    }
}
