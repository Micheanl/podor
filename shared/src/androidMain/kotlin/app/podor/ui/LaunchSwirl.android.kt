package app.podor.ui

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

internal actual fun createLaunchSwirlRenderer(): LaunchSwirlRenderer? =
    if (Build.VERSION.SDK_INT >= 33) AndroidLaunchSwirlRenderer() else null

@RequiresApi(33)
private class AndroidLaunchSwirlRenderer : LaunchSwirlRenderer {
    private val shader = RuntimeShader(launchSwirlShader)
    private val paint = Paint().apply { this.shader = this@AndroidLaunchSwirlRenderer.shader }

    init {
        val back = StudioTheme.launchSwirlBack
        val front = StudioTheme.launchSwirlFront
        shader.setFloatUniform("colorBack", back.red, back.green, back.blue, back.alpha)
        shader.setFloatUniform("colorFront", front.red, front.green, front.blue, front.alpha)
    }

    override fun DrawScope.draw(time: Float) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("pixelSize", StudioTheme.launchSwirlPixelSize.toPx())
        shader.setFloatUniform("time", time)
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
    }

    override fun close() {
        paint.shader = null
    }
}
