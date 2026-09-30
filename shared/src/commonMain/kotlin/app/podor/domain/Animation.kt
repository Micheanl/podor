package app.podor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LayerMaskScope {
    @SerialName("cel") Cel,
    @SerialName("global") Global,
}

@Serializable
data class AnimationInfo(
    val enabled: Boolean = true,
    val activeFrameId: Int,
    val activeCelId: Int? = null,
    val maxFrames: Int,
    val maxCels: Int,
    val maxTags: Int,
    val frames: List<AnimationFrame>,
    val tags: List<AnimationTag> = emptyList(),
) {
    fun frame(id: Int) = frames.firstOrNull { it.id == id }

    fun exposure(frameId: Int, layerId: Int) =
        frame(frameId)?.cels?.firstOrNull { it.layerId == layerId }
}

@Serializable
data class AnimationFrame(
    val id: Int,
    val durationMs: Int,
    val cels: List<CelExposure> = emptyList(),
)

@Serializable data class CelExposure(val layerId: Int, val celId: Int)

@Serializable
data class AnimationTag(
    val id: Int,
    val name: String,
    val fromFrame: Int,
    val toFrame: Int,
    val direction: AnimationDirection = AnimationDirection.Forward,
    val repeat: Int = 0,
    val color: List<Int>,
)

@Serializable
enum class AnimationDirection {
    @SerialName("forward") Forward,
    @SerialName("reverse") Reverse,
    @SerialName("ping_pong") PingPong,
    @SerialName("ping_pong_reverse") PingPongReverse,
}

data class PlaybackPosition(val frameId: Int, val cycle: Long, val finished: Boolean)

class AnimationPlaybackPlan
private constructor(
    val frames: List<AnimationFrame>,
    val repeat: Int,
) {
    private val ends =
        LongArray(frames.size).also { values ->
            var end = 0L
            frames.forEachIndexed { index, frame ->
                end += frame.durationMs.toLong() * 1_000_000L
                values[index] = end
            }
        }
    val cycleNanos = ends.last()

    fun at(elapsedNanos: Long): PlaybackPosition {
        val elapsed = elapsedNanos.coerceAtLeast(0L)
        val cycle = elapsed / cycleNanos
        if (repeat != 0 && cycle >= repeat)
            return PlaybackPosition(frames.last().id, repeat.toLong() - 1, true)
        val within = elapsed % cycleNanos
        val match = ends.binarySearch(within)
        val index = if (match >= 0) match + 1 else -match - 1
        return PlaybackPosition(frames[index].id, cycle, false)
    }

    companion object {
        fun create(
            animation: AnimationInfo,
            tagId: Int? = null,
            direction: AnimationDirection = AnimationDirection.Forward,
            repeat: Int = 0,
        ): AnimationPlaybackPlan? {
            if (!animation.enabled || animation.frames.isEmpty()) return null
            val tag = tagId?.let { id -> animation.tags.firstOrNull { it.id == id } ?: return null }
            val range =
                if (tag == null) animation.frames
                else {
                    val from = animation.frames.indexOfFirst { it.id == tag.fromFrame }
                    val to = animation.frames.indexOfFirst { it.id == tag.toFrame }
                    if (from < 0 || to < 0) return null
                    animation.frames.subList(minOf(from, to), maxOf(from, to) + 1)
                }
            val count = tag?.repeat ?: repeat
            if (
                count !in 0..65535 ||
                    range.any { it.durationMs !in 1..60000 } ||
                    animation.frames.map { it.id }.toSet().size != animation.frames.size
            )
                return null
            val ordered =
                when (tag?.direction ?: direction) {
                    AnimationDirection.Forward -> range
                    AnimationDirection.Reverse -> range.asReversed()
                    AnimationDirection.PingPong -> range + range.drop(1).dropLast(1).asReversed()
                    AnimationDirection.PingPongReverse ->
                        range.asReversed() + range.drop(1).dropLast(1)
                }
            return AnimationPlaybackPlan(ordered.toList(), count)
        }
    }
}
