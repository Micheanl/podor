package app.podor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LayerBlendMode(val label: String) {
    @SerialName("normal") Normal("正常"),
    @SerialName("multiply") Multiply("正片叠底"),
    @SerialName("screen") Screen("滤色"),
    @SerialName("overlay") Overlay("叠加"),
    @SerialName("soft_light") SoftLight("柔光"),
    @SerialName("darken") Darken("变暗"),
    @SerialName("lighten") Lighten("变亮"),
    @SerialName("difference") Difference("差值"),
}
