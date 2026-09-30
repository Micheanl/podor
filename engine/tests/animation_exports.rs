use image::{codecs::gif::GifDecoder, AnimationDecoder};
use podor_engine::{
    animation::{AnimationSet, Cel, CelKind, CelSource, Frame},
    model::*,
    vector::{FillRule, Geometry, Style, VectorLayer, VectorObject},
    Command, Engine, ExportFormat, ExportOptions,
};
use serde_json::{json, Value};
use std::{collections::BTreeMap, io::Cursor, io::Read, sync::Arc};

type Pixels = Vec<[u8; 4]>;

const RED: [u8; 4] = [255, 0, 0, 255];
const GREEN: [u8; 4] = [0, 255, 0, 255];
const BLUE: [u8; 4] = [0, 0, 255, 255];
const CLEAR: [u8; 4] = [0; 4];

fn send(engine: &mut Engine, mut value: Value) -> Value {
    let state = engine.state();
    value["revision"] = state["revision"].clone();
    if !state["animation"].is_null()
        && !matches!(
            value["type"].as_str().unwrap(),
            "add_frame"
                | "duplicate_frame"
                | "delete_frame"
                | "reorder_frames"
                | "set_frame_duration"
                | "select_frame"
                | "add_frame_tag"
        )
    {
        value["frame_id"] = state["animation"]["activeFrameId"].clone();
        value["cel_id"] = state["animation"]["activeCelId"].clone();
        value["target_layer_id"] = state["active"].clone();
    }
    engine
        .command_request(serde_json::from_value(value).unwrap())
        .unwrap()
}

fn raster(width: u32, height: u32, pixels: &[[u8; 4]]) -> RasterPlane {
    assert_eq!(pixels.len(), (width * height) as usize);
    let mut tiles = BTreeMap::<TileKey, Tile>::new();
    for (index, &pixel) in pixels.iter().enumerate() {
        if pixel[3] == 0 {
            continue;
        }
        let x = index as u32 % width;
        let y = index as u32 / width;
        let tile = tiles
            .entry((x / TILE_SIZE, y / TILE_SIZE))
            .or_insert_with(|| Arc::new(vec![0; TILE_BYTES]));
        let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
        Arc::make_mut(tile)[offset..offset + 4].copy_from_slice(&pixel);
    }
    RasterPlane::Rgba(tiles)
}

fn animated(width: u32, height: u32, frames: &[(u32, Pixels)]) -> Engine {
    let mut engine = Engine::new(width, height).unwrap();
    engine.document.layers[0].content = LayerContent::CelTrack {
        kind: CelKind::Raster,
    };
    let mut cels = BTreeMap::new();
    let frames: Vec<_> = frames
        .iter()
        .enumerate()
        .map(|(index, (duration_ms, pixels))| {
            let id = index as u32 + 1;
            let exposures = if pixels.iter().any(|pixel| pixel[3] != 0) {
                cels.insert(
                    id,
                    Arc::new(Cel {
                        id,
                        layer_id: 1,
                        source: CelSource::Raster(Arc::new(raster(width, height, pixels))),
                        masks: vec![],
                    }),
                );
                BTreeMap::from([(1, id)])
            } else {
                BTreeMap::new()
            };
            Frame {
                id,
                duration_ms: *duration_ms,
                exposures,
            }
        })
        .collect();
    let last = frames.last().unwrap().id;
    engine.document.animation = Some(Arc::new(AnimationSet {
        frames,
        cels,
        active_frame: last,
        next_frame_id: last + 1,
        next_cel_id: last + 1,
        next_tag_id: 1,
        tags: vec![],
    }));
    engine.load(&engine.save().unwrap()).unwrap();
    engine
}

fn sprites() -> Engine {
    animated(
        2,
        1,
        &[
            (20, vec![RED, BLUE]),
            (70, vec![CLEAR, CLEAR]),
            (130, vec![CLEAR, RED]),
        ],
    )
}

fn all(direction: &str, repeat: u16) -> Value {
    json!({"kind":"all","direction":direction,"repeat":repeat})
}

fn request(engine: &Engine, format: &str, scope: Value) -> Value {
    json!({"revision":engine.state()["revision"],"format":format,"scope":scope})
}

fn export(engine: &Engine, value: Value) -> Result<Vec<u8>, String> {
    let request = serde_json::from_value(value).map_err(|error| error.to_string())?;
    engine.export_animation(request)
}

fn readonly_export(engine: &mut Engine, value: Value, rejected: bool) -> Vec<u8> {
    engine.frame_with_background(true);
    let saved = engine.save().unwrap();
    let state = engine.state();
    let selection = engine.selection_frame();
    let result = export(engine, value);
    assert_eq!(
        result.is_err(),
        rejected,
        "unexpected export error: {:?}",
        result.as_ref().err()
    );
    assert!(
        engine.save().unwrap() == saved,
        "export changed saved bytes"
    );
    assert_eq!(engine.state(), state);
    assert!(engine.selection_frame() == selection);
    assert_eq!(engine.frame_with_background(true).len(), 16);
    result.unwrap_or_default()
}

fn png(bytes: &[u8]) -> (u32, u32, Pixels) {
    let mut reader = png::Decoder::new(bytes).read_info().unwrap();
    let mut rgba = vec![0; reader.output_buffer_size()];
    let frame = reader.next_frame(&mut rgba).unwrap();
    assert_eq!(frame.color_type, png::ColorType::Rgba);
    assert_eq!(frame.bit_depth, png::BitDepth::Eight);
    rgba.truncate(frame.buffer_size());
    (frame.width, frame.height, rgba.as_chunks::<4>().0.to_vec())
}

fn actual_pixels(engine: &Engine, id: u32) -> Pixels {
    let bytes = engine
        .export_image(ExportOptions {
            format: ExportFormat::Png,
            transparent: true,
            frame_id: Some(id),
            ..ExportOptions::default()
        })
        .unwrap();
    let (width, height, pixels) = png(&bytes);
    assert_eq!(
        (width, height),
        (engine.document.width, engine.document.height)
    );
    let request = serde_json::from_value(
        json!({"revision":engine.state()["revision"],"frame_id":id,"transparent":true}),
    )
    .unwrap();
    let packet = engine.render_animation_frame(request).unwrap();
    let mut raw = vec![CLEAR; (width * height) as usize];
    for record in packet[16..].as_chunks::<{ 8 + TILE_BYTES }>().0 {
        let tx = u32::from_le_bytes(record[..4].try_into().unwrap());
        let ty = u32::from_le_bytes(record[4..8].try_into().unwrap());
        for y in 0..TILE_SIZE.min(height - ty * TILE_SIZE) {
            for x in 0..TILE_SIZE.min(width - tx * TILE_SIZE) {
                let source = ((y * TILE_SIZE + x) * 4) as usize + 8;
                let mut pixel: [u8; 4] = record[source..source + 4].try_into().unwrap();
                let alpha = u32::from(pixel[3]);
                for channel in &mut pixel[..3] {
                    *channel = (u32::from(*channel) * 255 + alpha / 2)
                        .checked_div(alpha)
                        .unwrap_or(0)
                        .min(255) as u8;
                }
                let target = ((ty * TILE_SIZE + y) * width + tx * TILE_SIZE + x) as usize;
                raw[target] = pixel;
            }
        }
    }
    assert!(pixels == raw, "PNG and actual frame composite differ");
    pixels
}

#[derive(Debug, PartialEq, Eq)]
struct GifImage {
    delay: u16,
    disposal: u8,
    transparency: Option<u8>,
    rect: [u16; 4],
    palette: Vec<[u8; 3]>,
}

#[derive(Debug)]
struct GifBlocks {
    width: u16,
    height: u16,
    background: u8,
    global_palette: Vec<[u8; 3]>,
    loops: Option<u16>,
    images: Vec<GifImage>,
}

fn take<'a>(bytes: &'a [u8], position: &mut usize, count: usize) -> &'a [u8] {
    let result = &bytes[*position..*position + count];
    *position += count;
    result
}

fn blocks(bytes: &[u8], position: &mut usize) -> Vec<u8> {
    let mut result = vec![];
    loop {
        let count = take(bytes, position, 1)[0] as usize;
        if count == 0 {
            return result;
        }
        result.extend_from_slice(take(bytes, position, count));
    }
}

fn palette(bytes: &[u8], position: &mut usize, flags: u8) -> Vec<[u8; 3]> {
    if flags & 0x80 == 0 {
        return vec![];
    }
    let colors = 1usize << ((flags & 7) + 1);
    take(bytes, position, colors * 3)
        .as_chunks::<3>()
        .0
        .to_vec()
}

fn gif_blocks(bytes: &[u8]) -> GifBlocks {
    assert_eq!(&bytes[..6], b"GIF89a");
    let mut position = 6;
    let header = take(bytes, &mut position, 7);
    let width = u16::from_le_bytes(header[..2].try_into().unwrap());
    let height = u16::from_le_bytes(header[2..4].try_into().unwrap());
    let background = header[5];
    let global_palette = palette(bytes, &mut position, header[4]);
    let mut loops = None;
    let mut images = vec![];
    let mut control = None;
    loop {
        match take(bytes, &mut position, 1)[0] {
            0x21 => match take(bytes, &mut position, 1)[0] {
                0xf9 => {
                    assert!(control.is_none());
                    assert_eq!(take(bytes, &mut position, 1), [4]);
                    let fields = take(bytes, &mut position, 4);
                    assert_eq!(fields[0] & 0xe2, 0);
                    control = Some((
                        u16::from_le_bytes(fields[1..3].try_into().unwrap()),
                        (fields[0] >> 2) & 7,
                        (fields[0] & 1 != 0).then_some(fields[3]),
                    ));
                    assert_eq!(take(bytes, &mut position, 1), [0]);
                }
                0xff => {
                    let count = take(bytes, &mut position, 1)[0] as usize;
                    let application = take(bytes, &mut position, count);
                    let fields = blocks(bytes, &mut position);
                    if application == b"NETSCAPE2.0" {
                        assert!(loops.is_none());
                        assert_eq!(fields.len(), 3);
                        assert_eq!(fields[0], 1);
                        loops = Some(u16::from_le_bytes(fields[1..].try_into().unwrap()));
                    }
                }
                _ => {
                    blocks(bytes, &mut position);
                }
            },
            0x2c => {
                let descriptor = take(bytes, &mut position, 9);
                let rect = std::array::from_fn(|index| {
                    u16::from_le_bytes(descriptor[index * 2..index * 2 + 2].try_into().unwrap())
                });
                let local = palette(bytes, &mut position, descriptor[8]);
                let selected = if local.is_empty() {
                    global_palette.clone()
                } else {
                    local
                };
                let (delay, disposal, transparency) = control.take().unwrap();
                assert!((2..=8).contains(&take(bytes, &mut position, 1)[0]));
                blocks(bytes, &mut position);
                images.push(GifImage {
                    delay,
                    disposal,
                    transparency,
                    rect,
                    palette: selected,
                });
            }
            0x3b => break,
            other => panic!("unexpected GIF block {other:#x}"),
        }
    }
    assert_eq!(position, bytes.len());
    assert!(control.is_none());
    GifBlocks {
        width,
        height,
        background,
        global_palette,
        loops,
        images,
    }
}

fn decoded_gif(bytes: &[u8]) -> Vec<(Pixels, u32)> {
    GifDecoder::new(Cursor::new(bytes))
        .unwrap()
        .into_frames()
        .collect_frames()
        .unwrap()
        .into_iter()
        .map(|frame| {
            let (numerator, denominator) = frame.delay().numer_denom_ms();
            assert_eq!(denominator, 1);
            (
                frame.buffer().as_raw().as_chunks::<4>().0.to_vec(),
                numerator,
            )
        })
        .collect()
}

fn matches_sequence(engine: &Engine, bytes: &[u8], sequence: &[u32], delays: &[u32]) {
    let decoded = decoded_gif(bytes);
    assert_eq!(decoded.len(), sequence.len());
    for ((pixels, duration), (&id, &expected)) in decoded.iter().zip(sequence.iter().zip(delays)) {
        assert!(
            *pixels == actual_pixels(engine, id),
            "GIF pixel mismatch for frame {id}"
        );
        assert_eq!(*duration, expected);
    }
    let expected: Vec<_> = sequence
        .iter()
        .zip(delays)
        .map(|(&id, &duration)| (actual_pixels(engine, id), duration))
        .collect();
    emit_gif_fixture(
        bytes,
        engine.document.width,
        engine.document.height,
        &expected,
    );
}

fn emit_gif_fixture(bytes: &[u8], width: u32, height: u32, frames: &[(Pixels, u32)]) {
    let Some(directory) = std::env::var_os("PODOR_GIF_FIXTURE_DIR") else {
        return;
    };
    static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let id = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let directory = std::path::PathBuf::from(directory);
    std::fs::create_dir_all(&directory).unwrap();
    let expected = json!({
        "width":width,"height":height,
        "frames":frames.iter().map(|(pixels, duration)| {
            json!({"durationMs":duration,"rgba":pixels.iter().flatten().copied().collect::<Vec<_>>()})
        }).collect::<Vec<_>>()
    });
    std::fs::write(directory.join(format!("fixture-{id:04}.gif")), bytes).unwrap();
    std::fs::write(
        directory.join(format!("fixture-{id:04}.json")),
        serde_json::to_vec(&expected).unwrap(),
    )
    .unwrap();
}

fn atlas(bytes: &[u8]) -> (Value, u32, u32, Pixels) {
    let mut archive = zip::ZipArchive::new(Cursor::new(bytes)).unwrap();
    assert_eq!(archive.len(), 2);
    let mut names: Vec<_> = archive.file_names().map(str::to_owned).collect();
    names.sort();
    assert_eq!(names, ["atlas.json", "atlas.png"]);
    let mut image = vec![];
    {
        let mut entry = archive.by_name("atlas.png").unwrap();
        assert_eq!(entry.compression(), zip::CompressionMethod::Stored);
        entry.read_to_end(&mut image).unwrap();
    }
    let mut metadata = vec![];
    archive
        .by_name("atlas.json")
        .unwrap()
        .read_to_end(&mut metadata)
        .unwrap();
    let metadata: Value = serde_json::from_slice(&metadata).unwrap();
    let (width, height, pixels) = png(&image);
    assert_eq!(metadata["format"], "podor_atlas");
    assert_eq!(metadata["version"], 1);
    assert_eq!(metadata["image"], "atlas.png");
    assert_eq!(metadata["width"], width);
    assert_eq!(metadata["height"], height);
    (metadata, width, height, pixels)
}

fn atlas_matches(engine: &Engine, metadata: &Value, width: u32, height: u32, pixels: &Pixels) {
    let source_width = engine.document.width;
    let source_height = engine.document.height;
    assert_eq!(metadata["sourceWidth"], source_width);
    assert_eq!(metadata["sourceHeight"], source_height);
    let mut expected = vec![CLEAR; (width * height) as usize];
    for frame in metadata["frames"].as_array().unwrap() {
        let id = frame["id"].as_u64().unwrap() as u32;
        let rect = &frame["rect"];
        assert_eq!(rect["width"], source_width);
        assert_eq!(rect["height"], source_height);
        let x = rect["x"].as_u64().unwrap() as u32;
        let y = rect["y"].as_u64().unwrap() as u32;
        assert!(x + source_width <= width && y + source_height <= height);
        let source = actual_pixels(engine, id);
        for row in 0..source_height {
            let to = ((y + row) * width + x) as usize;
            let from = (row * source_width) as usize;
            expected[to..to + source_width as usize]
                .copy_from_slice(&source[from..from + source_width as usize]);
        }
    }
    assert!(
        *pixels == expected,
        "atlas contents, padding or unused cells differ"
    );
}

#[test]
fn transparent_gif_clears_opaque_previous_frames_and_preserves_moving_sprites() {
    let mut engine = sprites();
    let value = request(&engine, "gif", all("forward", 1));
    let bytes = readonly_export(&mut engine, value, false);
    let parsed = gif_blocks(&bytes);
    assert_eq!((parsed.width, parsed.height, parsed.background), (2, 1, 0));
    assert!(!parsed.global_palette.is_empty());
    assert_eq!(parsed.loops, None);
    assert_eq!(parsed.images.len(), 3);
    for (frame, delay) in parsed.images.iter().zip([2, 7, 13]) {
        assert_eq!(frame.delay, delay);
        assert_eq!(frame.disposal, 2);
        assert_eq!(frame.transparency, Some(0));
        assert_eq!(frame.rect, [0, 0, 2, 1]);
    }
    matches_sequence(&engine, &bytes, &[1, 2, 3], &[20, 70, 130]);
    assert_eq!(decoded_gif(&bytes)[0].0, [RED, BLUE]);
    assert_eq!(decoded_gif(&bytes)[1].0, [CLEAR, CLEAR]);
    assert_eq!(decoded_gif(&bytes)[2].0, [CLEAR, RED]);
}

#[test]
fn transparent_gif_clears_intermediate_opaque_frames_and_final_to_first_loop_boundary() {
    let mut engine = animated(
        2,
        1,
        &[
            (20, vec![CLEAR, CLEAR]),
            (70, vec![RED, BLUE]),
            (130, vec![CLEAR, CLEAR]),
            (200, vec![RED, CLEAR]),
        ],
    );
    let value = request(&engine, "gif", all("ping_pong", 0));
    let bytes = readonly_export(&mut engine, value, false);
    let parsed = gif_blocks(&bytes);
    assert_eq!(parsed.loops, Some(0));
    assert_eq!(parsed.images.len(), 6);
    assert!(parsed
        .images
        .iter()
        .all(|image| image.disposal == 2 && image.transparency == Some(0)));
    matches_sequence(
        &engine,
        &bytes,
        &[1, 2, 3, 4, 3, 2],
        &[20, 70, 130, 200, 130, 70],
    );
    let flags = bytes[10];
    let body = 13
        + if flags & 0x80 == 0 {
            0
        } else {
            3 * (1usize << ((flags & 7) + 1))
        };
    let mut two_cycles = bytes[..bytes.len() - 1].to_vec();
    two_cycles.extend_from_slice(&bytes[body..]);
    matches_sequence(
        &engine,
        &two_cycles,
        &[1, 2, 3, 4, 3, 2, 1, 2, 3, 4, 3, 2],
        &[20, 70, 130, 200, 130, 70, 20, 70, 130, 200, 130, 70],
    );
}

#[test]
fn gif_directions_and_total_cycle_counts_are_visible_in_independent_binary_blocks() {
    let mut engine = sprites();
    for (direction, sequence, delays) in [
        ("forward", vec![1, 2, 3], vec![20, 70, 130]),
        ("reverse", vec![3, 2, 1], vec![130, 70, 20]),
        ("ping_pong", vec![1, 2, 3, 2], vec![20, 70, 130, 70]),
        ("ping_pong_reverse", vec![3, 2, 1, 2], vec![130, 70, 20, 70]),
    ] {
        for (repeat, loops) in [
            (0, Some(0)),
            (1, None),
            (4, Some(3)),
            (u16::MAX, Some(65534)),
        ] {
            let value = request(&engine, "gif", all(direction, repeat));
            let bytes = readonly_export(&mut engine, value, false);
            let parsed = gif_blocks(&bytes);
            assert_eq!(parsed.loops, loops);
            assert_eq!(parsed.images.len(), sequence.len());
            matches_sequence(&engine, &bytes, &sequence, &delays);
        }
    }
}

#[test]
fn single_and_two_frame_ping_pong_do_not_duplicate_the_turn_endpoints() {
    for frames in [
        vec![(20, vec![RED])],
        vec![(20, vec![RED]), (70, vec![BLUE])],
    ] {
        let mut engine = animated(1, 1, &frames);
        for (direction, reverse) in [("ping_pong", false), ("ping_pong_reverse", true)] {
            let mut sequence: Vec<_> = (1..=frames.len() as u32).collect();
            let mut delays: Vec<_> = frames.iter().map(|(duration, _)| *duration).collect();
            if reverse {
                sequence.reverse();
                delays.reverse();
            }
            let value = request(&engine, "gif", all(direction, 1));
            let bytes = readonly_export(&mut engine, value, false);
            assert_eq!(gif_blocks(&bytes).images.len(), frames.len());
            matches_sequence(&engine, &bytes, &sequence, &delays);
        }
    }
}

#[test]
fn explicit_gif_rounding_has_ten_millisecond_ticks_without_changing_source_durations() {
    let durations = [1, 9, 15, 33, 60000];
    let frames: Vec<_> = durations
        .iter()
        .map(|duration| (*duration, vec![RED]))
        .collect();
    let mut engine = animated(1, 1, &frames);
    let exact = request(&engine, "gif", all("forward", 1));
    readonly_export(&mut engine, exact, true);
    let mut rounded = request(&engine, "gif", all("forward", 1));
    rounded["timing"] = json!("round");
    let bytes = readonly_export(&mut engine, rounded, false);
    assert_eq!(
        gif_blocks(&bytes)
            .images
            .iter()
            .map(|image| image.delay)
            .collect::<Vec<_>>(),
        [1, 1, 2, 3, 6000]
    );
    matches_sequence(&engine, &bytes, &[1, 2, 3, 4, 5], &[10, 10, 20, 30, 60000]);
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
        durations
    );
}

fn distinct_colors(count: usize) -> Pixels {
    (0..count)
        .map(|index| [index as u8, (index >> 8) as u8, 111, 255])
        .collect()
}

#[test]
fn opaque_exact_gif_keeps_all_256_colors_and_rejects_257() {
    for (count, rejected) in [(256, false), (257, true)] {
        let original = distinct_colors(count);
        let mut engine = animated(count as u32, 1, &[(20, original.clone())]);
        let value = request(&engine, "gif", all("forward", 1));
        let bytes = readonly_export(&mut engine, value, rejected);
        if !rejected {
            let parsed = gif_blocks(&bytes);
            assert_eq!(parsed.images[0].palette.len(), 256);
            assert_eq!(parsed.images[0].disposal, 1);
            assert_eq!(parsed.images[0].transparency, None);
            assert!(decoded_gif(&bytes)[0].0 == original);
            matches_sequence(&engine, &bytes, &[1], &[20]);
        }
    }
}

#[test]
fn exact_transparent_sequences_reserve_one_color_even_for_opaque_first_frames() {
    let mut colors = distinct_colors(255);
    colors.push(colors[0]);
    let mut engine = animated(256, 1, &[(20, colors.clone()), (70, vec![CLEAR; 256])]);
    let value = request(&engine, "gif", all("forward", 1));
    let bytes = readonly_export(&mut engine, value, false);
    for image in gif_blocks(&bytes).images {
        assert_eq!(image.disposal, 2);
        assert_eq!(image.transparency, Some(0));
    }
    assert!(decoded_gif(&bytes)[0].0 == colors);
    matches_sequence(&engine, &bytes, &[1, 2], &[20, 70]);
    let mut engine = animated(
        256,
        1,
        &[(20, distinct_colors(256)), (70, vec![CLEAR; 256])],
    );
    let exact = request(&engine, "gif", all("forward", 1));
    readonly_export(&mut engine, exact, true);
    let mut quantized = request(&engine, "gif", all("forward", 1));
    quantized["color_policy"] = json!("quantize");
    let bytes = readonly_export(&mut engine, quantized, false);
    let images = decoded_gif(&bytes);
    assert!(images[0].0.iter().all(|pixel| pixel[3] == 255));
    assert!(images[1].0.iter().all(|pixel| *pixel == CLEAR));
    assert_eq!(gif_blocks(&bytes).images.len(), 2);
}

#[test]
fn partial_alpha_requires_explicit_matte_or_threshold_and_uses_premultiplied_channels() {
    let mut engine = animated(2, 1, &[(20, vec![[64, 32, 16, 128], CLEAR])]);
    let exact = request(&engine, "gif", all("forward", 1));
    readonly_export(&mut engine, exact, true);
    let mut matte = request(&engine, "gif", all("forward", 1));
    matte["alpha"] = json!({"kind":"matte","color":[20,40,60]});
    let bytes = readonly_export(&mut engine, matte, false);
    assert_eq!(
        decoded_gif(&bytes)[0].0,
        [[74, 52, 46, 255], [20, 40, 60, 255]]
    );
    assert_eq!(gif_blocks(&bytes).images[0].transparency, None);
    let mut engine = animated(2, 1, &[(20, vec![[63, 31, 15, 127], [64, 32, 16, 128]])]);
    emit_gif_fixture(
        &bytes,
        2,
        1,
        &[(vec![[74, 52, 46, 255], [20, 40, 60, 255]], 20)],
    );
    let mut threshold = request(&engine, "gif", all("forward", 1));
    threshold["alpha"] = json!({"kind":"threshold","cutoff":128});
    let bytes = readonly_export(&mut engine, threshold, false);
    assert_eq!(decoded_gif(&bytes)[0].0, [CLEAR, [128, 64, 32, 255]]);
    assert_eq!(gif_blocks(&bytes).images[0].transparency, Some(0));
    assert_eq!(gif_blocks(&bytes).images[0].disposal, 2);
    emit_gif_fixture(&bytes, 2, 1, &[(vec![CLEAR, [128, 64, 32, 255]], 20)]);
}

#[test]
fn quantization_is_deterministic_and_all_transparent_frames_remain_real_frames() {
    let colors: Pixels = (0..300)
        .map(|index| {
            [
                (index % 256) as u8,
                (index / 256) as u8,
                (index * 29 % 256) as u8,
                255,
            ]
        })
        .collect();
    let mut sparse = vec![CLEAR; 300];
    sparse[299] = [0, 255, 255, 255];
    let mut engine = animated(
        300,
        1,
        &[(20, colors), (70, sparse), (130, vec![CLEAR; 300])],
    );
    let mut value = request(&engine, "gif", all("ping_pong", 0));
    value["color_policy"] = json!("quantize");
    let first = readonly_export(&mut engine, value.clone(), false);
    let second = readonly_export(&mut engine, value, false);
    assert!(
        first == second,
        "same snapshot quantization must be byte deterministic"
    );
    let frames = decoded_gif(&first);
    assert_eq!(frames.len(), 4);
    assert!(frames[0].0.iter().all(|pixel| pixel[3] == 255));
    assert!(frames[1].0[..299].iter().all(|pixel| *pixel == CLEAR));
    assert_eq!(frames[1].0[299][3], 255);
    assert!(frames[2].0.iter().all(|pixel| *pixel == CLEAR));
    assert!(frames[1].0 == frames[3].0);
    assert_eq!(
        frames.iter().map(|(_, ms)| *ms).collect::<Vec<_>>(),
        [20, 70, 130, 70]
    );
    emit_gif_fixture(
        &first,
        engine.document.width,
        engine.document.height,
        &frames,
    );
    let mut small = animated(
        2,
        1,
        &[
            (20, vec![[173, 91, 43, 255], CLEAR]),
            (70, vec![CLEAR, [22, 148, 210, 255]]),
        ],
    );
    let mut value = request(&small, "gif", all("forward", 1));
    value["color_policy"] = json!("quantize");
    let bytes = readonly_export(&mut small, value, false);
    matches_sequence(&small, &bytes, &[1, 2], &[20, 70]);
    for policy in ["exact", "quantize"] {
        let mut empty = animated(2, 1, &[(20, vec![CLEAR; 2]), (70, vec![CLEAR; 2])]);
        let mut value = request(&empty, "gif", all("forward", 1));
        value["color_policy"] = json!(policy);
        let bytes = readonly_export(&mut empty, value, false);
        matches_sequence(&empty, &bytes, &[1, 2], &[20, 70]);
        assert_eq!(gif_blocks(&bytes).images.len(), 2);
    }
}

#[test]
fn atlas_png_cells_padding_unused_cells_and_partial_alpha_match_real_frame_pixels() {
    let mut engine = animated(
        3,
        2,
        &[
            (17, vec![[64, 32, 16, 128], RED, CLEAR, CLEAR, BLUE, CLEAR]),
            (33, vec![CLEAR; 6]),
            (60000, vec![GREEN; 6]),
        ],
    );
    let mut value = request(&engine, "atlas", all("ping_pong_reverse", 4));
    value["columns"] = json!(2);
    value["padding"] = json!(1);
    let bytes = readonly_export(&mut engine, value.clone(), false);
    assert!(readonly_export(&mut engine, value, false) == bytes);
    let (metadata, width, height, pixels) = atlas(&bytes);
    assert_eq!((width, height), (10, 8));
    assert_eq!(metadata["columns"], 2);
    assert_eq!(metadata["padding"], 1);
    assert_eq!(metadata["tag"], Value::Null);
    assert_eq!(
        metadata["playback"],
        json!({"frameIds":[3,2,1,2],"direction":"ping_pong_reverse","repeat":4})
    );
    assert_eq!(
        metadata["frames"],
        json!([
            {"id":1,"durationMs":17,"rect":{"x":1,"y":1,"width":3,"height":2}},
            {"id":2,"durationMs":33,"rect":{"x":6,"y":1,"width":3,"height":2}},
            {"id":3,"durationMs":60000,"rect":{"x":1,"y":5,"width":3,"height":2}}
        ])
    );
    assert_eq!(pixels[11], [128, 64, 32, 128]);
    atlas_matches(&engine, &metadata, width, height, &pixels);
}

#[test]
fn atlas_keeps_linked_frame_identities_and_default_grid_has_no_implicit_resize() {
    let mut engine = animated(2, 1, &[(20, vec![RED, BLUE])]);
    send(
        &mut engine,
        json!({"type":"duplicate_frame","frame_id":1,"index":1,"linked":true,"select":true}),
    );
    send(
        &mut engine,
        json!({"type":"set_frame_duration","frame_id":2,"duration_ms":130}),
    );
    let animation = engine.document.animation.as_ref().unwrap();
    assert_eq!(animation.frames[0].exposures, animation.frames[1].exposures);
    let value = request(&engine, "atlas", all("reverse", 1));
    let bytes = readonly_export(&mut engine, value, false);
    let (metadata, width, height, pixels) = atlas(&bytes);
    assert_eq!(metadata["frames"].as_array().unwrap().len(), 2);
    assert_eq!(metadata["frames"][0]["id"], 1);
    assert_eq!(metadata["frames"][1]["id"], 2);
    assert_eq!(metadata["frames"][0]["durationMs"], 20);
    assert_eq!(metadata["frames"][1]["durationMs"], 130);
    assert_eq!(metadata["padding"], 0);
    assert_eq!(metadata["playback"]["frameIds"], json!([2, 1]));
    atlas_matches(&engine, &metadata, width, height, &pixels);
    let gif = request(&engine, "gif", all("forward", 1));
    let bytes = readonly_export(&mut engine, gif, false);
    matches_sequence(&engine, &bytes, &[1, 2], &[20, 130]);
}

#[test]
fn atlas_auto_grid_finds_legal_columns_for_asymmetric_sources_and_explicit_grid_rejects() {
    for (width, height, legal_columns, invalid_columns) in [(8192, 1, 1, 2), (1, 8192, 2, 1)] {
        let mut first = vec![CLEAR; (width * height) as usize];
        first[0] = RED;
        let mut last = vec![CLEAR; (width * height) as usize];
        last[(width * height - 1) as usize] = BLUE;
        let mut engine = animated(width, height, &[(20, first), (70, last)]);
        let value = request(&engine, "atlas", all("forward", 1));
        let bytes = readonly_export(&mut engine, value, false);
        let (metadata, atlas_width, atlas_height, pixels) = atlas(&bytes);
        assert_eq!(metadata["columns"], legal_columns);
        assert!(atlas_width <= 8192 && atlas_height <= 8192);
        atlas_matches(&engine, &metadata, atlas_width, atlas_height, &pixels);
        let mut invalid = request(&engine, "atlas", all("forward", 1));
        invalid["columns"] = json!(invalid_columns);
        readonly_export(&mut engine, invalid, true);
    }
}

#[test]
fn range_uses_current_timeline_positions_and_normalizes_stable_id_endpoints() {
    let mut engine = animated(
        1,
        1,
        &[(20, vec![RED]), (70, vec![GREEN]), (130, vec![BLUE])],
    );
    send(&mut engine, json!({"type":"reorder_frames","ids":[3,1,2]}));
    let scope =
        json!({"kind":"range","from_frame":2,"to_frame":3,"direction":"reverse","repeat":4});
    let value = request(&engine, "gif", scope.clone());
    let bytes = readonly_export(&mut engine, value, false);
    assert_eq!(gif_blocks(&bytes).loops, Some(3));
    matches_sequence(&engine, &bytes, &[2, 1, 3], &[70, 20, 130]);
    let mut value = request(&engine, "atlas", scope);
    value["columns"] = json!(3);
    let bytes = readonly_export(&mut engine, value, false);
    let (metadata, width, height, pixels) = atlas(&bytes);
    assert_eq!(
        metadata["frames"]
            .as_array()
            .unwrap()
            .iter()
            .map(|frame| frame["id"].as_u64().unwrap())
            .collect::<Vec<_>>(),
        [3, 1, 2]
    );
    assert_eq!(metadata["playback"]["frameIds"], json!([2, 1, 3]));
    atlas_matches(&engine, &metadata, width, height, &pixels);
    let scope =
        json!({"kind":"range","from_frame":2,"to_frame":1,"direction":"ping_pong","repeat":1});
    let value = request(&engine, "gif", scope);
    let bytes = readonly_export(&mut engine, value, false);
    matches_sequence(&engine, &bytes, &[1, 2], &[20, 70]);
}

#[test]
fn tag_export_uses_stored_direction_repeat_metadata_and_updated_span_after_delete() {
    let mut engine = animated(
        1,
        1,
        &[(20, vec![RED]), (70, vec![GREEN]), (130, vec![BLUE])],
    );
    send(
        &mut engine,
        json!({"type":"add_frame_tag","tag":{"name":"Walk","from_frame":1,"to_frame":3,"direction":"ping_pong_reverse","repeat":4,"color":[4,8,12,255]}}),
    );
    let stale = request(&engine, "gif", json!({"kind":"tag","id":1}));
    send(&mut engine, json!({"type":"reorder_frames","ids":[3,2,1]}));
    readonly_export(&mut engine, stale, true);
    let value = request(&engine, "gif", json!({"kind":"tag","id":1}));
    let bytes = readonly_export(&mut engine, value, false);
    assert_eq!(gif_blocks(&bytes).loops, Some(3));
    matches_sequence(&engine, &bytes, &[1, 2, 3, 2], &[20, 70, 130, 70]);
    let value = request(&engine, "atlas", json!({"kind":"tag","id":1}));
    let bytes = readonly_export(&mut engine, value, false);
    let (metadata, width, height, pixels) = atlas(&bytes);
    assert_eq!(
        metadata["tag"],
        json!({"id":1,"name":"Walk","color":[4,8,12,255],"fromFrame":3,"toFrame":1})
    );
    assert_eq!(
        metadata["playback"],
        json!({"frameIds":[1,2,3,2],"direction":"ping_pong_reverse","repeat":4})
    );
    atlas_matches(&engine, &metadata, width, height, &pixels);
    send(&mut engine, json!({"type":"delete_frame","frame_id":3}));
    let value = request(&engine, "gif", json!({"kind":"tag","id":1}));
    let bytes = readonly_export(&mut engine, value, false);
    matches_sequence(&engine, &bytes, &[1, 2], &[20, 70]);
    let value = request(&engine, "atlas", json!({"kind":"tag","id":1}));
    let bytes = readonly_export(&mut engine, value, false);
    assert_eq!(atlas(&bytes).0["tag"]["fromFrame"], 2);
}

#[test]
fn invalid_export_scopes_revisions_settings_and_cross_format_fields_reject_atomically() {
    let mut engine = sprites();
    send(
        &mut engine,
        json!({"type":"add_frame_tag","tag":{"name":"Valid","from_frame":1,"to_frame":3,"direction":"forward","repeat":1,"color":[0,0,0,255]}}),
    );
    let good = request(&engine, "gif", all("forward", 1));
    let mut invalid = vec![
        json!({"revision":0,"format":"gif","scope":all("forward",1)}),
        request(&engine, "gif", json!({"kind":"tag","id":0})),
        request(&engine, "gif", json!({"kind":"tag","id":999})),
        request(
            &engine,
            "atlas",
            json!({"kind":"range","from_frame":1,"to_frame":999,"direction":"forward","repeat":1}),
        ),
        request(
            &engine,
            "atlas",
            json!({"kind":"all","direction":"invalid","repeat":1}),
        ),
    ];
    for extra in [
        json!({"columns":1}),
        json!({"padding":0}),
        json!({"alpha":{"kind":"threshold","cutoff":0}}),
        json!({"alpha":{"kind":"threshold","cutoff":256}}),
        json!({"alpha":{"kind":"matte","color":[1,2]}}),
        json!({"color_policy":"unknown"}),
        json!({"timing":"unknown"}),
    ] {
        let mut value = good.clone();
        for (key, field) in extra.as_object().unwrap() {
            value[key] = field.clone();
        }
        invalid.push(value);
    }
    for extra in [
        json!({"alpha":{"kind":"exact"}}),
        json!({"timing":"exact"}),
        json!({"color_policy":"exact"}),
        json!({"columns":4}),
        json!({"padding":33}),
    ] {
        let mut value = request(&engine, "atlas", all("forward", 1));
        for (key, field) in extra.as_object().unwrap() {
            value[key] = field.clone();
        }
        invalid.push(value);
    }
    invalid.push(request(
        &engine,
        "gif",
        json!({"kind":"tag","id":1,"direction":"reverse","repeat":1}),
    ));
    for value in invalid {
        readonly_export(&mut engine, value, true);
    }
    let mut still = Engine::new(2, 1).unwrap();
    let value = request(&still, "gif", all("forward", 1));
    readonly_export(&mut still, value, true);
    let value = request(&still, "atlas", all("forward", 1));
    readonly_export(&mut still, value, true);
}

#[test]
fn successful_and_failed_exports_preserve_selection_pending_dirty_undo_redo_and_ids() {
    let mut engine = sprites();
    let mut control = sprites();
    for candidate in [&mut engine, &mut control] {
        candidate.frame_with_background(true);
        send(
            candidate,
            json!({"type":"begin","brush":{"size":1,"opacity":1,"hardness":1,"color":[0,255,0],"eraser":false,"raster":"pixel","size_pressure":0,"opacity_pressure":0,"stabilization":0}}),
        );
        candidate
            .samples(&[Sample {
                x: 0.5,
                y: 0.5,
                pressure: 1.0,
            }])
            .unwrap();
        send(candidate, json!({"type":"end"}));
        send(
            candidate,
            json!({"type":"select","rect":{"left":0,"top":0,"right":1,"bottom":1}}),
        );
    }
    let saved = engine.save().unwrap();
    let state = engine.state();
    let selection = engine.selection_frame();
    let value = request(&engine, "gif", all("forward", 1));
    let bytes = export(&engine, value).unwrap();
    matches_sequence(&engine, &bytes, &[1, 2, 3], &[20, 70, 130]);
    let value = request(&engine, "atlas", all("forward", 1));
    export(&engine, value).unwrap();
    let mut invalid = request(&engine, "atlas", all("forward", 1));
    invalid["padding"] = json!(33);
    assert!(export(&engine, invalid).is_err());
    assert!(engine.save().unwrap() == saved);
    assert_eq!(engine.state(), state);
    assert!(engine.selection_frame() == selection);
    assert!(engine.frame_with_background(true) == control.frame_with_background(true));
    engine.command(Command::Undo).unwrap();
    control.command(Command::Undo).unwrap();
    assert!(engine.save().unwrap() == control.save().unwrap());
    assert_eq!(engine.state(), control.state());
    engine.command(Command::Redo).unwrap();
    control.command(Command::Redo).unwrap();
    assert!(engine.save().unwrap() == saved);
    assert!(engine.save().unwrap() == control.save().unwrap());
    assert_eq!(engine.state(), control.state());
}

#[test]
fn live_stroke_export_rejection_does_not_commit_cancel_or_consume_its_pixels() {
    let mut engine = sprites();
    engine.frame_with_background(true);
    let committed = engine.save().unwrap();
    send(
        &mut engine,
        json!({"type":"begin","brush":{"size":1,"opacity":1,"hardness":1,"color":[0,255,0],"eraser":false,"raster":"pixel","size_pressure":0,"opacity_pressure":0,"stabilization":0}}),
    );
    engine
        .samples(&[Sample {
            x: 0.5,
            y: 0.5,
            pressure: 1.0,
        }])
        .unwrap();
    let state = engine.state();
    let preview = actual_pixels(&engine, 3);
    assert_eq!(preview[0], GREEN);
    for format in ["gif", "atlas"] {
        let value = request(&engine, format, all("forward", 1));
        assert!(export(&engine, value).is_err());
        assert_eq!(engine.state(), state);
        assert!(actual_pixels(&engine, 3) == preview);
    }
    send(&mut engine, json!({"type":"cancel"}));
    assert!(engine.save().unwrap() == committed);
    assert_eq!(actual_pixels(&engine, 3), [CLEAR, RED]);
}

fn mask(id: u32, hidden_x: u32) -> MaskEntry {
    let mut plane = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 4,
            bottom: 1,
        },
        255,
    );
    let mut tile = vec![255; MASK_TILE_BYTES];
    tile[hidden_x as usize] = 0;
    plane.tiles.insert((0, 0), Arc::new(tile));
    MaskEntry {
        id,
        name: format!("Mask {id}"),
        plane,
    }
}

fn vector(x: f32) -> CelSource {
    CelSource::Vector(Arc::new(VectorLayer {
        next_object_id: 2,
        objects: vec![Arc::new(VectorObject {
            id: 1,
            name: "Vector clip base".into(),
            visible: true,
            geometry: Geometry::Rect {
                x,
                y: 0.0,
                width: 1.0,
                height: 1.0,
            },
            transform: [1.0, 0.0, 0.0, 1.0, 0.0, 0.0],
            style: Style {
                fill: Some(GREEN),
                stroke: None,
                fill_rule: FillRule::NonZero,
            },
        })],
    }))
}

#[test]
fn gif_and_atlas_use_canonical_group_masks_cel_masks_vector_and_clipped_composites() {
    let mut engine = animated(4, 1, &[(20, vec![RED; 4]), (70, vec![GREEN; 4])]);
    let mut group = Layer::group(4, "Group".into(), GroupIsolation::Isolated);
    group.masks = vec![mask(1, 2)];
    let mut base = engine.document.layers[0].clone();
    base.parent_id = Some(4);
    let mut vector_track = Layer::new(2, "Vector".into());
    vector_track.parent_id = Some(4);
    vector_track.content = LayerContent::CelTrack {
        kind: CelKind::Vector,
    };
    let mut clipping = Layer::new(3, "Clipping".into());
    clipping.parent_id = Some(4);
    clipping.clipping = true;
    clipping.content = LayerContent::CelTrack {
        kind: CelKind::Raster,
    };
    engine.document.layers = vec![group, base, vector_track, clipping];
    engine.document.next_id = 5;
    engine.document.next_mask_id = 4;
    let animation = Arc::make_mut(engine.document.animation.as_mut().unwrap());
    Arc::make_mut(animation.cels.get_mut(&1).unwrap()).masks = vec![mask(2, 3)];
    Arc::make_mut(animation.cels.get_mut(&2).unwrap()).masks = vec![mask(3, 0)];
    for (frame, vector_cel, clipping_cel, x) in [(1, 3, 4, 1.0), (2, 5, 6, 2.0)] {
        animation.cels.insert(
            vector_cel,
            Arc::new(Cel {
                id: vector_cel,
                layer_id: 2,
                source: vector(x),
                masks: vec![],
            }),
        );
        animation.cels.insert(
            clipping_cel,
            Arc::new(Cel {
                id: clipping_cel,
                layer_id: 3,
                source: CelSource::Raster(Arc::new(raster(4, 1, &[BLUE; 4]))),
                masks: vec![],
            }),
        );
        let frame = animation
            .frames
            .iter_mut()
            .find(|candidate| candidate.id == frame)
            .unwrap();
        frame.exposures.insert(2, vector_cel);
        frame.exposures.insert(3, clipping_cel);
    }
    animation.next_cel_id = 7;
    engine.load(&engine.save().unwrap()).unwrap();
    assert_eq!(actual_pixels(&engine, 1), [RED, BLUE, CLEAR, CLEAR]);
    assert_eq!(actual_pixels(&engine, 2), [CLEAR, GREEN, CLEAR, GREEN]);
    let value = request(&engine, "gif", all("forward", 1));
    let bytes = readonly_export(&mut engine, value, false);
    matches_sequence(&engine, &bytes, &[1, 2], &[20, 70]);
    let value = request(&engine, "atlas", all("forward", 1));
    let bytes = readonly_export(&mut engine, value, false);
    let (metadata, width, height, pixels) = atlas(&bytes);
    atlas_matches(&engine, &metadata, width, height, &pixels);
}

#[test]
fn gif_and_atlas_resolve_indexed_cels_with_nonzero_transparent_slot_to_actual_rgba() {
    let mut engine = animated(
        3,
        1,
        &[(20, vec![RED, CLEAR, BLUE]), (70, vec![BLUE, RED, CLEAR])],
    );
    engine.document.palette = Some(IndexedPalette {
        colors: vec![RED, BLUE, CLEAR],
        transparent: 2,
        order: vec![2, 1, 0],
    });
    let animation = Arc::make_mut(engine.document.animation.as_mut().unwrap());
    for (id, indices) in [(1, [0, 2, 1]), (2, [1, 0, 2])] {
        let mut tile = vec![2; INDEX_TILE_BYTES];
        tile[..3].copy_from_slice(&indices);
        Arc::make_mut(animation.cels.get_mut(&id).unwrap()).source = CelSource::Raster(Arc::new(
            RasterPlane::Indexed(BTreeMap::from([((0, 0), Arc::new(tile))])),
        ));
    }
    engine.load(&engine.save().unwrap()).unwrap();
    assert_eq!(actual_pixels(&engine, 1), [RED, CLEAR, BLUE]);
    assert_eq!(actual_pixels(&engine, 2), [BLUE, RED, CLEAR]);
    let value = request(&engine, "gif", all("forward", 1));
    let bytes = readonly_export(&mut engine, value, false);
    matches_sequence(&engine, &bytes, &[1, 2], &[20, 70]);
    let value = request(&engine, "atlas", all("forward", 1));
    let bytes = readonly_export(&mut engine, value, false);
    let (metadata, width, height, pixels) = atlas(&bytes);
    atlas_matches(&engine, &metadata, width, height, &pixels);
}

#[test]
fn gif_and_atlas_apply_global_masked_inverse_curves_to_each_frame_without_filling_clear_pixels() {
    let mut engine = animated(
        3,
        1,
        &[(20, vec![RED, RED, CLEAR]), (70, vec![GREEN, GREEN, CLEAR])],
    );
    let settings: podor_engine::AdjustmentSpec = serde_json::from_value(json!({
        "kind":"curves","curves":{"rgb":{"points":[{"x":0,"y":255},{"x":255,"y":0}]}}
    }))
    .unwrap();
    let mut adjustment = Layer::new(2, "Masked inverse curves".into());
    adjustment.content = LayerContent::Adjustment {
        settings: settings.into(),
    };
    let mut plane = LayerMask::new(
        MaskBounds {
            left: 0,
            top: 0,
            right: 3,
            bottom: 1,
        },
        255,
    );
    let mut coverage = vec![255; MASK_TILE_BYTES];
    coverage[1] = 0;
    plane.tiles.insert((0, 0), Arc::new(coverage));
    adjustment.masks = vec![MaskEntry {
        id: 1,
        name: "Protect middle pixel".into(),
        plane,
    }];
    engine.document.layers.push(adjustment);
    engine.document.next_id = 3;
    engine.document.next_mask_id = 2;
    engine.load(&engine.save().unwrap()).unwrap();
    assert_eq!(actual_pixels(&engine, 1), [[0, 255, 255, 255], RED, CLEAR]);
    assert_eq!(
        actual_pixels(&engine, 2),
        [[255, 0, 255, 255], GREEN, CLEAR]
    );
    let value = request(&engine, "gif", all("forward", 1));
    let bytes = readonly_export(&mut engine, value, false);
    matches_sequence(&engine, &bytes, &[1, 2], &[20, 70]);
    let value = request(&engine, "atlas", all("forward", 1));
    let bytes = readonly_export(&mut engine, value, false);
    let (metadata, width, height, pixels) = atlas(&bytes);
    atlas_matches(&engine, &metadata, width, height, &pixels);
}
