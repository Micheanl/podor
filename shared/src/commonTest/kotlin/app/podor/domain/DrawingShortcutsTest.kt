package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.test.*

class DrawingShortcutsTest {
    @Test
    fun symbolShortcutsRemainValidAndResolveSavedConflicts() {
        assertTrue(Preferences().valid())
        for (key in listOf("+", "-", "[", "]")) assertTrue(Shortcut(key).valid())
        val preferences =
            Preferences(shortcuts = mapOf(ShortcutAction.Brush to Shortcut("+", true)))
                .withNewShortcuts()
        assertEquals(Shortcut("+", true), preferences.shortcut(ShortcutAction.Brush))
        assertNotEquals(
            preferences.shortcut(ShortcutAction.Brush),
            preferences.shortcut(ShortcutAction.ZoomIn),
        )
        assertTrue(preferences.valid())
    }

    @Test
    fun zoomKeepsCenterAnchorAndClampsWithoutChangingOrientation() {
        val view = Viewport(2f, Offset(30f, -20f), 45f, true)
        val zoomed = view.zoomBy(2f)
        assertEquals(4f, zoomed.zoom)
        assertEquals(Offset(60f, -40f), zoomed.pan)
        assertEquals(view, zoomed.zoomBy(0.5f))
        assertEquals(StudioDefaults.maxZoom, view.zoomBy(100f).zoom)
        assertEquals(StudioDefaults.minZoom, view.zoomBy(0.001f).zoom)
        assertEquals(45f, zoomed.rotation)
        assertTrue(zoomed.mirrored)
    }
}
