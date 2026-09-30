package app.podor

import androidx.compose.ui.geometry.Offset
import app.podor.domain.*
import kotlin.math.sin
import kotlin.test.*
import kotlinx.serialization.json.Json

class SelectionTest {
    @Test
    fun shapeBoundsClampAndShiftKeepsEqualSidesInEveryDirection() {
        for (kind in listOf(SelectionKind.Rectangle, SelectionKind.Ellipse)) {
            val gesture = SelectionGesture(kind, Offset(40f, 30f), 64, 48, 1f)
            gesture.add(Offset(90f, 60f))
            assertEquals(Selection(40, 30, 64, 48, kind), gesture.selection())
            gesture.add(Offset(90f, 60f), true)
            assertEquals(Selection(40, 30, 58, 48, kind), gesture.selection())
            gesture.add(Offset(20f, 20f), true)
            assertEquals(Selection(20, 10, 40, 30, kind), gesture.selection())
            gesture.add(Offset(20f, 30f), true)
            assertEquals(Selection(22, 30, 40, 48, kind), gesture.selection())
            gesture.add(Offset(40f, 30f))
            assertNull(gesture.selection())
            val fractional = SelectionGesture(kind, Offset(12.95f, 10.05f), 64, 48, 1f)
            fractional.add(Offset(35.4f, 18f), true)
            val circle = assertNotNull(fractional.selection())
            assertEquals(circle.right - circle.left, circle.bottom - circle.top)
        }
    }

    @Test
    fun lassoSamplingIsBoundedKeepsEndpointsAndIgnoresInvalidCoordinates() {
        val gesture = SelectionGesture(SelectionKind.Lasso, Offset(12f, 14f), 8192, 2048, 0.75f)
        repeat(50_000) { i ->
            gesture.add(Offset(i % 8192f, 1024f + sin(i * 0.1f) * 1000f))
            assertTrue(gesture.points.size < StudioDefaults.maxSelectionPoints)
        }
        gesture.add(Offset(-30f, 2200f))
        val selection = assertNotNull(gesture.selection())
        assertEquals(SelectionPoint(12f, 14f), selection.points.first())
        assertEquals(SelectionPoint(-30f, 2200f), selection.points.last())
        assertEquals(0, selection.left)
        assertEquals(2048, selection.bottom)
        gesture.add(Offset(Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(selection, gesture.selection())
        assertEquals(selection, Json.decodeFromString<Selection>(Json.encodeToString(selection)))
    }

    @Test
    fun tinyGesturesClearAndLegacyRectanglesStillDecode() {
        val gesture = SelectionGesture(SelectionKind.Lasso, Offset(10f, 10f), 64, 48, 1f)
        assertNull(gesture.selection())
        gesture.add(Offset(30f, 20f))
        assertNull(gesture.selection())
        gesture.add(Offset(10f, 30f))
        assertEquals(SelectionKind.Lasso, gesture.selection()?.kind)
        assertEquals(
            Selection(1, 2, 30, 40),
            Json.decodeFromString<Selection>("""{"left":1,"top":2,"right":30,"bottom":40}"""),
        )
    }
}
