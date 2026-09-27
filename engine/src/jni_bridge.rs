use crate::ffi;
use jni::{
    objects::{JByteArray, JClass},
    sys::{jbyteArray, jint, jlong},
    JNIEnv,
};
use std::panic::{catch_unwind, AssertUnwindSafe};

#[no_mangle]
pub extern "system" fn Java_app_podor_engine_NativeBridge_create(
    mut env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
) -> jlong {
    match catch_unwind(|| ffi::create(width as u32, height as u32))
        .unwrap_or_else(|_| Err("引擎初始化失败".into()))
    {
        Ok(handle) => handle as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_podor_engine_NativeBridge_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(|| ffi::destroy(handle as u64));
}

#[no_mangle]
pub extern "system" fn Java_app_podor_engine_NativeBridge_call(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    operation: jint,
    input: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let length = env.get_array_length(&input).map_err(|e| e.to_string())?;
        if length > 256 * 1024 * 1024 {
            return Err("请求超出大小限制".into());
        }
        let bytes = env.convert_byte_array(input).map_err(|e| e.to_string())?;
        ffi::dispatch(handle as u64, operation as u32, &bytes)
    }))
    .unwrap_or_else(|_| Err("引擎内部错误".into()));
    match result {
        Ok(bytes) => match env.byte_array_from_slice(&bytes) {
            Ok(array) => array.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            std::ptr::null_mut()
        }
    }
}
