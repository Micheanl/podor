package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.presentation.StudioController
import kotlin.math.roundToInt
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Composable
fun AdjustmentControls(controller: StudioController) {
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0f) }
    var radius by remember { mutableStateOf(4f) }
    val locked =
        controller.document.layers.firstOrNull { it.id == controller.document.active }?.locked ==
            true
    val enabled = controller.ready && !controller.busy && !locked
    Text(
        tr(if (controller.document.selection != null) "仅作用于当前图层的选区" else "作用于当前图层"),
        fontSize = 11.sp,
        color = StudioTheme.muted,
    )
    SectionLabel("明暗与色彩")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledSlider("亮度", brightness, -1f..1f, "${(brightness*100).roundToInt()}") {
            brightness = it
        }
        LabeledSlider("对比度", contrast, -1f..1f, "${(contrast*100).roundToInt()}") { contrast = it }
        LabeledSlider("饱和度", saturation, -1f..1f, "${(saturation*100).roundToInt()}") {
            saturation = it
        }
        ActionButton(
            "应用色彩调整",
            {
                val b = brightness
                val c = contrast
                val s = saturation
                controller.command("tone") {
                    putJsonObject("settings") {
                        put("brightness", b)
                        put("contrast", c)
                        put("saturation", s)
                    }
                }
                brightness = 0f
                contrast = 0f
                saturation = 0f
            },
            enabled = enabled && (brightness != 0f || contrast != 0f || saturation != 0f),
            modifier = Modifier.fillMaxWidth(),
            glyph = Glyph.Check,
        )
    }
    HorizontalDivider(color = StudioTheme.border)
    Column {
        SectionLabel("高斯模糊")
        LabeledSlider("半径", radius, 0.5f..32f, "${(radius*10).roundToInt()/10f} px") { radius = it }
        ActionButton(
            "应用模糊",
            {
                val value = radius
                controller.command("blur") { put("sigma", value) }
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            primary = false,
        )
    }
    HorizontalDivider(color = StudioTheme.border)
    Column {
        SectionLabel("填充设置")
        LabeledSlider(
            "颜色容差",
            controller.fillTolerance,
            0f..255f,
            "${controller.fillTolerance.roundToInt()}",
        ) {
            controller.fillTolerance = it
        }
    }
    if (controller.document.selection != null)
        TextButton({ controller.clearSelection() }) { Text(tr("取消选区"), fontSize = 12.sp) }
}
