package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun ColorControls(controller: StudioController) {
    if (
        controller.document.colorMode == DocumentColorMode.Indexed &&
            !controller.document.maskEditing
    ) {
        IndexedPaletteControls(controller)
        return
    }
    val color =
        if (controller.tool == Tool.Gradient) {
            if (controller.gradientEditingStart) controller.gradient.from
            else controller.gradient.to
        } else controller.brush.color
    fun changeColor(value: Long) {
        if (controller.tool == Tool.Gradient) {
            controller.gradient =
                if (controller.gradientEditingStart) controller.gradient.copy(from = value)
                else controller.gradient.copy(to = value)
        } else controller.brush = controller.brush.copy(color = value)
    }
    val hexLabel = tr("HEX 颜色")
    var hsv by remember { mutableStateOf(HsvColor.fromArgb(color)) }
    var hex by
        remember(color) {
            mutableStateOf((color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase())
        }
    LaunchedEffect(color) {
        if (hsv.toArgb() != color) hsv = HsvColor.fromArgb(color)
    }
    fun update(value: HsvColor) {
        hsv = value
        changeColor(value.toArgb())
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("调色盘"), Modifier.weight(1f), fontSize = 12.sp, color = StudioTheme.muted)
        ToolButton(Glyph.Picker, "取色", controller.tool == Tool.Picker) {
            controller.tool = Tool.Picker
        }
        if (!controller.document.maskEditing)
            ToolButton(Glyph.PixelGrid, "转换为索引色", enabled = controller.ready && !controller.busy) {
                controller.convertColorMode(DocumentColorMode.Indexed)
            }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ColorWheel(hsv, ::update, Modifier.widthIn(max = StudioTheme.colorWheelSize).fillMaxWidth())
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(StudioTheme.background)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("HEX", fontSize = 10.sp, color = StudioTheme.muted)
        Spacer(Modifier.width(18.dp))
        Text("#", fontSize = 12.sp, color = StudioTheme.muted)
        BasicTextField(
            hex,
            { value ->
                hex = value.take(6)
                if (hex.length == 6)
                    hex.toLongOrNull(16)?.let {
                        changeColor(0xFF000000L or it)
                    }
            },
            singleLine = true,
            textStyle =
                TextStyle(
                    color = StudioTheme.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            cursorBrush = SolidColor(StudioTheme.accent),
            modifier = Modifier.weight(1f).semantics { contentDescription = hexLabel },
        )
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(color)))
    }
    if (!controller.document.maskEditing)
        controller.document.asepriteMetadata?.companionPalette?.let {
            ProjectPalette(it, color, ::changeColor)
        }
    PersonalPalette(controller, color, ::changeColor)
    SectionLabel("工作室色卡", "${StudioDefaults.palette.size}")
    StudioDefaults.palette.chunked(6).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            row.forEach { swatch ->
                ColorSwatch(swatch, color == swatch) {
                    changeColor(swatch)
                }
            }
        }
    }
    Column(Modifier.clip(StudioTheme.cardShape).background(StudioTheme.background).padding(14.dp)) {
        HsvSliders(hsv, ::update)
    }
    if (!controller.document.maskEditing) ColorHarmonyControls(color, ::changeColor)
}

@Composable
internal fun HsvSliders(hsv: HsvColor, update: (HsvColor) -> Unit) {
    LabeledSlider(
        "色相",
        hsv.hue,
        0f..360f,
        "${hsv.hue.roundToInt()}°",
        trackColors = remember { (0..6).map { Color(HsvColor(it * 60f, 1f, 1f).toArgb()) } },
    ) {
        update(hsv.copy(hue = it))
    }
    LabeledSlider(
        "饱和度",
        hsv.saturation,
        0f..1f,
        "${(hsv.saturation*100).roundToInt()}%",
        trackColors =
            listOf(
                Color(hsv.copy(saturation = 0f).toArgb()),
                Color(hsv.copy(saturation = 1f).toArgb()),
            ),
    ) {
        update(hsv.copy(saturation = it))
    }
    LabeledSlider(
        "明度",
        hsv.brightness,
        0f..1f,
        "${(hsv.brightness*100).roundToInt()}%",
        trackColors = listOf(Color.Black, Color(hsv.copy(brightness = 1f).toArgb())),
    ) {
        update(hsv.copy(brightness = it))
    }
}
