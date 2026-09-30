package app.podor.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import app.podor.resources.Res
import app.podor.ui.LocalStudioBaseDensity
import app.podor.ui.StudioTheme
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Modifier.platformCanvasPointer(): Modifier {
    val density = LocalStudioBaseDensity.current ?: LocalDensity.current
    val sizePx = with(density) { StudioTheme.canvasPointerSize.roundToPx() }.coerceAtLeast(1)
    val icon by
        produceState<PointerIcon?>(null, sizePx) {
            value =
                withContext(Dispatchers.Default) {
                    createCanvasPointerIcon(Res.readBytes("files/stylus-cursor.png"), sizePx)
                }
        }
    return pointerHoverIcon(icon ?: PointerIcon.Default)
}

internal fun canvasPointerHotspot(size: IntSize): Offset {
    val side = min(size.width, size.height)
    return Offset(
        (size.width - side) / 2 + side * 0.06558310206515894f,
        (size.height - side) / 2 + side * 0.8681729718656697f,
    )
}

internal expect fun createCanvasPointerIcon(image: ByteArray, sizePx: Int): PointerIcon?
