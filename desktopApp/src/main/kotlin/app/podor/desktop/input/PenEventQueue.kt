package app.podor.desktop.input

import app.podor.domain.StudioDefaults
import java.util.ArrayDeque

internal enum class PenPhase {
    Hover,
    Down,
    Move,
    Up,
    Leave,
    Cancel,
}

internal data class WindowPenPoint(val x: Float, val y: Float, val pressure: Float)

internal data class WindowPenFrame(
    val phase: PenPhase,
    val id: Int,
    val points: List<WindowPenPoint>,
    val eraser: Boolean,
    val modifiers: Int = 0,
    val time: Long = System.currentTimeMillis(),
)

internal class PenEventQueue {
    private val queue = ArrayDeque<WindowPenFrame>()
    private var dropping = false

    val size: Int
        get() = queue.size

    fun offer(frame: WindowPenFrame) {
        if (frame.phase == PenPhase.Down) dropping = false
        if (dropping) return
        val last = queue.peekLast()
        if (last?.id == frame.id && last.eraser == frame.eraser && last.phase == frame.phase) {
            if (frame.phase == PenPhase.Hover) {
                queue.removeLast()
                queue.add(frame)
                return
            }
            if (
                frame.phase == PenPhase.Move &&
                    last.points.size + frame.points.size <= StudioDefaults.maxBatchSamples
            ) {
                queue.removeLast()
                queue.add(frame.copy(points = last.points + frame.points))
                return
            }
        }
        if (queue.size == StudioDefaults.inputQueueCapacity) {
            queue.clear()
            queue.add(frame.copy(phase = PenPhase.Cancel, points = listOf(frame.points.last())))
            dropping = true
        } else queue.add(frame)
    }

    fun poll(): WindowPenFrame? = queue.pollFirst()

    fun clear() {
        queue.clear()
        dropping = false
    }
}
