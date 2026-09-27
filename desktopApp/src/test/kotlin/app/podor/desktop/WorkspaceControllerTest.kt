package app.podor.desktop

import androidx.compose.ui.geometry.Offset
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put

class WorkspaceControllerTest {
    private class Files : ProjectFiles {
        var saved: ByteArray? = null
        var writes = 0
        var opened: ByteArray? = null
        var preferences: ByteArray? = null
        var cancelSave = false
        var failSave = false
        var entries = emptyList<RecentProject>()
        var thumbnail: ByteArray? = null

        override suspend fun open() = opened

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            check(!javax.swing.SwingUtilities.isEventDispatchThread())
            if (failSave) error("磁盘写入失败")
            if (cancelSave) return false
            writes++
            saved = bytes
            return true
        }

        override suspend fun saveDocument(
            bytes: ByteArray,
            reference: ProjectReference?,
            saveAs: Boolean,
        ) = if (save(bytes, false)) ProjectReference("saved.podor", "Study") else null

        override suspend fun readPreferences() = preferences

        override suspend fun writePreferences(bytes: ByteArray) {
            preferences = bytes
        }

        override suspend fun recentProjects() = entries

        override suspend fun rememberProject(
            reference: ProjectReference,
            width: Int,
            height: Int,
            thumbnail: ByteArray,
        ) {
            entries = listOf(RecentProject(reference, width, height, 1))
            this.thumbnail = thumbnail
        }

        override suspend fun forgetProject(reference: ProjectReference) {
            entries = emptyList()
        }

        override suspend fun readThumbnail(reference: ProjectReference) = thumbnail
    }

    private suspend fun awaitState(predicate: () -> Boolean) =
        withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
        }

    @Test
    fun startupDefaultsToWorkspaceAndBlackWithoutAutomaticSaving() =
        runBlocking<Unit> {
            NativeLoader.load()
            val files = Files()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) {
                    assertTrue(controller.showWorkspace)
                    assertFalse(controller.hasCanvas)
                    assertEquals(0xFF000000L, controller.brush.color)
                    assertNull(controller.error)
                    controller.navigate(WorkspaceDestination.New(32, 24))
                }
                awaitState { controller.document.width == 32 }
                withContext(Dispatchers.Main) {
                    controller.begin(Offset(8f, 8f), 1f)
                    controller.end()
                }
                awaitState { controller.hasUnsavedChanges }
                delay(16_100)
                withContext(Dispatchers.Main) { controller.shutdown() }
                assertEquals(0, files.writes)
            } finally {
                withContext(Dispatchers.Main) { controller.close() }
                scope.cancel()
            }
        }

    @Test
    fun explicitSaveCanRetryAndCancelledSaveCannotDiscardQueuedStrokes() =
        runBlocking<Unit> {
            NativeLoader.load()
            val files = Files()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) {
                    controller.begin(Offset(64f, 64f), 1f)
                    controller.points(listOf(Triple(128f, 64f, 1f)))
                    controller.navigate(WorkspaceDestination.New(48, 48))
                }
                awaitState { controller.pendingNavigation != null }
                files.cancelSave = true
                withContext(Dispatchers.Main) { controller.resolveUnsaved(UnsavedChoice.Save) }
                delay(100)
                awaitState { !controller.busy }
                withContext(Dispatchers.Main) {
                    assertNotNull(controller.pendingNavigation)
                    assertEquals(1600, controller.document.width)
                    assertTrue(controller.hasUnsavedChanges)
                }
                files.cancelSave = false
                files.failSave = true
                withContext(Dispatchers.Main) { controller.resolveUnsaved(UnsavedChoice.Save) }
                awaitState { controller.error != null && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertNotNull(controller.pendingNavigation)
                    assertTrue(controller.ready)
                    controller.dismissError()
                }
                files.failSave = false
                withContext(Dispatchers.Main) { controller.resolveUnsaved(UnsavedChoice.Save) }
                awaitState { controller.document.width == 48 && !controller.busy }
                val engine = createNativeEngine(1, 1)
                try {
                    engine.call(EngineOperation.LOAD, assertNotNull(files.saved))
                    assertEquals(
                        """{"color":[0,0,0]}""",
                        engine
                            .call(
                                EngineOperation.COMMAND,
                                """{"type":"pick","x":100,"y":64}""".encodeToByteArray(),
                            )
                            .decodeToString(),
                    )
                } finally {
                    engine.close()
                }
                assertEquals(1, files.writes)
                assertNotNull(files.thumbnail)
                withContext(Dispatchers.Main) {
                    assertEquals("Study", controller.recentProjects.single().reference.name)
                    assertFalse(controller.hasUnsavedChanges)
                }
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }

    @Test
    fun homeKeepsSessionInMemoryAndExitCanCancelOrDiscardWithoutSaving() =
        runBlocking<Unit> {
            NativeLoader.load()
            val files = Files()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) {
                    controller.navigate(WorkspaceDestination.New(32, 32))
                }
                awaitState { controller.document.width == 32 }
                withContext(Dispatchers.Main) { controller.command("add_layer") }
                awaitState { controller.document.layers.size == 2 }
                withContext(Dispatchers.Main) {
                    controller.home()
                    assertTrue(controller.showWorkspace)
                    assertTrue(controller.hasCanvas)
                    controller.resumeCanvas()
                    assertFalse(controller.showWorkspace)
                    controller.navigate(WorkspaceDestination.Exit)
                }
                awaitState { controller.pendingNavigation != null }
                withContext(Dispatchers.Main) { controller.resolveUnsaved(UnsavedChoice.Cancel) }
                awaitState { controller.pendingNavigation == null }
                withContext(Dispatchers.Main) {
                    assertFalse(controller.exitRequested)
                    assertEquals(2, controller.document.layers.size)
                    controller.navigate(WorkspaceDestination.Exit)
                }
                awaitState { controller.pendingNavigation != null }
                withContext(Dispatchers.Main) { controller.resolveUnsaved(UnsavedChoice.Discard) }
                awaitState { controller.exitRequested }
                assertEquals(0, files.writes)
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }

    @Test
    fun updateWaitsForUnsavedDecisionAndFailedHandoffKeepsCanvasOpen() =
        runBlocking<Unit> {
            NativeLoader.load()
            val files = Files()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            var installs = 0
            var failInstall = true
            val release =
                AppRelease(
                    "0.2.5",
                    "",
                    "https://example.test/app.msi",
                    "0".repeat(64),
                    10,
                    "windows-x64",
                )
            val destination = WorkspaceDestination.InstallUpdate(release, "installer.msi")
            val controller =
                withContext(Dispatchers.Main) {
                    StudioController(files, scope) { received, path ->
                        check(!javax.swing.SwingUtilities.isEventDispatchThread())
                        assertEquals(release, received)
                        assertEquals("installer.msi", path)
                        installs++
                        if (failInstall) error("安装程序无法启动")
                    }
                }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.command("add_layer") }
                awaitState { controller.document.layers.size == 2 }
                withContext(Dispatchers.Main) { controller.navigate(destination) }
                awaitState { controller.pendingNavigation != null }
                assertEquals(0, installs)
                withContext(Dispatchers.Main) { controller.resolveUnsaved(UnsavedChoice.Cancel) }
                awaitState { controller.pendingNavigation == null }
                assertEquals(0, installs)
                withContext(Dispatchers.Main) { controller.navigate(destination) }
                awaitState { controller.pendingNavigation != null }
                files.cancelSave = true
                withContext(Dispatchers.Main) { controller.resolveUnsaved(UnsavedChoice.Save) }
                delay(100)
                assertEquals(0, installs)
                withContext(Dispatchers.Main) { controller.resolveUnsaved(UnsavedChoice.Discard) }
                awaitState { controller.error != null && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertFalse(controller.exitRequested)
                    assertTrue(controller.ready)
                    assertTrue(controller.hasUnsavedChanges)
                    assertEquals(2, controller.document.layers.size)
                    controller.dismissError()
                    controller.navigate(destination)
                }
                awaitState { controller.pendingNavigation != null }
                files.cancelSave = false
                failInstall = false
                withContext(Dispatchers.Main) { controller.resolveUnsaved(UnsavedChoice.Save) }
                awaitState { controller.exitRequested }
                assertEquals(2, installs)
                assertEquals(1, files.writes)
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }

    @Test
    fun startupChoiceSurvivesRestartAndLayerActionsUpdatePreviewsAndUndo() =
        runBlocking<Unit> {
            NativeLoader.load()
            val files =
                Files().apply {
                    preferences =
                        Json.encodeToString(Preferences(startupScreen = StartupScreen.Canvas))
                            .encodeToByteArray()
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) {
                    assertFalse(controller.showWorkspace)
                    controller.select(Offset(0f, 0f), Offset(16f, 16f))
                    controller.fill(Offset(8f, 8f))
                }
                awaitState { controller.hasUnsavedChanges && !controller.busy }
                withContext(Dispatchers.Main) {
                    controller.command("duplicate_layer") { put("id", 1) }
                }
                awaitState {
                    controller.document.layers.size == 2 &&
                        controller.previews.revision == controller.document.revision
                }
                withContext(Dispatchers.Main) {
                    assertEquals(2, controller.document.active)
                    controller.command("merge_visible")
                }
                awaitState { controller.document.layers.size == 1 && !controller.busy }
                withContext(Dispatchers.Main) { controller.command("undo") }
                awaitState { controller.document.layers.size == 2 }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
                awaitState { files.writes == 1 && !controller.busy }
                withContext(Dispatchers.Main) { controller.command("add_layer") }
                awaitState { controller.document.layers.size == 3 && controller.hasUnsavedChanges }
                withContext(Dispatchers.Main) { controller.command("undo") }
                awaitState { controller.document.layers.size == 2 && !controller.hasUnsavedChanges }
                withContext(Dispatchers.Main) { controller.command("redo") }
                awaitState { controller.hasUnsavedChanges }
                withContext(Dispatchers.Main) {
                    controller.updatePreferences(
                        controller.preferences.copy(startupScreen = StartupScreen.Workspace)
                    )
                }
                withContext(Dispatchers.Main) { controller.shutdown() }
                assertEquals(
                    StartupScreen.Workspace,
                    Json.decodeFromString<Preferences>(files.preferences!!.decodeToString())
                        .startupScreen,
                )
            } finally {
                withContext(Dispatchers.Main) { controller.close() }
                scope.cancel()
            }
        }
}
