package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.LayerBlendMode
import app.podor.domain.LayerKind
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun LayerBlendControls(controller: StudioController) {
    val preview = controller.adjustmentPreview ?: return
    val settings = preview.settings
    val active = controller.document.layers.firstOrNull { it.id == preview.layerId } ?: return
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(StudioTheme.layerBlendGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.layerBlendGap),
        ) {
            ArtworkPreview(
                controller.previews.images[active.id],
                controller.document.width,
                controller.document.height,
                Modifier.size(StudioTheme.layerPreviewSize),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    tr(active.name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = StudioTheme.layerBlendLabelSize,
                    color = StudioTheme.text,
                )
                Text(
                    tr(if (active.visible) "图层混合" else "图层已隐藏"),
                    fontSize = StudioTheme.layerBlendCaptionSize,
                    color = StudioTheme.muted,
                )
            }
        }
        LabeledSlider(
            "图层不透明度",
            settings.opacity,
            0f..1f,
            "${(settings.opacity * 100).roundToInt()}%",
        ) {
            controller.updateAdjustment(settings.copy(opacity = it))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            LayerBlendOptions(
                settings.blend,
                if (active.kind == LayerKind.Adjustment) listOf(LayerBlendMode.Normal)
                else LayerBlendMode.entries,
            ) {
                controller.updateAdjustment(settings.copy(blend = it))
            }
        }
        Box(Modifier.fillMaxWidth().height(StudioTheme.layerBlendProgressHeight)) {
            if (preview.updating || preview.committing)
                LinearProgressIndicator(Modifier.fillMaxSize())
        }
    }
}

@Composable
fun LayerBlendOptions(
    current: LayerBlendMode,
    modes: List<LayerBlendMode> = LayerBlendMode.entries,
    onSelect: (LayerBlendMode) -> Unit,
) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        modes.chunked(2).forEach { row ->
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
                            Modifier.padding(top = StudioTheme.layerBlendLabelGap),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
        Modifier.size(StudioTheme.layerBlendSampleWidth, StudioTheme.layerBlendSampleHeight)
            .graphicsLayer {
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
