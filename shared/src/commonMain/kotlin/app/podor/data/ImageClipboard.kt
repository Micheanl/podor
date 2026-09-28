package app.podor.data

import app.podor.domain.ClipboardImage

interface ImageClipboard {
    suspend fun read(): ClipboardImage?

    suspend fun write(image: ClipboardImage)
}
