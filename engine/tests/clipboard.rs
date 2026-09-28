use podor_engine::{model::*, Command, CopyMode, Engine};
use serde_json::json;
use std::sync::Arc;

fn command(engine: &mut Engine, value: serde_json::Value) {
    engine
        .command(serde_json::from_value(value).unwrap())
        .unwrap();
}

fn pixel(engine: &Engine, layer: usize, x: u32, y: u32) -> [u8; 4] {
    engine.document.layers[layer]
        .tiles
        .get(&(x / TILE_SIZE, y / TILE_SIZE))
        .map_or([0; 4], |tile| {
            let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
            tile[i..i + 4].try_into().unwrap()
        })
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

fn decoded(packet: &[u8]) -> (u32, u32, Vec<u8>) {
    let mut reader = png::Decoder::new(&packet[16..]).read_info().unwrap();
    let mut bytes = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut bytes).unwrap();
    bytes.truncate(info.buffer_size());
    (info.width, info.height, bytes)
}

#[test]
fn selected_copy_keeps_coverage_position_and_source_history() {
    let mut engine = Engine::new(256, 192).unwrap();
    fill(&mut engine, [160, 40, 80, 137]);
    command(
        &mut engine,
        json!({"type":"select_shape","selection":{"kind":"ellipse","left":96,"top":37,"right":173,"bottom":118}}),
    );
    let before = engine.save().unwrap();
    let state = engine.state();
    let packet = engine.copy_selection(CopyMode::Layer).unwrap();
    let (width, height, bytes) = decoded(&packet);
    assert_eq!((width, height), (77, 81));
    assert_eq!(
        packet[..16],
        [256u32, 192, 96, 37]
            .into_iter()
            .flat_map(u32::to_le_bytes)
            .collect::<Vec<_>>()
    );
    assert_eq!(&bytes[..4], &[0; 4]);
    assert!(bytes
        .as_chunks::<4>()
        .0
        .iter()
        .any(|p| p[3] > 0 && p[3] < 137));
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(engine.state(), state);
    engine.paste_image(&packet).unwrap();
    assert!(engine.state()["selection"].is_null());
    assert_eq!(engine.document.layers.len(), 2);
    assert_eq!(pixel(&engine, 1, 96, 37), [0; 4]);
    assert_eq!(pixel(&engine, 1, 130, 70), pixel(&engine, 0, 130, 70));
    assert_eq!(pixel(&engine, 1, 0, 0), [0; 4]);
    let pasted = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), pasted);
}

#[test]
fn copy_visible_matches_transparent_export_with_hidden_layers_and_blending() {
    let mut engine = Engine::new(130, 67).unwrap();
    fill(&mut engine, [100, 180, 220, 200]);
    engine.command(Command::AddLayer).unwrap();
    fill(&mut engine, [220, 70, 130, 130]);
    engine
        .command(Command::SetBlend {
            id: 2,
            mode: BlendMode::Multiply,
        })
        .unwrap();
    engine.command(Command::AddLayer).unwrap();
    fill(&mut engine, [10, 20, 30, 255]);
    engine
        .command(Command::SetLayer {
            id: 3,
            visible: false,
            opacity: 0.4,
            name: "隐藏".into(),
        })
        .unwrap();
    let packet = engine.copy_selection(CopyMode::Visible).unwrap();
    let transparent = engine
        .export_image(podor_engine::ExportOptions {
            transparent: true,
            ..Default::default()
        })
        .unwrap();
    let mut reference = vec![0; 16];
    reference.extend(transparent);
    assert_eq!(decoded(&packet), decoded(&reference));
    let (_, _, active) = decoded(&engine.copy_selection(CopyMode::Layer).unwrap());
    assert_eq!(&active[..4], &[10, 20, 30, 255]);
}

#[test]
fn antialiased_cut_preserves_unselected_pixels_and_can_be_undone_once() {
    let mut engine = Engine::new(256, 128).unwrap();
    fill(&mut engine, [240, 60, 90, 173]);
    command(
        &mut engine,
        json!({"type":"select_shape","selection":{"kind":"lasso","left":0,"top":0,"right":1,"bottom":1,"points":[{"x":100.2,"y":10.3},{"x":170.6,"y":20.7},{"x":139.2,"y":102.5}]}}),
    );
    let before = engine.save().unwrap();
    let state = engine.state();
    let packet = engine.copy_selection(CopyMode::Cut).unwrap();
    let (width, height, copied) = decoded(&packet);
    let revision = state["revision"].as_u64().unwrap();
    engine.command(Command::CutSelection { revision }).unwrap();
    assert_eq!(pixel(&engine, 0, 20, 20), [163, 41, 61, 173]);
    for y in 0..height {
        for x in 0..width {
            let alpha = copied[((y * width + x) * 4 + 3) as usize];
            assert!(u16::from(pixel(&engine, 0, 100 + x, 10 + y)[3]) + u16::from(alpha) >= 172);
            assert!(u16::from(pixel(&engine, 0, 100 + x, 10 + y)[3]) + u16::from(alpha) <= 174);
        }
    }
    let after = engine.save().unwrap();
    assert_eq!(engine.state()["selection"], state["selection"]);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
}

#[test]
fn external_paste_is_centered_at_original_size_and_clipped_without_resampling() {
    let mut source = Engine::new(9, 7).unwrap();
    fill(&mut source, [100, 180, 220, 255]);
    let tile = source.document.active_mut().tiles.get_mut(&(0, 0)).unwrap();
    for x in 0..9usize {
        let index = (3 * TILE_SIZE as usize + x) * 4;
        Arc::make_mut(tile)[index..index + 4].copy_from_slice(&[x as u8, 0, 0, 255]);
    }
    let mut packet = source.copy_selection(CopyMode::Layer).unwrap();
    packet[..16].fill(0);
    let mut target = Engine::new(5, 3).unwrap();
    target.paste_image(&packet).unwrap();
    for x in 0..5 {
        assert_eq!(pixel(&target, 1, x, 1), [(x + 2) as u8, 0, 0, 255]);
    }
    let mut larger = Engine::new(13, 11).unwrap();
    larger.paste_image(&packet).unwrap();
    assert_eq!(pixel(&larger, 1, 0, 0), [0; 4]);
    assert_eq!(pixel(&larger, 1, 2, 2), [100, 180, 220, 255]);
    assert_eq!(pixel(&larger, 1, 10, 8), [100, 180, 220, 255]);
    assert_eq!(pixel(&larger, 1, 11, 8), [0; 4]);
}

#[test]
fn failed_cut_and_paste_preserve_document_selection_and_history() {
    let mut engine = Engine::new(64, 64).unwrap();
    assert!(engine.copy_selection(CopyMode::Layer).is_err());
    fill(&mut engine, [100, 80, 60, 255]);
    let packet = engine.copy_selection(CopyMode::Layer).unwrap();
    for (locked, alpha_locked) in [(true, false), (false, true)] {
        engine.document.active_mut().locked = locked;
        engine.document.active_mut().alpha_locked = alpha_locked;
        let before = engine.save().unwrap();
        assert!(engine.copy_selection(CopyMode::Cut).is_err());
        assert!(engine
            .command(Command::CutSelection {
                revision: engine.state()["revision"].as_u64().unwrap()
            })
            .is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert!(engine.copy_selection(CopyMode::Layer).is_ok());
    }
    engine.document.active_mut().alpha_locked = false;
    let before = engine.save().unwrap();
    let state = engine.state();
    assert!(engine
        .command(Command::CutSelection { revision: 0 })
        .is_err());
    let mut bad_origin = packet.clone();
    bad_origin[8..12].copy_from_slice(&u32::MAX.to_le_bytes());
    for invalid in [
        vec![],
        vec![0; 16],
        bad_origin,
        packet[..packet.len() / 2].to_vec(),
    ] {
        assert!(engine.paste_image(&invalid).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
    while engine.document.layers.len() < MAX_LAYERS {
        engine.command(Command::AddLayer).unwrap();
    }
    let before = engine.save().unwrap();
    assert!(engine.paste_image(&packet).is_err());
    assert_eq!(engine.save().unwrap(), before);
}

#[test]
fn paste_budget_and_cut_history_budget_fail_atomically() {
    let mut engine = Engine::new(4097, 4094).unwrap();
    for y in 0..engine.document.height.div_ceil(TILE_SIZE) {
        for x in 0..engine.document.width.div_ceil(TILE_SIZE) {
            let mut pixels = vec![0; TILE_BYTES];
            for py in 0..TILE_SIZE.min(engine.document.height - y * TILE_SIZE) {
                for px in 0..TILE_SIZE.min(engine.document.width - x * TILE_SIZE) {
                    let i = ((py * TILE_SIZE + px) * 4) as usize;
                    pixels[i..i + 4].copy_from_slice(&[100, 80, 60, 255]);
                }
            }
            engine
                .document
                .active_mut()
                .tiles
                .insert((x, y), Arc::new(pixels));
        }
    }
    engine.document.validate().unwrap();
    let before = engine.document.clone();
    assert!(engine
        .command(Command::CutSelection { revision: 0 })
        .is_err());
    assert_eq!(engine.document.tile_count(), before.tile_count());
    for (key, tile) in &before.layers[0].tiles {
        assert!(Arc::ptr_eq(tile, &engine.document.layers[0].tiles[key]));
    }
    let mut source = Engine::new(256, 256).unwrap();
    fill(&mut source, [255; 4]);
    let packet = source.copy_selection(CopyMode::Layer).unwrap();
    let layer = engine.document.layers[0].clone();
    let mut extra = Layer::new(2, "预算".into());
    extra.tiles = layer
        .tiles
        .into_iter()
        .take(MAX_DOCUMENT_BYTES / TILE_BYTES - engine.document.tile_count())
        .collect();
    engine.document.layers.push(extra);
    engine.document.next_id = 3;
    engine.document.validate().unwrap();
    let state = engine.state();
    assert!(engine.paste_image(&packet).is_err());
    assert_eq!(engine.state(), state);
}
