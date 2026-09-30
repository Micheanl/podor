package app.podor.ui.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import app.podor.domain.TouchInput

expect fun PointerEvent.platformTouchInput(): TouchInput?

fun Modifier.nativeTouchGuard() =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val touch = event.platformTouchInput()
                if (touch?.gesturing == true || touch?.cancelled == true)
                    event.changes.forEach { it.consume() }
            }
        }
    }
