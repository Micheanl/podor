package app.podor.desktop.data

import app.podor.domain.AppIdentity
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DesktopStorage(
    val root: Path = Path.of(System.getProperty("user.home"), AppIdentity.dataDirectory)
) {
    fun readPreferences(): ByteArray? = readLimited(root.resolve("preferences.json"), 1024 * 1024)

    fun writePreferences(bytes: ByteArray) = write("preferences.json", bytes)

    private fun readLimited(path: Path, limit: Long): ByteArray? =
        if (Files.exists(path) && Files.size(path) <= limit) Files.readAllBytes(path) else null

    private fun write(name: String, bytes: ByteArray) {
        Files.createDirectories(root)
        atomicWrite(root.resolve(name), bytes)
    }

    companion object {
        const val maxFileBytes = 256L * 1024 * 1024

        fun atomicWrite(path: Path, bytes: ByteArray) {
            val temp =
                Files.createTempFile(path.toAbsolutePath().parent, ".${AppIdentity.name}-", ".tmp")
            try {
                Files.write(temp, bytes)
                try {
                    Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }
}
