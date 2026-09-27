package app.podor.desktop.data

import app.podor.data.UpdateException
import app.podor.domain.AppIdentity
import app.podor.domain.AppRelease
import app.podor.domain.UpdateProblem
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

class DesktopInstaller(
    private val directory: Path,
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).start() },
) {
    suspend fun install(release: AppRelease, installer: String) =
        withContext(Dispatchers.IO) {
            release.validate()
            val root = directory.toAbsolutePath().normalize()
            val path = Path.of(installer).toAbsolutePath().normalize()
            if (
                path != root.resolve("${AppIdentity.name}-${release.version}.msi") ||
                    !Files.isRegularFile(path) ||
                    Files.size(path) != release.size
            )
                throw UpdateException(UpdateProblem.Integrity)
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            if (!hash.digest().joinToString("") { "%02x".format(it) }.equals(release.sha256, true))
                throw UpdateException(UpdateProblem.Integrity)
            val script = root.resolve("install-update.ps1")
            DesktopInstaller::class.java.getResourceAsStream("/install-update.ps1")!!.use {
                DesktopStorage.atomicWrite(script, it.readAllBytes())
            }
            val ready = root.resolve("install-${UUID.randomUUID()}.ready")
            val powershell =
                Path.of(
                    System.getenv("SystemRoot"),
                    "System32",
                    "WindowsPowerShell",
                    "v1.0",
                    "powershell.exe",
                )
            var process: Process? = null
            try {
                process =
                    launch(
                        listOf(
                            powershell.toString(),
                            "-NoProfile",
                            "-NonInteractive",
                            "-WindowStyle",
                            "Hidden",
                            "-ExecutionPolicy",
                            "Bypass",
                            "-File",
                            script.toString(),
                            "-ParentId",
                            ProcessHandle.current().pid().toString(),
                            "-Installer",
                            path.toString(),
                            "-Sha256",
                            release.sha256,
                            "-Size",
                            release.size.toString(),
                            "-ReadyFile",
                            ready.toString(),
                        )
                    )
                withTimeout(10_000) {
                    while (!Files.exists(ready)) {
                        if (!process.isAlive) throw UpdateException(UpdateProblem.Installation)
                        delay(50)
                    }
                }
            } catch (failure: Exception) {
                process?.destroy()
                if (failure is CancellationException && failure !is TimeoutCancellationException)
                    throw failure
                throw UpdateException(UpdateProblem.Installation)
            } finally {
                Files.deleteIfExists(ready)
            }
        }
}
