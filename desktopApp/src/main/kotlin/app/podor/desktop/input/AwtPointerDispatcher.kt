package app.podor.desktop.input

import androidx.compose.ui.geometry.Offset
import app.podor.domain.*
import java.awt.Component
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

internal class PenMouseEvent(
    component: Component,
    id: Int,
    time: Long,
    modifiers: Int,
    x: Int,
    y: Int,
    button: Int,
    override val penInput: PenInput,
) : MouseEvent(component, id, time, modifiers, x, y, 1, false, button), PenEvent

internal class TouchMouseEvent(
    component: Component,
    id: Int,
    time: Long,
    modifiers: Int,
    x: Int,
    y: Int,
    button: Int,
    override val touchInput: TouchInput,
) : MouseEvent(component, id, time, modifiers, x, y, 1, false, button), TouchEvent

internal class AwtPointerDispatcher(private val window: Window) : AutoCloseable {
    private val queue = PointerEventQueue()
    private var scheduled = false
    private var closed = false
    private var captured: Component? = null
    private var hovered: Component? = null

    fun offer(frame: WindowPointerFrame) =
        synchronized(queue) {
            if (closed) return@synchronized
            queue.offer(frame)
            if (!scheduled) {
                scheduled = true
                EventQueue.invokeLater(::drain)
            }
        }

    private fun drain() {
        repeat(StudioDefaults.nativeInputDispatchLimit) {
            val frame = synchronized(queue) { if (closed) null else queue.poll() }
            if (frame != null) dispatch(frame)
        }
        synchronized(queue) {
            if (queue.size > 0 && !closed) EventQueue.invokeLater(::drain) else scheduled = false
        }
    }

    private fun dispatch(frame: WindowPointerFrame) {
        if (!window.isDisplayable) return
        val current = frame.points.last()
        val x = current.x.roundToInt()
        val y = current.y.roundToInt()
        val target =
            captured
                ?: (if (frame.phase == PointerPhase.Leave || frame.phase == PointerPhase.Cancel)
                    hovered
                else SwingUtilities.getDeepestComponentAt(window, x, y))
                ?: return
        val point = SwingUtilities.convertPoint(window, x, y, target)
        val pen =
            if (frame.touch == null)
                PenInput(
                    frame.points.map { PenSample(Offset(it.x - x, it.y - y), it.pressure) },
                    frame.eraser,
                    frame.phase == PointerPhase.Cancel,
                    frame.barrel,
                )
            else null
        fun send(
            component: Component,
            id: Int,
            button: Int = MouseEvent.NOBUTTON,
            contact: Boolean = false,
            outside: Boolean = false,
        ) {
            val local =
                if (component == target) point
                else SwingUtilities.convertPoint(window, x, y, component)
            val eventX = if (outside) -1 else local.x
            val eventY = if (outside) -1 else local.y
            val modifiers = frame.modifiers or if (contact) MouseEvent.BUTTON1_DOWN_MASK else 0
            component.dispatchEvent(
                if (frame.touch != null)
                    TouchMouseEvent(
                        component,
                        id,
                        frame.time,
                        modifiers,
                        eventX,
                        eventY,
                        button,
                        TouchInput(
                            frame.touch.contacts.map {
                                it.copy(
                                    offset =
                                        it.offset - Offset(x.toFloat(), y.toFloat()) +
                                            Offset(
                                                (local.x - eventX).toFloat(),
                                                (local.y - eventY).toFloat(),
                                            )
                                )
                            },
                            frame.touch.gesturing,
                            frame.phase == PointerPhase.Cancel,
                            frame.points.map {
                                Offset(it.x - x, it.y - y) +
                                    Offset(
                                        (local.x - eventX).toFloat(),
                                        (local.y - eventY).toFloat(),
                                    )
                            },
                        ),
                    )
                else
                    PenMouseEvent(
                        component,
                        id,
                        frame.time,
                        modifiers,
                        eventX,
                        eventY,
                        button,
                        if (id == MouseEvent.MOUSE_RELEASED && frame.phase == PointerPhase.Up)
                            checkNotNull(pen).copy(samples = listOf(pen.samples.last()))
                        else checkNotNull(pen),
                    )
            )
        }
        if (
            hovered != target &&
                frame.phase != PointerPhase.Cancel &&
                frame.phase != PointerPhase.Leave
        ) {
            hovered?.let { send(it, MouseEvent.MOUSE_EXITED) }
            send(target, MouseEvent.MOUSE_ENTERED)
            hovered = target
        }
        // Compose 会先合成位置变化事件，提前派发移动可避免后续落笔和抬笔丢失。
        when (frame.phase) {
            PointerPhase.Down -> {
                captured = target
                target.requestFocusInWindow()
                send(target, MouseEvent.MOUSE_MOVED)
                send(target, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, true)
            }
            PointerPhase.Move ->
                if (captured != null) send(target, MouseEvent.MOUSE_DRAGGED, contact = true)
            PointerPhase.Up -> {
                if (captured != null) {
                    send(target, MouseEvent.MOUSE_DRAGGED, contact = true)
                    send(target, MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1)
                }
                captured = null
            }
            PointerPhase.Cancel -> {
                if (captured != null) {
                    send(target, MouseEvent.MOUSE_DRAGGED, contact = true)
                    send(target, MouseEvent.MOUSE_DRAGGED, contact = true, outside = true)
                    send(target, MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1, outside = true)
                }
                captured = null
                hovered = null
            }
            PointerPhase.Hover -> send(target, MouseEvent.MOUSE_MOVED)
            PointerPhase.Leave -> {
                hovered?.let { send(it, MouseEvent.MOUSE_EXITED) }
                hovered = null
            }
        }
    }

    override fun close() =
        synchronized(queue) {
            closed = true
            queue.clear()
            captured = null
            hovered = null
        }
}
