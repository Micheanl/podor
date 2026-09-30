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
    fun legacyPreferencesReceiveWorkspaceDefaultsWithoutReplacingUserChoices() {
        val restored =
            Json.decodeFromString<Preferences>(
                    """{"language":"English","shortcuts":{"Brush":{"key":"B","alt":true}},"palette":[4294901760,4278190335]}"""
                )
                .withNewShortcuts()
        assertTrue(restored.valid())
        assertEquals(WorkspaceAppearance(), restored.workspaceAppearance)
        assertEquals(Language.English, restored.language)
        assertEquals(Shortcut("B", alt = true), restored.shortcut(ShortcutAction.Brush))
        assertEquals(listOf(0xFFFF0000L, 0xFF0000FFL), restored.palette)
    }

    @Test
    fun workspacePreferencesPersistTogetherWithUnrelatedSettings() {
        val restored =
            Json.decodeFromString<Preferences>(
                    """{"language":"English","workspaceAppearance":{"toolDock":"Bottom","hiddenTools":["Picker"],"inspectorPosition":"Left","density":"Comfortable","scale":1.75,"reducedMotion":true},"shortcuts":{"Brush":{"key":"G","alt":true}},"palette":[4294901760]}"""
                )
                .withNewShortcuts()
        assertTrue(restored.valid())
        val saved = Json.decodeFromString<Preferences>(Json.encodeToString(restored))
        assertEquals(ToolDockPosition.Bottom, saved.workspaceAppearance.toolDock)
        assertEquals(setOf("Picker"), saved.workspaceAppearance.hiddenTools)
        assertEquals(InspectorPosition.Left, saved.workspaceAppearance.inspectorPosition)
        assertEquals(InterfaceDensity.Comfortable, saved.workspaceAppearance.density)
        assertEquals(1.75f, saved.workspaceAppearance.scale)
        assertTrue(saved.workspaceAppearance.reducedMotion)
        assertEquals(originalOrder, saved.workspaceAppearance.toolOrder)
        assertEquals(Language.English, saved.language)
        assertEquals(Shortcut("G", alt = true), saved.shortcut(ShortcutAction.Brush))
        assertEquals(listOf(0xFFFF0000L), saved.palette)
    }

    @Test
    fun invalidWorkspaceValuesCannotMakeAnOtherwiseValidPreferenceImportValid() {
        val base = Preferences()
        assertTrue(base.valid())
        assertFalse(base.copy(workspaceAppearance = WorkspaceAppearance(scale = 2.01f)).valid())
        assertFalse(
            base
                .copy(
                    workspaceAppearance = WorkspaceAppearance(toolOrder = originalOrder.dropLast(1))
                )
                .valid()
        )
        assertFalse(
            base
                .copy(workspaceAppearance = WorkspaceAppearance(hiddenTools = setOf("Unknown")))
                .valid()
        )
        assertFalse(
            Json.decodeFromString<Preferences>("""{"workspaceAppearance":{"scale":0.5}}""").valid()
        )
    }

    @Test
    fun missingAppearanceFieldsRestoreTheDefaultWorkspace() {
        val restored = Json.decodeFromString<WorkspaceAppearance>("{}")
        assertTrue(restored.valid())
        assertEquals(ToolDockPosition.Left, restored.toolDock)
        assertEquals(InspectorPosition.Right, restored.inspectorPosition)
        assertEquals(InterfaceDensity.Standard, restored.density)
        assertEquals(1f, restored.scale)
        assertFalse(restored.reducedMotion)
        assertEquals(originalOrder, restored.toolOrder)
        assertEquals(emptySet(), restored.hiddenTools)
    }

    @Test
    fun aSavedWorkspaceRestoresEveryChoiceFromAnIndependentJsonFixture() {
        val fixture =
            """{"toolDock":"Top","toolOrder":["Picker","Brush","Eraser","Select","Fill","Hand","MoveLayer","TransformLayer","Gradient","Smudge","LassoFill","Vector","Assistant","LineGenerator"],"hiddenTools":["Eraser","Smudge"],"inspectorPosition":"Left","density":"Compact","scale":1.5,"reducedMotion":true}"""
        val restored = Json.decodeFromString<WorkspaceAppearance>(fixture)
        assertTrue(restored.valid())
        assertEquals(ToolDockPosition.Top, restored.toolDock)
        assertEquals(InspectorPosition.Left, restored.inspectorPosition)
        assertEquals(InterfaceDensity.Compact, restored.density)
        assertEquals(1.5f, restored.scale)
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
