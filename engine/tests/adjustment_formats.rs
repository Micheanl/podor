use podor_engine::{
    model::*, AdjustmentEffect, AdjustmentSpec, Command, Engine, ExportFormat, ExportOptions,
};
use serde_json::json;
use std::{io::Cursor, io::Write, sync::Arc};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

fn effects() -> Vec<AdjustmentEffect> {
    [
        json!({"kind":"tone","brightness":0.2,"contrast":0.1,"saturation":-0.2}),
        json!({"kind":"curves","curves":{"rgb":{"points":[
            {"x":0,"y":0},{"x":128,"y":180},{"x":255,"y":255}
        ]}}}),
        json!({"kind":"gradient_map","gradient_map":{"stops":[
            {"position":0.0,"color":[10,20,40]},
            {"position":1.0,"color":[220,100,80]}
        ]}}),
    ]
    .into_iter()
    .map(|value| {
        serde_json::from_value::<AdjustmentSpec>(value)
            .unwrap()
            .into()
    })
    .collect()
}

fn pixel_layer(id: u32, pixels: &[[u8; 4]]) -> Layer {
    let mut layer = Layer::new(id, format!("Pigment {id}"));
    let mut tile = vec![0; TILE_BYTES];
    for (output, pixel) in tile.as_chunks_mut::<4>().0.iter_mut().zip(pixels) {
        *output = *pixel;
    }
    layer
        .raster_mut()
        .unwrap()
        .tiles_mut()
        .insert((0, 0), Arc::new(tile));
    layer
}

fn mask() -> LayerMask {
    let mut result = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 3,
            bottom: 1,
        },
        255,
    );
    let mut tile = vec![255; MASK_TILE_BYTES];
    tile[..3].copy_from_slice(&[255, 128, 0]);
    result.tiles.insert((0, 0), Arc::new(tile));
    result
}

fn native(effect: AdjustmentEffect, nested: bool, complex: bool) -> Engine {
    let mut engine = Engine::new(3, 1).unwrap();
    engine.document.layers = vec![pixel_layer(
        1,
        &[[128, 64, 32, 128], [20, 40, 80, 255], [0; 4]],
    )];
    let mut adjustment = Layer::new(if nested { 5 } else { 2 }, "Adjustment".into());
    adjustment.content = LayerContent::Adjustment { settings: effect };
    if nested {
        let mut group = Layer::group(2, "Isolated".into(), GroupIsolation::Isolated);
        group.content = LayerContent::Group {
            isolation: GroupIsolation::Isolated,
            closed: true,
        };
        let mut base = pixel_layer(3, &[[30, 60, 90, 128], [70, 40, 30, 128], [0; 4]]);
        base.parent_id = Some(2);
        let mut child = pixel_layer(4, &[[90, 30, 20, 128], [20, 40, 70, 128], [0; 4]]);
        child.parent_id = Some(2);
        adjustment.parent_id = Some(2);
        if complex {
            group.opacity = 0.75;
            group.set_first_mask(Some(mask()));
            child.clipping = true;
            adjustment.opacity = 0.6;
            adjustment.set_first_mask(Some(mask()));
        }
        engine.document.layers.extend([group, base, child]);
    }
    engine.document.active = adjustment.id;
    engine.document.next_id = adjustment.id + 1;
    engine.document.layers.push(adjustment);
    engine.document.assign_mask_ids().unwrap();
    engine.document.validate().unwrap();
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
    engine
}

fn rename(engine: &mut Engine, name: &str) {
    engine
        .command(Command::SetLayer {
            id: 1,
            visible: true,
            opacity: 1.0,
            name: name.into(),
        })
        .unwrap();
}

fn assert_atomic(mut engine: Engine, action: impl FnOnce(&mut Engine)) {
    let initial = engine.save().unwrap();
    rename(&mut engine, "Before export");
    let current = engine.save().unwrap();
    rename(&mut engine, "Redo checkpoint");
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    let state = engine.state();
    assert_eq!(engine.save().unwrap(), current);
    let mut control = Engine::new(1, 1).unwrap();
    control.load(&initial).unwrap();
    rename(&mut control, "Before export");
    rename(&mut control, "Redo checkpoint");
    control.command(Command::Undo).unwrap();
    let pending = control.frame();
    action(&mut engine);
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

fn png_pixels(bytes: &[u8]) -> (u32, u32, Vec<u8>) {
    let mut reader = png::Decoder::new(bytes).read_info().unwrap();
    let mut pixels = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut pixels).unwrap();
    assert_eq!(info.color_type, png::ColorType::Rgba);
    pixels.truncate(info.buffer_size());
    (info.width, info.height, pixels)
}

fn png_export(engine: &Engine, transparent: bool) -> Vec<u8> {
    engine
        .export_image(ExportOptions {
            format: ExportFormat::Png,
            transparent,
            ..Default::default()
        })
        .unwrap()
}

fn block(data: &[u8], output: &mut Vec<u8>) {
    output.extend((data.len() as u32).to_be_bytes());
    output.extend(data);
}

fn foreign_psd(key: Option<&[u8; 4]>, cached: bool, hidden: bool, opacity: u8) -> Vec<u8> {
    let mut output = b"8BPS\0\x01\0\0\0\0\0\0\0\x04".to_vec();
    output.extend(1u32.to_be_bytes());
    output.extend(1u32.to_be_bytes());
    output.extend([0, 8, 0, 3]);
    output.extend([0; 8]);
    let mut info = (-1i16).to_be_bytes().to_vec();
    for bound in [0i32, 0, i32::from(cached), i32::from(cached)] {
        info.extend(bound.to_be_bytes());
    }
    info.extend(if cached { 4u16 } else { 0 }.to_be_bytes());
    if cached {
        for channel in [0i16, 1, 2, -1] {
            info.extend(channel.to_be_bytes());
            info.extend(3u32.to_be_bytes());
        }
    }
    info.extend(b"8BIMnorm");
    info.extend([opacity, 0, if hidden { 2 } else { 0 }, 0]);
    let mut extra = vec![0; 8];
    extra.extend([1, b'A', 0, 0]);
    if let Some(key) = key {
        extra.extend(b"8BIM");
        extra.extend(key);
        block(&[0; 4], &mut extra);
    }
    block(&extra, &mut info);
    if cached {
        for value in [17, 34, 51, 255] {
            info.extend([0, 0, value]);
        }
    }
    if !info.len().is_multiple_of(2) {
        info.push(0);
    }
    let mut section = Vec::new();
    block(&info, &mut section);
    block(&[], &mut section);
    block(&section, &mut output);
    output.extend([0, 0, 17, 34, 51, 255]);
    output
}

fn foreign_ora(xml: &str) -> Vec<u8> {
    let mut png = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut png, 1, 1);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&[17, 34, 51, 255])
            .unwrap();
    }
    let mut archive = ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    for (path, bytes) in [
        ("mimetype", &b"image/openraster"[..]),
        ("stack.xml", xml.as_bytes()),
        ("data/cache.png", &png[..]),
    ] {
        archive.start_file(path, options).unwrap();
        archive.write_all(bytes).unwrap();
    }
    archive.finish().unwrap().into_inner()
}

#[test]
fn foreign_psd_adjustment_tags_reject_even_hidden_zero_opacity_and_cached_rgb_atomically() {
    let mut control = Engine::new(1, 1).unwrap();
    control.load(&foreign_psd(None, true, false, 255)).unwrap();
    assert_eq!(png_pixels(&png_export(&control, true)).2, [17, 34, 51, 255]);
    assert_atomic(Engine::new(3, 1).unwrap(), |engine| {
        for key in [b"brit", b"curv", b"grdm", b"CgEd"] {
            for cached in [false, true] {
                for (hidden, opacity) in [(false, 255), (true, 255), (false, 0)] {
                    let error = engine
                        .load(&foreign_psd(Some(key), cached, hidden, opacity))
                        .unwrap_err();
                    assert!(error.contains("调整图层"), "{key:?}: {error}");
                }
            }
        }
    });
}

#[test]
fn foreign_ora_filters_and_adjustment_extensions_never_import_cached_pixels_atomically() {
    let mut control = Engine::new(1, 1).unwrap();
    control.load(&foreign_ora(
        r#"<image w="1" h="1"><stack><layer name="Filter study" src="data/cache.png"/></stack></image>"#,
    )).unwrap();
    assert_eq!(png_pixels(&png_export(&control, true)).2, [17, 34, 51, 255]);
    assert_atomic(Engine::new(3, 1).unwrap(), |engine| {
        for body in [
            r#"<filter type="standard:brightness" output="data/cache.png"/>"#,
            r#"<filter type="standard:curves" output="data/cache.png" visibility="hidden"><params/></filter>"#,
            r#"<stack><filter type="standard:curves" output="data/cache.png" opacity="0"/></stack>"#,
            r#"<layer src="data/cache.png" filter="url(#curves)"/>"#,
            r#"<layer src="data/cache.png" visibility="hidden" filter="url(#curves)"/>"#,
            r#"<layer src="data/cache.png" opacity="0" svg:filter="url(#curves)"/>"#,
            r#"<layer src="data/cache.png" ext:adjustment="curves"/>"#,
        ] {
            let xml = format!(
                r#"<image w="1" h="1" xmlns:svg="http://www.w3.org/2000/svg" xmlns:ext="urn:foreign"><stack>{body}</stack></image>"#
            );
            assert!(engine
                .load(&foreign_ora(&xml))
                .unwrap_err()
                .contains("调整图层"));
        }
        for xml in [
            r#"<image w="1" h="1" filter="curves"><stack/></image>"#,
            r#"<image w="1" h="1"><stack filter="curves"/></image>"#,
        ] {
            assert!(engine
                .load(&foreign_ora(xml))
                .unwrap_err()
                .contains("调整图层"));
        }
    });
}

#[test]
fn editable_psd_and_ora_export_reject_every_adjustment_kind_and_visibility_atomically() {
    for effect in effects() {
        for nested in [false, true] {
            for (visible, opacity) in [(true, 1.0), (false, 1.0), (true, 0.0)] {
                let mut engine = native(effect.clone(), nested, false);
                let adjustment = engine.document.active_mut();
                adjustment.visible = visible;
                adjustment.opacity = opacity;
                let saved = engine.save().unwrap();
                engine.load(&saved).unwrap();
                assert_atomic(engine, |engine| {
                    for format in ["psd", "ora"] {
                        for choice in [
                            json!({"format":format}),
                            json!({"format":format,"bake_layers":false}),
                        ] {
                            let options = serde_json::from_value::<ExportOptions>(choice).unwrap();
                            assert!(!options.bake_layers);
                            assert!(engine
                                .export_image(options)
                                .unwrap_err()
                                .contains("调整图层"));
                        }
                    }
                });
            }
        }
    }
}

fn assert_baked(engine: &Engine, transparent: bool, format: ExportFormat) {
    let expected = png_pixels(&png_export(engine, true));
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
    let layer = &reopened.document.layers[0];
    assert_eq!(layer.name, "Composite");
    assert_eq!(layer.parent_id, None);
    assert!(matches!(
        layer.content,
        LayerContent::Raster(RasterPlane::Rgba(_))
    ));
    assert!(layer.masks.is_empty());
    assert!(!layer.clipping);
    assert_eq!(layer.blend, BlendMode::Normal);
    assert_eq!(layer.opacity, 1.0);
    assert_eq!(png_pixels(&png_export(&reopened, true)), expected);
}

#[test]
fn explicit_baked_adjustments_retain_alpha_independently_of_flat_png_background_options() {
    for effect in effects() {
        for (visible, opacity) in [(true, 1.0), (false, 1.0), (true, 0.0)] {
            let mut engine = native(effect.clone(), false, false);
            engine.document.active_mut().visible = visible;
            engine.document.active_mut().opacity = opacity;
            let saved = engine.save().unwrap();
            engine.load(&saved).unwrap();
            assert_atomic(engine, |engine| {
                for transparent in [false, true] {
                    let expected = png_pixels(&png_export(engine, transparent));
                    assert_eq!(expected.2[11], if transparent { 0 } else { 255 });
                    for format in [ExportFormat::Psd, ExportFormat::Ora] {
                        assert_baked(engine, transparent, format);
                    }
                }
            });
        }
    }
}

#[test]
fn explicit_baked_nested_groups_masks_and_clipping_preserve_final_composite_only() {
    for effect in effects() {
        assert_atomic(native(effect, true, true), |engine| {
            for transparent in [false, true] {
                for format in [ExportFormat::Psd, ExportFormat::Ora] {
                    assert_baked(engine, transparent, format);
                }
            }
        });
    }
}

#[test]
fn explicit_baked_indexed_adjustments_export_rgba_and_preserve_native_palette() {
    let mut engine = native(effects().remove(0), false, false);
    engine.document.palette = Some(IndexedPalette {
        colors: vec![[0; 4], [80, 40, 20, 128], [20, 40, 80, 255]],
        transparent: 0,
        order: vec![2, 0, 1],
    });
    let mut indices = vec![0; INDEX_TILE_BYTES];
    indices[..3].copy_from_slice(&[1, 2, 0]);
    engine.document.layers[0].content = LayerContent::Raster(RasterPlane::Indexed(
        std::collections::BTreeMap::from([((0, 0), Arc::new(indices))]),
    ));
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
    assert_atomic(engine, |engine| {
        for transparent in [false, true] {
            for format in [ExportFormat::Psd, ExportFormat::Ora] {
                assert_baked(engine, transparent, format);
            }
        }
    });
}
