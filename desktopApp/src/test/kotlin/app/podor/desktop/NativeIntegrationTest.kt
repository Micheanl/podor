package app.podor.desktop

import app.podor.desktop.engine.NativeLoader

import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import kotlin.test.*

class NativeIntegrationTest {
    @Test
    fun nativeLibraryPaintsAndRestoresProjectAcrossTheBridge() {
        NativeLoader.load()
        val engine = createNativeEngine(256, 256)
        try {
            engine.call(
                EngineOperation.COMMAND,
                """{"type":"begin","brush":{"size":32,"opacity":1,"hardness":1,"color":[20,80,200],"eraser":false}}"""
                    .encodeToByteArray(),
            )
            val samples =
                java.nio.ByteBuffer.allocate(12)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putFloat(64f)
                    .putFloat(64f)
                    .putFloat(1f)
                    .array()
            engine.call(EngineOperation.SAMPLES, samples)
            engine.call(EngineOperation.COMMAND, """{"type":"end"}""".encodeToByteArray())
            val frame = engine.call(EngineOperation.FRAME)
            assertEquals(16 + 8 + 128 * 128 * 4, frame.size)
            val pixel = 24 + (64 * 128 + 64) * 4
            assertEquals(
                listOf(20, 80, 200, 255),
                frame.slice(pixel..pixel + 3).map { it.toInt() and 255 },
            )
            val project = engine.call(EngineOperation.SAVE)
            engine.call(EngineOperation.COMMAND, """{"type":"clear"}""".encodeToByteArray())
            engine.call(EngineOperation.LOAD, project)
            val png = engine.call(EngineOperation.EXPORT_IMAGE)
            assertContentEquals(
                byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
                png.copyOfRange(0, 8),
            )
            assertFailsWith<IllegalStateException> {
                engine.call(
                    EngineOperation.COMMAND,
                    """{"type":"select_layer","id":999}""".encodeToByteArray(),
                )
            }
        } finally {
            engine.close()
        }
    }
}
