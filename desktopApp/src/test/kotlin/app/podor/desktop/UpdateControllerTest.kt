package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.podor.data.UpdateSource
import app.podor.domain.*
import app.podor.presentation.UpdateController
import app.podor.ui.PodorTheme
import app.podor.ui.UpdateSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

class UpdateControllerTest {
    private class Source : UpdateSource {
        var release =
            AppRelease(
                "0.2.1",
                "新增应用内更新，保留原有画笔、图层和工程。",
                "https://example.test/podor.msi",
                "0".repeat(64),
                100,
                "windows-x64",
            )
        var calls = 0
        var started = CompletableDeferred<Unit>()
        var revealed: String? = null
        var hold = true

        override suspend fun latest(): AppRelease {
            calls++
            return release
        }

        override suspend fun download(
            release: AppRelease,
            progress: suspend (Long) -> Unit,
        ): String {
            progress(50)
            started.complete(Unit)
            if (hold) awaitCancellation()
            return "verified.msi"
        }

        override suspend fun reveal(installer: String) {
            revealed = installer
        }
    }

    @Test
    fun checkingCancellationRetryAndRevealAreSeparateFromPainting() = runBlocking {
        val source = Source()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { UpdateController(source, scope, "0.2.0") }
        suspend fun await(phase: UpdatePhase) =
            withTimeout(5000) {
                while (!withContext(Dispatchers.Main) { controller.state.phase == phase }) delay(10)
            }
        try {
            withContext(Dispatchers.Main) {
                controller.check()
                controller.check()
            }
            await(UpdatePhase.Available)
            assertEquals(1, source.calls)
            withContext(Dispatchers.Main) { controller.download() }
            source.started.await()
            assertEquals(50, withContext(Dispatchers.Main) { controller.state.received })
            withContext(Dispatchers.Main) { controller.cancel() }
            await(UpdatePhase.Available)
            source.hold = false
            withContext(Dispatchers.Main) { controller.download() }
            await(UpdatePhase.Downloaded)
            withContext(Dispatchers.Main) { controller.reveal() }
            withTimeout(5000) { while (source.revealed == null) delay(10) }
            assertEquals("verified.msi", source.revealed)
            source.release = source.release.copy(version = "0.1.8")
            withContext(Dispatchers.Main) { controller.check() }
            await(UpdatePhase.Current)
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
            scope.cancel()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun updatePageRendersBothLanguagesAtNarrowWidth() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller =
                withContext(Dispatchers.Main) { UpdateController(Source(), scope, "0.2.0") }
            try {
                withContext(Dispatchers.Main) { controller.check() }
                withTimeout(5000) {
                    while (
                        withContext(Dispatchers.Main) {
                            controller.state.phase != UpdatePhase.Available
                        }
                    ) delay(10)
                }
                withContext(Dispatchers.Main) {
                    for (language in Language.entries) {
                        val scene =
                            ImageComposeScene(420, 520) {
                                PodorTheme(language) {
                                    androidx.compose.material3.Surface {
                                        Box(Modifier.fillMaxSize().padding(24.dp)) {
                                            UpdateSettings(controller)
                                        }
                                    }
                                }
                            }
                        try {
                            scene.render().close()
                            scene.render(500_000_000).use { rendered ->
                                val path =
                                    Path.of("build/reports/screenshots/update-${language.name}.png")
                                Files.createDirectories(path.parent)
                                Files.write(
                                    path,
                                    rendered.encodeToData(EncodedImageFormat.PNG)!!.bytes,
                                )
                            }
                        } finally {
                            scene.close()
                        }
                    }
                }
            } finally {
                controller.close()
                scope.cancel()
            }
        }
}
