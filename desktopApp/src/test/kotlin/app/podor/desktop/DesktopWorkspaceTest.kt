package app.podor.desktop

import app.podor.desktop.data.DesktopWorkspace
import app.podor.domain.ProjectReference
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.*

class DesktopWorkspaceTest {
    @OptIn(ExperimentalPathApi::class)
    private fun workspace(test: (Path, DesktopWorkspace) -> Unit) {
        val root = Path.of("build", "workspace-tests").toAbsolutePath().normalize()
        Files.createDirectories(root)
        val directory = Files.createTempDirectory(root, "library-")
        try {
            test(directory, DesktopWorkspace(directory.resolve("index")))
        } finally {
            check(directory.toRealPath().startsWith(root.toRealPath()))
            directory.deleteRecursively()
        }
    }

    @Test
    fun libraryPersistsOrderAndThumbnailsWithoutModifyingOriginalArtwork() =
        workspace { directory, library ->
            val source = directory.resolve("drawing.podor")
            val original = byteArrayOf(1, 2, 3)
            Files.write(source, original)
            val first = ProjectReference(source.toString(), "山与海")
            val second = ProjectReference(directory.resolve("another.podor").toString(), "Sketch")
            library.remember(first, 256, 128, byteArrayOf(9, 7))
            library.remember(second, 32, 64, byteArrayOf(8))
            library.remember(first, 512, 128, byteArrayOf(6))
            val reopened = DesktopWorkspace(directory.resolve("index"))
            assertEquals(listOf(first, second), reopened.recent().map { it.reference })
            assertEquals(512, reopened.recent().first().width)
            assertContentEquals(byteArrayOf(6), reopened.thumbnail(first))
            reopened.forget(first)
            assertEquals(listOf(second), reopened.recent().map { it.reference })
            assertNull(reopened.thumbnail(first))
            assertContentEquals(original, Files.readAllBytes(source))
        }

    @Test
    fun libraryAndThumbnailStorageStayBounded() = workspace { directory, library ->
        repeat(DesktopWorkspace.maxEntries + 4) { index ->
            library.remember(
                ProjectReference("work-$index", "Work $index"),
                24,
                32,
                byteArrayOf(index.toByte()),
            )
        }
        assertEquals(DesktopWorkspace.maxEntries, library.recent().size)
        assertNull(library.thumbnail(ProjectReference("work-0", "Work 0")))
        Files.list(directory.resolve("index/thumbnails")).use {
            assertEquals(DesktopWorkspace.maxEntries.toLong(), it.count())
        }
    }

    @Test
    fun malformedIndexIsReportedWithoutOverwritingIt() = workspace { directory, library ->
        val root = directory.resolve("index")
        Files.createDirectories(root)
        val damaged = "{invalid}".encodeToByteArray()
        Files.write(root.resolve("workspace.json"), damaged)
        assertFailsWith<IllegalArgumentException> { library.recent() }
        assertFailsWith<IllegalArgumentException> {
            library.remember(ProjectReference("art.podor", "Art"), 32, 32, byteArrayOf())
        }
        assertContentEquals(damaged, Files.readAllBytes(root.resolve("workspace.json")))
    }
}
