package app.podor.data

import app.podor.domain.ExportFormat
import app.podor.domain.OpenedProject
import app.podor.domain.ProjectReference
import app.podor.domain.RecentProject

interface ProjectFiles {
    val clipboard: ImageClipboard? get() = null

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

    suspend fun openImage(): OpenedProject? = openDocument()

    suspend fun openReference(): OpenedProject? = openImage()

    suspend fun save(bytes: ByteArray, png: Boolean): Boolean

    suspend fun openDocument(reference: ProjectReference? = null): OpenedProject? {
        require(reference == null) { "无法打开这份作品，请重新选择文件" }
        return open()?.let { OpenedProject(it, null) }
    }

    suspend fun saveDocument(
        bytes: ByteArray,
        reference: ProjectReference?,
        saveAs: Boolean = false,
    ): ProjectReference? =
        if (save(bytes, png = false)) reference ?: ProjectReference("", "未命名") else null

    suspend fun recentProjects(): List<RecentProject> = emptyList()

    suspend fun rememberProject(
        reference: ProjectReference,
        width: Int,
        height: Int,
        thumbnail: ByteArray,
    ) {}

    suspend fun forgetProject(reference: ProjectReference) {}

    suspend fun readThumbnail(reference: ProjectReference): ByteArray? = null
}
