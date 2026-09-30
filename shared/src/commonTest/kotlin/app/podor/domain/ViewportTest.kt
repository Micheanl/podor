package app.podor.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportTest {
    @Test
    fun rotatedAndMirroredCoordinatesRoundTrip() {
        val document = DocumentInfo(width = 384, height = 256)
        val view = Size(761f, 693f)
        for (angle in listOf(-180f, -125f, -90f, -35f, 0f, 37f, 90f, 180f)) {
            for (mirrored in listOf(false, true)) {
                val viewport = Viewport(1.7f, Offset(47f, -35f), angle, mirrored)
                for (point in listOf(Offset.Zero, Offset(384f, 256f), Offset(131.25f, 77.5f))) {
                    val restored =
                        viewport.toDocument(viewport.toView(point, view, document), view, document)
                    assertEquals(point.x, restored.x, 0.001f)
                    assertEquals(point.y, restored.y, 0.001f)
                }
            }
        }
        val rotated = Viewport(rotation = 90f)
        val center = rotated.toView(Offset(192f, 128f), view, document)
        val right = rotated.toView(Offset(202f, 128f), view, document)
        assertEquals(center.x, right.x, 0.001f)
        assertTrue(right.y > center.y)
        assertTrue(
            rotated.copy(mirrored = true).toView(Offset(202f, 128f), view, document).y < center.y
        )
    }

    @Test
    fun simultaneousRotationZoomAndPanKeepThePreviousCentroidAnchored() {
        val document = DocumentInfo(width = 768, height = 512)
        val view = Size(920f, 720f)
        val anchor = Offset(351f, 209f)
        val delta = Offset(-51f, 93f)
        for (mirrored in listOf(false, true)) {
            val start = Viewport(7f, Offset(33f, -20f), 153f, mirrored)
            val original = start.toDocument(anchor, view, document)
            val next = start.transform(anchor, delta, 2f, view, document, degrees = 78f)
            val current = next.toDocument(anchor + delta, view, document)
            assertEquals(original.x, current.x, 0.001f)
            assertEquals(original.y, current.y, 0.001f)
            assertEquals(StudioDefaults.maxZoom, next.zoom)
            assertEquals(-129f, next.rotation)
            assertEquals(mirrored, next.mirrored)
        }
        assertEquals(-15f, Viewport().rotateBy(-3615f).rotation)
        assertEquals(15f, Viewport().rotateBy(3615f).rotation)
    }

    @Test
    fun visibleTileBoundsContainRotatedCanvasIncludingInspectorMargins() {
        val document = DocumentInfo(width = 768, height = 512)
        val view = Size(600f, 720f)
        val clip = Size(920f, 720f)
        for (angle in listOf(-135f, -90f, -15f, 0f, 35f, 90f, 177f)) {
            for (mirrored in listOf(false, true)) {
                val viewport = Viewport(3f, Offset(70f, -110f), angle, mirrored)
                val visible = viewport.visibleBounds(view, document, clip)
                for (x in 1 until clip.width.toInt() step 17) {
                    for (y in 1 until clip.height.toInt() step 19) {
                        val point =
                            viewport.toDocument(Offset(x.toFloat(), y.toFloat()), view, document)
                        if (point.x in 0f..768f && point.y in 0f..512f) {
                            assertTrue(
                                visible.contains(point),
                                "Missing visible point $point at $angle/$mirrored",
                            )
                        }
                    }
                }
            }
        }
        assertTrue(Viewport(pan = Offset(10000f, 10000f)).visibleBounds(view, document).isEmpty)
    }

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
