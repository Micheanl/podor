package app.podor.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class Viewport(
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero,
    val rotation: Float = 0f,
    val mirrored: Boolean = false,
) {
    private val radians = rotation * (PI.toFloat() / 180f)
    private val cosine = cos(radians)
    private val sine = sin(radians)
    val horizontalSign: Float
        get() = if (mirrored) -1f else 1f

    fun scale(view: Size, document: DocumentInfo): Float =
        minOf(view.width / document.width, view.height / document.height) * 0.84f * zoom

    fun origin(view: Size, document: DocumentInfo): Offset = toView(Offset.Zero, view, document)

    fun toView(point: Offset, view: Size, document: DocumentInfo): Offset {
        val local =
            (point - Offset(document.width / 2f, document.height / 2f)) * scale(view, document)
        val x = local.x * horizontalSign
        return Offset(x * cosine - local.y * sine, x * sine + local.y * cosine) +
            Offset(view.width / 2f, view.height / 2f) +
            pan
    }

    fun toDocument(point: Offset, view: Size, document: DocumentInfo): Offset {
        val local = point - Offset(view.width / 2f, view.height / 2f) - pan
        return Offset(
            (local.x * cosine + local.y * sine) * horizontalSign,
            -local.x * sine + local.y * cosine,
        ) / scale(view, document) + Offset(document.width / 2f, document.height / 2f)
    }

    fun visibleBounds(view: Size, document: DocumentInfo, clip: Size = view): Rect {
        val a = toDocument(Offset.Zero, view, document)
        val b = toDocument(Offset(clip.width, 0f), view, document)
        val c = toDocument(Offset(clip.width, clip.height), view, document)
        val d = toDocument(Offset(0f, clip.height), view, document)
        return Rect(
            maxOf(0f, minOf(a.x, b.x, c.x, d.x)),
            maxOf(0f, minOf(a.y, b.y, c.y, d.y)),
            minOf(document.width.toFloat(), maxOf(a.x, b.x, c.x, d.x)),
            minOf(document.height.toFloat(), maxOf(a.y, b.y, c.y, d.y)),
        )
    }

    fun zoomBy(factor: Float): Viewport {
        val next = (zoom * factor).coerceIn(StudioDefaults.minZoom, StudioDefaults.maxZoom)
        return copy(zoom = next, pan = pan * (next / zoom))
    }

    fun rotateBy(degrees: Float) =
        copy(rotation = ((rotation + degrees + 180f) % 360f + 360f) % 360f - 180f)

    fun transform(
        centroid: Offset,
        delta: Offset,
        factor: Float,
        view: Size,
        document: DocumentInfo,
        degrees: Float = 0f,
    ): Viewport {
        val nextZoom = (zoom * factor).coerceIn(StudioDefaults.minZoom, StudioDefaults.maxZoom)
        val center = Offset(view.width / 2, view.height / 2)
        val anchor = centroid - center - pan
        val radians = degrees * (PI.toFloat() / 180f)
        val cosine = cos(radians)
        val sine = sin(radians)
        val rotated =
            Offset(anchor.x * cosine - anchor.y * sine, anchor.x * sine + anchor.y * cosine)
        val nextPan = centroid - center - rotated * (nextZoom / zoom) + delta
        return rotateBy(degrees).copy(zoom = nextZoom, pan = nextPan)
    }
}
