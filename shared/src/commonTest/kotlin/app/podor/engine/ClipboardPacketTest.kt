package app.podor.engine

import app.podor.domain.ClipboardImage
import app.podor.domain.ClipboardOrigin
import kotlin.test.*

class ClipboardPacketTest {
    @Test
    fun nativePacketPreservesPixelBytesAndLargeCanvasOffsets() {
        val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val image = ClipboardImage(bytes, ClipboardOrigin(8192, 2048, 4107, 1025))
        val restored = clipboardImage(image.toNativePacket())
        assertContentEquals(bytes, restored.png)
        assertEquals(image.origin, restored.origin)
        assertContentEquals(
            ByteArray(16),
            ClipboardImage(bytes).toNativePacket().take(16).toByteArray(),
        )
        assertFailsWith<IllegalArgumentException> { clipboardImage(ByteArray(16)) }
    }
}
