package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

internal fun colorOnTrack(colors: List<Color>, fraction: Float): Color {
    val position = fraction.coerceIn(0f, 1f) * (colors.size - 1)
    val index = position.toInt()
    return lerp(colors[index], colors[(index + 1).coerceAtMost(colors.lastIndex)], position - index)
}

@Composable
internal fun ColorSliderThumb(color: Color, modifier: Modifier) {
    Box(
        modifier
            .shadow(2.dp, CircleShape)
            .background(Color.Black, CircleShape)
            .padding(1.dp)
            .background(Color.White, CircleShape)
            .padding(2.dp)
            .background(color, CircleShape)
    )
}
