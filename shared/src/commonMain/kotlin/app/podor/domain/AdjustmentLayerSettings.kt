package app.podor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class AdjustmentLayerSettings(
    val kind: AdjustmentKind,
    val brightness: Float = StudioDefaults.toneAmount,
    val contrast: Float = StudioDefaults.toneAmount,
    val saturation: Float = StudioDefaults.toneAmount,
    val curves: ColorCurves = ColorCurves(),
    @SerialName("gradient_map") val gradientMap: GradientMapSettings = GradientMapSettings(),
) {
    fun valid() =
        when (kind) {
            AdjustmentKind.Tone ->
                listOf(brightness, contrast, saturation).all { it.isFinite() && it in -1f..1f }
            AdjustmentKind.Curves -> curves.valid()
            AdjustmentKind.GradientMap -> gradientMap.valid()
            else -> false
        }

    fun request(): JsonObject = buildJsonObject {
        put("kind", Json.encodeToJsonElement(kind))
        when (kind) {
            AdjustmentKind.Tone -> {
                put("brightness", brightness)
                put("contrast", contrast)
                put("saturation", saturation)
            }
            AdjustmentKind.Curves -> put("curves", nativeParameters.encodeToJsonElement(curves))
            AdjustmentKind.GradientMap ->
                put("gradient_map", nativeParameters.encodeToJsonElement(gradientMap))
            else -> error("调整图层不支持此类型")
        }
    }

    fun editingSettings() =
        AdjustmentSettings.defaults(kind)
            .copy(
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                curves = curves,
                gradientMap = gradientMap,
            )

    companion object {
        private val nativeParameters = Json { encodeDefaults = true }

        fun from(settings: AdjustmentSettings) =
            AdjustmentLayerSettings(
                settings.kind,
                settings.brightness,
                settings.contrast,
                settings.saturation,
                settings.curves,
                settings.gradientMap,
            )
    }
}
