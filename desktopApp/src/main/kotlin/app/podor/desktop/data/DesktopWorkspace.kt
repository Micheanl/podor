package app.podor.desktop.data

import app.podor.domain.ProjectReference
import app.podor.domain.RecentProject
import app.podor.domain.validCanvasSize
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json

class DesktopWorkspace(private val root: Path) {
    private val index = root.resolve("workspace.json")
    private val thumbnails = root.resolve("thumbnails")
    private val json = Json { ignoreUnknownKeys = true }

    fun recent(): List<RecentProject> {
        if (!Files.exists(index)) return emptyList()
        require(Files.size(index) <= maxIndexBytes) { "作品列表无法读取" }
        val entries = runCatching {
            json.decodeFromString<List<RecentProject>>(Files.readString(index))
        }.getOrElse { throw IllegalArgumentException("作品列表无法读取") }
        require(
            entries.size <= maxEntries &&
                entries.all {
                    it.reference.id.isNotBlank() && validCanvasSize(it.width, it.height)
                }
        ) {
            "作品列表无法读取"
        }
        return entries
    }

    fun remember(reference: ProjectReference, width: Int, height: Int, thumbnail: ByteArray) {
        val old = recent()
        val entries =
            (listOf(RecentProject(reference, width, height, System.currentTimeMillis())) +
                    old.filterNot { it.reference.id == reference.id })
                .take(maxEntries)
        Files.createDirectories(thumbnails)
        require(thumbnail.size <= maxThumbnailBytes) { "作品缩略图过大" }
        DesktopStorage.atomicWrite(thumbnailPath(reference), thumbnail)
        DesktopStorage.atomicWrite(index, json.encodeToString(entries).encodeToByteArray())
        old.filter { entry -> entries.none { it.reference.id == entry.reference.id } }
            .forEach { Files.deleteIfExists(thumbnailPath(it.reference)) }
    }

    fun forget(reference: ProjectReference) {
        val entries = recent().filterNot { it.reference.id == reference.id }
        Files.createDirectories(root)
        DesktopStorage.atomicWrite(index, json.encodeToString(entries).encodeToByteArray())
        Files.deleteIfExists(thumbnailPath(reference))
    }

    fun thumbnail(reference: ProjectReference): ByteArray? {
        val path = thumbnailPath(reference)
        return if (Files.exists(path) && Files.size(path) <= maxThumbnailBytes)
            Files.readAllBytes(path)
        else null
    }

    private fun thumbnailPath(reference: ProjectReference): Path {
        val hash = MessageDigest.getInstance("SHA-256").digest(reference.id.encodeToByteArray())
        return thumbnails.resolve(java.util.HexFormat.of().formatHex(hash) + ".rgba")
    }

    companion object {
        const val maxEntries = 32
        private const val maxIndexBytes = 256 * 1024
        private const val maxThumbnailBytes = 512 * 512 * 4 + 4
    }
}
