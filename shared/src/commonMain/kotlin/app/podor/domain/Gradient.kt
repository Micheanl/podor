package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

enum class GradientShape(val label: String) {
    Linear("线性渐变"),
    Radial("径向渐变"),
}

data class GradientSettings(
    val shape: GradientShape = GradientShape.Linear,
    val from: Long = StudioDefaults.brushColor,
    val to: Long = StudioDefaults.gradientEndColor,
    val transparent: Boolean = false,
    val opacity: Float = StudioDefaults.gradientOpacity,
    val reversed: Boolean = false,
) {
    private val terminalColor
        get() = if (transparent) from and 0xFFFFFFL else to

    val startColor
        get() = if (reversed) terminalColor else from

    val endColor
        get() = if (reversed) from else terminalColor
}

data class GradientLine(val start: Offset, val end: Offset) {
    fun valid() =
        listOf(start.x, start.y, end.x, end.y).all {
            it.isFinite() && abs(it) <= StudioDefaults.maxGradientCoordinate
        } && (end - start).getDistance() >= StudioDefaults.gradientMinLength
}

enum class GradientHandle {
    Start,
    End,
    New,
}

class GradientGesture(val before: GradientLine?, val anchor: Offset, val handle: GradientHandle) {
    fun update(point: Offset, snap: Boolean): GradientLine {
        fun bounded(value: Offset) =
            Offset(
                value.x.coerceIn(
                    -StudioDefaults.maxGradientCoordinate,
                    StudioDefaults.maxGradientCoordinate,
                ),
                value.y.coerceIn(
                    -StudioDefaults.maxGradientCoordinate,
                    StudioDefaults.maxGradientCoordinate,
                ),
            )
        var p = bounded(point)
        val origin =
            when (handle) {
                GradientHandle.Start -> before!!.end
                GradientHandle.End -> before!!.start
                GradientHandle.New -> bounded(anchor)
            }
        if (snap) {
            val delta = p - origin
            val angle =
                (atan2(delta.y, delta.x) * 180f / PI.toFloat() / StudioDefaults.rotationStep)
                    .roundToInt() * StudioDefaults.rotationStep * PI.toFloat() / 180f
            p = bounded(origin + Offset(cos(angle), sin(angle)) * delta.getDistance())
        }
        return if (handle == GradientHandle.Start) GradientLine(p, origin)
        else GradientLine(origin, p)
    }
}
