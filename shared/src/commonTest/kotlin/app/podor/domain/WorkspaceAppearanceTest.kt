package app.podor.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class WorkspaceAppearanceTest {
    private val originalOrder =
        listOf(
            "Brush",
            "Eraser",
            "Select",
            "Fill",
            "Picker",
            "Hand",
            "MoveLayer",
            "TransformLayer",
            "Gradient",
            "Smudge",
            "LassoFill",
            "Vector",
            "Assistant",
            "LineGenerator",
        )

    @Test
    fun missingAppearanceFieldsRestoreTheDefaultWorkspace() {
        val restored = Json.decodeFromString<WorkspaceAppearance>("{}")
        assertTrue(restored.valid())
        assertEquals(ToolDockPosition.Left, restored.toolDock)
        assertEquals(InspectorPosition.Right, restored.inspectorPosition)
        assertEquals(InterfaceDensity.Standard, restored.density)
        assertEquals(1f, restored.scale)
        assertTrue(restored.showStatusBar)
        assertFalse(restored.reducedMotion)
        assertEquals(originalOrder, restored.toolOrder)
        assertEquals(emptySet(), restored.hiddenTools)
    }

    @Test
    fun aSavedWorkspaceRestoresEveryChoiceFromAnIndependentJsonFixture() {
        val fixture =
            """{"toolDock":"Top","toolOrder":["Picker","Brush","Eraser","Select","Fill","Hand","MoveLayer","TransformLayer","Gradient","Smudge","LassoFill","Vector","Assistant","LineGenerator"],"hiddenTools":["Eraser","Smudge"],"inspectorPosition":"Left","density":"Compact","scale":1.5,"showStatusBar":false,"reducedMotion":true}"""
        val restored = Json.decodeFromString<WorkspaceAppearance>(fixture)
        assertTrue(restored.valid())
        assertEquals(ToolDockPosition.Top, restored.toolDock)
        assertEquals(InspectorPosition.Left, restored.inspectorPosition)
        assertEquals(InterfaceDensity.Compact, restored.density)
        assertEquals(1.5f, restored.scale)
        assertFalse(restored.showStatusBar)
        assertTrue(restored.reducedMotion)
        assertEquals(
            originalOrder.filter { it != "Picker" }.let { listOf("Picker") + it },
            restored.toolOrder,
        )
        assertEquals(setOf("Eraser", "Smudge"), restored.hiddenTools)
        assertEquals(
            Json.parseToJsonElement(fixture),
            Json.parseToJsonElement(Json.encodeToString(restored)),
        )
    }

    @Test
    fun movingAndHidingToolsPreservesTheirOrderAndAllowsEveryToolInTheMenu() {
        val workspace = WorkspaceAppearance().moveTool(Tool.Select, -1).showTool(Tool.Brush, false)
        assertEquals(
            listOf("Brush", "Select", "Eraser") + originalOrder.drop(3),
            workspace.toolOrder,
        )
        assertEquals(listOf(Tool.Select, Tool.Eraser), workspace.visibleTools().take(2))
        assertEquals(Tool.Brush, workspace.orderedTools().first())
        assertEquals(workspace, workspace.moveTool(Tool.Brush, -1))
        assertEquals(workspace, workspace.moveTool(Tool.LineGenerator, 1))
        val hidden = Tool.entries.fold(workspace) { current, tool -> current.showTool(tool, false) }
        assertTrue(hidden.valid())
        assertTrue(hidden.visibleTools().isEmpty())
        assertEquals(workspace.orderedTools(), hidden.orderedTools())
        assertEquals(listOf(Tool.Picker), hidden.showTool(Tool.Picker, true).visibleTools())
    }

    @Test
    fun malformedToolOrdersAndUnknownHiddenToolsAreRejected() {
        val base = WorkspaceAppearance()
        listOf(
                base.copy(toolOrder = originalOrder.dropLast(1)),
                base.copy(toolOrder = originalOrder + "Brush"),
                base.copy(toolOrder = originalOrder.dropLast(1) + "Brush"),
                base.copy(toolOrder = originalOrder.dropLast(1) + "Unknown"),
                base.copy(hiddenTools = setOf("Unknown")),
            )
            .forEach { assertFalse(it.valid()) }
        assertTrue(base.copy(toolOrder = originalOrder.reversed()).valid())
    }

    @Test
    fun interfaceScaleIncludesItsActualLimitsAndRejectsNonFiniteValues() {
        val base = WorkspaceAppearance()
        listOf(0.75f, 1f, 2f).forEach { assertTrue(base.copy(scale = it).valid()) }
        listOf(0.749f, 2.001f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
            .forEach { assertFalse(base.copy(scale = it).valid()) }
    }
}
