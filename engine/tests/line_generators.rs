use podor_engine::{
    model::*,
    vector::{Geometry, Segment, VectorObject},
    AdjustmentEffect, AdjustmentSpec, Command, Engine, LayerActionRequest,
};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

type Frame = BTreeMap<TileKey, Vec<u8>>;
type Point = (f64, f64);

fn send(engine: &mut Engine, mut value: Value) -> Result<Value, String> {
    if value.get("revision").is_none() {
        value["revision"] = engine.state()["revision"].clone();
    }
    if value.get("selection_id").is_none() {
        value["selection_id"] = engine.state()["selectionId"].clone();
    }
    engine.command(serde_json::from_value(value).unwrap())
}

fn concentration() -> Value {
    json!({"kind":"concentration","seed":42,"count":37,
        "stroke":{"color":[10,30,90,255],"width":4,"cap":"butt","join":"miter","miter_limit":4},
        "opacity":1,"randomness":0.7,"taper_start":0,"taper_end":1,
        "center":{"x":128,"y":128},"inner":{"rx":30,"ry":22},"outer":{"rx":100,"ry":90},
        "angle_start":-180,"angle_sweep":360})
}

fn speed() -> Value {
    json!({"kind":"speed","seed":42,"count":3,
        "stroke":{"color":[20,50,90,255],"width":6,"cap":"butt","join":"miter","miter_limit":4},
        "opacity":1,"randomness":0,"taper_start":0,"taper_end":0,
        "origin":{"x":30.5,"y":110.5},"angle":0,"length":80,"spacing":30})
}

fn uniform(mut settings: Value) -> Value {
    settings["taper_start"] = json!(1);
    settings["taper_end"] = json!(1);
    settings
}

fn request(engine: &Engine, settings: Value, parent: Option<u32>, index: usize) -> Value {
    json!({"type":"generate_lines","id":engine.document.active,
        "revision":engine.state()["revision"],"selection_id":engine.state()["selectionId"],
        "mask_editing":engine.state()["maskEditing"],"mask_id":engine.state()["activeMaskId"],
        "name":"Generated lines","parent_id":parent,"index":index,"settings":settings})
}

fn preview(engine: &Engine, value: &Value) -> Result<Vec<u8>, String> {
    let mut envelope = value.clone();
    envelope["action"] = json!({"kind":"generate_lines","name":value["name"],
        "parent_id":value["parent_id"],"index":value["index"],"settings":value["settings"]});
    let request: LayerActionRequest = serde_json::from_value(envelope).unwrap();
    engine.preview_layer_action(request)
}

fn generate(engine: &mut Engine, settings: Value) {
    let value = request(engine, settings, None, engine.document.layers.len());
    send(engine, value).unwrap();
}

fn tiles(packet: &[u8]) -> Frame {
    assert!(packet.len() >= 16);
    let count = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
    assert_eq!(packet.len(), 16 + count * (8 + TILE_BYTES));
    packet[16..]
        .as_chunks::<{ 8 + TILE_BYTES }>()
        .0
        .iter()
        .map(|record| {
            let x = u32::from_le_bytes(record[..4].try_into().unwrap());
            let y = u32::from_le_bytes(record[4..8].try_into().unwrap());
            ((x, y), record[8..].to_vec())
        })
        .collect()
}

fn frame(engine: &Engine) -> Frame {
    let mut copy = Engine::new(1, 1).unwrap();
    copy.load(&engine.save().unwrap()).unwrap();
    tiles(&copy.frame_with_background(true))
}

fn at(frame: &Frame, x: u32, y: u32) -> [u8; 4] {
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    frame
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| tile[offset..offset + 4].try_into().unwrap())
}

fn same_frame(actual: &Frame, expected: &Frame) {
    for y in 0..256 {
        for x in 0..256 {
            assert_eq!(at(actual, x, y), at(expected, x, y), "at {x},{y}");
        }
    }
}

fn put(layer: &mut Layer, x: u32, y: u32, color: [u8; 4]) {
    let tile = layer
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .entry((x / TILE_SIZE, y / TILE_SIZE))
        .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&color);
}

fn reload(engine: &mut Engine) {
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
}

fn native() -> Engine {
    let mut engine = Engine::new(256, 256).unwrap();
    for y in 4..12 {
        for x in 4..12 {
            put(&mut engine.document.layers[0], x, y, [0, 0, 255, 255]);
        }
    }
    reload(&mut engine);
    engine
}

fn layer(engine: &Engine, id: u32) -> &Layer {
    engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap()
}

fn active(engine: &Engine) -> &Layer {
    layer(engine, engine.document.active)
}

fn objects(engine: &Engine) -> &[Arc<VectorObject>] {
    &active(engine).vector().unwrap().objects
}

fn line(object: &VectorObject) -> (Point, Point) {
    match object.geometry {
        Geometry::Line { x1, y1, x2, y2 } => (
            (f64::from(x1), f64::from(y1)),
            (f64::from(x2), f64::from(y2)),
        ),
        _ => panic!("expected real Line geometry"),
    }
}

fn difference(a: Point, b: Point) -> Point {
    (a.0 - b.0, a.1 - b.1)
}

fn cross(a: Point, b: Point) -> f64 {
    a.0 * b.1 - a.1 * b.0
}

fn dot(a: Point, b: Point) -> f64 {
    a.0 * b.0 + a.1 * b.1
}

fn length(a: Point) -> f64 {
    a.0.hypot(a.1)
}

fn close(actual: f64, expected: f64) {
    assert!(
        (actual - expected).abs() < 0.0002,
        "actual {actual}, expected {expected}"
    );
}

fn history_rejection(mut engine: Engine, change: impl FnOnce(&Engine, &mut Value)) {
    let initial = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"set_layer","id":1,"name":"Checkpoint","visible":true,"opacity":1}),
    )
    .unwrap();
    let checkpoint = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"set_layer","id":1,"name":"Future","visible":true,"opacity":1}),
    )
    .unwrap();
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    engine.frame_with_background(true);
    let state = engine.state();
    let mut value = request(&engine, speed(), None, 1);
    change(&engine, &mut value);
    assert!(preview(&engine, &value).is_err());
    assert!(send(&mut engine, value).is_err());
    assert!(
        engine.save().unwrap() == checkpoint,
        "failed generation changed storage"
    );
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == future);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == checkpoint);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == initial);
}

#[test]
fn zero_randomness_speed_has_hand_calculated_geometry_and_canonical_styles() {
    let mut engine = native();
    let mut settings = uniform(speed());
    settings["origin"] = json!({"x":20.5,"y":64.5});
    settings["spacing"] = json!(20);
    generate(&mut engine, settings);
    assert_eq!(engine.state()["maxGeneratedLines"], 256);
    let vector = active(&engine).vector().unwrap();
    assert_eq!(vector.next_object_id, 4);
    for (i, object) in vector.objects.iter().enumerate() {
        assert_eq!(object.id, i as u32 + 1);
        assert_eq!(object.name, format!("Speed {:03}", i + 1));
        assert_eq!(object.transform, [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]);
        assert_eq!(
            line(object),
            (
                (20.5, 44.5 + i as f64 * 20.0),
                (100.5, 44.5 + i as f64 * 20.0)
            )
        );
        assert_eq!(object.style.fill, None);
        assert_eq!(object.style.stroke.as_ref().unwrap().width, 6.0);
        assert_eq!(
            object.style.stroke.as_ref().unwrap().color,
            [20, 50, 90, 255]
        );
    }
}

#[test]
fn nonzero_seed_matches_independently_calculated_splitmix_geometry_golden() {
    let mut engine = native();
    let mut settings = uniform(speed());
    settings["count"] = json!(2);
    settings["randomness"] = json!(0.6f64);
    generate(&mut engine, settings);
    let expected: [[f64; 5]; 2] = [
        [
            34.53034591674805,
            98.5346450805664,
            93.1637191772461,
            98.5346450805664,
            6.594316005706787,
        ],
        [
            25.154306411743164,
            118.70914459228516,
            103.09282684326172,
            118.70914459228516,
            6.997596263885498,
        ],
    ];
    let vector = active(&engine).vector().unwrap();
    assert_eq!(vector.next_object_id, 3);
    assert_eq!(vector.objects.len(), 2);
    for (i, (object, expected)) in vector.objects.iter().zip(expected).enumerate() {
        let Geometry::Line { x1, y1, x2, y2 } = object.geometry else {
            panic!("expected Line geometry")
        };
        let stroke = object.style.stroke.as_ref().unwrap();
        assert_eq!(
            [x1, y1, x2, y2, stroke.width],
            expected.map(|value| value as f32)
        );
        assert_eq!(object.id, i as u32 + 1);
        assert_eq!(object.name, format!("Speed {:03}", i + 1));
        assert!(object.visible);
        assert_eq!(object.transform, [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]);
        assert_eq!(object.style.fill, None);
        assert_eq!(
            object.style.fill_rule,
            podor_engine::vector::FillRule::NonZero
        );
        assert_eq!(stroke.color, [20, 50, 90, 255]);
        assert_eq!(stroke.cap, podor_engine::vector::Cap::Butt);
        assert_eq!(stroke.join, podor_engine::vector::Join::Miter);
        assert_eq!(stroke.miter_limit, 4.0);
    }
}

#[test]
fn concentration_rays_are_collinear_distinct_and_bounded_by_both_ellipses() {
    for (start, sweep) in [(-180.0, 360.0), (-25.0, 110.0)] {
        let mut engine = native();
        let mut settings = uniform(concentration());
        settings["angle_start"] = json!(start);
        settings["angle_sweep"] = json!(sweep);
        generate(&mut engine, settings);
        let mut previous = None;
        for (i, object) in objects(&engine).iter().enumerate() {
            let (p, q) = line(object);
            let a = difference(p, (128.0, 128.0));
            let b = difference(q, (128.0, 128.0));
            assert!(cross(a, b).abs() < 0.002);
            assert!(dot(a, b) > 0.0 && length(b) > length(a));
            assert!(a.0.powi(2) / 30.0f64.powi(2) + a.1.powi(2) / 22.0f64.powi(2) > 1.0);
            assert!(b.0.powi(2) / 100.0f64.powi(2) + b.1.powi(2) / 90.0f64.powi(2) < 1.0);
            let angle = a.1.atan2(a.0).to_degrees();
            let low = start + (i as f64 + 0.5 - 0.7 * 0.45) * sweep / 37.0;
            let high = start + (i as f64 + 0.5 + 0.7 * 0.45) * sweep / 37.0;
            assert!(angle >= low - 0.0001 && angle <= high + 0.0001);
            if let Some(before) = previous {
                assert!(angle > before);
            }
            previous = Some(angle);
            let width = object.style.stroke.as_ref().unwrap().width;
            assert!((2.6..5.4).contains(&width));
        }
    }
}

#[test]
fn concentration_full_width_pixels_preserve_central_blank_and_outer_boundary() {
    for (tapered, cap) in [
        (true, "butt"),
        (false, "butt"),
        (false, "round"),
        (false, "square"),
    ] {
        let mut engine = Engine::new(256, 256).unwrap();
        let mut settings = concentration();
        if !tapered {
            settings = uniform(settings);
        }
        settings["stroke"]["cap"] = json!(cap);
        generate(&mut engine, settings);
        let pixels = frame(&engine);
        let mut painted = 0;
        for y in 0..256 {
            for x in 0..256 {
                let dx = f64::from(x) + 0.5 - 128.0;
                let dy = f64::from(y) + 0.5 - 128.0;
                let alpha = at(&pixels, x, y)[3];
                if dx.powi(2) / 30.0f64.powi(2) + dy.powi(2) / 22.0f64.powi(2) <= 1.0
                    || dx.powi(2) / 100.0f64.powi(2) + dy.powi(2) / 90.0f64.powi(2) > 1.0
                {
                    assert_eq!(alpha, 0, "taper {tapered}, cap {cap}, at {x},{y}");
                }
                painted += usize::from(alpha > 0);
            }
        }
        assert!(painted > 2000);
    }
}

#[test]
fn randomized_speed_lines_keep_parallel_ordered_lanes_and_realized_length_and_width() {
    let mut engine = native();
    let mut settings = uniform(speed());
    settings["origin"] = json!({"x":110,"y":75});
    settings["angle"] = json!(23);
    settings["length"] = json!(40);
    settings["spacing"] = json!(15);
    settings["count"] = json!(9);
    settings["randomness"] = json!(0.7);
    generate(&mut engine, settings);
    let u = (23.0f64.to_radians().cos(), 23.0f64.to_radians().sin());
    let n = (-u.1, u.0);
    let mut previous = None;
    for object in objects(&engine) {
        let (p, q) = line(object);
        let direction = difference(q, p);
        assert!(cross(direction, u).abs() < 0.0001);
        assert!(dot(direction, u) > 0.0);
        assert!((26.0..54.0).contains(&length(direction)));
        let lane = dot(difference(p, (110.0, 75.0)), n);
        if let Some(before) = previous {
            assert!(lane - before >= 1.5 - 0.0001);
        }
        previous = Some(lane);
        assert!((3.9..8.1).contains(&object.style.stroke.as_ref().unwrap().width));
    }
}

fn polyline(segments: &[Segment]) -> Vec<Point> {
    let mut points = Vec::new();
    let mut cursor = (0.0, 0.0);
    for segment in segments {
        match *segment {
            Segment::MoveTo { x, y } | Segment::LineTo { x, y } => {
                cursor = (f64::from(x), f64::from(y));
                points.push(cursor);
            }
            Segment::CubicTo {
                c1x,
                c1y,
                c2x,
                c2y,
                x,
                y,
            } => {
                let start = cursor;
                for step in 1..=32 {
                    let t = f64::from(step) / 32.0;
                    let s = 1.0 - t;
                    points.push((
                        s.powi(3) * start.0
                            + 3.0 * s.powi(2) * t * f64::from(c1x)
                            + 3.0 * s * t.powi(2) * f64::from(c2x)
                            + t.powi(3) * f64::from(x),
                        s.powi(3) * start.1
                            + 3.0 * s.powi(2) * t * f64::from(c1y)
                            + 3.0 * s * t.powi(2) * f64::from(c2y)
                            + t.powi(3) * f64::from(y),
                    ));
                }
                cursor = (f64::from(x), f64::from(y));
            }
            Segment::Close => points.push(points[0]),
            _ => panic!("taper must use real cubic boundaries"),
        }
    }
    points.dedup_by(|a, b| length(difference(*a, *b)) < 1e-6);
    points
}

fn proper_intersection(a: Point, b: Point, c: Point, d: Point) -> bool {
    let ab = difference(b, a);
    let cd = difference(d, c);
    cross(ab, difference(c, a)) * cross(ab, difference(d, a)) < -1e-8
        && cross(cd, difference(a, c)) * cross(cd, difference(b, c)) < -1e-8
}

#[test]
fn taper_is_closed_non_self_intersecting_cubic_geometry_with_endpoint_widths() {
    for (start, end) in [(0.0, 0.0), (0.0, 1.0), (0.25, 0.75)] {
        let mut engine = native();
        let mut settings = speed();
        settings["taper_start"] = json!(start);
        settings["taper_end"] = json!(end);
        generate(&mut engine, settings);
        for object in objects(&engine) {
            assert_eq!(object.style.fill, Some([20, 50, 90, 255]));
            assert!(object.style.stroke.is_none());
            let Geometry::Path { segments } = &object.geometry else {
                panic!("expected Path")
            };
            assert!(segments.len() <= 7 && matches!(segments.last(), Some(Segment::Close)));
            let points = polyline(segments);
            let area = points
                .windows(2)
                .map(|pair| cross(pair[0], pair[1]))
                .sum::<f64>()
                .abs()
                * 0.5;
            assert!(area > 200.0 && area < 480.0);
            for i in 0..points.len() - 1 {
                for j in i + 2..points.len() - 1 {
                    assert!(!proper_intersection(
                        points[i],
                        points[i + 1],
                        points[j],
                        points[j + 1]
                    ));
                }
            }
            let endpoints = segments
                .iter()
                .filter_map(|segment| match *segment {
                    Segment::MoveTo { x, y }
                    | Segment::LineTo { x, y }
                    | Segment::CubicTo { x, y, .. } => Some((f64::from(x), f64::from(y))),
                    _ => None,
                })
                .collect::<Vec<_>>();
            close(length(difference(endpoints[0], endpoints[5])), 6.0 * start);
            close(length(difference(endpoints[2], endpoints[3])), 6.0 * end);
            close(length(difference(endpoints[1], endpoints[4])), 6.0);
            for pair in [(1, 2), (4, 5)] {
                let Segment::CubicTo { c2x, c2y, x, y, .. } = segments[pair.0] else {
                    panic!()
                };
                let Segment::CubicTo { c1x, c1y, .. } = segments[pair.1] else {
                    panic!()
                };
                let incoming = difference(
                    (f64::from(x), f64::from(y)),
                    (f64::from(c2x), f64::from(c2y)),
                );
                let outgoing = difference(
                    (f64::from(c1x), f64::from(c1y)),
                    (f64::from(x), f64::from(y)),
                );
                close(cross(incoming, outgoing), 0.0);
                assert!(dot(incoming, outgoing) > 0.0);
                close(length(incoming), length(outgoing));
            }
        }
    }
}

#[test]
fn opacity_multiplies_alpha_once_and_never_premultiplies_persisted_rgb() {
    for tapered in [false, true] {
        let mut engine = Engine::new(256, 256).unwrap();
        let mut settings = speed();
        if !tapered {
            settings = uniform(settings);
        }
        settings["count"] = json!(1);
        settings["stroke"]["color"] = json!([120, 80, 40, 128]);
        settings["opacity"] = json!(0.5);
        generate(&mut engine, settings);
        let object = &objects(&engine)[0];
        let color = object
            .style
            .fill
            .unwrap_or_else(|| object.style.stroke.as_ref().unwrap().color);
        assert_eq!(color, [120, 80, 40, 64]);
        assert_eq!(active(&engine).opacity, 1.0);
        assert_eq!(at(&frame(&engine), 70, 110), [30, 20, 10, 64]);
    }
}

fn assert_preview_commit(mut engine: Engine, parent: Option<u32>, index: usize) {
    let before = engine.save().unwrap();
    let state = engine.state();
    let source = active(&engine).raster().unwrap().clone();
    let original = tiles(&engine.frame_with_background(true));
    let value = request(&engine, concentration(), parent, index);
    let packet = preview(&engine, &value).unwrap();
    assert!(packet.len() > 16);
    assert!(engine.save().unwrap() == before);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    let mut expected = original.clone();
    expected.extend(tiles(&packet));
    send(&mut engine, value).unwrap();
    let after = engine.save().unwrap();
    let mut actual = original;
    actual.extend(tiles(&engine.frame_with_background(true)));
    same_frame(&actual, &expected);
    same_frame(&actual, &frame(&engine));
    assert!(
        layer(&engine, state["active"].as_u64().unwrap() as u32)
            .raster()
            .unwrap()
            == &source
    );
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == before);
    assert_eq!(
        engine.document.active,
        state["active"].as_u64().unwrap() as u32
    );
    assert!(!engine.state()["canUndo"].as_bool().unwrap());
    engine.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == after);
    same_frame(&frame(&engine), &actual);
}

#[test]
fn raster_source_preview_matches_full_commit_and_single_undo_restores_ids() {
    assert_preview_commit(native(), None, 1);
}

fn grouped(isolation: GroupIsolation, dependencies: bool) -> Engine {
    let mut engine = native();
    let mut source = engine.document.layers.remove(0);
    source.parent_id = Some(2);
    engine.document.layers = vec![Layer::group(2, "Parent".into(), isolation), source];
    engine.document.next_id = 3;
    if dependencies {
        let mut clipping = Layer::new(3, "Upper clipping".into());
        clipping.clipping = true;
        for y in 0..256 {
            for x in 0..256 {
                put(&mut clipping, x, y, [0, 80, 140, 160]);
            }
        }
        let mut adjustment = Layer::new(4, "Higher tone".into());
        let spec: AdjustmentSpec = serde_json::from_value(
            json!({"kind":"tone","brightness":0.2,"contrast":0,"saturation":0}),
        )
        .unwrap();
        let settings: AdjustmentEffect = spec.into();
        adjustment.content = LayerContent::Adjustment { settings };
        engine.document.layers.extend([clipping, adjustment]);
        engine.document.next_id = 5;
        if isolation == GroupIsolation::PassThrough {
            engine.document.layers[0].parent_id = Some(5);
            engine.document.layers.insert(
                0,
                Layer::group(5, "Isolated clipping base".into(), GroupIsolation::Isolated),
            );
            engine.document.next_id = 6;
        }
    }
    reload(&mut engine);
    engine
}

#[test]
fn nested_preview_includes_isolated_passthrough_and_higher_clip_adjustment_dependencies() {
    for isolation in [GroupIsolation::Isolated, GroupIsolation::PassThrough] {
        for dependencies in [false, true] {
            assert_preview_commit(grouped(isolation, dependencies), Some(2), 1);
        }
    }
}

#[test]
fn fixed_seed_is_identical_after_repeated_changed_and_cancelled_previews() {
    let mut actual = native();
    let mut expected = native();
    actual.frame_with_background(true);
    let saved = actual.save().unwrap();
    let state = actual.state();
    let value = request(&actual, concentration(), None, 1);
    let first = preview(&actual, &value).unwrap();
    let mut different = value.clone();
    different["settings"]["seed"] = json!(43);
    let changed = preview(&actual, &different).unwrap();
    assert!(first != changed);
    for _ in 0..4 {
        assert!(preview(&actual, &value).unwrap() == first);
    }
    assert!(actual.save().unwrap() == saved);
    assert_eq!(actual.state(), state);
    assert_eq!(actual.frame_with_background(true).len(), 16);
    send(&mut actual, value).unwrap();
    generate(&mut expected, concentration());
    assert!(actual.save().unwrap() == expected.save().unwrap());
    let mut other = native();
    generate(&mut other, different["settings"].clone());
    assert!(actual.save().unwrap() != other.save().unwrap());
}

#[test]
fn invisible_and_offcanvas_lines_still_create_all_editable_objects() {
    for offcanvas in [false, true] {
        let mut engine = native();
        let before = engine.save().unwrap();
        engine.frame_with_background(true);
        let mut settings = speed();
        if offcanvas {
            settings["origin"] = json!({"x":-1000,"y":-1000});
        } else {
            settings["opacity"] = json!(0);
        }
        let value = request(&engine, settings, None, 1);
        assert_eq!(preview(&engine, &value).unwrap().len(), 16);
        send(&mut engine, value).unwrap();
        assert_eq!(objects(&engine).len(), 3);
        assert_eq!(active(&engine).vector().unwrap().next_object_id, 4);
        assert_eq!(engine.frame_with_background(true).len(), 16);
        let after = engine.save().unwrap();
        engine.command(Command::Undo).unwrap();
        assert!(engine.save().unwrap() == before);
        assert_eq!(engine.frame_with_background(true).len(), 16);
        engine.command(Command::Redo).unwrap();
        assert!(engine.save().unwrap() == after);
        assert_eq!(engine.frame_with_background(true).len(), 16);
    }
}

#[test]
fn generated_paths_reopen_as_editable_svg_without_embedded_raster_images() {
    let mut engine = native();
    generate(&mut engine, speed());
    let original = engine.save().unwrap();
    assert_eq!(&original[..6], b"PODOR\x0c");
    let pixels = frame(&engine);
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&original).unwrap();
    assert!(reopened.save().unwrap() == original);
    same_frame(&frame(&reopened), &pixels);
    let id = reopened.document.active;
    let svg = String::from_utf8(
        reopened
            .vector_svg(id, reopened.state()["revision"].as_u64().unwrap())
            .unwrap(),
    )
    .unwrap();
    let xml = roxmltree::Document::parse(&svg).unwrap();
    assert_eq!(
        xml.descendants()
            .filter(|node| node.has_tag_name("path"))
            .count(),
        3
    );
    assert!(!xml.descendants().any(|node| node.has_tag_name("image")));
    for path in xml.descendants().filter(|node| node.has_tag_name("path")) {
        let d = path.attribute("d").unwrap();
        assert_eq!(
            d.split_whitespace().filter(|token| *token == "C").count(),
            4
        );
        assert!(d.trim_end().ends_with('Z'));
    }
    assert_eq!(
        objects(&reopened)
            .iter()
            .map(|object| object.id)
            .collect::<Vec<_>>(),
        [1, 2, 3]
    );
}

#[test]
fn one_generated_object_can_be_hidden_moved_and_deleted_without_touching_others() {
    let mut engine = Engine::new(256, 256).unwrap();
    generate(&mut engine, speed());
    let id = engine.document.active;
    let initial = engine.save().unwrap();
    let mut object = serde_json::to_value(objects(&engine)[1].spec()).unwrap();
    object["visible"] = json!(false);
    send(
        &mut engine,
        json!({"type":"set_vector_object","id":id,"object_id":2,"object":object}),
    )
    .unwrap();
    let hidden = frame(&engine);
    assert_eq!(at(&hidden, 70, 110)[3], 0);
    assert_eq!(at(&hidden, 70, 80)[3], 255);
    assert_eq!(at(&hidden, 70, 140)[3], 255);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == initial);
    let mut moved = serde_json::to_value(objects(&engine)[1].spec()).unwrap();
    moved["geometry"] = json!({"kind":"line","x1":30.5,"y1":160.5,"x2":110.5,"y2":160.5});
    moved["style"] = json!({"fill":null,"stroke":{"color":[20,50,90,255],"width":4,"cap":"butt","join":"miter","miter_limit":4},"fill_rule":"non_zero"});
    send(
        &mut engine,
        json!({"type":"set_vector_object","id":id,"object_id":2,"object":moved}),
    )
    .unwrap();
    let moved_save = engine.save().unwrap();
    let pixels = frame(&engine);
    assert_eq!(at(&pixels, 70, 110)[3], 0);
    assert_eq!(at(&pixels, 70, 160)[3], 255);
    assert_eq!(objects(&engine)[1].id, 2);
    send(
        &mut engine,
        json!({"type":"delete_vector_object","id":id,"object_id":2}),
    )
    .unwrap();
    assert_eq!(objects(&engine).len(), 2);
    assert_eq!(at(&frame(&engine), 70, 160)[3], 0);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == moved_save);
    engine.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == initial);
}

#[test]
fn captured_revision_selection_active_and_mask_targets_reject_atomically() {
    for field in ["revision", "selection_id", "id", "mask_id"] {
        history_rejection(native(), |engine, value| {
            value[field] = if field == "revision" {
                json!(engine.state()["revision"].as_u64().unwrap() - 1)
            } else {
                json!(999)
            };
        });
    }
    let mut selected = native();
    selected
        .command(Command::Select {
            rect: Some(Rect {
                left: 40,
                top: 40,
                right: 100,
                bottom: 100,
            }),
        })
        .unwrap();
    history_rejection(selected, |_, _| {});
    let mut masked = native();
    send(&mut masked, json!({"type":"add_mask","mode":"reveal"})).unwrap();
    send(
        &mut masked,
        json!({"type":"set_mask_editing","enabled":true,"id":1,"mask_id":1}),
    )
    .unwrap();
    history_rejection(masked, |_, _| {});
}

#[test]
fn invalid_parameters_reject_the_complete_candidate_and_preserve_redo_and_dirty() {
    let mut invalid = Vec::new();
    for (field, value) in [
        ("count", json!(0)),
        ("count", json!(257)),
        ("opacity", json!(1.01)),
        ("randomness", json!(-0.01)),
        ("randomness", json!(1.01)),
        ("taper_start", json!(-0.01)),
        ("taper_end", json!(1.01)),
        ("angle", json!(361)),
        ("length", json!(0)),
        ("spacing", json!(0)),
    ] {
        let mut settings = speed();
        settings[field] = value;
        invalid.push(settings);
    }
    let mut settings = speed();
    settings["stroke"]["width"] = json!(0.001);
    invalid.push(settings);
    let mut settings = speed();
    settings["stroke"]["width"] = json!(8192);
    settings["randomness"] = json!(1);
    invalid.push(settings);
    let mut settings = speed();
    settings["origin"] = json!({"x":1e100,"y":0});
    invalid.push(settings);
    let mut settings = speed();
    settings["origin"] = json!({"x":10000,"y":10000});
    settings["length"] = json!(1e-12);
    invalid.push(settings);
    for (field, value) in [
        ("angle_start", json!(-361)),
        ("angle_sweep", json!(0)),
        ("angle_sweep", json!(361)),
    ] {
        let mut settings = concentration();
        settings[field] = value;
        invalid.push(settings);
    }
    let mut settings = concentration();
    settings["inner"]["rx"] = json!(0);
    invalid.push(settings);
    let mut settings = concentration();
    settings["outer"]["rx"] = json!(29);
    invalid.push(settings);
    let mut settings = concentration();
    settings["inner"] = json!({"rx":30,"ry":30});
    settings["outer"] = json!({"rx":32,"ry":32});
    invalid.push(settings);
    for settings in invalid {
        history_rejection(native(), |_, value| value["settings"] = settings);
    }
    for (field, invalid) in [("parent_id", 1), ("parent_id", 999), ("index", 999)] {
        history_rejection(native(), |_, value| value[field] = json!(invalid));
    }
}

#[test]
fn indexed_and_live_strokes_are_rejected_without_exiting_the_original_context() {
    let mut indexed = native();
    send(
        &mut indexed,
        json!({"type":"new_indexed","width":256,"height":256,
        "palette":{"colors":[[0,0,0,0],[0,0,0,255]],"transparent":0,"order":[0,1]}}),
    )
    .unwrap();
    history_rejection(indexed, |_, _| {});
    let mut engine = native();
    engine
        .command(Command::Begin {
            brush: Brush::default(),
            assistant: None,
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x: 45.0,
            y: 45.0,
            pressure: 1.0,
        }])
        .unwrap();
    let saved = engine.save().unwrap();
    let state = engine.state();
    engine.frame_with_background(true);
    let value = request(&engine, speed(), None, 1);
    assert!(preview(&engine, &value).is_err());
    assert!(send(&mut engine, value).is_err());
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    engine.command(Command::Cancel).unwrap();
    assert!(engine.save().unwrap() == native().save().unwrap());
}

#[test]
fn insertion_respects_complete_clipping_chains_and_editable_parent_ancestry() {
    let mut engine = native();
    let mut clipped = Layer::new(2, "Clipped".into());
    clipped.clipping = true;
    engine.document.layers.push(clipped);
    engine.document.next_id = 3;
    reload(&mut engine);
    let saved = engine.save().unwrap();
    engine.frame_with_background(true);
    let split = request(&engine, speed(), None, 1);
    assert!(preview(&engine, &split).is_err());
    assert!(send(&mut engine, split).is_err());
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    let valid = request(&engine, speed(), None, 2);
    send(&mut engine, valid).unwrap();
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| layer.id)
            .collect::<Vec<_>>(),
        [1, 2, 3]
    );
    for locked in [false, true] {
        let mut engine = grouped(GroupIsolation::Isolated, false);
        if locked {
            engine.document.layers[0].locked = true;
        } else {
            engine.document.layers[0].visible = false;
        }
        reload(&mut engine);
        history_rejection(engine, |_, value| {
            value["parent_id"] = json!(2);
            value["index"] = json!(1);
        });
    }
    let mut locked_source = native();
    locked_source.document.layers[0].locked = true;
    reload(&mut locked_source);
    assert_preview_commit(locked_source, None, 1);
}

#[test]
fn line_limit_is_complete_and_layer_node_or_id_exhaustion_rejects_atomically() {
    let mut engine = native();
    let mut settings = speed();
    settings["count"] = json!(256);
    settings["spacing"] = json!(0.25);
    settings["stroke"]["width"] = json!(0.5);
    generate(&mut engine, settings);
    assert_eq!(objects(&engine).len(), 256);
    assert_eq!(active(&engine).vector().unwrap().next_object_id, 257);
    for nodes in [false, true] {
        let mut engine = native();
        let limit = if nodes { MAX_LAYER_NODES } else { MAX_LAYERS };
        for id in 2..=limit as u32 {
            engine.document.layers.push(if nodes {
                Layer::group(id, format!("Group {id}"), GroupIsolation::Isolated)
            } else {
                Layer::new(id, format!("Raster {id}"))
            });
        }
        engine.document.next_id = limit as u32 + 1;
        reload(&mut engine);
        history_rejection(engine, |_, value| value["index"] = json!(limit));
    }
    let mut exhausted = native();
    exhausted.document.next_id = u32::MAX;
    reload(&mut exhausted);
    history_rejection(exhausted, |_, _| {});
}
