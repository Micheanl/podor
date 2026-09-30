package app.podor.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ToolDockPosition(val label: String) {
    Left("左侧"),
    Right("右侧"),
    Top("顶部"),
    Bottom("底部"),
}

@Serializable
enum class InspectorPosition(val label: String) {
    Left("左侧"),
    Right("右侧"),
}

@Serializable
enum class InterfaceDensity(val label: String) {
    Compact("紧凑"),
    Standard("标准"),
    Comfortable("宽松"),
}

@Serializable
data class WorkspaceAppearance(
    val toolDock: ToolDockPosition = StudioDefaults.toolDockPosition,
    val toolOrder: List<String> = StudioDefaults.workspaceToolOrder,
    val hiddenTools: Set<String> = emptySet(),
    val inspectorPosition: InspectorPosition = StudioDefaults.inspectorPosition,
    val density: InterfaceDensity = StudioDefaults.interfaceDensity,
    val scale: Float = StudioDefaults.interfaceScale,
    val showStatusBar: Boolean = StudioDefaults.showStatusBar,
    val reducedMotion: Boolean = StudioDefaults.reducedMotion,
) {
    fun valid(): Boolean =
        scale.isFinite() &&
            scale in 0.75f..2f &&
            toolOrder.size == Tool.entries.size &&
            toolOrder.toSet() == Tool.entries.map { it.name }.toSet() &&
            hiddenTools.all { it in toolOrder }

    fun orderedTools(): List<Tool> = toolOrder.map { name ->
        Tool.entries.first { it.name == name }
    }

    fun visibleTools(): List<Tool> = orderedTools().filter { it.name !in hiddenTools }

    fun moveTool(tool: Tool, offset: Int): WorkspaceAppearance {
        require(offset == -1 || offset == 1)
        val source = toolOrder.indexOf(tool.name)
        val destination = (source + offset).coerceIn(toolOrder.indices)
        if (source == destination) return this
        val order = toolOrder.toMutableList()
        order.add(destination, order.removeAt(source))
        return copy(toolOrder = order)
    }

    fun showTool(tool: Tool, visible: Boolean): WorkspaceAppearance =
        copy(hiddenTools = if (visible) hiddenTools - tool.name else hiddenTools + tool.name)
}
