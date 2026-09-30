package app.podor.domain

import app.podor.engine.engineBrushJson
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

class PixelWorkflowTest {
    @Test
    fun pixelPresetsHaveOnePixelHardEdgesAndNoPressureOrStabilization() {
        for (preset in listOf(BrushPreset.PixelPencil, BrushPreset.PixelPerfect)) {
            assertTrue(preset.valid())
            assertEquals(1f, preset.size)
            assertEquals(1f, preset.hardness)
            assertEquals(1f, preset.opacity)
            assertEquals(0f, preset.sizePressure)
            assertEquals(0f, preset.stabilization)
            val payload = engineBrushJson(BrushSettings(preset = preset))
            assertEquals(preset.raster.engineName, payload.getValue("raster").jsonPrimitive.content)
        }
        assertEquals(BrushRaster.Pixel, BrushPreset.PixelPencil.raster)
        assertEquals(BrushRaster.PixelPerfect, BrushPreset.PixelPerfect.raster)
    }

    @Test
    fun oldBrushesRemainAntialiasedAndPixelModeSurvivesPackExport() {
        val old = Json.decodeFromString<BrushPreset>(
            """{"id":"old","label":"Old","hardness":1,"opacity":1,"size":1}"""
        )
        assertEquals(BrushRaster.Antialiased, old.raster)
        val pack = BrushPack("pixel", "Pixel", brushes = listOf(BrushPreset.PixelPerfect))
        assertEquals(pack, BrushPack.parse(Json.encodeToString(pack).encodeToByteArray()))
    }

    @Test
    fun gridSettingsArePersistentDisplayOptionsAndRejectInvalidDimensions() {
        val old = Json.decodeFromString<Preferences>("{}")
        assertEquals(CanvasGridSettings(), old.canvasGrid)
        val settings = CanvasGridSettings(pixels = true, tiles = true, tileWidth = 24, tileHeight = 16)
        val preferences = old.copy(canvasGrid = settings)
        assertTrue(preferences.valid())
        assertEquals(preferences, Json.decodeFromString<Preferences>(Json.encodeToString(preferences)))
        for (size in listOf(-1, 0, StudioDefaults.maxDimension + 1, Int.MAX_VALUE)) {
            assertFalse(preferences.copy(canvasGrid = settings.copy(tileWidth = size)).valid())
            assertFalse(preferences.copy(canvasGrid = settings.copy(tileHeight = size)).valid())
        }
    }

    @Test
    fun newPixelShortcutsPreserveOlderBindingsWithoutConflicts() {
        assertFailsWith<IllegalArgumentException> {
            Preferences().assign(ShortcutAction.Brush, Shortcut("P"))
        }
        val previous = Preferences(shortcuts = mapOf(
            ShortcutAction.Brush to Shortcut("P"),
            ShortcutAction.Eraser to Shortcut("G", command = true),
        ))
        val migrated = previous.withNewShortcuts()
        assertTrue(migrated.valid())
        previous.shortcuts.forEach { (action, binding) -> assertEquals(binding, migrated.shortcut(action)) }
        assertEquals(migrated, migrated.withNewShortcuts())
        assertNotEquals(Shortcut("P"), migrated.shortcut(ShortcutAction.PixelPencil))
        assertNotEquals(Shortcut("G", command = true), migrated.shortcut(ShortcutAction.PixelGrid))
    }
}
