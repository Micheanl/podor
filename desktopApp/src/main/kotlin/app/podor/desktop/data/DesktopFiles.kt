package app.podor.desktop.data

import app.podor.data.ProjectFiles
import app.podor.desktop.data.DesktopStorage.Companion.atomicWrite
import app.podor.domain.AppIdentity
import app.podor.domain.ExportFormat
import app.podor.domain.OpenedProject
import app.podor.domain.ProjectReference
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopFiles(
    private val storage: DesktopStorage = DesktopStorage(),
    private val owner: () -> Frame?,
) : ProjectFiles {
    private val workspace = DesktopWorkspace(storage.root)
    override val exportFormats = ExportFormat.entries
    override val clipboard = DesktopClipboard()

    override suspend fun openImage(): OpenedProject? {
        val path = choose(false, images = true) ?: return null
        return openDocument(reference(path))
    }

    override suspend fun openReference(): OpenedProject? {
        val path = choose(false, images = true) ?: return null
        return withContext(Dispatchers.IO) {
            require(Files.size(path) <= app.podor.domain.StudioDefaults.maxClipboardBytes) { "参考图文件过大" }
            OpenedProject(Files.readAllBytes(path), reference(path))
        }
    }

    override suspend fun openDocument(reference: ProjectReference?): OpenedProject? {
        val path = reference?.let { Path.of(it.id) } ?: choose(false) ?: return null
        return withContext(Dispatchers.IO) {
            require(Files.isRegularFile(path)) { "找不到这份作品，请重新选择文件" }
            require(Files.size(path) <= DesktopStorage.maxFileBytes) { "文件过大" }
            OpenedProject(Files.readAllBytes(path), reference(path))
        }
    }

    override suspend fun saveDocument(
        bytes: ByteArray,
        reference: ProjectReference?,
        saveAs: Boolean,
    ): ProjectReference? {
        val existing = reference?.takeIf {
            it.editable && it.id.endsWith(".${AppIdentity.projectExtension}", true)
        }
        val path =
            if (!saveAs && existing != null) Path.of(existing.id)
            else choose(true, suggestedName = reference?.name ?: AppIdentity.name) ?: return null
        withContext(Dispatchers.IO) { atomicWrite(path, bytes) }
        return reference(path)
    }

    override suspend fun recentProjects() = withContext(Dispatchers.IO) { workspace.recent() }

    override suspend fun rememberProject(
        reference: ProjectReference,
        width: Int,
        height: Int,
        thumbnail: ByteArray,
    ) = withContext(Dispatchers.IO) { workspace.remember(reference, width, height, thumbnail) }

    override suspend fun forgetProject(reference: ProjectReference) =
        withContext(Dispatchers.IO) { workspace.forget(reference) }

    override suspend fun readThumbnail(reference: ProjectReference) =
        withContext(Dispatchers.IO) { workspace.thumbnail(reference) }

    private fun reference(path: Path) =
        ProjectReference(
            path.toAbsolutePath().normalize().toString(),
            path.fileName.toString().substringBeforeLast('.'),
            path.fileName.toString().endsWith(".${AppIdentity.projectExtension}", true),
        )

    override suspend fun export(bytes: ByteArray, format: ExportFormat): Boolean {
        val path = choose(true, format.extension) ?: return false
        withContext(Dispatchers.IO) { atomicWrite(path, bytes) }
        return true
    }

    override suspend fun readPreferences(): ByteArray? =
        withContext(Dispatchers.IO) { storage.readPreferences() }

    override suspend fun writePreferences(bytes: ByteArray) =
        withContext(Dispatchers.IO) { storage.writePreferences(bytes) }

    override suspend fun openBrushPack(): ByteArray? {
        val path = choose(false, "podor-brushes.json", true) ?: return null
        return withContext(Dispatchers.IO) {
            require(Files.size(path) <= app.podor.domain.BrushPack.maxFileBytes) { "笔刷包过大" }
            Files.readAllBytes(path)
        }
    }

    override suspend fun saveBrushPack(bytes: ByteArray): Boolean {
        val path = choose(true, "podor-brushes.json", true) ?: return false
        withContext(Dispatchers.IO) { atomicWrite(path, bytes) }
        return true
    }

    override suspend fun open(): ByteArray? {
        val path = choose(false) ?: return null
        return withContext(Dispatchers.IO) {
            require(Files.size(path) <= DesktopStorage.maxFileBytes) { "文件过大" }
            Files.readAllBytes(path)
        }
    }

    override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
        val path = choose(true, if (png) "png" else AppIdentity.projectExtension) ?: return false
        withContext(Dispatchers.IO) { atomicWrite(path, bytes) }
        return true
    }

    private suspend fun choose(
        save: Boolean,
        extension: String = AppIdentity.projectExtension,
        brushes: Boolean = false,
        suggestedName: String = AppIdentity.name,
        images: Boolean = false,
    ): Path? =
        withContext(Dispatchers.Main) {
            val dialog =
                FileDialog(
                    owner(),
                    AppIdentity.name,
                    if (save) FileDialog.SAVE else FileDialog.LOAD,
                )
            dialog.file =
                if (save) "$suggestedName.$extension"
                else if (brushes) "*.json"
                else if (images) "*.png;*.jpg;*.jpeg;*.webp"
                else "*.${AppIdentity.projectExtension};*.psd;*.ora;*.png;*.jpg;*.jpeg;*.webp"
            try {
                dialog.isVisible = true
                dialog.file?.let { name ->
                    Path.of(
                        dialog.directory,
                        if (save && !name.endsWith(".$extension", true)) "$name.$extension"
                        else name,
                    )
                }
            } finally {
                dialog.dispose()
            }
        }
}
