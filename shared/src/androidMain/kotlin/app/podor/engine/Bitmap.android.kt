package app.podor.engine

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun rgbaBitmap(bytes: ByteArray, offset: Int, size: Int): ImageBitmap {
    val pixels =
        IntArray(size * size) { index ->
            val start = offset + index * 4
            ((bytes[start + 3].toInt() and 255) shl 24) or
                ((bytes[start].toInt() and 255) shl 16) or
                ((bytes[start + 1].toInt() and 255) shl 8) or
                (bytes[start + 2].toInt() and 255)
        }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
