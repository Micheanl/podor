package app.podor.domain

import kotlinx.serialization.Serializable

@Serializable
enum class CanvasBackground(val label: String) {
    White("白色"),
    Gray("灰色"),
    Transparent("透明棋盘格"),
}
