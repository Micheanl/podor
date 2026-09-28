package app.podor.domain

import kotlin.test.*
import kotlinx.serialization.json.Json

class BrushFavoritesTest {
    @Test
    fun legacyPreferencesAndDisabledPacksRetainFavoritesWithoutIdCollisions() {
        assertTrue(Json.decodeFromString<Preferences>("{}").favoriteBrushes.isEmpty())
        val custom = BrushPreset.Ink.copy(id = "custom-1", label = "My ink")
        val pack = BrushPack("studio", "Studio", brushes = listOf(BrushPreset.Ink), enabled = false)
        val preferences =
            Preferences(
                brushes = listOf(custom),
                plugins = listOf(pack),
                favoriteBrushes = setOf("ink", "custom-1", "plugin:studio/ink"),
            )
        assertTrue(preferences.valid())
        assertEquals(
            preferences,
            Json.decodeFromString<Preferences>(Json.encodeToString(preferences)),
        )
        assertEquals(preferences, preferences.withAvailableBrushFavorites())
        assertEquals(
            setOf("ink"),
            preferences
                .copy(brushes = emptyList(), plugins = emptyList())
                .withAvailableBrushFavorites()
                .favoriteBrushes,
        )
        assertFalse(preferences.copy(favoriteBrushes = setOf("unknown")).valid())
    }

    @Test
    fun replacedPackKeepsOnlySurvivingBrushFavorites() {
        val pack =
            BrushPack("studio", "Studio", brushes = listOf(BrushPreset.Ink, BrushPreset.Marker))
        val before =
            Preferences(
                plugins = listOf(pack),
                favoriteBrushes = setOf("ink", "plugin:studio/ink", "plugin:studio/marker"),
            )
        val updated =
            before
                .copy(plugins = listOf(pack.copy(brushes = listOf(BrushPreset.Marker))))
                .withAvailableBrushFavorites()
        assertTrue(updated.valid())
        assertEquals(setOf("ink", "plugin:studio/marker"), updated.favoriteBrushes)
    }
}
