package app.podor.domain

import kotlin.math.abs
import kotlin.math.roundToInt

data class HsvColor(val hue: Float, val saturation: Float, val brightness: Float) {
    fun toArgb(): Long {
        val chroma = brightness * saturation
        val sector = ((hue % 360 + 360) % 360) / 60
        val secondary = chroma * (1 - abs(sector % 2 - 1))
        val rgb =
            when (sector.toInt()) {
                0 -> floatArrayOf(chroma, secondary, 0f)
                1 -> floatArrayOf(secondary, chroma, 0f)
                2 -> floatArrayOf(0f, chroma, secondary)
                3 -> floatArrayOf(0f, secondary, chroma)
                4 -> floatArrayOf(secondary, 0f, chroma)
                else -> floatArrayOf(chroma, 0f, secondary)
            }
        val offset = brightness - chroma
        fun channel(index: Int) =
            ((rgb[index] + offset) * 255).roundToInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    companion object {
        fun fromArgb(color: Long): HsvColor {
            val red = (color shr 16 and 255) / 255f
            val green = (color shr 8 and 255) / 255f
            val blue = (color and 255) / 255f
            val maximum = maxOf(red, green, blue)
            val delta = maximum - minOf(red, green, blue)
            val hue =
                when {
                    delta == 0f -> 0f
                    maximum == red -> 60 * ((green - blue) / delta % 6)
                    maximum == green -> 60 * ((blue - red) / delta + 2)
                    else -> 60 * ((red - green) / delta + 4)
                }
            return HsvColor((hue + 360) % 360, if (maximum == 0f) 0f else delta / maximum, maximum)
        }
    }
}
