package app.podor.engine

import app.podor.domain.ClipboardImage
import app.podor.domain.ClipboardOrigin
import app.podor.domain.StudioDefaults

fun ClipboardImage.toNativePacket(): ByteArray {
    require(png.size <= StudioDefaults.maxClipboardBytes) { "剪贴板图片过大" }
    val output = ByteArray(16 + png.size)
    origin?.let {
        listOf(it.width, it.height, it.left, it.top).forEachIndexed { index, value ->
            repeat(4) { byte -> output[index * 4 + byte] = (value ushr (byte * 8)).toByte() }
        }
    }
    png.copyInto(output, 16)
    return output
}

fun clipboardImage(packet: ByteArray): ClipboardImage {
    require(packet.size in 17..(StudioDefaults.maxClipboardBytes + 16)) { "剪贴板图片数据无效" }
    return ClipboardImage(
        packet.copyOfRange(16, packet.size),
        ClipboardOrigin(packet.intAt(0), packet.intAt(4), packet.intAt(8), packet.intAt(12)),
    )
}

private fun ByteArray.intAt(offset: Int): Int =
    (this[offset].toInt() and 255) or
        ((this[offset + 1].toInt() and 255) shl 8) or
        ((this[offset + 2].toInt() and 255) shl 16) or
        ((this[offset + 3].toInt() and 255) shl 24)
