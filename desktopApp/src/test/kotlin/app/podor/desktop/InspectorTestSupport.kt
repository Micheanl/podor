package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*

private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
    yield(node)
    for (child in node.children) yieldAll(descendants(child))
}

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal fun ImageComposeScene.controlBounds(vararg labels: String): Rect {
    val nodes = semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.toList()
    val described = nodes.filter {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label ->
            label in labels
        } == true
    }
    return (described.ifEmpty {
            nodes.filter {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                    text.text in labels
                } == true
            }
        })
        .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
        .boundsInWindow
}

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal fun ImageComposeScene.sliderBounds(vararg labels: String): Rect =
    semanticsOwners
        .asSequence()
        .flatMap { descendants(it.rootSemanticsNode) }
        .single {
            it.config.contains(SemanticsProperties.ProgressBarRangeInfo) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label ->
                    label in labels
                } == true
        }
        .boundsInWindow

@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageComposeScene.clickControl(label: String, render: () -> Unit) {
    val point = controlBounds(label).center
    sendPointerEvent(PointerEventType.Press, point)
    sendPointerEvent(PointerEventType.Release, point)
    sendPointerEvent(PointerEventType.Move, Offset.Zero)
    repeat(35) { render() }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageComposeScene.openInspector(render: () -> Unit) {
    repeat(35) { render() }
    val point = controlBounds("展开面板", "Show panel").center
    sendPointerEvent(PointerEventType.Press, point)
    sendPointerEvent(PointerEventType.Release, point)
    sendPointerEvent(PointerEventType.Move, Offset.Zero)
    repeat(35) { render() }
}
