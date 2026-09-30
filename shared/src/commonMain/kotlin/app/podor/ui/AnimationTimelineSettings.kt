package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlinx.serialization.json.put

@Composable
internal fun AnimationTimelineSettings(
    controller: StudioController,
    compact: Boolean,
    onDismiss: () -> Unit,
    onEditRange: () -> Unit,
) {
    val animation = controller.document.animation ?: return
    val active = animation.frames.first { it.id == animation.activeFrameId }
    val index = animation.frames.indexOf(active)
    val tag = animation.tags.firstOrNull { it.id == controller.animationTagId }
    val cel = animation.exposure(active.id, controller.document.active)?.celId
    var duration by
        remember(active.id, active.durationMs) {
            mutableStateOf(active.durationMs.toString())
        }
    var ranges by remember { mutableStateOf(false) }
    val durationLabel = tr("帧时长")
    val settingsLabel = tr("动画设置")
    val validDuration = duration.toIntOrNull()?.takeIf { it in 1..60000 }
    val inputBorder = StudioTheme.border
    val applyDuration = {
        validDuration?.let { value ->
            onDismiss()
            controller.animationCommand("set_frame_duration") {
                put("frame_id", active.id)
                put("duration_ms", value)
            }
        }
    }
    Column(
        Modifier.width(StudioTheme.animationSettingsWidth)
            .semantics { paneTitle = settingsLabel }
            .padding(horizontal = StudioTheme.animationSettingsPadding)
    ) {
        Row(
            Modifier.fillMaxWidth().height(StudioTheme.controlSize),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                durationLabel,
                Modifier.weight(1f),
                fontSize = StudioTheme.canvasLabelSize,
                color = StudioTheme.muted,
            )
            BasicTextField(
                duration,
                { duration = it.filter(Char::isDigit).take(5) },
                Modifier.width(StudioTheme.animationDurationInputWidth)
                    .height(StudioTheme.controlSize)
                    .drawBehind {
                        drawLine(
                            inputBorder,
                            Offset(0f, size.height),
                            Offset(size.width, size.height),
                        )
                    }
                    .semantics { contentDescription = durationLabel },
                singleLine = true,
                textStyle =
                    TextStyle(
                        color =
                            if (validDuration == null) MaterialTheme.colorScheme.error
                            else StudioTheme.text,
                        fontSize = StudioTheme.canvasLabelSize,
                        fontFamily = FontFamily.Monospace,
                    ),
                cursorBrush = SolidColor(StudioTheme.text),
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { applyDuration() }),
                decorationBox = { field ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        field()
                    }
                },
            )
            Text("ms", fontSize = StudioTheme.canvasCaptionSize, color = StudioTheme.muted)
            ToolButton(Glyph.Check, "确认帧时长", plain = true, enabled = validDuration != null) {
                applyDuration()
            }
        }
        HorizontalDivider(color = StudioTheme.border)
        AnimationDirectionPicker(tag?.direction ?: controller.animationDirection) {
            controller.animationDirection = it
            controller.animationTagId = null
            onDismiss()
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                StudioTextButton({ ranges = true }) {
                    Text(
                        tag?.name ?: tr("全部帧"),
                        Modifier.weight(1f),
                        fontSize = StudioTheme.canvasLabelSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StudioIcon(Glyph.Chevron, StudioTheme.muted)
                }
                StudioDropdownMenu(ranges, { ranges = false }) {
                    StudioDropdownMenuItem(
                        text = { Text(tr("全部帧")) },
                        onClick = {
                            ranges = false
                            controller.animationTagId = null
                            onDismiss()
                        },
                        trailingIcon = { if (tag == null) StudioIcon(Glyph.Check) },
                    )
                    animation.tags.forEach { value ->
                        StudioDropdownMenuItem(
                            text = { Text(value.name) },
                            onClick = {
                                ranges = false
                                controller.animationTagId = value.id
                                onDismiss()
                            },
                            trailingIcon = { if (tag?.id == value.id) StudioIcon(Glyph.Check) },
                        )
                    }
                }
            }
            ToolButton(
                Glyph.Plus,
                "新建播放范围",
                plain = true,
                enabled = animation.tags.size < animation.maxTags,
            ) {
                controller.animationTagId = null
                onEditRange()
            }
            if (tag != null) {
                ToolButton(Glyph.Settings, "编辑播放范围", plain = true, onClick = onEditRange)
                ToolButton(Glyph.Trash, "删除播放范围", plain = true) {
                    onDismiss()
                    controller.animationCommand("delete_frame_tag") { put("id", tag.id) }
                }
            }
        }
        HorizontalDivider(color = StudioTheme.border)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ToolButton(Glyph.Backward, "前移帧", plain = true, enabled = index > 0) {
                onDismiss()
                controller.reorderAnimationFrame(active.id, index - 1)
            }
            ToolButton(
                Glyph.Forward,
                "后移帧",
                plain = true,
                enabled = index < animation.frames.lastIndex,
            ) {
                onDismiss()
                controller.reorderAnimationFrame(active.id, index + 1)
            }
            ToolButton(
                Glyph.Unlink,
                "解除单元共享",
                plain = true,
                enabled =
                    cel != null &&
                        animation.frames.count {
                            animation.exposure(it.id, controller.document.active)?.celId == cel
                        } > 1,
            ) {
                onDismiss()
                controller.animationCommand("unlink_cel") {
                    put("frame_id", active.id)
                    put("layer_id", controller.document.active)
                    put("cel_id", cel)
                }
            }
            ToolButton(Glyph.Eraser, "清除当前帧单元", plain = true, enabled = cel != null) {
                onDismiss()
                controller.animationCommand("clear_cel") {
                    put("frame_id", active.id)
                    put("layer_id", controller.document.active)
                    put("cel_id", cel)
                }
            }
        }
        if (compact) {
            HorizontalDivider(color = StudioTheme.border)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ToolButton(
                    Glyph.Link,
                    "共享复制帧",
                    plain = true,
                    enabled = animation.frames.size < animation.maxFrames,
                ) {
                    onDismiss()
                    controller.animationCommand("duplicate_frame") {
                        put("frame_id", active.id)
                        put("index", index + 1)
                        put("linked", true)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AnimationDirectionPicker(
    direction: AnimationDirection,
    onChange: (AnimationDirection) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        AnimationDirection.entries.forEach { value ->
            ToolButton(
                when (value) {
                    AnimationDirection.Forward -> Glyph.Forward
                    AnimationDirection.Reverse -> Glyph.Backward
                    AnimationDirection.PingPong -> Glyph.Swap
                    AnimationDirection.PingPongReverse -> Glyph.SwapReverse
                },
                value.label(),
                selected = direction == value,
                plain = true,
            ) {
                onChange(value)
            }
        }
    }
}
