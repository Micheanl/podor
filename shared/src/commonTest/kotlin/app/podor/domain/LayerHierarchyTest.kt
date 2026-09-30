package app.podor.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class LayerHierarchyTest {
    private fun layer(
        id: Int,
        parent: Int? = null,
        group: Boolean = false,
        closed: Boolean = false,
    ) =
        LayerInfo(
            id,
            "Layer $id",
            true,
            1f,
            kind = if (group) LayerKind.Group else LayerKind.Raster,
            parentId = parent,
            closed = closed,
        )

    private val layers =
        listOf(
            layer(1),
            layer(2, group = true),
            layer(3, 2),
            layer(4, 2, group = true),
            layer(5, 4),
            layer(6),
        )
    private val document = DocumentInfo(layers = layers, uiOrder = listOf(6, 2, 4, 5, 3, 1))

    @Test
    fun rowsPreserveRecursiveUiOrderAndOnlyHideChildrenOfClosedAncestors() {
        assertEquals(listOf(6, 2, 4, 5, 3, 1), document.layerRows().map { it.id })
        val nestedClosed =
            document.copy(layers = layers.map { if (it.id == 4) it.copy(closed = true) else it })
        assertEquals(listOf(6, 2, 4, 3, 1), nestedClosed.layerRows().map { it.id })
        val parentClosed =
            document.copy(layers = layers.map { if (it.id == 2) it.copy(closed = true) else it })
        assertEquals(listOf(6, 2, 1), parentClosed.layerRows().map { it.id })
        assertEquals(document.layers, parentClosed.layers.map { it.copy(closed = false) })
    }

    @Test
    fun siblingOrderAncestorsAndRasterCountsDoNotTreatGroupsAsPixelLayers() {
        assertEquals(listOf(1, 2, 6), document.siblings(null).map { it.id })
        assertEquals(listOf(3, 4), document.siblings(2).map { it.id })
        assertEquals(listOf(4, 2), document.ancestorIds(5))
        assertEquals(emptyList(), document.ancestorIds(1))
        assertEquals(4, document.rasterLayerCount)
    }

    @Test
    fun legacyStateUsesRasterDefaultsAndKeepsItsVisibilityAndLockState() {
        val old =
            Json.decodeFromString<DocumentInfo>(
                """{"layers":[{"id":1,"name":"Hidden","visible":false,"opacity":0.5,"locked":true}]}"""
            )
        val layer = old.layers.single()
        assertEquals(LayerKind.Raster, layer.kind)
        assertFalse(layer.effectiveVisible)
        assertTrue(layer.effectiveLocked)
        assertEquals(old.layers.asReversed(), old.layerRows())
    }

    @Test
    fun nativeGroupStateRetainsItsOrderIsolationAndInheritedProtection() {
        val state =
            Json.decodeFromString<DocumentInfo>(
                """{"maxLayerNodes":64,"maxGroupDepth":16,"uiOrder":[2,1],"layers":[{"id":1,"name":"Group","visible":true,"opacity":1,"kind":"group","parentId":null,"depth":0,"childCount":1,"isolation":"pass_through","closed":true,"effectiveVisible":true,"effectiveLocked":true},{"id":2,"name":"Child","visible":true,"opacity":1,"kind":"raster","parentId":1,"depth":1,"effectiveVisible":true,"effectiveLocked":true}]}"""
            )
        assertEquals(GroupIsolation.PassThrough, state.layers[0].isolation)
        assertEquals(16, state.maxGroupDepth)
        assertEquals(64, state.maxLayerNodes)
        assertTrue(state.layers[1].effectiveLocked)
        assertEquals(listOf(1), state.layerRows().map { it.id })
        assertEquals(state, Json.decodeFromString<DocumentInfo>(Json.encodeToString(state)))
    }
}
