package app.podor.desktop

import app.podor.data.UpdateException
import app.podor.desktop.data.DesktopUpdates
import app.podor.domain.*
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

class DesktopUpdatesTest {
    private val address = "https://updates.example.test/podor.msi"
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val hash =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun release() =
        AppRelease("0.2.1", "更新", address, hash, bytes.size.toLong(), "windows-x64")

    private class Connection(
        uri: URI,
        val body: ByteArray,
        val status: Int = 200,
        val redirect: String? = null,
    ) : HttpURLConnection(uri.toURL()) {
        var closed = false

        override fun connect() {}

        override fun disconnect() {
            closed = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = status

        override fun getInputStream() = ByteArrayInputStream(body)

        override fun getContentLengthLong() = body.size.toLong()

        override fun getHeaderField(name: String?) = if (name == "Location") redirect else null
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun verifiedDownloadIsAtomicAndCorruptionCannotReplaceExistingInstaller() = runBlocking {
        val directory = Files.createTempDirectory("podor-update-")
        try {
            val source = DesktopUpdates(address, directory) { Connection(it, bytes) }
            var last = 0L
            val path = Path(source.download(release()) { last = it })
            assertEquals(bytes.size.toLong(), last)
            assertContentEquals(bytes, path.readBytes())
            val corrupted = bytes.copyOf().apply { this[0] = 9 }
            val bad = DesktopUpdates(address, directory) { Connection(it, corrupted) }
            assertEquals(
                UpdateProblem.Integrity,
                assertFailsWith<UpdateException> { bad.download(release()) {} }.problem,
            )
            assertContentEquals(bytes, path.readBytes())
            assertTrue(directory.listDirectoryEntries("*.part").isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun cancellationAndWrongLengthLeaveNoInstallerOrTemporaryFile() = runBlocking {
        val directory = Files.createTempDirectory("podor-update-")
        try {
            val connection = Connection(URI(address), bytes)
            val source = DesktopUpdates(address, directory) { connection }
            assertFailsWith<CancellationException> {
                source.download(release()) { throw CancellationException() }
            }
            assertTrue(connection.closed)
            assertTrue(directory.listDirectoryEntries().isEmpty())
            assertEquals(
                UpdateProblem.Integrity,
                assertFailsWith<UpdateException> { source.download(release().copy(size = 1)) {} }
                    .problem,
            )
            assertTrue(directory.listDirectoryEntries().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun manifestRejectsHtmlOversizedPayloadAndInsecureRedirect() = runBlocking {
        val directory = Path("unused")
        for (body in
            listOf(
                "<html>Login required</html>".encodeToByteArray(),
                ByteArray(DesktopUpdates.maxManifestBytes + 1),
            )) {
            val source = DesktopUpdates(address, directory) { Connection(it, body) }
            assertEquals(
                UpdateProblem.Manifest,
                assertFailsWith<UpdateException> { source.latest() }.problem,
            )
        }
        var requests = 0
        val source =
            DesktopUpdates(address, directory) {
                requests++
                Connection(it, byteArrayOf(), 302, "http://updates.example.test/insecure")
            }
        assertEquals(
            UpdateProblem.Manifest,
            assertFailsWith<UpdateException> { source.latest() }.problem,
        )
        assertEquals(1, requests)
    }

    @Test
    fun publicReleaseIndexLoadsVerifiedManifestAndRejectsMismatchedTags() = runBlocking {
        val manifestUrl = "https://updates.example.test/latest.json"
        fun source(tag: String, prerelease: Boolean = false) =
            DesktopUpdates(address, Path("unused")) { uri ->
                val body =
                    if (uri.toString() == address)
                        Json.encodeToString(
                            PublishedRelease(
                                tag,
                                prerelease,
                                listOf(
                                    ReleaseAsset("latest.json", manifestUrl),
                                    ReleaseAsset("podor-0.2.1.msi", address),
                                ),
                            )
                        )
                    else Json.encodeToString(release())
                Connection(uri, body.encodeToByteArray())
            }
        assertEquals(release(), source("v0.2.1").latest())
        for (source in listOf(source("v0.2.2"), source("v0.2.1", true))) {
            assertEquals(
                UpdateProblem.Manifest,
                assertFailsWith<UpdateException> { source.latest() }.problem,
            )
        }
    }
}
