package app.podor.presentation

import app.podor.domain.DocumentInfo
import kotlinx.serialization.json.*

private val timelineCommands =
    setOf(
        "enable_animation",
        "add_frame",
        "duplicate_frame",
        "delete_frame",
        "reorder_frames",
        "set_frame_duration",
        "select_frame",
        "new_cel",
        "clear_cel",
        "link_cel",
        "unlink_cel",
        "add_frame_tag",
        "set_frame_tag",
        "delete_frame_tag",
        "state",
        "undo",
        "redo",
        "new",
    )

internal fun JsonObject.withAnimationTarget(
    document: DocumentInfo,
    includeRevision: Boolean = true,
): JsonObject {
    val animation = document.animation ?: return this
    if (this["type"]?.jsonPrimitive?.content in timelineCommands) return this
    val target = buildJsonObject {
        put("frame_id", animation.activeFrameId)
        put("cel_id", animation.activeCelId?.let(::JsonPrimitive) ?: JsonNull)
        put("target_layer_id", document.active)
        if (includeRevision) put("revision", document.revision)
    }
    return JsonObject(target + this)
}

internal fun JsonObject.rebindAnimationStroke(document: DocumentInfo): JsonObject {
    val animation = document.animation ?: return this
    require(this["frame_id"]?.jsonPrimitive?.intOrNull == animation.activeFrameId) {
        "笔画所属帧已切换"
    }
    require(this["target_layer_id"]?.jsonPrimitive?.intOrNull == document.active) {
        "笔画所属图层已切换"
    }
    return JsonObject(this - setOf("frame_id", "cel_id", "target_layer_id", "revision"))
        .withAnimationTarget(document)
}
