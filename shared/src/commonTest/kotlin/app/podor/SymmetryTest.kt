package app.podor

import androidx.compose.ui.geometry.Offset
import app.podor.domain.*
import kotlin.test.*

class SymmetryTest {
    @Test
    fun oddCanvasAxesAndCopiesMatchPixelCentersWithoutDuplicatingTheOrigin() {
        val doc = DocumentInfo(width = 257, height = 129)
        val settings = SymmetrySettings(SymmetryMode.Quadrant, x = 0.25f, y = 0.25f)
        assertEquals(Offset(64.5f, 32.5f), settings.axis(doc))
        val points = mutableListOf<Offset>()
        settings.forEachPoint(Offset(5.5f, 12.5f), doc) { points.add(it) }
        assertEquals(
            listOf(
                Offset(5.5f, 12.5f),
                Offset(123.5f, 12.5f),
                Offset(5.5f, 52.5f),
                Offset(123.5f, 52.5f),
            ),
            points,
        )
        points.clear()
        settings.forEachPoint(settings.axis(doc), doc) { points.add(it) }
        assertEquals(listOf(settings.axis(doc)), points)
        points.clear()
        settings.copy(mode = SymmetryMode.Off).forEachPoint(Offset(-5f, 30f), doc) {
            points.add(it)
        }
        assertEquals(listOf(Offset(-5f, 30f)), points)
    }
}
