package app.podor.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.podor.domain.VectorObjectSpec
import kotlinx.serialization.json.*

class VectorEditPreview(
    val layerId: Int,
    val revision: Long,
    val selectionId: Long,
    val objectId: Int?,
    val initial: VectorObjectSpec?,
    value: VectorObjectSpec,
    original: RenderFrame,
) {
    val canonical = LayerActionPreview(original)
    var value by mutableStateOf(value)
        internal set

    var committing by mutableStateOf(false)
        internal set

    fun edit(): JsonObject = buildJsonObject {
        put("type", if (objectId == null) "add" else "set")
        objectId?.let { put("object_id", it) }
        put("object", value.request())
    }

    fun request(): JsonObject = buildJsonObject {
        put("id", layerId)
        put("revision", revision)
        put("selection_id", selectionId)
        put("mask_editing", false)
        putJsonObject("action") {
            put("kind", "vector")
            put("edit", edit())
        }
    }
}
