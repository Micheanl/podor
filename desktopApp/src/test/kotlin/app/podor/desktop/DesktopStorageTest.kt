package app.podor.desktop

import app.podor.desktop.data.DesktopStorage
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class DesktopStorageTest {
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun preferencesSurviveReopeningWithoutTemporaryFiles() {
        val directory = Files.createTempDirectory("podor-storage-")
        try {
            val root = directory.resolve("data")
            val preferences = "{\"language\":\"English\"}".encodeToByteArray()
            val storage = DesktopStorage(root)
            assertNull(storage.readPreferences())
            storage.writePreferences(preferences)
            val reopened = DesktopStorage(root)
            assertContentEquals(preferences, reopened.readPreferences())
            reopened.writePreferences(byteArrayOf(9))
            assertContentEquals(byteArrayOf(9), storage.readPreferences())
            assertTrue(root.listDirectoryEntries("*.tmp").isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun oversizedPreferencesAreIgnoredAndLegacyArtworkIsUntouched() {
        val directory = Files.createTempDirectory("podor-storage-")
        try {
            directory.resolve("preferences.json").writeBytes(ByteArray(1024 * 1024 + 1))
            val damaged = byteArrayOf(7, 3)
            directory.resolve("recovery.podor").writeBytes(damaged)
            val storage = DesktopStorage(directory)
            assertNull(storage.readPreferences())
            storage.writePreferences("{}".encodeToByteArray())
            assertContentEquals(damaged, directory.resolve("recovery.podor").readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }
}
