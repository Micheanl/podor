use podor_engine::{
    assistants::{Geometry, Point, StrokeBinding},
    model::*,
    Command, Engine, ExportOptions, ResampleFilter,
};
use serde_json::{json, Value};
use std::sync::Arc;

fn send(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn edit(engine: &mut Engine, mut value: Value) {
    value["revision"] = engine.state()["revision"].clone();
    send(engine, value).unwrap();
}

fn assistant(engine: &mut Engine, geometry: Value, visible: bool) {
    edit(
        engine,
        json!({"type":"add_assistant","assistant":{"name":"Guide","visible":visible,"geometry":geometry}}),
    );
    edit(engine, json!({"type":"set_assistant_snap","id":1}));
}

fn parallel(engine: &mut Engine) {
    assistant(
        engine,
        json!({"kind":"parallel","a":{"x":-120,"y":5},"b":{"x":300,"y":5}}),
        true,
    );
}

fn sample(x: f32, y: f32, pressure: f32) -> Sample {
    Sample { x, y, pressure }
}

fn line(raw: &[Sample], origin: (f64, f64), direction: (f64, f64)) -> Vec<Sample> {
    raw.iter()
        .map(|point| {
            let t = ((f64::from(point.x) - origin.0) * direction.0
                + (f64::from(point.y) - origin.1) * direction.1)
                / (direction.0 * direction.0 + direction.1 * direction.1);
            sample(
                (origin.0 + t * direction.0) as f32,
                (origin.1 + t * direction.1) as f32,
                point.pressure,
            )
        })
        .collect()
}

fn hard(raster: BrushRaster) -> Brush {
    Brush {
        size: 1.0,
        hardness: 1.0,
        size_pressure: 0.0,
        color: [220, 30, 70],
        raster,
        ..Default::default()
    }
}

fn stroke(engine: &mut Engine, brush: Brush, points: &[Sample], batch: usize, explicit: bool) {
    let assistant = explicit.then(|| StrokeBinding {
        id: engine.document.assistants.snap_id.unwrap(),
        revision: engine.state()["revision"].as_u64().unwrap(),
    });
    engine.command(Command::Begin { brush, assistant }).unwrap();
    for chunk in points.chunks(batch) {
        engine.samples(chunk).unwrap();
    }
    engine.command(Command::End).unwrap();
}

fn same_pixels(actual: &Engine, expected: &Engine) {
    assert_eq!(
        (actual.document.width, actual.document.height),
        (expected.document.width, expected.document.height)
    );
    assert_eq!(actual.document.layers.len(), expected.document.layers.len());
    for (actual, expected) in actual.document.layers.iter().zip(&expected.document.layers) {
        assert!(
            actual.raster_opt() == expected.raster_opt(),
            "raw raster differs on layer {}",
            actual.id
        );
        assert!(
            actual.masks == expected.masks,
            "raw masks differ on layer {}",
            actual.id
        );
    }
    let png = |engine: &Engine| {
        engine
            .export_image(ExportOptions {
                transparent: true,
                ..Default::default()
            })
            .unwrap()
    };
    assert!(png(actual) == png(expected), "composited PNG differs");
}

fn alpha(engine: &Engine, x: u32, y: u32) -> u8 {
    let layer = &engine.document.layers[0];
    layer
        .raster()
        .unwrap()
        .tiles()
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or(0, |tile| {
            if let Some(palette) = &engine.document.palette {
                palette.colors[tile[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize] as usize]
                    [3]
            } else {
                tile[((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4 + 3) as usize]
            }
        })
}

fn native(indexed: bool) -> Engine {
    let mut engine = Engine::new(128, 96).unwrap();
    if indexed {
        send(&mut engine,json!({"type":"new_indexed","width":128,"height":96,
            "palette":{"colors":[[0,0,0,0],[220,30,70,255],[40,110,210,255]],"transparent":0,"order":[0,1,2]}})).unwrap();
    }
    engine
}

#[test]
fn parallel_strokes_follow_pen_down_axis_instead_of_guide_baseline_for_legacy_and_bound_begin() {
    let raw = [
        sample(20.5, 60.5, 1.0),
        sample(75.5, 15.5, 1.0),
        sample(130.5, 118.5, 1.0),
        sample(210.5, 31.5, 1.0),
    ];
    let straight = line(&raw, (20.5, 60.5), (1.0, 0.0));
    let brush = Brush {
        size: 3.0,
        ..hard(BrushRaster::Antialiased)
    };
    let mut expected = Engine::new(256, 160).unwrap();
    stroke(&mut expected, brush, &straight, 1, false);
    for explicit in [false, true] {
        for batch in [1, 4] {
            let mut actual = Engine::new(256, 160).unwrap();
            parallel(&mut actual);
            if explicit {
                stroke(&mut actual, brush, &raw, batch, true);
            } else {
                send(&mut actual,json!({"type":"begin","brush":{"size":3,"opacity":1,"hardness":1,"size_pressure":0,"color":[220,30,70],"eraser":false}})).unwrap();
                for chunk in raw.chunks(batch) {
                    actual.samples(chunk).unwrap();
                }
                actual.command(Command::End).unwrap();
            }
            same_pixels(&actual, &expected);
            assert_eq!(alpha(&actual, 130, 5), 0);
            assert_eq!(alpha(&actual, 130, 60), 255);
        }
    }
}

#[test]
fn radial_axis_passes_through_center_and_defers_center_origin_until_nonzero_motion() {
    for origin in [(40.5, 40.5), (16.5, 16.5)] {
        let raw = [
            sample(origin.0, origin.1, 1.0),
            sample(origin.0, origin.1, 1.0),
            sample(origin.0 + 30.0, origin.1 + 30.0, 1.0),
            sample(95.5, 29.5, 1.0),
            sample(81.5, 90.5, 1.0),
        ];
        let straight = line(&raw, (f64::from(origin.0), f64::from(origin.1)), (1.0, 1.0));
        let mut actual = native(false);
        assistant(
            &mut actual,
            json!({"kind":"radial","center":{"x":16.5,"y":16.5}}),
            true,
        );
        let mut expected = native(false);
        stroke(&mut actual, hard(BrushRaster::Pixel), &raw, 1, true);
        stroke(
            &mut expected,
            hard(BrushRaster::Pixel),
            &straight,
            raw.len(),
            false,
        );
        same_pixels(&actual, &expected);
        assert_eq!(alpha(&actual, 60, 60), 255);
        assert_eq!(alpha(&actual, 80, 30), 0);
    }
    let mut single = native(false);
    assistant(
        &mut single,
        json!({"kind":"radial","center":{"x":16.5,"y":16.5}}),
        true,
    );
    let mut expected = native(false);
    stroke(
        &mut single,
        hard(BrushRaster::Pixel),
        &[sample(16.5, 16.5, 1.0)],
        1,
        false,
    );
    stroke(
        &mut expected,
        hard(BrushRaster::Pixel),
        &[sample(16.5, 16.5, 1.0)],
        1,
        false,
    );
    same_pixels(&single, &expected);
}

#[test]
fn one_two_and_three_point_perspective_lock_the_first_motion_family_despite_later_turns() {
    let cases = [
        (
            json!([
                {"kind":"finite_vanishing_point","point":{"x":160.5,"y":80.5}},
                {"kind":"infinite_direction","direction":{"x":0,"y":3}},
                {"kind":"infinite_direction","direction":{"x":2,"y":2}}
            ]),
            sample(120.5, 83.5, 1.0),
            (1.0, 0.0),
        ),
        (
            json!([
                {"kind":"finite_vanishing_point","point":{"x":160.5,"y":80.5}},
                {"kind":"finite_vanishing_point","point":{"x":80.5,"y":-40.5}},
                {"kind":"infinite_direction","direction":{"x":2,"y":2}}
            ]),
            sample(83.5, 20.5, 1.0),
            (0.0, 1.0),
        ),
        (
            json!([
                {"kind":"finite_vanishing_point","point":{"x":160.5,"y":80.5}},
                {"kind":"finite_vanishing_point","point":{"x":80.5,"y":-40.5}},
                {"kind":"finite_vanishing_point","point":{"x":180.5,"y":180.5}}
            ]),
            sample(120.5, 118.5, 1.0),
            (1.0, 1.0),
        ),
    ];
    for (families, first, direction) in cases {
        let raw = [
            sample(80.5, 80.5, 1.0),
            sample(80.5, 80.5, 1.0),
            first,
            sample(20.5, 140.5, 1.0),
            sample(150.5, 40.5, 1.0),
        ];
        let straight = line(&raw, (80.5, 80.5), direction);
        let mut expected = Engine::new(192, 160).unwrap();
        stroke(&mut expected, hard(BrushRaster::Pixel), &straight, 1, false);
        for batch in [1, raw.len()] {
            let mut actual = Engine::new(192, 160).unwrap();
            assistant(
                &mut actual,
                json!({"kind":"perspective","families":families}),
                false,
            );
            stroke(&mut actual, hard(BrushRaster::Pixel), &raw, batch, true);
            same_pixels(&actual, &expected);
        }
    }
}

#[test]
fn perspective_origin_at_vanishing_point_and_equal_angle_ties_resolve_deterministically() {
    for (families, direction) in [
        (
            json!([
                {"kind":"finite_vanishing_point","point":{"x":40.5,"y":40.5}},
                {"kind":"finite_vanishing_point","point":{"x":160.5,"y":40.5}},
                {"kind":"infinite_direction","direction":{"x":0,"y":2}}
            ]),
            (1.0, 1.0),
        ),
        (
            json!([
                {"kind":"finite_vanishing_point","point":{"x":160.5,"y":40.5}},
                {"kind":"infinite_direction","direction":{"x":0,"y":2}},
                {"kind":"infinite_direction","direction":{"x":-2,"y":2}}
            ]),
            (1.0, 0.0),
        ),
    ] {
        let raw = [
            sample(40.5, 40.5, 1.0),
            sample(40.5, 40.5, 1.0),
            sample(65.5, 65.5, 1.0),
            sample(110.5, 20.5, 1.0),
        ];
        let straight = line(&raw, (40.5, 40.5), direction);
        let mut actual = native(false);
        assistant(
            &mut actual,
            json!({"kind":"perspective","families":families}),
            true,
        );
        let mut expected = native(false);
        stroke(&mut actual, hard(BrushRaster::Pixel), &raw, 2, false);
        stroke(&mut expected, hard(BrushRaster::Pixel), &straight, 1, false);
        same_pixels(&actual, &expected);
    }
}

#[test]
fn stabilized_pressure_and_leaf_finish_flick_match_manual_straight_input_across_batches() {
    let raw = (0..81)
        .map(|i| {
            sample(
                20.5 + i as f32 * 2.0,
                60.5 + if i == 0 {
                    0.0
                } else {
                    (i as f32 * 0.73).sin() * 26.0
                },
                0.15 + 0.75 * (i as f32 * std::f32::consts::PI / 80.0).sin(),
            )
        })
        .collect::<Vec<_>>();
    let straight = line(&raw, (20.5, 60.5), (1.0, 0.0));
    for tip in [BrushTip::Round, BrushTip::Leaf] {
        let brush = Brush {
            size: 32.0,
            opacity: 0.75,
            hardness: 1.0,
            stabilization: 0.8,
            tip,
            aspect: 0.42,
            spacing: 0.06,
            follow_direction: true,
            size_pressure: 0.85,
            opacity_pressure: 0.65,
            pressure_curve: -0.4,
            ..Default::default()
        };
        let mut expected = Engine::new(256, 160).unwrap();
        stroke(&mut expected, brush, &straight, 1, false);
        for batch in [1, 7, raw.len()] {
            let mut actual = Engine::new(256, 160).unwrap();
            parallel(&mut actual);
            stroke(&mut actual, brush, &raw, batch, true);
            same_pixels(&actual, &expected);
        }
    }
}

#[test]
fn stabilized_pen_up_reaches_projected_endpoint_without_a_cross_axis_tail() {
    let brush = Brush {
        size: 3.0,
        stabilization: 1.0,
        ..hard(BrushRaster::Antialiased)
    };
    let raw = [sample(20.5, 60.5, 1.0), sample(210.5, 120.5, 1.0)];
    let straight = line(&raw, (20.5, 60.5), (1.0, 0.0));
    let mut actual = Engine::new(256, 160).unwrap();
    parallel(&mut actual);
    actual
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    actual.samples(&raw).unwrap();
    assert_eq!(alpha(&actual, 210, 60), 0);
    actual.command(Command::End).unwrap();
    assert!(alpha(&actual, 210, 60) > 200);
    assert_eq!(alpha(&actual, 210, 120), 0);
    let mut expected = Engine::new(256, 160).unwrap();
    stroke(&mut expected, brush, &straight, 1, false);
    same_pixels(&actual, &expected);
}

#[test]
fn rgba_and_indexed_pixel_modes_finish_a_stabilized_axis_and_preserve_batching() {
    let raw = [
        sample(10.5, 30.5, 1.0),
        sample(30.5, 75.5, 1.0),
        sample(67.5, 8.5, 1.0),
        sample(110.5, 82.5, 1.0),
    ];
    let straight = line(&raw, (10.5, 30.5), (1.0, 0.0));
    for indexed in [false, true] {
        for raster in [BrushRaster::Pixel, BrushRaster::PixelPerfect] {
            let brush = Brush {
                stabilization: 1.0,
                index: indexed.then_some(1),
                ..hard(raster)
            };
            let mut expected = native(indexed);
            stroke(&mut expected, brush, &straight, 1, false);
            for batch in [1, 4] {
                let mut actual = native(indexed);
                parallel(&mut actual);
                stroke(&mut actual, brush, &raw, batch, true);
                same_pixels(&actual, &expected);
                for x in 10..=110 {
                    assert_eq!(alpha(&actual, x, 30), 255, "missing pixel {x}");
                }
                assert_eq!(alpha(&actual, 110, 82), 0);
            }
        }
    }
}

#[test]
fn disabled_guides_leave_dab_and_old_pixel_stabilizer_behavior_byte_identical() {
    let raw = (0..25)
        .map(|i| {
            sample(
                15.5 + i as f32 * 3.0,
                40.5 + (i as f32 * 0.7).sin() * 18.0,
                0.3 + i as f32 * 0.02,
            )
        })
        .collect::<Vec<_>>();
    for raster in [
        BrushRaster::Antialiased,
        BrushRaster::Pixel,
        BrushRaster::PixelPerfect,
    ] {
        let brush = Brush {
            size: 3.0,
            stabilization: 0.9,
            ..hard(raster)
        };
        let mut actual = native(false);
        parallel(&mut actual);
        edit(&mut actual, json!({"type":"set_assistant_snap","id":null}));
        let mut expected = native(false);
        stroke(&mut actual, brush, &raw, 1, false);
        stroke(&mut expected, brush, &raw, raw.len(), false);
        same_pixels(&actual, &expected);
        if raster != BrushRaster::Antialiased {
            let mut unfiltered = native(false);
            stroke(
                &mut unfiltered,
                Brush {
                    stabilization: 0.0,
                    ..brush
                },
                &raw,
                3,
                false,
            );
            same_pixels(&actual, &unfiltered);
        }
    }
}

#[test]
fn symmetry_mirrors_the_constrained_base_without_projecting_copies_back_to_it() {
    let raw = [
        sample(16.5, 24.5, 1.0),
        sample(50.5, 38.5, 1.0),
        sample(40.5, 48.5, 1.0),
    ];
    let straight = line(&raw, (16.5, 24.5), (1.0, 1.0));
    for mode in [
        SymmetryMode::Vertical,
        SymmetryMode::Horizontal,
        SymmetryMode::Quadrant,
    ] {
        let brush = Brush {
            symmetry: Symmetry {
                mode,
                ..Default::default()
            },
            ..hard(BrushRaster::Pixel)
        };
        let mut actual = Engine::new(128, 128).unwrap();
        assistant(
            &mut actual,
            json!({"kind":"parallel","a":{"x":0,"y":0},"b":{"x":50,"y":50}}),
            true,
        );
        let mut expected = Engine::new(128, 128).unwrap();
        stroke(&mut actual, brush, &raw, 1, false);
        stroke(&mut expected, brush, &straight, 1, false);
        same_pixels(&actual, &expected);
        assert_eq!(alpha(&actual, 40, 48), 255);
        if mode != SymmetryMode::Horizontal {
            assert_eq!(alpha(&actual, 87, 48), 255);
        }
        if mode != SymmetryMode::Vertical {
            assert_eq!(alpha(&actual, 40, 79), 255);
        }
    }
}

#[test]
fn constrained_gray_mask_brush_and_eraser_respect_selection_and_keep_raw_color_planes() {
    let raw = [
        sample(10.5, 30.5, 1.0),
        sample(50.5, 72.5, 1.0),
        sample(110.5, 12.5, 1.0),
    ];
    let straight = line(&raw, (10.5, 30.5), (1.0, 0.0));
    for indexed in [false, true] {
        let prepare = || {
            let mut engine = native(indexed);
            let mut tile = vec![
                0;
                if indexed {
                    INDEX_TILE_BYTES
                } else {
                    TILE_BYTES
                }
            ];
            for y in 0..96 {
                for x in 0..128 {
                    let offset = y * TILE_SIZE as usize + x;
                    if indexed {
                        tile[offset] = 1;
                    } else {
                        tile[offset * 4..offset * 4 + 4].copy_from_slice(&[220, 30, 70, 255]);
                    }
                }
            }
            engine.document.layers[0]
                .raster_mut()
                .unwrap()
                .tiles_mut()
                .insert((0, 0), Arc::new(tile));
            edit(&mut engine, json!({"type":"add_mask","mode":"reveal"}));
            engine
                .command(Command::Select {
                    rect: Some(Rect {
                        left: 14,
                        top: 25,
                        right: 75,
                        bottom: 42,
                    }),
                })
                .unwrap();
            engine
        };
        let mut actual = prepare();
        let original = actual.document.layers[0].raster().unwrap().clone();
        parallel(&mut actual);
        let mut expected = prepare();
        let brush = Brush {
            color: [96; 3],
            ..hard(BrushRaster::Pixel)
        };
        stroke(&mut actual, brush, &raw, 1, true);
        stroke(&mut expected, brush, &straight, 3, false);
        same_pixels(&actual, &expected);
        assert_eq!(
            actual.document.layers[0]
                .first_mask()
                .unwrap()
                .sample(20, 30),
            96
        );
        assert_eq!(
            actual.document.layers[0]
                .first_mask()
                .unwrap()
                .sample(90, 30),
            255
        );
        let erase = [sample(40.5, 30.5, 1.0), sample(70.5, 78.5, 1.0)];
        let erase_line = line(&erase, (40.5, 30.5), (1.0, 0.0));
        stroke(
            &mut actual,
            Brush {
                eraser: true,
                ..brush
            },
            &erase,
            1,
            false,
        );
        stroke(
            &mut expected,
            Brush {
                eraser: true,
                ..brush
            },
            &erase_line,
            1,
            false,
        );
        same_pixels(&actual, &expected);
        assert_eq!(
            actual.document.layers[0]
                .first_mask()
                .unwrap()
                .sample(60, 30),
            0
        );
        for (key, tile) in original.tiles() {
            assert!(Arc::ptr_eq(
                tile,
                &actual.document.layers[0].raster().unwrap().tiles()[key]
            ));
        }
    }
}

#[test]
fn cancel_undo_redo_restore_guides_and_one_whole_stroke_with_captured_binding() {
    let mut engine = native(false);
    parallel(&mut engine);
    let before = engine.save().unwrap();
    let guides = engine.document.assistants.clone();
    let points = [sample(10.5, 30.5, 1.0), sample(110.5, 83.5, 1.0)];
    let brush = Brush {
        size: 8.0,
        stabilization: 0.7,
        ..hard(BrushRaster::Antialiased)
    };
    engine
        .command(Command::Begin {
            brush,
            assistant: None,
        })
        .unwrap();
    engine.samples(&points).unwrap();
    let state = engine.state();
    assert!(send(
        &mut engine,
        json!({"type":"delete_assistant","id":1,"revision":state["revision"]})
    )
    .is_err());
    assert_eq!(engine.document.assistants, guides);
    engine.command(Command::Cancel).unwrap();
    engine.command(Command::End).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    stroke(&mut engine, brush, &points, 1, true);
    let painted = engine.save().unwrap();
    assert_ne!(painted, before);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.document.assistants, guides);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), painted);
    assert_eq!(engine.document.assistants, guides);
}

#[test]
fn smudge_ignores_implicit_global_snap_and_rejects_explicit_binding_atomically() {
    let prepare = || {
        let mut engine = native(false);
        let mut tile = vec![0; TILE_BYTES];
        for y in 0..96 {
            for x in 0..128 {
                let offset = (y * TILE_SIZE as usize + x) * 4;
                tile[offset..offset + 4].copy_from_slice(if x < 48 {
                    &[180, 40, 60, 255]
                } else {
                    &[20, 80, 200, 255]
                });
            }
        }
        engine.document.layers[0]
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert((0, 0), Arc::new(tile));
        engine
    };
    let mut actual = prepare();
    parallel(&mut actual);
    let before = actual.save().unwrap();
    let state = actual.state();
    actual.frame();
    let brush = Brush {
        size: 16.0,
        hardness: 0.8,
        opacity: 0.8,
        smudge: true,
        size_pressure: 0.0,
        stabilization: 0.2,
        ..Default::default()
    };
    assert!(actual
        .command(Command::Begin {
            brush,
            assistant: Some(StrokeBinding {
                id: 1,
                revision: state["revision"].as_u64().unwrap()
            })
        })
        .is_err());
    assert_eq!(actual.save().unwrap(), before);
    assert_eq!(actual.state(), state);
    assert_eq!(actual.frame().len(), 16);
    let raw = [
        sample(35.5, 32.5, 1.0),
        sample(48.5, 43.5, 1.0),
        sample(62.5, 57.5, 1.0),
    ];
    let mut expected = prepare();
    stroke(&mut actual, brush, &raw, 1, false);
    stroke(&mut expected, brush, &raw, raw.len(), false);
    same_pixels(&actual, &expected);
    assert_ne!(actual.save().unwrap(), before);
    assert_eq!(actual.document.assistants.snap_id, Some(1));
}

#[test]
fn metadata_mutations_are_one_undo_and_noop_stale_invalid_and_capacity_keep_history_and_dirty() {
    let mut engine = native(false);
    engine.frame();
    let initial = engine.save().unwrap();
    let spec = json!({"name":"Guide","visible":true,"geometry":{"kind":"parallel","a":{"x":0,"y":0},"b":{"x":20,"y":0}}});
    edit(
        &mut engine,
        json!({"type":"add_assistant","assistant":spec}),
    );
    let added = engine.save().unwrap();
    assert_eq!(engine.document.assistants.next_id, 2);
    assert_eq!(engine.document.assistants.snap_id, None);
    assert_eq!(engine.frame().len(), 16);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), initial);
    assert_eq!(engine.state()["canUndo"], false);
    assert_eq!(engine.frame().len(), 16);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), added);
    edit(&mut engine, json!({"type":"set_assistant_snap","id":1}));
    engine
        .command(Command::SetLayer {
            id: 1,
            name: "Redo checkpoint".into(),
            visible: true,
            opacity: 1.0,
        })
        .unwrap();
    let redo = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    engine.frame();
    let before = engine.save().unwrap();
    let state = engine.state();
    for noop in [
        json!({"type":"set_assistant","id":1,"assistant":spec}),
        json!({"type":"set_assistant_snap","id":1}),
    ] {
        edit(&mut engine, noop);
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame().len(), 16);
    }
    let revision = state["revision"].as_u64().unwrap();
    for invalid in [
        json!({"type":"delete_assistant","id":1,"revision":revision-1}),
        json!({"type":"set_assistant_snap","id":99,"revision":revision}),
        json!({"type":"delete_assistant","id":99,"revision":revision}),
        json!({"type":"add_assistant","assistant":{"name":"Invalid","visible":true,"geometry":{"kind":"parallel","a":{"x":1,"y":1},"b":{"x":1,"y":1}}},"revision":revision}),
    ] {
        assert!(send(&mut engine, invalid).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame().len(), 16);
    }
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), redo);
    for _ in 1..MAX_DRAWING_ASSISTANTS {
        edit(
            &mut engine,
            json!({"type":"add_assistant","assistant":spec}),
        );
    }
    engine.frame();
    let before = engine.save().unwrap();
    let state = engine.state();
    assert!(send(
        &mut engine,
        json!({"type":"add_assistant","assistant":spec,"revision":state["revision"]})
    )
    .is_err());
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame().len(), 16);
}

#[test]
fn canvas_translation_and_image_scaling_transform_finite_anchors_and_infinite_directions_atomically(
) {
    let mut engine = Engine::new(100, 80).unwrap();
    for geometry in [
        json!({"kind":"parallel","a":{"x":-10.5,"y":20.25},"b":{"x":30.5,"y":60.25}}),
        json!({"kind":"radial","center":{"x":200,"y":-30}}),
        json!({"kind":"perspective","families":[
            {"kind":"finite_vanishing_point","point":{"x":-100,"y":40}},
            {"kind":"infinite_direction","direction":{"x":2,"y":1}},
            {"kind":"infinite_direction","direction":{"x":0,"y":5}}
        ]}),
    ] {
        edit(
            &mut engine,
            json!({"type":"add_assistant","assistant":{"name":"Guide","visible":false,"geometry":geometry}}),
        );
    }
    edit(&mut engine, json!({"type":"set_assistant_snap","id":3}));
    let before = engine.save().unwrap();
    edit(
        &mut engine,
        json!({"type":"resize_canvas","width":140,"height":100,"anchor":4}),
    );
    let moved = engine.save().unwrap();
    let controls = &engine.document.assistants.items;
    assert_eq!(
        controls[0].geometry,
        Geometry::Parallel {
            a: Point { x: 9.5, y: 30.25 },
            b: Point { x: 50.5, y: 70.25 }
        }
    );
    assert_eq!(
        controls[1].geometry,
        Geometry::Radial {
            center: Point { x: 220.0, y: -20.0 }
        }
    );
    let families = engine.state()["assistants"]["items"][2]["geometry"]["families"].clone();
    assert_eq!(families[0]["point"], json!({"x":-80.0,"y":50.0}));
    assert_eq!(families[1]["direction"], json!({"x":2.0,"y":1.0}));
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), moved);
    engine
        .command(Command::ResizeImage {
            width: 70,
            height: 200,
            filter: ResampleFilter::Nearest,
            revision: engine.state()["revision"].as_u64().unwrap(),
        })
        .unwrap();
    let scaled = engine.save().unwrap();
    let controls = &engine.document.assistants.items;
    assert_eq!(
        controls[0].geometry,
        Geometry::Parallel {
            a: Point { x: 4.75, y: 60.5 },
            b: Point { x: 25.25, y: 140.5 }
        }
    );
    assert_eq!(
        controls[1].geometry,
        Geometry::Radial {
            center: Point { x: 110.0, y: -40.0 }
        }
    );
    let families = engine.state()["assistants"]["items"][2]["geometry"]["families"].clone();
    assert_eq!(families[0]["point"], json!({"x":-40.0,"y":100.0}));
    assert_eq!(families[1]["direction"], json!({"x":1.0,"y":2.0}));
    assert_eq!(families[2]["direction"], json!({"x":0.0,"y":10.0}));
    assert_eq!(engine.document.assistants.snap_id, Some(3));
    assert_eq!(engine.document.assistants.next_id, 4);
    assert!(engine
        .document
        .assistants
        .items
        .iter()
        .all(|item| !item.visible));
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), moved);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), scaled);
}

#[test]
fn layer_and_group_geometry_operations_leave_global_guides_unchanged() {
    let mut engine = native(false);
    stroke(
        &mut engine,
        hard(BrushRaster::Pixel),
        &[sample(20.5, 30.5, 1.0), sample(40.5, 30.5, 1.0)],
        1,
        false,
    );
    parallel(&mut engine);
    let guides = engine.document.assistants.clone();
    edit(
        &mut engine,
        json!({"type":"translate_layer","id":1,"dx":5,"dy":7}),
    );
    assert_eq!(engine.document.assistants, guides);
    edit(
        &mut engine,
        json!({"type":"create_group","name":"Group","parent_id":null,"index":1,"isolation":"isolated"}),
    );
    edit(
        &mut engine,
        json!({"type":"move_node","id":1,"parent_id":2,"index":0}),
    );
    edit(
        &mut engine,
        json!({"type":"translate_layer","id":2,"dx":-3,"dy":4}),
    );
    assert_eq!(engine.document.assistants, guides);
    engine.command(Command::SelectLayer { id: 2 }).unwrap();
    edit(
        &mut engine,
        json!({"type":"transform_layer","id":2,"transform":{"width":25,"height":5,"dx":2,"dy":-2,"angle":15,"filter":"nearest"}}),
    );
    assert_eq!(engine.document.assistants, guides);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.document.assistants, guides);
}

#[test]
fn world_limit_clamps_axis_parameter_and_preserves_collinearity_at_canvas_entry() {
    let mut actual = native(false);
    assistant(
        &mut actual,
        json!({"kind":"parallel","a":{"x":0,"y":0},"b":{"x":3,"y":1}}),
        true,
    );
    let mut expected = native(false);
    let raw = [sample(0.5, 0.5, 1.0), sample(16384.0, 16384.0, 1.0)];
    let straight = [sample(0.5, 0.5, 1.0), sample(16384.0, 5461.6665, 1.0)];
    stroke(&mut actual, hard(BrushRaster::Pixel), &raw, 1, true);
    stroke(&mut expected, hard(BrushRaster::Pixel), &straight, 1, false);
    same_pixels(&actual, &expected);
    assert_eq!(alpha(&actual, 90, 30), 255);
    assert_eq!(alpha(&actual, 90, 36), 0);
}
