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
    val hexLabel = tr("HEX 颜色")
    var hsv by remember { mutableStateOf(HsvColor.fromArgb(controller.brush.color)) }
    var hex by
        remember(controller.brush.color) {
            mutableStateOf(
                (controller.brush.color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
            )
        }
    LaunchedEffect(controller.brush.color) {
        if (hsv.toArgb() != controller.brush.color) hsv = HsvColor.fromArgb(controller.brush.color)
    }
    fun update(value: HsvColor) {
        hsv = value
        controller.brush = controller.brush.copy(color = value.toArgb())
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("调色盘"), Modifier.weight(1f), fontSize = 12.sp, color = StudioTheme.muted)
        ToolButton(Glyph.Picker, "取色", controller.tool == Tool.Picker) {
            controller.tool = Tool.Picker
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
                        controller.brush = controller.brush.copy(color = 0xFF000000L or it)
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
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(controller.brush.color)))
    }
    SectionLabel("工作室色卡", "${StudioDefaults.palette.size}")
    StudioDefaults.palette.chunked(6).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            row.forEach { color ->
                ColorSwatch(color, controller.brush.color == color) {
                    controller.brush = controller.brush.copy(color = color)
                }
            }
        }
    }
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(StudioTheme.background).padding(14.dp)
    ) {
        LabeledSlider("饱和度", hsv.saturation, 0f..1f, "${(hsv.saturation*100).roundToInt()}%") {
            update(hsv.copy(saturation = it))
        }
        LabeledSlider("明度", hsv.brightness, 0f..1f, "${(hsv.brightness*100).roundToInt()}%") {
            update(hsv.copy(brightness = it))
        }
    }
}
