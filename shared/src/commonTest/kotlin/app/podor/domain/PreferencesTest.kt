package app.podor.domain

import kotlin.test.*
import kotlinx.serialization.json.Json

class PreferencesTest {
    @Test
    fun addingMoveToolPreservesOlderCustomShortcuts() {
        val old = Preferences(shortcuts = mapOf(
            ShortcutAction.Brush to Shortcut("V"),
            ShortcutAction.Eraser to Shortcut("V", shift = true),
            ShortcutAction.Picker to Shortcut("V", alt = true),
        ), language = Language.English)
        val restored = Json.decodeFromString<Preferences>(Json.encodeToString(old)).withMoveShortcut()
        assertTrue(restored.valid())
        old.shortcuts.forEach { (action, key) -> assertEquals(key, restored.shortcut(action)) }
        assertEquals(Language.English, restored.language)
        assertEquals(restored, restored.withMoveShortcut())
        assertEquals(Shortcut("V"), Preferences().withMoveShortcut().shortcut(ShortcutAction.MoveLayer))
        val assigned = Preferences().assign(ShortcutAction.MoveLayer, Shortcut("T", command = true))
        assertEquals(assigned, assigned.withMoveShortcut())
    }

    @Test
    fun shortcutConflictsAreRejectedAndCustomKeysSurviveSerialization() {
        val original = Preferences()
        assertFailsWith<IllegalArgumentException> {
            original.assign(ShortcutAction.Brush, Shortcut("E"))
        }
        val changed =
            original
                .assign(ShortcutAction.Brush, Shortcut("P", shift = true))
                .copy(language = Language.English)
        val restored = Json.decodeFromString<Preferences>(Json.encodeToString(changed))
        assertTrue(restored.valid())
        assertEquals(Shortcut("P", shift = true), restored.shortcut(ShortcutAction.Brush))
        assertEquals(Language.English, restored.language)
    }

    @Test
    fun brushPackRejectsInvalidGeometryDuplicateIdsAndUnknownPayload() {
        val pack = BrushPack("artist.ink", "Artist ink", brushes = listOf(BrushPreset.Ink))
        assertEquals(pack, BrushPack.parse(Json.encodeToString(pack).encodeToByteArray()))
        for (invalid in
            listOf(
                pack.copy(version = 99),
                pack.copy(brushes = listOf(BrushPreset.Ink.copy(aspect = 0f))),
                pack.copy(brushes = listOf(BrushPreset.Ink.copy(stabilization = -0.1f))),
                pack.copy(brushes = listOf(BrushPreset.Ink.copy(stabilization = 1.1f))),
                pack.copy(brushes = listOf(BrushPreset.Ink, BrushPreset.Ink)),
            )) {
            assertFailsWith<IllegalArgumentException> {
                BrushPack.parse(Json.encodeToString(invalid).encodeToByteArray())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            BrushPack.parse(
                """{"id":"x","name":"x","brushes":[],"script":"run"}""".encodeToByteArray()
            )
        }
        assertFalse(Preferences(brushes = listOf(BrushPreset.Ink)).valid())
        val custom = BrushPreset.Ink.copy(id = "custom-1")
        assertFalse(Preferences(brushes = listOf(custom, custom)).valid())
    }

    @Test
    fun stabilizationSurvivesBrushPackAndPreferencesRoundTrips() {
        val brush =
            BrushPreset.Ink.copy(id = "custom-1", stabilization = 0.65f, followDirection = true)
        val preferences = Preferences(brushes = listOf(brush))
        assertEquals(
            preferences,
            Json.decodeFromString<Preferences>(Json.encodeToString(preferences)),
        )
        val pack = BrushPack("artist.liner", "Liner", brushes = listOf(brush))
        assertEquals(pack, BrushPack.parse(Json.encodeToString(pack).encodeToByteArray()))
        assertFalse(brush.copy(stabilization = Float.NaN).valid())
        assertFalse(brush.copy(stabilization = Float.POSITIVE_INFINITY).valid())
        val legacy =
            Json.decodeFromString<BrushPreset>(
                """{"id":"old","label":"Old brush","hardness":1.0,"opacity":1.0,"size":12.0}"""
            )
        assertFalse(legacy.followDirection)
    }

    @Test
    fun customCanvasValidatesAreaDimensionsAndMissingInput() {
        assertTrue(validCanvasSize(4096, 4096))
        assertTrue(validCanvasSize(8192, 2048))
        assertFalse(validCanvasSize(8192, 8192))
        assertFalse(validCanvasSize(0, 100))
        assertFalse(validCanvasSize(null, 100))
        assertFalse(validCanvasSize(Int.MAX_VALUE, 2))
    }

    @Test
    fun bundledBrushesHaveDistinctAndValidParameters() {
        assertEquals(14, BrushPreset.entries.size)
        assertTrue(BrushPreset.entries.all { it.valid() })
        assertEquals(14, BrushPreset.entries.map { it.id }.distinct().size)
        assertTrue(BrushPreset.entries.any { it.tip == BrushTip.Flat })
        assertTrue(BrushPreset.entries.any { it.grain > 0f })
        assertTrue(BrushPreset.entries.any { it.stabilization > 0f })
        assertEquals(3, BrushPreset.entries.count { it.followDirection })
    }
}
