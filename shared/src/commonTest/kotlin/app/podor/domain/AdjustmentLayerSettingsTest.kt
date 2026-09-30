package app.podor.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class AdjustmentLayerSettingsTest {
    @Test
    fun wireParametersKeepEachAdjustmentKindDistinct() {
        val tone = AdjustmentLayerSettings(AdjustmentKind.Tone, 0.25f, -0.5f, 1f)
        assertEquals(setOf("kind", "brightness", "contrast", "saturation"), tone.request().keys)
        assertEquals("tone", tone.request().getValue("kind").jsonPrimitive.content)
        assertEquals(0.25f, tone.request().getValue("brightness").jsonPrimitive.float)
        assertEquals(
            setOf("kind", "curves"),
            AdjustmentLayerSettings(AdjustmentKind.Curves).request().keys,
        )
        assertEquals(
            setOf("kind", "gradient_map"),
            AdjustmentLayerSettings(AdjustmentKind.GradientMap).request().keys,
        )
        assertEquals(tone, AdjustmentLayerSettings.from(tone.editingSettings()))
        val gradient = AdjustmentLayerSettings(AdjustmentKind.GradientMap).request()
        assertEquals(
            2,
            gradient.getValue("gradient_map").jsonObject.getValue("stops").jsonArray.size,
        )
        val curves =
            AdjustmentLayerSettings(AdjustmentKind.Curves).request().getValue("curves").jsonObject
        assertTrue(curves.keys.containsAll(listOf("rgb", "red", "green", "blue")))
        curves.values.forEach { assertEquals(2, it.jsonObject.getValue("points").jsonArray.size) }
    }

    @Test
    fun rejectsUnsupportedTypesAndInvalidEffectParameters() {
        assertFalse(AdjustmentLayerSettings(AdjustmentKind.Blur).valid())
        assertFalse(AdjustmentLayerSettings(AdjustmentKind.LayerBlend).valid())
        assertFalse(AdjustmentLayerSettings(AdjustmentKind.Tone, Float.NaN).valid())
        assertFalse(AdjustmentLayerSettings(AdjustmentKind.Tone, contrast = 1.01f).valid())
        assertTrue(AdjustmentLayerSettings(AdjustmentKind.Curves).valid())
        assertTrue(AdjustmentLayerSettings(AdjustmentKind.GradientMap).valid())
    }

    @Test
    fun nativeStateDecodesAdjustmentNodesWithoutRasterLayerBudget() {
        val doc =
            Json.decodeFromString<DocumentInfo>(
                """
                {"layers":[{"id":1,"name":"Paint","visible":true,"opacity":1},
                                {"id":2,"name":"Tone","visible":false,"opacity":0.5,"kind":"adjustment",
                                "adjustment":{"kind":"tone","brightness":0.2,"contrast":0,"saturation":0}}],
                                "uiOrder":[2,1]}
                """
                    .trimIndent()
            )
        assertEquals(1, doc.rasterLayerCount)
        assertEquals(listOf(2, 1), doc.layerRows().map { it.id })
        assertEquals(0.2f, doc.layers[1].adjustment?.editingSettings()?.brightness)
        assertEquals(LayerKind.Adjustment, doc.layers[1].kind)
        assertFalse(doc.layers[1].effectiveVisible)
        assertFalse(ExportOptions(format = ExportFormat.Psd).bakeLayers)
        val request =
            Json.encodeToJsonElement(ExportOptions(format = ExportFormat.Psd, bakeLayers = true))
                .jsonObject
        assertTrue(request.getValue("bake_layers").jsonPrimitive.boolean)
    }
}
