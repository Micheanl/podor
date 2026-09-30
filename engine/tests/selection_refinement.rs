use podor_engine::{model::*, Command, Engine};
use serde_json::{json, Value};
use std::sync::Arc;

fn command(engine: &mut Engine, value: Value) -> Result<Value, String> {
    engine.command(serde_json::from_value(value).unwrap())
}

fn select(engine: &mut Engine, left: u32, top: u32, right: u32, bottom: u32) {
    command(
        engine,
        json!({"type":"select","rect":{"left":left,"top":top,"right":right,"bottom":bottom}}),
    )
    .unwrap();
}

fn request(engine: &Engine, kind: &str, radius: u32) -> Value {
    let state = engine.state();
    json!({"type":"modify_selection","kind":kind,"radius":radius,
        "revision":state["revision"],"selection_id":state["selectionId"]})
}

fn refine(engine: &mut Engine, kind: &str, radius: u32) {
    let value = request(engine, kind, radius);
    command(engine, value).unwrap();
}

fn coverage(engine: &Engine) -> Vec<u8> {
    let mut output = vec![0; (engine.document.width * engine.document.height) as usize];
    let bytes = engine.selection_outline_mask();
    let read = |offset| u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap());
    assert_eq!(read(0), 1);
    let size = read(4);
    let stride = 8 + (size * size) as usize;
    assert_eq!(bytes.len(), 12 + read(8) as usize * stride);
    for index in 0..read(8) as usize {
        let offset = 12 + index * stride;
        let left = read(offset) * size;
        let top = read(offset + 4) * size;
        for y in top..(top + size).min(engine.document.height) {
            for x in left..(left + size).min(engine.document.width) {
                output[(y * engine.document.width + x) as usize] =
                    bytes[offset + 8 + ((y - top) * size + x - left) as usize];
            }
        }
    }
    output
}

fn extrema(source: &[u8], width: usize, height: usize, radius: i32, maximum: bool) -> Vec<u8> {
    (0..width * height)
        .map(|index| {
            let x = (index % width) as i32;
            let y = (index / width) as i32;
            let mut value = if maximum { 0 } else { 255 };
            for dy in -radius..=radius {
                for dx in -radius..=radius {
                    let px = x + dx;
                    let py = y + dy;
                    let next = if px < 0 || py < 0 || px >= width as i32 || py >= height as i32 {
                        0
                    } else {
                        source[py as usize * width + px as usize]
                    };
                    value = if maximum {
                        value.max(next)
                    } else {
                        value.min(next)
                    };
                }
            }
            value
        })
        .collect()
}

#[test]
fn expansion_and_contraction_use_square_neighborhoods_with_zero_outside_canvas() {
    let mut engine = Engine::new(9, 9).unwrap();
    select(&mut engine, 4, 4, 5, 5);
    refine(&mut engine, "expand", 1);
    let expanded = coverage(&engine);
    for y in 0..9 {
        for x in 0..9 {
            assert_eq!(
                expanded[y * 9 + x],
                if (3..6).contains(&x) && (3..6).contains(&y) {
                    255
                } else {
                    0
                }
            );
        }
    }
    refine(&mut engine, "contract", 1);
    let contracted = coverage(&engine);
    assert_eq!(contracted.iter().filter(|&&value| value != 0).count(), 1);
    assert_eq!(contracted[4 * 9 + 4], 255);
    select(&mut engine, 0, 0, 9, 9);
    refine(&mut engine, "contract", 1);
    assert_eq!(coverage(&engine), extrema(&[255; 81], 9, 9, 1, false));
}

#[test]
fn gray_antialiased_edges_match_an_independent_two_dimensional_extrema_reference() {
    for kind in ["ellipse", "lasso"] {
        let mut engine = Engine::new(24, 20).unwrap();
        let shape = if kind == "ellipse" {
            json!({"kind":"ellipse","left":2,"top":3,"right":20,"bottom":17})
        } else {
            json!({"kind":"lasso","left":0,"top":0,"right":24,"bottom":20,
                "points":[{"x":1.2,"y":2.4},{"x":21.7,"y":5.3},{"x":6.4,"y":18.1}]})
        };
        for maximum in [true, false] {
            command(
                &mut engine,
                json!({"type":"select_shape","selection":shape}),
            )
            .unwrap();
            let before = coverage(&engine);
            assert!(before.iter().any(|&value| value > 0 && value < 255));
            let expected = extrema(&before, 24, 20, 2, maximum);
            refine(&mut engine, if maximum { "expand" } else { "contract" }, 2);
            assert_eq!(coverage(&engine), expected);
        }
    }
}

#[test]
fn smoothing_removes_small_holes_and_isolated_pixels_but_preserves_large_holes() {
    let mut engine = Engine::new(28, 24).unwrap();
    select(&mut engine, 4, 4, 24, 20);
    for (mode, area) in [
        ("subtract", [7, 7, 8, 8]),
        ("subtract", [12, 9, 18, 15]),
        ("add", [1, 1, 2, 2]),
    ] {
        command(
            &mut engine,
            json!({"type":"combine_selection","mode":mode,
            "selection":{"left":area[0],"top":area[1],"right":area[2],"bottom":area[3]}}),
        )
        .unwrap();
    }
    let mut expected = coverage(&engine);
    for maximum in [true, false, false, true] {
        expected = extrema(&expected, 28, 24, 1, maximum);
    }
    refine(&mut engine, "smooth", 1);
    assert_eq!(coverage(&engine), expected);
    assert_eq!(expected[7 * 28 + 7], 255);
    assert_eq!(expected[12 * 28 + 15], 0);
    assert_eq!(expected[28 + 1], 0);
}

#[test]
fn feather_spreads_fractional_coverage_symmetrically_and_fades_at_the_canvas_edge() {
    let mut engine = Engine::new(17, 17).unwrap();
    select(&mut engine, 8, 8, 9, 9);
    refine(&mut engine, "feather", 1);
    let mask = coverage(&engine);
    for y in 0..17 {
        for x in 0..17 {
            assert_eq!(
                mask[y * 17 + x],
                if (7..10).contains(&x) && (7..10).contains(&y) {
                    28
                } else {
                    0
                }
            );
        }
    }
    select(&mut engine, 4, 4, 13, 13);
    refine(&mut engine, "feather", 2);
    let mask = coverage(&engine);
    assert_eq!(
        &mask[8 * 17..8 * 17 + 9],
        &[6, 23, 57, 102, 153, 198, 232, 249, 255]
    );
    assert_eq!(mask[8 * 17 + 8], 255);
    assert!(mask[8 * 17 + 2] > 0 && mask[8 * 17 + 2] < mask[8 * 17 + 4]);
    for y in 0..17 {
        for x in 0..17 {
            assert_eq!(mask[y * 17 + x], mask[y * 17 + 16 - x]);
            assert_eq!(mask[y * 17 + x], mask[(16 - y) * 17 + x]);
        }
    }
    select(&mut engine, 0, 0, 17, 17);
    refine(&mut engine, "feather", 1);
    let mask = coverage(&engine);
    assert_eq!(mask[0], 113);
    assert_eq!(mask[8], 170);
    assert_eq!(mask[8 * 17 + 8], 255);
}

#[test]
fn zero_radius_empty_and_unchanged_results_preserve_selection_identity_and_history() {
    let mut engine = Engine::new(16, 16).unwrap();
    command(
        &mut engine,
        json!({"type":"fill","x":0,"y":0,"color":[80,40,20,255],"tolerance":0}),
    )
    .unwrap();
    engine.command(Command::Undo).unwrap();
    select(&mut engine, 3, 3, 13, 13);
    let state = engine.state();
    let saved = engine.save().unwrap();
    let mask = coverage(&engine);
    for kind in ["expand", "contract", "smooth", "feather"] {
        refine(&mut engine, kind, 0);
        assert_eq!(engine.state(), state);
    }
    refine(&mut engine, "smooth", 1);
    assert_eq!(engine.state(), state);
    assert_eq!(coverage(&engine), mask);
    assert_eq!(engine.save().unwrap(), saved);
    assert_eq!(engine.state()["canRedo"], true);
    select(&mut engine, 0, 0, 16, 16);
    let state = engine.state();
    refine(&mut engine, "expand", 5);
    assert_eq!(engine.state(), state);
    refine(&mut engine, "contract", 8192);
    assert_eq!(engine.state()["selection"]["empty"], true);
    let state = engine.state();
    for kind in ["expand", "contract", "smooth", "feather"] {
        refine(&mut engine, kind, 4);
        assert_eq!(engine.state(), state);
    }
}

#[test]
fn refinement_changes_only_selection_and_does_not_touch_pixel_storage_or_redo() {
    let mut engine = Engine::new(16, 16).unwrap();
    command(
        &mut engine,
        json!({"type":"fill","x":0,"y":0,"color":[80,40,20,255],"tolerance":0}),
    )
    .unwrap();
    command(
        &mut engine,
        json!({"type":"fill","x":0,"y":0,"color":[20,100,220,255],"tolerance":0}),
    )
    .unwrap();
    engine.command(Command::Undo).unwrap();
    select(&mut engine, 6, 6, 10, 10);
    let state = engine.state();
    let saved = engine.save().unwrap();
    let pixels = engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)].clone();
    engine.frame();
    refine(&mut engine, "expand", 2);
    let after = engine.state();
    assert_eq!(
        after["selectionId"].as_u64(),
        Some(state["selectionId"].as_u64().unwrap() + 1)
    );
    for field in ["revision", "contentId", "canUndo", "canRedo", "layers"] {
        assert_eq!(after[field], state[field]);
    }
    assert!(Arc::ptr_eq(
        &pixels,
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)]
    ));
    assert_eq!(engine.save().unwrap(), saved);
    assert_eq!(engine.frame().len(), 16);
    engine.command(Command::Redo).unwrap();
    assert_eq!(
        &engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)][..4],
        &[20, 100, 220, 255]
    );
}

#[test]
fn stale_selection_artwork_revision_invalid_radius_and_active_stroke_are_atomic() {
    let mut engine = Engine::new(16, 16).unwrap();
    assert!(command(
        &mut engine,
        request(&Engine::new(16, 16).unwrap(), "expand", 1)
    )
    .is_err());
    select(&mut engine, 3, 3, 9, 9);
    let stale = request(&engine, "expand", 1);
    select(&mut engine, 4, 4, 10, 10);
    assert_eq!(engine.state()["selection"]["id"], 0);
    let state = engine.state();
    let mask = coverage(&engine);
    assert!(command(&mut engine, stale).is_err());
    assert_eq!(engine.state(), state);
    let stale = request(&engine, "feather", 1);
    command(
        &mut engine,
        json!({"type":"fill","x":5,"y":5,"color":[255,0,0,255],"tolerance":0}),
    )
    .unwrap();
    let state = engine.state();
    let saved = engine.save().unwrap();
    assert!(command(&mut engine, stale).is_err());
    let too_large = request(&engine, "expand", 8193);
    assert!(command(&mut engine, too_large).is_err());
    for radius in [json!(-1), json!(1.5), Value::Null] {
        let mut invalid = request(&engine, "expand", 1);
        invalid["radius"] = radius;
        assert!(serde_json::from_value::<Command>(invalid).is_err());
    }
    let mut invalid = request(&engine, "unknown", 1);
    assert!(serde_json::from_value::<Command>(invalid.clone()).is_err());
    invalid["kind"] = json!("expand");
    invalid["selection_id"] = json!(-1);
    assert!(serde_json::from_value::<Command>(invalid).is_err());
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), saved);
    assert_eq!(coverage(&engine), mask);
    engine
        .command(Command::Begin {
            brush: Brush::default(),
            assistant: None,
        })
        .unwrap();
    let current = request(&engine, "expand", 1);
    assert!(command(&mut engine, current).is_err());
    engine.command(Command::End).unwrap();
    assert_eq!(coverage(&engine), mask);
}

#[test]
fn refined_gray_coverage_is_used_once_by_fill_and_by_selection_mask_creation() {
    let mut engine = Engine::new(24, 24).unwrap();
    select(&mut engine, 6, 6, 18, 18);
    refine(&mut engine, "feather", 2);
    let mask = coverage(&engine);
    assert!(mask.iter().any(|&value| value > 0 && value < 255));
    command(&mut engine, json!({"type":"fill","x":12,"y":12,"color":[255,255,255,255],"tolerance":0,"contiguous":false})).unwrap();
    for y in 0..24 {
        for x in 0..24 {
            assert_eq!(
                engine.document.layers[0].raster().unwrap().tiles()[&(0, 0)][(y * 128 + x) * 4 + 3],
                mask[y * 24 + x]
            );
        }
    }
    command(&mut engine, json!({"type":"add_mask","mode":"selection"})).unwrap();
    let layer_mask = engine.document.layers[0].first_mask().unwrap();
    for y in 0..24 {
        for x in 0..24 {
            assert_eq!(layer_mask.sample(x as i32, y as i32), mask[y * 24 + x]);
        }
    }
}

#[test]
fn maximum_supported_canvas_and_radius_complete_without_artwork_or_history_allocation() {
    let mut engine = Engine::new(4096, 4096).unwrap();
    select(&mut engine, 2048, 2048, 2049, 2049);
    refine(&mut engine, "expand", 8192);
    let selection = engine.state()["selection"].clone();
    assert_eq!(selection["left"], 0);
    assert_eq!(selection["top"], 0);
    assert_eq!(selection["right"], 4096);
    assert_eq!(selection["bottom"], 4096);
    refine(&mut engine, "contract", 8192);
    assert_eq!(engine.state()["selection"]["empty"], true);
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
    assert_eq!(engine.state()["revision"], 0);
    assert_eq!(engine.state()["canUndo"], false);
}

#[test]
fn feather_outline_uses_half_coverage_without_discarding_soft_pixel_coverage() {
    let mut engine = Engine::new(17, 17).unwrap();
    select(&mut engine, 5, 5, 12, 12);
    refine(&mut engine, "feather", 1);
    let mask = coverage(&engine);
    assert_eq!(mask[8 * 17 + 4], 85);
    let bytes = engine.selection_outline();
    assert_eq!(&bytes[..4], &0u32.to_le_bytes());
    assert!(bytes.len() > 4);
    for line in bytes[4..].as_chunks::<16>().0 {
        let values: Vec<u32> = line
            .as_chunks::<4>()
            .0
            .iter()
            .map(|value| u32::from_le_bytes(*value))
            .collect();
        assert!(values.iter().all(|&value| (5..=12).contains(&value)));
        if values[1] == values[3] {
            let y = values[1] as usize;
            for x in values[0] as usize..values[2] as usize {
                assert_ne!(mask[y * 17 + x] >= 128, mask[(y - 1) * 17 + x] >= 128);
            }
        } else {
            let x = values[0] as usize;
            for y in values[1] as usize..values[3] as usize {
                assert_ne!(mask[y * 17 + x] >= 128, mask[y * 17 + x - 1] >= 128);
            }
        }
    }
    select(&mut engine, 8, 8, 9, 9);
    refine(&mut engine, "feather", 1);
    assert_eq!(engine.state()["selection"]["empty"], false);
    assert_eq!(coverage(&engine)[8 * 17 + 8], 28);
    assert_eq!(engine.selection_outline(), 0u32.to_le_bytes());
}
