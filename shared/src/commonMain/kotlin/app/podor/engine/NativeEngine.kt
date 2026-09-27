package app.podor.engine

import androidx.compose.ui.graphics.ImageBitmap

interface NativeEngine {
    fun call(operation: Int, input: ByteArray = byteArrayOf()): ByteArray

    fun close()
}

expect fun createNativeEngine(width: Int, height: Int): NativeEngine

expect fun rgbaBitmap(bytes: ByteArray, offset: Int, size: Int): ImageBitmap

object EngineOperation {
    const val COMMAND = 0
    const val SAMPLES = 1
    const val FRAME = 2
    const val SAVE = 3
    const val LOAD = 4
    const val EXPORT_IMAGE = 5
    const val PREVIEWS = 6
    const val THUMBNAIL = 7
}
