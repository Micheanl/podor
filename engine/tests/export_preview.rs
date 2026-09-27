use podor_engine::{model::PREVIEW_EDGE, Command, Engine, ExportFormat, ExportOptions};

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

fn preview(engine: &mut Engine) -> Vec<u8> {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    loop {
        let bytes = engine.previews().unwrap();
        if !bytes.is_empty() {
            return bytes;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "preview worker did not finish"
        );
        std::thread::sleep(std::time::Duration::from_millis(1));
    }
}

fn preview_pixel(bytes: &[u8], index: usize, x: usize, y: usize) -> &[u8] {
    let edge = PREVIEW_EDGE as usize;
    let start = 16 + index * (4 + edge * edge * 4) + 4 + (y * edge + x) * 4;
    &bytes[start..start + 4]
}

#[test]
fn transparent_png_keeps_straight_color_and_does_not_change_project() {
    let mut engine = Engine::new(32, 24).unwrap();
    fill(&mut engine, [200, 100, 50, 128]);
    let project = engine.save().unwrap();
    let bytes = engine
        .export_image(ExportOptions {
            transparent: true,
            ..Default::default()
        })
        .unwrap();
    let mut reader = png::Decoder::new(bytes.as_slice()).read_info().unwrap();
    assert_eq!((reader.info().width, reader.info().height), (32, 24));
    let mut pixels = vec![0; reader.output_buffer_size()];
    reader.next_frame(&mut pixels).unwrap();
    for (actual, expected) in pixels[..4].iter().zip([200u8, 100, 50, 128]) {
        assert!(actual.abs_diff(expected) <= 1);
    }
    assert_eq!(project, engine.save().unwrap());
}

#[test]
fn transparent_export_composites_layer_opacity_without_white_halos() {
    let mut engine = Engine::new(32, 32).unwrap();
    fill(&mut engine, [255, 0, 0, 128]);
    engine.command(Command::AddLayer).unwrap();
    fill(&mut engine, [0, 0, 255, 64]);
    engine
        .command(Command::SetLayer {
            id: 2,
            visible: true,
            opacity: 0.5,
            name: "blue".into(),
        })
        .unwrap();
    let bytes = engine
        .export_image(ExportOptions {
            format: ExportFormat::Webp,
            transparent: true,
            ..Default::default()
        })
        .unwrap();
    let decoded = image::load_from_memory(&bytes).unwrap().to_rgba8();
    assert_eq!(decoded.get_pixel(16, 16).0, [198, 0, 57, 144]);
}

#[test]
fn jpeg_and_webp_decode_with_correct_background_and_dimensions() {
    let mut engine = Engine::new(64, 48).unwrap();
    fill(&mut engine, [200, 100, 50, 128]);
    let jpeg = engine
        .export_image(ExportOptions {
            format: ExportFormat::Jpeg,
            quality: 94,
            ..Default::default()
        })
        .unwrap();
    let decoded = image::load_from_memory(&jpeg).unwrap().to_rgba8();
    assert_eq!(decoded.dimensions(), (64, 48));
    for (actual, expected) in decoded
        .get_pixel(24, 24)
        .0
        .iter()
        .zip([227u8, 177, 152, 255])
    {
        assert!(actual.abs_diff(expected) <= 3);
    }
    let webp = engine
        .export_image(ExportOptions {
            format: ExportFormat::Webp,
            transparent: true,
            ..Default::default()
        })
        .unwrap();
    let decoded = image::load_from_memory(&webp).unwrap().to_rgba8();
    assert_eq!(decoded.dimensions(), (64, 48));
    assert_eq!(decoded.get_pixel(24, 24).0, [199, 100, 50, 128]);
    assert!(engine
        .export_image(ExportOptions {
            format: ExportFormat::Jpeg,
            transparent: true,
            ..Default::default()
        })
        .is_err());
    assert!(engine
        .export_image(ExportOptions {
            quality: 0,
            ..Default::default()
        })
        .is_err());
}

#[test]
fn hidden_layer_has_its_own_preview_without_affecting_composite() {
    let mut engine = Engine::new(200, 100).unwrap();
    fill(&mut engine, [255, 0, 0, 255]);
    engine.command(Command::AddLayer).unwrap();
    fill(&mut engine, [0, 0, 255, 255]);
    engine
        .command(Command::SetLayer {
            id: 2,
            visible: false,
            opacity: 0.5,
            name: "hidden".into(),
        })
        .unwrap();
    let bytes = preview(&mut engine);
    assert_eq!(u32::from_le_bytes(bytes[12..16].try_into().unwrap()), 3);
    assert_eq!(preview_pixel(&bytes, 0, 48, 48), &[255, 0, 0, 255]);
    assert_eq!(preview_pixel(&bytes, 1, 48, 48), &[255, 0, 0, 255]);
    assert_eq!(preview_pixel(&bytes, 2, 48, 48), &[0, 0, 255, 255]);
    assert_eq!(preview_pixel(&bytes, 0, 48, 0), &[0, 0, 0, 0]);
    assert!(engine.previews().unwrap().is_empty());
}

#[test]
fn preview_discards_stale_work_and_refreshes_after_clear_and_undo() {
    let mut engine = Engine::new(128, 128).unwrap();
    fill(&mut engine, [255, 0, 0, 255]);
    assert!(engine.previews().unwrap().is_empty());
    engine.command(Command::Clear).unwrap();
    let cleared = preview(&mut engine);
    assert_eq!(preview_pixel(&cleared, 1, 48, 48), &[0, 0, 0, 0]);
    engine.command(Command::Undo).unwrap();
    let restored = preview(&mut engine);
    assert_eq!(preview_pixel(&restored, 1, 48, 48), &[255, 0, 0, 255]);
}
