package app.podor.domain

import kotlinx.serialization.Serializable

@Serializable
data class GradientMapStop(val position: Float, val color: List<Int>) {
    val argb: Long
        get() =
            0xFF000000L or
                (color[0].toLong() shl 16) or
                (color[1].toLong() shl 8) or
                color[2].toLong()

    fun valid() =
        position.isFinite() && position in 0f..1f && color.size == 3 && color.all { it in 0..255 }

    fun withColor(argb: Long) =
        copy(
            color =
                listOf(
                    (argb shr 16 and 255).toInt(),
                    (argb shr 8 and 255).toInt(),
                    (argb and 255).toInt(),
                )
        )
}

@Serializable
data class GradientMapSettings(
    val stops: List<GradientMapStop> =
        listOf(GradientMapStop(0f, listOf(0, 0, 0)), GradientMapStop(1f, listOf(255, 255, 255)))
) {
    fun valid() =
        stops.size in 2..StudioDefaults.maxGradientMapStops &&
            stops.all { it.valid() } &&
            stops.zipWithNext().all { (a, b) -> a.position < b.position }
}
