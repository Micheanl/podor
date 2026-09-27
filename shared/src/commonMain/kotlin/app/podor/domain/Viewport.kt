package app.podor.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

data class Viewport(val zoom: Float = 1f, val pan: Offset = Offset.Zero) {
    fun scale(view: Size, document: DocumentInfo): Float =
        minOf(view.width / document.width, view.height / document.height) * 0.84f * zoom

    fun origin(view: Size, document: DocumentInfo): Offset {
        val scale = scale(view, document)
        return Offset(
            (view.width - document.width * scale) / 2,
            (view.height - document.height * scale) / 2,
        ) + pan
    }

    fun toDocument(point: Offset, view: Size, document: DocumentInfo): Offset =
        (point - origin(view, document)) / scale(view, document)

    fun transform(
        centroid: Offset,
        delta: Offset,
        factor: Float,
        view: Size,
        document: DocumentInfo,
    ): Viewport {
        val nextZoom = (zoom * factor).coerceIn(StudioDefaults.minZoom, StudioDefaults.maxZoom)
        val center = Offset(view.width / 2, view.height / 2)
        val nextPan = centroid - center - (centroid - center - pan) * (nextZoom / zoom) + delta
        return copy(zoom = nextZoom, pan = nextPan)
    }
}
