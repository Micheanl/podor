package app.podor.desktop

import app.podor.desktop.input.*
import app.podor.domain.StudioDefaults
import kotlin.test.*

class TouchQueueTest {
    @Test
    fun transitionToGestureSurvivesCoalescingAndTheLastFingerCannotResumePainting() {
        val queue = PointerEventQueue()
        val state = TouchPointerState(queue::offer)
        fun point(x: Float) = WindowPointerPoint(x, 150f, 1f)
        state.update(PointerPhase.Down, 5, point(100f), 0)
        state.update(PointerPhase.Down, 8, point(200f), 0)
        repeat(1000) { state.update(PointerPhase.Move, 8, point(200f + it), 0) }
        assertEquals(3, queue.size)
        assertEquals(PointerPhase.Down, queue.poll()!!.phase)
        val start = queue.poll()!!
        assertTrue(start.touch!!.started)
        assertEquals(200f, start.touch.contacts.last().offset.x)
        assertEquals(1199f, queue.poll()!!.touch!!.contacts.last().offset.x)
        state.update(PointerPhase.Up, 5, point(100f), 0)
        val remaining = queue.poll()!!
        assertEquals(5, remaining.id)
        assertEquals(listOf(8), remaining.touch!!.contacts.map { it.id })
        assertTrue(remaining.touch.gesturing)
        state.update(PointerPhase.Up, 8, point(1199f), 0)
        assertEquals(PointerPhase.Up, queue.poll()!!.phase)
        state.update(PointerPhase.Down, 9, point(20f), 0)
        assertFalse(queue.poll()!!.touch!!.gesturing)
    }

    @Test
    fun cancellationAndQueueCapacityApplyToTouchAndPenTogether() {
        val queue = PointerEventQueue()
        val state = TouchPointerState(queue::offer)
        val point = WindowPointerPoint(10f, 10f, 1f)
        state.update(PointerPhase.Down, 1, point, 0)
        state.cancel()
        queue.offer(WindowPointerFrame(PointerPhase.Down, 1, listOf(point), false))
        assertNotNull(queue.poll()!!.touch)
        assertEquals(PointerPhase.Cancel, queue.poll()!!.phase)
        assertNull(queue.poll()!!.touch)
        repeat(StudioDefaults.inputQueueCapacity / 2 + 1) {
            state.update(PointerPhase.Down, 2, point, 0)
            state.update(PointerPhase.Up, 2, point, 0)
        }
        assertEquals(1, queue.size)
        assertEquals(PointerPhase.Cancel, queue.poll()!!.phase)
    }
}
