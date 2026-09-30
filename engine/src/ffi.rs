use crate::{
    model::{
        Sample, MAX_COMMAND_BYTES, MAX_PALETTE_COMMAND_BYTES, MAX_SELECTION_COMMAND_BYTES,
        MAX_VECTOR_COMMAND_BYTES,
    },
    Command, CopyMode, Engine,
};
use std::{
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{
        atomic::{AtomicU64, Ordering},
        Mutex, OnceLock,
    },
};

static ENGINES: OnceLock<Mutex<HashMap<u64, Engine>>> = OnceLock::new();
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
const MAX_REQUEST: usize = 256 * 1024 * 1024;

fn engines() -> &'static Mutex<HashMap<u64, Engine>> {
    ENGINES.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn create(width: u32, height: u32) -> Result<u64, String> {
    let engine = Engine::new(width, height)?;
    let id = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    engines()
        .lock()
        .map_err(|_| "引擎状态异常")?
        .insert(id, engine);
    Ok(id)
}

pub fn destroy(handle: u64) {
    if let Ok(mut map) = engines().lock() {
        map.remove(&handle);
    }
}

pub fn dispatch(handle: u64, operation: u32, bytes: &[u8]) -> Result<Vec<u8>, String> {
    if bytes.len() > MAX_REQUEST {
        return Err("请求超出大小限制".into());
    }
    if operation == 18 {
        return crate::reference::decode(bytes);
    }
    if operation == 19 {
        if bytes.len() > MAX_COMMAND_BYTES {
            return Err("笔刷预览参数过长".into());
        }
        let brush = serde_json::from_slice(bytes).map_err(|_| "笔刷预览参数无效")?;
        return crate::brush_preview(brush);
    }
    if operation == 24 {
        if bytes.len() > MAX_COMMAND_BYTES {
            return Err("帧渲染参数过长".into());
        }
        let request: crate::FrameRenderRequest =
            serde_json::from_slice(bytes).map_err(|_| "动画帧渲染参数无效")?;
        let (document, cache) = {
            let map = engines().lock().map_err(|_| "引擎状态异常")?;
            let engine = map.get(&handle).ok_or("画布已关闭")?;
            if request.revision != engine.revision {
                return Err("动画帧已变化".into());
            }
            (engine.frame_snapshot()?, engine.vector_cache.clone())
        };
        return crate::animation_engine::render_frame(&document, request, cache);
    }
    if operation == 25 {
        if bytes.len() > MAX_COMMAND_BYTES {
            return Err("动画缩略图参数过长".into());
        }
        let request: crate::FramePreviewsRequest =
            serde_json::from_slice(bytes).map_err(|_| "动画缩略图参数无效")?;
        let (document, cache) = {
            let map = engines().lock().map_err(|_| "引擎状态异常")?;
            let engine = map.get(&handle).ok_or("画布已关闭")?;
            if request.revision != engine.revision {
                return Err("动画缩略图请求已过期".into());
            }
            (engine.frame_snapshot()?, engine.vector_cache.clone())
        };
        return crate::animation_engine::frame_previews(&document, request, cache);
    }
    if operation == 26 {
        if bytes.len() > MAX_COMMAND_BYTES {
            return Err("动画导出参数过长".into());
        }
        let request: crate::AnimationExportRequest =
            serde_json::from_slice(bytes).map_err(|_| "动画导出参数无效")?;
        let document = {
            let map = engines().lock().map_err(|_| "引擎状态异常")?;
            let engine = map.get(&handle).ok_or("画布已关闭")?;
            engine.animation_export_snapshot(&request)?
        };
        return crate::animation_export::export(&document, request);
    }
    if operation == 27 {
        if bytes.len() > MAX_COMMAND_BYTES {
            return Err("Aseprite 导出参数过长".into());
        }
        let request: crate::AsepriteExportRequest =
            serde_json::from_slice(bytes).map_err(|_| "Aseprite 导出参数无效")?;
        let document = {
            let map = engines().lock().map_err(|_| "引擎状态异常")?;
            map.get(&handle)
                .ok_or("画布已关闭")?
                .aseprite_export_snapshot(&request)?
        };
        return crate::aseprite::export(&document, request);
    }
    if operation == 4 {
        let document = crate::storage::load(bytes)?;
        let mut map = engines().lock().map_err(|_| "引擎状态异常")?;
        let engine = map.get_mut(&handle).ok_or("画布已关闭")?;
        engine.install_document(document)?;
        return Ok(engine.state().to_string().into_bytes());
    }
    if operation == 5 {
        let options = if bytes.is_empty() {
            Default::default()
        } else {
            serde_json::from_slice(bytes).map_err(|_| "导出选项无效")?
        };
        let document = {
            let map = engines().lock().map_err(|_| "引擎状态异常")?;
            map.get(&handle).ok_or("画布已关闭")?.frame_snapshot()?
        };
        return crate::storage::export_image(&document, options);
    }
    if operation == 3 {
        let document = {
            let map = engines().lock().map_err(|_| "引擎状态异常")?;
            map.get(&handle).ok_or("画布已关闭")?.frame_snapshot()?
        };
        return crate::storage::save(&document);
    }
    let mut map = engines().lock().map_err(|_| "引擎状态异常")?;
    let engine = map.get_mut(&handle).ok_or("画布已关闭")?;
    match operation {
        23 => {
            if bytes.len() > MAX_COMMAND_BYTES {
                return Err("SVG 导出参数过长".into());
            }
            #[derive(serde::Deserialize)]
            struct Request {
                id: u32,
                revision: u64,
                #[serde(default)]
                frame_id: Option<u32>,
                #[serde(default, deserialize_with = "crate::animation_engine::optional_cel")]
                cel_id: Option<Option<u32>>,
                #[serde(default)]
                target_layer_id: Option<u32>,
            }
            let request: Request = serde_json::from_slice(bytes).map_err(|_| "SVG 导出参数无效")?;
            engine.check_frame_target(
                request.frame_id,
                request.cel_id,
                Some(request.revision),
                request.target_layer_id,
            )?;
            engine.vector_svg(request.id, request.revision)
        }
        22 => {
            if bytes.len() > MAX_VECTOR_COMMAND_BYTES {
                return Err("预览参数过长".into());
            }
            let request: crate::LayerActionRequest =
                serde_json::from_slice(bytes).map_err(|_| "图层预览参数无效")?;
            if bytes.len() > MAX_COMMAND_BYTES
                && !matches!(request.action, crate::LayerAction::Vector { .. })
            {
                return Err("预览参数过长".into());
            }
            engine.preview_layer_action(request)
        }
        21 => match bytes {
            [] | [0] => engine.move_layer_frame(false),
            [1] => engine.move_layer_frame(true),
            _ => Err("移动预览选项无效".into()),
        },
        20 => match bytes {
            [] => Ok(engine.mask_previews()),
            [1] => Ok(engine.mask_stack_previews()),
            _ => Err("蒙版缩略图请求无效".into()),
        },
        17 => {
            if !bytes.is_empty() {
                return Err("曲线直方图请求无效".into());
            }
            engine.curve_histogram()
        }
        16 => {
            if bytes.len() > MAX_COMMAND_BYTES {
                return Err("命令过长".into());
            }
            let request = serde_json::from_slice(bytes).map_err(|_| "调整参数无效")?;
            engine.preview_adjustment(request)
        }
        15 => match bytes {
            [] | [0] => Ok(engine.selection_outline()),
            [1] => Ok(engine.selection_outline_mask()),
            _ => Err("选区轮廓选项无效".into()),
        },
        14 => {
            let [count] = bytes else {
                return Err("色卡数量无效".into());
            };
            Ok(engine
                .extract_palette(usize::from(*count))?
                .into_iter()
                .flatten()
                .collect())
        }
        13 => Ok(engine.selection_frame()),
        0 => {
            if bytes.len() > MAX_VECTOR_COMMAND_BYTES {
                return Err("命令过长".into());
            }
            let request: crate::CommandRequest =
                serde_json::from_slice(bytes).map_err(|_| "命令格式无效")?;
            let command = &request.command;
            if matches!(
                command,
                Command::AddVectorObject { .. } | Command::SetVectorObject { .. }
            ) {
                return Ok(engine.command_request(request)?.to_string().into_bytes());
            }
            if bytes.len() > MAX_SELECTION_COMMAND_BYTES {
                return Err("命令过长".into());
            }
            if bytes.len() > MAX_COMMAND_BYTES
                && bytes.len() <= MAX_PALETTE_COMMAND_BYTES
                && matches!(
                    command,
                    Command::NewIndexed { .. }
                        | Command::ConvertColorMode { .. }
                        | Command::ReorderPalette { .. }
                )
            {
                return Ok(engine.command_request(request)?.to_string().into_bytes());
            }
            if bytes.len() > MAX_COMMAND_BYTES
                && !matches!(
                    command,
                    Command::SelectShape { .. }
                        | Command::CombineSelection { .. }
                        | Command::FillLasso { .. }
                )
            {
                return Err("命令过长".into());
            }
            Ok(engine.command_request(request)?.to_string().into_bytes())
        }
        1 => {
            if !bytes.len().is_multiple_of(12) || bytes.len() > 12 * 4096 {
                return Err("笔画采样数据无效".into());
            }
            let samples: Vec<_> = bytes
                .as_chunks::<12>()
                .0
                .iter()
                .map(|p| Sample {
                    x: f32::from_le_bytes(p[0..4].try_into().unwrap()),
                    y: f32::from_le_bytes(p[4..8].try_into().unwrap()),
                    pressure: f32::from_le_bytes(p[8..12].try_into().unwrap()),
                })
                .collect();
            engine.samples(&samples)?;
            Ok(Vec::new())
        }
        2 => match bytes {
            [] | [0] => Ok(engine.frame()),
            [1] => Ok(engine.frame_with_background(true)),
            _ => Err("画布显示选项无效".into()),
        },
        3 => engine.save(),
        4 => {
            engine.load(bytes)?;
            Ok(engine.state().to_string().into_bytes())
        }
        5 => {
            let options = if bytes.is_empty() {
                Default::default()
            } else {
                serde_json::from_slice(bytes).map_err(|_| "导出选项无效")?
            };
            engine.export_image(options)
        }
        6 => engine.previews(),
        7 => Ok(engine.thumbnail()),
        8 => {
            if bytes == [1] {
                engine.selection_move_frame()
            } else {
                engine.layer_frame()
            }
        }
        9 => {
            let size = bytes.get(..4).ok_or("导入图层数据无效")?;
            let length = u32::from_le_bytes(size.try_into().unwrap()) as usize;
            if length == 0 || length > crate::model::MAX_LAYER_NAME_BYTES {
                return Err("导入图层数据无效".into());
            }
            let name = bytes.get(4..4 + length).ok_or("导入图层数据无效")?;
            let name = std::str::from_utf8(name).map_err(|_| "图层属性无效")?;
            engine.import_layer(&bytes[4 + length..], name)?;
            Ok(engine.state().to_string().into_bytes())
        }
        10 => {
            let mode = match bytes {
                [0] => CopyMode::Layer,
                [1] => CopyMode::Visible,
                [2] => CopyMode::Cut,
                _ => return Err("复制选项无效".into()),
            };
            engine.copy_selection(mode)
        }
        11 => {
            engine.paste_image(bytes)?;
            Ok(engine.state().to_string().into_bytes())
        }
        12 => {
            let bounds = engine.layer_transform_bounds()?;
            Ok([bounds.left, bounds.top, bounds.right, bounds.bottom]
                .into_iter()
                .flat_map(i32::to_le_bytes)
                .collect())
        }
        _ => Err("未知引擎操作".into()),
    }
}

#[repr(C)]
pub struct PodorBuffer {
    pub data: *mut u8,
    pub length: usize,
    pub error: u32,
}

impl PodorBuffer {
    fn from_result(result: Result<Vec<u8>, String>) -> Self {
        let (bytes, error) = match result {
            Ok(bytes) => (bytes, 0),
            Err(error) => (error.into_bytes(), 1),
        };
        let mut bytes = bytes.into_boxed_slice();
        let output = Self {
            data: bytes.as_mut_ptr(),
            length: bytes.len(),
            error,
        };
        std::mem::forget(bytes);
        output
    }
}

#[no_mangle]
pub extern "C" fn podor_create(width: u32, height: u32) -> u64 {
    catch_unwind(|| create(width, height).unwrap_or(0)).unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn podor_destroy(handle: u64) {
    let _ = catch_unwind(|| destroy(handle));
}

#[no_mangle]
pub unsafe extern "C" fn podor_call(
    handle: u64,
    operation: u32,
    data: *const u8,
    length: usize,
) -> PodorBuffer {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if length > MAX_REQUEST || (length > 0 && data.is_null()) {
            return Err("输入缓冲区无效".into());
        }
        let bytes = if length == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(data, length) }
        };
        dispatch(handle, operation, bytes)
    }))
    .unwrap_or_else(|_| Err("引擎内部错误".into()));
    PodorBuffer::from_result(result)
}

#[no_mangle]
pub unsafe extern "C" fn podor_free(buffer: PodorBuffer) {
    if !buffer.data.is_null() {
        unsafe {
            drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(
                buffer.data,
                buffer.length,
            )));
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn animation_export_bridge_keeps_canonical_state_dirty_and_history_and_rejects_live_strokes() {
        let handle = create(4, 2).unwrap();
        animation_bridge_command(
            handle,
            json!({"type":"fill","x":0,"y":0,"color":[180,40,20,255],"tolerance":0}),
        );
        animation_bridge_command(handle, json!({"type":"enable_animation","duration_ms":70}));
        let state = animation_bridge_command(
            handle,
            json!({"type":"add_frame","index":1,"duration_ms":130}),
        );
        let saved = dispatch(handle, 3, &[]).unwrap();
        let dirty = engines()
            .lock()
            .unwrap()
            .get(&handle)
            .unwrap()
            .dirty
            .clone();
        let request = json!({"revision":state["revision"],"format":"gif","scope":{"kind":"all","direction":"ping_pong","repeat":4}});
        let encoded = dispatch(handle, 26, &serde_json::to_vec(&request).unwrap()).unwrap();
        assert_eq!(&encoded[..6], b"GIF89a");
        assert_eq!(encoded.last(), Some(&b';'));
        assert!(dispatch(handle, 3, &[]).unwrap() == saved);
        assert_eq!(
            animation_bridge_command(handle, json!({"type":"state"})),
            state
        );
        assert_eq!(engines().lock().unwrap().get(&handle).unwrap().dirty, dirty);
        let mut invalid = request.clone();
        invalid["revision"] = json!(0);
        assert!(dispatch(handle, 26, &serde_json::to_vec(&invalid).unwrap()).is_err());
        invalid = request.clone();
        invalid["scope"] = json!({"kind":"tag","id":900});
        assert!(dispatch(handle, 26, &serde_json::to_vec(&invalid).unwrap()).is_err());
        invalid = request.clone();
        invalid["columns"] = json!(2);
        assert!(dispatch(handle, 26, &serde_json::to_vec(&invalid).unwrap()).is_err());
        assert!(dispatch(handle, 26, &[]).is_err());
        assert!(dispatch(handle, 26, &vec![0; MAX_COMMAND_BYTES + 1]).is_err());
        assert!(dispatch(handle, 3, &[]).unwrap() == saved);
        animation_bridge_command(
            handle,
            json!({"type":"begin","brush":{"size":4,"opacity":1,"hardness":1,"color":[255,0,0],"eraser":false}}),
        );
        let live_state = animation_bridge_command(handle, json!({"type":"state"}));
        let mut live_request = request;
        live_request["revision"] = live_state["revision"].clone();
        assert!(dispatch(handle, 26, &serde_json::to_vec(&live_request).unwrap()).is_err());
        assert_eq!(
            animation_bridge_command(handle, json!({"type":"state"})),
            live_state
        );
        animation_bridge_command(handle, json!({"type":"cancel"}));
        assert!(dispatch(handle, 3, &[]).unwrap() == saved);
        destroy(handle);
    }

    #[test]
    fn captured_animation_export_runs_while_the_global_engine_lock_is_held() {
        let handle = create(2, 2).unwrap();
        let state =
            animation_bridge_command(handle, json!({"type":"enable_animation","duration_ms":100}));
        let request: crate::AnimationExportRequest = serde_json::from_value(json!({"revision":state["revision"],"format":"atlas","scope":{"kind":"all","direction":"forward","repeat":1}})).unwrap();
        let guard = engines().lock().unwrap();
        let document = guard
            .get(&handle)
            .unwrap()
            .animation_export_snapshot(&request)
            .unwrap();
        let (sender, receiver) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            sender
                .send(crate::animation_export::export(&document, request))
                .unwrap()
        });
        let result = receiver
            .recv_timeout(std::time::Duration::from_secs(3))
            .unwrap()
            .unwrap();
        assert_eq!(&result[..4], b"PK\x03\x04");
        drop(guard);
        worker.join().unwrap();
        destroy(handle);
    }

    #[test]
    fn ffi_chunked_pressure_and_mask_strokes_with_transparent_frames_preserve_sample_bits_and_sources(
    ) {
        for animated in [false, true] {
            for mask in [false, true] {
                let fixture = create(128, 96).unwrap();
                animation_bridge_command(
                    fixture,
                    json!({"type":"select","rect":{"left":0,"top":0,"right":32,"bottom":96}}),
                );
                animation_bridge_command(
                    fixture,
                    json!({"type":"fill","x":0,"y":0,"color":[20,80,100,255],"tolerance":0}),
                );
                animation_bridge_command(fixture, json!({"type":"select","rect":null}));
                if mask {
                    animation_bridge_command(fixture, json!({"type":"add_mask","mode":"reveal"}));
                    animation_bridge_command(
                        fixture,
                        json!({"type":"set_mask_editing","enabled":false}),
                    );
                }
                if animated {
                    animation_bridge_command(
                        fixture,
                        json!({"type":"enable_animation","duration_ms":100}),
                    );
                    if !mask {
                        animation_bridge_command(
                            fixture,
                            json!({"type":"add_frame","index":1,"duration_ms":100,"select":true}),
                        );
                    }
                }
                let saved = dispatch(fixture, 3, &[]).unwrap();
                destroy(fixture);
                let batch = create(1, 1).unwrap();
                let chunked = create(1, 1).unwrap();
                let brush = json!({"type":"begin","brush":{"size":if mask{8}else{6},"opacity":1,
                    "hardness":1,"size_pressure":0.5,"opacity_pressure":0,"stabilization":0,
                    "spacing":0.08,"color":if mask{[0,0,0]}else{[179,87,52]},"eraser":mask,
                    "smudge":false,"texture":"smooth","raster":"antialiased","tip":"round",
                    "follow_direction":false,"pressure_curve":0,"grain":0,"paper":0,"mix":0,
                    "aspect":1,"angle":0,"symmetry":{"mode":"off"}}});
                for handle in [batch, chunked] {
                    dispatch(handle, 4, &saved).unwrap();
                    if mask {
                        animation_bridge_command(
                            handle,
                            json!({"type":"set_mask_editing","enabled":true}),
                        );
                    }
                    animation_bridge_command(handle, brush.clone());
                }
                let samples: Vec<[f32; 3]> = if mask {
                    vec![[12.0, 20.0, 1.0], [20.0, 20.0, 1.0]]
                } else {
                    vec![[56.0, 24.0, 0.25], [64.0, 30.0, 0.75], [80.0, 38.0, 1.0]]
                };
                let bytes: Vec<u8> = samples
                    .iter()
                    .flatten()
                    .flat_map(|value| value.to_le_bytes())
                    .collect();
                dispatch(batch, 1, &bytes).unwrap();
                for sample in bytes.as_chunks::<12>().0 {
                    dispatch(chunked, 1, sample).unwrap();
                    let before = dispatch(chunked, 0, b"{\"type\":\"state\"}").unwrap();
                    dispatch(chunked, 2, &[1]).unwrap();
                    assert!(before == dispatch(chunked, 0, b"{\"type\":\"state\"}").unwrap());
                }
                for handle in [batch, chunked] {
                    animation_bridge_command(handle, json!({"type":"end"}));
                }
                let expected = dispatch(batch, 3, &[]).unwrap();
                let actual = dispatch(chunked, 3, &[]).unwrap();
                assert!(
                    expected == actual,
                    "FFI raw sources differ: animated={animated}, mask={mask}"
                );
                for handle in [batch, chunked] {
                    destroy(handle);
                }
            }
        }
    }

    fn animation_bridge_command(handle: u64, mut command: serde_json::Value) -> serde_json::Value {
        let state: serde_json::Value =
            serde_json::from_slice(&dispatch(handle, 0, br#"{"type":"state"}"#).unwrap()).unwrap();
        if command.get("revision").is_none() {
            command["revision"] = state["revision"].clone();
        }
        if !state["animation"].is_null() {
            if command.get("frame_id").is_none() {
                command["frame_id"] = state["animation"]["activeFrameId"].clone();
            }
            if command.get("cel_id").is_none() {
                command["cel_id"] = state["animation"]["activeCelId"].clone();
            }
            if command.get("target_layer_id").is_none() {
                command["target_layer_id"] = state["active"].clone();
            }
        }
        serde_json::from_slice(
            &dispatch(handle, 0, &serde_json::to_vec(&command).unwrap()).unwrap(),
        )
        .unwrap()
    }

    #[test]
    fn animation_frame_and_timeline_thumbnail_bridge_reads_snapshot_without_consuming_dirty() {
        let handle = create(64, 64).unwrap();
        animation_bridge_command(
            handle,
            json!({"type":"fill","x":0,"y":0,"color":[200,100,50,255],"tolerance":0}),
        );
        let first =
            animation_bridge_command(handle, json!({"type":"enable_animation","duration_ms":100}))
                ["animation"]["activeFrameId"]
                .clone();
        let state = animation_bridge_command(
            handle,
            json!({"type":"add_frame","index":1,"duration_ms":150}),
        );
        let blank = state["animation"]["activeFrameId"].clone();
        dispatch(handle, 2, &[1]).unwrap();
        animation_bridge_command(
            handle,
            json!({"type":"begin","brush":{"size":1,"opacity":1,"hardness":1,
                "color":[0,200,100],"eraser":false,"raster":"pixel","size_pressure":0,
                "opacity_pressure":0,"stabilization":0}}),
        );
        let sample: Vec<_> = [20.0_f32, 24.0, 1.0]
            .into_iter()
            .flat_map(f32::to_le_bytes)
            .collect();
        dispatch(handle, 1, &sample).unwrap();
        let before = dispatch(handle, 3, &[]).unwrap();
        let state_bytes = dispatch(handle, 0, br#"{"type":"state"}"#).unwrap();
        let state: serde_json::Value = serde_json::from_slice(&state_bytes).unwrap();
        assert!(state["animation"]["activeCelId"].is_null());
        let render = json!({"revision":state["revision"],"frame_id":first,"transparent":true});
        let frame = dispatch(handle, 24, &serde_json::to_vec(&render).unwrap()).unwrap();
        assert_eq!(
            vector_bridge_pixel(&vector_bridge_tiles(&frame), 30, 30),
            [200, 100, 50, 255]
        );
        let live = dispatch(
            handle,
            24,
            &serde_json::to_vec(&json!({
            "revision":state["revision"],"frame_id":blank,"transparent":true}))
            .unwrap(),
        )
        .unwrap();
        assert_eq!(
            vector_bridge_pixel(&vector_bridge_tiles(&live), 20, 24),
            [0, 200, 100, 255]
        );
        let thumbs = json!({"revision":state["revision"],"frame_ids":[first,blank]});
        let thumbnails = dispatch(handle, 25, &serde_json::to_vec(&thumbs).unwrap()).unwrap();
        assert_eq!(
            u64::from_le_bytes(thumbnails[..8].try_into().unwrap()),
            state["revision"].as_u64().unwrap()
        );
        assert_eq!(
            u32::from_le_bytes(thumbnails[8..12].try_into().unwrap()),
            96
        );
        assert_eq!(
            u32::from_le_bytes(thumbnails[12..16].try_into().unwrap()),
            2
        );
        let stride = 4 + 96 * 96 * 4;
        assert_eq!(thumbnails.len(), 16 + 2 * stride);
        assert_eq!(
            u32::from_le_bytes(thumbnails[16..20].try_into().unwrap()),
            first.as_u64().unwrap() as u32
        );
        assert_eq!(
            &thumbnails[20 + (40 * 96 + 40) * 4..24 + (40 * 96 + 40) * 4],
            &[200, 100, 50, 255]
        );
        assert_eq!(
            u32::from_le_bytes(thumbnails[16 + stride..20 + stride].try_into().unwrap()),
            blank.as_u64().unwrap() as u32
        );
        assert_eq!(
            dispatch(handle, 0, br#"{"type":"state"}"#).unwrap(),
            state_bytes
        );
        assert_eq!(dispatch(handle, 3, &[]).unwrap(), before);
        let mut invalid = thumbs.clone();
        invalid["revision"] = json!(state["revision"].as_u64().unwrap() + 1);
        assert!(dispatch(handle, 25, &serde_json::to_vec(&invalid).unwrap()).is_err());
        for (operation, payload) in [
            (
                24,
                json!({"revision":state["revision"],"frame_id":999,"transparent":true}),
            ),
            (
                25,
                json!({"revision":state["revision"],"frame_ids":[first,first]}),
            ),
            (
                25,
                json!({"revision":state["revision"],"frame_ids":[first],"size":95}),
            ),
        ] {
            assert!(dispatch(handle, operation, &serde_json::to_vec(&payload).unwrap()).is_err());
        }
        assert_eq!(
            dispatch(handle, 0, br#"{"type":"state"}"#).unwrap(),
            state_bytes
        );
        assert_eq!(dispatch(handle, 3, &[]).unwrap(), before);
        assert_eq!(
            vector_bridge_pixel(
                &vector_bridge_tiles(&dispatch(handle, 2, &[1]).unwrap()),
                20,
                24
            ),
            [0, 200, 100, 255]
        );
        animation_bridge_command(handle, json!({"type":"cancel"}));
        assert!(
            animation_bridge_command(handle, json!({"type":"state"}))["animation"]["activeCelId"]
                .is_null()
        );
        destroy(handle);
    }

    #[test]
    fn animated_bridge_requires_explicit_blank_cel_and_current_layer_for_pixel_commands() {
        let handle = create(32, 32).unwrap();
        animation_bridge_command(handle, json!({"type":"add_layer"}));
        animation_bridge_command(handle, json!({"type":"enable_animation","duration_ms":100}));
        let state = animation_bridge_command(
            handle,
            json!({"type":"add_frame","index":1,"duration_ms":100}),
        );
        let saved = dispatch(handle, 3, &[]).unwrap();
        let request = json!({"type":"fill","x":4,"y":5,"color":[255,0,0,255],"tolerance":0,
            "frame_id":state["animation"]["activeFrameId"],"cel_id":null,
            "target_layer_id":state["active"],"revision":state["revision"]});
        for field in ["frame_id", "cel_id", "target_layer_id", "revision"] {
            let mut missing = request.clone();
            missing.as_object_mut().unwrap().remove(field);
            assert!(dispatch(handle, 0, &serde_json::to_vec(&missing).unwrap()).is_err());
            assert_eq!(dispatch(handle, 3, &[]).unwrap(), saved);
        }
        let mut wrong = request.clone();
        wrong["target_layer_id"] = json!(1);
        assert!(dispatch(handle, 0, &serde_json::to_vec(&wrong).unwrap()).is_err());
        assert_eq!(dispatch(handle, 3, &[]).unwrap(), saved);
        let committed: serde_json::Value = serde_json::from_slice(
            &dispatch(handle, 0, &serde_json::to_vec(&request).unwrap()).unwrap(),
        )
        .unwrap();
        assert!(!committed["animation"]["activeCelId"].is_null());
        assert!(dispatch(handle, 0, &serde_json::to_vec(&request).unwrap()).is_err());
        assert_eq!(
            vector_bridge_pixel(
                &vector_bridge_tiles(&dispatch(handle, 2, &[1]).unwrap()),
                4,
                5
            ),
            [255, 0, 0, 255]
        );
        destroy(handle);
    }

    fn line_generator_bridge_settings() -> serde_json::Value {
        json!({"kind":"speed","seed":42,"count":3,
            "stroke":{"color":[20,80,160,200],"width":4,"cap":"butt","join":"miter","miter_limit":4},
            "opacity":0.5,"randomness":0,"taper_start":1,"taper_end":1,
            "origin":{"x":16,"y":48},"angle":0,"length":32,"spacing":12})
    }

    #[test]
    fn line_generator_bridge_new_layer_preview_and_atomic_commit_share_real_geometry() {
        let handle = create(64, 80).unwrap();
        let before_state = vector_bridge_command(handle, json!({"type":"state"}));
        dispatch(handle, 2, &[1]).unwrap();
        let before = dispatch(handle, 3, &[]).unwrap();
        let settings = line_generator_bridge_settings();
        let request = json!({"id":1,"revision":before_state["revision"],"selection_id":before_state["selectionId"],
            "mask_editing":false,"mask_id":null,"action":{"kind":"generate_lines","name":"Speed effect",
            "parent_id":null,"index":1,"settings":settings}});
        let preview = dispatch(handle, 22, &serde_json::to_vec(&request).unwrap()).unwrap();
        let preview_tiles = vector_bridge_tiles(&preview);
        assert!(!preview_tiles.is_empty());
        assert!(dispatch(handle, 3, &[]).unwrap() == before);
        assert_eq!(
            vector_bridge_command(handle, json!({"type":"state"})),
            before_state
        );
        assert_eq!(dispatch(handle, 2, &[1]).unwrap().len(), 16);
        assert!(preview == dispatch(handle, 22, &serde_json::to_vec(&request).unwrap()).unwrap());
        let mut command = request.clone();
        let action = command.as_object_mut().unwrap().remove("action").unwrap();
        command
            .as_object_mut()
            .unwrap()
            .extend(action.as_object().unwrap().clone());
        command["type"] = json!("generate_lines");
        command.as_object_mut().unwrap().remove("kind");
        let state = vector_bridge_command(handle, command);
        assert_eq!(state["maxGeneratedLines"], 256);
        assert_eq!(state["active"], 2);
        assert_eq!(state["layers"][1]["vector"]["objectCount"], 3);
        assert_eq!(state["layers"][1]["vector"]["nextObjectId"], 4);
        let actual = vector_bridge_tiles(&dispatch(handle, 2, &[1]).unwrap());
        assert!(actual == preview_tiles);
        for y in [36, 48, 60] {
            assert_eq!(vector_bridge_pixel(&actual, 30, y), [8, 31, 63, 100]);
        }
        let object =
            vector_bridge_command(handle, json!({"type":"vector_object","id":2,"object_id":2}));
        assert_eq!(
            object["object"]["geometry"],
            json!({"kind":"line","x1":16.0,"y1":48.0,"x2":48.0,"y2":48.0})
        );
        assert_eq!(
            object["object"]["style"]["stroke"]["color"],
            json!([20, 80, 160, 100])
        );
        let committed = dispatch(handle, 3, &[]).unwrap();
        assert!(dispatch(handle, 22, &serde_json::to_vec(&request).unwrap()).is_err());
        vector_bridge_command(handle, json!({"type":"undo"}));
        assert!(dispatch(handle, 3, &[]).unwrap() == before);
        assert_eq!(
            vector_bridge_command(handle, json!({"type":"state"}))["canUndo"],
            false
        );
        vector_bridge_command(handle, json!({"type":"redo"}));
        assert!(dispatch(handle, 3, &[]).unwrap() == committed);
        destroy(handle);
    }

    #[test]
    fn line_generator_bridge_invalid_drafts_never_advance_document_or_ids() {
        let handle = create(64, 80).unwrap();
        let state = vector_bridge_command(handle, json!({"type":"state"}));
        dispatch(handle, 2, &[1]).unwrap();
        let before = dispatch(handle, 3, &[]).unwrap();
        let request = json!({"id":1,"revision":state["revision"],"selection_id":state["selectionId"],
            "mask_editing":false,"mask_id":null,"action":{"kind":"generate_lines","name":"Speed effect",
            "parent_id":null,"index":1,"settings":line_generator_bridge_settings()}});
        for mutation in 0..6 {
            let mut invalid = request.clone();
            match mutation {
                0 => invalid["action"]["settings"]["count"] = json!(0),
                1 => invalid["action"]["settings"]["count"] = json!(257),
                2 => invalid["action"]["settings"]["randomness"] = json!(1.01),
                3 => invalid["action"]["settings"]["angle"] = json!(361),
                4 => invalid["action"]["index"] = json!(3),
                _ => invalid["selection_id"] = json!(99),
            }
            assert!(dispatch(handle, 22, &serde_json::to_vec(&invalid).unwrap()).is_err());
            let action = invalid.as_object_mut().unwrap().remove("action").unwrap();
            invalid
                .as_object_mut()
                .unwrap()
                .extend(action.as_object().unwrap().clone());
            invalid["type"] = json!("generate_lines");
            invalid.as_object_mut().unwrap().remove("kind");
            assert!(dispatch(handle, 0, &serde_json::to_vec(&invalid).unwrap()).is_err());
            assert!(dispatch(handle, 3, &[]).unwrap() == before);
            assert_eq!(
                vector_bridge_command(handle, json!({"type":"state"})),
                state
            );
            assert_eq!(dispatch(handle, 2, &[1]).unwrap().len(), 16);
        }
        destroy(handle);
    }

    #[test]
    fn assistant_bridge_geometry_preview_guards_and_noop_are_atomic() {
        let handle = create(64, 64).unwrap();
        let spec = json!({"name":"Parallel","visible":false,"geometry":{
            "kind":"parallel","a":{"x":-100.0,"y":20.0},"b":{"x":200.0,"y":20.0}
        }});
        let state = vector_bridge_command(handle, json!({"type":"add_assistant","assistant":spec}));
        assert_eq!(state["maxDrawingAssistants"], 16);
        assert_eq!(state["assistants"]["nextId"], 2);
        assert_eq!(state["assistants"]["snapId"], serde_json::Value::Null);
        assert_eq!(
            state["assistants"]["items"][0]["geometry"],
            spec["geometry"]
        );
        let state = vector_bridge_command(handle, json!({"type":"set_assistant_snap","id":1}));
        dispatch(handle, 2, &[1]).unwrap();
        let saved = dispatch(handle, 3, &[]).unwrap();
        let state_bytes = dispatch(handle, 0, br#"{"type":"state"}"#).unwrap();
        let request = json!({"type":"preview_assistant","assistant":spec,
            "origin":{"x":12.0,"y":24.0},"point":{"x":52.0,"y":56.0},"revision":state["revision"]});
        let preview: serde_json::Value = serde_json::from_slice(
            &dispatch(handle, 0, &serde_json::to_vec(&request).unwrap()).unwrap(),
        )
        .unwrap();
        assert_eq!(preview["point"], json!({"x":52.0,"y":24.0}));
        assert_eq!(preview["family"], serde_json::Value::Null);
        assert_eq!(preview["axis"]["direction"], json!({"x":1.0,"y":0.0}));
        let noop = vector_bridge_command(
            handle,
            json!({"type":"set_assistant","id":1,"assistant":spec}),
        );
        assert_eq!(noop, state);
        for request in [
            json!({"type":"preview_assistant","assistant":spec,"origin":{"x":0,"y":0},"point":{"x":1,"y":1},"revision":0}),
            json!({"type":"set_assistant_snap","id":100,"revision":state["revision"]}),
            json!({"type":"delete_assistant","id":1,"revision":0}),
            json!({"type":"set_assistant","id":1,"assistant":{"name":"Broken","visible":true,"geometry":{"kind":"perspective","families":[]}},"revision":state["revision"]}),
            json!({"type":"begin","brush":{"size":1,"opacity":1,"hardness":1,"color":[0,0,0],"eraser":false},"assistant":{"id":1,"revision":0}}),
            json!({"type":"begin","brush":{"size":1,"opacity":1,"hardness":1,"color":[0,0,0],"eraser":false},"assistant":{"id":2,"revision":state["revision"]}}),
        ] {
            assert!(dispatch(handle, 0, &serde_json::to_vec(&request).unwrap()).is_err());
        }
        assert!(dispatch(handle, 3, &[]).unwrap() == saved);
        assert_eq!(
            dispatch(handle, 0, br#"{"type":"state"}"#).unwrap(),
            state_bytes
        );
        assert_eq!(dispatch(handle, 2, &[1]).unwrap().len(), 16);
        destroy(handle);
    }

    #[test]
    fn assistant_bridge_captures_binding_and_rejects_mutation_during_stroke() {
        let handle = create(64, 64).unwrap();
        let spec = json!({"name":"Horizontal","visible":true,"geometry":{
            "kind":"parallel","a":{"x":0,"y":0},"b":{"x":1,"y":0}
        }});
        vector_bridge_command(handle, json!({"type":"add_assistant","assistant":spec}));
        let state = vector_bridge_command(handle, json!({"type":"set_assistant_snap","id":1}));
        let saved = dispatch(handle, 3, &[]).unwrap();
        vector_bridge_command(
            handle,
            json!({"type":"begin","brush":{"raster":"pixel","size":1,"opacity":1,"hardness":1,"color":[0,0,0],"eraser":false,"size_pressure":0},
            "assistant":{"id":1,"revision":state["revision"]}}),
        );
        let raw = [12.0f32, 24.0, 1.0, 52.0, 56.0, 0.7];
        dispatch(
            handle,
            1,
            &raw.into_iter()
                .flat_map(f32::to_le_bytes)
                .collect::<Vec<_>>(),
        )
        .unwrap();
        assert!(dispatch(
            handle,
            0,
            &serde_json::to_vec(
                &json!({"type":"delete_assistant","id":1,"revision":state["revision"]})
            )
            .unwrap()
        )
        .is_err());
        let preview = vector_bridge_command(
            handle,
            json!({"type":"preview_assistant","assistant":spec,
            "origin":{"x":12,"y":24},"point":{"x":52,"y":56}}),
        );
        assert_eq!(preview["point"], json!({"x":52.0,"y":24.0}));
        vector_bridge_command(handle, json!({"type":"end"}));
        let frame = vector_bridge_tiles(&dispatch(handle, 2, &[1]).unwrap());
        for x in 12..=52 {
            assert_eq!(vector_bridge_pixel(&frame, x, 24), [0, 0, 0, 255]);
            assert_eq!(vector_bridge_pixel(&frame, x, 56), [0; 4]);
        }
        let committed = dispatch(handle, 3, &[]).unwrap();
        vector_bridge_command(handle, json!({"type":"undo"}));
        assert!(dispatch(handle, 3, &[]).unwrap() == saved);
        vector_bridge_command(handle, json!({"type":"redo"}));
        assert!(dispatch(handle, 3, &[]).unwrap() == committed);
        destroy(handle);
    }

    fn vector_bridge_command(handle: u64, mut value: serde_json::Value) -> serde_json::Value {
        if value.get("revision").is_none() {
            let state: serde_json::Value =
                serde_json::from_slice(&dispatch(handle, 0, br#"{"type":"state"}"#).unwrap())
                    .unwrap();
            value["revision"] = state["revision"].clone();
        }
        serde_json::from_slice(&dispatch(handle, 0, &serde_json::to_vec(&value).unwrap()).unwrap())
            .unwrap()
    }

    fn vector_bridge_path() -> serde_json::Value {
        let mut segments = vec![json!({"kind":"move_to","x":24.0,"y":16.0})];
        segments.extend((1..512).map(|index| {
            let angle = index as f64 * std::f64::consts::TAU / 512.0;
            json!({"kind":"line_to","x":16.0 + 8.0 * angle.cos(),"y":16.0 + 8.0 * angle.sin()})
        }));
        segments.push(json!({"kind":"close"}));
        json!({
            "name":"Contour","visible":true,
            "geometry":{"kind":"path","segments":segments},
            "transform":[1.0,0.0,0.0,1.0,0.0,0.0],
            "style":{"fill":[220,40,80,255],"stroke":null,"fill_rule":"non_zero"}
        })
    }

    fn vector_bridge_tiles(bytes: &[u8]) -> std::collections::BTreeMap<(u32, u32), Vec<u8>> {
        let count = u32::from_le_bytes(bytes[12..16].try_into().unwrap()) as usize;
        assert_eq!(bytes.len(), 16 + count * (8 + crate::model::TILE_BYTES));
        bytes[16..]
            .as_chunks::<{ 8 + crate::model::TILE_BYTES }>()
            .0
            .iter()
            .map(|record| {
                let key = (
                    u32::from_le_bytes(record[..4].try_into().unwrap()),
                    u32::from_le_bytes(record[4..8].try_into().unwrap()),
                );
                (key, record[8..].to_vec())
            })
            .collect()
    }

    fn vector_bridge_pixel(
        tiles: &std::collections::BTreeMap<(u32, u32), Vec<u8>>,
        x: u32,
        y: u32,
    ) -> [u8; 4] {
        let edge = crate::model::TILE_SIZE;
        let offset = ((y % edge * edge + x % edge) * 4) as usize;
        tiles
            .get(&(x / edge, y / edge))
            .map_or([0; 4], |tile| tile[offset..offset + 4].try_into().unwrap())
    }

    #[test]
    fn vector_object_commands_cross_the_bridge_and_svg_queries_keep_snapshot_and_limits() {
        let handle = create(32, 32).unwrap();
        let state = vector_bridge_command(
            handle,
            json!({"type":"create_vector","name":"Vectors","parent_id":null,"index":1}),
        );
        let id = state["active"].as_u64().unwrap() as u32;
        let object = vector_bridge_path();
        let add = serde_json::to_vec(&json!({
            "type":"add_vector_object","id":id,"object":object,
            "index":null,"revision":state["revision"]
        }))
        .unwrap();
        assert!(add.len() > MAX_COMMAND_BYTES && add.len() < MAX_VECTOR_COMMAND_BYTES);
        let state: serde_json::Value =
            serde_json::from_slice(&dispatch(handle, 0, &add).unwrap()).unwrap();
        assert_eq!(state["layers"][1]["vector"]["objectCount"], 1);
        let original = vector_bridge_command(
            handle,
            json!({"type":"vector_object","id":id,"object_id":1}),
        )["object"]
            .clone();
        assert_eq!(
            original["geometry"]["segments"].as_array().unwrap().len(),
            513
        );
        let mut edited = original.clone();
        edited["geometry"]["segments"][1]["x"] = json!(23.0);
        let set = serde_json::to_vec(&json!({
            "type":"set_vector_object","id":id,"object_id":1,
            "object":edited,"revision":state["revision"]
        }))
        .unwrap();
        assert!(set.len() > MAX_COMMAND_BYTES && set.len() < MAX_VECTOR_COMMAND_BYTES);
        let state: serde_json::Value =
            serde_json::from_slice(&dispatch(handle, 0, &set).unwrap()).unwrap();
        dispatch(handle, 2, &[1]).unwrap();
        let before = dispatch(handle, 3, &[]).unwrap();
        let state_bytes = dispatch(handle, 0, br#"{"type":"state"}"#).unwrap();
        let detail = vector_bridge_command(
            handle,
            json!({"type":"vector_object","id":id,"object_id":1}),
        );
        assert_eq!(detail["object"], edited);
        let summary = vector_bridge_command(handle, json!({"type":"vector_objects","id":id}));
        assert_eq!(summary["nextObjectId"], 2);
        assert_eq!(summary["objects"][0]["kind"], "path");
        assert!(summary["objects"][0].get("geometry").is_none());
        assert_eq!(
            vector_bridge_command(
                handle,
                json!({"type":"pick_vector_object","id":id,"x":16.0,"y":16.0,"tolerance":0.0})
            )["object_id"],
            1
        );
        let svg_request = json!({"id":id,"revision":state["revision"]});
        let svg = dispatch(handle, 23, &serde_json::to_vec(&svg_request).unwrap()).unwrap();
        let xml = std::str::from_utf8(&svg).unwrap();
        let parsed = roxmltree::Document::parse(xml).unwrap();
        let path = parsed
            .descendants()
            .find(|node| node.has_tag_name("path"))
            .unwrap();
        assert_eq!(path.attribute("id"), Some("object-1"));
        assert_eq!(path.attribute("fill"), Some("#dc2850"));
        let data = path.attribute("d").unwrap();
        assert_eq!(
            data.split_whitespace()
                .filter(|token| *token == "L")
                .count(),
            511
        );
        assert!(data.ends_with("Z ") || data.ends_with('Z'));
        let revision = state["revision"].as_u64().unwrap();
        for request in [
            json!({"type":"vector_object","id":id,"object_id":1,"revision":revision - 1}),
            json!({"type":"vector_object","id":id,"object_id":999,"revision":revision}),
            json!({"type":"state","padding":" ".repeat(MAX_COMMAND_BYTES)}),
            json!({"type":"create_vector","name":"Too large","index":1,"revision":revision,"padding":" ".repeat(MAX_COMMAND_BYTES)}),
            json!({"type":"set_vector_object","id":id,"object_id":1,"object":edited,"revision":revision,"padding":" ".repeat(MAX_VECTOR_COMMAND_BYTES)}),
        ] {
            assert!(dispatch(handle, 0, &serde_json::to_vec(&request).unwrap()).is_err());
        }
        for request in [
            json!({"id":id,"revision":revision - 1}),
            json!({"id":1,"revision":revision}),
            json!({"id":id,"revision":revision,"padding":" ".repeat(MAX_COMMAND_BYTES)}),
        ] {
            assert!(dispatch(handle, 23, &serde_json::to_vec(&request).unwrap()).is_err());
        }
        assert!(dispatch(handle, 0, b"{").is_err());
        assert!(dispatch(handle, 23, b"{}").is_err());
        assert!(dispatch(handle, 3, &[]).unwrap() == before);
        assert_eq!(
            dispatch(handle, 0, br#"{"type":"state"}"#).unwrap(),
            state_bytes
        );
        assert_eq!(dispatch(handle, 2, &[1]).unwrap().len(), 16);
        destroy(handle);
    }

    #[test]
    fn long_vector_canonical_previews_keep_cancelled_source_and_match_committed_pixels() {
        let handle = create(384, 32).unwrap();
        let state = vector_bridge_command(
            handle,
            json!({"type":"create_vector","name":"Vectors","parent_id":null,"index":1}),
        );
        let id = state["active"].as_u64().unwrap() as u32;
        let state = vector_bridge_command(
            handle,
            json!({"type":"add_vector_object","id":id,"object":vector_bridge_path(),"index":null}),
        );
        let original = vector_bridge_tiles(&dispatch(handle, 2, &[1]).unwrap());
        let before = dispatch(handle, 3, &[]).unwrap();
        let state_bytes = dispatch(handle, 0, br#"{"type":"state"}"#).unwrap();
        let mut edited = vector_bridge_command(
            handle,
            json!({"type":"vector_object","id":id,"object_id":1}),
        )["object"]
            .clone();
        edited["transform"] = json!([1.0, 0.0, 0.0, 1.0, 256.0, 0.0]);
        let envelope = |edit: serde_json::Value| {
            json!({
                "id":id,"revision":state["revision"],"selection_id":state["selectionId"],
                "mask_editing":false,"mask_id":null,"action":{"kind":"vector","edit":edit}
            })
        };
        let cancelled = serde_json::to_vec(&envelope(
            json!({"type":"add","object":edited,"index":null}),
        ))
        .unwrap();
        assert!(cancelled.len() > MAX_COMMAND_BYTES && cancelled.len() < MAX_VECTOR_COMMAND_BYTES);
        let cancelled_frame = dispatch(handle, 22, &cancelled).unwrap();
        assert_eq!(
            vector_bridge_pixel(&vector_bridge_tiles(&cancelled_frame), 272, 16),
            [220, 40, 80, 255]
        );
        let request = envelope(json!({"type":"set","object_id":1,"object":edited}));
        let request_bytes = serde_json::to_vec(&request).unwrap();
        assert!(
            request_bytes.len() > MAX_COMMAND_BYTES
                && request_bytes.len() < MAX_VECTOR_COMMAND_BYTES
        );
        let preview = dispatch(handle, 22, &request_bytes).unwrap();
        let changes = vector_bridge_tiles(&preview);
        assert!(changes.contains_key(&(0, 0)) && changes.contains_key(&(2, 0)));
        assert_eq!(vector_bridge_pixel(&changes, 16, 16), [0; 4]);
        assert_eq!(vector_bridge_pixel(&changes, 272, 16), [220, 40, 80, 255]);
        assert!(dispatch(handle, 3, &[]).unwrap() == before);
        assert_eq!(
            dispatch(handle, 0, br#"{"type":"state"}"#).unwrap(),
            state_bytes
        );
        assert_eq!(dispatch(handle, 2, &[1]).unwrap().len(), 16);
        assert_eq!(
            vector_bridge_command(handle, json!({"type":"vector_objects","id":id}))["nextObjectId"],
            2
        );
        let mut invalid = request.clone();
        invalid["action"] = json!({"kind":"translate","dx":1,"dy":0});
        invalid["padding"] = json!(" ".repeat(MAX_COMMAND_BYTES));
        assert!(dispatch(handle, 22, &serde_json::to_vec(&invalid).unwrap()).is_err());
        invalid = request.clone();
        invalid["padding"] = json!(" ".repeat(MAX_VECTOR_COMMAND_BYTES));
        assert!(dispatch(handle, 22, &serde_json::to_vec(&invalid).unwrap()).is_err());
        invalid = request.clone();
        invalid.as_object_mut().unwrap().remove("selection_id");
        assert!(dispatch(handle, 22, &serde_json::to_vec(&invalid).unwrap()).is_err());
        assert!(dispatch(handle, 3, &[]).unwrap() == before);
        assert_eq!(dispatch(handle, 2, &[1]).unwrap().len(), 16);
        let state = vector_bridge_command(
            handle,
            json!({"type":"set_vector_object","id":id,"object_id":1,"object":edited}),
        );
        let mut expected = original.clone();
        expected.extend(changes);
        let mut committed = original;
        committed.extend(vector_bridge_tiles(&dispatch(handle, 2, &[1]).unwrap()));
        for y in 0..32 {
            for x in 0..384 {
                assert_eq!(
                    vector_bridge_pixel(&committed, x, y),
                    vector_bridge_pixel(&expected, x, y),
                    "at {x},{y}"
                );
            }
        }
        assert!(dispatch(handle, 22, &request_bytes).is_err());
        let after = dispatch(handle, 3, &[]).unwrap();
        let state_bytes = dispatch(handle, 0, br#"{"type":"state"}"#).unwrap();
        let mut noop = request;
        noop["revision"] = state["revision"].clone();
        assert_eq!(
            dispatch(handle, 22, &serde_json::to_vec(&noop).unwrap())
                .unwrap()
                .len(),
            16
        );
        vector_bridge_command(
            handle,
            json!({"type":"set_vector_object","id":id,"object_id":1,"object":edited}),
        );
        assert!(dispatch(handle, 3, &[]).unwrap() == after);
        assert_eq!(
            dispatch(handle, 0, br#"{"type":"state"}"#).unwrap(),
            state_bytes
        );
        assert_eq!(dispatch(handle, 2, &[1]).unwrap().len(), 16);
        destroy(handle);
    }

    #[test]
    fn independent_mask_thumbnail_packets_keep_legacy_projection_and_actual_mask_ids() {
        use super::*;
        use serde_json::{json, Value};
        let handle = create(4, 2).unwrap();
        for mode in ["reveal", "hide"] {
            dispatch(
                handle,
                0,
                &serde_json::to_vec(&json!({"type":"add_mask","mode":mode,"name":mode})).unwrap(),
            )
            .unwrap();
        }
        let state = dispatch(handle, 0, br#"{"type":"state"}"#).unwrap();
        let decoded: Value = serde_json::from_slice(&state).unwrap();
        assert_eq!(decoded["maxLayerMasks"], 16);
        assert_eq!(decoded["layers"][0]["masks"].as_array().unwrap().len(), 2);
        let first = decoded["layers"][0]["masks"][0]["id"].as_u64().unwrap() as u32;
        let second = decoded["layers"][0]["masks"][1]["id"].as_u64().unwrap() as u32;
        assert_ne!(first, second);
        assert_eq!(decoded["activeMaskId"], second);
        assert_eq!(decoded["layers"][0]["mask"]["id"], second);
        let saved = dispatch(handle, 3, &[]).unwrap();
        let stack = dispatch(handle, 20, &[1]).unwrap();
        let legacy = dispatch(handle, 20, &[]).unwrap();
        let edge = crate::model::PREVIEW_EDGE as usize;
        let length = edge * edge * 4;
        assert_eq!(stack.len(), 16 + 2 * (4 + length));
        assert_eq!(u32::from_le_bytes(stack[12..16].try_into().unwrap()), 2);
        assert_eq!(u32::from_le_bytes(stack[16..20].try_into().unwrap()), first);
        assert_eq!(
            u32::from_le_bytes(stack[20 + length..24 + length].try_into().unwrap()),
            second
        );
        assert_eq!(&legacy[20..], &stack[24 + length..]);
        assert!(stack[20..20 + length]
            .as_chunks::<4>()
            .0
            .iter()
            .filter(|pixel| pixel[3] == 255)
            .all(|pixel| *pixel == [255; 4]));
        assert!(stack[24 + length..]
            .as_chunks::<4>()
            .0
            .iter()
            .filter(|pixel| pixel[3] == 255)
            .all(|pixel| *pixel == [0, 0, 0, 255]));
        for invalid in [vec![0], vec![2], vec![1, 0]] {
            assert!(dispatch(handle, 20, &invalid).is_err());
        }
        assert_eq!(saved, dispatch(handle, 3, &[]).unwrap());
        assert_eq!(state, dispatch(handle, 0, br#"{"type":"state"}"#).unwrap());
        dispatch(
            handle,
            0,
            &serde_json::to_vec(
                &json!({"type":"set_mask_editing","id":1,"mask_id":first,"enabled":true}),
            )
            .unwrap(),
        )
        .unwrap();
        let selected = dispatch(handle, 20, &[]).unwrap();
        assert_eq!(&selected[20..], &stack[20..20 + length]);
        assert_eq!(stack, dispatch(handle, 20, &[1]).unwrap());
        destroy(handle);
    }

    #[test]
    fn typed_adjustment_creation_parameter_preview_commit_and_baked_copy_cross_the_bridge() {
        use super::*;
        use serde_json::{json, Value};
        let handle = create(4, 2).unwrap();
        dispatch(
            handle,
            0,
            br#"{"type":"fill","x":0,"y":0,"color":[20,40,60,255],"tolerance":0}"#,
        )
        .unwrap();
        let state: Value =
            serde_json::from_slice(&dispatch(handle, 0, br#"{"type":"state"}"#).unwrap()).unwrap();
        let create = json!({"type":"create_adjustment","name":"Live tone","index":1,"revision":state["revision"],"selection_id":state["selectionId"],"settings":{"kind":"tone","brightness":0.0,"contrast":0.0,"saturation":0.0}});
        let state: Value = serde_json::from_slice(
            &dispatch(handle, 0, &serde_json::to_vec(&create).unwrap()).unwrap(),
        )
        .unwrap();
        assert_eq!(state["layers"][1]["kind"], "adjustment");
        assert_eq!(state["layers"][1]["adjustment"]["kind"], "tone");
        dispatch(handle, 2, &[1]).unwrap();
        let before = dispatch(handle, 3, &[]).unwrap();
        let settings =
            json!({"kind":"curves","curves":{"rgb":{"points":[{"x":0,"y":255},{"x":255,"y":0}]}}});
        let request = json!({"id":state["active"],"revision":state["revision"],"selection_id":state["selectionId"],"mask_editing":false,"action":{"kind":"adjustment","settings":settings}});
        let preview = dispatch(handle, 22, &serde_json::to_vec(&request).unwrap()).unwrap();
        assert_eq!(&preview[24..28], &[235, 215, 195, 255]);
        assert_eq!(dispatch(handle, 3, &[]).unwrap(), before);
        assert_eq!(dispatch(handle, 2, &[1]).unwrap().len(), 16);
        dispatch(handle,0,&serde_json::to_vec(&json!({"type":"set_adjustment","id":state["active"],"revision":state["revision"],"settings":settings})).unwrap()).unwrap();
        assert_eq!(dispatch(handle, 2, &[1]).unwrap(), preview);
        for format in ["psd", "ora"] {
            assert!(dispatch(
                handle,
                5,
                &serde_json::to_vec(&json!({"format":format,"transparent":true})).unwrap()
            )
            .is_err());
            assert!(!dispatch(
                handle,
                5,
                &serde_json::to_vec(
                    &json!({"format":format,"transparent":true,"bake_layers":true})
                )
                .unwrap()
            )
            .unwrap()
            .is_empty());
        }
        assert!(dispatch(handle, 22, &serde_json::to_vec(&request).unwrap()).is_err());
        dispatch(handle, 0, br#"{"type":"undo"}"#).unwrap();
        assert_eq!(dispatch(handle, 3, &[]).unwrap(), before);
        destroy(handle);
    }
    use super::*;
    use crate::model::MAX_SELECTION_POINTS;
    use serde_json::json;

    #[test]
    fn canonical_layer_action_bridge_preserves_snapshot_and_dirty_and_validates_payload() {
        let handle = create(16, 16).unwrap();
        dispatch(
            handle,
            0,
            br#"{"type":"fill","x":0,"y":0,"color":[255,0,0,128],"tolerance":0}"#,
        )
        .unwrap();
        dispatch(handle, 0, br#"{"type":"add_layer"}"#).unwrap();
        dispatch(
            handle,
            0,
            br#"{"type":"fill","x":0,"y":0,"color":[0,0,255,255],"tolerance":0}"#,
        )
        .unwrap();
        let state: serde_json::Value =
            serde_json::from_slice(&dispatch(handle, 0, br#"{"type":"state"}"#).unwrap()).unwrap();
        let request = serde_json::to_vec(
            &json!({"type":"set_clipping","id":2,"clipping":true,"revision":state["revision"]}),
        )
        .unwrap();
        let state = dispatch(handle, 0, &request).unwrap();
        let value: serde_json::Value = serde_json::from_slice(&state).unwrap();
        dispatch(handle, 2, &[1]).unwrap();
        let saved = dispatch(handle, 3, &[]).unwrap();
        let request = json!({"id":2,"revision":value["revision"],"selectionId":value["selectionId"],"maskEditing":false,"action":{"kind":"translate","dx":1,"dy":0}});
        let frame = dispatch(handle, 22, &serde_json::to_vec(&request).unwrap()).unwrap();
        assert_eq!(frame.len(), 16 + 8 + crate::model::TILE_BYTES);
        assert_eq!(&frame[24..28], &[128, 0, 0, 128]);
        assert_eq!(&frame[28..32], &[0, 0, 128, 128]);
        assert_eq!(dispatch(handle, 3, &[]).unwrap(), saved);
        assert_eq!(dispatch(handle, 0, br#"{"type":"state"}"#).unwrap(), state);
        assert_eq!(dispatch(handle, 2, &[1]).unwrap().len(), 16);
        let mut invalid = request.clone();
        invalid.as_object_mut().unwrap().remove("selectionId");
        assert!(dispatch(handle, 22, &serde_json::to_vec(&invalid).unwrap()).is_err());
        assert!(dispatch(handle, 22, b"{}").is_err());
        assert!(dispatch(handle, 22, &vec![b' '; MAX_COMMAND_BYTES + 1]).is_err());
        dispatch(
            handle,
            0,
            br#"{"type":"translate_layer","id":2,"dx":1,"dy":0}"#,
        )
        .unwrap();
        assert_eq!(dispatch(handle, 2, &[1]).unwrap(), frame);
        destroy(handle);
    }

    #[test]
    fn complete_indexed_palettes_cross_the_bridge_without_relaxing_unrelated_command_limits() {
        let handle = create(1, 1).unwrap();
        let palette = json!({"colors": (0..=255).map(|alpha| [200,210,220,alpha]).collect::<Vec<_>>(), "transparent":0, "order":(0..=255).collect::<Vec<u8>>()});
        let command = serde_json::to_vec(
            &json!({"type":"new_indexed","width":8,"height":8,"palette":palette}),
        )
        .unwrap();
        assert!(command.len() > MAX_COMMAND_BYTES);
        let state: serde_json::Value =
            serde_json::from_slice(&dispatch(handle, 0, &command).unwrap()).unwrap();
        assert_eq!(state["indexedPalette"], palette);
        let revision = state["revision"].clone();
        let convert = serde_json::to_vec(&json!({"type":"convert_color_mode","mode":"indexed","palette":palette,"revision":revision})).unwrap();
        assert!(convert.len() > MAX_COMMAND_BYTES);
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&dispatch(handle, 0, &convert).unwrap())
                .unwrap(),
            state
        );
        let oversized = serde_json::to_vec(&json!({"type":"new_indexed","width":1,"height":1,"palette":palette,"padding":" ".repeat(MAX_PALETTE_COMMAND_BYTES)})).unwrap();
        assert!(dispatch(handle, 0, &oversized).is_err());
        let unrelated =
            serde_json::to_vec(&json!({"type":"state","padding":" ".repeat(MAX_COMMAND_BYTES)}))
                .unwrap();
        assert!(dispatch(handle, 0, &unrelated).is_err());
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(
                &dispatch(handle, 0, br#"{"type":"state"}"#).unwrap()
            )
            .unwrap(),
            state
        );
        destroy(handle);
    }

    #[test]
    fn move_layer_packets_reject_unknown_options_without_editing_the_project() {
        let handle = create(16, 16).unwrap();
        let saved = dispatch(handle, 3, &[]).unwrap();
        let basic = dispatch(handle, 21, &[]).unwrap();
        assert_eq!(basic, dispatch(handle, 21, &[0]).unwrap());
        assert_eq!(basic, dispatch(handle, 21, &[1]).unwrap());
        assert_eq!(basic.len(), 28);
        assert_eq!(&basic[24..], &[0, 0, 0, 0]);
        for bytes in [&[2][..], &[1, 0][..]] {
            assert!(dispatch(handle, 21, bytes).is_err());
        }
        assert_eq!(dispatch(handle, 3, &[]).unwrap(), saved);
        destroy(handle);
    }

    #[test]
    fn decoding_references_does_not_wait_for_the_canvas_engine_lock() {
        let image = Engine::new(16, 16).unwrap().export_png().unwrap();
        let guard = engines().lock().unwrap();
        let (sender, receiver) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || sender.send(dispatch(0, 18, &image)).unwrap());
        let result = receiver.recv_timeout(std::time::Duration::from_secs(2));
        drop(guard);
        worker.join().unwrap();
        assert_eq!(result.unwrap().unwrap().len(), 16 + 16 * 16 * 4);
    }

    #[test]
    fn brush_previews_do_not_wait_for_the_canvas_engine_lock() {
        let guard = engines().lock().unwrap();
        let (sender, receiver) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            sender.send(dispatch(0, 19, br#"{"size":40,"opacity":1,"hardness":0.9,"color":[0,0,0],"eraser":false,"texture":"graphite"}"#)).unwrap();
        });
        let result = receiver.recv_timeout(std::time::Duration::from_secs(2));
        drop(guard);
        worker.join().unwrap();
        assert_eq!(
            result.unwrap().unwrap().len(),
            (crate::model::BRUSH_PREVIEW_WIDTH * crate::model::BRUSH_PREVIEW_HEIGHT * 4) as usize
        );
        assert!(dispatch(0, 19, b"{}").is_err());
        assert!(dispatch(0, 19, &vec![b' '; MAX_COMMAND_BYTES + 1]).is_err());
    }

    #[test]
    fn frame_options_preserve_alpha_and_reject_unknown_encodings() {
        let handle = create(16, 16).unwrap();
        dispatch(
            handle,
            0,
            br#"{"type":"fill","x":0,"y":0,"color":[100,40,20,100],"tolerance":0}"#,
        )
        .unwrap();
        let saved = dispatch(handle, 3, &[]).unwrap();
        let opaque = dispatch(handle, 2, &[]).unwrap();
        let alpha = dispatch(handle, 2, &[1]).unwrap();
        assert_eq!(opaque[27], 255);
        assert_eq!(alpha[27], 100);
        for invalid in [&[2][..], &[1, 0][..]] {
            assert!(dispatch(handle, 2, invalid).is_err());
        }
        assert_eq!(dispatch(handle, 2, &[1]).unwrap().len(), 16);
        assert_eq!(dispatch(handle, 2, &[0]).unwrap(), opaque);
        assert_eq!(dispatch(handle, 3, &[]).unwrap(), saved);
        destroy(handle);
    }

    #[test]
    fn long_selection_commands_cross_the_bridge_without_relaxing_other_limits() {
        let handle = create(128, 128).unwrap();
        let points = (0..MAX_SELECTION_POINTS)
            .map(|i| {
                let angle = i as f64 * std::f64::consts::TAU / MAX_SELECTION_POINTS as f64;
                json!({"x":64.0 + 60.0 * angle.cos(),"y":64.0 + 60.0 * angle.sin()})
            })
            .collect::<Vec<_>>();
        let bytes = serde_json::to_vec(&json!({"type":"select_shape","selection":{"kind":"lasso","left":0,"top":0,"right":128,"bottom":128,"points":points}})).unwrap();
        assert!(bytes.len() > MAX_COMMAND_BYTES);
        let state: serde_json::Value =
            serde_json::from_slice(&dispatch(handle, 0, &bytes).unwrap()).unwrap();
        assert_eq!(
            state["selection"]["points"].as_array().unwrap().len(),
            MAX_SELECTION_POINTS
        );
        let oversized =
            serde_json::to_vec(&json!({"type":"state","padding":" ".repeat(MAX_COMMAND_BYTES)}))
                .unwrap();
        assert!(dispatch(handle, 0, &oversized).is_err());
        assert!(dispatch(handle, 0, &vec![b' '; MAX_SELECTION_COMMAND_BYTES + 1]).is_err());
        let current: serde_json::Value =
            serde_json::from_slice(&dispatch(handle, 0, b"{\"type\":\"state\"}").unwrap()).unwrap();
        assert_eq!(current, state);
        destroy(handle);
    }

    #[test]
    fn long_lasso_fill_commands_cross_the_bridge_with_bounded_paths() {
        let handle = create(128, 128).unwrap();
        let points = (0..MAX_SELECTION_POINTS)
            .map(|i| {
                let angle = i as f64 * std::f64::consts::TAU / MAX_SELECTION_POINTS as f64;
                json!({"x":64.0 + 60.0 * angle.cos(),"y":64.0 + 60.0 * angle.sin()})
            })
            .collect::<Vec<_>>();
        let bytes = serde_json::to_vec(&json!({
            "type":"fill_lasso", "points":points, "color":[200,40,80],
            "opacity":1.0, "eraser":false
        }))
        .unwrap();
        assert!(bytes.len() > MAX_COMMAND_BYTES);
        let state: serde_json::Value =
            serde_json::from_slice(&dispatch(handle, 0, &bytes).unwrap()).unwrap();
        assert_eq!(state["revision"], 1);
        assert_eq!(state["canUndo"], true);
        assert_eq!(state["selection"], serde_json::Value::Null);
        let oversized = serde_json::to_vec(&json!({
            "type":"fill_lasso", "points":vec![json!({"x":1,"y":1}); MAX_SELECTION_POINTS+1],
            "color":[200,40,80], "opacity":1.0, "eraser":false
        }))
        .unwrap();
        assert!(dispatch(handle, 0, &oversized).is_err());
        let current: serde_json::Value =
            serde_json::from_slice(&dispatch(handle, 0, b"{\"type\":\"state\"}").unwrap()).unwrap();
        assert_eq!(current, state);
        destroy(handle);
    }

    #[test]
    fn forced_selection_outline_masks_match_simple_combined_and_antialiased_coverage() {
        let handle = create(640, 520).unwrap();
        assert_eq!(
            dispatch(handle, 15, &[1]).unwrap(),
            [1u32, 512, 0]
                .into_iter()
                .flat_map(u32::to_le_bytes)
                .collect::<Vec<_>>()
        );
        dispatch(
            handle,
            0,
            br#"{"type":"select","rect":{"left":12,"top":9,"right":610,"bottom":500}}"#,
        )
        .unwrap();
        for command in [
            None,
            Some(br#"{"type":"combine_selection","mode":"subtract","selection":{"left":200,"top":100,"right":400,"bottom":300,"kind":"rectangle"}}"#.as_slice()),
            Some(br#"{"type":"select_shape","selection":{"left":50,"top":20,"right":600,"bottom":510,"kind":"ellipse"}}"#.as_slice()),
        ] {
            if let Some(command) = command {
                dispatch(handle, 0, command).unwrap();
            }
            let state = dispatch(handle, 0, br#"{"type":"state"}"#).unwrap();
            let outline = dispatch(handle, 15, &[]).unwrap();
            assert_eq!(u32::from_le_bytes(outline[..4].try_into().unwrap()), 0);
            assert_eq!(outline, dispatch(handle, 15, &[0]).unwrap());
            let mask = dispatch(handle, 15, &[1]).unwrap();
            assert_eq!(u32::from_le_bytes(mask[..4].try_into().unwrap()), 1);
            let size = u32::from_le_bytes(mask[4..8].try_into().unwrap());
            let count = u32::from_le_bytes(mask[8..12].try_into().unwrap());
            assert_eq!(size, crate::model::SELECTION_PREVIEW_TILE_SIZE);
            assert_eq!(mask.len(), 12 + count as usize * (8 + (size * size) as usize));
            let mut coverage = vec![0; 640 * 520];
            for tile in mask[12..].chunks_exact(8 + (size * size) as usize) {
                let tx = u32::from_le_bytes(tile[..4].try_into().unwrap());
                let ty = u32::from_le_bytes(tile[4..8].try_into().unwrap());
                for y in 0..size.min(520 - ty * size) {
                    for x in 0..size.min(640 - tx * size) {
                        coverage[((ty * size + y) * 640 + tx * size + x) as usize] = tile[8 + (y * size + x) as usize];
                    }
                }
            }
            let map = engines().lock().unwrap();
            let selected = map.get(&handle).unwrap().selection.as_ref().unwrap();
            for y in 0..520 {
                for x in 0..640 {
                    assert_eq!(coverage[(y * 640 + x) as usize], selected.coverage(x, y));
                }
            }
            drop(map);
            for invalid in [&[2][..], &[1, 0][..], &[0, 0][..], &[255][..]] {
                assert!(dispatch(handle, 15, invalid).is_err());
            }
            assert_eq!(dispatch(handle, 0, br#"{"type":"state"}"#).unwrap(), state);
        }
        destroy(handle);
    }
}
