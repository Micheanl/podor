package app.podor.ui.input

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.IntSize

internal actual fun createCanvasPointerIcon(image: ByteArray, sizePx: Int): PointerIcon? {
    val source = BitmapFactory.decodeByteArray(image, 0, image.size)
    val bitmap = Bitmap.createScaledBitmap(source, sizePx, sizePx, true)
    if (source !== bitmap) source.recycle()
    val hotspot = canvasPointerHotspot(IntSize(sizePx, sizePx))
    return PointerIcon(android.view.PointerIcon.create(bitmap, hotspot.x, hotspot.y))
}
