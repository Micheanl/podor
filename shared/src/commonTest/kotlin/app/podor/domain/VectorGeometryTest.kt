package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.test.*
import kotlinx.serialization.json.*

class VectorGeometryTest {
    @Test
    fun typedShapeAndCubicControlsSerializeTheirActualGeometryAndExplicitStyles() {
        val spec =
            VectorObjectSpec(
                "Curve",
                geometry =
                    VectorGeometry.Path(
                        listOf(
                            VectorSegment.Move(2f, 3f),
                            VectorSegment.Cubic(4f, 5f, 6f, 7f, 8f, 9f),
                            VectorSegment.Close,
                        )
                    ),
                style = VectorStyle(fill = vectorRgba(0x80406080)),
            )
        val wire = spec.request()
        assertEquals(
            "path",
            wire.getValue("geometry").jsonObject.getValue("kind").jsonPrimitive.content,
        )
        val segments = wire.getValue("geometry").jsonObject.getValue("segments").jsonArray
        assertEquals("cubic_to", segments[1].jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(6f, segments[1].jsonObject.getValue("c2x").jsonPrimitive.float)
        assertEquals(JsonNull, wire.getValue("style").jsonObject.getValue("stroke"))
        assertEquals(spec, Json.decodeFromJsonElement<VectorObjectSpec>(wire))
        assertTrue(spec.valid())
        assertFalse(spec.copy(transform = listOf(0f, 0f, 0f, 0f, 0f, 0f)).valid())
        assertFalse(spec.copy(style = VectorStyle(fill = listOf(1, 2, 3, 300))).valid())
        assertFalse(VectorGeometry.Line(1f, 2f, 1f, 2f).valid())
        assertFalse(VectorGeometry.Path(listOf(VectorSegment.Move(1f, 2f))).valid())
    }

    @Test
    fun transformedControlPointEditingPreservesOtherSegmentsAndStyle() {
        val geometry =
            VectorGeometry.Path(
                listOf(
                    VectorSegment.Move(2f, 3f),
                    VectorSegment.Quadratic(4f, 5f, 6f, 7f),
                    VectorSegment.Cubic(8f, 9f, 10f, 11f, 12f, 13f),
                )
            )
        val spec =
            VectorObjectSpec(
                "Curve",
                geometry = geometry,
                transform = listOf(2f, 1f, -1f, 3f, 17f, -9f),
                style = VectorStyle(stroke = VectorStroke(vectorRgba(0xFF123456), 2f)),
            )
        val node = geometry.nodes().first { it.segment == 2 && it.point == 2 }
        val world = spec.worldPoint(node.position)
        assertEquals(node.position, spec.localPoint(world))
        val moved =
            geometry.withNode(node, spec.localPoint(spec.worldPoint(Offset(19f, 23f))))
                as VectorGeometry.Path
        assertEquals(geometry.segments.take(2), moved.segments.take(2))
        assertEquals(VectorSegment.Cubic(8f, 9f, 19f, 23f, 12f, 13f), moved.segments.last())
        assertEquals(spec.style, spec.copy(geometry = moved).style)
        assertEquals(geometry, spec.geometry)
    }

    @Test
    fun penDragProducesSeparateBezierHandlesAndCloseRetainsItsOwnSegment() {
        val pen = VectorPenGesture()
        pen.begin(Offset(2f, 4f))
        pen.drag(Offset(6f, 4f))
        pen.begin(Offset(12f, 14f))
        pen.drag(Offset(16f, 18f))
        assertEquals(
            listOf(
                VectorSegment.Move(2f, 4f),
                VectorSegment.Cubic(6f, 4f, 8f, 10f, 12f, 14f),
            ),
            pen.geometry().segments,
        )
        assertTrue(pen.geometry().valid())
        assertFalse(VectorGeometry.Path(listOf(VectorSegment.Close)).valid())
        assertFalse(
            VectorGeometry.Path(
                    listOf(
                        VectorSegment.Move(0f, 0f),
                        VectorSegment.Close,
                        VectorSegment.Line(1f, 1f),
                    )
                )
                .valid()
        )
    }
}
