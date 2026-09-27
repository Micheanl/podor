package app.podor.domain

import kotlinx.serialization.Serializable

@Serializable
data class ProjectReference(val id: String, val name: String, val editable: Boolean = true)

data class OpenedProject(val bytes: ByteArray, val reference: ProjectReference?)

@Serializable
data class RecentProject(
    val reference: ProjectReference,
    val width: Int,
    val height: Int,
    val openedAt: Long,
)

@Serializable
enum class StartupScreen(val label: String) {
    Workspace("作品首页"),
    Canvas("空白画布"),
}

sealed interface WorkspaceDestination {
    data class New(val width: Int, val height: Int) : WorkspaceDestination

    data class Open(val reference: ProjectReference? = null) : WorkspaceDestination

    data object Exit : WorkspaceDestination

    data class InstallUpdate(val release: AppRelease, val installer: String) : WorkspaceDestination
}

enum class UnsavedChoice {
    Save,
    Discard,
    Cancel,
}
