package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.test.*

class GradientTest {
    @Test
    fun draggingOutsideTheCanvasKeepsBothEndpointsWithinEngineLimits() {
        val limit = StudioDefaults.maxGradientCoordinate
        val drag = GradientGesture(null, Offset(-limit * 2, 0f), GradientHandle.New)
        for (snap in listOf(false, true)) {
            val line = drag.update(Offset(limit * 2, limit * 2), snap)
            assertTrue(line.valid())
            assertEquals(-limit, line.start.x)
        }
    }

    @Test
    fun endpointsStayAnchoredAndSnapWithoutChangingPreviousDraft() {
        val original = GradientLine(Offset(20f, 30f), Offset(90f, 120f))
        val drag = GradientGesture(original, original.end, GradientHandle.End)
        val next = drag.update(Offset(100f, 68f), true)
        assertEquals(original.start, next.start)
        val delta = next.end - next.start
        assertEquals(30f, atan2(delta.y, delta.x) * 180f / PI.toFloat(), 0.01f)
        val from =
            GradientGesture(original, original.start, GradientHandle.Start)
                .update(Offset(10f, 15f), false)
        assertEquals(original.end, from.end)
        assertEquals(Offset(10f, 15f), from.start)
        assertTrue(next.valid())
        assertFalse(GradientLine(Offset.Zero, Offset.Zero).valid())
        val color = GradientSettings(from = 0xFFCC3366, to = 0xFF22BB88, transparent = true)
        assertEquals(0x00CC3366L, color.endColor)
        assertEquals(color.startColor, color.copy(reversed = true).endColor)
        val migrated =
            Preferences(shortcuts = mapOf(ShortcutAction.Brush to Shortcut("G", shift = true)))
                .withNewShortcuts()
        assertTrue(migrated.valid())
        assertEquals(Shortcut("G", shift = true), migrated.shortcut(ShortcutAction.Brush))
    }
}
