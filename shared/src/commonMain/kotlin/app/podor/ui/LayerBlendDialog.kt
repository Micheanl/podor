package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.LayerBlendMode

@Composable
fun LayerBlendDialog(
    current: LayerBlendMode,
    onSelect: (LayerBlendMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<LayerBlendMode?>(null) }
    StudioModal(
        "混合模式",
        Glyph.Layers,
        {
            selected?.let(onSelect)
            onDismiss()
        },
        width = 400.dp,
    ) { dismiss ->
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState())) {
            LayerBlendOptions(current) {
                selected = it
                dismiss()
            }
        }
    }
}

@Composable
fun LayerBlendOptions(current: LayerBlendMode, onSelect: (LayerBlendMode) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LayerBlendMode.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { mode ->
                    ChoiceSurface(mode == current, { onSelect(mode) }, Modifier.weight(1f)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BlendSample(mode)
                            Spacer(Modifier.weight(1f))
                            if (mode == current)
                                StudioIcon(Glyph.Check, StudioTheme.accent, Modifier.size(16.dp))
                        }
                        Text(
                            tr(mode.label),
                            Modifier.padding(top = 10.dp),
                            fontSize = 12.sp,
                            color =
                                if (mode == current) StudioTheme.onSelection else StudioTheme.text,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlendSample(mode: LayerBlendMode) {
    val blend =
        when (mode) {
            LayerBlendMode.Normal -> BlendMode.SrcOver
            LayerBlendMode.Multiply -> BlendMode.Multiply
            LayerBlendMode.Screen -> BlendMode.Screen
            LayerBlendMode.Overlay -> BlendMode.Overlay
            LayerBlendMode.SoftLight -> BlendMode.Softlight
            LayerBlendMode.Darken -> BlendMode.Darken
            LayerBlendMode.Lighten -> BlendMode.Lighten
            LayerBlendMode.Difference -> BlendMode.Difference
        }
    Canvas(
        Modifier.size(52.dp, 32.dp).graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        val radius = size.height / 2f
        drawCircle(StudioTheme.blendBackdrop, radius, Offset(radius, radius))
        drawCircle(
            StudioTheme.blendSource,
            radius,
            Offset(size.width - radius, radius),
            blendMode = blend,
        )
    }
}
