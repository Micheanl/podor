package app.podor.domain

import kotlin.test.*

class ColorHarmonyTest {
    @Test
    fun saturatedRedProducesTheFiveStandardHueArrangements() {
        val expected =
            mapOf(
                ColorHarmony.Complementary to listOf(0xFFFF0000L, 0xFF00FFFFL),
                ColorHarmony.SplitComplementary to listOf(0xFFFF0000L, 0xFF00FF80L, 0xFF0080FFL),
                ColorHarmony.Analogous to listOf(0xFFFF0000L, 0xFFFF0080L, 0xFFFF8000L),
                ColorHarmony.Triadic to listOf(0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL),
                ColorHarmony.Tetradic to listOf(0xFFFF0000L, 0xFFFFFF00L, 0xFF00FFFFL, 0xFF0000FFL),
            )
        expected.forEach { (mode, colors) ->
            assertEquals(colors, mode.colors(0xFFFF0000L), mode.name)
        }
    }

    @Test
    fun hueWrapsWithoutClampingAcrossTheRedBoundary() {
        val positive = HsvColor(359f, 0.8f, 0.7f)
        val negative = positive.copy(hue = -1f)
        ColorHarmony.entries.forEach { mode ->
            assertEquals(mode.colors(positive), mode.colors(negative), mode.name)
            assertEquals(
                mode.colors(HsvColor(0f, 1f, 1f)),
                mode.colors(HsvColor(360f, 1f, 1f)),
                mode.name,
            )
            assertEquals(
                mode.colors(HsvColor(Float.MAX_VALUE % 360f, 1f, 1f)),
                mode.colors(HsvColor(Float.MAX_VALUE, 1f, 1f)),
                mode.name,
            )
        }
        val analogous = ColorHarmony.Analogous.colors(positive)
        assertEquals(positive.copy(hue = 329f).toArgb(), analogous[1])
        assertEquals(positive.copy(hue = 29f).toArgb(), analogous[2])
    }

    @Test
    fun hueRotationsPreserveSaturationBrightnessAlphaAndTheOriginalColor() {
        val source = 0x80204060L
        val hsv = HsvColor.fromArgb(source)
        ColorHarmony.entries.forEach { mode ->
            val colors = mode.colors(source)
            assertEquals(source, colors.first(), mode.name)
            colors.forEach { color ->
                assertEquals(128, (color ushr 24).toInt(), mode.name)
                assertEquals(hsv.saturation, HsvColor.fromArgb(color).saturation, 0.000001f)
                assertEquals(hsv.brightness, HsvColor.fromArgb(color).brightness, 0.000001f)
            }
            assertEquals(mode.colors(hsv, 128), colors, mode.name)
            assertEquals(
                mode.colors(source and 0xFFFFFFL).map { it or 0x80000000L },
                colors,
                mode.name,
            )
        }
    }

    @Test
    fun achromaticAndTransparentColorsRetainEveryHarmonyPosition() {
        val counts =
            mapOf(
                ColorHarmony.Complementary to 2,
                ColorHarmony.SplitComplementary to 3,
                ColorHarmony.Analogous to 3,
                ColorHarmony.Triadic to 3,
                ColorHarmony.Tetradic to 4,
            )
        for (source in listOf(0x00000000L, 0xFF000000L, 0x7F808080L, 0xFFFFFFFFL)) {
            counts.forEach { (mode, count) ->
                assertEquals(List(count) { source }, mode.colors(source), mode.name)
            }
        }
        assertEquals(
            listOf(0x00FF0000L, 0x0000FFFFL),
            ColorHarmony.Complementary.colors(0x00FF0000L),
        )
    }

    @Test
    fun invalidColorComponentsAreRejectedBeforeConversion() {
        for (color in listOf(-1L, 0x100000000L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ColorHarmony.Complementary.colors(color) }
        }
        val valid = HsvColor(0f, 1f, 1f)
        val invalid =
            listOf(
                valid.copy(hue = Float.NaN),
                valid.copy(hue = Float.POSITIVE_INFINITY),
                valid.copy(hue = Float.NEGATIVE_INFINITY),
                valid.copy(saturation = Float.NaN),
                valid.copy(saturation = Float.POSITIVE_INFINITY),
                valid.copy(saturation = -0.1f),
                valid.copy(saturation = 1.1f),
                valid.copy(brightness = Float.NaN),
                valid.copy(brightness = Float.NEGATIVE_INFINITY),
                valid.copy(brightness = -0.1f),
                valid.copy(brightness = 1.1f),
            )
        invalid.forEach { hsv ->
            assertFailsWith<IllegalArgumentException> { ColorHarmony.Complementary.colors(hsv) }
        }
        for (alpha in listOf(-1, 256)) {
            assertFailsWith<IllegalArgumentException> {
                ColorHarmony.Complementary.colors(valid, alpha)
            }
        }
    }
}
