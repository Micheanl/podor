package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import app.podor.domain.LayerBlendMode
import app.podor.presentation.LayerFrame

@Composable
internal fun LayerStackOverlay(
    layers: List<LayerFrame>,
    modifier: Modifier,
    drawLayer: DrawScope.(LayerFrame) -> Unit,
) {
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        layers.forEach { frame ->
            Canvas(
                Modifier.matchParentSize().graphicsLayer {
                    alpha = frame.layer.opacity
                    blendMode =
                        when (frame.layer.blend) {
                            LayerBlendMode.Normal -> BlendMode.SrcOver
                            LayerBlendMode.Multiply -> BlendMode.Multiply
                            LayerBlendMode.Screen -> BlendMode.Screen
                            LayerBlendMode.Overlay -> BlendMode.Overlay
                            LayerBlendMode.SoftLight -> BlendMode.Softlight
                            LayerBlendMode.Darken -> BlendMode.Darken
                            LayerBlendMode.Lighten -> BlendMode.Lighten
                            LayerBlendMode.Difference -> BlendMode.Difference
                        }
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            ) {
                drawLayer(frame)
            }
        }
    }
}
