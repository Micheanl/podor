package app.podor.desktop.data

import app.podor.data.ProjectFiles
import app.podor.desktop.data.DesktopStorage.Companion.atomicWrite
import app.podor.domain.AppIdentity
import app.podor.domain.ExportFormat
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
    override val exportFormats = ExportFormat.entries

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

    override suspend fun readRecovery(): ByteArray? =
        withContext(Dispatchers.IO) { storage.readRecovery() }

    override suspend fun writeRecovery(bytes: ByteArray) =
        withContext(Dispatchers.IO) { storage.writeRecovery(bytes) }

    override suspend fun preserveRecovery(bytes: ByteArray) =
        withContext(Dispatchers.IO) { storage.preserveRecovery(bytes) }

    private suspend fun choose(
        save: Boolean,
        extension: String = AppIdentity.projectExtension,
        brushes: Boolean = false,
    ): Path? =
        withContext(Dispatchers.Main) {
            val dialog =
                FileDialog(
                    owner(),
                    AppIdentity.name,
                    if (save) FileDialog.SAVE else FileDialog.LOAD,
                )
            dialog.file =
                if (save) "${AppIdentity.name}.$extension"
                else if (brushes) "*.json"
                else
                    "*.${AppIdentity.projectExtension};*.png;*.jpg;*.jpeg;*.webp"
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
