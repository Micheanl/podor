package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SelectionKind(val label: String) {
    @SerialName("rectangle") Rectangle("矩形选区"),
    @SerialName("ellipse") Ellipse("椭圆选区"),
    @SerialName("lasso") Lasso("自由套索"),
    @SerialName("wand") MagicWand("魔棒选区"),
}

@Serializable
enum class SelectionMode(val label: String) {
    @SerialName("replace") Replace("新建选区"),
    @SerialName("add") Add("添加到选区"),
    @SerialName("subtract") Subtract("从选区减去"),
    @SerialName("intersect") Intersect("与选区相交"),
}

@Serializable
enum class SelectionRefinement(val label: String) {
    @SerialName("expand") Expand("扩展选区"),
    @SerialName("contract") Contract("收缩选区"),
    @SerialName("smooth") Smooth("平滑选区"),
    @SerialName("feather") Feather("羽化选区"),
}

@Serializable data class SelectionPoint(val x: Float, val y: Float)

@Serializable
data class Selection(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val kind: SelectionKind = SelectionKind.Rectangle,
    val points: List<SelectionPoint> = emptyList(),
    val id: Long = 0,
    val combined: Boolean = false,
    val raster: Boolean = false,
    val empty: Boolean = false,
)

class SelectionGesture(
    val kind: SelectionKind,
    val start: Offset,
    private val width: Int,
    private val height: Int,
    private var spacing: Float,
) {
    var end = start
        private set

    private val path = arrayListOf(SelectionPoint(start.x, start.y))
    private var constrained = false
    val points: List<SelectionPoint>
        get() = path

    fun add(point: Offset, constrain: Boolean = false) {
        if (!point.x.isFinite() || !point.y.isFinite()) return
        if (kind == SelectionKind.Lasso) {
            val limit = StudioDefaults.maxDimension * 2f
            end = Offset(point.x.coerceIn(-limit, limit), point.y.coerceIn(-limit, limit))
            val last = path.last()
            if ((end - Offset(last.x, last.y)).getDistanceSquared() < spacing * spacing) return
            if (path.size >= StudioDefaults.maxSelectionPoints - 1) {
                var target = 1
                for (index in 2 until path.size step 2) path[target++] = path[index]
                path.subList(target, path.size).clear()
                spacing *= 2f
            }
            path.add(SelectionPoint(end.x, end.y))
        } else {
            constrained = constrain
            var delta = point - start
            if (constrain) {
                val side =
                    minOf(
                        maxOf(abs(delta.x), abs(delta.y)),
                        if (delta.x < 0) start.x else width - start.x,
                        if (delta.y < 0) start.y else height - start.y,
                    )
                delta = Offset(if (delta.x < 0) -side else side, if (delta.y < 0) -side else side)
            }
            end =
                Offset(
                    (start.x + delta.x).coerceIn(0f, width.toFloat()),
                    (start.y + delta.y).coerceIn(0f, height.toFloat()),
                )
        }
    }

    fun selection(): Selection? {
        if (kind == SelectionKind.MagicWand) return null
        val samples =
            if (kind == SelectionKind.Lasso) path + SelectionPoint(end.x, end.y)
            else listOf(SelectionPoint(start.x, start.y), SelectionPoint(end.x, end.y))
        if (kind == SelectionKind.Lasso && samples.distinct().size < 3) return null
        var left = floor(samples.minOf { it.x }).toInt().coerceIn(0, width)
        var top = floor(samples.minOf { it.y }).toInt().coerceIn(0, height)
        var right = ceil(samples.maxOf { it.x }).toInt().coerceIn(0, width)
        var bottom = ceil(samples.maxOf { it.y }).toInt().coerceIn(0, height)
        if (constrained) {
            val side = minOf(right - left, bottom - top)
            if (end.x < start.x) left = right - side else right = left + side
            if (end.y < start.y) top = bottom - side else bottom = top + side
        }
        if (left >= right || top >= bottom || (kind != SelectionKind.Lasso && start == end))
            return null
        return Selection(
            left,
            top,
            right,
            bottom,
            kind,
            if (kind == SelectionKind.Lasso) samples else emptyList(),
        )
    }
}
