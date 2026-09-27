package app.podor.desktop

import app.podor.desktop.data.DesktopStorage
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class DesktopStorageTest {
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun recoveryAndPreferencesSurviveReopeningWithoutTemporaryFiles() {
        val directory = Files.createTempDirectory("podor-storage-")
        try {
            val root = directory.resolve("data")
            val preferences = "{\"language\":\"English\"}".encodeToByteArray()
            val recovery = byteArrayOf(1, 2, 3)
            val storage = DesktopStorage(root)
            assertNull(storage.readPreferences())
            assertNull(storage.readRecovery())
            storage.writePreferences(preferences)
            storage.writeRecovery(recovery)
            val reopened = DesktopStorage(root)
            assertContentEquals(preferences, reopened.readPreferences())
            assertContentEquals(recovery, reopened.readRecovery())
            reopened.writeRecovery(byteArrayOf(9))
            assertContentEquals(byteArrayOf(9), storage.readRecovery())
            assertTrue(root.listDirectoryEntries("*.tmp").isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun oversizedPreferencesAreIgnoredAndDamagedRecoveryIsPreserved() {
        val directory = Files.createTempDirectory("podor-storage-")
        try {
            directory.resolve("preferences.json").writeBytes(ByteArray(1024 * 1024 + 1))
            val damaged = byteArrayOf(7, 3)
            directory.resolve("recovery.podor").writeBytes(damaged)
            val storage = DesktopStorage(directory)
            assertNull(storage.readPreferences())
            assertContentEquals(damaged, storage.readRecovery())
            storage.preserveRecovery(damaged)
            assertContentEquals(
                damaged,
                directory.listDirectoryEntries("recovery-damaged-*.podor").single().readBytes(),
            )
            assertContentEquals(damaged, directory.resolve("recovery.podor").readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }
}
