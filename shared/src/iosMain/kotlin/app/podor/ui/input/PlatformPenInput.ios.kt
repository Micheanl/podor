package app.podor.ui.input

import androidx.compose.ui.input.pointer.PointerEvent
import app.podor.domain.PenInput

actual fun PointerEvent.platformPenInput(): PenInput? = null
