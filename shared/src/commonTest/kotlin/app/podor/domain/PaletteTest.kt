package app.podor.domain

import kotlin.test.*
import kotlinx.serialization.json.Json

class PaletteTest {
    @Test
    fun colorsRoundTripAndOldPreferencesKeepAnEmptyPersonalPalette() {
        assertEquals(emptyList(), Json.decodeFromString<Preferences>("{}").palette)
        val value = Preferences(palette = listOf(0xFF000000, 0xFF902040, 0xFFFFFFFF))
        assertTrue(value.valid())
        assertEquals(value, Json.decodeFromString<Preferences>(Json.encodeToString(value)))
        assertFalse(value.copy(palette = listOf(0x00FFFFFF)).valid())
        assertFalse(value.copy(palette = listOf(-1L)).valid())
        assertFalse(value.copy(palette = listOf(0xFF000000, 0xFF000000)).valid())
        assertFalse(
            value
                .copy(palette = List(StudioDefaults.maxPaletteColors + 1) { 0xFF000000L + it })
                .valid()
        )
    }
}
