package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import app.podor.domain.SymmetryMode
import app.podor.domain.Tool
import app.podor.presentation.StudioController

@Composable
fun SymmetryGuides(controller: StudioController, viewSize: Size, modifier: Modifier = Modifier) {
    if (
        (controller.tool != Tool.Brush && controller.tool != Tool.Eraser) ||
            controller.symmetry.mode == SymmetryMode.Off ||
            !controller.symmetry.guides
    )
        return
    val density = LocalDensity.current
    val guideScale = controller.viewport.scale(viewSize, controller.document).coerceAtLeast(0.01f)
    val dash =
        remember(density, guideScale) {
            val length = with(density) { StudioTheme.symmetryGuideDash.toPx() } / guideScale
            PathEffect.dashPathEffect(floatArrayOf(length, length))
        }
    Canvas(modifier.graphicsLayer()) {
        if (controller.tool != Tool.Brush && controller.tool != Tool.Eraser) return@Canvas
        val settings = controller.symmetry
        if (!settings.guides || settings.mode == SymmetryMode.Off) return@Canvas
        val document = controller.document
        val viewport = controller.viewport
        val scale = viewport.scale(viewSize, document)
        if (scale <= 0f) return@Canvas
        val axis = settings.axis(document)
        val origin = viewport.origin(viewSize, document)
        withTransform({
            translate(origin.x, origin.y)
            rotate(viewport.rotation, Offset.Zero)
            scale(scale * viewport.horizontalSign, scale, Offset.Zero)
        }) {
            clipRect(0f, 0f, document.width.toFloat(), document.height.toFloat()) {
                fun guide(start: Offset, end: Offset) {
                    drawLine(
                        StudioTheme.symmetryGuideShade,
                        start,
                        end,
                        strokeWidth = StudioTheme.symmetryGuideHalo.toPx() / scale,
                    )
                    drawLine(
                        StudioTheme.symmetryGuideColor,
                        start,
                        end,
                        strokeWidth = StudioTheme.symmetryGuideWidth.toPx() / scale,
                        pathEffect = dash,
                    )
                }
                if (settings.mode.vertical)
                    guide(Offset(axis.x, 0f), Offset(axis.x, document.height.toFloat()))
                if (settings.mode.horizontal)
                    guide(Offset(0f, axis.y), Offset(document.width.toFloat(), axis.y))
            }
        }
    }
}
