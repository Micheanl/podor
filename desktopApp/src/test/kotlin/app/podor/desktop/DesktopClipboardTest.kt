package app.podor.desktop

import app.podor.desktop.data.DesktopClipboard
import app.podor.domain.ClipboardImage
import app.podor.domain.ClipboardOrigin
import java.awt.datatransfer.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class DesktopClipboardTest {
    private fun image() =
        BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, 0x7F923456)
            setRGB(1, 0, 0xFF123456.toInt())
            setRGB(2, 1, 0x00ABCDEF)
        }

    private fun png(image: BufferedImage) =
        ByteArrayOutputStream()
            .also {
                check(ImageIO.write(image, "png", it))
            }
            .toByteArray()

    private fun content(flavor: DataFlavor, value: Any) =
        object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(flavor)

            override fun isDataFlavorSupported(candidate: DataFlavor) = candidate == flavor

            override fun getTransferData(candidate: DataFlavor): Any {
                if (!isDataFlavorSupported(candidate)) throw UnsupportedFlavorException(candidate)
                return value
            }
        }

    @Test
    fun localPngAndImageFormatsPreserveTransparencyWithoutUsingTheUserClipboard() = runBlocking {
        val clipboard = Clipboard("podor-test")
        val adapter = DesktopClipboard {
            assertFalse(SwingUtilities.isEventDispatchThread())
            clipboard
        }
        val image = image()
        val source = ClipboardImage(png(image), ClipboardOrigin(64, 48, 15, 20))
        adapter.write(source)
        val restored = assertNotNull(adapter.read())
        assertContentEquals(source.png, restored.png)
        assertEquals(source.origin, restored.origin)
        val native = clipboard.getContents(null)
        assertTrue(native.isDataFlavorSupported(DataFlavor.imageFlavor))
        (native.getTransferData(DesktopClipboard.pngFlavor) as java.io.InputStream).use {
            assertEquals(image.getRGB(0, 0), ImageIO.read(it).getRGB(0, 0))
        }
        clipboard.setContents(
            content(DesktopClipboard.pngFlavor, ByteArrayInputStream(source.png)),
            null,
        )
        assertContentEquals(source.png, assertNotNull(adapter.read()).png)
        clipboard.setContents(content(DataFlavor.imageFlavor, image), null)
        val external = assertNotNull(adapter.read())
        assertNull(external.origin)
        val decoded = ImageIO.read(ByteArrayInputStream(external.png))
        assertEquals(image.getRGB(0, 0), decoded.getRGB(0, 0))
        assertEquals(0, decoded.getRGB(2, 1) ushr 24)
    }

    @Test
    fun malformedAndBusyClipboardCannotReplaceTheExistingContents() =
        runBlocking<Unit> {
            val clipboard = Clipboard("podor-test")
            clipboard.setContents(StringSelection("existing text"), null)
            val adapter = DesktopClipboard { clipboard }
            assertNull(adapter.read())
            assertFailsWith<IllegalArgumentException> {
                adapter.write(ClipboardImage(byteArrayOf(1)))
            }
            assertEquals("existing text", clipboard.getData(DataFlavor.stringFlavor))
            val busy = DesktopClipboard { throw IllegalStateException("busy") }
            assertFailsWith<IllegalStateException> { busy.write(ClipboardImage(png(image()))) }
            assertFailsWith<IllegalStateException> { busy.read() }
        }
}
