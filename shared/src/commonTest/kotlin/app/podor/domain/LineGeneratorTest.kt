package app.podor.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class LineGeneratorTest {
    @Test
    fun typedRequestsContainOnlyTheirActualGeneratorFieldsAndFullStyles() {
        val doc = DocumentInfo(width = 128, height = 96, maxGeneratedLines = 256)
        for (kind in LineGeneratorKind.entries) {
            val settings = defaultLineGenerator(doc, BrushSettings(), kind)
            assertTrue(settings.valid(doc.maxGeneratedLines))
            val json = settings.request()
            assertEquals(kind.wire, json.getValue("kind").jsonPrimitive.content)
            assertEquals(42, json.getValue("seed").jsonPrimitive.int)
            assertEquals(
                "butt",
                json.getValue("stroke").jsonObject.getValue("cap").jsonPrimitive.content,
            )
            assertEquals(kind == LineGeneratorKind.Concentration, json.containsKey("center"))
            assertEquals(kind == LineGeneratorKind.Speed, json.containsKey("origin"))
        }
    }

    @Test
    fun invalidCountsSeedsBandsAndOpacityCannotCommit() {
        val value =
            defaultLineGenerator(DocumentInfo(), BrushSettings(), LineGeneratorKind.Concentration)
        assertFalse(value.copy(count = 257).valid(256))
        assertFalse(value.copy(seed = -1).valid(256))
        assertFalse(value.copy(seed = 4294967296L).valid(256))
        assertFalse(value.copy(outer = value.inner).valid(256))
        assertFalse(value.copy(opacity = Float.NaN).valid(256))
        assertFalse(value.copy(angleSweep = 0f).valid(256))
        assertFalse(value.copy(kind = LineGeneratorKind.Speed, spacing = 0f).valid(256))
    }
}
