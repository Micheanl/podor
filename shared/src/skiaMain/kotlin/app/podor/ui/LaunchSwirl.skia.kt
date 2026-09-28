package app.podor.ui

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

internal actual fun createLaunchSwirlRenderer(): LaunchSwirlRenderer? = SkiaLaunchSwirlRenderer()

private class SkiaLaunchSwirlRenderer : LaunchSwirlRenderer {
    private val effect = RuntimeEffect.makeForShader(launchSwirlShader)
    private val builder = RuntimeShaderBuilder(effect)
    private val paint = Paint()

    init {
        val back = StudioTheme.launchSwirlBack
        val front = StudioTheme.launchSwirlFront
        builder.uniform("colorBack", back.red, back.green, back.blue, back.alpha)
        builder.uniform("colorFront", front.red, front.green, front.blue, front.alpha)
    }

    override fun DrawScope.draw(time: Float) {
        builder.uniform("resolution", size.width, size.height)
        builder.uniform("pixelSize", StudioTheme.launchSwirlPixelSize.toPx())
        builder.uniform("time", time)
        builder.makeShader().use { shader ->
            paint.shader = shader
            drawIntoCanvas { it.skiaCanvas.drawRect(Rect.makeWH(size.width, size.height), paint) }
            paint.shader = null
        }
    }

    override fun close() {
        paint.close()
        builder.close()
        effect.close()
    }
}
