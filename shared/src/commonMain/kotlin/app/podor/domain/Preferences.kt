package app.podor.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class Language {
    Chinese,
    English,
}

@Serializable
data class BrushPack(
    val id: String,
    val name: String,
    val version: Int = 1,
    val brushes: List<BrushPreset>,
    val enabled: Boolean = true,
) {
    fun valid() =
        version == 1 &&
            id.matches(Regex("[a-zA-Z0-9._-]{1,64}")) &&
            name.isNotBlank() &&
            name.length <= 60 &&
            brushes.size in 1..64 &&
            brushes.all { it.valid() } &&
            brushes.map { it.id }.distinct().size == brushes.size

    companion object {
        const val maxFileBytes = 256 * 1024

        fun parse(bytes: ByteArray): BrushPack {
            require(bytes.size <= maxFileBytes) { "笔刷包过大" }
            return Json.decodeFromString<BrushPack>(bytes.decodeToString()).also {
                require(it.valid()) { "笔刷包参数无效" }
            }
        }
    }
}

@Serializable
data class Shortcut(
    val key: String,
    val command: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    fun valid() = key.matches(Regex("[A-Z0-9]"))

    fun display() =
        listOfNotNull(
                if (command) "Ctrl/⌘" else null,
                if (shift) "Shift" else null,
                if (alt) "Alt" else null,
                key,
            )
            .joinToString(" + ")
}

@Serializable
enum class ShortcutAction(val label: String, val default: Shortcut) {
    Brush("画笔", Shortcut("B")),
    Eraser("橡皮", Shortcut("E")),
    Picker("取色", Shortcut("I")),
    Hand("平移画布", Shortcut("H")),
    MoveLayer("移动图层", Shortcut("V")),
    Select("选区", Shortcut("M")),
    Fill("填充", Shortcut("G")),
    Fit("适合窗口", Shortcut("0")),
    Undo("撤销", Shortcut("Z", true)),
    Redo("重做", Shortcut("Z", true, true)),
    Save("保存工程", Shortcut("S", true)),
    Open("打开工程 / 图片", Shortcut("O", true)),
    Export("导出图像", Shortcut("E", true)),
    Deselect("取消选区", Shortcut("D", true)),
    New("新建画布", Shortcut("N", true)),
}

@Serializable
data class Preferences(
    val language: Language = Language.Chinese,
    val startupScreen: StartupScreen = StartupScreen.Workspace,
    val shortcuts: Map<ShortcutAction, Shortcut> = emptyMap(),
    val plugins: List<BrushPack> = emptyList(),
    val brushes: List<BrushPreset> = emptyList(),
) {
    fun shortcut(action: ShortcutAction) = shortcuts[action] ?: action.default

    fun withMoveShortcut(): Preferences {
        val action = ShortcutAction.MoveLayer
        if (action in shortcuts || action.default !in shortcuts.values) return this
        val used = ShortcutAction.entries.filter { it != action }.map(::shortcut).toSet()
        val candidates =
            sequenceOf(action.default.copy(shift = true), action.default.copy(alt = true)) +
                ('A'..'Z').asSequence().map { Shortcut(it.toString(), shift = true, alt = true) }
        return copy(shortcuts = shortcuts + (action to candidates.first { it !in used }))
    }

    fun valid() =
        plugins.size <= 16 &&
            plugins.all { it.valid() } &&
            plugins.map { it.id }.distinct().size == plugins.size &&
            brushes.size <= 64 &&
            brushes.all { it.valid() } &&
            brushes.map { it.id }.distinct().size == brushes.size &&
            brushes.none { custom -> BrushPreset.entries.any { it.id == custom.id } } &&
            shortcuts.values.all { it.valid() } &&
            ShortcutAction.entries.map(::shortcut).distinct().size == ShortcutAction.entries.size

    fun assign(action: ShortcutAction, binding: Shortcut): Preferences {
        require(binding.valid()) { "仅支持字母、数字和组合键" }
        require(ShortcutAction.entries.none { it != action && shortcut(it) == binding }) {
            "快捷键已被占用"
        }
        return copy(shortcuts = shortcuts + (action to binding))
    }
}

fun validCanvasSize(width: Int?, height: Int?) =
    width != null &&
        height != null &&
        width in 1..StudioDefaults.maxDimension &&
        height in 1..StudioDefaults.maxDimension &&
        width.toLong() * height <= StudioDefaults.maxCanvasPixels
