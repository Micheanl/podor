package app.podor.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixelGridGeometryTest {
    @Test
    fun pixelLinesUseOnlyTheVisibleInteriorCoordinates() {
        assertEquals(listOf(13, 14, 15, 16), visibleGridLines(12.4f, 16.8f, 1, 4096).toList())
        assertEquals(listOf(1, 2, 3), visibleGridLines(-4f, 4f, 1, 4).toList())
        assertEquals(listOf(2001, 2002), visibleGridLines(2000.6f, 2002.2f, 1, 4096).toList())
    }

    @Test
    fun rectangularTileLinesKeepTheDocumentOriginAndPartialEdgeTile() {
        assertEquals(listOf(16, 32, 48, 64), visibleGridLines(0f, 70f, 16, 70).toList())
        assertEquals(listOf(32, 48), visibleGridLines(20f, 55f, 16, 70).toList())
        assertEquals(listOf(8, 16, 24), visibleGridLines(0f, 32f, 8, 32).toList())
        assertEquals(listOf(4, 8, 12, 16), visibleGridLines(0f, 19f, 4, 19).toList())
    }

    @Test
    fun emptyOrInvalidVisibleRangesNeverProduceLines() {
        for (lines in
            listOf(
                visibleGridLines(20f, 10f, 1, 64),
                visibleGridLines(70f, 90f, 1, 64),
                visibleGridLines(-20f, -1f, 1, 64),
                visibleGridLines(0f, 64f, 0, 64),
                visibleGridLines(0f, 64f, 1, 1),
                visibleGridLines(Float.NaN, 64f, 1, 64),
                visibleGridLines(0f, Float.POSITIVE_INFINITY, 1, 64),
                visibleGridLines(0f, 64f, 128, 64),
            )) assertTrue(lines.none())
    }
}
