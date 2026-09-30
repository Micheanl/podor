package app.podor.desktop

import app.podor.desktop.data.DesktopClipboard
import app.podor.domain.ClipboardImage
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue

class ClipboardSystemTest {
    @Test
    fun windowsNativePngRoundTripUsesAnIsolatedClipboard() {
        assumeTrue(System.getenv("PODOR_CLIPBOARD_SYSTEM_TEST") == "1")
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val classpath = System.getProperty("podor.test.classpath")
        assertTrue(classpath.isNotBlank())
        val report = Path.of("build/reports/clipboard-system.txt")
        Files.createDirectories(report.parent)
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    classpath,
                    ClipboardSystemProbe::class.java.name,
                )
                .redirectErrorStream(true)
                .redirectOutput(report.toFile())
                .start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Native clipboard probe timed out")
            assertEquals(0, process.exitValue(), Files.readString(report))
            assertTrue(Files.readString(report).contains("Windows PNG clipboard round trip passed"))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

object ClipboardSystemProbe {
    private interface Windows : StdCallLibrary {
        fun CreateWindowStationW(
            name: WString?,
            flags: Int,
            access: Int,
            attributes: Pointer?,
        ): Pointer?

        fun GetProcessWindowStation(): Pointer

        fun SetProcessWindowStation(station: Pointer): Boolean

        fun CreateDesktopW(
            name: WString,
            device: Pointer?,
            mode: Pointer?,
            flags: Int,
            access: Int,
            attributes: Pointer?,
        ): Pointer?

        fun SetThreadDesktop(desktop: Pointer): Boolean

        fun CreateWindowExW(
            ex: Int,
            type: WString,
            title: WString,
            style: Int,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            parent: Pointer?,
            menu: Pointer?,
            instance: Pointer?,
            parameters: Pointer?,
        ): Pointer?

        fun RegisterClipboardFormatW(name: WString): Int

        fun OpenClipboard(window: Pointer?): Boolean

        fun EmptyClipboard(): Boolean

        fun CloseClipboard(): Boolean

        fun GetClipboardData(format: Int): Pointer?

        fun SetClipboardData(format: Int, memory: Pointer): Pointer?
    }

    private interface Memory : StdCallLibrary {
        fun GlobalAlloc(flags: Int, bytes: Long): Pointer?

        fun GlobalLock(memory: Pointer): Pointer?

        fun GlobalUnlock(memory: Pointer): Boolean

        fun GlobalSize(memory: Pointer): Long
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val windows = Native.load("user32", Windows::class.java)
            val memory = Native.load("kernel32", Memory::class.java)
            val originalStation = windows.GetProcessWindowStation()
            val station =
                checkNotNull(
                    windows.CreateWindowStationW(
                        WString("podor-test-${java.util.UUID.randomUUID()}"),
                        1,
                        0x10000000,
                        null,
                    )
                ) {
                    "Create isolated station: ${Native.getLastError()}"
                }
            check(station != originalStation)
            check(windows.SetProcessWindowStation(station))
            val desktop =
                checkNotNull(
                    windows.CreateDesktopW(WString("Default"), null, null, 0, 0x10000000, null)
                )
            check(windows.SetThreadDesktop(desktop))
            val window =
                checkNotNull(
                    windows.CreateWindowExW(
                        0,
                        WString("STATIC"),
                        WString("clipboard test"),
                        0,
                        0,
                        0,
                        0,
                        0,
                        Pointer.createConstant(-3L),
                        null,
                        null,
                        null,
                    )
                ) {
                    "Create clipboard owner: ${Native.getLastError()}"
                }
            check(windows.GetProcessWindowStation() == station)
            val format = windows.RegisterClipboardFormatW(WString("PNG"))
            check(format != 0)
            val source =
                BufferedImage(7, 5, BufferedImage.TYPE_INT_ARGB).apply {
                    setRGB(2, 3, 0x7F983452)
                    setRGB(4, 1, 0xFF24689A.toInt())
                }
            fun png() =
                ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()
            runBlocking {
                val adapter = DesktopClipboard()
                adapter.write(ClipboardImage(png()))
                check(windows.OpenClipboard(window))
                val nativePng =
                    try {
                        val handle = checkNotNull(windows.GetClipboardData(format))
                        val pointer = checkNotNull(memory.GlobalLock(handle))
                        try {
                            pointer.getByteArray(0, memory.GlobalSize(handle).toInt())
                        } finally {
                            memory.GlobalUnlock(handle)
                        }
                    } finally {
                        windows.CloseClipboard()
                    }
                val decoded = ImageIO.read(ByteArrayInputStream(nativePng))
                check(decoded.getRGB(2, 3) == source.getRGB(2, 3))
                check(decoded.getRGB(0, 0) ushr 24 == 0)
                source.setRGB(2, 3, 0xAB126789.toInt())
                val external = png()
                check(windows.OpenClipboard(window))
                try {
                    check(windows.EmptyClipboard())
                    val handle = checkNotNull(memory.GlobalAlloc(0x42, external.size.toLong()))
                    val pointer = checkNotNull(memory.GlobalLock(handle))
                    pointer.write(0, external, 0, external.size)
                    memory.GlobalUnlock(handle)
                    checkNotNull(windows.SetClipboardData(format, handle))
                } finally {
                    windows.CloseClipboard()
                }
                withTimeout(5_000) {
                    while (true) {
                        val pasted = adapter.read()
                        if (pasted != null) {
                            val image = ImageIO.read(ByteArrayInputStream(pasted.png))
                            if (image.getRGB(2, 3) == source.getRGB(2, 3)) {
                                check(image.getRGB(0, 0) ushr 24 == 0)
                                check(pasted.origin == null)
                                break
                            }
                        }
                        delay(20)
                    }
                }
            }
            println("Windows PNG clipboard round trip passed; private window station, 7 x 5 RGBA")
            exitProcess(0)
        } catch (failure: Throwable) {
            failure.printStackTrace()
            exitProcess(1)
        }
    }
}
