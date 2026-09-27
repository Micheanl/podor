use podor_engine::{model::*, Command, Engine, ExportFormat, ExportOptions};
use std::io::{Cursor, Write};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

fn png(width: u32, height: u32, pixels: &[u8], color: png::ColorType) -> Vec<u8> {
    let mut bytes = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut bytes, width, height);
        encoder.set_color(color);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(pixels)
            .unwrap();
    }
    bytes
}

fn archive(xml: &str, files: &[(&str, Vec<u8>)], compression: CompressionMethod) -> Vec<u8> {
    let mut zip = ZipWriter::new(Cursor::new(Vec::new()));
    zip.start_file(
        "mimetype",
        SimpleFileOptions::default().compression_method(CompressionMethod::Stored),
    )
    .unwrap();
    zip.write_all(b"image/openraster").unwrap();
    let options = SimpleFileOptions::default().compression_method(compression);
    zip.start_file("stack.xml", options).unwrap();
    zip.write_all(xml.as_bytes()).unwrap();
    for (path, bytes) in files {
        zip.start_file(*path, options).unwrap();
        zip.write_all(bytes).unwrap();
    }
    zip.finish().unwrap().into_inner()
}

fn stack(layers: &str) -> String {
    format!(r#"<image version="0.0.6" w="129" h="131"><stack>{layers}</stack></image>"#)
}

fn pixel(layer: &Layer, x: u32, y: u32) -> [u8; 4] {
    let Some(tile) = layer.tiles.get(&(x / TILE_SIZE, y / TILE_SIZE)) else {
        return [0; 4];
    };
    let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
    tile[offset..offset + 4].try_into().unwrap()
}

#[test]
fn exported_layers_reopen_with_the_same_composite_and_properties() {
    for mode in [
        BlendMode::Normal,
        BlendMode::Multiply,
        BlendMode::Screen,
        BlendMode::Overlay,
        BlendMode::SoftLight,
        BlendMode::Darken,
        BlendMode::Lighten,
        BlendMode::Difference,
    ] {
        let mut source = Engine::new(129, 131).unwrap();
        source
            .command(Command::Fill {
                x: 0,
                y: 0,
                color: [80, 120, 200, 192],
                tolerance: 0,
            })
            .unwrap();
        source.command(Command::AddLayer).unwrap();
        source
            .command(Command::Fill {
                x: 0,
                y: 0,
                color: [190, 40, 80, 150],
                tolerance: 0,
            })
            .unwrap();
        source.command(Command::SetBlend { id: 2, mode }).unwrap();
        source
            .command(Command::SetLayer {
                id: 2,
                name: "颜色 & <红> \"纸\"\n\t".into(),
                visible: true,
                opacity: 0.6,
            })
            .unwrap();
        source.command(Command::AddLayer).unwrap();
        source
            .command(Command::SetLayer {
                id: 3,
                name: "隐藏".into(),
                visible: false,
                opacity: 0.25,
            })
            .unwrap();
        let ora = source
            .export_image(ExportOptions {
                format: ExportFormat::Ora,
                ..Default::default()
            })
            .unwrap();
        let mut imported = Engine::new(1, 1).unwrap();
        imported.load(&ora).unwrap();
        assert_eq!(imported.state()["layers"], source.state()["layers"]);
        assert_eq!(imported.document.active, 3);
        let transparent = ExportOptions {
            transparent: true,
            ..Default::default()
        };
        assert_eq!(
            imported.export_image(transparent).unwrap(),
            source.export_image(transparent).unwrap()
        );
        let saved = imported.save().unwrap();
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&saved).unwrap();
        assert_eq!(reopened.state()["layers"], imported.state()["layers"]);
    }
}

#[test]
fn deflated_layers_keep_negative_offsets_clipping_palette_alpha_and_partial_tiles() {
    let mut palette = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut palette, 3, 1);
        encoder.set_color(png::ColorType::Indexed);
        encoder.set_depth(png::BitDepth::Eight);
        encoder.set_palette(vec![255, 0, 0, 0, 255, 0, 0, 0, 255]);
        encoder.set_trns(vec![128, 0, 255]);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&[0, 1, 2])
            .unwrap();
    }
    let files = [
        (
            "data/edge.png",
            png(3, 3, &[255, 0, 100, 128].repeat(9), png::ColorType::Rgba),
        ),
        ("data/palette.png", palette),
        (
            "data/gray.png",
            png(2, 1, &[200, 128, 70, 255], png::ColorType::GrayscaleAlpha),
        ),
    ];
    let xml = stack(
        r#"<layer src="data/edge.png" x="128" y="130"/><layer src="data/palette.png" x="126" y="1"/><layer src="data/gray.png" x="-1"/>"#,
    );
    let mut engine = Engine::new(1, 1).unwrap();
    engine
        .load(&archive(&xml, &files, CompressionMethod::Deflated))
        .unwrap();
    assert_eq!(pixel(&engine.document.layers[0], 0, 0), [70, 70, 70, 255]);
    assert_eq!(pixel(&engine.document.layers[1], 126, 1), [128, 0, 0, 128]);
    assert_eq!(pixel(&engine.document.layers[1], 127, 1), [0; 4]);
    assert_eq!(pixel(&engine.document.layers[1], 128, 1), [0, 0, 255, 255]);
    assert_eq!(
        pixel(&engine.document.layers[2], 128, 130),
        [128, 0, 50, 128]
    );
    assert_eq!(engine.document.layers[2].tiles.len(), 1);
    assert_eq!(engine.document.tile_count(), 4);
    engine.document.validate().unwrap();
}

#[test]
fn invalid_archives_never_replace_the_open_document() {
    let image = png(1, 1, &[255, 20, 40, 128], png::ColorType::Rgba);
    let files = [("data/p.png", image)];
    let mut engine = Engine::new(12, 13).unwrap();
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: [20, 50, 80, 255],
            tolerance: 0,
        })
        .unwrap();
    let saved = engine.save().unwrap();
    let state = engine.state();
    let layers = [
        "<stack><layer src=\"data/p.png\"/></stack>".to_owned(),
        "<filter/>".into(),
        "<layer/>".into(),
        "<layer src=\"../data/p.png\"/>".into(),
        "<layer src=\"https://example.invalid/p.png\"/>".into(),
        "<layer src=\"data/missing.png\"/>".into(),
        "<layer src=\"data/p.svg\"/>".into(),
        "<layer src=\"data/p.png\" opacity=\"NaN\"/>".into(),
        "<layer src=\"data/p.png\" opacity=\"1.1\"/>".into(),
        "<layer src=\"data/p.png\" visibility=\"invalid\"/>".into(),
        "<layer src=\"data/p.png\" composite-op=\"svg:color-burn\"/>".into(),
        "<layer src=\"data/p.png\" x=\"2147483648\"/>".into(),
        "<layer src=\"data/p.png\"><filter/></layer>".into(),
        format!("<layer src=\"data/p.png\" name=\"{}\"/>", "画".repeat(100)),
        "<layer src=\"data/p.png\"/>".repeat(MAX_LAYERS + 1),
    ];
    for layers in layers {
        assert!(engine
            .load(&archive(
                &stack(&layers),
                &files,
                CompressionMethod::Deflated
            ))
            .is_err());
        assert_eq!(engine.save().unwrap(), saved);
        assert_eq!(engine.state(), state);
    }
    for xml in [
        "<image w=\"1\" h=\"1\"><stack></image>".to_owned(),
        "<image w=\"0\" h=\"1\"><stack/></image>".into(),
        "<image w=\"99999\" h=\"1\"><stack/></image>".into(),
        "<image w=\"1\" h=\"1\"><stack/><stack/></image>".into(),
        "<!DOCTYPE image [<!ENTITY x SYSTEM 'file:///missing'>]><image w=\"1\" h=\"1\"><stack>&x;</stack></image>".into(),
        format!("<image w=\"1\" h=\"1\"><stack/><!--{}--></image>", " ".repeat(MAX_ORA_METADATA_BYTES)),
    ] {
        assert!(engine.load(&archive(&xml, &files, CompressionMethod::Deflated)).is_err());
        assert_eq!(engine.save().unwrap(), saved);
        assert_eq!(engine.state(), state);
    }
    let valid = archive(
        &stack("<layer src=\"data/p.png\"/>"),
        &files,
        CompressionMethod::Stored,
    );
    let mut damaged_crc = valid.clone();
    let central = damaged_crc
        .windows(4)
        .position(|bytes| bytes == b"PK\x01\x02")
        .unwrap();
    damaged_crc[central + 16] ^= 1;
    let mut oversized_directory = valid.clone();
    let end = oversized_directory.len() - 22;
    oversized_directory[end + 8..end + 12].copy_from_slice(&[255, 255, 255, 255]);
    for bytes in [
        &valid[..valid.len() - 9],
        &damaged_crc,
        &oversized_directory,
    ] {
        assert!(engine.load(bytes).is_err());
        assert_eq!(engine.save().unwrap(), saved);
        assert_eq!(engine.state(), state);
    }
}

#[test]
fn transparent_and_off_canvas_layers_stay_sparse_and_extreme_offsets_do_not_overflow() {
    let files = [(
        "data/p.png",
        png(1, 1, &[255, 255, 255, 0], png::ColorType::Rgba),
    )];
    let xml = stack(
        "<layer src=\"data/p.png\"/>\
        <layer src=\"data/p.png\" x=\"2147483647\" y=\"-2147483648\"/>\
        <layer src=\"data/p.png\" x=\"-2147483648\" y=\"2147483647\" selected=\"true\"/>",
    );
    let mut engine = Engine::new(1, 1).unwrap();
    let mut bytes = archive(&xml, &files, CompressionMethod::Stored);
    let end = bytes.len() - 22;
    let comment = b"OpenRaster fixture";
    bytes[end + 20..end + 22].copy_from_slice(&(comment.len() as u16).to_le_bytes());
    bytes.extend(comment);
    engine.load(&bytes).unwrap();
    assert_eq!(engine.document.layers.len(), 3);
    assert_eq!(engine.document.active, 1);
    assert_eq!(engine.document.tile_count(), 0);
}

#[test]
fn pixel_budget_is_enforced_during_decode_without_replacing_the_document() {
    let png = png(
        4096,
        4096,
        &vec![100; 4096 * 4096],
        png::ColorType::Grayscale,
    );
    let xml = "<image w=\"4096\" h=\"4096\"><stack><layer src=\"data/p.png\"/><layer src=\"data/p.png\"/><layer src=\"data/p.png\"/></stack></image>";
    let bytes = archive(xml, &[("data/p.png", png)], CompressionMethod::Deflated);
    let mut engine = Engine::new(12, 13).unwrap();
    let saved = engine.save().unwrap();
    assert_eq!(engine.load(&bytes).unwrap_err(), "工程像素超过内存限制");
    assert_eq!(engine.save().unwrap(), saved);
}

#[test]
fn gimp_archive_opens_with_editable_layers_and_matching_merged_preview() {
    let source = include_bytes!("fixtures/gimp-layers.ora");
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(source).unwrap();
    assert_eq!((engine.document.width, engine.document.height), (129, 131));
    let layers = &engine.document.layers;
    assert_eq!(layers.len(), 3);
    assert_eq!(layers[0].name, "底色 & <蓝> \"纸\"");
    assert_eq!(layers[1].blend, BlendMode::Multiply);
    assert_eq!(layers[1].opacity, 0.5);
    assert!(!layers[2].visible);
    assert_eq!(layers[2].opacity, 0.25);
    assert_eq!(pixel(&layers[1], 124, 0), [0; 4]);
    assert_eq!(pixel(&layers[1], 125, 0), [180, 70, 130, 255]);
    let mut zip = zip::ZipArchive::new(Cursor::new(source)).unwrap();
    let mut merged = Vec::new();
    std::io::Read::read_to_end(&mut zip.by_name("mergedimage.png").unwrap(), &mut merged).unwrap();
    let mut expected = Engine::new(1, 1).unwrap();
    expected.load(&merged).unwrap();
    let actual = engine.frame();
    let reference = expected.frame();
    assert_eq!(actual.len(), reference.len());
    assert!(actual
        .iter()
        .zip(reference)
        .all(|(&a, b)| a.abs_diff(b) <= 2));
    let saved = engine.save().unwrap();
    engine.command(Command::SelectLayer { id: 2 }).unwrap();
    engine.command(Command::Clear).unwrap();
    assert!(engine.document.layers[1].tiles.is_empty());
    engine.command(Command::Undo).unwrap();
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&saved).unwrap();
    assert_eq!(engine.state()["layers"], reopened.state()["layers"]);
}

#[test]
fn interlaced_and_sixteen_bit_png_layers_decode_correctly() {
    let interlaced = include_bytes!("fixtures/gimp-interlaced.png");
    assert_eq!(interlaced[28], 1);
    let bytes = archive(
        &stack("<layer src=\"data/p.png\"/>"),
        &[("data/p.png", interlaced.to_vec())],
        CompressionMethod::Stored,
    );
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&bytes).unwrap();
    let mut expected = Engine::new(1, 1).unwrap();
    expected.load(interlaced).unwrap();
    assert_eq!(engine.frame(), expected.frame());
    let mut wide = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut wide, 1, 1);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Sixteen);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&[255, 255, 128, 0, 16, 0, 128, 128])
            .unwrap();
    }
    engine
        .load(&archive(
            &stack("<layer src=\"data/p.png\"/>"),
            &[("data/p.png", wide)],
            CompressionMethod::Stored,
        ))
        .unwrap();
    assert_eq!(pixel(&engine.document.layers[0], 0, 0), [128, 64, 8, 128]);
}
