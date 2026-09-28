package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class AppearanceTest {
    @Test
    fun oldPreferencesKeepDarkAppearance() {
        assertEquals(Appearance.Dark, Json.decodeFromString<Preferences>("{} ").appearance)
        val light = Preferences(appearance = Appearance.Light)
        assertEquals(light, Json.decodeFromString<Preferences>(Json.encodeToString(light)))
    }

    @Test
    fun appearanceUpdatesAllPanelsAndPersistsWithoutChangingArtwork() = runBlocking {
        NativeLoader.load()
        val saved = AtomicReference<ByteArray?>()
        val files =
            object : ProjectFiles {
                override suspend fun open(): ByteArray? = null

                override suspend fun save(bytes: ByteArray, png: Boolean) = false

                override suspend fun readPreferences() = saved.get()

                override suspend fun writePreferences(bytes: ByteArray) {
                    saved.set(bytes)
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        var reopened: StudioController? = null
        try {
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { controller.ready }) delay(10)
            }
            withContext(Dispatchers.Main) {
                val page = mutableStateOf("panels")
                var selectedDialog: StudioDialog? = null
                val scene =
                    ImageComposeScene(1360, 920) {
                        PodorTheme(
                            controller.preferences.language,
                            controller.preferences.appearance,
                            androidx.compose.ui.graphics.Color(controller.brush.color),
                        ) {
                            Surface(Modifier.fillMaxSize(), color = StudioTheme.background) {
                                when (page.value) {
                                    "panels" ->
                                        Row(
                                            Modifier.fillMaxSize().padding(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            StudioPanel.entries.forEach { panel ->
                                                Surface(
                                                    Modifier.weight(1f).fillMaxHeight(),
                                                    color = StudioTheme.panel,
                                                ) {
                                                    Inspector(controller, panel, {})
                                                }
                                            }
                                        }
                                    "settings" -> SettingsDialog(controller, {})
                                    "new" -> NewCanvasDialog(controller, {})
                                    "home" -> WorkspaceHome(controller)
                                    "studio" -> StudioApp(controller)
                                    "brush" -> BrushControls(controller)
                                    "header" ->
                                        Column {
                                            StudioHeader(
                                                controller,
                                                false,
                                                true,
                                                { selectedDialog = it },
                                            )
                                        }
                                    "menu" ->
                                        Box(Modifier.padding(80.dp)) {
                                            Text("Menu")
                                            StudioDropdownMenu(true, {}) {
                                                Text("Layers", Modifier.padding(24.dp))
                                                Text("Brushes", Modifier.padding(24.dp))
                                            }
                                        }
                                }
                            }
                        }
                    }
                var frame = 0L
                fun settle() {
                    repeat(280) { scene.render(frame++ * 16_666_667L).close() }
                }
                fun capture(name: String) {
                    val path = Path.of("build/reports/screenshots/appearance-$name.png")
                    Files.createDirectories(path.parent)
                    scene.render(frame++ * 16_666_667L).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(path, it.bytes)
                        }
                    }
                }
                val brush = controller.brush
                val revision = controller.document.revision
                val pixels = controller.frame
                try {
                    for (appearance in Appearance.entries) {
                        controller.updatePreferences(
                            controller.preferences.copy(appearance = appearance)
                        )
                        for (screen in
                            listOf("panels", "settings", "new", "home", "studio", "menu")) {
                            page.value = screen
                            if (screen == "menu") {
                                repeat(80) { scene.render(frame++ * 16_666_667L).close() }
                                capture("${appearance.name}-menu-trail")
                            }
                            settle()
                            capture("${appearance.name}-$screen")
                            scene.render(frame++ * 16_666_667L).use { image ->
                                if (screen == "panels") {
                                    val background = image.toComposeImageBitmap().toPixelMap()[1, 1]
                                    assertEquals(StudioTheme.background, background)
                                    assertEquals(
                                        appearance == Appearance.Light,
                                        background.red > 0.5f,
                                    )
                                }
                            }
                            if (screen == "new") {
                                scene.render(frame++ * 16_666_667L).use { image ->
                                    val pixels = image.toComposeImageBitmap().toPixelMap()
                                    val white =
                                        (0 until pixels.height step 3).sumOf { y ->
                                            (0 until pixels.width step 3).count { x ->
                                                pixels[x, y] ==
                                                    androidx.compose.ui.graphics.Color.White
                                            }
                                        }
                                    assertTrue(
                                        white > 500,
                                        "New canvas paper preview must remain white in $appearance",
                                    )
                                }
                            }
                            assertFalse(
                                scene.hasInvalidations(),
                                "$appearance $screen still animates while idle",
                            )
                        }
                    }
                    page.value = "settings"
                    settle()
                    for ((x, expected) in
                        listOf(550f to Appearance.Dark, 790f to Appearance.Light)) {
                        scene.sendPointerEvent(PointerEventType.Press, Offset(x, 410f))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(x, 410f))
                        scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                        settle()
                        assertEquals(expected, controller.preferences.appearance)
                        assertEquals(expected, StudioTheme.appearance)
                    }
                    page.value = "brush"
                    controller.brush = controller.brush.copy(color = 0xFFFF5500)
                    settle()
                    scene.render(frame++ * 16_666_667L).use { image ->
                        val pixels = image.toComposeImageBitmap().toPixelMap()
                        val count =
                            (0 until pixels.height step 3).sumOf { y ->
                                (0 until pixels.width step 3).count { x ->
                                    val color = pixels[x, y]
                                    color.red > 0.95f &&
                                        color.green in 0.30f..0.37f &&
                                        color.blue < 0.05f
                                }
                            }
                        assertTrue(count > 1000, "Brush sliders must use the current paint color")
                    }
                    page.value = "panels"
                    controller.fillTolerance = 83f
                    controller.brush = controller.brush.copy(color = 0xFF008A46)
                    settle()
                    capture("Light-selected-color")
                    scene.render(frame++ * 16_666_667L).use { image ->
                        val pixels = image.toComposeImageBitmap().toPixelMap()
                        val count =
                            (0 until pixels.height step 3).sumOf { y ->
                                (1040 until pixels.width step 3).count { x ->
                                    val color = pixels[x, y]
                                    color.red < 0.03f &&
                                        color.green in 0.52f..0.56f &&
                                        color.blue in 0.26f..0.29f
                                }
                            }
                        assertTrue(
                            count > 80,
                            "Fill tolerance slider must update with the selected color",
                        )
                    }
                    controller.brush = brush
                    page.value = "header"
                    settle()
                    scene.sendPointerEvent(PointerEventType.Press, Offset(110f, 30f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(110f, 30f))
                    settle()
                    capture("Light-icon-menu")
                    scene.sendPointerEvent(PointerEventType.Press, Offset(110f, 86f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(110f, 86f))
                    settle()
                    assertEquals(StudioDialog.New, selectedDialog)
                    assertEquals(brush, controller.brush)
                    assertEquals(revision, controller.document.revision)
                    assertSame(pixels, controller.frame)
                } finally {
                    scene.close()
                    val reset =
                        ImageComposeScene(10, 10) { PodorTheme(appearance = Appearance.Dark) {} }
                    try {
                        repeat(3) { reset.render(it * 16_666_667L).close() }
                    } finally {
                        reset.close()
                    }
                }
            }
            withTimeout(5_000) {
                while (
                    saved.get()?.let {
                        Json.decodeFromString<Preferences>(it.decodeToString()).appearance
                    } != Appearance.Light
                ) delay(10)
            }
            reopened = withContext(Dispatchers.Main) { StudioController(files, scope) }
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { reopened.ready }) delay(10)
            }
            assertEquals(Appearance.Light, reopened.preferences.appearance)
        } finally {
            withContext(Dispatchers.Main) {
                reopened?.shutdown()
                controller.shutdown()
            }
            scope.cancel()
        }
    }
}
