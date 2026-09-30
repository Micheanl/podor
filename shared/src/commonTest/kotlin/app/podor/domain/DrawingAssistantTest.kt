package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.test.*
import kotlinx.serialization.json.*

class DrawingAssistantTest {
    @Test
    fun presetsKeepThreeDistinctPerspectiveFamiliesAndExplicitWireGeometry() {
        val document = DocumentInfo(width = 128, height = 96, maxAssistantCoordinate = 1048576f)
        for (preset in AssistantPreset.entries) {
            val value = preset.spec(document)
            assertTrue(value.valid(document.maxAssistantCoordinate))
            assertEquals("${preset.name} 1", value.name)
            assertEquals(true, value.request()["visible"]?.jsonPrimitive?.boolean)
        }
        val spec = AssistantPreset.TwoPoint.spec(document)
        val geometry = spec.request().getValue("geometry").jsonObject
        assertEquals("perspective", geometry.getValue("kind").jsonPrimitive.content)
        assertEquals(
            "finite_vanishing_point",
            geometry
                .getValue("families")
                .jsonArray[0]
                .jsonObject
                .getValue("kind")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "infinite_direction",
            geometry
                .getValue("families")
                .jsonArray[2]
                .jsonObject
                .getValue("kind")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun handlesEditOnlyTheirActualPointOrInfiniteDirection() {
        val document = DocumentInfo(width = 128, height = 96)
        val original =
            AssistantPreset.TwoPoint.spec(document).geometry as AssistantGeometry.Perspective
        val edited =
            original.withHandle(0, Offset(-30f, 12f), document) as AssistantGeometry.Perspective
        assertEquals(AssistantFamily.Vanishing(AssistantPoint(-30f, 12f)), edited.families[0])
        assertEquals(original.families.drop(1), edited.families.drop(1))
        assertEquals(
            AssistantPoint(16f, 32f),
            (original.families[0] as AssistantFamily.Vanishing).point,
        )
        val direction =
            original.withHandle(2, Offset(80f, 64f), document) as AssistantGeometry.Perspective
        assertEquals(AssistantFamily.Infinite(AssistantPoint(16f, 16f)), direction.families[2])
        assertEquals(original.families.take(2), direction.families.take(2))
    }

    @Test
    fun malformedGuidesCannotCommitNaNRepeatedPointsOrDegenerateDirections() {
        assertFalse(
            AssistantGeometry.Parallel(AssistantPoint(0f, 0f), AssistantPoint(0f, 0f)).valid(1024f)
        )
        assertFalse(AssistantGeometry.Radial(AssistantPoint(Float.NaN, 0f)).valid(1024f))
        val finite = AssistantFamily.Vanishing(AssistantPoint(10f, 10f))
        val a = AssistantFamily.Infinite(AssistantPoint(1f, 0f))
        val b = AssistantFamily.Infinite(AssistantPoint(-2f, 0f))
        assertFalse(AssistantGeometry.Perspective(listOf(finite, a, b)).valid(1024f))
        assertFalse(AssistantGeometry.Perspective(listOf(a, a, a)).valid(1024f))
        assertFalse(AssistantGeometry.Perspective(listOf(finite, finite, a)).valid(1024f))
        assertFalse(AssistantGeometry.Radial(AssistantPoint(2048f, 0f)).valid(1024f))
    }
}
