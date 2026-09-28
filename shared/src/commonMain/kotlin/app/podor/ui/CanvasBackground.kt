package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import app.podor.domain.CanvasBackground
import app.podor.engine.rgbaBitmap
import app.podor.presentation.StudioController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberCanvasChecker(): Brush? {
    val brush by
        produceState<Brush?>(null) {
            value =
                withContext(Dispatchers.Default) {
                    val light = StudioTheme.checkerLight.toArgb()
                    val dark = StudioTheme.checkerDark.toArgb()
                    val bytes = ByteArray(16)
                    for ((index, color) in listOf(dark, light, light, dark).withIndex()) {
                        bytes[index * 4] = (color shr 16).toByte()
                        bytes[index * 4 + 1] = (color shr 8).toByte()
                        bytes[index * 4 + 2] = color.toByte()
                        bytes[index * 4 + 3] = 255.toByte()
                    }
                    ShaderBrush(
                        ImageShader(rgbaBitmap(bytes, 0, 2), TileMode.Repeated, TileMode.Repeated)
                    )
                }
        }
    return brush
}

internal fun DrawScope.drawCanvasBackground(
    background: CanvasBackground,
    checker: Brush?,
    paper: Size,
    scale: Float,
) {
    when (background) {
        CanvasBackground.White -> drawRect(Color.White, size = paper)
        CanvasBackground.Gray -> drawRect(StudioTheme.canvasGray, size = paper)
        CanvasBackground.Transparent -> {
            if (checker == null) drawRect(StudioTheme.checkerLight, size = paper)
            else {
                val cell = StudioTheme.canvasCheckerSize.toPx() / scale
                withTransform({ scale(cell, cell, Offset.Zero) }) {
                    drawRect(checker, size = Size(paper.width / cell, paper.height / cell))
                }
            }
        }
    }
}

@Composable
fun CanvasBackgroundMenu(controller: StudioController) {
    var expanded by remember { mutableStateOf(false) }
    val background = controller.preferences.canvasBackground
    Box {
        ToolButton(Glyph.AlphaLock, "画布背景", selected = background != CanvasBackground.White) {
            expanded = !expanded
        }
        StudioDropdownMenu(
            expanded,
            { expanded = false },
        ) {
            CanvasBackground.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(tr(mode.label)) },
                    leadingIcon = {
                        if (mode == CanvasBackground.Transparent) StudioIcon(Glyph.AlphaLock)
                        else
                            Canvas(Modifier.size(StudioTheme.iconSize)) {
                                drawCircle(
                                    if (mode == CanvasBackground.White) Color.White
                                    else StudioTheme.canvasGray
                                )
                            }
                    },
                    trailingIcon = {
                        if (background == mode) StudioIcon(Glyph.Check, StudioTheme.accent)
                    },
                    onClick = {
                        expanded = false
                        controller.updatePreferences(
                            controller.preferences.copy(canvasBackground = mode)
                        )
                    },
                )
            }
            Text(
                tr("仅影响显示"),
                Modifier.padding(StudioTheme.canvasBackgroundHintPadding),
                fontSize = StudioTheme.canvasBackgroundHintSize,
                color = StudioTheme.muted,
            )
        }
    }
}
