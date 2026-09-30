use podor_engine::{model::*, Command, CopyMode, Engine};
use std::sync::Arc;

fn closed(engine: &Engine, id: u32) -> bool {
    match engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap()
        .content
    {
        LayerContent::Group { closed, .. } => closed,
        _ => panic!("expected group {id}"),
    }
}

fn nested(active: u32, indexed: bool) -> Engine {
    let mut engine = Engine::new(16, 16).unwrap();
    let mut outer = Layer::group(2, "Outer".into(), GroupIsolation::Isolated);
    let mut inner = Layer::group(3, "Inner".into(), GroupIsolation::Isolated);
    for group in [&mut outer, &mut inner] {
        let LayerContent::Group { closed, .. } = &mut group.content else {
            unreachable!()
        };
        *closed = true;
    }
    inner.parent_id = Some(2);
    let mut paint = Layer::new(4, "Paint".into());
    let mut detail = Layer::new(5, "Detail".into());
    paint.parent_id = Some(3);
    detail.parent_id = Some(3);
    engine.document.layers.extend([outer, inner, paint, detail]);
    if indexed {
        engine.document.palette = Some(IndexedPalette {
            colors: vec![[0, 0, 0, 0], [200, 40, 80, 255]],
            transparent: 0,
            order: vec![0, 1],
        });
        for layer in &mut engine.document.layers {
            if !layer.is_group() {
                layer.content = LayerContent::Raster(RasterPlane::Indexed(Default::default()));
            }
        }
    } else {
        let mut tile = vec![0; TILE_BYTES];
        tile[..4].copy_from_slice(&[200, 40, 80, 255]);
        engine.document.layers[3]
            .raster_mut()
            .unwrap()
            .tiles_mut()
            .insert((0, 0), Arc::new(tile));
    }
    engine.document.active = active;
    engine.document.next_id = 6;
    engine.load(&engine.save().unwrap()).unwrap();
    engine.frame();
    assert!(closed(&engine, 2) && closed(&engine, 3));
    engine
}

fn revision(engine: &Engine) -> u64 {
    engine.state()["revision"].as_u64().unwrap()
}

fn assert_active_ancestors_open(engine: &Engine) {
    let mut id = engine.document.active;
    while let Some(parent) = engine
        .document
        .layers
        .iter()
        .find(|layer| layer.id == id)
        .unwrap()
        .parent_id
    {
        assert!(
            !closed(engine, parent),
            "active {} is hidden by closed group {parent}",
            engine.document.active
        );
        id = parent;
    }
    engine.document.validate().unwrap();
}

fn undo_redo(engine: &mut Engine, before: Vec<u8>) {
    let after = engine.save().unwrap();
    assert_ne!(after, before);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    assert!(closed(engine, 2) && closed(engine, 3));
    assert_eq!(engine.state()["canUndo"], false);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), after);
    assert_active_ancestors_open(engine);
}

fn png() -> Vec<u8> {
    let mut bytes = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut bytes, 1, 1);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&[70, 150, 220, 255])
            .unwrap();
    }
    bytes
}

fn packet() -> Vec<u8> {
    let mut source = Engine::new(1, 1).unwrap();
    source
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: [70, 150, 220, 255],
            tolerance: 0,
            contiguous: true,
            merged: false,
        })
        .unwrap();
    source.copy_selection(CopyMode::Layer).unwrap()
}

#[test]
fn create_and_group_reveal_the_active_group_ancestors_in_the_same_undo_transaction() {
    for grouping in [false, true] {
        let mut engine = nested(4, false);
        let before = engine.save().unwrap();
        let command = if grouping {
            Command::GroupLayers {
                ids: vec![4, 5],
                name: "Grouped".into(),
                parent_id: Some(3),
                index: 0,
                revision: revision(&engine),
            }
        } else {
            Command::CreateGroup {
                name: "Created".into(),
                parent_id: Some(3),
                index: 0,
                isolation: GroupIsolation::Isolated,
                revision: revision(&engine),
            }
        };
        engine.command(command).unwrap();
        assert_eq!(engine.document.active, 6);
        assert_eq!(
            engine
                .document
                .layers
                .iter()
                .map(|layer| layer.id)
                .collect::<Vec<_>>(),
            [1, 2, 3, 6, 4, 5]
        );
        assert_eq!(engine.document.layers[3].parent_id, Some(3));
        assert_eq!(
            engine.document.layers[4].parent_id,
            Some(if grouping { 6 } else { 3 })
        );
        assert_active_ancestors_open(&engine);
        undo_redo(&mut engine, before);
    }
}

#[test]
fn add_duplicate_import_and_paste_reveal_nested_targets_with_one_undo() {
    let png = png();
    let packet = packet();
    for active in [3, 4] {
        for operation in 0..4 {
            let mut engine = nested(active, false);
            let before = engine.save().unwrap();
            match operation {
                0 => {
                    engine.command(Command::AddLayer).unwrap();
                }
                1 => {
                    engine.command(Command::DuplicateLayer { id: 4 }).unwrap();
                }
                2 => engine.import_layer(&png, "Imported").unwrap(),
                _ => engine.paste_image(&packet).unwrap(),
            }
            assert_eq!(engine.document.active, 6);
            let inserted = engine
                .document
                .layers
                .iter()
                .find(|layer| layer.id == 6)
                .unwrap();
            assert_eq!(inserted.parent_id, Some(3));
            assert!(!inserted.is_group());
            if operation >= 2 {
                assert_eq!(inserted.raster().unwrap().tiles().len(), 1);
            }
            assert_active_ancestors_open(&engine);
            undo_redo(&mut engine, before);
        }
    }
}

#[test]
fn duplicating_a_closed_group_reveals_its_parent_and_preserves_its_own_closed_state() {
    let mut engine = nested(3, false);
    let before = engine.save().unwrap();
    engine.command(Command::DuplicateLayer { id: 3 }).unwrap();
    assert_eq!(engine.document.active, 6);
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| (layer.id, layer.parent_id))
            .collect::<Vec<_>>(),
        [
            (1, None),
            (2, None),
            (3, Some(2)),
            (4, Some(3)),
            (5, Some(3)),
            (6, Some(2)),
            (7, Some(6)),
            (8, Some(6))
        ]
    );
    assert!(!closed(&engine, 2));
    assert!(closed(&engine, 3) && closed(&engine, 6));
    assert_active_ancestors_open(&engine);
    undo_redo(&mut engine, before);
    assert!(closed(&engine, 6));
}

#[test]
fn reparenting_the_active_leaf_reveals_both_new_ancestors_and_undo_restores_the_root() {
    let mut engine = nested(1, false);
    let before = engine.save().unwrap();
    engine
        .command(Command::MoveNode {
            id: 1,
            parent_id: Some(3),
            index: 1,
            revision: revision(&engine),
        })
        .unwrap();
    assert_eq!(engine.document.active, 1);
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| layer.id)
            .collect::<Vec<_>>(),
        [2, 3, 4, 1, 5]
    );
    assert_eq!(engine.document.layers[3].parent_id, Some(3));
    assert_active_ancestors_open(&engine);
    undo_redo(&mut engine, before);
}

#[test]
fn indexed_add_and_duplicate_reveal_nested_groups_without_changing_raster_mode() {
    for duplicate in [false, true] {
        let mut engine = nested(4, true);
        let before = engine.save().unwrap();
        let palette = engine.document.palette.clone();
        engine
            .command(if duplicate {
                Command::DuplicateLayer { id: 4 }
            } else {
                Command::AddLayer
            })
            .unwrap();
        assert_eq!(engine.document.active, 6);
        assert_eq!(engine.document.palette, palette);
        assert!(engine.document.active_mut().raster().unwrap().is_indexed());
        assert_active_ancestors_open(&engine);
        undo_redo(&mut engine, before);
    }
}

#[test]
fn explicit_group_closed_changes_do_not_expand_its_ancestors() {
    let mut engine = nested(4, false);
    let before = engine.save().unwrap();
    engine
        .command(Command::SetGroupClosed {
            id: 3,
            closed: false,
            revision: revision(&engine),
        })
        .unwrap();
    assert!(closed(&engine, 2));
    assert!(!closed(&engine, 3));
    assert_eq!(engine.document.active, 4);
    let opened = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), opened);
    engine
        .command(Command::SetGroupClosed {
            id: 3,
            closed: true,
            revision: revision(&engine),
        })
        .unwrap();
    assert!(closed(&engine, 2) && closed(&engine, 3));
}

fn history() -> (Engine, Vec<u8>, Vec<u8>) {
    let mut engine = nested(4, false);
    let initial = engine.save().unwrap();
    engine
        .command(Command::SetLayer {
            id: 1,
            visible: true,
            opacity: 1.0,
            name: "Renamed".into(),
        })
        .unwrap();
    engine
        .command(Command::SetGroupClosed {
            id: 3,
            closed: false,
            revision: revision(&engine),
        })
        .unwrap();
    let future = engine.save().unwrap();
    engine.command(Command::Undo).unwrap();
    assert!(closed(&engine, 2) && closed(&engine, 3));
    (engine, initial, future)
}

#[test]
fn unchanged_group_closed_and_sibling_move_keep_collapsed_state_dirty_pixels_and_history() {
    for operation in 0..3 {
        let (mut engine, initial, future) = history();
        let (mut control, _, _) = history();
        let pending = control.frame();
        let before = engine.save().unwrap();
        let state = engine.state();
        assert_eq!(state["canUndo"], true);
        assert_eq!(state["canRedo"], true);
        let command = match operation {
            0 => Command::SetGroupClosed {
                id: 3,
                closed: true,
                revision: revision(&engine),
            },
            1 => Command::MoveNode {
                id: 4,
                parent_id: Some(3),
                index: 0,
                revision: revision(&engine),
            },
            _ => Command::MoveNode {
                id: 3,
                parent_id: Some(2),
                index: 0,
                revision: revision(&engine),
            },
        };
        engine.command(command).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert_eq!(engine.frame(), pending);
        assert_eq!(engine.frame().len(), 16);
        engine.command(Command::Redo).unwrap();
        assert_eq!(engine.save().unwrap(), future);
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), before);
        engine.command(Command::Undo).unwrap();
        assert_eq!(engine.save().unwrap(), initial);
    }
}

#[test]
fn stale_nested_insertion_and_move_requests_leave_collapsed_ancestors_and_history_intact() {
    let (mut engine, initial, future) = history();
    let (mut control, _, _) = history();
    let pending = control.frame();
    let before = engine.save().unwrap();
    let state = engine.state();
    let stale = revision(&engine) + 1;
    for (index, command) in [
        Command::CreateGroup {
            name: "Stale".into(),
            parent_id: Some(3),
            index: 0,
            isolation: GroupIsolation::Isolated,
            revision: stale,
        },
        Command::GroupLayers {
            ids: vec![4, 5],
            name: "Stale".into(),
            parent_id: Some(3),
            index: 0,
            revision: stale,
        },
        Command::MoveNode {
            id: 1,
            parent_id: Some(3),
            index: 0,
            revision: stale,
        },
    ]
    .into_iter()
    .enumerate()
    {
        assert!(engine.command(command).unwrap_err().contains("图层已变化"));
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        assert!(closed(&engine, 2) && closed(&engine, 3));
        let frame = engine.frame();
        if index == 0 {
            assert_eq!(frame, pending);
        } else {
            assert_eq!(frame.len(), 16);
        }
    }
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), future);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), before);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), initial);
}

#[test]
fn duplicate_import_and_paste_budget_failures_do_not_reveal_collapsed_ancestors() {
    let mut engine = nested(4, false);
    engine.document.width = 4096;
    engine.document.height = 4096;
    let tile = Arc::new(vec![0; TILE_BYTES]);
    for layer in engine
        .document
        .layers
        .iter_mut()
        .filter(|layer| matches!(layer.id, 1 | 4))
    {
        let raster = layer.raster_mut().unwrap();
        raster.tiles_mut().clear();
        for y in 0..32 {
            for x in 0..32 {
                raster.tiles_mut().insert((x, y), tile.clone());
            }
        }
    }
    engine.document.validate().unwrap();
    assert_eq!(engine.document.pixel_bytes(), MAX_DOCUMENT_BYTES);
    let before = engine.document.clone();
    let state = engine.state();
    let png = png();
    let packet = packet();
    for operation in 0..3 {
        let result = match operation {
            0 => engine
                .command(Command::DuplicateLayer { id: 4 })
                .map(|_| ()),
            1 => engine.import_layer(&png, "Over budget"),
            _ => engine.paste_image(&packet),
        };
        assert!(result.unwrap_err().contains("内存"));
        assert!(engine.document == before);
        assert_eq!(engine.state(), state);
        assert!(closed(&engine, 2) && closed(&engine, 3));
        assert_eq!(engine.frame().len(), 16);
        assert!(Arc::ptr_eq(
            &tile,
            &engine.document.layers[3].raster().unwrap().tiles()[&(0, 0)]
        ));
    }
}
