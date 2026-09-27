package app.podor.domain

enum class CanvasAnchor(val label: String) {
    TopLeft("左上"),
    Top("上方"),
    TopRight("右上"),
    Left("左侧"),
    Center("居中"),
    Right("右侧"),
    BottomLeft("左下"),
    Bottom("下方"),
    BottomRight("右下");

    fun offsetX(before: Int, after: Int) = (after - before) * (ordinal % 3) / 2

    fun offsetY(before: Int, after: Int) = (after - before) * (ordinal / 3) / 2
}
