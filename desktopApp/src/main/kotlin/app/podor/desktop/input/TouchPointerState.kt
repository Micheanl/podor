package app.podor.desktop.input

import androidx.compose.ui.geometry.Offset
import app.podor.domain.StudioDefaults
import app.podor.domain.TouchContact

internal class TouchPointerState(private val emit: (WindowPointerFrame) -> Unit) {
    private val contacts = linkedMapOf<Int, WindowPointerPoint>()
    private var active: WindowPointerFrame? = null
    private var gesturing = false

    val ids: Set<Int>
        get() = contacts.keys

    fun update(phase: PointerPhase, id: Int, point: WindowPointerPoint, modifiers: Int) {
        if (phase != PointerPhase.Down && id !in contacts) return
        if (phase == PointerPhase.Down && contacts.size >= StudioDefaults.nativeTouchContacts) {
            cancel()
            return
        }
        val first = contacts.isEmpty()
        if (phase == PointerPhase.Up) contacts.remove(id) else contacts[id] = point
        val starting = !gesturing && contacts.size >= 2
        gesturing = gesturing || starting
        val primary = active?.id ?: id
        val position = contacts[primary] ?: contacts.values.firstOrNull() ?: point
        val frame =
            WindowPointerFrame(
                if (first) PointerPhase.Down
                else if (contacts.isEmpty()) PointerPhase.Up else PointerPhase.Move,
                primary,
                listOf(position),
                false,
                modifiers,
                touch =
                    WindowTouchState(
                        contacts.map { TouchContact(it.key, Offset(it.value.x, it.value.y)) },
                        gesturing,
                        starting,
                    ),
            )
        emit(frame)
        active = if (contacts.isEmpty()) null else frame
        if (contacts.isEmpty()) gesturing = false
    }

    fun cancel() {
        active?.let { emit(it.copy(phase = PointerPhase.Cancel)) }
        active = null
        contacts.clear()
        gesturing = false
    }
}
