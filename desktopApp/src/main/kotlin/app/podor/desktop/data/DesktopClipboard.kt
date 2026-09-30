package app.podor.desktop.data

import app.podor.data.ImageClipboard
import app.podor.domain.ClipboardImage
import app.podor.domain.StudioDefaults
import app.podor.domain.validCanvasSize
import java.awt.AlphaComposite
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopClipboard(
    private val clipboard: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard }
) : ImageClipboard {
    override suspend fun write(image: ClipboardImage) =
        withContext(Dispatchers.IO) {
            require(image.png.size <= StudioDefaults.maxClipboardBytes) { "剪贴板图片过大" }
            val bitmap = decode(image.png)
            val content =
                object : Transferable {
                    override fun getTransferDataFlavors() =
                        arrayOf(localFlavor, pngFlavor, DataFlavor.imageFlavor)

                    override fun isDataFlavorSupported(flavor: DataFlavor) =
                        flavor in transferDataFlavors

                    override fun getTransferData(flavor: DataFlavor): Any =
                        when (flavor) {
                            localFlavor -> image
                            pngFlavor -> ByteArrayInputStream(image.png)
                            DataFlavor.imageFlavor -> bitmap
                            else -> throw UnsupportedFlavorException(flavor)
                        }
                }
            try {
                clipboard().setContents(content, null)
            } catch (_: IllegalStateException) {
                error("剪贴板正被占用，请稍后重试")
            }
        }

    override suspend fun read(): ClipboardImage? =
        withContext(Dispatchers.IO) {
            val content =
                try {
                    clipboard().getContents(null)
                } catch (_: IllegalStateException) {
                    error("剪贴板正被占用，请稍后重试")
                } ?: return@withContext null
            if (content.isDataFlavorSupported(localFlavor)) {
                val image = content.getTransferData(localFlavor) as ClipboardImage
                require(image.png.size <= StudioDefaults.maxClipboardBytes) { "剪贴板图片过大" }
                return@withContext image
            }
            if (content.isDataFlavorSupported(pngFlavor)) {
                val bytes =
                    (content.getTransferData(pngFlavor) as InputStream).use {
                        it.readNBytes(StudioDefaults.maxClipboardBytes + 1)
                    }
                require(bytes.size <= StudioDefaults.maxClipboardBytes) { "剪贴板图片过大" }
                return@withContext ClipboardImage(bytes)
            }
            if (!content.isDataFlavorSupported(DataFlavor.imageFlavor)) return@withContext null
            val image =
                content.getTransferData(DataFlavor.imageFlavor) as? Image ?: return@withContext null
            val width = image.getWidth(null)
            val height = image.getHeight(null)
            require(validCanvasSize(width, height)) { "剪贴板图片尺寸超出限制" }
            val bitmap =
                if (image is BufferedImage) image
                else
                    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
                        val graphics = createGraphics()
                        try {
                            graphics.composite = AlphaComposite.Src
                            check(graphics.drawImage(image, 0, 0, null)) { "剪贴板图片尚未就绪" }
                        } finally {
                            graphics.dispose()
                        }
                    }
            val output = ByteArrayOutputStream()
            check(ImageIO.write(bitmap, "png", output)) { "读取剪贴板图片失败" }
            require(output.size() <= StudioDefaults.maxClipboardBytes) { "剪贴板图片过大" }
            ClipboardImage(output.toByteArray())
        }

    private fun decode(bytes: ByteArray): BufferedImage =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "剪贴板图片数据无效" }
            val reader = readers.next()
            try {
                reader.input = input
                require(validCanvasSize(reader.getWidth(0), reader.getHeight(0))) { "剪贴板图片尺寸超出限制" }
                reader.read(0)
            } finally {
                reader.dispose()
            }
        }

    companion object {
        val localFlavor =
            DataFlavor(
                "${DataFlavor.javaJVMLocalObjectMimeType};class=app.podor.domain.ClipboardImage"
            )
        val pngFlavor = DataFlavor("image/png;class=java.io.InputStream")

        init {
            if (System.getProperty("os.name").startsWith("Windows")) {
                val mapping = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
                mapping.addUnencodedNativeForFlavor(pngFlavor, "PNG")
                mapping.addFlavorForUnencodedNative("PNG", pngFlavor)
            }
        }
    }
}
