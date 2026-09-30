package app.podor.desktop.data

import app.podor.data.UpdateException
import app.podor.data.UpdateSource
import app.podor.domain.*
import java.awt.Desktop
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

class DesktopUpdates(
    private val manifestUrl: String = AppBuildInfo.manifestUrl,
    private val directory: Path = DesktopStorage().root.resolve("updates"),
    private val openConnection: (URI) -> HttpURLConnection = {
        it.toURL().openConnection() as HttpURLConnection
    },
) : UpdateSource {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun install(release: AppRelease, installer: String) =
        DesktopInstaller(directory).install(release, installer)

    override suspend fun latest(): AppRelease =
        withContext(Dispatchers.IO) {
            if (manifestUrl.isBlank()) throw UpdateException(UpdateProblem.Unconfigured)
            try {
                val uri = URI(manifestUrl)
                val latest = json.decodeFromString<PublishedRelease>(fetch(uri))
                val published = if (complete(latest)) latest else stableRelease(uri)
                val manifest = published.assets.single { it.name == "latest.json" }
                json.decodeFromString<AppRelease>(fetch(URI(manifest.url))).also {
                    it.validate()
                    require(published.tag == "v${it.version}")
                    require(
                        published.assets.any { asset ->
                            asset.url == it.url &&
                                asset.name == "${AppIdentity.name}-${it.version}.msi"
                        }
                    )
                    validateUrl(URI(it.url))
                }
            } catch (_: IllegalArgumentException) {
                throw UpdateException(UpdateProblem.Manifest)
            } catch (_: NoSuchElementException) {
                throw UpdateException(UpdateProblem.Manifest)
            }
        }

    private fun complete(release: PublishedRelease): Boolean {
        if (release.prerelease || !release.tag.startsWith('v')) return false
        val version = release.tag.removePrefix("v")
        try {
            AppVersion.parse(version)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return release.assets.count { it.name == "latest.json" } == 1 &&
            release.assets.count { it.name == "${AppIdentity.name}-$version.msi" } == 1
    }

    private suspend fun stableRelease(latestUri: URI): PublishedRelease {
        require(latestUri.path.endsWith("/releases/latest"))
        for (page in 1..maxReleasePages) {
            val index =
                URI(
                    latestUri.scheme,
                    null,
                    latestUri.host,
                    latestUri.port,
                    latestUri.path.removeSuffix("/latest"),
                    "page=$page&per_page=$releasePageSize&direction=desc",
                    null,
                )
            val releases =
                json.decodeFromString<List<PublishedRelease>>(fetch(index, maxIndexBytes))
            require(releases.size <= releasePageSize)
            releases
                .filter(::complete)
                .maxByOrNull { AppVersion.parse(it.tag.removePrefix("v")) }
                ?.let {
                    return it
                }
            if (releases.size < releasePageSize) break
        }
        throw UpdateException(UpdateProblem.Manifest)
    }

    private suspend fun fetch(uri: URI, limit: Int = maxManifestBytes): String {
        val connection = connect(uri)
        try {
            val bytes = connection.inputStream.use { input -> input.readNBytes(limit + 1) }
            currentCoroutineContext().ensureActive()
            if (bytes.size > limit) throw UpdateException(UpdateProblem.Manifest)
            return bytes.decodeToString()
        } catch (_: IOException) {
            throw UpdateException(UpdateProblem.Connection)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(release: AppRelease, progress: suspend (Long) -> Unit): String =
        withContext(Dispatchers.IO) {
            release.validate()
            val connection = connect(URI(release.url))
            var temporary: Path? = null
            try {
                val declared = connection.contentLengthLong
                if (declared >= 0 && declared != release.size)
                    throw UpdateException(UpdateProblem.Integrity)
                try {
                    Files.createDirectories(directory)
                    temporary = Files.createTempFile(directory, "download-", ".part")
                } catch (_: IOException) {
                    throw UpdateException(UpdateProblem.Storage)
                }
                val hash = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastProgress = 0L
                connection.inputStream.use { input ->
                    Files.newOutputStream(temporary).use { output ->
                        val buffer = ByteArray(bufferBytes)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > release.size)
                                throw UpdateException(UpdateProblem.Integrity)
                            try {
                                output.write(buffer, 0, count)
                            } catch (_: IOException) {
                                throw UpdateException(UpdateProblem.Storage)
                            }
                            hash.update(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - lastProgress >= progressIntervalNanos) {
                                progress(received)
                                lastProgress = now
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val digest = hash.digest().joinToString("") { "%02x".format(it) }
                if (received != release.size || !digest.equals(release.sha256, true)) {
                    throw UpdateException(UpdateProblem.Integrity)
                }
                val installer = directory.resolve("${AppIdentity.name}-${release.version}.msi")
                try {
                    Files.move(temporary, installer, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: IOException) {
                    throw UpdateException(UpdateProblem.Storage)
                }
                progress(received)
                installer.toAbsolutePath().toString()
            } catch (_: IOException) {
                throw UpdateException(UpdateProblem.Connection)
            } finally {
                connection.disconnect()
                temporary?.let { Files.deleteIfExists(it) }
            }
        }

    override suspend fun reveal(installer: String) =
        withContext(Dispatchers.IO) {
            val path = Path.of(installer).toAbsolutePath().normalize()
            require(
                path.parent == directory.toAbsolutePath().normalize() && Files.isRegularFile(path)
            )
            Desktop.getDesktop().open(path.parent.toFile())
        }

    private fun connect(initial: URI): HttpURLConnection {
        var uri = initial
        repeat(maxRedirects + 1) { attempt ->
            validateUrl(uri)
            val connection =
                try {
                    openConnection(uri)
                } catch (_: IOException) {
                    throw UpdateException(UpdateProblem.Connection)
                }
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty(
                "User-Agent",
                "${AppIdentity.name}/${AppBuildInfo.version}",
            )
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                when (connection.responseCode) {
                    200 -> return connection
                    301,
                    302,
                    303,
                    307,
                    308 -> {
                        val location =
                            connection.getHeaderField("Location")
                                ?: throw UpdateException(UpdateProblem.Connection)
                        if (attempt == maxRedirects) throw UpdateException(UpdateProblem.Connection)
                        uri = uri.resolve(location)
                        connection.disconnect()
                    }
                    else -> throw UpdateException(UpdateProblem.Connection)
                }
            } catch (failure: Exception) {
                connection.disconnect()
                if (failure is UpdateException) throw failure
                throw UpdateException(UpdateProblem.Connection)
            }
        }
        throw UpdateException(UpdateProblem.Connection)
    }

    private fun validateUrl(uri: URI) {
        require(
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null
        )
    }

    companion object {
        const val maxManifestBytes = 64 * 1024
        private const val maxIndexBytes = 512 * 1024
        private const val releasePageSize = 20
        private const val maxReleasePages = 5
        private const val bufferBytes = 64 * 1024
        private const val connectTimeoutMillis = 10_000
        private const val readTimeoutMillis = 5_000
        private const val maxRedirects = 4
        private const val progressIntervalNanos = 100_000_000L
    }
}
