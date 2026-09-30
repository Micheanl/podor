package app.podor.data

import app.podor.domain.AsePalette
import app.podor.domain.AseSwatch
import app.podor.domain.AseSwatchType
import app.podor.domain.StudioDefaults
import app.podor.domain.validateAseName

enum class TextPaletteFormat {
    Gpl,
    JascPal,
}

data class TextPaletteFile(
    val palette: AsePalette,
    val format: TextPaletteFormat,
    val name: String = "",
    val columns: Int = 0,
)

object TextPaletteCodec {
    const val maxColors = StudioDefaults.maxPaletteColors
    const val maxBytes = StudioDefaults.maxPaletteFileBytes

    fun decode(bytes: ByteArray, fallbackName: String = ""): TextPaletteFile {
        require(bytes.size <= maxBytes) { "色板文件超过大小限制" }
        val input = Lines(bytes)
        return when (input.next()) {
            "GIMP Palette" -> decodeGpl(input, fallbackName)
            "JASC-PAL" -> decodeJasc(input)
            else -> throw IllegalArgumentException("不是 GPL 或 JASC PAL 色板")
        }
    }

    fun requiresFlatten(palette: AsePalette, format: TextPaletteFormat): Boolean =
        palette.groups.isNotEmpty() ||
            palette.swatches.any {
                it.path.isNotEmpty() ||
                    it.type != AseSwatchType.Process ||
                    (format == TextPaletteFormat.JascPal && it.name.isNotEmpty())
            }

    fun requiresFlatten(file: TextPaletteFile): Boolean =
        requiresFlatten(file.palette, file.format) ||
            (file.format == TextPaletteFormat.JascPal &&
                (file.name.isNotEmpty() || file.columns != 0))

    fun encode(
        palette: AsePalette,
        format: TextPaletteFormat,
        flattenMetadata: Boolean = false,
    ): ByteArray = encode(TextPaletteFile(palette, format), flattenMetadata)

    fun encode(file: TextPaletteFile, flattenMetadata: Boolean = false): ByteArray {
        file.palette.requireValid()
        validateName(file.name)
        require(file.columns in 0..255) { "GPL 色板列数必须在 0 到 255 之间" }
        require(flattenMetadata || !requiresFlatten(file)) { "该格式无法保留色板元数据，需要明确允许扁平导出" }
        val output = StringBuilder()
        val newline = if (file.format == TextPaletteFormat.Gpl) "\n" else "\r\n"
        when (file.format) {
            TextPaletteFormat.Gpl -> {
                output.append("GIMP Palette\nName: ").append(file.name)
                output.append("\nColumns: ").append(file.columns).append("\n#\n")
            }
            TextPaletteFormat.JascPal ->
                output
                    .append("JASC-PAL\r\n0100\r\n")
                    .append(file.palette.swatches.size)
                    .append(newline)
        }
        file.palette.swatches.forEach { swatch ->
            output.append((swatch.color shr 16) and 255).append(' ')
            output.append((swatch.color shr 8) and 255).append(' ')
            output.append(swatch.color and 255)
            if (file.format == TextPaletteFormat.Gpl) {
                validateName(swatch.name)
                if (swatch.name.isNotEmpty()) output.append(' ').append(swatch.name)
            }
            output.append(newline)
        }
        return output.toString().encodeToByteArray().also {
            require(it.size <= maxBytes) { "色板文件超过大小限制" }
        }
    }

    private fun decodeGpl(input: Lines, fallbackName: String): TextPaletteFile {
        var line = input.next()
        var name = fallbackName
        var columns = 0
        if (line?.startsWith("Name: ") == true) {
            name = line.substring(6).trimSpaces()
            line = input.next()
            if (line?.startsWith("Columns: ") == true) {
                columns = number(line.substring(9).trimSpaces(), 255)
                line = input.next()
            }
        }
        validateName(name)
        val swatches = mutableListOf<AseSwatch>()
        while (line != null) {
            if (line.isNotEmpty() && !line.startsWith('#')) {
                require(swatches.size < maxColors) { "色板最多支持 $maxColors 色" }
                swatches.add(color(line, named = true))
            }
            line = input.next()
        }
        return TextPaletteFile(AsePalette(swatches.toList()), TextPaletteFormat.Gpl, name, columns)
    }

    private fun decodeJasc(input: Lines): TextPaletteFile {
        require(input.next(ascii = true) == "0100") { "尚未支持该 JASC PAL 版本" }
        val count = number(input.next(ascii = true) ?: invalidLine(), maxColors)
        val swatches =
            List(count) { color(input.next(ascii = true) ?: invalidLine(), named = false) }
        require(input.next(ascii = true) == null) { "JASC PAL 颜色数量与文件内容不一致" }
        return TextPaletteFile(AsePalette(swatches), TextPaletteFormat.JascPal)
    }

    private fun color(line: String, named: Boolean): AseSwatch {
        var index = 0
        fun component(): Int {
            while (index < line.length && space(line[index])) index++
            val start = index
            var value = 0
            while (index < line.length && line[index] in '0'..'9') {
                value = value * 10 + (line[index++] - '0')
                require(value <= 255) { "色板 RGB 数值必须在 0 到 255 之间" }
            }
            require(index > start && (index == line.length || space(line[index]))) {
                "色板 RGB 数值无效"
            }
            return value
        }
        val red = component()
        val green = component()
        val blue = component()
        val name = line.substring(index).trimSpaces()
        require(named || name.isEmpty()) { "JASC PAL 颜色行只能包含三个 RGB 数值" }
        validateName(name)
        return AseSwatch(
            name,
            0xFF000000L or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong(),
        )
    }

    private fun number(text: String, maximum: Int): Int {
        require(text.isNotEmpty()) { "色板整数无效" }
        var value = 0
        text.forEach {
            require(it in '0'..'9') { "色板整数无效" }
            value = value * 10 + (it - '0')
            require(value <= maximum) { "色板整数超过限制" }
        }
        return value
    }

    private fun validateName(name: String) {
        validateAseName(name)
        require(name == name.trimSpaces() && '\r' !in name && '\n' !in name) {
            "文本色板名称必须是单行且首尾没有空白"
        }
    }

    private fun String.trimSpaces(): String = trim(::space)

    private fun space(value: Char): Boolean = value == ' ' || value == '\t'

    private fun invalidLine(): Nothing = throw IllegalArgumentException("色板文件未完整结束")

    private class Lines(private val bytes: ByteArray) {
        private var offset = 0

        fun next(ascii: Boolean = false): String? {
            if (offset == bytes.size) return null
            val start = offset
            while (offset < bytes.size && bytes[offset] != 10.toByte()) offset++
            var end = offset
            if (offset < bytes.size && end > start && bytes[end - 1] == 13.toByte()) end--
            for (index in start until end) {
                require(bytes[index] != 0.toByte() && bytes[index] != 13.toByte()) { "色板文本包含无效字符" }
                require(!ascii || bytes[index] >= 0) { "JASC PAL 必须使用 ASCII 文本" }
            }
            if (offset < bytes.size) offset++
            return try {
                bytes.decodeToString(start, end, throwOnInvalidSequence = true)
            } catch (error: Exception) {
                throw IllegalArgumentException("色板文本不是有效 UTF-8", error)
            }
        }
    }
}
