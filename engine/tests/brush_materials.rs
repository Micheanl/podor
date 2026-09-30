use podor_engine::{brush_preview, model::*, Command, Engine};

const MATERIALS: [BrushTexture; 8] = [
    BrushTexture::Smooth,
    BrushTexture::Graphite,
    BrushTexture::Charcoal,
    BrushTexture::Bristle,
    BrushTexture::DryBristle,
    BrushTexture::Pigment,
    BrushTexture::Canvas,
    BrushTexture::Wash,
];

fn paint(brush: Brush, batch: usize) -> Engine {
    let mut engine = Engine::new(128, 128).unwrap();
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    let samples = (0..=80)
        .map(|i| Sample {
            x: 24.0 + i as f32,
            y: 64.0,
            pressure: 0.75,
        })
        .collect::<Vec<_>>();
    for chunk in samples.chunks(batch) {
        engine.samples(chunk).unwrap();
    }
    engine.command(Command::End).unwrap();
    engine
}

fn alpha(engine: &Engine, x: u32, y: u32) -> u8 {
    engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)][((y * 128 + x) * 4 + 3) as usize]
}

#[test]
fn dense_graphite_charcoal_and_dry_brushes_retain_unpainted_paper_tooth() {
    for texture in [
        BrushTexture::Graphite,
        BrushTexture::Charcoal,
        BrushTexture::DryBristle,
        BrushTexture::Canvas,
    ] {
        let engine = paint(
            Brush {
                size: 48.0,
                hardness: 1.0,
                opacity: 1.0,
                spacing: 0.02,
                texture,
                ..Brush::default()
            },
            1,
        );
        let values = (48..=80)
            .flat_map(|y| (48..=80).map(move |x| (x, y)))
            .map(|(x, y)| alpha(&engine, x, y))
            .collect::<Vec<_>>();
        assert!(
            values.iter().filter(|a| **a == 0).count() > 60,
            "{texture:?}"
        );
        assert!(
            values.iter().filter(|a| **a > 220).count() > 80,
            "{texture:?}"
        );
    }
}

#[test]
fn materials_are_distinct_under_the_same_tip_size_and_pressure() {
    let previews = MATERIALS.map(|texture| {
        brush_preview(Brush {
            size: 64.0,
            texture,
            ..Brush::default()
        })
        .unwrap()
    });
    for (index, first) in previews.iter().enumerate() {
        for second in &previews[index + 1..] {
            let difference: u64 = first
                .as_chunks::<4>()
                .0
                .iter()
                .zip(second.as_chunks::<4>().0)
                .map(|(a, b)| u64::from(a[3].abs_diff(b[3])))
                .sum();
            assert!(difference > 12_000, "indistinct material {index}");
        }
    }
}

#[test]
fn textured_strokes_are_packet_independent_and_survive_undo_and_redo() {
    for texture in MATERIALS {
        let brush = Brush {
            size: 32.0,
            opacity: 0.75,
            texture,
            ..Brush::default()
        };
        let mut first = paint(brush, 1);
        let second = paint(brush, 17);
        let painted = first.export_png().unwrap();
        assert_eq!(painted, second.export_png().unwrap(), "{texture:?}");
        first.command(Command::Undo).unwrap();
        assert_eq!(first.document.tile_count(), 0);
        first.command(Command::Redo).unwrap();
        assert_eq!(painted, first.export_png().unwrap());
    }
}

#[test]
fn materials_mirror_paper_and_nib_without_breaking_symmetry() {
    for texture in MATERIALS {
        for (tip, aspect) in [
            (BrushTip::Round, 1.0),
            (BrushTip::Flat, 0.4),
            (BrushTip::Leaf, 0.35),
            (BrushTip::Comb, 0.12),
        ] {
            let brush = Brush {
                size: 44.0,
                tip,
                aspect,
                angle: 27.0,
                texture,
                symmetry: Symmetry {
                    mode: SymmetryMode::Vertical,
                    ..Symmetry::default()
                },
                ..Brush::default()
            };
            let mut engine = Engine::new(128, 128).unwrap();
            engine
                .command(Command::Begin {
                    brush,
                    assistant: None,
                })
                .unwrap();
            engine
                .samples(&[Sample {
                    x: 30.0,
                    y: 64.0,
                    pressure: 0.8,
                }])
                .unwrap();
            engine.command(Command::End).unwrap();
            for y in 40..88 {
                for x in 12..48 {
                    assert_eq!(
                        alpha(&engine, x, y),
                        alpha(&engine, 127 - x, y),
                        "{texture:?} {x},{y}"
                    );
                }
            }
        }
    }
}

#[test]
fn old_brushes_default_to_smooth_and_invalid_textures_are_rejected() {
    let old = br#"{"size":20,"opacity":1,"hardness":1,"color":[0,0,0],"eraser":false}"#;
    let brush: Brush = serde_json::from_slice(old).unwrap();
    assert_eq!(brush.texture, BrushTexture::Smooth);
    let invalid = br#"{"size":20,"opacity":1,"hardness":1,"color":[0,0,0],"eraser":false,"texture":"unknown"}"#;
    assert!(serde_json::from_slice::<Brush>(invalid).is_err());
}

#[test]
fn previews_preserve_transparent_padding_and_premultiplied_alpha() {
    for texture in MATERIALS {
        let preview = brush_preview(Brush {
            size: 72.0,
            texture,
            ..Brush::default()
        })
        .unwrap();
        assert_eq!(
            preview.len(),
            (BRUSH_PREVIEW_WIDTH * BRUSH_PREVIEW_HEIGHT * 4) as usize
        );
        assert!(preview.as_chunks::<4>().0.iter().any(|pixel| pixel[3] > 0));
        assert!(preview
            .as_chunks::<4>()
            .0
            .iter()
            .all(|pixel| pixel[0] <= pixel[3] && pixel[1] <= pixel[3] && pixel[2] <= pixel[3]));
        assert!(preview[..BRUSH_PREVIEW_WIDTH as usize * 4]
            .iter()
            .all(|a| *a == 0));
        assert!(preview[preview.len() - BRUSH_PREVIEW_WIDTH as usize * 4..]
            .iter()
            .all(|a| *a == 0));
    }
    assert!(brush_preview(Brush {
        size: f32::NAN,
        ..Brush::default()
    })
    .is_err());
}

#[test]
fn previews_keep_the_stroke_separate_from_the_independent_nib_imprint() {
    for tip in [
        BrushTip::Round,
        BrushTip::Flat,
        BrushTip::Leaf,
        BrushTip::Comb,
    ] {
        let preview = brush_preview(Brush {
            size: 256.0,
            size_pressure: 0.0,
            tip,
            angle: 35.0,
            follow_direction: true,
            ..Brush::default()
        })
        .unwrap();
        let opacity = |x, y| preview[((y * BRUSH_PREVIEW_WIDTH + x) * 4 + 3) as usize];
        assert!((36..60).any(|y| (284..300).any(|x| opacity(x, y) > 0)));
        assert!((0..BRUSH_PREVIEW_WIDTH)
            .all(|x| opacity(x, 0) == 0 && opacity(x, BRUSH_PREVIEW_HEIGHT - 1) == 0));
        for y in 0..BRUSH_PREVIEW_HEIGHT {
            assert!(
                (272..278).all(|x| opacity(x, y) == 0),
                "tip overlaps imprint at row {y}"
            );
        }
    }
}

#[test]
fn glaze_and_large_pressure_independent_previews_preserve_edge_padding() {
    let glaze = Brush {
        size: 100.0,
        hardness: 0.2,
        opacity: 0.12,
        size_pressure: 0.2,
        texture: BrushTexture::Pigment,
        ..Brush::default()
    };
    let large = [
        BrushTip::Round,
        BrushTip::Flat,
        BrushTip::Leaf,
        BrushTip::Comb,
    ]
    .into_iter()
    .flat_map(|tip| {
        [false, true].map(move |follow_direction| Brush {
            size: 256.0,
            size_pressure: 0.0,
            tip,
            angle: 35.0,
            follow_direction,
            hardness: 1.0,
            ..Brush::default()
        })
    });
    for brush in std::iter::once(glaze).chain(large) {
        let preview = brush_preview(brush).unwrap();
        let opacity = |x, y| preview[((y * BRUSH_PREVIEW_WIDTH + x) * 4 + 3) as usize];
        assert!((30..66).any(|y| (50..240).any(|x| opacity(x, y) > 0)));
        for y in 0..BRUSH_PREVIEW_HEIGHT {
            assert!(
                (0..4).all(|x| opacity(x, y) == 0),
                "stroke head is cropped at row {y}"
            );
            assert!(
                (BRUSH_PREVIEW_WIDTH - 4..BRUSH_PREVIEW_WIDTH).all(|x| opacity(x, y) == 0),
                "stroke or imprint is cropped at row {y}"
            );
        }
        for x in 0..BRUSH_PREVIEW_WIDTH {
            assert_eq!(opacity(x, 0), 0, "stroke touches top at {x}");
            assert_eq!(
                opacity(x, BRUSH_PREVIEW_HEIGHT - 1),
                0,
                "stroke touches bottom at {x}"
            );
        }
    }
}
