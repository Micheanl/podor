package app.podor.data

import app.podor.domain.ExportFormat

interface ProjectFiles {
    val exportFormats: List<ExportFormat>
        get() = listOf(ExportFormat.Png)

    suspend fun export(bytes: ByteArray, format: ExportFormat): Boolean {
        require(format == ExportFormat.Png)
        return save(bytes, png = true)
    }

    suspend fun readPreferences(): ByteArray? = null

    suspend fun writePreferences(bytes: ByteArray) {}

    suspend fun openBrushPack(): ByteArray? = open()

    suspend fun saveBrushPack(bytes: ByteArray): Boolean = false

    suspend fun open(): ByteArray?

    suspend fun save(bytes: ByteArray, png: Boolean): Boolean

    suspend fun readRecovery(): ByteArray?

    suspend fun writeRecovery(bytes: ByteArray)

    suspend fun preserveRecovery(bytes: ByteArray)
}
