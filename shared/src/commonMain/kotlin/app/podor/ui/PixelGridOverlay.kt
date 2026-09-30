package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import app.podor.domain.StudioDefaults
import app.podor.presentation.StudioController
import kotlin.math.ceil
import kotlin.math.floor

internal fun visibleGridLines(start: Float, end: Float, step: Int, limit: Int): IntProgression {
    if (
        !start.isFinite() ||
            !end.isFinite() ||
            start > end ||
            step <= 0 ||
            limit <= 1 ||
            start >= limit ||
            end <= 0f
    )
        return IntRange.EMPTY
    val first = maxOf(step, (ceil(start.coerceAtLeast(0f) / step) * step).toInt())
    val last = minOf(limit - 1, (floor(end.coerceAtMost(limit.toFloat()) / step) * step).toInt())
    return first..last step step
}

@Composable
fun PixelGridOverlay(controller: StudioController, viewSize: Size, modifier: Modifier = Modifier) {
    val settings = controller.preferences.canvasGrid
    if (!settings.pixels && !settings.tiles) return
    Canvas(modifier.graphicsLayer()) {
        val document = controller.document
        val viewport = controller.viewport
        val scale = viewport.scale(viewSize, document)
        if (!scale.isFinite() || scale <= 0f) return@Canvas
        val pixels = settings.pixels && scale >= StudioDefaults.gridMinimumSpacing
        val columns =
            settings.tiles && settings.tileWidth * scale >= StudioDefaults.gridMinimumSpacing
        val rows =
            settings.tiles && settings.tileHeight * scale >= StudioDefaults.gridMinimumSpacing
        if (!pixels && !columns && !rows) return@Canvas
        val visible = viewport.visibleBounds(viewSize, document, size)
        if (visible.isEmpty) return@Canvas
        val origin = viewport.origin(viewSize, document)
        withTransform({
            translate(origin.x, origin.y)
            rotate(viewport.rotation, Offset.Zero)
            scale(scale * viewport.horizontalSign, scale, Offset.Zero)
        }) {
            clipRect(0f, 0f, document.width.toFloat(), document.height.toFloat()) {
                if (pixels) {
                    val width = StudioTheme.gridLineWidth.toPx() / scale
                    for (x in
                        visibleGridLines(visible.left, visible.right, 1, document.width)) drawLine(
                        StudioTheme.pixelGridColor,
                        Offset(x.toFloat(), visible.top),
                        Offset(x.toFloat(), visible.bottom),
                        width,
                    )
                    for (y in
                        visibleGridLines(visible.top, visible.bottom, 1, document.height)) drawLine(
                        StudioTheme.pixelGridColor,
                        Offset(visible.left, y.toFloat()),
                        Offset(visible.right, y.toFloat()),
                        width,
                    )
                }
                val width = StudioTheme.tileGridLineWidth.toPx() / scale
                if (columns)
                    for (x in
                        visibleGridLines(
                            visible.left,
                            visible.right,
                            settings.tileWidth,
                            document.width,
                        )) drawLine(
                        StudioTheme.tileGridColor,
                        Offset(x.toFloat(), visible.top),
                        Offset(x.toFloat(), visible.bottom),
                        width,
                    )
                if (rows)
                    for (y in
                        visibleGridLines(
                            visible.top,
                            visible.bottom,
                            settings.tileHeight,
                            document.height,
                        )) drawLine(
                        StudioTheme.tileGridColor,
                        Offset(visible.left, y.toFloat()),
                        Offset(visible.right, y.toFloat()),
                        width,
                    )
            }
        }
    }
}

@Composable
fun CanvasGridControls(controller: StudioController) {
    var expanded by remember { mutableStateOf(false) }
    val settings = controller.preferences.canvasGrid
    var width by
        remember(expanded, settings.tileWidth) { mutableStateOf(settings.tileWidth.toString()) }
    var height by
        remember(expanded, settings.tileHeight) { mutableStateOf(settings.tileHeight.toString()) }
    fun resize() {
        val next =
            settings.copy(
                tileWidth = width.toIntOrNull() ?: 0,
                tileHeight = height.toIntOrNull() ?: 0,
            )
        if (next.valid()) controller.changeCanvasGrid(next)
    }
    Box {
        ToolButton(
            Glyph.PixelGrid,
            "网格",
            selected = settings.pixels || settings.tiles,
            plain = true,
        ) {
            expanded = !expanded
        }
        StudioDropdownMenu(expanded, { expanded = false }) {
            Column(
                Modifier.width(StudioTheme.gridControlsWidth)
                    .padding(StudioTheme.gridControlsPadding),
                verticalArrangement = Arrangement.spacedBy(StudioTheme.gridInputSpacing),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolButton(Glyph.PixelGrid, "像素网格", selected = settings.pixels, plain = true) {
                        controller.changeCanvasGrid(settings.copy(pixels = !settings.pixels))
                    }
                    ToolButton(Glyph.TileGrid, "瓦片网格", selected = settings.tiles, plain = true) {
                        controller.changeCanvasGrid(settings.copy(tiles = !settings.tiles))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTheme.gridInputSpacing)) {
                    OutlinedTextField(
                        width,
                        {
                            width = it.filter(Char::isDigit).take(5)
                            resize()
                        },
                        Modifier.weight(1f),
                        label = { Text(tr("网格宽度")) },
                        suffix = { Text("px") },
                        singleLine = true,
                        isError = (width.toIntOrNull() ?: 0) !in 1..StudioDefaults.maxDimension,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        height,
                        {
                            height = it.filter(Char::isDigit).take(5)
                            resize()
                        },
                        Modifier.weight(1f),
                        label = { Text(tr("网格高度")) },
                        suffix = { Text("px") },
                        singleLine = true,
                        isError = (height.toIntOrNull() ?: 0) !in 1..StudioDefaults.maxDimension,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (side in listOf(16, 32, 64)) {
                        TextButton(
                            onClick = {
                                width = side.toString()
                                height = side.toString()
                                controller.changeCanvasGrid(
                                    settings.copy(tileWidth = side, tileHeight = side)
                                )
                            },
                            contentPadding = PaddingValues(StudioTheme.gridInputPadding),
                        ) {
                            Text("$side")
                        }
                    }
                }
            }
        }
    }
}
