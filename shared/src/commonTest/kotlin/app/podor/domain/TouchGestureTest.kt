package app.podor.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.*

class TouchGestureTest {
    @Test
    fun pairedContactsKeepTheSameArtworkUnderBothFingers() {
        val gesture = TouchGesture()
        val before =
            listOf(TouchContact(8, Offset(140f, 180f)), TouchContact(2, Offset(260f, 180f)))
        val after = listOf(TouchContact(2, Offset(220f, 300f)), TouchContact(8, Offset(220f, 60f)))
        assertNull(gesture.update(before))
        val move = assertNotNull(gesture.update(after))
        assertEquals(2f, move.zoom)
        assertEquals(90f, move.rotation)
        val view = Size(640f, 480f)
        val document = DocumentInfo(width = 1024, height = 768)
        val initial =
            Viewport(zoom = 1.4f, pan = Offset(45f, -13f), rotation = 31f, mirrored = true)
        val next =
            initial.transform(move.center, move.pan, move.zoom, view, document, move.rotation)
        for (contact in before) {
            val actual =
                next.toView(initial.toDocument(contact.offset, view, document), view, document)
            val expected = after.first { it.id == contact.id }.offset
            assertEquals(expected.x, actual.x, 0.001f)
            assertEquals(expected.y, actual.y, 0.001f)
        }
    }

    @Test
    fun liftedOrReplacedContactsDoNotJump() {
        val gesture = TouchGesture()
        val pair = listOf(TouchContact(1, Offset(100f, 100f)), TouchContact(2, Offset(200f, 100f)))
        assertNull(gesture.update(pair))
        assertNull(gesture.update(pair.take(1)))
        assertNull(gesture.update(listOf(pair[0], TouchContact(3, Offset(500f, 500f)))))
        gesture.reset()
        assertNull(gesture.update(pair))
        val collapsed = pair.map { it.copy(offset = Offset(150f, 100f)) }
        val move = assertNotNull(gesture.update(collapsed))
        assertEquals(1f, move.zoom)
        assertEquals(0f, move.rotation)
        assertEquals(Offset.Zero, move.pan)
    }
}
