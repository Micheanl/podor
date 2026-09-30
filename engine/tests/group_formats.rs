use podor_engine::{model::*, Command, Engine, ExportFormat, ExportOptions};
use std::{
    io::{Cursor, Read, Write},
    sync::Arc,
};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipArchive, ZipWriter};

#[derive(Clone)]
struct ExternalRecord {
    name: String,
    mode: [u8; 4],
    opacity: u8,
    clipping: u8,
    flags: u8,
    id: Option<u32>,
    section: Option<Vec<u8>>,
    section_key: [u8; 4],
    mask: Vec<u8>,
    channels: Vec<(i16, Vec<u8>)>,
}

impl ExternalRecord {
    fn marker(name: &str, kind: u32, mode: [u8; 4], id: Option<u32>) -> Self {
        let mut section = kind.to_be_bytes().to_vec();
        section.extend(b"8BIM");
        section.extend(mode);
        section.extend([0; 4]);
        Self {
            name: name.into(),
            mode: *b"norm",
            opacity: 255,
            clipping: 0,
            flags: 0,
            id,
            section: Some(section),
            section_key: *b"lsct",
            mask: Vec::new(),
            channels: Vec::new(),
        }
    }

    fn divider() -> Self {
        let mut value = Self::marker("</Layer group>", 3, *b"norm", None);
        value.section.as_mut().unwrap().truncate(4);
        value
    }

    fn pixel(name: &str, id: u32, rgba: [u8; 4]) -> Self {
        Self {
            name: name.into(),
            mode: *b"norm",
            opacity: 255,
            clipping: 0,
            flags: 0,
            id: Some(id),
            section: None,
            section_key: *b"lsct",
            mask: Vec::new(),
            channels: [2i16, -1, 0, 1]
                .into_iter()
                .map(|id| {
                    let index = if id == -1 { 3 } else { id as usize };
                    (id, [vec![0, 0], vec![rgba[index]; 3]].concat())
                })
                .collect(),
        }
    }

    fn signed_mask(&mut self) {
        for coordinate in [0i32, -1, 1, 2] {
            self.mask.extend(coordinate.to_be_bytes());
        }
        self.mask.extend([255, 1, 0, 0]);
        self.channels.push((-2, vec![0, 0, 0, 128, 255]));
    }
}

fn block(bytes: &[u8], output: &mut Vec<u8>) {
    output.extend((bytes.len() as u32).to_be_bytes());
    output.extend(bytes);
}

fn tag(key: &[u8; 4], data: &[u8], output: &mut Vec<u8>) {
    output.extend(b"8BIM");
    output.extend(key);
    block(data, output);
    if !data.len().is_multiple_of(2) {
        output.push(0);
    }
}

fn psd_fixture(records: &[ExternalRecord]) -> Vec<u8> {
    let mut output = b"8BPS\0\x01\0\0\0\0\0\0\0\x04".to_vec();
    output.extend(1u32.to_be_bytes());
    output.extend(3u32.to_be_bytes());
    output.extend([0, 8, 0, 3]);
    output.extend([0; 8]);
    let mut info = (-(records.len() as i16)).to_be_bytes().to_vec();
    for record in records {
        let bounds = if record.section.is_none() {
            [0i32, 0, 1, 3]
        } else {
            [0; 4]
        };
        for value in bounds {
            info.extend(value.to_be_bytes());
        }
        info.extend((record.channels.len() as u16).to_be_bytes());
        for (id, bytes) in &record.channels {
            info.extend(id.to_be_bytes());
            info.extend((bytes.len() as u32).to_be_bytes());
        }
        info.extend(b"8BIM");
        info.extend(record.mode);
        info.extend([record.opacity, record.clipping, record.flags, 0]);
        let mut extra = Vec::new();
        block(&record.mask, &mut extra);
        block(&[], &mut extra);
        let name = record.name.as_bytes();
        extra.push(name.len() as u8);
        extra.extend(name);
        extra.resize(extra.len() + (4 - (name.len() + 1) % 4) % 4, 0);
        if let Some(id) = record.id {
            tag(b"lyid", &id.to_be_bytes(), &mut extra);
        }
        if let Some(section) = &record.section {
            tag(&record.section_key, section, &mut extra);
        }
        block(&extra, &mut info);
    }
    for record in records {
        for (_, bytes) in &record.channels {
            info.extend(bytes);
        }
    }
    if !info.len().is_multiple_of(2) {
        info.push(0);
    }
    let mut section = Vec::new();
    block(&info, &mut section);
    block(&[], &mut section);
    block(&section, &mut output);
    output.extend([0; 2]);
    output.extend([0; 12]);
    output
}

fn nested_psd() -> Vec<ExternalRecord> {
    let mut child = ExternalRecord::pixel("Pigment", 40, [255, 0, 0, 255]);
    child.mode = *b"mul ";
    let mut outer = ExternalRecord::marker("Outer", 2, *b"norm", Some(20));
    outer.opacity = 128;
    vec![
        ExternalRecord::pixel("Backdrop", 10, [100, 150, 200, 255]),
        ExternalRecord::divider(),
        ExternalRecord::divider(),
        child,
        ExternalRecord::marker("Inner", 1, *b"norm", Some(30)),
        ExternalRecord::divider(),
        ExternalRecord::marker("Empty", 1, *b"norm", Some(50)),
        outer,
    ]
}

fn export(engine: &Engine, format: ExportFormat) -> Vec<u8> {
    engine
        .export_image(ExportOptions {
            format,
            transparent: true,
            ..Default::default()
        })
        .unwrap()
}

fn first_pixel(engine: &mut Engine) -> [u8; 4] {
    engine.frame_with_background(true)[24..28]
        .try_into()
        .unwrap()
}

fn raster_pixel(layer: &Layer) -> [u8; 4] {
    layer
        .raster()
        .unwrap()
        .tiles()
        .get(&(0, 0))
        .map_or([0; 4], |tile| tile[..4].try_into().unwrap())
}

#[test]
fn external_psd_nested_groups_preserve_order_identity_empty_folders_and_isolation() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&psd_fixture(&nested_psd())).unwrap();
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| layer.name.as_str())
            .collect::<Vec<_>>(),
        ["Backdrop", "Outer", "Inner", "Pigment", "Empty"]
    );
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| (layer.id, layer.parent_id))
            .collect::<Vec<_>>(),
        [
            (10, None),
            (20, None),
            (30, Some(20)),
            (40, Some(30)),
            (50, Some(20))
        ]
    );
    assert!(matches!(
        engine.document.layers[1].content,
        LayerContent::Group {
            isolation: GroupIsolation::Isolated,
            closed: true
        }
    ));
    assert!(engine.document.layers[4].is_group());
    assert_eq!(engine.document.next_id, 51);
    assert_eq!(raster_pixel(&engine.document.layers[3]), [255, 0, 0, 255]);
    assert_eq!(first_pixel(&mut engine), [178, 75, 100, 255]);
}

#[test]
fn external_psd_section_blend_key_distinguishes_pass_through_from_isolated_groups() {
    for (mode, expected) in [(*b"norm", [255, 0, 0, 255]), (*b"pass", [100, 0, 0, 255])] {
        let mut child = ExternalRecord::pixel("Pigment", 3, [255, 0, 0, 255]);
        child.mode = *b"mul ";
        let mut folder = ExternalRecord::marker("Group", 1, mode, Some(2));
        folder.section.as_mut().unwrap().truncate(12);
        let records = [
            ExternalRecord::pixel("Backdrop", 1, [100, 150, 200, 255]),
            ExternalRecord::divider(),
            child,
            folder,
        ];
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&psd_fixture(&records)).unwrap();
        assert_eq!(first_pixel(&mut engine), expected);
        assert_eq!(engine.document.layers[1].blend, BlendMode::Normal);
    }
}

#[test]
fn external_psd_folder_mask_uses_its_own_signed_geometry_and_preserves_child_clipping() {
    let mut clipped = ExternalRecord::pixel("Clip", 3, [0, 0, 255, 255]);
    clipped.clipping = 1;
    let mut group = ExternalRecord::marker("Mask group", 2, *b"norm", Some(4));
    group.signed_mask();
    let records = [
        ExternalRecord::divider(),
        ExternalRecord::pixel("Base", 2, [255, 0, 0, 128]),
        clipped,
        group,
    ];
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&psd_fixture(&records)).unwrap();
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| (layer.id, layer.parent_id))
            .collect::<Vec<_>>(),
        [(4, None), (2, Some(4)), (3, Some(4))]
    );
    let mask = engine.document.layers[0].first_mask().unwrap();
    assert_eq!(
        mask.bounds,
        MaskBounds {
            left: -1,
            top: 0,
            right: 2,
            bottom: 1
        }
    );
    assert!(!mask.linked);
    assert_eq!(mask.tiles[&(0, 0)][..3], [0, 128, 255]);
    assert!(engine.document.layers[2].clipping);
    assert_eq!(first_pixel(&mut engine), [0, 0, 64, 64]);
    assert_eq!(raster_pixel(&engine.document.layers[1]), [128, 0, 0, 128]);
    assert_eq!(raster_pixel(&engine.document.layers[2]), [0, 0, 255, 255]);
    let encoded = export(&engine, ExportFormat::Psd);
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&encoded).unwrap();
    assert_eq!(first_pixel(&mut reopened), [0, 0, 64, 64]);
    assert_eq!(engine.save().unwrap(), reopened.save().unwrap());
}

struct Bytes<'a> {
    bytes: &'a [u8],
    offset: usize,
}
impl<'a> Bytes<'a> {
    fn take(&mut self, count: usize) -> &'a [u8] {
        let start = self.offset;
        self.offset += count;
        &self.bytes[start..self.offset]
    }
    fn u16(&mut self) -> u16 {
        u16::from_be_bytes(self.take(2).try_into().unwrap())
    }
    fn u32(&mut self) -> u32 {
        u32::from_be_bytes(self.take(4).try_into().unwrap())
    }
    fn block(&mut self) -> Bytes<'a> {
        let count = self.u32() as usize;
        Bytes {
            bytes: self.take(count),
            offset: 0,
        }
    }
}

#[derive(Debug)]
struct ParsedRecord {
    name: String,
    section: u32,
    channels: Vec<i16>,
    data: Vec<Vec<u8>>,
}

fn parse_psd_records(bytes: &[u8]) -> Vec<ParsedRecord> {
    let mut file = Bytes { bytes, offset: 26 };
    file.block();
    file.block();
    let mut section = file.block();
    let mut info = section.block();
    let count = (info.u16() as i16).unsigned_abs() as usize;
    let mut result = Vec::new();
    let mut lengths = Vec::new();
    for _ in 0..count {
        info.take(16);
        let channel_count = info.u16();
        let channels = (0..channel_count)
            .map(|_| {
                let id = info.u16() as i16;
                lengths.push(info.u32() as usize);
                id
            })
            .collect();
        assert_eq!(info.take(4), b"8BIM");
        info.take(8);
        let mut extra = info.block();
        extra.block();
        extra.block();
        let length = extra.take(1)[0] as usize;
        let name = String::from_utf8(extra.take(length).to_vec()).unwrap();
        extra.take((4 - (length + 1) % 4) % 4);
        let mut kind = 0;
        while extra.offset + 12 <= extra.bytes.len() {
            assert_eq!(extra.take(4), b"8BIM");
            let key = extra.take(4);
            let mut data = extra.block();
            if key == b"lsct" {
                kind = data.u32();
            }
            extra.take(data.bytes.len() % 2);
        }
        result.push(ParsedRecord {
            name,
            section: kind,
            channels,
            data: Vec::new(),
        });
    }
    let mut lengths = lengths.into_iter();
    for record in &mut result {
        for _ in &record.channels {
            let mut channel = Bytes {
                bytes: info.take(lengths.next().unwrap()),
                offset: 0,
            };
            assert_eq!(channel.u16(), 1);
            let row_length = channel.u16() as usize;
            let mut row = Bytes {
                bytes: channel.take(row_length),
                offset: 0,
            };
            let mut raw = Vec::new();
            while row.offset < row.bytes.len() {
                let control = row.take(1)[0] as i8;
                match control {
                    -128 => {}
                    0..=127 => raw.extend(row.take(control as usize + 1)),
                    _ => {
                        let value = row.take(1)[0];
                        raw.extend(std::iter::repeat_n(
                            value,
                            (1i16 - i16::from(control)) as usize,
                        ));
                    }
                }
            }
            record.data.push(raw);
            assert_eq!(channel.offset, channel.bytes.len());
        }
    }
    result
}

#[test]
fn psd_writer_emits_real_folder_boundaries_and_channel_data_in_matching_record_order() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&psd_fixture(&nested_psd())).unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    let encoded = export(&engine, ExportFormat::Psd);
    let parsed = parse_psd_records(&encoded);
    assert_eq!(
        parsed
            .iter()
            .map(|record| (record.name.as_str(), record.section))
            .collect::<Vec<_>>(),
        [
            ("Backdrop", 0),
            ("</Layer group>", 3),
            ("</Layer group>", 3),
            ("Pigment", 0),
            ("Inner", 1),
            ("</Layer group>", 3),
            ("Empty", 1),
            ("Outer", 2)
        ]
    );
    assert_eq!(parsed[0].channels, [0, 1, 2, -1]);
    assert_eq!(
        parsed[0].data,
        [vec![100; 3], vec![150; 3], vec![200; 3], vec![255; 3]]
    );
    assert_eq!(
        parsed[3].data,
        [vec![255; 3], vec![0; 3], vec![0; 3], vec![255; 3]]
    );
    assert!(parsed
        .iter()
        .filter(|record| record.section != 0)
        .all(|record| record.channels.is_empty()));
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&encoded).unwrap();
    assert_eq!(before, reopened.save().unwrap());
    assert_eq!(before, engine.save().unwrap());
    assert_eq!(state, engine.state());
}

#[test]
fn psd_invalid_group_records_are_rejected_atomically() {
    let mut candidates = vec![
        vec![ExternalRecord::marker("Unmatched", 1, *b"norm", Some(1))],
        vec![
            ExternalRecord::divider(),
            ExternalRecord::pixel("Unclosed", 1, [1, 2, 3, 255]),
        ],
    ];
    for change in 0..7 {
        let mut records = nested_psd();
        let head = records.last_mut().unwrap();
        match change {
            0 => head.section.as_mut().unwrap()[4..8].copy_from_slice(b"FAIL"),
            1 => *head.section.as_mut().unwrap().last_mut().unwrap() = 1,
            2 => head.section.as_mut().unwrap().truncate(8),
            3 => head.id = Some(10),
            4 => head.id = Some(0),
            5 => head.id = Some(u32::MAX),
            _ => {
                head.section.as_mut().unwrap()[8..12].copy_from_slice(b"pass");
            }
        }
        candidates.push(records);
    }
    let mut pass_leaf = ExternalRecord::pixel("Leaf pass", 1, [1, 2, 3, 255]);
    pass_leaf.mode = *b"pass";
    candidates.push(vec![pass_leaf]);
    let mut nested = vec![ExternalRecord::divider(); MAX_GROUP_DEPTH + 1];
    nested.extend(
        (0..=MAX_GROUP_DEPTH)
            .map(|i| ExternalRecord::marker("Deep", 1, *b"norm", Some(i as u32 + 1))),
    );
    candidates.push(nested);
    candidates.push(
        (0..=MAX_LAYERS)
            .map(|i| ExternalRecord::pixel("Too many", i as u32 + 1, [1, 2, 3, 255]))
            .collect(),
    );
    let mut clipped = ExternalRecord::pixel("Cross boundary", 2, [1, 2, 3, 255]);
    clipped.clipping = 1;
    candidates.push(vec![
        ExternalRecord::pixel("Outside", 1, [1, 2, 3, 255]),
        ExternalRecord::divider(),
        clipped,
        ExternalRecord::marker("Group", 1, *b"norm", Some(3)),
    ]);
    let mut engine = Engine::new(2, 2).unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    for records in candidates {
        assert!(
            engine.load(&psd_fixture(&records)).is_err(),
            "accepted {:?}",
            records
                .iter()
                .map(|record| &record.name)
                .collect::<Vec<_>>()
        );
        assert_eq!(before, engine.save().unwrap());
        assert_eq!(state, engine.state());
    }
}

#[test]
fn psd_lsdk_divider_alias_and_folder_without_file_ids_keep_a_valid_unique_hierarchy() {
    let mut records = nested_psd();
    for record in &mut records {
        record.id = None;
        if record
            .section
            .as_ref()
            .is_some_and(|data| data.starts_with(&3u32.to_be_bytes()))
        {
            record.section_key = *b"lsdk";
        }
    }
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&psd_fixture(&records)).unwrap();
    engine.document.validate().unwrap();
    assert_eq!(first_pixel(&mut engine), [178, 75, 100, 255]);
    assert_eq!(engine.document.layers.len(), 5);
    assert_eq!(engine.document.next_id, 6);
}

#[test]
fn psd_structural_empty_rgb_channels_do_not_become_raster_layers() {
    for compression in [Vec::new(), vec![0, 0], vec![0, 1]] {
        let mut records = nested_psd();
        for record in records.iter_mut().filter(|record| record.section.is_some()) {
            record.channels = [0i16, 1, 2, -1]
                .into_iter()
                .map(|id| (id, compression.clone()))
                .collect();
        }
        let mut engine = Engine::new(1, 1).unwrap();
        engine.load(&psd_fixture(&records)).unwrap();
        assert_eq!(engine.document.layers.len(), 5);
        assert_eq!(first_pixel(&mut engine), [178, 75, 100, 255]);
    }
}

fn selection_resource(ids: &[u32]) -> Vec<u8> {
    let mut payload = (ids.len() as u16).to_be_bytes().to_vec();
    for id in ids {
        payload.extend(id.to_be_bytes());
    }
    let mut resource = b"8BIM\x04\x2D\0\0".to_vec();
    block(&payload, &mut resource);
    resource
}

fn psd_selection_fixture(resources: &[u8]) -> Vec<u8> {
    let mut fixture = psd_fixture(&nested_psd());
    let mut replacement = Vec::new();
    block(resources, &mut replacement);
    fixture.splice(30..34, replacement);
    fixture
}

#[test]
fn psd_selection_resource_preserves_selected_group_and_chooses_one_valid_external_id() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&psd_fixture(&nested_psd())).unwrap();
    engine.document.active = 20;
    let encoded = export(&engine, ExportFormat::Psd);
    let mut reader = Bytes {
        bytes: &encoded,
        offset: 26,
    };
    reader.block();
    assert_eq!(reader.block().bytes, selection_resource(&[20]));
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&encoded).unwrap();
    assert_eq!(engine.save().unwrap(), reopened.save().unwrap());
    engine
        .load(&psd_selection_fixture(&selection_resource(&[999, 20, 40])))
        .unwrap();
    assert_eq!(engine.document.active, 20);
    engine
        .load(&psd_selection_fixture(&selection_resource(&[999])))
        .unwrap();
    assert_eq!(engine.document.active, 40);
}

#[test]
fn malformed_psd_selection_resources_reject_atomically() {
    let valid = selection_resource(&[20]);
    let mut bad_count = valid.clone();
    bad_count[13] = 2;
    let resources = [
        selection_resource(&[20, 20]),
        selection_resource(&[0]),
        selection_resource(&[u32::MAX]),
        bad_count,
        [valid.clone(), valid].concat(),
    ];
    let mut engine = Engine::new(2, 2).unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    for resource in resources {
        assert!(engine.load(&psd_selection_fixture(&resource)).is_err());
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
    }
}

fn png_pixel(rgba: [u8; 4]) -> Vec<u8> {
    let mut output = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut output, 3, 1);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&[rgba; 3].concat())
            .unwrap();
    }
    output
}

fn ora_fixture(children: &str) -> Vec<u8> {
    let mut archive = ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    archive.start_file("mimetype", options).unwrap();
    archive.write_all(b"image/openraster").unwrap();
    archive.start_file("stack.xml", options).unwrap();
    archive
        .write_all(
            format!(r#"<image version="0.0.6" w="3" h="1"><stack>{children}</stack></image>"#)
                .as_bytes(),
        )
        .unwrap();
    for (name, rgba) in [
        ("data/backdrop.png", [100, 150, 200, 255]),
        ("data/pigment.png", [255, 0, 0, 255]),
    ] {
        archive.start_file(name, options).unwrap();
        archive.write_all(&png_pixel(rgba)).unwrap();
    }
    archive.finish().unwrap().into_inner()
}

fn empty_ora_fixture(attributes: &str) -> Vec<u8> {
    let mut archive = ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    archive.start_file("mimetype", options).unwrap();
    archive.write_all(b"image/openraster").unwrap();
    archive.start_file("stack.xml", options).unwrap();
    archive
        .write_all(format!(r#"<image w="3" h="1"><stack {attributes}/></image>"#).as_bytes())
        .unwrap();
    archive.finish().unwrap().into_inner()
}

#[test]
fn external_empty_ora_root_keeps_a_blank_editable_document() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&empty_ora_fixture("")).unwrap();
    let expected = Engine::new(3, 1).unwrap();
    assert_eq!(engine.save().unwrap(), expected.save().unwrap());
    assert_eq!(engine.document.active, 1);
    assert_eq!(engine.document.next_id, 2);
    assert_eq!(engine.document.layers.len(), 1);
    assert!(engine.document.layers[0]
        .raster()
        .unwrap()
        .tiles()
        .is_empty());
    engine.document.validate().unwrap();
}

#[test]
fn external_empty_ora_root_rejects_properties_and_extensions_without_mutating_history_or_pixels() {
    let with_history = || {
        let mut engine = Engine::new(2, 2).unwrap();
        engine
            .command(Command::Fill {
                x: 0,
                y: 0,
                color: [255, 0, 0, 255],
                tolerance: 0,
                contiguous: true,
                merged: false,
            })
            .unwrap();
        let painted = engine.save().unwrap();
        engine.command(Command::Clear).unwrap();
        let blank = engine.save().unwrap();
        engine.command(Command::Undo).unwrap();
        (engine, painted, blank)
    };
    let (mut engine, painted, blank) = with_history();
    let (mut control, _, _) = with_history();
    let pending_frame = control.frame();
    let before = engine.save().unwrap();
    let state = engine.state();
    assert_eq!(state["canUndo"], true);
    assert_eq!(state["canRedo"], true);
    for (index, (attributes, message)) in [
        (r#"x="1""#, "位置"),
        (r#"y="-1""#, "位置"),
        (r#"opacity="0.5""#, "根图层组"),
        (r#"visibility="hidden""#, "根图层组"),
        (r#"composite-op="svg:multiply""#, "根图层组"),
        (r#"isolation="auto""#, "根图层组"),
        (r#"mask="data/mask.png""#, "蒙版"),
        (
            r#"xmlns:m="urn:example:ora" m:mask="data/mask.png""#,
            "蒙版",
        ),
        (r#"alpha-inherit="true""#, "剪贴"),
    ]
    .into_iter()
    .enumerate()
    {
        let error = engine.load(&empty_ora_fixture(attributes)).unwrap_err();
        assert!(error.contains(message), "{attributes}: {error}");
        assert_eq!(engine.save().unwrap(), before);
        assert_eq!(engine.state(), state);
        let frame = engine.frame();
        if index == 0 {
            assert_eq!(frame, pending_frame);
        } else {
            assert_eq!(frame.len(), 16);
        }
    }
    engine.command(Command::Redo).unwrap();
    assert_eq!(engine.save().unwrap(), blank);
    engine.command(Command::Undo).unwrap();
    assert_eq!(engine.save().unwrap(), painted);
}

fn nested_ora() -> &'static str {
    r#"<stack name="Outer" opacity="0.5" selected="true"><stack name="Empty"/><stack name="Inner"><layer name="Pigment" src="data/pigment.png" composite-op="svg:multiply"/></stack></stack><layer name="Backdrop" src="data/backdrop.png"/>"#
}

#[test]
fn external_ora_nested_stacks_default_to_isolation_and_keep_empty_groups() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&ora_fixture(nested_ora())).unwrap();
    assert_eq!(
        engine
            .document
            .layers
            .iter()
            .map(|layer| (layer.name.as_str(), layer.parent_id))
            .collect::<Vec<_>>(),
        [
            ("Backdrop", None),
            ("Outer", None),
            ("Inner", Some(2)),
            ("Pigment", Some(3)),
            ("Empty", Some(2))
        ]
    );
    assert_eq!(engine.document.active, 2);
    assert!(matches!(
        engine.document.layers[1].content,
        LayerContent::Group {
            isolation: GroupIsolation::Isolated,
            ..
        }
    ));
    assert_eq!(raster_pixel(&engine.document.layers[3]), [255, 0, 0, 255]);
    assert_eq!(first_pixel(&mut engine), [178, 75, 100, 255]);
}

#[test]
fn external_ora_auto_group_connects_children_to_the_backdrop_without_flattening() {
    let children = r#"<stack name="Pass" isolation="auto"><layer name="Pigment" src="data/pigment.png" composite-op="svg:multiply"/></stack><layer name="Backdrop" src="data/backdrop.png"/>"#;
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&ora_fixture(children)).unwrap();
    assert!(matches!(
        engine.document.layers[1].content,
        LayerContent::Group {
            isolation: GroupIsolation::PassThrough,
            ..
        }
    ));
    assert_eq!(engine.document.layers[2].parent_id, Some(2));
    assert_eq!(first_pixel(&mut engine), [100, 0, 0, 255]);
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&export(&engine, ExportFormat::Ora)).unwrap();
    assert_eq!(engine.save().unwrap(), reopened.save().unwrap());
}

fn zip_file(bytes: &[u8], path: &str) -> Vec<u8> {
    let mut archive = ZipArchive::new(Cursor::new(bytes)).unwrap();
    let mut output = Vec::new();
    archive
        .by_name(path)
        .unwrap()
        .read_to_end(&mut output)
        .unwrap();
    output
}

#[test]
fn ora_writer_emits_nested_stacks_without_extra_raster_layers_and_preserves_selection() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&ora_fixture(nested_ora())).unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    let encoded = export(&engine, ExportFormat::Ora);
    let xml = String::from_utf8(zip_file(&encoded, "stack.xml")).unwrap();
    let parsed = roxmltree::Document::parse(&xml).unwrap();
    let root = parsed.root_element().first_element_child().unwrap();
    let roots = root
        .children()
        .filter(|node| node.is_element())
        .collect::<Vec<_>>();
    assert_eq!(
        roots
            .iter()
            .map(|node| (node.tag_name().name(), node.attribute("name").unwrap()))
            .collect::<Vec<_>>(),
        [("stack", "Outer"), ("layer", "Backdrop")]
    );
    let outer = roots[0];
    assert_eq!(outer.attribute("isolation"), Some("isolate"));
    assert_eq!(outer.attribute("selected"), Some("true"));
    assert_eq!(
        outer
            .children()
            .filter(|node| node.is_element())
            .map(|node| node.attribute("name").unwrap())
            .collect::<Vec<_>>(),
        ["Empty", "Inner"]
    );
    let archive = ZipArchive::new(Cursor::new(&encoded)).unwrap();
    assert_eq!(archive.len(), 6);
    assert!(archive
        .file_names()
        .filter(|name| name.starts_with("data/"))
        .all(|name| matches!(name, "data/layer-1.png" | "data/layer-4.png")));
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&encoded).unwrap();
    assert_eq!(before, reopened.save().unwrap());
    assert_eq!(state, engine.state());
    assert_eq!(before, engine.save().unwrap());
}

#[test]
fn ora_group_properties_depth_and_baseline_extensions_reject_atomically() {
    let mut candidates = vec![
        r#"<stack isolation="auto" opacity="0.5"><layer src="data/pigment.png"/></stack>"#.to_owned(),
        r#"<stack isolation="auto" composite-op="svg:multiply"><layer src="data/pigment.png"/></stack>"#.into(),
        r#"<stack isolation="unknown"/>"#.into(), r#"<stack x="0"/>"#.into(),
        r#"<stack><filter/></stack>"#.into(), r#"<stack mask="data/mask.png"/>"#.into(),
        r#"<stack alpha-inherit="true"/>"#.into(), r#"<stack><layer src="data/missing.png"/></stack>"#.into(),
    ];
    candidates.push(format!(
        "{}{}",
        "<stack>".repeat(MAX_GROUP_DEPTH + 1),
        "</stack>".repeat(MAX_GROUP_DEPTH + 1)
    ));
    candidates.push("<stack/>".repeat(MAX_LAYER_NODES + 1));
    candidates.push(r#"<layer src="data/pigment.png"/>"#.repeat(MAX_LAYERS + 1));
    let mut engine = Engine::new(2, 2).unwrap();
    let before = engine.save().unwrap();
    let state = engine.state();
    for children in candidates {
        assert!(
            engine.load(&ora_fixture(&children)).is_err(),
            "accepted {children}"
        );
        assert_eq!(before, engine.save().unwrap());
        assert_eq!(state, engine.state());
    }
}

#[test]
fn empty_group_node_limit_is_independent_from_raster_limit_and_psd_markers() {
    let mut engine = Engine::new(3, 1).unwrap();
    engine.document.layers = (1..=MAX_LAYER_NODES as u32)
        .map(|id| Layer::group(id, format!("Group {id}"), GroupIsolation::Isolated))
        .collect();
    engine.document.active = MAX_LAYER_NODES as u32;
    engine.document.next_id = MAX_LAYER_NODES as u32 + 1;
    engine.document.validate().unwrap();
    for format in [ExportFormat::Psd, ExportFormat::Ora] {
        let encoded = export(&engine, format);
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&encoded).unwrap();
        assert_eq!(reopened.document.layers.len(), MAX_LAYER_NODES);
        assert!(reopened.document.layers.iter().all(Layer::is_group));
        reopened.document.validate().unwrap();
    }
}

#[test]
fn group_depth_boundary_roundtrips_without_replacing_nested_empty_groups() {
    let mut engine = Engine::new(3, 1).unwrap();
    engine.document.layers = (1..=MAX_GROUP_DEPTH as u32)
        .map(|id| {
            let mut group = Layer::group(id, format!("Group {id}"), GroupIsolation::Isolated);
            group.parent_id = (id > 1).then_some(id - 1);
            group
        })
        .collect();
    engine.document.active = MAX_GROUP_DEPTH as u32;
    engine.document.next_id = MAX_GROUP_DEPTH as u32 + 1;
    engine.document.validate().unwrap();
    for format in [ExportFormat::Psd, ExportFormat::Ora] {
        let encoded = export(&engine, format);
        if matches!(format, ExportFormat::Psd) {
            assert_eq!(
                encoded
                    .windows(8)
                    .filter(|bytes| *bytes == b"8BIMlsdk")
                    .count(),
                MAX_GROUP_DEPTH - 5
            );
        }
        let mut reopened = Engine::new(1, 1).unwrap();
        reopened.load(&encoded).unwrap();
        assert_eq!(engine.save().unwrap(), reopened.save().unwrap());
    }
}

#[test]
fn ora_never_bakes_group_masks_or_clipping_into_children() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&ora_fixture(nested_ora())).unwrap();
    engine.document.layers[1].set_first_mask(Some(LayerMask::new(
        MaskBounds {
            left: -1,
            top: 0,
            right: 2,
            bottom: 1,
        },
        255,
    )));
    engine.document.assign_mask_ids().unwrap();
    for enabled in [false, true] {
        engine.document.layers[1].first_mask_mut().unwrap().enabled = enabled;
        assert!(engine
            .export_image(ExportOptions {
                format: ExportFormat::Ora,
                ..Default::default()
            })
            .unwrap_err()
            .contains("独立图层蒙版"));
    }
    engine.document.layers[1].set_first_mask(None);
    engine.document.layers[1].clipping = true;
    assert!(engine
        .export_image(ExportOptions {
            format: ExportFormat::Ora,
            ..Default::default()
        })
        .unwrap_err()
        .contains("剪贴蒙版"));
}

#[test]
fn psd_group_mask_channel_remains_separate_from_raster_channels_in_writer_plan() {
    let mut engine = Engine::new(1, 1).unwrap();
    engine.load(&psd_fixture(&nested_psd())).unwrap();
    let mut mask = LayerMask::new(
        MaskBounds {
            left: -1,
            top: 0,
            right: 2,
            bottom: 1,
        },
        255,
    );
    let mut pixels = vec![255; MASK_TILE_BYTES];
    pixels[..3].copy_from_slice(&[0, 128, 255]);
    mask.tiles.insert((0, 0), Arc::new(pixels));
    engine.document.layers[1].set_first_mask(Some(mask));
    engine.document.assign_mask_ids().unwrap();
    let encoded = export(&engine, ExportFormat::Psd);
    let records = parse_psd_records(&encoded);
    let group = records.last().unwrap();
    assert_eq!(group.channels, [-2]);
    assert_eq!(group.data, [vec![0, 128, 255]]);
    let mut reopened = Engine::new(1, 1).unwrap();
    reopened.load(&encoded).unwrap();
    assert_eq!(engine.save().unwrap(), reopened.save().unwrap());
}
