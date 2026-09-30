package app.podor.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

data class ReferencePlacement(val bounds: Rect, val mirrored: Boolean = false) {
    val corners: List<Offset>
        get() = listOf(bounds.topLeft, bounds.topRight, bounds.bottomRight, bounds.bottomLeft)

    companion object {
        fun fit(width: Int, height: Int, document: DocumentInfo): ReferencePlacement {
            val scale =
                minOf(document.width.toFloat() / width, document.height.toFloat() / height) *
                    StudioDefaults.referenceInitialFraction
            val size = Size(width * scale, height * scale)
            return ReferencePlacement(
                Rect(
                    Offset(
                        document.width / 2f - size.width / 2f,
                        document.height / 2f - size.height / 2f,
                    ),
                    size,
                )
            )
        }
    }
}

class ReferenceGesture(
    val before: ReferencePlacement,
    private val start: Offset,
    private val corner: Int?,
) {
    fun update(point: Offset): ReferencePlacement {
        if (corner == null) return before.copy(bounds = before.bounds.translate(point - start))
        val anchor = before.corners[(corner + 2) % 4]
        val diagonal = before.corners[corner] - anchor
        val delta = point - start
        val factor =
            (1f + (delta.x * diagonal.x + delta.y * diagonal.y) / diagonal.getDistanceSquared())
                .coerceIn(StudioDefaults.referenceMinZoom, StudioDefaults.referenceMaxZoom)
        val opposite = anchor + diagonal * factor
        return before.copy(
            bounds =
                Rect(
                    minOf(anchor.x, opposite.x),
                    minOf(anchor.y, opposite.y),
                    maxOf(anchor.x, opposite.x),
                    maxOf(anchor.y, opposite.y),
                )
        )
    }
}
