@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.podor.engine

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.podor.ffi.*
import kotlinx.cinterop.*
import org.jetbrains.skia.*

actual fun createNativeEngine(width: Int, height: Int): NativeEngine {
    val handle = podor_create(width.toUInt(), height.toUInt())
    check(handle != 0uL) { "绘图引擎初始化失败" }
    return object : NativeEngine {
        private var closed = false

        override fun call(operation: Int, input: ByteArray): ByteArray {
            check(!closed) { "画布已关闭" }
            val result =
                if (input.isEmpty()) podor_call(handle, operation.toUInt(), null, 0u)
                else
                    input.usePinned {
                        podor_call(
                            handle,
                            operation.toUInt(),
                            it.addressOf(0).reinterpret(),
                            input.size.toULong(),
                        )
                    }
            try {
                return result.useContents {
                    val bytes = data?.readBytes(length.toInt()) ?: byteArrayOf()
                    check(error == 0u) { bytes.decodeToString() }
                    bytes
                }
            } finally {
                podor_free(result)
            }
        }

        override fun close() {
            if (!closed) {
                closed = true
                podor_destroy(handle)
            }
        }
    }
}

actual fun rgbaBitmap(bytes: ByteArray, offset: Int, size: Int, height: Int): ImageBitmap =
    Image.makeRaster(
            ImageInfo(size, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
            bytes.copyOfRange(offset, offset + size * height * 4),
            size * 4,
        )
        .toComposeImageBitmap()

actual fun alphaBitmap(bytes: ByteArray, offset: Int, size: Int): ImageBitmap =
    Image.makeRaster(
            ImageInfo(size, size, ColorType.ALPHA_8, ColorAlphaType.PREMUL),
            bytes.copyOfRange(offset, offset + size * size),
            size,
        )
        .toComposeImageBitmap()
