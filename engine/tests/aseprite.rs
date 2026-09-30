use flate2::{read::ZlibDecoder, write::ZlibEncoder, Compression};
use podor_engine::{
    animation::{CelSource, TagDirection},
    model::*,
    Command, Engine, ExportFormat, ExportOptions,
};
use serde_json::json;
use std::{
    io::{Read, Write},
    sync::Arc,
};

const RED: [u8; 4] = [255, 0, 0, 255];
const GREEN: [u8; 4] = [0, 255, 0, 255];
const BLUE: [u8; 4] = [0, 0, 255, 255];
const CLEAR: [u8; 4] = [0; 4];

#[derive(Clone, Debug)]
struct ExternalFrame {
    duration: u16,
    chunks: Vec<Vec<u8>>,
}

#[derive(Clone)]
struct ExternalFile {
    width: u16,
    height: u16,
    depth: u16,
    flags: u32,
    speed: u16,
    transparent: u8,
    colors: u16,
    frames: Vec<ExternalFrame>,
}

impl ExternalFile {
    fn rgba(width: u16, height: u16, frames: Vec<ExternalFrame>) -> Self {
        Self {
            width,
            height,
            depth: 32,
            flags: 3,
            speed: 100,
            transparent: 0,
            colors: 0,
            frames,
        }
    }

    fn bytes(&self) -> Vec<u8> {
        let mut output = vec![0; 128];
        output[4..6].copy_from_slice(&0xa5e0u16.to_le_bytes());
        output[6..8].copy_from_slice(&(self.frames.len() as u16).to_le_bytes());
        output[8..10].copy_from_slice(&self.width.to_le_bytes());
        output[10..12].copy_from_slice(&self.height.to_le_bytes());
        output[12..14].copy_from_slice(&self.depth.to_le_bytes());
        output[14..18].copy_from_slice(&self.flags.to_le_bytes());
        output[18..20].copy_from_slice(&self.speed.to_le_bytes());
        output[28] = self.transparent;
        output[32..34].copy_from_slice(&self.colors.to_le_bytes());
        output[34..36].copy_from_slice(&[1, 1]);
        for frame in &self.frames {
            let length = 16 + frame.chunks.iter().map(Vec::len).sum::<usize>();
            output.extend((length as u32).to_le_bytes());
            output.extend(0xf1fau16.to_le_bytes());
            output.extend((frame.chunks.len() as u16).to_le_bytes());
            output.extend(frame.duration.to_le_bytes());
            output.extend([0; 2]);
            output.extend((frame.chunks.len() as u32).to_le_bytes());
            for value in &frame.chunks {
                output.extend(value);
            }
        }
        let length = output.len() as u32;
        output[..4].copy_from_slice(&length.to_le_bytes());
        output
    }
}

fn chunk(kind: u16, payload: &[u8]) -> Vec<u8> {
    let mut output = ((payload.len() + 6) as u32).to_le_bytes().to_vec();
    output.extend(kind.to_le_bytes());
    output.extend(payload);
    output
}

fn string(value: &str, output: &mut Vec<u8>) {
    output.extend((value.len() as u16).to_le_bytes());
    output.extend(value.as_bytes());
}

#[derive(Clone)]
struct ExternalLayer {
    name: String,
    flags: u16,
    kind: u16,
    depth: u16,
    blend: u16,
    opacity: u8,
}

impl ExternalLayer {
    fn image(name: &str) -> Self {
        Self {
            name: name.into(),
            flags: 3,
            kind: 0,
            depth: 0,
            blend: 0,
            opacity: 255,
        }
    }

    fn bytes(&self) -> Vec<u8> {
        let mut payload = self.flags.to_le_bytes().to_vec();
        payload.extend(self.kind.to_le_bytes());
        payload.extend(self.depth.to_le_bytes());
        payload.extend([0; 4]);
        payload.extend(self.blend.to_le_bytes());
        payload.push(self.opacity);
        payload.extend([0; 3]);
        string(&self.name, &mut payload);
        chunk(0x2004, &payload)
    }
}

#[derive(Clone)]
struct ExternalCel {
    layer: u16,
    x: i16,
    y: i16,
    opacity: u8,
    z: i16,
    kind: u16,
    width: u16,
    height: u16,
    pixels: Vec<u8>,
    link: u16,
}

impl ExternalCel {
    fn raw(layer: u16, width: u16, height: u16, pixels: &[u8]) -> Self {
        Self {
            layer,
            x: 0,
            y: 0,
            opacity: 255,
            z: 0,
            kind: 0,
            width,
            height,
            pixels: pixels.to_vec(),
            link: 0,
        }
    }

    fn linked(layer: u16, target: u16) -> Self {
        let mut result = Self::raw(layer, 0, 0, &[]);
        result.kind = 1;
        result.link = target;
        result
    }

    fn bytes(&self) -> Vec<u8> {
        let mut payload = self.layer.to_le_bytes().to_vec();
        payload.extend(self.x.to_le_bytes());
        payload.extend(self.y.to_le_bytes());
        payload.push(self.opacity);
        payload.extend(self.kind.to_le_bytes());
        payload.extend(self.z.to_le_bytes());
        payload.extend([0; 5]);
        if self.kind == 1 {
            payload.extend(self.link.to_le_bytes());
        } else {
            payload.extend(self.width.to_le_bytes());
            payload.extend(self.height.to_le_bytes());
            if self.kind == 2 {
                payload.extend(deflate(&self.pixels));
            } else {
                payload.extend(&self.pixels);
            }
        }
        chunk(0x2005, &payload)
    }
}

fn deflate(bytes: &[u8]) -> Vec<u8> {
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::fast());
    encoder.write_all(bytes).unwrap();
    encoder.finish().unwrap()
}

fn inflate(bytes: &[u8]) -> Vec<u8> {
    let mut output = Vec::new();
    ZlibDecoder::new(bytes).read_to_end(&mut output).unwrap();
    output
}

fn frame(duration: u16, chunks: Vec<Vec<u8>>) -> ExternalFrame {
    ExternalFrame { duration, chunks }
}

fn basic_file() -> ExternalFile {
    ExternalFile::rgba(
        2,
        1,
        vec![frame(
            83,
            vec![
                ExternalLayer::image("Ink").bytes(),
                ExternalCel::raw(0, 2, 1, &[RED, BLUE].concat()).bytes(),
            ],
        )],
    )
}

fn load(bytes: &[u8]) -> Engine {
    let mut engine = Engine::new(8, 8).unwrap();
    engine.load(bytes).unwrap();
    engine
}

fn png_pixels(engine: &Engine, index: usize) -> Vec<[u8; 4]> {
    let frame_id = engine
        .document
        .animation
        .as_ref()
        .map(|animation| animation.frames[index].id);
    let bytes = engine
        .export_image(ExportOptions {
            format: ExportFormat::Png,
            transparent: true,
            frame_id,
            ..ExportOptions::default()
        })
        .unwrap();
    let mut decoder = png::Decoder::new(bytes.as_slice()).read_info().unwrap();
    let mut output = vec![0; decoder.output_buffer_size()];
    let info = decoder.next_frame(&mut output).unwrap();
    assert_eq!(info.color_type, png::ColorType::Rgba);
    output.truncate(info.buffer_size());
    output.as_chunks::<4>().0.to_vec()
}

fn unchanged_rejection(bytes: &[u8]) {
    let mut engine = Engine::new(4, 4).unwrap();
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: RED,
            tolerance: 0,
            contiguous: true,
            merged: false,
        })
        .unwrap();
    engine
        .command(Command::Select {
            rect: Some(Rect {
                left: 1,
                top: 1,
                right: 3,
                bottom: 3,
            }),
        })
        .unwrap();
    engine.frame_with_background(true);
    let saved = engine.save().unwrap();
    let state = engine.state();
    let selection = engine.selection_frame();
    assert!(
        engine.load(bytes).is_err(),
        "accepted unsupported or malformed Aseprite input"
    );
    assert_eq!(engine.state(), state);
    assert!(
        engine.save().unwrap() == saved,
        "failed import changed project bytes"
    );
    assert!(engine.selection_frame() == selection);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    engine.command(Command::Undo).unwrap();
    assert_eq!(png_pixels(&engine, 0), vec![CLEAR; 16]);
    engine.command(Command::Redo).unwrap();
    assert_eq!(png_pixels(&engine, 0), vec![RED; 16]);
}

fn export(engine: &Engine, bake_layers: bool) -> Result<Vec<u8>, String> {
    let request = serde_json::from_value(json!({
        "revision":engine.state()["revision"],
        "bake_layers":bake_layers
    }))
    .unwrap();
    engine.export_aseprite(request)
}

fn palette(size: u32, first: u32, entries: &[([u8; 4], Option<&str>)]) -> Vec<u8> {
    let mut payload = size.to_le_bytes().to_vec();
    payload.extend(first.to_le_bytes());
    payload.extend((first + entries.len() as u32 - 1).to_le_bytes());
    payload.extend([0; 8]);
    for (color, name) in entries {
        payload.extend(u16::from(name.is_some()).to_le_bytes());
        payload.extend(color);
        if let Some(name) = name {
            string(name, &mut payload);
        }
    }
    chunk(0x2019, &payload)
}

fn indexed_file() -> ExternalFile {
    let colors = [
        ([173, 91, 43, 255], None),
        ([22, 148, 210, 255], None),
        ([9, 8, 7, 255], None),
        ([173, 91, 43, 255], None),
    ];
    let mut file = ExternalFile::rgba(
        4,
        1,
        vec![frame(
            39,
            vec![
                palette(4, 0, &colors),
                ExternalLayer::image("Indices").bytes(),
                ExternalCel::raw(0, 4, 1, &[0, 1, 2, 3]).bytes(),
            ],
        )],
    );
    file.depth = 8;
    file.transparent = 2;
    file.colors = 4;
    file
}

type ExternalTag<'a> = (u16, u16, u8, u16, [u8; 3], &'a str);
fn tags(entries: &[ExternalTag<'_>]) -> Vec<u8> {
    let mut payload = (entries.len() as u16).to_le_bytes().to_vec();
    payload.extend([0; 8]);
    for (from, to, direction, repeat, color, name) in entries {
        payload.extend(from.to_le_bytes());
        payload.extend(to.to_le_bytes());
        payload.push(*direction);
        payload.extend(repeat.to_le_bytes());
        payload.extend([0; 6]);
        payload.extend(color);
        payload.push(0);
        string(name, &mut payload);
    }
    chunk(0x2018, &payload)
}

fn user_color(color: [u8; 4]) -> Vec<u8> {
    let mut payload = 2u32.to_le_bytes().to_vec();
    payload.extend(color);
    chunk(0x2020, &payload)
}

#[derive(Debug)]
struct ParsedFile {
    depth: u16,
    flags: u32,
    transparent: u8,
    frames: Vec<ExternalFrame>,
}

fn u16_at(bytes: &[u8], index: usize) -> u16 {
    u16::from_le_bytes(bytes[index..index + 2].try_into().unwrap())
}

fn u32_at(bytes: &[u8], index: usize) -> u32 {
    u32::from_le_bytes(bytes[index..index + 4].try_into().unwrap())
}

fn parse_export(bytes: &[u8]) -> ParsedFile {
    assert!(bytes.len() >= 128);
    assert_eq!(u32_at(bytes, 0) as usize, bytes.len());
    assert_eq!(u16_at(bytes, 4), 0xa5e0);
    let mut cursor = 128;
    let mut frames = Vec::new();
    for _ in 0..u16_at(bytes, 6) {
        let end = cursor + u32_at(bytes, cursor) as usize;
        assert!(end <= bytes.len());
        assert_eq!(u16_at(bytes, cursor + 4), 0xf1fa);
        let duration = u16_at(bytes, cursor + 8);
        let old_count = u16_at(bytes, cursor + 6);
        let new_count = u32_at(bytes, cursor + 12);
        let count = if new_count == 0 {
            u32::from(old_count)
        } else {
            new_count
        };
        cursor += 16;
        let mut chunks = Vec::new();
        for _ in 0..count {
            let length = u32_at(bytes, cursor) as usize;
            assert!(length >= 6 && cursor + length <= end);
            chunks.push(bytes[cursor..cursor + length].to_vec());
            cursor += length;
        }
        assert_eq!(cursor, end);
        frames.push(ExternalFrame { duration, chunks });
    }
    assert_eq!(cursor, bytes.len());
    ParsedFile {
        depth: u16_at(bytes, 12),
        flags: u32_at(bytes, 14),
        transparent: bytes[28],
        frames,
    }
}

fn chunks_of(frame: &ExternalFrame, kind: u16) -> Vec<&[u8]> {
    frame
        .chunks
        .iter()
        .filter(|bytes| u16_at(bytes, 4) == kind)
        .map(|bytes| &bytes[6..])
        .collect()
}

fn raw_export_cel(payload: &[u8]) -> Vec<u8> {
    assert!(matches!(u16_at(payload, 7), 0 | 2));
    if u16_at(payload, 7) == 2 {
        inflate(&payload[20..])
    } else {
        payload[20..].to_vec()
    }
}

fn canonical_engine(document: Document) -> Engine {
    let mut engine = Engine::new(document.width, document.height).unwrap();
    engine.document = document;
    let saved = engine.save().unwrap();
    engine.load(&saved).unwrap();
    engine
}

fn parsed_string(payload: &[u8], index: &mut usize) -> String {
    let length = u16_at(payload, *index) as usize;
    *index += 2;
    let value = std::str::from_utf8(&payload[*index..*index + length])
        .unwrap()
        .to_owned();
    *index += length;
    value
}

fn parsed_palette(payload: &[u8]) -> (Vec<[u8; 4]>, Vec<Option<String>>) {
    let first = u32_at(payload, 4);
    let last = u32_at(payload, 8);
    let mut cursor = 20;
    let mut colors = Vec::new();
    let mut names = Vec::new();
    for _ in first..=last {
        let flags = u16_at(payload, cursor);
        cursor += 2;
        colors.push(payload[cursor..cursor + 4].try_into().unwrap());
        cursor += 4;
        names.push((flags & 1 != 0).then(|| parsed_string(payload, &mut cursor)));
    }
    assert_eq!(cursor, payload.len());
    (colors, names)
}

#[test]
fn rgba_companion_palette_names_grid_and_srgb_survive_native_save_and_external_export() {
    let mut file = basic_file();
    let colors = [
        ([173, 91, 43, 255], Some("Copper")),
        ([22, 148, 210, 128], Some("海蓝")),
        ([10, 20, 30, 0], None),
    ];
    file.colors = 3;
    file.frames[0].chunks.insert(0, palette(3, 0, &colors));
    let mut profile = 1u16.to_le_bytes().to_vec();
    profile.extend([0; 14]);
    file.frames[0].chunks.insert(0, chunk(0x2007, &profile));
    let mut bytes = file.bytes();
    bytes[36..38].copy_from_slice(&(-3i16).to_le_bytes());
    bytes[38..40].copy_from_slice(&5i16.to_le_bytes());
    bytes[40..42].copy_from_slice(&16u16.to_le_bytes());
    bytes[42..44].copy_from_slice(&24u16.to_le_bytes());
    let engine = load(&bytes);
    assert!(
        engine.document.palette.is_none(),
        "RGBA swatches must not switch the pixel color mode"
    );
    let metadata = engine.state()["asepriteMetadata"].clone();
    assert_eq!(
        metadata["companionPalette"]["colors"],
        json!(colors.map(|(color, _)| color))
    );
    assert_eq!(
        metadata["companionPalette"]["names"],
        json!(["Copper", "海蓝", null])
    );
    assert_eq!(
        metadata["grid"],
        json!({"x":-3,"y":5,"width":16,"height":24})
    );
    assert_eq!(metadata["srgb"], true);
    assert_eq!(png_pixels(&engine, 0), [RED, BLUE]);
    let saved = engine.save().unwrap();
    assert!(saved.starts_with(b"PODOR\x0c"));
    let restored = load(&saved);
    assert_eq!(restored.state()["asepriteMetadata"], metadata);
    let exported = export(&restored, false).unwrap();
    assert_eq!(&exported[36..44], &bytes[36..44]);
    let parsed = parse_export(&exported);
    let companion = chunks_of(&parsed.frames[0], 0x2019);
    assert_eq!(companion.len(), 1);
    let (actual_colors, names) = parsed_palette(companion[0]);
    assert_eq!(actual_colors, colors.map(|(color, _)| color));
    assert_eq!(names, [Some("Copper".into()), Some("海蓝".into()), None]);
    let profiles = chunks_of(&parsed.frames[0], 0x2007);
    assert_eq!(profiles.len(), 1);
    assert_eq!(u16_at(profiles[0], 0), 1);
    assert_eq!(u16_at(profiles[0], 2), 0);
}

#[test]
fn indexed_palette_names_survive_save_and_export_without_replacing_slot_identity() {
    let mut file = indexed_file();
    file.frames[0].chunks[0] = palette(
        4,
        0,
        &[
            ([173, 91, 43, 255], Some("Copper")),
            ([22, 148, 210, 255], None),
            ([9, 8, 7, 255], Some("Transparent")),
            ([173, 91, 43, 255], Some("Other copper")),
        ],
    );
    let engine = load(&file.bytes());
    let names = json!(["Copper", null, "Transparent", "Other copper"]);
    assert_eq!(
        engine.state()["asepriteMetadata"]["indexedPaletteNames"],
        names
    );
    let restored = load(&engine.save().unwrap());
    assert_eq!(
        restored.state()["asepriteMetadata"]["indexedPaletteNames"],
        names
    );
    let parsed = parse_export(&export(&restored, false).unwrap());
    let (_, actual_names) = parsed_palette(chunks_of(&parsed.frames[0], 0x2019)[0]);
    assert_eq!(
        actual_names,
        [
            Some("Copper".into()),
            None,
            Some("Transparent".into()),
            Some("Other copper".into())
        ]
    );
    assert_eq!(
        raw_export_cel(chunks_of(&parsed.frames[0], 0x2005)[0]),
        [0, 1, 2, 3]
    );
}

#[test]
fn changing_only_a_palette_name_in_later_frames_is_not_silently_lost() {
    let mut file = indexed_file();
    file.frames[0].chunks[0] = palette(
        4,
        0,
        &[
            ([173, 91, 43, 255], Some("Copper")),
            ([22, 148, 210, 255], None),
            ([9, 8, 7, 255], None),
            ([173, 91, 43, 255], None),
        ],
    );
    file.frames.push(frame(
        100,
        vec![
            palette(4, 0, &[([173, 91, 43, 255], Some("Different name"))]),
            ExternalCel::linked(0, 0).bytes(),
        ],
    ));
    unchanged_rejection(&file.bytes());
}

#[test]
fn old_eight_bit_and_six_bit_palettes_decode_256_entry_zero_count_packets() {
    for kind in [0x0004, 0x0011] {
        let max = if kind == 0x0011 { 63 } else { 255 };
        let mut payload = 1u16.to_le_bytes().to_vec();
        payload.extend([0, 0]);
        payload.extend([0, 0, 0]);
        payload.extend([max, 0, 0]);
        payload.extend([0, max, 0]);
        payload.extend([0, 0, max]);
        payload.extend(vec![0; (256 - 4) * 3]);
        let mut file = indexed_file();
        file.transparent = 0;
        file.colors = 0;
        file.frames[0].chunks[0] = chunk(kind, &payload);
        let engine = load(&file.bytes());
        assert_eq!(engine.document.palette.as_ref().unwrap().colors.len(), 256);
        assert_eq!(png_pixels(&engine, 0), [CLEAR, RED, GREEN, BLUE]);
    }
}

#[test]
fn new_palette_takes_precedence_over_conflicting_old_palette_in_either_chunk_order() {
    let mut old = 1u16.to_le_bytes().to_vec();
    old.extend([0, 4]);
    old.extend([0, 0, 255].repeat(4));
    for before in [false, true] {
        let mut file = indexed_file();
        file.frames[0]
            .chunks
            .insert(usize::from(!before), chunk(0x0004, &old));
        assert_eq!(
            png_pixels(&load(&file.bytes()), 0),
            [
                [173, 91, 43, 255],
                [22, 148, 210, 255],
                CLEAR,
                [173, 91, 43, 255]
            ]
        );
    }
}

#[test]
fn old_palette_out_of_range_and_invalid_six_bit_channels_fail_atomically() {
    for (kind, payload) in [
        (0x0011, [1, 0, 0, 1, 64, 0, 0].to_vec()),
        (0x0004, [1, 0, 255, 2, 0, 0, 0, 0, 0, 0].to_vec()),
    ] {
        let mut file = indexed_file();
        file.frames[0].chunks[0] = chunk(kind, &payload);
        unchanged_rejection(&file.bytes());
    }
}

#[test]
fn independent_duplicate_cels_sharing_source_memory_export_as_images_instead_of_links() {
    let mut file = basic_file();
    file.frames
        .push(frame(127, vec![ExternalCel::linked(0, 0).bytes()]));
    let imported = load(&file.bytes());
    let mut document = imported.document.clone();
    let animation = Arc::make_mut(document.animation.as_mut().unwrap());
    let original = animation.cels.values().next().unwrap().clone();
    let mut duplicate = (*original).clone();
    duplicate.id = animation.next_cel_id;
    animation.next_cel_id += 1;
    animation.frames[1]
        .exposures
        .insert(duplicate.layer_id, duplicate.id);
    animation.cels.insert(duplicate.id, Arc::new(duplicate));
    let engine = canonical_engine(document);
    let parsed = parse_export(&export(&engine, false).unwrap());
    let second = chunks_of(&parsed.frames[1], 0x2005);
    assert_eq!(second.len(), 1);
    assert_eq!(u16_at(second[0], 7), 2);
    assert_eq!(raw_export_cel(second[0]), [RED, BLUE].concat());
}

#[test]
fn explicit_bake_preserves_all_frame_durations_and_masks_visible_alpha_without_changing_original() {
    let mut file = basic_file();
    file.frames
        .push(frame(127, vec![ExternalCel::linked(0, 0).bytes()]));
    file.frames[0]
        .chunks
        .insert(0, tags(&[(0, 1, 0, 4, [20, 30, 40], "Masked")]));
    let imported = load(&file.bytes());
    let mut document = imported.document.clone();
    let animation = Arc::make_mut(document.animation.as_mut().unwrap());
    let cel = Arc::make_mut(animation.cels.values_mut().next().unwrap());
    let mut plane = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 2,
            bottom: 1,
        },
        0,
    );
    let mut pixels = vec![0; MASK_TILE_BYTES];
    pixels[0] = 255;
    plane.tiles.insert((0, 0), Arc::new(pixels));
    cel.masks.push(MaskEntry {
        id: 1,
        name: "Keep red".into(),
        plane,
    });
    document.next_mask_id = 2;
    let mut engine = canonical_engine(document);
    engine.frame_with_background(true);
    let saved = engine.save().unwrap();
    let state = engine.state();
    assert!(export(&engine, false).is_err());
    let bytes = export(&engine, true).unwrap();
    let parsed = parse_export(&bytes);
    assert_eq!(parsed.depth, 32);
    assert_eq!(
        parsed
            .frames
            .iter()
            .map(|frame| frame.duration)
            .collect::<Vec<_>>(),
        [83, 127]
    );
    assert_eq!(chunks_of(&parsed.frames[0], 0x2004).len(), 1);
    assert_eq!(chunks_of(&parsed.frames[0], 0x2018).len(), 1);
    assert_eq!(raw_export_cel(chunks_of(&parsed.frames[0], 0x2005)[0]), RED);
    assert_eq!(png_pixels(&engine, 0), [RED, CLEAR]);
    assert_eq!(png_pixels(&engine, 1), [RED, CLEAR]);
    assert_eq!(engine.state(), state);
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

#[test]
fn exported_tags_have_zero_based_frame_ranges_and_color_userdata_in_original_order() {
    let mut file = basic_file();
    file.frames
        .push(frame(127, vec![ExternalCel::linked(0, 0).bytes()]));
    file.frames[0].chunks.splice(
        0..0,
        [
            tags(&[
                (0, 1, 1, 65535, [1, 2, 3], "Reverse"),
                (1, 1, 2, 0, [4, 5, 6], "Ping"),
            ]),
            user_color([40, 60, 80, 100]),
            user_color([90, 110, 130, 255]),
        ],
    );
    let engine = load(&file.bytes());
    let parsed = parse_export(&export(&engine, false).unwrap());
    let payloads = chunks_of(&parsed.frames[0], 0x2018);
    assert_eq!(payloads.len(), 1);
    let payload = payloads[0];
    assert_eq!(u16_at(payload, 0), 2);
    let mut cursor = 10;
    for (from, to, direction, repeat, name) in [(0, 1, 1, 65535, "Reverse"), (1, 1, 2, 0, "Ping")] {
        assert_eq!(u16_at(payload, cursor), from);
        assert_eq!(u16_at(payload, cursor + 2), to);
        assert_eq!(payload[cursor + 4], direction);
        assert_eq!(u16_at(payload, cursor + 5), repeat);
        cursor += 17;
        assert_eq!(parsed_string(payload, &mut cursor), name);
    }
    assert_eq!(cursor, payload.len());
    let colors = chunks_of(&parsed.frames[0], 0x2020);
    assert_eq!(colors.len(), 2);
    assert_eq!(u32_at(colors[0], 0), 2);
    assert_eq!(&colors[0][4..8], &[40, 60, 80, 100]);
    assert_eq!(&colors[1][4..8], &[90, 110, 130, 255]);
}

#[test]
fn still_document_exports_as_one_real_frame_with_default_duration() {
    let mut engine = Engine::new(2, 1).unwrap();
    engine
        .command(Command::Fill {
            x: 0,
            y: 0,
            color: GREEN,
            tolerance: 0,
            contiguous: true,
            merged: false,
        })
        .unwrap();
    let parsed = parse_export(&export(&engine, false).unwrap());
    assert_eq!(parsed.frames.len(), 1);
    assert_eq!(parsed.frames[0].duration, 100);
    assert_eq!(
        raw_export_cel(chunks_of(&parsed.frames[0], 0x2005)[0]),
        [GREEN, GREEN].concat()
    );
}

#[test]
fn hand_written_raw_and_zlib_frames_keep_individual_durations_and_pixels() {
    let mut compressed = ExternalCel::raw(0, 2, 1, &[GREEN, RED].concat());
    compressed.kind = 2;
    let mut file = basic_file();
    file.frames.push(frame(137, vec![compressed.bytes()]));
    let engine = load(&file.bytes());
    let animation = engine.document.animation.as_ref().unwrap();
    assert_eq!(
        animation
            .frames
            .iter()
            .map(|frame| frame.duration_ms)
            .collect::<Vec<_>>(),
        [83, 137]
    );
    assert_eq!(png_pixels(&engine, 0), [RED, BLUE]);
    assert_eq!(png_pixels(&engine, 1), [GREEN, RED]);
}

#[test]
fn native_mapped_blend_codes_preserve_their_enum_and_file_values() {
    for (code, expected) in [
        (0, BlendMode::Normal),
        (1, BlendMode::Multiply),
        (2, BlendMode::Screen),
        (3, BlendMode::Overlay),
        (4, BlendMode::Darken),
        (5, BlendMode::Lighten),
        (9, BlendMode::SoftLight),
        (10, BlendMode::Difference),
    ] {
        let mut file = basic_file();
        let mut layer = ExternalLayer::image("Blend");
        layer.blend = code;
        file.frames[0].chunks[0] = layer.bytes();
        let engine = load(&file.bytes());
        assert_eq!(engine.document.layers[0].blend, expected);
        let parsed = parse_export(&export(&engine, false).unwrap());
        let actual = chunks_of(&parsed.frames[0], 0x2004);
        assert_eq!(u16_at(actual[0], 10), code);
    }
    for code in [6, 7, 8, 11, 12, 13, 14, 15, 16, 17, 18] {
        let mut file = basic_file();
        let mut layer = ExternalLayer::image("Unsupported blend");
        layer.blend = code;
        file.frames[0].chunks[0] = layer.bytes();
        unchanged_rejection(&file.bytes());
    }
}

#[test]
fn group_export_preserves_uniform_isolation_and_rejects_mixed_semantics() {
    for (flags, isolation) in [
        (1, GroupIsolation::PassThrough),
        (3, GroupIsolation::Isolated),
    ] {
        let mut group = ExternalLayer::image("Group");
        group.kind = 1;
        group.flags |= 32;
        let mut child = ExternalLayer::image("Child");
        child.depth = 1;
        let mut file = basic_file();
        file.flags = flags;
        file.frames[0].chunks = vec![
            group.bytes(),
            child.bytes(),
            ExternalCel::raw(1, 2, 1, &[RED, BLUE].concat()).bytes(),
        ];
        let engine = load(&file.bytes());
        assert_eq!(
            engine.document.layers[0].content,
            LayerContent::Group {
                isolation,
                closed: true
            }
        );
        let parsed = parse_export(&export(&engine, false).unwrap());
        assert_eq!(parsed.flags & 3, flags);
        let layers = chunks_of(&parsed.frames[0], 0x2004);
        assert_eq!(layers.len(), 2);
        assert_eq!(u16_at(layers[0], 2), 1);
        assert_eq!(u16_at(layers[0], 0) & 32, 32);
        assert_eq!(u16_at(layers[1], 4), 1);
        assert_eq!(u16_at(chunks_of(&parsed.frames[0], 0x2005)[0], 0), 1);
    }
    let imported = load(&basic_file().bytes());
    let mut document = imported.document.clone();
    document
        .layers
        .push(Layer::group(2, "Isolated".into(), GroupIsolation::Isolated));
    document.layers.push(Layer::group(
        3,
        "Pass through".into(),
        GroupIsolation::PassThrough,
    ));
    document.next_id = 4;
    let engine = canonical_engine(document);
    let saved = engine.save().unwrap();
    assert!(export(&engine, false).is_err());
    assert!(export(&engine, true).is_ok());
    assert!(engine.save().unwrap() == saved);
}

#[test]
fn live_still_and_animation_strokes_cannot_export_partial_projects() {
    for mut engine in [Engine::new(2, 1).unwrap(), load(&basic_file().bytes())] {
        let before = engine.save().unwrap();
        engine
            .command(Command::Begin {
                brush: Brush::default(),
                assistant: None,
            })
            .unwrap();
        let live = engine.state();
        assert!(export(&engine, false).is_err());
        assert!(export(&engine, true).is_err());
        assert_eq!(engine.state(), live);
        engine.command(Command::Cancel).unwrap();
        assert!(engine.save().unwrap() == before);
    }
}

#[test]
fn empty_tag_userdata_retains_legacy_color_and_invalid_tag_ranges_are_rejected() {
    let mut file = basic_file();
    file.frames[0].chunks.splice(
        0..0,
        [
            tags(&[(0, 0, 0, 1, [20, 40, 60], "Empty userdata")]),
            chunk(0x2020, &0u32.to_le_bytes()),
        ],
    );
    let engine = load(&file.bytes());
    assert_eq!(
        engine.document.animation.as_ref().unwrap().tags[0].color,
        [20, 40, 60, 255]
    );
    for (from, to, direction) in [(0, 1, 0), (1, 0, 0), (0, 0, 4)] {
        let mut file = basic_file();
        file.frames[0]
            .chunks
            .insert(0, tags(&[(from, to, direction, 0, [1, 2, 3], "Invalid")]));
        unchanged_rejection(&file.bytes());
    }
}

#[test]
fn absent_exposure_and_real_empty_cel_are_distinct() {
    let mut file = basic_file();
    file.frames.push(frame(70, vec![]));
    file.frames.push(frame(
        90,
        vec![ExternalCel::raw(0, 2, 1, &[CLEAR, CLEAR].concat()).bytes()],
    ));
    let engine = load(&file.bytes());
    let animation = engine.document.animation.as_ref().unwrap();
    assert!(animation.frames[1].exposures.is_empty());
    assert_eq!(animation.frames[2].exposures.len(), 1);
    assert_eq!(animation.cels.len(), 2);
    assert_eq!(png_pixels(&engine, 1), [CLEAR, CLEAR]);
    assert_eq!(png_pixels(&engine, 2), [CLEAR, CLEAR]);
}

#[test]
fn same_layer_backward_link_chains_share_cel_identity() {
    let mut file = basic_file();
    file.frames
        .push(frame(130, vec![ExternalCel::linked(0, 0).bytes()]));
    file.frames
        .push(frame(210, vec![ExternalCel::linked(0, 1).bytes()]));
    let engine = load(&file.bytes());
    let animation = engine.document.animation.as_ref().unwrap();
    let first = animation.frames[0].exposures.values().next().unwrap();
    assert!(animation
        .frames
        .iter()
        .all(|frame| frame.exposures.values().next() == Some(first)));
    assert_eq!(animation.cels.len(), 1);
    assert_eq!(png_pixels(&engine, 2), [RED, BLUE]);
}

#[test]
fn cropped_positive_positioned_cel_and_equal_position_link_keep_canvas_placement() {
    let mut cel = ExternalCel::raw(0, 2, 1, &[RED, BLUE].concat());
    cel.x = 1;
    cel.y = 1;
    let mut link = ExternalCel::linked(0, 0);
    link.x = 1;
    link.y = 1;
    let file = ExternalFile::rgba(
        4,
        2,
        vec![
            frame(
                40,
                vec![ExternalLayer::image("Offset").bytes(), cel.bytes()],
            ),
            frame(80, vec![link.bytes()]),
        ],
    );
    let engine = load(&file.bytes());
    let expected = [CLEAR, CLEAR, CLEAR, CLEAR, CLEAR, RED, BLUE, CLEAR];
    assert_eq!(png_pixels(&engine, 0), expected);
    assert_eq!(png_pixels(&engine, 1), expected);
    let animation = engine.document.animation.as_ref().unwrap();
    assert_eq!(animation.frames[0].exposures, animation.frames[1].exposures);
}

#[test]
fn duration_zero_uses_header_speed_and_nonzero_frame_duration_wins() {
    let mut file = basic_file();
    file.speed = 193;
    file.frames[0].duration = 0;
    file.frames
        .push(frame(41, vec![ExternalCel::linked(0, 0).bytes()]));
    let engine = load(&file.bytes());
    assert_eq!(
        engine
            .document
            .animation
            .as_ref()
            .unwrap()
            .frames
            .iter()
            .map(|frame| frame.duration_ms)
            .collect::<Vec<_>>(),
        [193, 41]
    );
    file.speed = 0;
    unchanged_rejection(&file.bytes());
    file.speed = 193;
    file.frames[1].duration = 60001;
    unchanged_rejection(&file.bytes());
}

#[test]
fn header_flags_decide_whether_image_opacity_bytes_are_valid() {
    for (flags, expected) in [(0, 1.0), (1, 128.0 / 255.0)] {
        let mut layer = ExternalLayer::image("Opacity");
        layer.opacity = 128;
        let mut file = basic_file();
        file.flags = flags;
        file.frames[0].chunks[0] = layer.bytes();
        let engine = load(&file.bytes());
        assert_eq!(engine.document.layers[0].opacity, expected);
    }
}

#[test]
fn nested_groups_keep_hierarchy_order_visibility_editability_and_collapsed_state() {
    let mut group = ExternalLayer::image("Outer");
    group.kind = 1;
    group.flags = 1 | 32;
    group.opacity = 128;
    let mut inner = group.clone();
    inner.name = "Inner".into();
    inner.depth = 1;
    inner.flags = 3;
    inner.opacity = 255;
    let mut child = ExternalLayer::image("Child");
    child.depth = 2;
    let mut hidden = ExternalLayer::image("Hidden");
    hidden.flags = 2;
    let file = ExternalFile::rgba(
        2,
        1,
        vec![frame(
            100,
            vec![
                group.bytes(),
                inner.bytes(),
                child.bytes(),
                hidden.bytes(),
                ExternalCel::raw(2, 2, 1, &[RED, BLUE].concat()).bytes(),
                ExternalCel::raw(3, 2, 1, &[GREEN, GREEN].concat()).bytes(),
            ],
        )],
    );
    let engine = load(&file.bytes());
    let layers = &engine.document.layers;
    assert_eq!(
        layers
            .iter()
            .map(|layer| layer.name.as_str())
            .collect::<Vec<_>>(),
        ["Outer", "Inner", "Child", "Hidden"]
    );
    assert_eq!(layers[1].parent_id, Some(layers[0].id));
    assert_eq!(layers[2].parent_id, Some(layers[1].id));
    assert_eq!(layers[3].parent_id, None);
    assert!(layers[0].locked);
    assert!(!layers[3].visible);
    assert_eq!(
        layers[0].content,
        LayerContent::Group {
            isolation: GroupIsolation::Isolated,
            closed: true
        }
    );
    assert_eq!(png_pixels(&engine, 0), [[255, 0, 0, 128], [0, 0, 255, 128]]);
}

#[test]
fn noncomposing_group_ignores_opacity_and_blend_fields() {
    let mut group = ExternalLayer::image("Pass Through");
    group.kind = 1;
    group.opacity = 0;
    group.blend = 65535;
    let mut child = ExternalLayer::image("Child");
    child.depth = 1;
    let mut file = basic_file();
    file.flags = 1;
    file.frames[0].chunks = vec![
        group.bytes(),
        child.bytes(),
        ExternalCel::raw(1, 2, 1, &[RED, BLUE].concat()).bytes(),
    ];
    let engine = load(&file.bytes());
    assert_eq!(
        engine.document.layers[0].content,
        LayerContent::Group {
            isolation: GroupIsolation::PassThrough,
            closed: false
        }
    );
    assert_eq!(engine.document.layers[0].opacity, 1.0);
    assert_eq!(engine.document.layers[0].blend, BlendMode::Normal);
    assert_eq!(png_pixels(&engine, 0), [RED, BLUE]);
}

#[test]
fn indexed_header_transparency_preserves_nonzero_slot_and_distinct_equal_color_indices() {
    let engine = load(&indexed_file().bytes());
    let palette = engine.document.palette.as_ref().unwrap();
    assert_eq!(palette.transparent, 2);
    assert_eq!(palette.colors[0], [173, 91, 43, 255]);
    assert_eq!(palette.colors[1], [22, 148, 210, 255]);
    assert_eq!(palette.colors[2][3], 0);
    assert_eq!(palette.colors[3], palette.colors[0]);
    let source = if let Some(animation) = &engine.document.animation {
        match &animation.cels.values().next().unwrap().source {
            CelSource::Raster(source) => source.as_ref(),
            _ => panic!("indexed source became vector"),
        }
    } else {
        engine.document.layers[0].raster().unwrap()
    };
    assert_eq!(&source.tiles().get(&(0, 0)).unwrap()[..4], &[0, 1, 2, 3]);
    assert_eq!(
        png_pixels(&engine, 0),
        [
            [173, 91, 43, 255],
            [22, 148, 210, 255],
            CLEAR,
            [173, 91, 43, 255]
        ]
    );
}

#[test]
fn indexed_palette_delta_may_repeat_existing_color_but_must_not_change_later_frames() {
    let mut file = indexed_file();
    file.frames.push(frame(
        91,
        vec![
            palette(4, 1, &[([22, 148, 210, 255], None)]),
            ExternalCel::linked(0, 0).bytes(),
        ],
    ));
    let engine = load(&file.bytes());
    assert_eq!(png_pixels(&engine, 0), png_pixels(&engine, 1));
    file.frames[1].chunks[0] = palette(4, 1, &[(GREEN, None)]);
    unchanged_rejection(&file.bytes());
}

#[test]
fn tag_order_ranges_directions_repeats_and_userdata_rgba_are_retained() {
    let mut file = basic_file();
    file.frames
        .push(frame(91, vec![ExternalCel::linked(0, 0).bytes()]));
    let entries = [
        (0, 1, 0, 65535, [1, 2, 3], "Forward"),
        (0, 1, 1, 4, [4, 5, 6], "Reverse"),
        (0, 1, 2, 0, [7, 8, 9], "Ping"),
        (1, 1, 3, 0, [10, 11, 12], "Pong"),
    ];
    let mut values = vec![tags(&entries)];
    values.extend(
        [
            [70, 80, 90, 120],
            [100, 110, 120, 255],
            [130, 140, 150, 0],
            [160, 170, 180, 255],
        ]
        .map(user_color),
    );
    file.frames[0].chunks.splice(0..0, values);
    let engine = load(&file.bytes());
    let animation = engine.document.animation.as_ref().unwrap();
    let expected_directions = [
        TagDirection::Forward,
        TagDirection::Reverse,
        TagDirection::PingPong,
        TagDirection::PingPongReverse,
    ];
    for (index, tag) in animation.tags.iter().enumerate() {
        assert_eq!(tag.name, entries[index].5);
        assert_eq!(
            tag.from_frame,
            animation.frames[entries[index].0 as usize].id
        );
        assert_eq!(tag.to_frame, animation.frames[entries[index].1 as usize].id);
        assert_eq!(tag.direction, expected_directions[index]);
        assert_eq!(tag.repeat, entries[index].3);
    }
    assert_eq!(animation.tags[0].color, [70, 80, 90, 120]);
    assert_eq!(animation.tags[2].color, [130, 140, 150, 0]);
}

#[test]
fn legacy_tag_rgb_color_is_used_when_tag_userdata_is_absent() {
    let mut file = basic_file();
    file.frames[0]
        .chunks
        .insert(0, tags(&[(0, 0, 0, 1, [71, 92, 113], "Legacy")]));
    let engine = load(&file.bytes());
    assert_eq!(
        engine.document.animation.as_ref().unwrap().tags[0].color,
        [71, 92, 113, 255]
    );
}

#[test]
fn positive_pingpong_repeats_are_rejected_instead_of_changing_playback_semantics() {
    for direction in [2, 3] {
        for repeat in [1, 2, 65535] {
            let mut file = basic_file();
            file.frames[0].chunks.insert(
                0,
                tags(&[(0, 0, direction, repeat, [1, 2, 3], "Ambiguous")]),
            );
            unchanged_rejection(&file.bytes());
        }
    }
}

#[test]
fn ordinary_none_and_srgb_profiles_are_accepted_but_gamma_and_icc_are_rejected() {
    for kind in [0u16, 1] {
        let mut payload = kind.to_le_bytes().to_vec();
        payload.extend([0; 14]);
        let mut file = basic_file();
        file.frames[0].chunks.insert(0, chunk(0x2007, &payload));
        assert_eq!(png_pixels(&load(&file.bytes()), 0), [RED, BLUE]);
        payload[2..4].copy_from_slice(&1u16.to_le_bytes());
        payload[4..8].copy_from_slice(&65536u32.to_le_bytes());
        file.frames[0].chunks[0] = chunk(0x2007, &payload);
        unchanged_rejection(&file.bytes());
    }
    let mut payload = 2u16.to_le_bytes().to_vec();
    payload.extend([0; 14]);
    payload.extend(4u32.to_le_bytes());
    payload.extend([1, 2, 3, 4]);
    let mut file = basic_file();
    file.frames[0].chunks.insert(0, chunk(0x2007, &payload));
    unchanged_rejection(&file.bytes());
}

#[test]
fn unsupported_layer_flags_and_blend_codes_are_transactional_errors() {
    for flag in [4, 8, 16, 64, 128] {
        let mut file = basic_file();
        let mut layer = ExternalLayer::image("Unsupported");
        layer.flags |= flag;
        file.frames[0].chunks[0] = layer.bytes();
        unchanged_rejection(&file.bytes());
    }
    let mut file = basic_file();
    let mut layer = ExternalLayer::image("Blend");
    layer.blend = 65535;
    file.frames[0].chunks[0] = layer.bytes();
    unchanged_rejection(&file.bytes());
    file = basic_file();
    file.flags |= 4;
    file.frames[0].chunks[0] = chunk(0x2004, &[&file.frames[0].chunks[0][6..], &[0; 16]].concat());
    unchanged_rejection(&file.bytes());
}

#[test]
fn unsupported_precise_bounds_metadata_and_extension_chunks_do_not_disappear() {
    let mut extra = 1u32.to_le_bytes().to_vec();
    extra.extend([0; 8]);
    extra.extend(65536u32.to_le_bytes());
    extra.extend(65536u32.to_le_bytes());
    extra.extend([0; 16]);
    let mut external = 1u32.to_le_bytes().to_vec();
    external.extend([0; 8]);
    external.extend(7u32.to_le_bytes());
    external.push(0);
    external.extend([0; 7]);
    string("external.gpl", &mut external);
    let mut mask = vec![0; 4];
    mask.extend(1u16.to_le_bytes());
    mask.extend(1u16.to_le_bytes());
    mask.extend([0; 8]);
    string("Selection", &mut mask);
    mask.push(0x80);
    let mut slice = 1u32.to_le_bytes().to_vec();
    slice.extend([0; 8]);
    string("Slice", &mut slice);
    slice.extend([0; 12]);
    slice.extend(1u32.to_le_bytes());
    slice.extend(1u32.to_le_bytes());
    let mut tileset = 1u32.to_le_bytes().to_vec();
    tileset.extend(2u32.to_le_bytes());
    tileset.extend(1u32.to_le_bytes());
    tileset.extend(1u16.to_le_bytes());
    tileset.extend(1u16.to_le_bytes());
    tileset.extend(1i16.to_le_bytes());
    tileset.extend([0; 14]);
    string("Tiles", &mut tileset);
    let tile_data = deflate(&RED);
    tileset.extend((tile_data.len() as u32).to_le_bytes());
    tileset.extend(tile_data);
    for (kind, payload) in [
        (0x2006, extra),
        (0x2008, external),
        (0x2016, mask),
        (0x2017, vec![]),
        (0x2022, slice),
        (0x2023, tileset),
        (0x7777, vec![1, 2, 3, 4]),
    ] {
        let mut file = basic_file();
        file.frames[0].chunks.push(chunk(kind, &payload));
        unchanged_rejection(&file.bytes());
    }
    let mut file = basic_file();
    file.frames[0].chunks.insert(1, user_color([1, 2, 3, 255]));
    unchanged_rejection(&file.bytes());
    file = basic_file();
    let mut text = 1u32.to_le_bytes().to_vec();
    string("Retain this metadata", &mut text);
    file.frames[0].chunks.splice(
        0..0,
        [
            tags(&[(0, 0, 0, 1, [1, 2, 3], "Tag")]),
            chunk(0x2020, &text),
        ],
    );
    unchanged_rejection(&file.bytes());
}

#[test]
fn grayscale_and_tilemap_images_are_explicitly_rejected() {
    let mut file = basic_file();
    file.depth = 16;
    file.frames[0].chunks[1] = ExternalCel::raw(0, 2, 1, &[42, 255, 77, 255]).bytes();
    unchanged_rejection(&file.bytes());
    file = basic_file();
    let mut tilemap = ExternalLayer::image("Tilemap");
    tilemap.kind = 2;
    let mut payload = tilemap.bytes()[6..].to_vec();
    payload.extend(0u32.to_le_bytes());
    file.frames[0].chunks[0] = chunk(0x2004, &payload);
    unchanged_rejection(&file.bytes());
    file = basic_file();
    let mut cel = ExternalCel::raw(0, 2, 1, &[]);
    cel.kind = 3;
    file.frames[0].chunks[1] = cel.bytes();
    unchanged_rejection(&file.bytes());
}

#[test]
fn cel_opacity_zindex_offcanvas_pixels_and_changed_link_positions_are_not_baked_implicitly() {
    let original = ExternalCel::raw(0, 2, 1, &[RED, BLUE].concat());
    for variant in 0..7 {
        let mut cel = original.clone();
        match variant {
            0 => cel.opacity = 254,
            1 => cel.z = 1,
            2 => cel.z = -1,
            3 => cel.x = -1,
            4 => cel.y = -1,
            5 => cel.x = 1,
            _ => cel.y = 1,
        }
        let mut file = basic_file();
        file.frames[0].chunks[1] = cel.bytes();
        unchanged_rejection(&file.bytes());
    }
    let mut file = basic_file();
    file.width = 4;
    let mut link = ExternalCel::linked(0, 0);
    link.x = 1;
    file.frames.push(frame(100, vec![link.bytes()]));
    unchanged_rejection(&file.bytes());
}

#[test]
fn forward_self_missing_cross_layer_and_duplicate_cels_are_rejected() {
    for target in [0, 1, 65535] {
        let mut file = basic_file();
        file.frames[0].chunks[1] = ExternalCel::linked(0, target).bytes();
        unchanged_rejection(&file.bytes());
    }
    let mut file = basic_file();
    file.frames[0]
        .chunks
        .insert(1, ExternalLayer::image("Other").bytes());
    file.frames
        .push(frame(100, vec![ExternalCel::linked(1, 0).bytes()]));
    unchanged_rejection(&file.bytes());
    file = basic_file();
    let duplicate = file.frames[0].chunks[1].clone();
    file.frames[0].chunks.push(duplicate);
    unchanged_rejection(&file.bytes());
}

#[test]
fn invalid_hierarchy_and_late_layer_layout_are_rejected() {
    for level in [1, 17, 65535] {
        let mut file = basic_file();
        let mut layer = ExternalLayer::image("Orphan");
        layer.depth = level;
        file.frames[0].chunks[0] = layer.bytes();
        unchanged_rejection(&file.bytes());
    }
    let mut file = basic_file();
    let mut child = ExternalLayer::image("Child of image");
    child.depth = 1;
    file.frames[0].chunks.insert(1, child.bytes());
    unchanged_rejection(&file.bytes());
    file = basic_file();
    file.frames
        .push(frame(100, vec![ExternalLayer::image("Late").bytes()]));
    unchanged_rejection(&file.bytes());
}

#[test]
fn hostile_sizes_magics_counts_and_truncations_are_atomic() {
    let original = basic_file().bytes();
    for (offset, replacement) in [
        (0, 1u32.to_le_bytes().to_vec()),
        (4, 0u16.to_le_bytes().to_vec()),
        (6, 0u16.to_le_bytes().to_vec()),
        (6, 257u16.to_le_bytes().to_vec()),
        (8, 8193u16.to_le_bytes().to_vec()),
        (12, 24u16.to_le_bytes().to_vec()),
        (128, 15u32.to_le_bytes().to_vec()),
        (132, 0u16.to_le_bytes().to_vec()),
        (134, 100u16.to_le_bytes().to_vec()),
        (140, 100u32.to_le_bytes().to_vec()),
        (144, 5u32.to_le_bytes().to_vec()),
        (144, u32::MAX.to_le_bytes().to_vec()),
    ] {
        let mut bytes = original.clone();
        bytes[offset..offset + replacement.len()].copy_from_slice(&replacement);
        if offset == 134 {
            bytes[140..144].fill(0);
        }
        unchanged_rejection(&bytes);
    }
    for length in [0, 4, 6, 32, 127, 128, 143, original.len() - 1] {
        unchanged_rejection(&original[..length]);
    }
    let mut bytes = original.clone();
    bytes.push(0);
    unchanged_rejection(&bytes);
    let mut file = basic_file();
    file.width = 8192;
    file.height = 8192;
    unchanged_rejection(&file.bytes());
}

#[test]
fn invalid_utf8_and_non_square_pixel_ratio_are_rejected() {
    let mut bytes = basic_file().bytes();
    bytes[168] = 255;
    unchanged_rejection(&bytes);
    bytes = basic_file().bytes();
    bytes[34..36].copy_from_slice(&[2, 1]);
    unchanged_rejection(&bytes);
}

#[test]
fn compressed_cel_requires_exact_zlib_payload_and_checked_decoded_size() {
    let mut cel = ExternalCel::raw(0, 2, 1, &[RED, BLUE].concat());
    cel.kind = 2;
    let valid = cel.bytes();
    for raw in [
        vec![],
        RED.to_vec(),
        [RED, BLUE, GREEN].concat(),
        vec![0; 1024 * 1024],
    ] {
        let mut file = basic_file();
        let mut bad = cel.clone();
        bad.pixels = raw;
        file.frames[0].chunks[1] = bad.bytes();
        unchanged_rejection(&file.bytes());
    }
    for mutation in 0..3 {
        let mut payload = valid[6..].to_vec();
        match mutation {
            0 => {
                payload.pop();
            }
            1 => {
                *payload.last_mut().unwrap() ^= 1;
            }
            _ => payload.extend([7, 8, 9]),
        }
        let mut file = basic_file();
        file.frames[0].chunks[1] = chunk(0x2005, &payload);
        unchanged_rejection(&file.bytes());
    }
}

#[test]
fn exported_whole_project_is_independently_parsed_and_preserves_links_and_blank_exposures() {
    let mut file = basic_file();
    file.frames
        .push(frame(127, vec![ExternalCel::linked(0, 0).bytes()]));
    file.frames.push(frame(211, vec![]));
    let mut engine = load(&file.bytes());
    engine.frame_with_background(true);
    let saved = engine.save().unwrap();
    let state = engine.state();
    let selection = engine.selection_frame();
    let bytes = export(&engine, false).unwrap();
    let parsed = parse_export(&bytes);
    assert_eq!(parsed.depth, 32);
    assert_eq!(parsed.flags & 1, 1);
    assert_eq!(
        parsed
            .frames
            .iter()
            .map(|frame| frame.duration)
            .collect::<Vec<_>>(),
        [83, 127, 211]
    );
    assert_eq!(chunks_of(&parsed.frames[0], 0x2004).len(), 1);
    let first = chunks_of(&parsed.frames[0], 0x2005);
    assert_eq!(first.len(), 1);
    assert_eq!(u16_at(first[0], 0), 0);
    assert_eq!(u16_at(first[0], 7), 2);
    assert_eq!(first[0][6], 255);
    assert_eq!(u16_at(first[0], 9), 0);
    assert_eq!(raw_export_cel(first[0]), [RED, BLUE].concat());
    let linked = chunks_of(&parsed.frames[1], 0x2005);
    assert_eq!(linked.len(), 1);
    assert_eq!(u16_at(linked[0], 7), 1);
    assert_eq!(u16_at(linked[0], 16), 0);
    assert!(chunks_of(&parsed.frames[2], 0x2005).is_empty());
    assert_eq!(engine.state(), state);
    assert!(engine.save().unwrap() == saved);
    assert!(engine.selection_frame() == selection);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

#[test]
fn indexed_export_contains_original_index_bytes_and_nonzero_header_transparency() {
    let engine = load(&indexed_file().bytes());
    let bytes = export(&engine, false).unwrap();
    let parsed = parse_export(&bytes);
    assert_eq!(parsed.depth, 8);
    assert_eq!(parsed.transparent, 2);
    let palette = chunks_of(&parsed.frames[0], 0x2019);
    assert_eq!(palette.len(), 1);
    assert_eq!(u32_at(palette[0], 0), 4);
    assert_eq!(&palette[0][22..26], &[173, 91, 43, 255]);
    assert_eq!(&palette[0][28..32], &[22, 148, 210, 255]);
    assert_eq!(
        raw_export_cel(chunks_of(&parsed.frames[0], 0x2005)[0]),
        [0, 1, 2, 3]
    );
}

#[test]
fn stale_export_request_is_readonly_and_does_not_return_a_file() {
    let mut engine = load(&basic_file().bytes());
    engine.frame_with_background(true);
    let saved = engine.save().unwrap();
    let state = engine.state();
    let request = serde_json::from_value(
        json!({"revision":state["revision"].as_u64().unwrap() + 1,"bake_layers":false}),
    )
    .unwrap();
    assert!(engine.export_aseprite(request).is_err());
    assert_eq!(engine.state(), state);
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.frame_with_background(true).len(), 16);
}

#[test]
fn six_bit_midtones_and_packet_skips_match_the_official_decoder() {
    let mut payload = 2u16.to_le_bytes().to_vec();
    payload.extend([0, 4]);
    payload.extend([16, 32, 48, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
    payload.extend([1, 1, 20, 21, 22]);
    let mut file = indexed_file();
    file.frames[0].chunks[0] = chunk(0x0011, &payload);
    let engine = load(&file.bytes());
    assert_eq!(
        engine.document.palette.as_ref().unwrap().colors[0],
        [65, 130, 195, 255]
    );
    assert_eq!(
        engine.document.palette.as_ref().unwrap().colors[1],
        [81, 85, 89, 255]
    );
    assert_eq!(
        engine.document.palette.as_ref().unwrap().colors[3],
        [28, 32, 36, 255]
    );
}

#[test]
fn old_palettes_after_the_first_modern_palette_are_ignored_throughout_the_file() {
    let mut file = indexed_file();
    let mut old = 1u16.to_le_bytes().to_vec();
    old.extend([0, 4]);
    old.extend([255, 0, 255].repeat(4));
    file.frames.push(frame(
        120,
        vec![chunk(0x0004, &old), ExternalCel::linked(0, 0).bytes()],
    ));
    let engine = load(&file.bytes());
    assert_eq!(png_pixels(&engine, 0), png_pixels(&engine, 1));
}

#[test]
fn ignored_gamma_bytes_do_not_enable_a_fixed_gamma_profile() {
    let mut payload = 1u16.to_le_bytes().to_vec();
    payload.extend([0; 2]);
    payload.extend(65536u32.to_le_bytes());
    payload.extend([0; 8]);
    let mut file = basic_file();
    file.frames[0].chunks.insert(0, chunk(0x2007, &payload));
    let engine = load(&file.bytes());
    assert!(engine.document.aseprite_metadata.as_ref().unwrap().srgb);
    assert_eq!(png_pixels(&engine, 0), [RED, BLUE]);
}

#[test]
fn isolated_group_opacity_requires_the_file_opacity_flag_as_well_as_the_group_flag() {
    for opacity in [0, 64, 255] {
        let mut group = ExternalLayer::image("Isolated");
        group.kind = 1;
        group.opacity = opacity;
        let mut child = ExternalLayer::image("Color");
        child.depth = 1;
        let mut file = ExternalFile::rgba(
            1,
            1,
            vec![frame(
                100,
                vec![
                    group.bytes(),
                    child.bytes(),
                    ExternalCel::raw(1, 1, 1, &RED).bytes(),
                ],
            )],
        );
        file.flags = 2;
        let engine = load(&file.bytes());
        assert_eq!(engine.document.layers[0].opacity, 1.0);
        assert_eq!(png_pixels(&engine, 0), [RED]);
    }
}

#[test]
fn pass_through_nonunit_metadata_commands_are_rejected_before_exchange_can_lose_pixels() {
    let mut group = ExternalLayer::image("Pass through");
    group.kind = 1;
    let mut child = ExternalLayer::image("Paint");
    child.depth = 1;
    let mut file = ExternalFile::rgba(
        1,
        1,
        vec![frame(
            100,
            vec![
                group.bytes(),
                child.bytes(),
                ExternalCel::raw(1, 1, 1, &RED).bytes(),
            ],
        )],
    );
    file.flags = 1;
    let mut engine = load(&file.bytes());
    let before = engine.save().unwrap();
    let state = engine.state();
    assert!(engine
        .command(Command::SetLayer {
            id: 1,
            name: "Pass through".into(),
            visible: true,
            opacity: 0.0
        })
        .is_err());
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(png_pixels(&engine, 0), [RED]);
    assert!(engine
        .command(Command::SetBlend {
            id: 1,
            mode: BlendMode::Multiply
        })
        .is_err());
    assert_eq!(engine.state(), state);
    assert_eq!(engine.save().unwrap(), before);
    assert_eq!(
        png_pixels(&load(&export(&engine, false).unwrap()), 0),
        [RED]
    );
}

#[test]
fn valid_large_zlib_images_cross_multiple_decode_output_blocks_exactly() {
    let pixels: Vec<u8> = (0..256 * 256)
        .flat_map(|index| {
            [
                index as u8,
                (index >> 8) as u8,
                ((index * 31) % 251) as u8,
                255,
            ]
        })
        .collect();
    let mut cel = ExternalCel::raw(0, 256, 256, &pixels);
    cel.kind = 2;
    let file = ExternalFile::rgba(
        256,
        256,
        vec![frame(
            100,
            vec![ExternalLayer::image("Pattern").bytes(), cel.bytes()],
        )],
    );
    let engine = load(&file.bytes());
    let decoded: Vec<u8> = png_pixels(&engine, 0).into_iter().flatten().collect();
    assert_eq!(decoded, pixels);
    assert_eq!(
        png_pixels(&load(&export(&engine, false).unwrap()), 0),
        png_pixels(&engine, 0)
    );
}

#[test]
fn reordered_tag_anchors_export_the_current_span_without_reversing_direction() {
    let mut file = basic_file();
    file.frames[0]
        .chunks
        .push(tags(&[(0, 1, 0, 3, [8, 9, 10], "span")]));
    file.frames
        .push(frame(120, vec![ExternalCel::linked(0, 0).bytes()]));
    let mut engine = load(&file.bytes());
    engine
        .command(Command::ReorderFrames {
            revision: engine.state()["revision"].as_u64().unwrap(),
            ids: vec![2, 1],
        })
        .unwrap();
    let parsed = parse_export(&export(&engine, false).unwrap());
    let payload = chunks_of(&parsed.frames[0], 0x2018)[0];
    assert_eq!(u16_at(payload, 10), 0);
    assert_eq!(u16_at(payload, 12), 1);
    assert_eq!(payload[14], 0);
    let restored = load(&export(&engine, false).unwrap());
    assert_eq!(
        restored.document.animation.as_ref().unwrap().tags[0].repeat,
        3
    );
}

#[test]
fn old_chunk_count_with_zero_new_field_loads_the_complete_frame() {
    let mut bytes = basic_file().bytes();
    bytes[140..144].fill(0);
    assert_eq!(png_pixels(&load(&bytes), 0), [RED, BLUE]);
}

#[test]
fn rgba_import_uses_documented_premultiplied_canonical_pixels_and_export_keeps_that_precision() {
    let mut file = basic_file();
    file.frames[0].chunks[1] =
        ExternalCel::raw(0, 2, 1, &[[65, 31, 15, 128], [90, 80, 70, 0]].concat()).bytes();
    let engine = load(&file.bytes());
    assert_eq!(png_pixels(&engine, 0), [[66, 32, 16, 128], CLEAR]);
    let animation = engine.document.animation.as_ref().unwrap();
    let source = match &animation.cels.values().next().unwrap().source {
        CelSource::Raster(source) => source,
        _ => panic!("RGBA source became vector"),
    };
    assert_eq!(
        &source.tiles().get(&(0, 0)).unwrap()[..8],
        &[33, 16, 8, 128, 0, 0, 0, 0]
    );
    let parsed = parse_export(&export(&engine, false).unwrap());
    assert_eq!(
        raw_export_cel(chunks_of(&parsed.frames[0], 0x2005)[0]),
        [66, 32, 16, 128]
    );
}
