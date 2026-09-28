use crate::{
    model::{Sample, MAX_COMMAND_BYTES, MAX_SELECTION_COMMAND_BYTES},
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
    let mut map = engines().lock().map_err(|_| "引擎状态异常")?;
    let engine = map.get_mut(&handle).ok_or("画布已关闭")?;
    match operation {
        17 => {
            if !bytes.is_empty() {
                return Err("曲线直方图请求无效".into());
            }
            Ok(engine.curve_histogram())
        }
        16 => {
            if bytes.len() > MAX_COMMAND_BYTES {
                return Err("命令过长".into());
            }
            let request = serde_json::from_slice(bytes).map_err(|_| "调整参数无效")?;
            engine.preview_adjustment(request)
        }
        15 => Ok(engine.selection_outline()),
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
            if bytes.len() > MAX_SELECTION_COMMAND_BYTES {
                return Err("命令过长".into());
            }
            let command = serde_json::from_slice(bytes).map_err(|_| "命令格式无效")?;
            if bytes.len() > MAX_COMMAND_BYTES
                && !matches!(
                    command,
                    Command::SelectShape { .. } | Command::CombineSelection { .. }
                )
            {
                return Err("命令过长".into());
            }
            Ok(engine.command(command)?.to_string().into_bytes())
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
        8 => Ok(engine.layer_frame()),
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
            let bounds = engine.layer_bounds()?;
            Ok([bounds.left, bounds.top, bounds.right, bounds.bottom]
                .into_iter()
                .flat_map(u32::to_le_bytes)
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
    use super::*;
    use crate::model::MAX_SELECTION_POINTS;
    use serde_json::json;

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
}
