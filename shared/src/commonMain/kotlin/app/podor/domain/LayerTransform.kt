package app.podor.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LayerTransform(
    val width: Int,
    val height: Int,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val angle: Float = 0f,
    @SerialName("flip_x") val flipX: Boolean = false,
    @SerialName("flip_y") val flipY: Boolean = false,
    val filter: ResampleFilter = StudioDefaults.resampleFilter,
) {
    fun center(source: Rect) = source.center + Offset(dx, dy)

    fun point(source: Rect, x: Float, y: Float) =
        center(source) + Offset(x * width / 2f, y * height / 2f).rotate(angle)

    fun local(source: Rect, point: Offset) = (point - center(source)).rotate(-angle)

    fun sourcePoint(source: Rect, point: Offset): Offset {
        val local = local(source, point)
        return source.center +
            Offset(
                local.x * source.width / width * if (flipX) -1f else 1f,
                local.y * source.height / height * if (flipY) -1f else 1f,
            )
    }

    fun valid() =
        validCanvasSize(width, height) &&
            dx.isFinite() &&
            dy.isFinite() &&
            abs(dx) <= StudioDefaults.maxTransformOffset &&
            abs(dy) <= StudioDefaults.maxTransformOffset &&
            angle.isFinite() &&
            abs(angle) <= 180f
}

enum class TransformHandle(val x: Float = 0f, val y: Float = 0f) {
    TopLeft(-1f, -1f),
    Top(0f, -1f),
    TopRight(1f, -1f),
    Right(1f, 0f),
    BottomRight(1f, 1f),
    Bottom(0f, 1f),
    BottomLeft(-1f, 1f),
    Left(-1f, 0f),
    Rotate,
    Move;

    fun position(source: Rect, value: LayerTransform, rotationGap: Float) =
        if (this == Rotate)
            value.point(source, 0f, -1f) + Offset(0f, -rotationGap).rotate(value.angle)
        else value.point(source, x, y)
}

fun transformHandle(
    source: Rect,
    value: LayerTransform,
    point: Offset,
    radius: Float,
    rotationGap: Float,
): TransformHandle? {
    val handles = TransformHandle.entries.filter { it != TransformHandle.Move }
    val closest = handles.minBy {
        (it.position(source, value, rotationGap) - point).getDistanceSquared()
    }
    if ((closest.position(source, value, rotationGap) - point).getDistance() <= radius)
        return closest
    val local = value.local(source, point)
    return if (abs(local.x) <= value.width / 2f && abs(local.y) <= value.height / 2f)
        TransformHandle.Move
    else null
}

class TransformGesture(
    val source: Rect,
    val initial: LayerTransform,
    val handle: TransformHandle,
    val start: Offset,
) {
    fun update(point: Offset, proportional: Boolean, snapRotation: Boolean): LayerTransform {
        if (!point.x.isFinite() || !point.y.isFinite() || point == start) return initial
        if (handle == TransformHandle.Move) {
            val delta = point - start
            return initial.copy(
                dx = (initial.dx + delta.x).limitedOffset(),
                dy = (initial.dy + delta.y).limitedOffset(),
            )
        }
        if (handle == TransformHandle.Rotate) {
            val a = start - initial.center(source)
            val b = point - initial.center(source)
            var angle = initial.angle + (atan2(b.y, b.x) - atan2(a.y, a.x)) * 180f / PI.toFloat()
            if (snapRotation)
                angle =
                    (angle / StudioDefaults.rotationStep).roundToInt() * StudioDefaults.rotationStep
            return initial.copy(angle = normalizeTransformAngle(angle))
        }
        val anchor = initial.point(source, -handle.x, -handle.y)
        val grip = initial.point(source, handle.x, handle.y)
        val delta = (point + grip - start - anchor).rotate(-initial.angle)
        var width =
            if (handle.x == 0f) initial.width.toFloat() else (delta.x * handle.x).coerceAtLeast(1f)
        var height =
            if (handle.y == 0f) initial.height.toFloat() else (delta.y * handle.y).coerceAtLeast(1f)
        if (proportional) {
            val ratio =
                when {
                    handle.x == 0f -> height / initial.height
                    handle.y == 0f -> width / initial.width
                    else ->
                        (width * initial.width + height * initial.height) /
                            (initial.width.toFloat() * initial.width +
                                initial.height.toFloat() * initial.height)
                }.coerceAtLeast(max(1f / initial.width, 1f / initial.height))
            width = initial.width * ratio
            height = initial.height * ratio
        }
        val limit =
            minOf(
                1f,
                StudioDefaults.maxDimension / width,
                StudioDefaults.maxDimension / height,
                sqrt(StudioDefaults.maxCanvasPixels.toFloat() / (width * height)),
            )
        val w = (width * limit).roundToInt().coerceIn(1, StudioDefaults.maxDimension)
        val h =
            (height * limit)
                .roundToInt()
                .coerceIn(
                    1,
                    min(StudioDefaults.maxDimension, (StudioDefaults.maxCanvasPixels / w).toInt()),
                )
        val center = anchor + Offset(handle.x * w / 2f, handle.y * h / 2f).rotate(initial.angle)
        return initial.copy(
            width = w,
            height = h,
            dx = (center.x - source.center.x).limitedOffset(),
            dy = (center.y - source.center.y).limitedOffset(),
        )
    }
}

fun normalizeTransformAngle(angle: Float) = ((angle + 180f) % 360f + 360f) % 360f - 180f

private fun Float.limitedOffset() =
    coerceIn(-StudioDefaults.maxTransformOffset, StudioDefaults.maxTransformOffset)

private fun Offset.rotate(angle: Float): Offset {
    val radians = angle * PI.toFloat() / 180f
    val s = sin(radians)
    val c = cos(radians)
    return Offset(x * c - y * s, x * s + y * c)
}
