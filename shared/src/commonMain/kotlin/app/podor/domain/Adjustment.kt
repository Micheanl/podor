package app.podor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AdjustmentKind(val label: String) {
    @SerialName("tone") Tone("明暗与色彩"),
    @SerialName("blur") Blur("高斯模糊"),
}

@Serializable
data class AdjustmentSettings(
    val kind: AdjustmentKind,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val sigma: Float,
) {
    fun valid() =
        listOf(brightness, contrast, saturation).all { it.isFinite() && it in -1f..1f } &&
            sigma.isFinite() &&
            sigma in StudioDefaults.minBlurSigma..StudioDefaults.maxBlurSigma

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
