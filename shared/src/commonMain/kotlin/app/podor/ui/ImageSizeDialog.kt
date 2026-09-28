package app.podor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.podor.domain.ImageSize
import app.podor.domain.ResampleFilter
import app.podor.domain.StudioDefaults
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun ImageSizeDialog(controller: StudioController, onDismiss: () -> Unit) {
    val original = remember { controller.document }
    var settings by remember { mutableStateOf(ImageSize(original.width, original.height)) }
    StudioAlertDialog(
        title = "图像尺寸",
        glyph = Glyph.Fit,
        confirmLabel = "应用",
        onDismissRequest = onDismiss,
        enabled = settings.valid && settings.changed && !controller.busy,
        onConfirm = {
            if (settings.valid)
                controller.resizeImage(
                    settings.width.toInt(),
                    settings.height.toInt(),
                    settings.filter,
                    original.revision,
                )
        },
        text = { ImageSizeSettings(settings, controller.previews.images[0]) { settings = it } },
    )
}

@Composable
fun ImageSizeSettings(settings: ImageSize, preview: ImageBitmap?, onChange: (ImageSize) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.canvasSettingsGap)) {
        ImageSizePreview(settings, preview)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${settings.originalWidth} × ${settings.originalHeight}",
                color = StudioTheme.muted,
                fontSize = StudioTheme.canvasCaptionSize,
            )
            Text(
                "${settings.width.ifEmpty { "—" }} × ${settings.height.ifEmpty { "—" }} px",
                color = if (settings.valid) StudioTheme.accent else MaterialTheme.colorScheme.error,
                fontSize = StudioTheme.canvasCaptionSize,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.canvasLabelGap),
        ) {
            OutlinedTextField(
                settings.width,
                { onChange(settings.withWidth(it)) },
                Modifier.weight(1f),
                label = { Text(tr("宽度")) },
                suffix = { Text("px") },
                singleLine = true,
                isError = !settings.valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            ToolButton(Glyph.Lock, "锁定比例", settings.locked) {
                onChange(settings.withLock(!settings.locked))
            }
            OutlinedTextField(
                settings.height,
                { onChange(settings.withHeight(it)) },
                Modifier.weight(1f),
                label = { Text(tr("高度")) },
                suffix = { Text("px") },
                singleLine = true,
                isError = !settings.valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            StudioDefaults.imageScalePresets.forEach { percent ->
                StudioTextButton({ onChange(settings.scaled(percent)) }, Modifier.weight(1f)) {
                    Text("$percent%")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.canvasFieldsGap),
        ) {
            ResampleFilter.entries.forEach { filter ->
                ChoiceSurface(
                    settings.filter == filter,
                    { onChange(settings.copy(filter = filter)) },
                    Modifier.weight(1f),
                ) {
                    Text(tr(filter.label), fontSize = StudioTheme.canvasLabelSize)
                    Spacer(Modifier.height(StudioTheme.canvasLabelGap))
                    Text(
                        tr(filter.description),
                        fontSize = StudioTheme.canvasCaptionSize,
                        color = StudioTheme.muted,
                    )
                }
            }
        }
        Text(
            tr(if (settings.valid) "所有图层一起缩放，可撤销。" else "尺寸超出限制"),
            fontSize = StudioTheme.canvasCaptionSize,
            color = StudioTheme.muted,
        )
    }
}

@Composable
private fun ImageSizePreview(settings: ImageSize, preview: ImageBitmap?) {
    val duration = tween<Float>(StudioMotion.panelMillis, easing = StudioMotion.easing)
    val width =
        animateFloatAsState(
            if (settings.valid) settings.width.toFloat() else settings.originalWidth.toFloat(),
            duration,
        )
    val height =
        animateFloatAsState(
            if (settings.valid) settings.height.toFloat() else settings.originalHeight.toFloat(),
            duration,
        )
    val outline = remember { PathEffect.dashPathEffect(StudioTheme.canvasOutlineDash) }
    Canvas(
        Modifier.fillMaxWidth()
            .height(StudioTheme.canvasSizePreviewHeight)
            .clip(StudioTheme.canvasPreviewShape)
            .background(StudioTheme.background)
    ) {
        val inset = StudioTheme.canvasSizePreviewPadding.toPx()
        val scale =
            minOf(
                (size.width - inset * 2) / maxOf(width.value, settings.originalWidth.toFloat()),
                (size.height - inset * 2) / maxOf(height.value, settings.originalHeight.toFloat()),
            )
        val target = Size(width.value * scale, height.value * scale)
        val origin = Offset((size.width - target.width) / 2, (size.height - target.height) / 2)
        val previous = Size(settings.originalWidth * scale, settings.originalHeight * scale)
        drawRect(
            StudioTheme.muted,
            Offset((size.width - previous.width) / 2, (size.height - previous.height) / 2),
            previous,
            style = Stroke(StudioTheme.canvasPreviewLine.toPx(), pathEffect = outline),
        )
        clipRect(origin.x, origin.y, origin.x + target.width, origin.y + target.height) {
            drawRect(StudioTheme.checkerLight, origin, target)
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
            preview?.let {
                val sourceWidth =
                    (settings.originalWidth * it.width /
                            maxOf(settings.originalWidth, settings.originalHeight))
                        .coerceAtLeast(1)
                val sourceHeight =
                    (settings.originalHeight * it.height /
                            maxOf(settings.originalWidth, settings.originalHeight))
                        .coerceAtLeast(1)
                drawImage(
                    it,
                    srcOffset =
                        IntOffset((it.width - sourceWidth) / 2, (it.height - sourceHeight) / 2),
                    srcSize = IntSize(sourceWidth, sourceHeight),
                    dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    dstSize =
                        IntSize(
                            target.width.roundToInt().coerceAtLeast(1),
                            target.height.roundToInt().coerceAtLeast(1),
                        ),
                )
            }
        }
        drawRect(
            StudioTheme.accent,
            origin,
            target,
            style = Stroke(StudioTheme.canvasPreviewLine.toPx()),
        )
    }
}
