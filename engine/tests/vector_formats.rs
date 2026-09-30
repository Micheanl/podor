use podor_engine::{model::*, Command, Engine, ExportFormat, ExportOptions};
use serde_json::{json, Value};

fn command(engine: &mut Engine, mut value: Value) {
    value["revision"] = engine.state()["revision"].clone();
    engine
        .command(serde_json::from_value(value).unwrap())
        .unwrap();
}

fn object(name: &str, geometry: Value) -> Value {
    json!({"name":name,"visible":true,"geometry":geometry,"transform":[1,0,0,1,0,0],
        "style":{"fill":[200,40,80,128],"stroke":null,"fill_rule":"non_zero"}})
}

fn native(nested: bool) -> Engine {
    let mut engine = Engine::new(8, 8).unwrap();
    let parent = if nested {
        command(
            &mut engine,
            json!({"type":"create_group","name":"Parent","parent_id":null,"index":1,"isolation":"isolated"}),
        );
        Some(engine.document.active)
    } else {
        None
    };
    command(
        &mut engine,
        json!({"type":"create_vector","name":"Vector & <study>","parent_id":parent,"index":if nested {0}else{1}}),
    );
    let id = engine.document.active;
    command(
        &mut engine,
        json!({"type":"add_vector_object","id":id,"index":null,
        "object":object("Pigment",json!({"kind":"rect","x":1,"y":1,"width":3,"height":3}))}),
    );
    let bytes = engine.save().unwrap();
    engine.load(&bytes).unwrap();
    engine
}

fn png(engine: &Engine, transparent: bool) -> Vec<u8> {
    let bytes = engine
        .export_image(ExportOptions {
            format: ExportFormat::Png,
            transparent,
            ..Default::default()
        })
        .unwrap();
    let mut reader = png::Decoder::new(bytes.as_slice()).read_info().unwrap();
    let mut pixels = vec![0; reader.output_buffer_size()];
    let frame = reader.next_frame(&mut pixels).unwrap();
    assert_eq!(
        (frame.width, frame.height, frame.color_type),
        (8, 8, png::ColorType::Rgba)
    );
    pixels.truncate(frame.buffer_size());
    pixels
}

fn rename(engine: &mut Engine, name: &str) {
    engine
        .command(Command::SetLayer {
            id: 1,
            name: name.into(),
            visible: true,
            opacity: 1.0,
        })
        .unwrap();
}

fn assert_unchanged(mut engine: Engine, action: impl FnOnce(&Engine)) {
    let initial = engine.save().unwrap();
    rename(&mut engine, "Checkpoint");
    let current = engine.save().unwrap();
    rename(&mut engine, "Redo");
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    engine.frame();
    let state = engine.state();
    action(&engine);
    assert_eq!(engine.save().unwrap(), current);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame().len(), 16);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), future);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), current);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), initial);
}

#[test]
fn editable_psd_and_ora_reject_vectors_even_hidden_empty_nested_or_zero_opacity() {
    for nested in [false, true] {
        for (visible, opacity, empty) in [
            (true, 1.0, false),
            (false, 1.0, false),
            (true, 0.0, false),
            (true, 1.0, true),
        ] {
            let mut engine = native(nested);
            let id = engine.document.active;
            if empty {
                command(
                    &mut engine,
                    json!({"type":"delete_vector_object","id":id,"object_id":1}),
                );
            }
            engine.document.active_mut().visible = visible;
            engine.document.active_mut().opacity = opacity;
            let bytes = engine.save().unwrap();
            engine.load(&bytes).unwrap();
            assert_unchanged(engine, |engine| {
                for format in ["psd", "ora"] {
                    let options: ExportOptions =
                        serde_json::from_value(json!({"format":format})).unwrap();
                    assert!(!options.bake_layers);
                    assert!(engine
                        .export_image(options)
                        .unwrap_err()
                        .contains("矢量图层"));
                }
            });
        }
    }
}

#[test]
fn default_baked_vectors_keep_alpha_and_match_flat_png_without_changing_source() {
    for nested in [false, true] {
        assert_unchanged(native(nested), |engine| {
            let expected = png(engine, true);
            assert_eq!(expected[(2 * 8 + 2) * 4 + 3], 128);
            assert_eq!(expected[(7 * 8 + 7) * 4 + 3], 0);
            assert_eq!(png(engine, false)[(7 * 8 + 7) * 4 + 3], 255);
            for format in [ExportFormat::Psd, ExportFormat::Ora] {
                for transparent in [false, true] {
                    let bytes = engine
                        .export_image(ExportOptions {
                            format,
                            transparent,
                            bake_layers: true,
                            ..Default::default()
                        })
                        .unwrap();
                    let mut reopened = Engine::new(1, 1).unwrap();
                    reopened.load(&bytes).unwrap();
                    assert_eq!(reopened.document.layers.len(), 1);
                    let layer = &reopened.document.layers[0];
                    assert_eq!(layer.name, "Composite");
                    assert!(layer.raster_opt().is_some());
                    assert!(layer.masks.is_empty());
                    assert_eq!(layer.parent_id, None);
                    assert!(!layer.clipping);
                    assert_eq!(png(&reopened, true), expected);
                }
            }
        });
    }
}

#[test]
fn svg_contains_real_shapes_paths_styles_transforms_hidden_objects_and_escaped_names() {
    let mut engine = native(false);
    let id = engine.document.active;
    engine.document.active_mut().opacity = 0.5;
    let mut ellipse = object(
        "Ellipse",
        json!({"kind":"ellipse","cx":6,"cy":2,"rx":1,"ry":0.5}),
    );
    ellipse["visible"] = json!(false);
    command(
        &mut engine,
        json!({"type":"add_vector_object","id":id,"object":ellipse}),
    );
    let mut line = object("Line", json!({"kind":"line","x1":0,"y1":6,"x2":7,"y2":6}));
    line["style"] = json!({"fill":null,"stroke":{"color":[10,20,30,64],"width":1.5,"cap":"round","join":"bevel","miter_limit":4},"fill_rule":"even_odd"});
    line["transform"] = json!([1, 0.25, 0.5, 1, -1, 0.5]);
    command(
        &mut engine,
        json!({"type":"add_vector_object","id":id,"object":line}),
    );
    command(
        &mut engine,
        json!({"type":"add_vector_object","id":id,"object":object("Path <& \"'",json!({"kind":"path","segments":[
            {"kind":"move_to","x":1,"y":4},{"kind":"line_to","x":2,"y":4},
            {"kind":"quad_to","cx":3,"cy":5,"x":4,"y":4},
            {"kind":"cubic_to","c1x":5,"c1y":3,"c2x":6,"c2y":5,"x":6,"y":4},
            {"kind":"close"},{"kind":"move_to","x":2,"y":6},{"kind":"line_to","x":3,"y":7}
        ]}))}),
    );
    assert_unchanged(engine, |engine| {
        let bytes = engine
            .vector_svg(id, engine.state()["revision"].as_u64().unwrap())
            .unwrap();
        let text = String::from_utf8(bytes).unwrap();
        let xml = roxmltree::Document::parse(&text).unwrap();
        let root = xml.root_element();
        assert_eq!(root.tag_name().name(), "svg");
        assert_eq!(root.attribute("viewBox"), Some("0 0 8 8"));
        assert!(!text.contains("<image"));
        let group = root.children().find(|node| node.has_tag_name("g")).unwrap();
        assert_eq!(group.attribute("opacity"), Some("0.5"));
        let shapes = group
            .children()
            .filter(|node| node.is_element() && !node.has_tag_name("title"))
            .collect::<Vec<_>>();
        assert_eq!(
            shapes
                .iter()
                .map(|node| node.tag_name().name())
                .collect::<Vec<_>>(),
            ["rect", "ellipse", "line", "path"]
        );
        assert_eq!(shapes[0].attribute("fill"), Some("#c82850"));
        assert!(
            (shapes[0]
                .attribute("fill-opacity")
                .unwrap()
                .parse::<f64>()
                .unwrap()
                - 128.0 / 255.0)
                .abs()
                < 1e-12
        );
        assert_eq!(shapes[1].attribute("display"), Some("none"));
        assert_eq!(shapes[2].attribute("fill"), Some("none"));
        assert_eq!(shapes[2].attribute("stroke-linecap"), Some("round"));
        assert_eq!(shapes[2].attribute("stroke-linejoin"), Some("bevel"));
        assert_eq!(
            shapes[2].attribute("transform"),
            Some("matrix(1 0.25 0.5 1 -1 0.5)")
        );
        assert_eq!(shapes[2].attribute("fill-rule"), Some("evenodd"));
        assert_eq!(
            shapes[3]
                .attribute("d")
                .unwrap()
                .split_whitespace()
                .collect::<Vec<_>>(),
            [
                "M", "1", "4", "L", "2", "4", "Q", "3", "5", "4", "4", "C", "5", "3", "6", "5",
                "6", "4", "Z", "M", "2", "6", "L", "3", "7"
            ]
        );
        assert_eq!(
            shapes[3]
                .children()
                .find(|node| node.has_tag_name("title"))
                .unwrap()
                .text(),
            Some("Path <& \"'")
        );
    });
}

#[test]
fn svg_rejects_own_and_ancestor_mask_clip_blend_opacity_dependencies_atomically() {
    for ancestor in [false, true] {
        for property in 0..4 {
            if !ancestor && property == 3 {
                continue;
            }
            let mut engine = native(ancestor);
            let id = engine.document.active;
            let index = if ancestor {
                1
            } else {
                engine.document.layers.len() - 1
            };
            match property {
                0 => engine.document.layers[index].set_first_mask(Some(LayerMask::new(
                    MaskBounds {
                        left: 0,
                        top: 0,
                        right: 8,
                        bottom: 8,
                    },
                    255,
                ))),
                1 => engine.document.layers[index].clipping = true,
                2 => engine.document.layers[index].blend = BlendMode::Multiply,
                _ => engine.document.layers[index].opacity = 0.5,
            }
            if property == 0 {
                engine.document.layers[index]
                    .first_mask_mut()
                    .unwrap()
                    .enabled = false;
            }
            engine.document.assign_mask_ids().unwrap();
            let bytes = engine.save().unwrap();
            engine.load(&bytes).unwrap();
            assert_unchanged(engine, |engine| {
                assert!(engine
                    .vector_svg(id, engine.state()["revision"].as_u64().unwrap())
                    .unwrap_err()
                    .contains("SVG"));
            });
        }
    }
    assert_unchanged(native(false), |engine| {
        let revision = engine.state()["revision"].as_u64().unwrap();
        assert!(engine.vector_svg(1, revision).is_err());
        assert!(engine.vector_svg(999, revision).is_err());
        assert!(engine
            .vector_svg(engine.document.active, revision - 1)
            .is_err());
    });
}
