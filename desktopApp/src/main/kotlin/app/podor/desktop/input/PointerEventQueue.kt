package app.podor.desktop.input

import app.podor.domain.StudioDefaults
import app.podor.domain.TouchContact
import java.util.ArrayDeque

internal enum class PointerPhase {
    Hover,
    Down,
    Move,
    Up,
    Leave,
    Cancel,
}

internal data class WindowPointerPoint(val x: Float, val y: Float, val pressure: Float)

internal data class WindowTouchState(
    val contacts: List<TouchContact>,
    val gesturing: Boolean,
    val started: Boolean = false,
)

internal data class WindowPointerFrame(
    val phase: PointerPhase,
    val id: Int,
    val points: List<WindowPointerPoint>,
    val eraser: Boolean,
    val modifiers: Int = 0,
    val time: Long = System.currentTimeMillis(),
    val touch: WindowTouchState? = null,
    val barrel: Boolean = false,
)

internal class PointerEventQueue {
    private val queue = ArrayDeque<WindowPointerFrame>()
    private var dropping = false

    val size: Int
        get() = queue.size

    fun offer(frame: WindowPointerFrame) {
        if (frame.phase == PointerPhase.Down) dropping = false
        if (dropping) return
        val last = queue.peekLast()
        val sameSource =
            if (last?.touch == null) frame.touch == null
            else
                frame.touch != null &&
                    last.touch.gesturing == frame.touch.gesturing &&
                    !last.touch.started &&
                    !frame.touch.started &&
                    last.touch.contacts.map { it.id } == frame.touch.contacts.map { it.id }
        if (
            sameSource &&
                last?.id == frame.id &&
                last.eraser == frame.eraser &&
                last.barrel == frame.barrel &&
                last.modifiers == frame.modifiers &&
                last.phase == frame.phase
        ) {
            if (frame.phase == PointerPhase.Hover) {
                queue.removeLast()
                queue.add(frame)
                return
            }
            if (frame.phase == PointerPhase.Move && frame.touch?.gesturing == true) {
                queue.removeLast()
                queue.add(frame)
                return
            }
            if (
                frame.phase == PointerPhase.Move &&
                    last.points.size + frame.points.size <= StudioDefaults.maxBatchSamples
            ) {
                queue.removeLast()
                queue.add(frame.copy(points = last.points + frame.points))
                return
            }
        }
        if (queue.size == StudioDefaults.inputQueueCapacity) {
            queue.clear()
            queue.add(frame.copy(phase = PointerPhase.Cancel, points = listOf(frame.points.last())))
            dropping = true
        } else queue.add(frame)
    }

    fun poll(): WindowPointerFrame? = queue.pollFirst()

    fun clear() {
        queue.clear()
        dropping = false
    }
}
