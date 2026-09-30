package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

enum class VectorEditorTool(val label: String) {
    Rectangle("矩形"),
    Ellipse("椭圆"),
    Line("直线"),
    Pen("钢笔路径"),
    Edit("编辑节点"),
}

@Serializable data class VectorLayerInfo(val objectCount: Int = 0, val nextObjectId: Int = 1)

@Serializable
data class VectorObjectSummary(
    val id: Int,
    val name: String,
    val kind: String,
    val visible: Boolean,
    val bounds: List<Float>? = null,
)

@Serializable
data class VectorObjects(
    val id: Int,
    val revision: Long,
    val nextObjectId: Int,
    val objects: List<VectorObjectSummary>,
)

@Serializable
data class VectorObjectResult(
    val id: Int,
    @SerialName("object_id") val objectId: Int,
    val revision: Long,
    val `object`: VectorObjectSpec,
)

@Serializable
data class VectorObjectSpec(
    val name: String,
    val visible: Boolean = true,
    val geometry: VectorGeometry,
    val transform: List<Float> = listOf(1f, 0f, 0f, 1f, 0f, 0f),
    val style: VectorStyle,
) {
    fun request(): JsonObject = parameters.encodeToJsonElement(this).jsonObject

    fun worldPoint(point: Offset) =
        Offset(
            transform[0] * point.x + transform[2] * point.y + transform[4],
            transform[1] * point.x + transform[3] * point.y + transform[5],
        )

    fun localPoint(point: Offset): Offset {
        val determinant = transform[0] * transform[3] - transform[1] * transform[2]
        val x = point.x - transform[4]
        val y = point.y - transform[5]
        return Offset(
            (transform[3] * x - transform[2] * y) / determinant,
            (-transform[1] * x + transform[0] * y) / determinant,
        )
    }

    fun valid() =
        name.isNotBlank() &&
            name.length <= 60 &&
            transform.size == 6 &&
            transform.all { it.isFinite() } &&
            abs(transform[0] * transform[3] - transform[1] * transform[2]) > 0f &&
            style.valid() &&
            geometry.valid()

    companion object {
        private val parameters = Json { encodeDefaults = true }
    }
}

@Serializable
data class VectorStyle(
    val fill: List<Int>? = null,
    val stroke: VectorStroke? = null,
    @SerialName("fill_rule") val fillRule: VectorFillRule = VectorFillRule.NonZero,
) {
    fun valid() =
        (fill != null || stroke != null) &&
            (fill == null || fill.validRgba()) &&
            (stroke == null || stroke.valid())
}

@Serializable
data class VectorStroke(
    val color: List<Int>,
    val width: Float,
    val cap: VectorCap = VectorCap.Round,
    val join: VectorJoin = VectorJoin.Round,
    @SerialName("miter_limit") val miterLimit: Float = StudioDefaults.vectorMiterLimit,
) {
    fun valid() =
        color.validRgba() &&
            width.isFinite() &&
            width > 0f &&
            miterLimit.isFinite() &&
            miterLimit > 0f
}

@Serializable
enum class VectorFillRule {
    @SerialName("non_zero") NonZero,
    @SerialName("even_odd") EvenOdd,
}

@Serializable
enum class VectorCap {
    @SerialName("butt") Butt,
    @SerialName("round") Round,
    @SerialName("square") Square,
}

@Serializable
enum class VectorJoin {
    @SerialName("miter") Miter,
    @SerialName("round") Round,
    @SerialName("bevel") Bevel,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface VectorGeometry {
    @Serializable
    @SerialName("rect")
    data class Rectangle(val x: Float, val y: Float, val width: Float, val height: Float) :
        VectorGeometry

    @Serializable
    @SerialName("ellipse")
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : VectorGeometry

    @Serializable
    @SerialName("line")
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : VectorGeometry

    @Serializable
    @SerialName("path")
    data class Path(val segments: List<VectorSegment>) : VectorGeometry

    fun valid(): Boolean =
        when (this) {
            is Rectangle ->
                listOf(x, y, width, height).all { it.isFinite() } && width > 0f && height > 0f
            is Ellipse -> listOf(cx, cy, rx, ry).all { it.isFinite() } && rx > 0f && ry > 0f
            is Line -> listOf(x1, y1, x2, y2).all { it.isFinite() } && (x1 != x2 || y1 != y2)
            is Path -> {
                var contour = false
                segments.any { it !is VectorSegment.Move && it != VectorSegment.Close } &&
                    segments.all { segment ->
                        val allowed = segment is VectorSegment.Move || contour
                        contour =
                            when (segment) {
                                is VectorSegment.Move -> true
                                VectorSegment.Close -> false
                                else -> contour
                            }
                        allowed && segment.points().all { it.x.isFinite() && it.y.isFinite() }
                    }
            }
        }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface VectorSegment {
    @Serializable @SerialName("move_to") data class Move(val x: Float, val y: Float) : VectorSegment

    @Serializable @SerialName("line_to") data class Line(val x: Float, val y: Float) : VectorSegment

    @Serializable
    @SerialName("quad_to")
    data class Quadratic(val cx: Float, val cy: Float, val x: Float, val y: Float) : VectorSegment

    @Serializable
    @SerialName("cubic_to")
    data class Cubic(
        val c1x: Float,
        val c1y: Float,
        val c2x: Float,
        val c2y: Float,
        val x: Float,
        val y: Float,
    ) : VectorSegment

    @Serializable @SerialName("close") data object Close : VectorSegment

    fun points(): List<Offset> =
        when (this) {
            is Move -> listOf(Offset(x, y))
            is Line -> listOf(Offset(x, y))
            is Quadratic -> listOf(Offset(x, y), Offset(cx, cy))
            is Cubic -> listOf(Offset(x, y), Offset(c1x, c1y), Offset(c2x, c2y))
            Close -> emptyList()
        }

    fun withPoint(index: Int, point: Offset): VectorSegment =
        when (this) {
            is Move -> copy(x = point.x, y = point.y)
            is Line -> copy(x = point.x, y = point.y)
            is Quadratic ->
                if (index == 0) copy(x = point.x, y = point.y) else copy(cx = point.x, cy = point.y)
            is Cubic ->
                when (index) {
                    0 -> copy(x = point.x, y = point.y)
                    1 -> copy(c1x = point.x, c1y = point.y)
                    else -> copy(c2x = point.x, c2y = point.y)
                }
            Close -> this
        }
}

fun vectorRgba(color: Long) =
    listOf(
        (color shr 16 and 255).toInt(),
        (color shr 8 and 255).toInt(),
        (color and 255).toInt(),
        (color shr 24 and 255).toInt(),
    )

private fun List<Int>.validRgba() = size == 4 && all { it in 0..255 }
