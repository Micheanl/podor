use super::*;
use crate::{
    animation::{self, FrameTag},
    ffi, storage, Command,
};
use serde_json::json;

struct Handle(u64);
impl Drop for Handle {
    fn drop(&mut self) {
        ffi::destroy(self.0);
    }
}
impl Handle {
    fn new() -> Self {
        Self(ffi::create(8, 8).unwrap())
    }
    fn request(&self, operation: u32, value: Value) -> Result<Vec<u8>, String> {
        ffi::dispatch(self.0, operation, &serde_json::to_vec(&value).unwrap())
    }
    fn state(&self) -> Value {
        serde_json::from_slice(&self.request(0, json!({"type":"state"})).unwrap()).unwrap()
    }
    fn save(&self) -> Vec<u8> {
        ffi::dispatch(self.0, 3, &[]).unwrap()
    }
}

fn chunk(kind: u16, bytes: &[u8]) -> Vec<u8> {
    let mut result = ((bytes.len() + 6) as u32).to_le_bytes().to_vec();
    result.extend(kind.to_le_bytes());
    result.extend(bytes);
    result
}

fn fixture() -> Vec<u8> {
    let mut layer = vec![3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 255, 0, 0, 0, 4, 0];
    layer.extend(b"Ink!");
    let mut cel = vec![0; 16];
    cel[6] = 255;
    cel.extend([2, 0, 1, 0, 255, 0, 0, 255, 0, 0, 255, 255]);
    let mut output = vec![0; 128];
    output[4..6].copy_from_slice(&0xa5e0u16.to_le_bytes());
    output[6] = 2;
    output[8] = 2;
    output[10] = 1;
    output[12] = 32;
    output[14] = 3;
    output[18] = 100;
    output[34] = 1;
    output[35] = 1;
    for (duration, chunks) in [
        (83u16, vec![chunk(0x2004, &layer), chunk(0x2005, &cel)]),
        (127u16, vec![]),
    ] {
        let size = 16 + chunks.iter().map(Vec::len).sum::<usize>();
        output.extend((size as u32).to_le_bytes());
        output.extend(0xf1fau16.to_le_bytes());
        output.extend((chunks.len() as u16).to_le_bytes());
        output.extend(duration.to_le_bytes());
        output.extend([0; 2]);
        output.extend((chunks.len() as u32).to_le_bytes());
        for c in chunks {
            output.extend(c);
        }
    }
    let size = output.len() as u32;
    output[..4].copy_from_slice(&size.to_le_bytes());
    output
}

#[test]
fn load_and_whole_project_export_cross_the_bridge_without_changing_active_frame_or_dirty() {
    let h = Handle::new();
    let imported: Value =
        serde_json::from_slice(&ffi::dispatch(h.0, 4, &fixture()).unwrap()).unwrap();
    assert_eq!(imported["animation"]["frames"][0]["durationMs"], 83);
    assert_eq!(imported["animation"]["frames"][1]["durationMs"], 127);
    assert_eq!(imported["asepriteExport"]["operation"], 27);
    assert_eq!(imported["asepriteExport"]["fullFormatSupport"], false);
    assert_eq!(imported["asepriteExport"]["editableIssues"], json!([]));
    let before = h.save();
    ffi::dispatch(h.0, 2, &[1]).unwrap();
    let bytes = h
        .request(27, json!({"revision":imported["revision"]}))
        .unwrap();
    assert_eq!(&bytes[4..6], &0xa5e0u16.to_le_bytes());
    assert_eq!(u16::from_le_bytes(bytes[6..8].try_into().unwrap()), 2);
    let restored = import(&bytes).unwrap();
    assert_eq!(
        restored.animation.as_ref().unwrap().frames[0].duration_ms,
        83
    );
    assert!(restored.animation.as_ref().unwrap().frames[1]
        .exposures
        .is_empty());
    assert_eq!(h.state(), imported);
    assert_eq!(h.save(), before);
    assert_eq!(ffi::dispatch(h.0, 2, &[1]).unwrap().len(), 16);
    assert!(h.request(5, json!({"format":"aseprite"})).is_err());
}

#[test]
fn invalid_load_and_export_envelopes_leave_source_selection_and_history_unchanged() {
    let h = Handle::new();
    h.request(
        0,
        json!({"type":"fill","x":0,"y":0,"color":[255,0,0,255],"tolerance":0}),
    )
    .unwrap();
    h.request(
        0,
        json!({"type":"select","rect":{"left":1,"top":1,"right":4,"bottom":4}}),
    )
    .unwrap();
    let state = h.state();
    let saved = h.save();
    let selection = ffi::dispatch(h.0, 13, &[]).unwrap();
    ffi::dispatch(h.0, 2, &[1]).unwrap();
    let mut invalid = fixture();
    invalid.pop();
    assert!(ffi::dispatch(h.0, 4, &invalid).is_err());
    for request in [
        json!({}),
        json!({"revision":state["revision"].as_u64().unwrap()+1}),
        json!({"revision":state["revision"],"frame_id":1}),
        json!({"revision":state["revision"],"cel_id":null}),
        json!({"revision":state["revision"],"target_layer_id":1}),
        json!({"revision":state["revision"],"format":"aseprite"}),
        json!({"revision":state["revision"],"bake_layers":"yes"}),
    ] {
        assert!(h.request(27, request).is_err());
    }
    assert_eq!(h.state(), state);
    assert_eq!(h.save(), saved);
    assert_eq!(ffi::dispatch(h.0, 13, &[]).unwrap(), selection);
    assert_eq!(ffi::dispatch(h.0, 2, &[1]).unwrap().len(), 16);
    h.request(0, json!({"type":"undo"})).unwrap();
    assert_eq!(h.state()["canRedo"], true);
}

#[test]
fn project_level_issues_include_masks_in_inactive_cels_and_bake_keeps_their_alpha() {
    let mut doc = import(&fixture()).unwrap();
    let animation = Arc::make_mut(doc.animation.as_mut().unwrap());
    let mut cel = animation.cels[&1].as_ref().clone();
    cel.id = 2;
    let mut mask = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 2,
            bottom: 1,
        },
        255,
    );
    mask.tiles
        .insert((0, 0), Arc::new(vec![128; MASK_TILE_BYTES]));
    cel.masks.push(MaskEntry {
        id: 1,
        name: "Coverage".into(),
        plane: mask,
    });
    animation.cels.insert(2, Arc::new(cel));
    animation.frames[1].exposures.insert(1, 2);
    animation.next_cel_id = 3;
    doc.next_mask_id = 2;
    doc.validate().unwrap();
    let h = Handle::new();
    ffi::dispatch(h.0, 4, &storage::save(&doc).unwrap()).unwrap();
    let state = h.state();
    assert_eq!(state["layers"][0]["masks"], json!([]));
    assert_eq!(
        state["asepriteExport"]["editableIssues"],
        json!([{"kind":"mask","count":1}])
    );
    let saved = h.save();
    assert!(h
        .request(27, json!({"revision":state["revision"]}))
        .is_err());
    let bytes = h
        .request(27, json!({"revision":state["revision"],"bake_layers":true}))
        .unwrap();
    let baked = import(&bytes).unwrap();
    let animation = baked.animation.as_ref().unwrap();
    let source = &animation.cels[&animation.frames[1].exposures[&1]].source;
    let animation::CelSource::Raster(raster) = source else {
        panic!()
    };
    assert_eq!(
        &raster.tiles()[&(0, 0)][..8],
        &[128, 0, 0, 128, 0, 0, 128, 128]
    );
    assert_eq!(h.save(), saved);
    assert_eq!(h.state(), state);
}

#[test]
fn finite_ping_pong_repeat_blocks_even_baked_export_and_cannot_mutate_source() {
    let mut doc = import(&fixture()).unwrap();
    Arc::make_mut(doc.animation.as_mut().unwrap())
        .tags
        .push(FrameTag {
            id: 1,
            name: "Bounce".into(),
            color: [1, 2, 3, 255],
            from_frame: 1,
            to_frame: 2,
            direction: TagDirection::PingPong,
            repeat: 2,
        });
    Arc::make_mut(doc.animation.as_mut().unwrap()).next_tag_id = 2;
    let h = Handle::new();
    ffi::dispatch(h.0, 4, &storage::save(&doc).unwrap()).unwrap();
    let state = h.state();
    let saved = h.save();
    assert_eq!(
        state["asepriteExport"]["blockingIssues"],
        json!([{"kind":"finite_ping_pong_repeat","count":1}])
    );
    for bake in [false, true] {
        assert!(h
            .request(27, json!({"revision":state["revision"],"bake_layers":bake}))
            .is_err());
    }
    assert_eq!(h.state(), state);
    assert_eq!(h.save(), saved);
}

#[test]
fn indexed_palette_slot_names_follow_add_remove_convert_and_undo_in_every_frame() {
    for animated in [false, true] {
        let mut doc = Document::new(2, 1).unwrap();
        doc.palette = Some(IndexedPalette {
            colors: vec![[0; 4], [255, 0, 0, 255], [0, 0, 255, 255]],
            transparent: 0,
            order: vec![0, 1, 2],
        });
        doc.layers[0].content = LayerContent::Raster(RasterPlane::Indexed(BTreeMap::new()));
        doc.aseprite_metadata = Some(Arc::new(ProjectMetadata {
            companion_palette: None,
            indexed_names: vec![
                Some("Transparent".into()),
                Some(String::new()),
                Some("Ocean".into()),
            ],
            grid: GridMetadata {
                x: 0,
                y: 0,
                width: 16,
                height: 16,
            },
            srgb: true,
        }));
        if animated {
            doc = animation::enable(&doc, 100).unwrap();
        }
        let mut engine = Engine::new(2, 1).unwrap();
        engine.load(&storage::save(&doc).unwrap()).unwrap();
        let original = engine.save().unwrap();
        engine
            .command(Command::AddPaletteColor {
                color: [0, 255, 0, 255],
                revision: engine.state()["revision"].as_u64().unwrap(),
            })
            .unwrap();
        assert_eq!(
            engine
                .document
                .aseprite_metadata
                .as_ref()
                .unwrap()
                .indexed_names,
            vec![
                Some("Transparent".into()),
                Some(String::new()),
                Some("Ocean".into()),
                None
            ]
        );
        engine
            .command(Command::RemovePaletteColor {
                index: 1,
                replacement: 2,
                revision: engine.state()["revision"].as_u64().unwrap(),
            })
            .unwrap();
        assert_eq!(
            engine
                .document
                .aseprite_metadata
                .as_ref()
                .unwrap()
                .indexed_names,
            vec![Some("Transparent".into()), Some("Ocean".into()), None]
        );
        engine
            .command(Command::ConvertColorMode {
                mode: ColorMode::Rgba,
                palette: None,
                revision: engine.state()["revision"].as_u64().unwrap(),
            })
            .unwrap();
        let metadata = engine.document.aseprite_metadata.as_ref().unwrap();
        assert!(metadata.indexed_names.is_empty());
        assert_eq!(
            metadata.companion_palette.as_ref().unwrap().names,
            vec![Some("Transparent".into()), Some("Ocean".into()), None]
        );
        for _ in 0..3 {
            engine.command(Command::Undo).unwrap();
        }
        assert_eq!(engine.save().unwrap(), original);
    }
}
