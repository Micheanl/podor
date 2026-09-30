package app.podor.domain

import app.podor.engine.engineBrushJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

class BrushMaterialTest {
    @Test
    fun addingLassoFillPreservesAnExistingLShortcut() {
        val previous = Preferences(shortcuts = mapOf(ShortcutAction.Brush to Shortcut("L")))
        val migrated = previous.withNewShortcuts()
        assertEquals(Shortcut("L"), migrated.shortcut(ShortcutAction.Brush))
        assertTrue(migrated.shortcut(ShortcutAction.LassoFill) != Shortcut("L"))
        assertTrue(migrated.valid())
        assertEquals(migrated, migrated.withNewShortcuts())
        assertEquals(Shortcut("L"), Preferences().shortcut(ShortcutAction.LassoFill))
    }

    @Test
    fun olderBrushPacksKeepTheirOriginalSmoothNibAndMaterialsSurviveExport() {
        val old =
            BrushPack.parse(
                """{"id":"old","name":"Old","brushes":[{"id":"custom-1","label":"Ink","hardness":1,"opacity":1,"size":12}]}"""
                    .encodeToByteArray()
            )
        assertEquals(BrushTexture.Smooth, old.brushes.single().texture)
        val brushes =
            BrushTexture.entries.mapIndexed { index, texture ->
                old.brushes.single().copy(id = "custom-$index", texture = texture)
            }
        val pack = old.copy(brushes = brushes)
        assertEquals(pack, BrushPack.parse(Json.encodeToString(pack).encodeToByteArray()))
        val preferences = Preferences(brushes = brushes)
        assertTrue(preferences.valid())
        assertEquals(
            preferences,
            Json.decodeFromString<Preferences>(Json.encodeToString(preferences)),
        )
    }

    @Test
    fun paintingAndPreviewPayloadsCarryTheChosenMaterialWithoutDroppingBrushSettings() {
        val preset =
            BrushPreset.entries
                .first { it.id == "willow" }
                .copy(texture = BrushTexture.DryBristle, grain = 0.65f, pressureCurve = -0.2f)
        val payload = engineBrushJson(BrushSettings(preset, 73f, 0.42f, 0xFF345678))
        assertEquals("dry_bristle", payload.getValue("texture").jsonPrimitive.content)
        assertEquals("leaf", payload.getValue("tip").jsonPrimitive.content)
        assertEquals("73.0", payload.getValue("size").jsonPrimitive.content)
        assertEquals("0.42", payload.getValue("opacity").jsonPrimitive.content)
        assertEquals("0.65", payload.getValue("grain").jsonPrimitive.content)
        assertEquals("-0.2", payload.getValue("pressure_curve").jsonPrimitive.content)
        assertEquals("true", payload.getValue("follow_direction").jsonPrimitive.content)
        assertEquals("[52,86,120]", payload.getValue("color").toString())
    }
}
