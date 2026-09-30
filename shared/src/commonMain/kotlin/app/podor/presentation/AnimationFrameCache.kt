package app.podor.presentation

import app.podor.domain.StudioDefaults

internal class AnimationFrameCache {
    private val frames = LinkedHashMap<Int, RenderFrame>()
    private var bytes = 0L

    fun get(id: Int): RenderFrame? = frames.remove(id)?.also { frames[id] = it }

    fun put(id: Int, frame: RenderFrame) {
        val size = frame.bytes()
        require(size <= StudioDefaults.animationCacheBytes) { "动画预览超出缓存上限" }
        frames.remove(id)?.let { bytes -= it.bytes() }
        while (bytes + size > StudioDefaults.animationCacheBytes) {
            val first = frames.entries.first()
            bytes -= first.value.bytes()
            frames.remove(first.key)
        }
        frames[id] = frame
        bytes += size
    }

    fun clear() {
        frames.clear()
        bytes = 0
    }

    private fun RenderFrame.bytes() = tiles.values.sumOf { it.size.toLong() * it.size * 4 }
}
