package app.podor.domain

import kotlin.test.*
import kotlinx.serialization.json.Json

class SelectionModeTest {
    @Test
    fun magicWandShortcutPreservesExistingUserBinding() {
        val old = Preferences(shortcuts = mapOf(ShortcutAction.Brush to Shortcut("W")))
        val updated = old.withNewShortcuts()
        assertTrue(updated.valid())
        assertEquals(Shortcut("W"), updated.shortcut(ShortcutAction.Brush))
        assertNotEquals(Shortcut("W"), updated.shortcut(ShortcutAction.MagicWand))
        assertEquals(updated, updated.withNewShortcuts())
    }

    @Test
    fun legacyShapesAndCompoundSelectionStateRoundTrip() {
        val legacy =
            Json.decodeFromString<Selection>("""{"left":1,"top":2,"right":10,"bottom":20}""")
        assertFalse(legacy.combined)
        assertFalse(legacy.empty)
        assertEquals(0L, legacy.id)
        val combined = legacy.copy(combined = true, empty = true, id = 41)
        assertEquals(combined, Json.decodeFromString<Selection>(Json.encodeToString(combined)))
        assertEquals("\"subtract\"", Json.encodeToString(SelectionMode.Subtract))
    }

    @Test
    fun inversionShortcutDoesNotReplaceAnExistingUserBinding() {
        val binding = ShortcutAction.InvertSelection.default
        val old = Preferences(shortcuts = mapOf(ShortcutAction.Picker to binding))
        val updated = old.withNewShortcuts()
        assertTrue(updated.valid())
        assertEquals(binding, updated.shortcut(ShortcutAction.Picker))
        assertNotEquals(binding, updated.shortcut(ShortcutAction.InvertSelection))
    }
}
