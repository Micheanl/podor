package app.podor.presentation

import androidx.compose.ui.graphics.ImageBitmap
import app.podor.domain.BrushPreset
import app.podor.domain.BrushSettings
import app.podor.domain.StudioDefaults
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.engine.engineBrushJson
import app.podor.engine.rgbaBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object BrushPreviewCache {
    private val mutex = Mutex()
    private val images = LinkedHashMap<String, ImageBitmap>()

    suspend fun get(preset: BrushPreset): ImageBitmap =
        withContext(Dispatchers.Default) {
            val payload =
                engineBrushJson(BrushSettings(preset, preset.size, preset.opacity, 0xFFFFFFFF))
                    .toString()
            mutex.withLock {
                images.remove(payload)?.let {
                    images[payload] = it
                    return@withLock it
                }
                currentCoroutineContext().ensureActive()
                val engine = createNativeEngine(1, 1)
                val image =
                    try {
                        val bytes =
                            engine.call(EngineOperation.BRUSH_PREVIEW, payload.encodeToByteArray())
                        rgbaBitmap(
                            bytes,
                            0,
                            StudioDefaults.brushPreviewWidth,
                            StudioDefaults.brushPreviewHeight,
                        )
                    } finally {
                        engine.close()
                    }
                currentCoroutineContext().ensureActive()
                images[payload] = image
                if (images.size > StudioDefaults.brushPreviewCacheSize)
                    images.remove(images.keys.first())
                image
            }
        }
}
