package app.podor.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlinx.serialization.json.put

@Composable
fun NewCanvasDialog(controller: StudioController, onDismiss: () -> Unit) {
    var width by remember { mutableStateOf(controller.document.width.toString()) }
    var height by remember { mutableStateOf(controller.document.height.toString()) }
    val canvasWidth = width.toIntOrNull()
    val canvasHeight = height.toIntOrNull()
    val valid = validCanvasSize(canvasWidth, canvasHeight)
    StudioAlertDialog(
        onDismissRequest = onDismiss,
        title = "新建画布",
        glyph = Glyph.Selection,
        confirmLabel = "创建",
        enabled = valid && !controller.busy,
        onConfirm = {
            if (valid)
                controller.command("new") {
                    put("width", canvasWidth)
                    put("height", canvasHeight)
                }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val ratio =
                    ((width.toFloatOrNull() ?: 1f).coerceAtLeast(1f) /
                            (height.toFloatOrNull() ?: 1f).coerceAtLeast(1f))
                        .coerceIn(0.1f, 10f)
                val previewWidth by
                    animateDpAsState(
                        (if (ratio > 2f) 200f else 100f * ratio).dp,
                        tween(StudioMotion.panelMillis),
                    )
                val previewHeight by
                    animateDpAsState(
                        (if (ratio > 2f) 200f / ratio else 100f).dp,
                        tween(StudioMotion.panelMillis),
                    )
                Box(
                    Modifier.fillMaxWidth()
                        .height(148.dp)
                        .background(StudioTheme.background, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(previewWidth, previewHeight)
                            .shadow(12.dp, RoundedCornerShape(3.dp))
                            .background(StudioTheme.text, RoundedCornerShape(3.dp))
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        width,
                        { width = it.filter(Char::isDigit).take(5) },
                        Modifier.weight(1f),
                        label = { Text(tr("宽度")) },
                        suffix = { Text("px") },
                        singleLine = true,
                        isError = !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    ToolButton(Glyph.Swap, "交换宽高") {
                        val old = width
                        width = height
                        height = old
                    }
                    OutlinedTextField(
                        height,
                        { height = it.filter(Char::isDigit).take(5) },
                        Modifier.weight(1f),
                        label = { Text(tr("高度")) },
                        suffix = { Text("px") },
                        singleLine = true,
                        isError = !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                StudioDefaults.canvasPresets.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { preset ->
                            val selected =
                                width == preset.width.toString() &&
                                    height == preset.height.toString()
                            Row(
                                Modifier.weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (selected) StudioTheme.selection.copy(alpha = 0.25f)
                                        else StudioTheme.elevated
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) StudioTheme.selectionBorder
                                        else Color.Transparent,
                                        RoundedCornerShape(16.dp),
                                    )
                                    .clickable {
                                        width = preset.width.toString()
                                        height = preset.height.toString()
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Text(tr(preset.label), fontSize = 11.sp)
                                    Text(
                                        "${preset.width} × ${preset.height}",
                                        fontSize = 10.sp,
                                        color = StudioTheme.muted,
                                    )
                                }
                                if (selected)
                                    Box(
                                        Modifier.size(20.dp)
                                            .background(StudioTheme.selection, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        StudioIcon(
                                            Glyph.Check,
                                            StudioTheme.onSelection,
                                            Modifier.size(12.dp),
                                        )
                                    }
                            }
                        }
                    }
                }
                Text(
                    if (valid) "${width.toLong()*height.toLong()/100_000/10f} MP" else tr("尺寸超出限制"),
                    fontSize = 11.sp,
                    color = StudioTheme.muted,
                )
                Text(tr("请先保存当前工程，新画布会替换当前内容。"), fontSize = 11.sp, color = StudioTheme.muted)
            }
        },
    )
}
