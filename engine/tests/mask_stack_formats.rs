use podor_engine::{model::*, AdjustmentSpec, Command, Engine, ExportFormat, ExportOptions};
use serde_json::json;
use std::{collections::BTreeMap, sync::Arc};

fn mask(id: u32, default: u8, enabled: bool, linked: bool) -> MaskEntry {
    let mut plane = LayerMask::new(
        MaskBounds {
            left: -1,
            top: 0,
            right: 3,
            bottom: 1,
        },
        default,
    );
    plane.enabled = enabled;
    plane.linked = linked;
    let mut tile = vec![default; MASK_TILE_BYTES];
    tile[..4].copy_from_slice(&[17, 255, 128, 0]);
    plane.tiles.insert((0, 0), Arc::new(tile));
    MaskEntry {
        id,
        name: format!("Independent {id}"),
        plane,
    }
}

fn layer(id: u32, parent_id: Option<u32>, indexed: bool, front: bool) -> Layer {
    let mut layer = Layer::new(id, format!("Pigment {id}"));
    layer.parent_id = parent_id;
    let tile = if indexed {
        let mut indices = vec![0; INDEX_TILE_BYTES];
        indices[..3].copy_from_slice(if front { &[2, 1, 0] } else { &[1, 2, 0] });
        RasterPlane::Indexed(BTreeMap::from([((0, 0), Arc::new(indices))]))
    } else {
        let mut pixels = vec![0; TILE_BYTES];
        let colors = if front {
            [[16, 64, 32, 128], [60, 20, 40, 128], [0; 4]]
        } else {
            [[64, 32, 16, 128], [20, 40, 80, 255], [0; 4]]
        };
        for (destination, source) in pixels.as_chunks_mut::<4>().0.iter_mut().zip(colors) {
            *destination = source;
        }
        RasterPlane::Rgba(BTreeMap::from([((0, 0), Arc::new(pixels))]))
    };
    layer.content = LayerContent::Raster(tile);
    layer
}

fn native(indexed: bool) -> Engine {
    let mut engine = Engine::new(3, 1).unwrap();
    engine.document.palette = indexed.then_some(IndexedPalette {
        colors: vec![[0; 4], [128, 64, 32, 128], [20, 40, 80, 255]],
        transparent: 0,
        order: vec![2, 0, 1],
    });
    engine.document.layers = vec![
        layer(1, None, indexed, false),
        Layer::group(2, "Masked group".into(), GroupIsolation::Isolated),
        layer(3, Some(2), indexed, false),
        layer(4, Some(2), indexed, true),
    ];
    engine.document.active = 4;
    engine.document.next_id = 5;
    engine.document.next_mask_id = 100;
    engine
}

fn reload(mut engine: Engine) -> Engine {
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
    engine
}

fn png(engine: &Engine, transparent: bool) -> (u32, u32, Vec<u8>) {
    let bytes = engine
        .export_image(ExportOptions {
            format: ExportFormat::Png,
            transparent,
            ..Default::default()
        })
        .unwrap();
    let mut decoder = png::Decoder::new(bytes.as_slice()).read_info().unwrap();
    let mut pixels = vec![0; decoder.output_buffer_size()];
    let frame = decoder.next_frame(&mut pixels).unwrap();
    assert_eq!(frame.color_type, png::ColorType::Rgba);
    pixels.truncate(frame.buffer_size());
    (frame.width, frame.height, pixels)
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

fn assert_source_unchanged(mut engine: Engine, action: impl FnOnce(&Engine)) {
    let initial = engine.save().unwrap();
    rename(&mut engine, "Checkpoint");
    let current = engine.save().unwrap();
    rename(&mut engine, "Redo");
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    let state = engine.state();
    let mut control = Engine::new(1, 1).unwrap();
    control.load(&initial).unwrap();
    rename(&mut control, "Checkpoint");
    rename(&mut control, "Redo");
    control.command(Command::Undo).unwrap();
    let pending = control.frame();
    action(&engine);
    assert_eq!(engine.save().unwrap(), current);
    assert_eq!(engine.state(), state);
    assert_eq!(engine.frame(), pending);
    assert_eq!(engine.frame().len(), 16);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), future);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), current);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), initial);
}

#[test]
fn one_psd_mask_per_raster_and_group_preserves_planes_without_claiming_native_identity() {
    for default in [0, 255] {
        for (enabled, linked) in [(true, true), (false, false)] {
            let mut engine = native(false);
            engine.document.layers[1].masks = vec![mask(10, default, enabled, linked)];
            engine.document.layers[2].masks = vec![mask(20, 255, true, false)];
            let engine = reload(engine);
            let expected = png(&engine, true);
            let bytes = engine
                .export_image(ExportOptions {
                    format: ExportFormat::Psd,
                    transparent: true,
                    ..Default::default()
                })
                .unwrap();
            let mut reopened = Engine::new(1, 1).unwrap();
            reopened.load(&bytes).unwrap();
            assert_eq!(png(&reopened, true), expected);
            for id in [2, 3] {
                let before = engine
                    .document
                    .layers
                    .iter()
                    .find(|layer| layer.id == id)
                    .unwrap();
                let after = reopened
                    .document
                    .layers
                    .iter()
                    .find(|layer| layer.id == id)
                    .unwrap();
                assert_eq!(after.masks.len(), 1);
                assert_eq!(after.first_mask(), before.first_mask());
                assert_eq!(after.masks[0].name, "Mask");
                assert_ne!(after.masks[0].id, before.masks[0].id);
            }
            assert_eq!(reopened.document.next_mask_id, 3);
            assert_eq!(reopened.document.active_mask_id, None);
        }
    }
}

#[test]
fn editable_psd_rejects_multiple_masks_on_every_node_even_disabled_hidden_or_zero_opacity() {
    for target in [2, 3, 5] {
        for (visible, opacity, enabled) in
            [(true, 1.0, true), (false, 1.0, false), (true, 0.0, false)]
        {
            let mut engine = native(false);
            if target == 5 {
                let mut adjustment = Layer::new(5, "Adjustment".into());
                let settings: AdjustmentSpec = serde_json::from_value(json!({
                    "kind":"tone","brightness":0.2,"contrast":0.0,"saturation":0.0
                }))
                .unwrap();
                adjustment.content = LayerContent::Adjustment {
                    settings: settings.into(),
                };
                adjustment.parent_id = Some(2);
                engine.document.layers.push(adjustment);
                engine.document.next_id = 6;
            }
            let node = engine
                .document
                .layers
                .iter_mut()
                .find(|layer| layer.id == target)
                .unwrap();
            node.visible = visible;
            node.opacity = opacity;
            node.masks = vec![mask(10, 255, enabled, true), mask(20, 0, false, false)];
            assert_source_unchanged(reload(engine), |engine| {
                for value in [
                    json!({"format":"psd"}),
                    json!({"format":"psd","bake_layers":false}),
                ] {
                    let options: ExportOptions = serde_json::from_value(value).unwrap();
                    assert!(!options.bake_layers);
                    assert!(engine
                        .export_image(options)
                        .unwrap_err()
                        .contains("多个独立蒙版"));
                }
            });
        }
    }
}

#[test]
fn editable_ora_rejects_even_one_disabled_mask_on_hidden_or_zero_opacity_nodes() {
    for target in [2, 3] {
        for count in [1, 2] {
            for (visible, opacity) in [(true, 1.0), (false, 1.0), (true, 0.0)] {
                let mut engine = native(false);
                let node = engine
                    .document
                    .layers
                    .iter_mut()
                    .find(|layer| layer.id == target)
                    .unwrap();
                node.visible = visible;
                node.opacity = opacity;
                node.masks = (0..count)
                    .map(|index| mask(10 + index, 255, false, false))
                    .collect();
                assert_source_unchanged(reload(engine), |engine| {
                    assert!(engine
                        .export_image(ExportOptions {
                            format: ExportFormat::Ora,
                            ..Default::default()
                        })
                        .unwrap_err()
                        .contains("独立图层蒙版"));
                });
            }
        }
    }
}

#[test]
fn explicit_baked_rgba_and_indexed_stacks_match_png_without_mutating_source_or_history() {
    for indexed in [false, true] {
        let mut engine = native(indexed);
        engine.document.layers[1].opacity = 0.75;
        engine.document.layers[1].masks =
            vec![mask(10, 255, true, false), mask(11, 0, false, true)];
        engine.document.layers[2].masks =
            vec![mask(20, 255, true, true), mask(21, 255, true, false)];
        engine.document.layers[3].clipping = true;
        engine.document.layers[3].masks =
            vec![mask(30, 255, true, false), mask(31, 0, false, false)];
        engine.document.active_mask_id = Some(31);
        assert_source_unchanged(reload(engine), |engine| {
            for transparent in [false, true] {
                let expected = png(engine, true);
                assert_eq!(expected.2[11], 0);
                for format in [ExportFormat::Psd, ExportFormat::Ora] {
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
                    assert_eq!(reopened.document.palette, None);
                    assert_eq!(reopened.document.next_mask_id, 1);
                    let composite = &reopened.document.layers[0];
                    assert_eq!(composite.name, "Composite");
                    assert_eq!(composite.parent_id, None);
                    assert!(matches!(
                        composite.content,
                        LayerContent::Raster(RasterPlane::Rgba(_))
                    ));
                    assert!(composite.masks.is_empty());
                    assert!(!composite.clipping);
                    assert_eq!(composite.opacity, 1.0);
                    assert_eq!(composite.blend, BlendMode::Normal);
                    assert_eq!(png(&reopened, true), expected);
                }
            }
        });
    }
}

#[test]
fn default_baked_layer_formats_preserve_semitransparent_and_transparent_pixels() {
    assert_source_unchanged(reload(native(false)), |engine| {
        let expected = png(engine, true);
        assert!(expected.2[3] > 0 && expected.2[3] < 255);
        assert_eq!(expected.2[11], 0);
        assert_eq!(png(engine, false).2[11], 255);
        for format in [ExportFormat::Psd, ExportFormat::Ora] {
            let options = ExportOptions {
                format,
                bake_layers: true,
                ..Default::default()
            };
            assert!(!options.transparent);
            let bytes = engine.export_image(options).unwrap();
            let mut reopened = Engine::new(1, 1).unwrap();
            reopened.load(&bytes).unwrap();
            assert_eq!(reopened.document.layers.len(), 1);
            assert_eq!(reopened.document.layers[0].name, "Composite");
            assert_eq!(png(&reopened, true), expected);
        }
    });
}
