package app.podor.domain

import kotlin.test.*

class HsvColorTest {
    @Test
    fun paletteSurvivesRoundTrip() {
        (StudioDefaults.palette +
                listOf(0xFF000000, 0xFF808080, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF))
            .forEach { assertEquals(it, HsvColor.fromArgb(it).toArgb()) }
    }

    @Test
    fun hueWrapsAndBrightnessControlsBlack() {
        assertEquals(HsvColor(0f, 1f, 1f).toArgb(), HsvColor(360f, 1f, 1f).toArgb())
        assertEquals(0xFF000000, HsvColor(310f, 0.8f, 0f).toArgb())
        assertEquals(0xFFFFFFFF, HsvColor(310f, 0f, 1f).toArgb())
    }
}
