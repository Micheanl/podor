package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt

enum class SymmetryMode(val label: String, val vertical: Boolean, val horizontal: Boolean) {
    Off("关闭对称", false, false),
    Vertical("左右对称", true, false),
    Horizontal("上下对称", false, true),
    Quadrant("双轴对称", true, true),
}

data class SymmetrySettings(
    val mode: SymmetryMode = StudioDefaults.symmetryMode,
    val x: Float = StudioDefaults.symmetryAxis,
    val y: Float = StudioDefaults.symmetryAxis,
    val guides: Boolean = StudioDefaults.symmetryGuides,
) {
    fun axis(document: DocumentInfo) =
        Offset(
            (x * document.width * 2).roundToInt() * 0.5f,
            (y * document.height * 2).roundToInt() * 0.5f,
        )

    inline fun forEachPoint(point: Offset, document: DocumentInfo, draw: (Offset) -> Unit) {
        draw(point)
        if (mode == SymmetryMode.Off) return
        val axis = axis(document)
        val reflectedX = axis.x * 2 - point.x
        val reflectedY = axis.y * 2 - point.y
        if (mode.vertical && reflectedX != point.x) draw(Offset(reflectedX, point.y))
        if (mode.horizontal && reflectedY != point.y) draw(Offset(point.x, reflectedY))
        if (mode.vertical && mode.horizontal && reflectedX != point.x && reflectedY != point.y)
            draw(Offset(reflectedX, reflectedY))
    }
}
