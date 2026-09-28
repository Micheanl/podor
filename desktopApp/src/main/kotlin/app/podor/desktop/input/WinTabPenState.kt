package app.podor.desktop.input

internal data class WinTabCursor(val minimum: Int, val maximum: Int, val tipMask: Int) {
    fun pressure(value: Int): Float =
        ((value.toLong() - minimum).toDouble() / (maximum.toLong() - minimum))
            .toFloat()
            .coerceIn(0f, 1f)
}

internal class WinTabPenState(private val emit: (WindowPointerFrame) -> Unit) {
    private var active: WindowPointerFrame? = null
    private var last: WindowPointerFrame? = null
    private var dropping = false
    var inRange = false
        private set

    fun proximity(entered: Boolean) {
        if (!entered) {
            cancel()
            last?.let { emit(it.copy(phase = PointerPhase.Leave)) }
            last = null
        }
        inRange = entered
    }

    fun packet(
        id: Int,
        status: Int,
        buttons: Int,
        pressure: Int,
        cursor: WinTabCursor,
        x: Float,
        y: Float,
        modifiers: Int,
    ) {
        if (status and 1 != 0 && status and 8 == 0) {
            proximity(false)
            return
        }
        inRange = true
        val contact =
            if (cursor.tipMask != 0) buttons and cursor.tipMask != 0 else pressure > cursor.minimum
        if (status and 2 != 0) {
            cancel()
            dropping = contact
            return
        }
        if (dropping) {
            if (!contact) dropping = false
            return
        }
        val eraser = status and 0x10 != 0
        if (active?.let { it.id != id || it.eraser != eraser } == true) cancel()
        val phase =
            when {
                contact && active == null -> PointerPhase.Down
                contact -> PointerPhase.Move
                active != null -> PointerPhase.Up
                else -> PointerPhase.Hover
            }
        val frame =
            WindowPointerFrame(
                phase,
                id,
                listOf(WindowPointerPoint(x, y, cursor.pressure(pressure))),
                eraser,
                modifiers,
                barrel = cursor.tipMask != 0 && buttons and cursor.tipMask.inv() != 0,
            )
        emit(frame)
        active = if (contact) frame else null
        last = frame
    }

    fun cancel() {
        active?.let { emit(it.copy(phase = PointerPhase.Cancel)) }
        active = null
        dropping = false
        inRange = false
    }
}
