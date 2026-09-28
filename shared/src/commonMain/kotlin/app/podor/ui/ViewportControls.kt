package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.StudioDefaults
import app.podor.domain.Viewport
import kotlin.math.roundToInt

@Composable
fun ViewportControls(viewport: Viewport, fitLabel: String, onChange: (Viewport) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var zoomExpanded by remember { mutableStateOf(false) }
    val rotationLabel = tr("旋转视图")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            StudioTextButton(
                { zoomExpanded = !zoomExpanded },
                contentPadding = PaddingValues(horizontal = 7.dp),
            ) {
                ButtonLabel(
                    "${(viewport.zoom * 100).roundToInt()}%",
                    fontSize = 11.sp,
                    color = StudioTheme.muted,
                )
            }
            DropdownMenu(
                zoomExpanded,
                { zoomExpanded = false },
                shape = StudioTheme.menuShape,
                containerColor = StudioTheme.panel,
            ) {
                Column(Modifier.width(StudioTheme.quickControlsWidth).padding(16.dp)) {
                    LabeledSlider(
                        "缩放",
                        viewport.zoom,
                        StudioDefaults.minZoom..StudioDefaults.maxZoom,
                        "${(viewport.zoom * 100).roundToInt()}%",
                    ) {
                        onChange(viewport.zoomBy(it / viewport.zoom))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ToolButton(Glyph.Minus, "缩小画布") {
                            onChange(viewport.zoomBy(1f / StudioDefaults.zoomStep))
                        }
                        StudioTextButton({
                            onChange(Viewport())
                            zoomExpanded = false
                        }) {
                            ButtonLabel(tr("适合窗口"))
                        }
                        ToolButton(Glyph.Plus, "放大画布") {
                            onChange(viewport.zoomBy(StudioDefaults.zoomStep))
                        }
                    }
                }
            }
        }

        Box {
            StudioTextButton(
                { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = rotationLabel },
                contentPadding = PaddingValues(horizontal = 7.dp),
            ) {
                StudioIcon(Glyph.Rotate, StudioTheme.muted, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                ButtonLabel(
                    "${viewport.rotation.roundToInt()}°",
                    fontSize = 11.sp,
                    color = if (viewport.rotation == 0f) StudioTheme.muted else StudioTheme.accent,
                )
            }
            DropdownMenu(
                expanded,
                { expanded = false },
                shape = StudioTheme.menuShape,
                containerColor = StudioTheme.panel,
            ) {
                Column(Modifier.width(StudioTheme.viewControlsWidth).padding(16.dp)) {
                    LabeledSlider(
                        "旋转视图",
                        viewport.rotation,
                        -180f..180f,
                        "${viewport.rotation.roundToInt()}°",
                    ) {
                        onChange(viewport.copy(rotation = it))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ToolButton(Glyph.Undo, "向左旋转") {
                            onChange(viewport.rotateBy(-StudioDefaults.rotationStep))
                        }
                        StudioTextButton({ onChange(viewport.copy(rotation = 0f)) }) {
                            ButtonLabel(tr("回正"))
                        }
                        ToolButton(Glyph.Redo, "向右旋转") {
                            onChange(viewport.rotateBy(StudioDefaults.rotationStep))
                        }
                    }
                    Text(tr("Shift + 滚轮旋转"), color = StudioTheme.muted, fontSize = 10.sp)
                }
            }
        }
        ToolButton(Glyph.Mirror, "镜像视图", selected = viewport.mirrored) {
            onChange(viewport.copy(mirrored = !viewport.mirrored))
        }
        ToolButton(Glyph.Fit, fitLabel) { onChange(Viewport()) }
    }
}
