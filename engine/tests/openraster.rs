use podor_engine::{model::*, Command, Engine, ExportFormat, ExportOptions};
use std::io::{Cursor, Read};
use zip::{CompressionMethod, ZipArchive};

fn export(engine: &Engine) -> ZipArchive<Cursor<Vec<u8>>> {
    let bytes = engine
        .export_image(ExportOptions {
            format: ExportFormat::Ora,
            ..Default::default()
        })
        .unwrap();
    ZipArchive::new(Cursor::new(bytes)).unwrap()
}

fn read(archive: &mut ZipArchive<Cursor<Vec<u8>>>, path: &str) -> Vec<u8> {
    let mut bytes = Vec::new();
    archive
        .by_name(path)
        .unwrap()
        .read_to_end(&mut bytes)
        .unwrap();
    bytes
}

fn png(bytes: &[u8]) -> (u32, u32, Vec<u8>) {
    let mut reader = png::Decoder::new(bytes).read_info().unwrap();
    let mut pixels = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut pixels).unwrap();
    assert_eq!(info.color_type, png::ColorType::Rgba);
    assert_eq!(info.bit_depth, png::BitDepth::Eight);
    pixels.truncate(info.buffer_size());
    (info.width, info.height, pixels)
}

fn fill(engine: &mut Engine, color: [u8; 4]) {
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color,
            tolerance: 0,
        })
        .unwrap();
}

#[test]
fn archive_has_required_entries_and_preserves_layer_properties() {
    let mut engine = Engine::new(24, 12).unwrap();
    fill(&mut engine, [200, 100, 50, 128]);
    engine
        .command(Command::SetLayer {
            id: 1,
            visible: true,
            opacity: 0.5,
            name: "底色 & <红> \"纸\" '笔'\n\t".into(),
        })
        .unwrap();
    engine.command(Command::AddLayer).unwrap();
    fill(&mut engine, [0, 128, 255, 255]);
    engine
        .command(Command::SetLayer {
            id: 2,
            visible: false,
            opacity: 0.25,
            name: "Hidden".into(),
        })
        .unwrap();
    engine
        .command(Command::SetBlend {
            id: 2,
            mode: BlendMode::Multiply,
        })
        .unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    let mut archive = export(&engine);
    assert_eq!(archive.len(), 6);
    let first = archive.by_index(0).unwrap();
    assert_eq!(first.name(), "mimetype");
    assert_eq!(first.compression(), CompressionMethod::Stored);
    drop(first);
    assert_eq!(read(&mut archive, "mimetype"), b"image/openraster");
    let stack = String::from_utf8(read(&mut archive, "stack.xml")).unwrap();
    assert!(stack.contains("<image version=\"0.0.6\" w=\"24\" h=\"12\">"));
    assert!(stack.find("data/layer-2.png").unwrap() < stack.find("data/layer-1.png").unwrap());
    assert!(stack.contains("底色 &amp; &lt;红&gt; &quot;纸&quot; &apos;笔&apos;&#10;&#9;"));
    assert!(stack.contains("opacity=\"0.25\" visibility=\"hidden\" composite-op=\"svg:multiply\""));
    let (_, _, lower) = png(&read(&mut archive, "data/layer-1.png"));
    assert_eq!(&lower[..4], &[199, 100, 50, 128]);
    let (_, _, upper) = png(&read(&mut archive, "data/layer-2.png"));
    assert_eq!(&upper[..4], &[0, 128, 255, 255]);
    let thumbnail = png(&read(&mut archive, "Thumbnails/thumbnail.png"));
    let merged = png(&read(&mut archive, "mergedimage.png"));
    assert_eq!(thumbnail, merged);
    assert_eq!(before, engine.save().unwrap());
    assert_eq!(state, engine.state());
}

#[test]
fn merged_preview_matches_flat_export_for_every_blend_mode() {
    let modes = [
        (BlendMode::Normal, "src-over"),
        (BlendMode::Multiply, "multiply"),
        (BlendMode::Screen, "screen"),
        (BlendMode::Overlay, "overlay"),
        (BlendMode::SoftLight, "soft-light"),
        (BlendMode::Darken, "darken"),
        (BlendMode::Lighten, "lighten"),
        (BlendMode::Difference, "difference"),
    ];
    let mut engine = Engine::new(129, 131).unwrap();
    fill(&mut engine, [80, 120, 200, 192]);
    engine.command(Command::AddLayer).unwrap();
    fill(&mut engine, [180, 70, 130, 128]);
    for (mode, name) in modes {
        engine.command(Command::SetBlend { id: 2, mode }).unwrap();
        let expected = engine
            .export_image(ExportOptions {
                transparent: true,
                ..Default::default()
            })
            .unwrap();
        let mut archive = export(&engine);
        assert_eq!(png(&expected), png(&read(&mut archive, "mergedimage.png")));
        let stack = String::from_utf8(read(&mut archive, "stack.xml")).unwrap();
        assert!(stack.contains(&format!("composite-op=\"svg:{name}\"")));
    }
}

#[test]
fn cropped_layer_keeps_offsets_partial_tiles_and_transparent_gaps() {
    let mut engine = Engine::new(513, 385).unwrap();
    engine
        .command(Command::Begin {
            brush: Brush {
                size: 4.0,
                hardness: 1.0,
                ..Brush::default()
            },
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x: 130.5,
            y: 130.5,
            pressure: 1.0,
        }])
        .unwrap();
    engine.command(Command::End).unwrap();
    engine
        .command(Command::Begin {
            brush: Brush {
                size: 4.0,
                hardness: 1.0,
                ..Brush::default()
            },
        })
        .unwrap();
    engine
        .samples(&[Sample {
            x: 512.5,
            y: 384.5,
            pressure: 1.0,
        }])
        .unwrap();
    engine.command(Command::End).unwrap();
    engine.command(Command::AddLayer).unwrap();
    let mut archive = export(&engine);
    let stack = String::from_utf8(read(&mut archive, "stack.xml")).unwrap();
    assert!(stack.contains("src=\"data/layer-1.png\" x=\"128\" y=\"128\""));
    let (width, height, pixels) = png(&read(&mut archive, "data/layer-1.png"));
    assert_eq!((width, height), (385, 257));
    assert_eq!(
        &pixels[((2 * width + 2) * 4) as usize..][..4],
        &[0, 0, 0, 255]
    );
    assert_eq!(
        &pixels[((256 * width + 384) * 4) as usize..][..4],
        &[0, 0, 0, 255]
    );
    assert_eq!(
        &pixels[((150 * width + 200) * 4) as usize..][..4],
        &[0, 0, 0, 0]
    );
    assert_eq!(
        png(&read(&mut archive, "data/layer-2.png")),
        (1, 1, vec![0; 4])
    );
    let (width, height, _) = png(&read(&mut archive, "Thumbnails/thumbnail.png"));
    assert_eq!((width, height), (256, 192));
}

#[test]
fn thumbnail_preserves_alpha_without_upscaling_or_letterboxing() {
    for (width, height, expected_width, expected_height) in
        [(5, 3, 5, 3), (1, 700, 1, 256), (800, 400, 256, 128)]
    {
        let mut engine = Engine::new(width, height).unwrap();
        fill(&mut engine, [255, 0, 0, 128]);
        let mut archive = export(&engine);
        let (w, h, pixels) = png(&read(&mut archive, "Thumbnails/thumbnail.png"));
        assert_eq!((w, h), (expected_width, expected_height));
        assert!(pixels
            .as_chunks::<4>()
            .0
            .iter()
            .all(|pixel| *pixel == [255, 0, 0, 128]));
    }
}

#[test]
fn invalid_xml_names_fail_without_mutating_the_document() {
    let mut engine = Engine::new(32, 32).unwrap();
    for name in ["a\0b", "a\u{fffe}b"] {
        engine
            .command(Command::SetLayer {
                id: 1,
                visible: true,
                opacity: 1.0,
                name: name.into(),
            })
            .unwrap();
        let before = engine.save().unwrap();
        assert!(engine
            .export_image(ExportOptions {
                format: ExportFormat::Ora,
                ..Default::default()
            })
            .is_err());
        assert_eq!(before, engine.save().unwrap());
    }
}
