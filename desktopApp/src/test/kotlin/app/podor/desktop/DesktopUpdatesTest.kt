package app.podor.desktop

import app.podor.data.UpdateException
import app.podor.desktop.data.DesktopUpdates
import app.podor.domain.*
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

class DesktopUpdatesTest {
    private val address = "https://updates.example.test/podor.msi"
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val hash =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun fallbackRejectsOversizedIndexesAndHonorsCancellation() = runBlocking {
        val latest = "https://updates.example.test/repos/owner/podor/releases/latest"
        val pending = Json.encodeToString(PublishedRelease("v0.3.0", true, emptyList()))
        val metadata =
            PublishedRelease(
                "v0.2.1",
                false,
                listOf(
                    ReleaseAsset("latest.json", "https://updates.example.test/stable.json"),
                    ReleaseAsset("podor-0.2.1.msi", address),
                ),
            )
        val oversized = buildJsonObject {
            put("tag_name", metadata.tag)
            put("prerelease", false)
            put("assets", Json.parseToJsonElement(Json.encodeToString(metadata.assets)))
            put("body", "x".repeat(600 * 1024))
        }
        var requests = 0
        val source =
            DesktopUpdates(latest, Path("unused")) { uri ->
                requests++
                val body = if (uri.toString() == latest) pending else "[$oversized]"
                Connection(uri, body.encodeToByteArray())
            }
        assertEquals(
            UpdateProblem.Manifest,
            assertFailsWith<UpdateException> { source.latest() }.problem,
        )
        assertEquals(2, requests)
        requests = 0
        val cancelled =
            DesktopUpdates(latest, Path("unused")) { uri ->
                requests++
                if (uri.toString() != latest) throw CancellationException()
                Connection(uri, pending.encodeToByteArray())
            }
        assertFailsWith<CancellationException> { cancelled.latest() }
        assertEquals(2, requests)
    }

    @Test
    fun realGiteeCatalogResolvesStableReleaseWhenLatestIsTemporarilyIncomplete() = runBlocking {
        assumeTrue(System.getenv("PODOR_UPDATE_LIVE_TEST") == "1")
        val latest = AppBuildInfo.manifestUrl
        val pending = Json.encodeToString(PublishedRelease("v999.0.0", true, emptyList()))
        val source =
            DesktopUpdates(latest, Path("unused")) { uri ->
                if (uri.toString() == latest) Connection(uri, pending.encodeToByteArray())
                else uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            }
        val stable = source.latest()
        stable.validate()
        assertTrue(AppVersion.parse(stable.version) >= AppVersion(0, 2, 16))
        assertEquals("gitee.com", URI(stable.url).host)
    }

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

    @Test
    fun incompleteLatestReleaseFallsBackToTheNewestCompleteStableVersion() = runBlocking {
        val latest = "https://updates.example.test/repos/owner/podor/releases/latest"
        val stable = release().copy(version = "0.2.9")
        fun published(version: String, prerelease: Boolean = false, complete: Boolean = true) =
            PublishedRelease(
                "v$version",
                prerelease,
                if (complete)
                    listOf(
                        ReleaseAsset("latest.json", "https://updates.example.test/$version.json"),
                        ReleaseAsset("podor-$version.msi", address),
                    )
                else emptyList(),
            )
        val entries =
            listOf(
                published("0.2.4"),
                published("0.3.0", true),
                published("0.2.10", complete = false),
                published(stable.version),
            )
        for (pending in listOf(published("0.3.0", true), published("0.2.10", complete = false))) {
            val requests = mutableListOf<URI>()
            val connections = mutableListOf<Connection>()
            val source =
                DesktopUpdates(latest, Path("unused")) { uri ->
                    requests.add(uri)
                    val body =
                        when {
                            uri.toString() == latest -> Json.encodeToString(pending)
                            uri.path.endsWith("/releases") -> Json.encodeToString(entries)
                            uri.path == "/0.2.9.json" -> Json.encodeToString(stable)
                            else -> error("Unexpected update request $uri")
                        }
                    Connection(uri, body.encodeToByteArray()).also(connections::add)
                }
            assertEquals(stable, source.latest())
            assertEquals(3, requests.size)
            assertEquals("page=1&per_page=20&direction=desc", requests[1].query)
            assertTrue(connections.all { it.closed })
        }
    }

    @Test
    fun fallbackPaginatesPastPrereleasesAndNeverTreatsMissingStableVersionsAsCurrent() =
        runBlocking {
            val latest = "https://updates.example.test/repos/owner/podor/releases/latest"
            val pending = PublishedRelease("v0.3.0", true, emptyList())
            val stable =
                PublishedRelease(
                    "v0.2.1",
                    false,
                    listOf(
                        ReleaseAsset("latest.json", "https://updates.example.test/stable.json"),
                        ReleaseAsset("podor-0.2.1.msi", address),
                    ),
                )
            for (available in listOf(true, false)) {
                val pages = mutableListOf<Int>()
                val source =
                    DesktopUpdates(latest, Path("unused")) { uri ->
                        val body =
                            when {
                                uri.toString() == latest -> Json.encodeToString(pending)
                                uri.path.endsWith("/releases") -> {
                                    val page =
                                        uri.query
                                            .substringAfter("page=")
                                            .substringBefore('&')
                                            .toInt()
                                    pages.add(page)
                                    Json.encodeToString(
                                        if (available && page == 2) listOf(stable)
                                        else List(20) { pending }
                                    )
                                }
                                uri.path == "/stable.json" -> Json.encodeToString(release())
                                else -> error("Unexpected update request $uri")
                            }
                        Connection(uri, body.encodeToByteArray())
                    }
                if (available) {
                    assertEquals(release(), source.latest())
                    assertEquals(listOf(1, 2), pages)
                } else {
                    assertEquals(
                        UpdateProblem.Manifest,
                        assertFailsWith<UpdateException> { source.latest() }.problem,
                    )
                    assertEquals(listOf(1, 2, 3, 4, 5), pages)
                }
            }
        }

    @Test
    fun fallbackRejectsBrokenManifestsAndDoesNotHideConnectionFailures() = runBlocking {
        val latest = "https://updates.example.test/repos/owner/podor/releases/latest"
        val pending = PublishedRelease("v0.3.0", true, emptyList())
        val stable =
            PublishedRelease(
                "v0.2.1",
                false,
                listOf(
                    ReleaseAsset("latest.json", "https://updates.example.test/stable.json"),
                    ReleaseAsset("podor-0.2.1.msi", address),
                ),
            )
        var requests = 0
        val offline =
            DesktopUpdates(latest, Path("unused")) {
                requests++
                throw SocketTimeoutException()
            }
        assertEquals(
            UpdateProblem.Connection,
            assertFailsWith<UpdateException> { offline.latest() }.problem,
        )
        assertEquals(1, requests)
        for (malformed in
            listOf(
                release().copy(version = "0.2.2"),
                release().copy(url = "https://unlisted.example.test/podor.msi"),
                release().copy(sha256 = "invalid"),
            )) {
            val source =
                DesktopUpdates(latest, Path("unused")) { uri ->
                    val body =
                        when {
                            uri.toString() == latest -> Json.encodeToString(pending)
                            uri.path.endsWith("/releases") -> Json.encodeToString(listOf(stable))
                            else -> Json.encodeToString(malformed)
                        }
                    Connection(uri, body.encodeToByteArray())
                }
            assertEquals(
                UpdateProblem.Manifest,
                assertFailsWith<UpdateException> { source.latest() }.problem,
            )
        }
    }
}
