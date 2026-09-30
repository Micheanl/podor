package app.podor.engine

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer

actual fun rgbaBitmap(bytes: ByteArray, offset: Int, size: Int, height: Int): ImageBitmap {
    val pixels =
        IntArray(size * height) { index ->
            val start = offset + index * 4
            ((bytes[start + 3].toInt() and 255) shl 24) or
                ((bytes[start].toInt() and 255) shl 16) or
                ((bytes[start + 1].toInt() and 255) shl 8) or
                (bytes[start + 2].toInt() and 255)
        }
    return Bitmap.createBitmap(pixels, size, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

actual fun alphaBitmap(bytes: ByteArray, offset: Int, size: Int): ImageBitmap =
    Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        .apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes, offset, size * size)) }
        .asImageBitmap()
