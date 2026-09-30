package app.podor.domain

import kotlinx.serialization.Serializable

@Serializable
enum class AseSwatchType(val code: Int) {
    Global(0),
    Spot(1),
    Process(2),
}

@Serializable
data class AseSwatch(
    val name: String,
    val color: Long,
    val type: AseSwatchType = AseSwatchType.Process,
    val path: List<String> = emptyList(),
)

@Serializable data class AseGroup(val path: List<String>, val start: Int, val end: Int)

@Serializable
data class AsePalette(val swatches: List<AseSwatch>, val groups: List<AseGroup> = emptyList()) {
    fun valid(): Boolean =
        try {
            requireValid()
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    internal fun requireValid(): List<AseGroup> {
        require(swatches.size <= StudioDefaults.maxPaletteColors) {
            "ASE 色板最多支持 ${StudioDefaults.maxPaletteColors} 色"
        }
        var bytes = 12L
        swatches.forEach {
            require(it.color in 0xFF000000L..0xFFFFFFFFL) { "ASE 色板不支持透明色" }
            validateAseName(it.name)
            validatePath(it.path)
            bytes += 28L + it.name.length * 2L
        }
        val groups = this.groups.ifEmpty { groupsFor(swatches) }
        require(groups.size <= StudioDefaults.maxPaletteGroups) { "ASE 色板分组超过限制" }
        groups.forEach {
            validatePath(it.path)
            require(it.path.isNotEmpty() && it.start in 0..it.end && it.end <= swatches.size) {
                "ASE 色板分组区间无效"
            }
            bytes += 16L + it.path.last().length * 2L
        }
        require(bytes <= StudioDefaults.maxPaletteFileBytes) { "ASE 色板文件超过大小限制" }
        walk(groups, onGroup = { _, _ -> }, onSwatch = {})
        return groups
    }

    internal fun walk(
        groups: List<AseGroup>,
        onGroup: (AseGroup, Boolean) -> Unit,
        onSwatch: (AseSwatch) -> Unit,
    ) {
        var swatchIndex = 0
        var groupIndex = 0
        fun contents(path: List<String>, end: Int) {
            while (true) {
                val group = groups.getOrNull(groupIndex)
                if (
                    group != null &&
                        group.start == swatchIndex &&
                        group.path.size == path.size + 1 &&
                        path.indices.all { group.path[it] == path[it] }
                ) {
                    require(group.end <= end) { "ASE 色板分组区间交叉" }
                    groupIndex++
                    onGroup(group, true)
                    contents(group.path, group.end)
                    onGroup(group, false)
                } else if (swatchIndex < end) {
                    val swatch = swatches[swatchIndex++]
                    require(swatch.path == path) { "ASE 色板分组与颜色路径不一致" }
                    onSwatch(swatch)
                } else {
                    break
                }
            }
        }
        contents(emptyList(), swatches.size)
        require(groupIndex == groups.size && swatchIndex == swatches.size) {
            "ASE 色板分组顺序无效"
        }
    }

    private fun groupsFor(swatches: List<AseSwatch>): List<AseGroup> {
        val groups = mutableListOf<AseGroup>()
        val open = mutableListOf<Int>()
        var path = emptyList<String>()
        for (index in 0..swatches.size) {
            val next = swatches.getOrNull(index)?.path.orEmpty()
            var common = 0
            while (common < minOf(path.size, next.size) && path[common] == next[common]) common++
            while (open.size > common) {
                val group = open.removeAt(open.lastIndex)
                groups[group] = groups[group].copy(end = index)
            }
            for (depth in common until next.size) {
                require(groups.size < StudioDefaults.maxPaletteGroups) { "ASE 色板分组超过限制" }
                open.add(groups.size)
                groups.add(AseGroup(next.take(depth + 1), index, index))
            }
            path = next
        }
        return groups
    }

    private fun validatePath(path: List<String>) {
        require(path.size <= StudioDefaults.maxPaletteGroupDepth) { "ASE 色板分组嵌套过深" }
        path.forEach(::validateAseName)
    }
}

internal fun validateAseName(name: String) {
    require(name.length <= StudioDefaults.maxPaletteNameUnits && '\u0000' !in name) {
        "ASE 色板名称无效或过长"
    }
    var index = 0
    while (index < name.length) {
        val unit = name[index++].code
        if (unit in 0xD800..0xDBFF) {
            require(index < name.length && name[index++].code in 0xDC00..0xDFFF) {
                "ASE 色板名称包含无效 UTF-16"
            }
        } else {
            require(unit !in 0xDC00..0xDFFF) { "ASE 色板名称包含无效 UTF-16" }
        }
    }
}
