package app.podor.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.podor.domain.LineGeneratorSettings
import kotlinx.serialization.json.*

class LineGeneratorPreview(
    val layerId: Int,
    val revision: Long,
    val selectionId: Long,
    val parentId: Int?,
    val index: Int,
    settings: LineGeneratorSettings,
    original: RenderFrame,
) {
    val canonical = LayerActionPreview(original)
    var settings by mutableStateOf(settings)
        internal set

    var error by mutableStateOf<String?>(null)
        internal set

    var committing by mutableStateOf(false)
        internal set

    var renderedAction by mutableStateOf<JsonObject?>(null)
        internal set

    val frame
        get() = canonical.frame

    val updating
        get() = renderedAction != request()

    val name
        get() = if (settings.kind.wire == "speed") "Speed lines" else "Concentration lines"

    fun request(): JsonObject = buildJsonObject {
        put("id", layerId)
        put("revision", revision)
        put("selection_id", selectionId)
        put("mask_editing", false)
        put("mask_id", JsonNull)
        putJsonObject("action") {
            put("kind", "generate_lines")
            put("name", name)
            put("parent_id", parentId)
            put("index", index)
            put("settings", settings.request())
        }
    }
}
