package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.min

data class VectorNode(val segment: Int, val point: Int, val position: Offset)

fun VectorGeometry.nodes(): List<VectorNode> =
    when (this) {
        is VectorGeometry.Rectangle ->
            listOf(VectorNode(0, 0, Offset(x, y)), VectorNode(0, 1, Offset(x + width, y + height)))
        is VectorGeometry.Ellipse ->
            listOf(
                VectorNode(0, 0, Offset(cx, cy)),
                VectorNode(0, 1, Offset(cx + rx, cy)),
                VectorNode(0, 2, Offset(cx, cy + ry)),
            )
        is VectorGeometry.Line ->
            listOf(VectorNode(0, 0, Offset(x1, y1)), VectorNode(0, 1, Offset(x2, y2)))
        is VectorGeometry.Path ->
            segments.flatMapIndexed { segment, value ->
                value.points().mapIndexed { point, position ->
                    VectorNode(segment, point, position)
                }
            }
    }

fun VectorGeometry.withNode(node: VectorNode, point: Offset): VectorGeometry =
    when (this) {
        is VectorGeometry.Rectangle -> {
            val other = if (node.point == 0) Offset(x + width, y + height) else Offset(x, y)
            copy(
                x = min(point.x, other.x),
                y = min(point.y, other.y),
                width = abs(point.x - other.x),
                height = abs(point.y - other.y),
            )
        }
        is VectorGeometry.Ellipse ->
            when (node.point) {
                0 -> copy(cx = point.x, cy = point.y)
                1 -> copy(rx = abs(point.x - cx))
                else -> copy(ry = abs(point.y - cy))
            }
        is VectorGeometry.Line ->
            if (node.point == 0) copy(x1 = point.x, y1 = point.y)
            else copy(x2 = point.x, y2 = point.y)
        is VectorGeometry.Path ->
            copy(
                segments =
                    segments.mapIndexed { index, segment ->
                        if (index == node.segment) segment.withPoint(node.point, point) else segment
                    }
            )
    }

fun vectorShape(tool: VectorEditorTool, start: Offset, end: Offset): VectorGeometry? {
    val left = min(start.x, end.x)
    val top = min(start.y, end.y)
    val width = abs(end.x - start.x)
    val height = abs(end.y - start.y)
    return when (tool) {
        VectorEditorTool.Rectangle -> VectorGeometry.Rectangle(left, top, width, height)
        VectorEditorTool.Ellipse ->
            VectorGeometry.Ellipse(left + width / 2f, top + height / 2f, width / 2f, height / 2f)
        VectorEditorTool.Line -> VectorGeometry.Line(start.x, start.y, end.x, end.y)
        else -> null
    }
}

class VectorPenGesture {
    private data class Anchor(val point: Offset, val tangent: Offset = Offset.Zero)

    private val anchors = mutableListOf<Anchor>()

    fun begin(point: Offset) {
        anchors.add(Anchor(point))
    }

    fun drag(point: Offset) {
        val anchor = anchors.lastOrNull() ?: return
        anchors[anchors.lastIndex] = anchor.copy(tangent = point - anchor.point)
    }

    fun geometry(): VectorGeometry.Path =
        VectorGeometry.Path(
            anchors.mapIndexed { index, anchor ->
                if (index == 0) VectorSegment.Move(anchor.point.x, anchor.point.y)
                else {
                    val previous = anchors[index - 1]
                    val outgoing = previous.point + previous.tangent
                    val incoming = anchor.point - anchor.tangent
                    VectorSegment.Cubic(
                        outgoing.x,
                        outgoing.y,
                        incoming.x,
                        incoming.y,
                        anchor.point.x,
                        anchor.point.y,
                    )
                }
            }
        )
}
