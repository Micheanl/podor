package app.podor.domain

import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class AnimationExportCapabilities(
    val operation: Int,
    val maxFrames: Int,
    val maxSequenceFrames: Int,
    val maxPixels: Long,
    val maxPixelVisits: Long,
    val maxOutputBytes: Long,
    val maxScratchBytes: Long,
    val maxAtlasDimension: Int,
    val maxAtlasPadding: Int,
) {
    val available: Boolean
        get() =
            operation == 26 &&
                maxFrames > 0 &&
                maxSequenceFrames > 0 &&
                maxPixels > 0 &&
                maxPixelVisits > 0 &&
                maxOutputBytes > 0 &&
                maxScratchBytes > 0 &&
                maxAtlasDimension > 0 &&
                maxAtlasPadding >= 0
}

enum class AnimationExportFormat(
    val wireName: String,
    val extension: String,
    val mimeType: String,
) {
    Gif("gif", "gif", "image/gif"),
    Atlas("atlas", "zip", "application/zip"),
}

enum class GifColorPolicy(val wireName: String) {
    Exact("exact"),
    Quantize("quantize"),
}

enum class GifTimingPolicy(val wireName: String) {
    Exact("exact"),
    Round("round"),
}

sealed interface GifAlphaPolicy {
    data object Exact : GifAlphaPolicy

    data class Threshold(val cutoff: Int) : GifAlphaPolicy

    data class Matte(val color: List<Int>) : GifAlphaPolicy
}

sealed interface AnimationExportScope {
    data class All(
        val direction: AnimationDirection = AnimationDirection.Forward,
        val repeat: Int = 0,
    ) : AnimationExportScope

    data class Range(
        val fromFrame: Int,
        val toFrame: Int,
        val direction: AnimationDirection = AnimationDirection.Forward,
        val repeat: Int = 0,
    ) : AnimationExportScope

    data class Tag(val id: Int) : AnimationExportScope
}

enum class AnimationExportIssue(val label: String) {
    Unsupported("当前引擎不支持动画导出"),
    InvalidTimeline("动画帧数据无效"),
    InvalidScope("播放范围已失效，请重新选择"),
    InvalidSettings("动画导出参数无效"),
    FrameLimit("导出帧数量超出限制"),
    CanvasLimit("导出画布尺寸超出限制"),
    WorkLimit("导出范围过大，请减少帧或缩小画布"),
    AtlasLimit("图集尺寸超出限制，请减少帧或修改列数"),
    TimingPrecision("GIF 精确时长需要为 10 ms 的倍数"),
}

data class AnimationExportOptions(
    val format: AnimationExportFormat = StudioDefaults.animationExportFormat,
    val scope: AnimationExportScope = AnimationExportScope.All(),
    val colorPolicy: GifColorPolicy = StudioDefaults.animationExportColorPolicy,
    val alpha: GifAlphaPolicy =
        GifAlphaPolicy.Threshold(StudioDefaults.animationExportAlphaThreshold),
    val timing: GifTimingPolicy = StudioDefaults.animationExportTiming,
    val columns: Int = StudioDefaults.animationExportColumns,
    val padding: Int = StudioDefaults.animationExportPadding,
) {
    fun requestJson(revision: Long): JsonObject = buildJsonObject {
        put("revision", revision)
        put("format", format.wireName)
        putJsonObject("scope") {
            when (val value = scope) {
                is AnimationExportScope.All -> {
                    put("kind", "all")
                    put("direction", Json.encodeToJsonElement(value.direction))
                    put("repeat", value.repeat)
                }
                is AnimationExportScope.Range -> {
                    put("kind", "range")
                    put("from_frame", value.fromFrame)
                    put("to_frame", value.toFrame)
                    put("direction", Json.encodeToJsonElement(value.direction))
                    put("repeat", value.repeat)
                }
                is AnimationExportScope.Tag -> {
                    put("kind", "tag")
                    put("id", value.id)
                }
            }
        }
        when (format) {
            AnimationExportFormat.Gif -> {
                put("color_policy", colorPolicy.wireName)
                putJsonObject("alpha") {
                    when (val value = alpha) {
                        GifAlphaPolicy.Exact -> put("kind", "exact")
                        is GifAlphaPolicy.Threshold -> {
                            put("kind", "threshold")
                            put("cutoff", value.cutoff)
                        }
                        is GifAlphaPolicy.Matte -> {
                            put("kind", "matte")
                            putJsonArray("color") { value.color.forEach { add(it) } }
                        }
                    }
                }
                put("timing", timing.wireName)
            }
            AnimationExportFormat.Atlas -> {
                put("columns", columns)
                put("padding", padding)
            }
        }
    }

    fun validation(document: DocumentInfo): AnimationExportIssue? {
        val limits = document.animationExport
        if (limits?.available != true) return AnimationExportIssue.Unsupported
        if (document.revision < 0) return AnimationExportIssue.InvalidSettings
        val animation = document.animation
        if (
            animation == null ||
                !animation.enabled ||
                animation.frames.isEmpty() ||
                animation.frames.any { it.id <= 0 || it.durationMs !in 1..60000 } ||
                animation.frames.map { it.id }.toSet().size != animation.frames.size
        )
            return AnimationExportIssue.InvalidTimeline
        val tag =
            (scope as? AnimationExportScope.Tag)?.let { value ->
                if (value.id <= 0) return AnimationExportIssue.InvalidScope
                animation.tags.singleOrNull { it.id == value.id }
                    ?: return AnimationExportIssue.InvalidScope
            }
        val endpoints =
            when (val value = scope) {
                is AnimationExportScope.All -> null
                is AnimationExportScope.Range -> value.fromFrame to value.toFrame
                is AnimationExportScope.Tag -> tag!!.fromFrame to tag.toFrame
            }
        val frames =
            if (endpoints == null) animation.frames
            else {
                val from = animation.frames.indexOfFirst { it.id == endpoints.first }
                val to = animation.frames.indexOfFirst { it.id == endpoints.second }
                if (from < 0 || to < 0) return AnimationExportIssue.InvalidScope
                animation.frames.subList(minOf(from, to), maxOf(from, to) + 1)
            }
        val (direction, repeat) =
            when (val value = scope) {
                is AnimationExportScope.All -> value.direction to value.repeat
                is AnimationExportScope.Range -> value.direction to value.repeat
                is AnimationExportScope.Tag -> tag!!.direction to tag.repeat
            }
        if (repeat !in 0..65535) return AnimationExportIssue.InvalidSettings
        val sequenceCount =
            if (
                frames.size > 2 &&
                    direction in
                        listOf(AnimationDirection.PingPong, AnimationDirection.PingPongReverse)
            )
                frames.size.toLong() * 2 - 2
            else frames.size.toLong()
        if (frames.size > limits.maxFrames || sequenceCount > limits.maxSequenceFrames)
            return AnimationExportIssue.FrameLimit
        if (
            document.width !in 1..StudioDefaults.maxDimension ||
                document.height !in 1..StudioDefaults.maxDimension
        )
            return AnimationExportIssue.CanvasLimit
        val pixels = document.width.toLong() * document.height
        if (pixels > limits.maxPixels) return AnimationExportIssue.CanvasLimit
        return when (format) {
            AnimationExportFormat.Gif -> {
                if (
                    alpha is GifAlphaPolicy.Threshold && alpha.cutoff !in 1..255 ||
                        alpha is GifAlphaPolicy.Matte &&
                            (alpha.color.size != 3 || alpha.color.any { it !in 0..255 })
                )
                    AnimationExportIssue.InvalidSettings
                else if (timing == GifTimingPolicy.Exact && frames.any { it.durationMs % 10 != 0 })
                    AnimationExportIssue.TimingPrecision
                else if (pixels > limits.maxPixelVisits / (frames.size + sequenceCount))
                    AnimationExportIssue.WorkLimit
                else null
            }
            AnimationExportFormat.Atlas -> {
                if (columns !in 0..frames.size || padding !in 0..limits.maxAtlasPadding)
                    return AnimationExportIssue.InvalidSettings
                val cellWidth = document.width.toLong() + padding.toLong() * 2
                val cellHeight = document.height.toLong() + padding.toLong() * 2
                val ideal =
                    ceil(sqrt(frames.size * cellHeight.toDouble() / cellWidth))
                        .toInt()
                        .coerceIn(1, frames.size)
                val candidates = if (columns == 0) 1..frames.size else columns..columns
                val grid =
                    candidates
                        .mapNotNull { count ->
                            val rows = (frames.size.toLong() + count - 1) / count
                            if (
                                cellWidth > limits.maxAtlasDimension.toLong() / count ||
                                    cellHeight > limits.maxAtlasDimension.toLong() / rows
                            )
                                null
                            else {
                                val width = cellWidth * count
                                val height = cellHeight * rows
                                if (width > limits.maxPixels / height) null
                                else count to width * height
                            }
                        }
                        .minWithOrNull(
                            compareBy({ kotlin.math.abs(it.first - ideal) }, { it.first })
                        ) ?: return AnimationExportIssue.AtlasLimit
                if (
                    grid.second > limits.maxPixelVisits ||
                        pixels > (limits.maxPixelVisits - grid.second) / frames.size
                )
                    AnimationExportIssue.WorkLimit
                else null
            }
        }
    }
}
