package app.podor.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.*
import kotlinx.serialization.json.Json

class LayerTransformTest {
    private val bounds = Rect(100f, 120f, 300f, 220f)

    @Test
    fun rotatedHandlesKeepTheirOppositeAnchorAndDoNotJumpWhenGrabbedOffCenter() {
        for (angle in listOf(0f, 33f, 90f, -170f)) {
            val initial = LayerTransform(200, 100, dx = 10f, dy = -20f, angle = angle)
            val handle = TransformHandle.BottomRight
            val grip = handle.position(bounds, initial, 30f)
            val start = grip + Offset(2f, -3f)
            val gesture = TransformGesture(bounds, initial, handle, start)
            assertEquals(initial, gesture.update(start, false, false))
            val changed = gesture.update(start + Offset(80f, 35f), false, false)
            val before = initial.point(bounds, -1f, -1f)
            val after = changed.point(bounds, -1f, -1f)
            assertEquals(before.x, after.x, 0.001f)
            assertEquals(before.y, after.y, 0.001f)
            assertTrue(changed.valid())
        }
    }

    @Test
    fun proportionalScalingRotationSnapHitTestingAndExtremeInputsStayValid() {
        val initial = LayerTransform(200, 100)
        val handle = TransformHandle.BottomRight
        val start = handle.position(bounds, initial, 30f)
        val gesture = TransformGesture(bounds, initial, handle, start)
        val resized = gesture.update(start + Offset(160f, 20f), true, false)
        assertEquals(2f, resized.width.toFloat() / resized.height, 0.01f)
        for (point in listOf(Offset(1e20f, 1e20f), Offset(-1e20f, -1e20f), Offset(1e4f, 100f))) {
            assertTrue(gesture.update(point, true, false).valid())
            assertTrue(gesture.update(point, false, false).valid())
        }
        assertEquals(initial, gesture.update(Offset(Float.NaN, 0f), true, false))
        assertEquals(handle, transformHandle(bounds, initial, start + Offset(1f, 2f), 10f, 30f))
        assertEquals(
            TransformHandle.Move,
            transformHandle(bounds, initial, bounds.center, 10f, 30f),
        )
        assertNull(transformHandle(bounds, initial, Offset.Zero, 10f, 30f))
        val rotation =
            TransformGesture(
                    bounds,
                    initial,
                    TransformHandle.Rotate,
                    initial.point(bounds, 0f, -1f),
                )
                .update(bounds.center + Offset(100f, -10f), false, true)
        assertEquals(90f, rotation.angle)
    }

    @Test
    fun mirroredCoordinatesAndSerializedParametersAgreeWithTheNativeContract() {
        val value =
            LayerTransform(
                400,
                200,
                dx = 8f,
                dy = 5f,
                angle = 37f,
                flipX = true,
                filter = ResampleFilter.Nearest,
            )
        val corner = value.point(bounds, -1f, 1f)
        val source = value.sourcePoint(bounds, corner)
        assertEquals(bounds.right, source.x, 0.001f)
        assertEquals(bounds.bottom, source.y, 0.001f)
        val wire = Json.encodeToString(value)
        assertTrue(wire.contains("\"flip_x\":true"))
        assertTrue(wire.contains("\"filter\":\"nearest\""))
        assertEquals(value, Json.decodeFromString<LayerTransform>(wire))
        val old = Preferences(shortcuts = mapOf(ShortcutAction.Brush to Shortcut("T", true)))
        assertTrue(old.withNewShortcuts().valid())
        assertEquals(Shortcut("T", true), old.withNewShortcuts().shortcut(ShortcutAction.Brush))
    }
}
