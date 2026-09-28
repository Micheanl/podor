package app.podor.engine

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual fun rgbaBitmap(bytes: ByteArray, offset: Int, size: Int): ImageBitmap =
    Image.makeRaster(
            ImageInfo(size, size, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
            bytes.copyOfRange(offset, offset + size * size * 4),
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
