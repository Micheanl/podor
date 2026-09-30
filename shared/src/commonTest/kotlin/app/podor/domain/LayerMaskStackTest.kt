package app.podor.domain

import kotlin.test.*
import kotlinx.serialization.json.Json

class LayerMaskStackTest {
    @Test
    fun nativeTargetsUseIndependentMaskIdsAndLegacyProjectionRemainsCompatible() {
        val doc =
            Json.decodeFromString<DocumentInfo>(
                """
                {"active":1,"activeMaskId":31,"maskEditing":true,"maxLayerMasks":16,
                                "layers":[{"id":1,"name":"Paint","visible":true,"opacity":1,
                                "mask":{"id":31,"name":"Detail","enabled":true,"linked":false},
                                "masks":[{"id":30,"name":"Shape","enabled":false,"linked":true},
                                {"id":31,"name":"Detail","enabled":true,"linked":false}]}]}
                """
                    .trimIndent()
            )
        assertEquals(31, doc.activeMaskId)
        assertEquals(16, doc.maxLayerMasks)
        assertEquals(listOf(30, 31), doc.layers.single().maskEntries.map { it.id })
        assertEquals(doc.layers.single().masks[1], doc.layers.single().mask)
        val legacy = LayerInfo(1, "Paint", true, 1f, mask = LayerMaskInfo(enabled = false))
        assertEquals(listOf(LayerMaskInfo(enabled = false)), legacy.maskEntries)
        assertEquals(1, doc.rasterLayerCount)
    }

    @Test
    fun layeredExportRequiresExplicitFlatteningOnlyWhereTheFormatCannotPreserveTheNodes() {
        val mask = LayerMaskInfo(id = 8, name = "Mask")
        val plain = LayerInfo(1, "Paint", true, 1f)
        val single = DocumentInfo(layers = listOf(plain.copy(mask = mask, masks = listOf(mask))))
        assertFalse(single.requiresBakedExport(ExportFormat.Psd))
        assertTrue(single.requiresBakedExport(ExportFormat.Ora))
        val stack =
            single.copy(
                layers =
                    listOf(single.layers.single().copy(masks = listOf(mask, mask.copy(id = 9))))
            )
        assertTrue(stack.requiresBakedExport(ExportFormat.Psd))
        assertTrue(stack.requiresBakedExport(ExportFormat.Ora))
        assertFalse(stack.requiresBakedExport(ExportFormat.Png))
        val clipping = DocumentInfo(layers = listOf(plain.copy(clipping = true)))
        assertFalse(clipping.requiresBakedExport(ExportFormat.Psd))
        assertTrue(clipping.requiresBakedExport(ExportFormat.Ora))
        val effect = DocumentInfo(layers = listOf(plain.copy(kind = LayerKind.Adjustment)))
        assertTrue(effect.requiresBakedExport(ExportFormat.Psd))
        assertTrue(effect.requiresBakedExport(ExportFormat.Ora))
    }
}
