package app.podor.desktop

import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.presentation.StudioController
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

class BrushLibraryTest {
    @Test
    fun overwriteAtCapacityRetainsIdentityAndFavoritesAndSurvivesReopening() = runBlocking {
        NativeLoader.load()
        val customs =
            List(StudioDefaults.maxCustomBrushes) {
                BrushPreset.Ink.copy(id = "custom-${it + 1}", label = "Ink ${it + 1}")
            }
        val initial = Preferences(brushes = customs)
        val saved = AtomicReference(Json.encodeToString(initial).encodeToByteArray())
        val threads = mutableListOf<String>()
        val files =
            object : ProjectFiles {
                override suspend fun open(): ByteArray? = null

                override suspend fun save(bytes: ByteArray, png: Boolean) =
                    error("Brush management must not save artwork")

                override suspend fun readPreferences() = saved.get()

                override suspend fun writePreferences(bytes: ByteArray) {
                    threads.add(Thread.currentThread().name)
                    saved.set(bytes)
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        var reopened: StudioController? = null
        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        assertNull(controller.error)
                        predicate()
                    }
                ) delay(5)
            }
        fun stored() = Json.decodeFromString<Preferences>(saved.get().decodeToString())
        try {
            waitFor { controller.ready }
            val before = withContext(Dispatchers.Main) { controller.document to controller.frame }
            withContext(Dispatchers.Main) {
                controller.selectPreset(customs.first())
                controller.toggleBrushFavorite(customs.first().id)
                controller.brush =
                    controller.brush.copy(
                        size = 53f,
                        opacity = 0.73f,
                        preset =
                            controller.brush.preset.copy(
                                grain = 0.67f,
                                stabilization = 0.4f,
                                pressureCurve = -0.2f,
                            ),
                    )
                controller.saveBrush(" Updated ink ", replace = true)
                val brush = controller.preferences.brushes.first()
                assertEquals("custom-1", brush.id)
                assertEquals("Updated ink", brush.label)
                assertEquals(53f, brush.size)
                assertEquals(0.73f, brush.opacity)
                assertEquals(0.67f, brush.grain)
                assertEquals(0.4f, brush.stabilization)
                assertEquals(-0.2f, brush.pressureCurve)
                assertEquals(customs.size, controller.preferences.brushes.size)
                assertEquals(setOf("custom-1"), controller.preferences.favoriteBrushes)
                controller.saveBrush("Copy")
                assertEquals(customs.size, controller.preferences.brushes.size)
                controller.selectPreset(BrushPreset.Ink)
                controller.saveBrush("Cannot overwrite built-in", replace = true)
                assertEquals(customs.size, controller.preferences.brushes.size)
                assertEquals(before.first, controller.document)
                assertSame(before.second, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
            }
            waitFor { stored().brushes.first().label == "Updated ink" }
            val next = withContext(Dispatchers.Main) { StudioController(files, scope) }
            reopened = next
            waitFor { next.ready }
            withContext(Dispatchers.Main) {
                assertEquals(controller.preferences, next.preferences)
                next.updatePreferences(
                    next.preferences.copy(brushes = next.preferences.brushes.dropLast(1))
                )
                next.selectPreset(next.preferences.brushes.first())
                next.saveBrush("Copy")
                assertEquals(64, next.preferences.brushes.size)
                assertNotEquals("custom-1", next.brush.preset.id)
                assertEquals("Copy", next.brush.preset.label)
                assertEquals(53f, next.brush.size)
                assertEquals(setOf("custom-1"), next.preferences.favoriteBrushes)
                next.updatePreferences(
                    next.preferences.copy(
                        brushes = next.preferences.brushes.filterNot { it.id == "custom-1" }
                    )
                )
                assertTrue(next.preferences.favoriteBrushes.isEmpty())
            }
            waitFor { stored().favoriteBrushes.isEmpty() && stored().brushes.size == 63 }
            assertTrue(threads.isNotEmpty())
            assertTrue(threads.none { "AWT-EventQueue" in it })
        } finally {
            withContext(Dispatchers.Main) {
                reopened?.shutdown()
                controller.shutdown()
            }
            scope.cancel()
        }
    }
}
