package app.podor.desktop

import app.podor.data.UpdateException
import app.podor.desktop.data.DesktopInstaller
import app.podor.domain.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class DesktopInstallerTest {
    private class Helper(private val alive: Boolean) : Process() {
        var destroyed = false

        override fun getOutputStream() = ByteArrayOutputStream()

        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())

        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

        override fun waitFor() = 0

        override fun exitValue() = 0

        override fun isAlive() = alive && !destroyed

        override fun destroy() {
            destroyed = true
        }
    }

    @Test
    fun installerIsReverifiedAndHelperMustBeReadyBeforeTheAppExits() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createDirectories(Path.of("build", "installer-tests").toAbsolutePath())
        val directory = Files.createTempDirectory(root, "update with spaces ")
        try {
            val bytes = byteArrayOf(1, 2, 3)
            val hash =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
            val release =
                AppRelease("0.2.5", "", "https://example.test/podor.msi", hash, 3, "windows-x64")
            val file = Files.write(directory.resolve("podor-0.2.5.msi"), bytes)
            var starts = 0
            val service =
                DesktopInstaller(directory) { command ->
                    starts++
                    assertEquals(file.toString(), command[command.indexOf("-Installer") + 1])
                    assertEquals("Hidden", command[command.indexOf("-WindowStyle") + 1])
                    assertEquals(
                        ProcessHandle.current().pid().toString(),
                        command[command.indexOf("-ParentId") + 1],
                    )
                    assertEquals(hash, command[command.indexOf("-Sha256") + 1])
                    Files.writeString(Path.of(command[command.indexOf("-ReadyFile") + 1]), "ready")
                    Helper(true)
                }
            service.install(release, file.toString())
            assertEquals(1, starts)
            Files.write(file, byteArrayOf(1, 2, 4))
            assertEquals(
                UpdateProblem.Integrity,
                assertFailsWith<UpdateException> { service.install(release, file.toString()) }
                    .problem,
            )
            assertEquals(
                UpdateProblem.Integrity,
                assertFailsWith<UpdateException> {
                        service.install(release, directory.resolve("outside.msi").toString())
                    }
                    .problem,
            )
            assertEquals(1, starts)
            Files.write(file, bytes)
            val dead = Helper(false)
            assertEquals(
                UpdateProblem.Installation,
                assertFailsWith<UpdateException> {
                        DesktopInstaller(directory) { dead }.install(release, file.toString())
                    }
                    .problem,
            )
            assertTrue(dead.destroyed)
            Files.list(directory).use {
                assertFalse(it.anyMatch { path -> path.toString().endsWith(".ready") })
            }
        } finally {
            check(directory.normalize().startsWith(root))
            Files.walk(directory).use {
                it.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}
