package app.podor.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportTest {
    @Test
    fun zoomKeepsAnchorUnderPointer() {
        val document = DocumentInfo()
        val view = Size(1000f, 800f)
        val anchor = Offset(250f, 320f)
        val start = Viewport(pan = Offset(23f, -18f))
        val before = start.toDocument(anchor, view, document)
        val after =
            start
                .transform(anchor, Offset.Zero, 1.5f, view, document)
                .toDocument(anchor, view, document)
        assertEquals(before.x, after.x, 0.001f)
        assertEquals(before.y, after.y, 0.001f)
    }

    @Test
    fun zoomIsBoundedAndPanUsesScreenPixels() {
        val next =
            Viewport()
                .transform(
                    Offset(500f, 400f),
                    Offset(80f, 10f),
                    100f,
                    Size(1000f, 800f),
                    DocumentInfo(),
                )
        assertEquals(StudioDefaults.maxZoom, next.zoom)
        assertEquals(Offset(80f, 10f), next.pan)
        assertTrue(next.scale(Size(1000f, 800f), DocumentInfo()) > 0)
    }
}
