package app.podor.domain

import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ResampleFilter(val wireName: String, val label: String, val description: String) {
    @SerialName("lanczos3")
    Lanczos3("lanczos3", "平滑", "适合绘画与照片"),
    @SerialName("nearest")
    Nearest("nearest", "像素", "保留像素硬边"),
}

data class ImageSize(
    val originalWidth: Int,
    val originalHeight: Int,
    val width: String = originalWidth.toString(),
    val height: String = originalHeight.toString(),
    val locked: Boolean = StudioDefaults.imageSizeLocked,
    val filter: ResampleFilter = StudioDefaults.resampleFilter,
) {
    val valid
        get() = validCanvasSize(width.toIntOrNull(), height.toIntOrNull())

    val changed
        get() = width.toIntOrNull() != originalWidth || height.toIntOrNull() != originalHeight

    fun withWidth(value: String): ImageSize {
        val cleaned = value.filter(Char::isDigit).take(5)
        return copy(
            width = cleaned,
            height = if (locked) proportional(cleaned, originalWidth, originalHeight) else height,
        )
    }

    fun withHeight(value: String): ImageSize {
        val cleaned = value.filter(Char::isDigit).take(5)
        return copy(
            height = cleaned,
            width = if (locked) proportional(cleaned, originalHeight, originalWidth) else width,
        )
    }

    fun withLock(value: Boolean): ImageSize =
        copy(locked = value).let { if (value) it.withWidth(width) else it }

    fun scaled(percent: Int) =
        copy(
            width = (originalWidth * percent / 100.0).roundToInt().coerceAtLeast(1).toString(),
            height = (originalHeight * percent / 100.0).roundToInt().coerceAtLeast(1).toString(),
        )

    private fun proportional(value: String, from: Int, to: Int): String =
        value.toIntOrNull()?.let {
            if (it == 0) "0"
            else (it.toDouble() * to / from).roundToInt().coerceAtLeast(1).toString()
        } ?: ""
}
