package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType

@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageComposeScene.openInspector(render: () -> Unit) {
    repeat(35) { render() }
    sendPointerEvent(PointerEventType.Press, Offset(1314f, 32f))
    sendPointerEvent(PointerEventType.Release, Offset(1314f, 32f))
    sendPointerEvent(PointerEventType.Move, Offset.Zero)
    repeat(35) { render() }
}
