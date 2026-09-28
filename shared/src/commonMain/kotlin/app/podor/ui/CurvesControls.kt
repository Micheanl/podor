package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun CurvesControls(controller: StudioController) {
    val preview = controller.adjustmentPreview ?: return
    var channel by remember(preview) { mutableStateOf(CurveChannel.Rgb) }
    var selected by remember(channel) { mutableIntStateOf(0) }
    val curve = preview.settings.curves[channel]
    val index = selected.coerceIn(curve.points.indices)
    val enabled = !preview.committing
    var inputValid by remember(channel, index) { mutableStateOf(true) }
    var outputValid by remember(channel, index) { mutableStateOf(true) }
    SideEffect { preview.inputValid = inputValid && outputValid }
    DisposableEffect(preview) { onDispose { preview.inputValid = true } }
    fun update(curve: ToneCurve) {
        val settings = preview.settings
        controller.updateAdjustment(
            settings.copy(curves = settings.curves.withCurve(channel, curve))
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.curveGap)) {
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.curveGap),
        ) {
            CurveChannel.entries.forEach { entry ->
                val label = tr(entry.label)
                ChoiceSurface(
                    channel == entry,
                    { if (enabled) channel = entry },
                    Modifier.weight(1f).semantics { contentDescription = label },
                ) {
                    Text(
                        entry.symbol,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = curveColor(entry),
                        fontSize = StudioTheme.curveLabelSize,
                    )
                }
            }
        }
        CurveGraph(
            curve,
            preview.histogram.getOrNull(channel.ordinal).orEmpty(),
            channel,
            index,
            enabled,
            { selected = it },
            ::update,
        )
        val point = curve.points[index]
        key(channel, index) {
            Row(horizontalArrangement = Arrangement.spacedBy(StudioTheme.curveGap)) {
                CurveValue(
                    "输入",
                    point.x,
                    Modifier.weight(1f),
                    enabled && index in 1 until curve.points.lastIndex,
                    if (index in 1 until curve.points.lastIndex)
                        curve.points[index - 1].x + 1 until curve.points[index + 1].x
                    else point.x..point.x,
                    {
                        inputValid = it
                        preview.inputValid = inputValid && outputValid
                    },
                ) {
                    update(curve.move(index, point.copy(x = it)))
                }
                CurveValue(
                    "输出",
                    point.y,
                    Modifier.weight(1f),
                    enabled,
                    0..255,
                    {
                        outputValid = it
                        preview.inputValid = inputValid && outputValid
                    },
                ) {
                    update(curve.move(index, point.copy(y = it)))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("点击加点，拖动调整"),
                Modifier.weight(1f),
                color = StudioTheme.muted,
                fontSize = StudioTheme.curveLabelSize,
            )
            ToolButton(
                Glyph.Trash,
                "删除控制点",
                enabled = enabled && index in 1 until curve.points.lastIndex,
            ) {
                update(curve.remove(index))
                selected = (index - 1).coerceAtLeast(0)
            }
            ToolButton(Glyph.Rotate, "重置通道", enabled = enabled && curve != ToneCurve()) {
                update(ToneCurve())
                selected = 0
            }
        }
    }
}

@Composable
private fun CurveValue(
    label: String,
    value: Int,
    modifier: Modifier,
    enabled: Boolean,
    range: IntRange,
    onValidity: (Boolean) -> Unit,
    onValue: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        {
            text = it.filter(Char::isDigit).take(3)
            val value = text.toIntOrNull()
            val valid = value != null && value in range
            onValidity(valid)
            if (valid) onValue(value)
        },
        modifier.onFocusChanged {
            if (!it.isFocused) {
                text = value.toString()
                onValidity(true)
            }
        },
        label = { Text(tr(label), fontSize = StudioTheme.curveLabelSize) },
        enabled = enabled,
        singleLine = true,
        isError = text.isNotEmpty() && text.toIntOrNull() !in range,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun curveColor(channel: CurveChannel) =
    when (channel) {
        CurveChannel.Rgb -> StudioTheme.accent
        CurveChannel.Red -> StudioTheme.curveRed
        CurveChannel.Green -> StudioTheme.curveGreen
        CurveChannel.Blue -> StudioTheme.curveBlue
    }

@Composable
private fun CurveGraph(
    curve: ToneCurve,
    histogram: List<Float>,
    channel: CurveChannel,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onChange: (ToneCurve) -> Unit,
) {
    val current by rememberUpdatedState(curve)
    val change by rememberUpdatedState(onChange)
    val focus = remember { FocusRequester() }
    val label = tr("曲线编辑器")
    val tint = curveColor(channel)
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(1f)
            .clip(StudioTheme.brushGraphShape)
            .background(StudioTheme.background)
            .semantics { contentDescription = label }
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (
                    !enabled ||
                        event.type != KeyEventType.KeyDown ||
                        event.isCtrlPressed ||
                        event.isMetaPressed ||
                        event.isAltPressed
                )
                    false
                else {
                    val amount = if (event.isShiftPressed) 10 else 1
                    val point = curve.points[selected]
                    when (event.key) {
                        Key.DirectionLeft ->
                            onChange(curve.move(selected, point.copy(x = point.x - amount)))
                        Key.DirectionRight ->
                            onChange(curve.move(selected, point.copy(x = point.x + amount)))
                        Key.DirectionUp ->
                            onChange(curve.move(selected, point.copy(y = point.y + amount)))
                        Key.DirectionDown ->
                            onChange(curve.move(selected, point.copy(y = point.y - amount)))
                        Key.Delete,
                        Key.Backspace -> {
                            onChange(curve.remove(selected))
                            onSelect((selected - 1).coerceAtLeast(0))
                        }
                        else -> return@onKeyEvent false
                    }
                    true
                }
            }
            .focusable(enabled)
            .pointerInput(channel, enabled) {
                if (!enabled) return@pointerInput
                val inset = StudioTheme.curveInset.toPx()
                val hit = StudioTheme.curveHitRadius.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    focus.requestFocus()
                    down.consume()
                    val width = (size.width - 2 * inset).coerceAtLeast(1f)
                    val height = (size.height - 2 * inset).coerceAtLeast(1f)
                    fun point(position: Offset) =
                        CurvePoint(
                            ((position.x - inset) / width * 255).roundToInt().coerceIn(0, 255),
                            ((1 - (position.y - inset) / height) * 255)
                                .roundToInt()
                                .coerceIn(0, 255),
                        )
                    var edited = current
                    var index =
                        edited.points.indices.minBy { i ->
                            val p = edited.points[i]
                            (down.position -
                                    Offset(
                                        inset + width * p.x / 255,
                                        inset + height * (1 - p.y / 255f),
                                    ))
                                .getDistanceSquared()
                        }
                    val near = edited.points[index]
                    if (
                        (down.position -
                                Offset(
                                    inset + width * near.x / 255,
                                    inset + height * (1 - near.y / 255f),
                                ))
                            .getDistance() > hit
                    ) {
                        val p = point(down.position)
                        val added = edited.insert(p)
                        if (added == edited) return@awaitEachGesture
                        edited = added
                        index = edited.points.indexOfFirst { it.x == p.x }
                        change(edited)
                    }
                    onSelect(index)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            pointer.consume()
                            break
                        }
                        if (pointer.position != pointer.previousPosition) {
                            edited = edited.move(index, point(pointer.position))
                            change(edited)
                        }
                        pointer.consume()
                    }
                }
            }
            .drawWithCache {
                val inset = StudioTheme.curveInset.toPx()
                val width = (size.width - 2 * inset).coerceAtLeast(1f)
                val height = (size.height - 2 * inset).coerceAtLeast(1f)
                fun point(x: Float, y: Float) =
                    Offset(inset + width * x / 255, inset + height * (1 - y / 255))
                val values = curve.samples()
                val path =
                    Path().apply {
                        values.forEachIndexed { x, y ->
                            val p = point(x.toFloat(), y)
                            if (x == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                        }
                    }
                val distribution =
                    Path().apply {
                        moveTo(inset, inset + height)
                        histogram.forEachIndexed { x, v ->
                            lineTo(
                                inset + width * x / 255,
                                inset + height * (1 - sqrt(v) * StudioTheme.curveHistogramHeight),
                            )
                        }
                        lineTo(inset + width, inset + height)
                        close()
                    }
                val stroke = Stroke(StudioTheme.curveLine.toPx())
                val guide =
                    PathEffect.dashPathEffect(
                        floatArrayOf(
                            StudioTheme.curveGuideDash.toPx(),
                            StudioTheme.curveGuideDash.toPx(),
                        )
                    )
                val gradient =
                    Brush.horizontalGradient(listOf(Color.Black, Color.White), inset, inset + width)
                onDrawBehind {
                    for (i in 0..4) {
                        val t = i * 255f / 4
                        drawLine(
                            StudioTheme.border.copy(alpha = 0.5f),
                            point(t, 0f),
                            point(t, 255f),
                        )
                        drawLine(
                            StudioTheme.border.copy(alpha = 0.5f),
                            point(0f, t),
                            point(255f, t),
                        )
                    }
                    drawPath(distribution, tint.copy(alpha = 0.15f))
                    drawLine(
                        StudioTheme.muted.copy(alpha = 0.25f),
                        point(0f, 0f),
                        point(255f, 255f),
                        pathEffect = guide,
                    )
                    drawPath(path, tint, style = stroke)
                    curve.points.forEachIndexed { index, p ->
                        val center = point(p.x.toFloat(), p.y.toFloat())
                        if (index == selected) {
                            drawLine(
                                tint.copy(alpha = 0.3f),
                                point(p.x.toFloat(), 0f),
                                center,
                                pathEffect = guide,
                            )
                            drawCircle(
                                tint.copy(alpha = 0.15f),
                                StudioTheme.curveHitRadius.toPx(),
                                center,
                            )
                        }
                        drawCircle(
                            StudioTheme.background,
                            StudioTheme.curvePointRadius.toPx(),
                            center,
                        )
                        drawCircle(
                            tint,
                            StudioTheme.curvePointRadius.toPx(),
                            center,
                            style =
                                if (index == selected) androidx.compose.ui.graphics.drawscope.Fill
                                else stroke,
                        )
                    }
                    drawRect(
                        gradient,
                        Offset(inset, size.height - StudioTheme.curveScaleHeight.toPx()),
                        Size(width, StudioTheme.curveScaleHeight.toPx()),
                    )
                }
            }
    )
}
