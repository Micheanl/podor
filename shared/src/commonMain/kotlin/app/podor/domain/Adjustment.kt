package app.podor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AdjustmentKind(val label: String) {
    @SerialName("tone") Tone("明暗与色彩"),
    @SerialName("blur") Blur("高斯模糊"),
    @SerialName("layer_blend") LayerBlend("图层混合"),
    @SerialName("curves") Curves("曲线"),
}

@Serializable
data class AdjustmentSettings(
    val kind: AdjustmentKind,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val sigma: Float,
    val opacity: Float = StudioDefaults.layerOpacity,
    val blend: LayerBlendMode = LayerBlendMode.Normal,
    val curves: ColorCurves = ColorCurves(),
) {
    fun valid() =
        listOf(brightness, contrast, saturation).all { it.isFinite() && it in -1f..1f } &&
            sigma.isFinite() &&
            sigma in StudioDefaults.minBlurSigma..StudioDefaults.maxBlurSigma &&
            opacity.isFinite() &&
            opacity in 0f..1f &&
            curves.valid()

    companion object {
        fun defaults(kind: AdjustmentKind) =
            AdjustmentSettings(
                kind,
                StudioDefaults.toneAmount,
                StudioDefaults.toneAmount,
                StudioDefaults.toneAmount,
                StudioDefaults.blurSigma,
            )
    }
}
