package app.podor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.podor.domain.CanvasAnchor
import app.podor.domain.StudioDefaults
import app.podor.domain.validCanvasSize
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun CanvasSizeDialog(controller: StudioController, onDismiss: () -> Unit) {
    val original = remember { controller.document }
    var width by remember { mutableStateOf(original.width.toString()) }
    var height by remember { mutableStateOf(original.height.toString()) }
    var anchor by remember { mutableStateOf(StudioDefaults.canvasAnchor) }
    val valid = validCanvasSize(width.toIntOrNull(), height.toIntOrNull())
    StudioAlertDialog(
        title = "画布大小",
        glyph = Glyph.Fit,
        confirmLabel = "应用",
        onDismissRequest = onDismiss,
        enabled =
            valid &&
                !controller.busy &&
                (width.toIntOrNull() != original.width || height.toIntOrNull() != original.height),
        onConfirm = {
            if (valid)
                controller.resizeCanvas(width.toInt(), height.toInt(), anchor, original.revision)
        },
        text = {
            CanvasSizeSettings(
                original.width,
                original.height,
                controller.previews.images[0],
                width,
                height,
                anchor,
                { width = it },
                { height = it },
                { anchor = it },
            )
        },
    )
}

@Composable
fun CanvasSizeSettings(
    originalWidth: Int,
    originalHeight: Int,
    preview: ImageBitmap?,
    width: String,
    height: String,
    anchor: CanvasAnchor,
    onWidth: (String) -> Unit,
    onHeight: (String) -> Unit,
    onAnchor: (CanvasAnchor) -> Unit,
) {
    val valid = validCanvasSize(width.toIntOrNull(), height.toIntOrNull())
    val newWidth = if (valid) width.toInt() else originalWidth
    val newHeight = if (valid) height.toInt() else originalHeight
    val cropped = newWidth < originalWidth || newHeight < originalHeight
    Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.canvasSettingsGap)) {
        CanvasSizePreview(originalWidth, originalHeight, newWidth, newHeight, anchor, preview)
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTheme.canvasFieldsGap)) {
            OutlinedTextField(
                width,
                { onWidth(it.filter(Char::isDigit).take(5)) },
                Modifier.weight(1f),
                label = { Text(tr("宽度")) },
                suffix = { Text("px") },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                height,
                { onHeight(it.filter(Char::isDigit).take(5)) },
                Modifier.weight(1f),
                label = { Text(tr("高度")) },
                suffix = { Text("px") },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.canvasLabelGap)) {
                Text(tr("定位"), fontSize = StudioTheme.canvasLabelSize)
                Text(
                    "$originalWidth × $originalHeight → $newWidth × $newHeight",
                    color = StudioTheme.muted,
                    fontSize = StudioTheme.canvasCaptionSize,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.canvasAnchorGap)) {
                CanvasAnchor.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioTheme.canvasAnchorGap)) {
                        row.forEach { item ->
                            val label = tr(item.label)
                            Box(
                                Modifier.size(StudioTheme.canvasAnchorSize)
                                    .clip(CircleShape)
                                    .background(
                                        if (item == anchor) StudioTheme.selection
                                        else StudioTheme.elevated
                                    )
                                    .selectable(item == anchor, role = Role.RadioButton) {
                                        onAnchor(item)
                                    }
                                    .semantics { contentDescription = label },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (item == anchor)
                                    StudioIcon(
                                        Glyph.Check,
                                        StudioTheme.onSelection,
                                        Modifier.size(StudioTheme.canvasAnchorIcon),
                                    )
                                else
                                    Box(
                                        Modifier.size(StudioTheme.canvasAnchorDot)
                                            .background(StudioTheme.muted, CircleShape)
                                    )
                            }
                        }
                    }
                }
            }
        }
        Text(
            tr(if (!valid) "尺寸超出限制" else if (cropped) "边界外的像素会裁切，可撤销。" else "新增区域透明，笔迹大小不变。"),
            color = if (cropped || !valid) StudioTheme.accent else StudioTheme.muted,
            fontSize = StudioTheme.canvasCaptionSize,
        )
    }
}

@Composable
private fun CanvasSizePreview(
    width: Int,
    height: Int,
    newWidth: Int,
    newHeight: Int,
    anchor: CanvasAnchor,
    preview: ImageBitmap?,
) {
    val duration = tween<Float>(StudioMotion.panelMillis, easing = StudioMotion.easing)
    val animatedWidth = animateFloatAsState(newWidth.toFloat(), duration)
    val animatedHeight = animateFloatAsState(newHeight.toFloat(), duration)
    val animatedX = animateFloatAsState(anchor.offsetX(width, newWidth).toFloat(), duration)
    val animatedY = animateFloatAsState(anchor.offsetY(height, newHeight).toFloat(), duration)
    val oldOutline = remember { PathEffect.dashPathEffect(StudioTheme.canvasOutlineDash) }
    Canvas(
        Modifier.fillMaxWidth()
            .height(StudioTheme.canvasSizePreviewHeight)
            .clip(StudioTheme.canvasPreviewShape)
            .background(StudioTheme.background)
    ) {
        val w = animatedWidth.value
        val h = animatedHeight.value
        val dx = animatedX.value
        val dy = animatedY.value
        val left = minOf(0f, dx)
        val top = minOf(0f, dy)
        val right = maxOf(w, dx + width)
        val bottom = maxOf(h, dy + height)
        val padding = StudioTheme.canvasSizePreviewPadding.toPx()
        val scale =
            minOf(
                (size.width - padding * 2) / (right - left),
                (size.height - padding * 2) / (bottom - top),
            )
        val origin =
            Offset(
                (size.width - (right - left) * scale) / 2 - left * scale,
                (size.height - (bottom - top) * scale) / 2 - top * scale,
            )
        val oldOrigin = origin + Offset(dx * scale, dy * scale)
        val oldSize = Size(width * scale, height * scale)
        val target = Size(w * scale, h * scale)
        drawRect(StudioTheme.checkerLight, oldOrigin, oldSize)
        drawRect(StudioTheme.checkerLight, origin, target)
        clipRect(origin.x, origin.y, origin.x + target.width, origin.y + target.height) {
            val cell = StudioTheme.canvasCheckerCell.toPx()
            for (y in 0..(target.height / cell).toInt()) for (x in
                0..(target.width / cell).toInt()) {
                if ((x + y) % 2 == 0)
                    drawRect(
                        StudioTheme.checkerDark,
                        origin + Offset(x * cell, y * cell),
                        Size(cell, cell),
                    )
            }
        }
        preview?.let {
            val sourceWidth = (width * it.width / maxOf(width, height)).coerceAtLeast(1)
            val sourceHeight = (height * it.height / maxOf(width, height)).coerceAtLeast(1)
            drawImage(
                it,
                srcOffset = IntOffset((it.width - sourceWidth) / 2, (it.height - sourceHeight) / 2),
                srcSize = IntSize(sourceWidth, sourceHeight),
                dstOffset = IntOffset(oldOrigin.x.roundToInt(), oldOrigin.y.roundToInt()),
                dstSize =
                    IntSize(
                        oldSize.width.roundToInt().coerceAtLeast(1),
                        oldSize.height.roundToInt().coerceAtLeast(1),
                    ),
            )
        }
        clipRect(
            oldOrigin.x,
            oldOrigin.y,
            oldOrigin.x + oldSize.width,
            oldOrigin.y + oldSize.height,
        ) {
            val shade = StudioTheme.canvasCropShade
            drawRect(shade, Offset.Zero, Size(size.width, origin.y.coerceAtLeast(0f)))
            drawRect(
                shade,
                Offset(0f, origin.y + target.height),
                Size(size.width, (size.height - origin.y - target.height).coerceAtLeast(0f)),
            )
            drawRect(shade, Offset(0f, origin.y), Size(origin.x.coerceAtLeast(0f), target.height))
            drawRect(
                shade,
                Offset(origin.x + target.width, origin.y),
                Size((size.width - origin.x - target.width).coerceAtLeast(0f), target.height),
            )
        }
        drawRect(
            StudioTheme.muted,
            oldOrigin,
            oldSize,
            style = Stroke(StudioTheme.canvasPreviewLine.toPx(), pathEffect = oldOutline),
        )
        drawRect(
            StudioTheme.accent,
            origin,
            target,
            style = Stroke(StudioTheme.canvasPreviewLine.toPx()),
        )
    }
}
