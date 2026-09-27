package app.podor.ui.input

import androidx.compose.ui.input.pointer.PointerEvent
import app.podor.domain.TouchInput

actual fun PointerEvent.platformTouchInput(): TouchInput? = null
