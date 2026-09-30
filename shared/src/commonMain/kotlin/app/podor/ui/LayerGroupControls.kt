package app.podor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import app.podor.domain.AdjustmentKind
import app.podor.domain.DocumentColorMode
import app.podor.domain.GroupIsolation
import app.podor.domain.LayerBlendMode
import app.podor.domain.LayerInfo
import app.podor.domain.LayerKind
import app.podor.presentation.StudioController
import kotlinx.serialization.json.put

@Composable
internal fun LayerGroupControls(
    controller: StudioController,
    active: LayerInfo?,
    enabled: Boolean,
    selecting: Boolean,
    selectedIds: Set<Int>,
    onSelecting: () -> Unit,
    onGrouped: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val document = controller.document
    val nodesAvailable = document.maxLayerNodes > document.layers.size
    Box {
        ToolButton(Glyph.More, "图层操作", enabled = enabled, selected = selecting) { menu = !menu }
        StudioDropdownMenu(menu, { menu = false }) {
            if (document.maxVectorObjects > 0)
                StudioDropdownMenuItem(
                    text = { Text(tr("新建矢量图层")) },
                    leadingIcon = { StudioIcon(Glyph.Vector) },
                    enabled =
                        nodesAvailable &&
                            document.drawableLayerCount < document.maxLayers &&
                            active?.effectiveLocked != true &&
                            !document.maskEditing &&
                            document.colorMode == DocumentColorMode.Rgba,
                    onClick = {
                        menu = false
                        controller.createVectorLayer()
                    },
                )
            StudioDropdownMenuItem(
                text = { Text(tr("新建图层组")) },
                leadingIcon = { StudioIcon(Glyph.Folder) },
                enabled =
                    nodesAvailable &&
                        active?.effectiveLocked != true &&
                        (active?.depth ?: 0) + (if (active?.kind == LayerKind.Group) 1 else 0) <
                            document.maxGroupDepth,
                onClick = {
                    menu = false
                    controller.createLayerGroup()
                },
            )
            listOf(AdjustmentKind.Tone, AdjustmentKind.Curves, AdjustmentKind.GradientMap)
                .forEach { kind ->
                    StudioDropdownMenuItem(
                        text = { Text("${tr("新建调整图层")} · ${tr(kind.label)}") },
                        leadingIcon = { StudioIcon(Glyph.Adjustments) },
                        enabled =
                            nodesAvailable &&
                                active?.effectiveLocked != true &&
                                !document.maskEditing,
                        onClick = {
                            menu = false
                            controller.createAdjustmentLayer(kind)
                        },
                    )
                }
            StudioDropdownMenuItem(
                text = { Text(tr(if (selecting) "结束选择图层" else "选择多个图层")) },
                leadingIcon = { StudioIcon(Glyph.Selection) },
                onClick = {
                    menu = false
                    onSelecting()
                },
            )
            if (selecting) {
                val chosen = document.layers.filter { it.id in selectedIds }
                val siblings = document.siblings(chosen.firstOrNull()?.parentId)
                val indices = chosen.map(siblings::indexOf)
                StudioDropdownMenuItem(
                    text = { Text(tr("将选中图层分组")) },
                    leadingIcon = { StudioIcon(Glyph.Folder) },
                    enabled =
                        nodesAvailable &&
                            chosen.isNotEmpty() &&
                            chosen.all {
                                it.parentId == chosen.first().parentId && !it.effectiveLocked
                            } &&
                            indices.zipWithNext().all { (a, b) -> b == a + 1 },
                    onClick = {
                        menu = false
                        controller.groupLayers(selectedIds)
                        onGrouped()
                    },
                )
            }
            if (active != null) {
                if (active.parentId != null) {
                    val parent = document.layers.first { it.id == active.parentId }
                    StudioDropdownMenuItem(
                        text = { Text(tr("移出图层组")) },
                        leadingIcon = { StudioIcon(Glyph.Up) },
                        enabled = !active.effectiveLocked,
                        onClick = {
                            menu = false
                            val siblings =
                                document.siblings(parent.parentId).filter { it.id != active.id }
                            controller.moveLayerNode(
                                active.id,
                                parent.parentId,
                                siblings.indexOf(parent) + 1,
                            )
                        },
                    )
                }
                val groups =
                    document.layers.filter {
                        it.kind == LayerKind.Group &&
                            it.id != active.id &&
                            it.id != active.parentId &&
                            active.id !in document.ancestorIds(it.id) &&
                            !it.effectiveLocked
                    }
                if (groups.isNotEmpty()) {
                    HorizontalDivider(color = StudioTheme.border)
                    groups.forEach { target ->
                        StudioDropdownMenuItem(
                            text = { Text("${tr("移入图层组")} · ${tr(target.name)}") },
                            leadingIcon = { StudioIcon(Glyph.Folder) },
                            enabled = !active.effectiveLocked,
                            onClick = {
                                menu = false
                                controller.moveLayerNode(
                                    active.id,
                                    target.id,
                                    document.siblings(target.id).size,
                                )
                            },
                        )
                    }
                }
                if (active.kind == LayerKind.Group) {
                    HorizontalDivider(color = StudioTheme.border)
                    GroupIsolation.entries.forEach { isolation ->
                        StudioDropdownMenuItem(
                            text = { Text(tr(isolation.label)) },
                            leadingIcon = { StudioIcon(Glyph.Layers) },
                            trailingIcon = {
                                if (active.isolation == isolation) StudioIcon(Glyph.Check)
                            },
                            enabled =
                                !active.effectiveLocked &&
                                    (isolation != GroupIsolation.PassThrough ||
                                        (active.opacity == 1f &&
                                            active.mask == null &&
                                            active.blend == LayerBlendMode.Normal &&
                                            !active.clipping &&
                                            document.layers.none { it.clippingBase == active.id })),
                            onClick = {
                                menu = false
                                controller.command("set_group_isolation") {
                                    put("id", active.id)
                                    put(
                                        "isolation",
                                        if (isolation == GroupIsolation.Isolated) "isolated"
                                        else "pass_through",
                                    )
                                    put("revision", document.revision)
                                }
                            },
                        )
                    }
                    StudioDropdownMenuItem(
                        text = { Text(tr("取消图层分组")) },
                        leadingIcon = { StudioIcon(Glyph.Layers) },
                        enabled =
                            !active.effectiveLocked &&
                                active.opacity == 1f &&
                                active.mask == null &&
                                active.blend == LayerBlendMode.Normal &&
                                !active.clipping &&
                                document.layers.none { it.clippingBase == active.id },
                        onClick = {
                            menu = false
                            controller.command("ungroup") {
                                put("id", active.id)
                                put("revision", document.revision)
                            }
                        },
                    )
                }
            }
        }
    }
}
