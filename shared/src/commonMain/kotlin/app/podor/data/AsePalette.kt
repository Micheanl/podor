package app.podor.data

import app.podor.domain.AseGroup
import app.podor.domain.AsePalette
import app.podor.domain.AseSwatch
import app.podor.domain.AseSwatchType
import app.podor.domain.StudioDefaults
import app.podor.domain.validateAseName
import kotlin.math.pow
import kotlin.math.roundToInt

object AsePaletteCodec {
    const val maxColors = StudioDefaults.maxPaletteColors
    const val maxBytes = StudioDefaults.maxPaletteFileBytes
    const val maxGroups = StudioDefaults.maxPaletteGroups
    const val maxGroupDepth = StudioDefaults.maxPaletteGroupDepth
    const val maxNameUnits = StudioDefaults.maxPaletteNameUnits

    fun decode(bytes: ByteArray): AsePalette {
        require(bytes.size <= maxBytes) { "ASE 色板文件超过大小限制" }
        val input = Reader(bytes)
        require(input.ascii(4) == "ASEF") { "不是 Adobe Swatch Exchange 色板" }
        require(input.u16() == 1 && input.u16() == 0) { "尚未支持该 ASE 版本" }
        val count = input.u32()
        require(count <= maxColors + maxGroups * 2 && count <= input.remaining / 6) {
            "ASE 色板块数量无效"
        }
        val swatches = mutableListOf<AseSwatch>()
        val groups = mutableListOf<AseGroup>()
        val open = mutableListOf<Int>()
        val path = mutableListOf<String>()
        repeat(count.toInt()) {
            val type = input.u16()
            val block = input.block()
            when (type) {
                0x0001 -> {
                    require(swatches.size < maxColors) { "ASE 色板最多支持 $maxColors 色" }
                    val name = block.name()
                    val color = readColor(block)
                    val code = block.u16()
                    val swatchType = AseSwatchType.entries.firstOrNull { it.code == code }
                    require(swatchType != null) { "尚未支持该 ASE 色板类型" }
                    swatches.add(AseSwatch(name, color, swatchType, path.toList()))
                }
                0xC001 -> {
                    require(groups.size < maxGroups && path.size < maxGroupDepth) {
                        "ASE 色板分组超过限制"
                    }
                    path.add(block.name())
                    open.add(groups.size)
                    groups.add(AseGroup(path.toList(), swatches.size, swatches.size))
                }
                0xC002 -> {
                    require(open.isNotEmpty()) { "ASE 色板分组未匹配" }
                    val index = open.removeAt(open.lastIndex)
                    groups[index] = groups[index].copy(end = swatches.size)
                    path.removeAt(path.lastIndex)
                }
                else -> throw IllegalArgumentException("尚未支持该 ASE 色板块")
            }
            require(block.remaining == 0) { "ASE 色板块长度无效" }
        }
        require(input.remaining == 0 && open.isEmpty()) { "ASE 色板数据或分组未完整结束" }
        return AsePalette(swatches.toList(), groups.toList())
    }

    fun isValid(palette: AsePalette) = palette.valid()

    fun encode(palette: AsePalette): ByteArray {
        val groups = palette.requireValid()
        val output = Writer()
        output.ascii("ASEF")
        output.u16(1)
        output.u16(0)
        output.u32(palette.swatches.size + groups.size * 2)
        palette.walk(
            groups,
            onGroup = { group, start ->
                if (start) {
                    output.block(0xC001, Writer().apply { name(group.path.last()) }.bytes())
                } else {
                    output.block(0xC002, byteArrayOf())
                }
            },
            onSwatch = { swatch ->
                val body = Writer()
                body.name(swatch.name)
                body.ascii("RGB ")
                for (shift in listOf(16, 8, 0)) {
                    body.u32(((swatch.color shr shift and 255) / 255f).toBits())
                }
                body.u16(swatch.type.code)
                output.block(0x0001, body.bytes())
            },
        )
        return output.bytes()
    }

    private fun readColor(input: Reader): Long {
        val model = input.ascii(4)
        val channels =
            when (model) {
                "RGB ",
                "LAB " -> DoubleArray(3) { input.number() }
                "CMYK" -> DoubleArray(4) { input.number() }
                "Gray" -> DoubleArray(1) { input.number() }
                else -> throw IllegalArgumentException("尚未支持该 ASE 颜色模型")
            }
        if (model == "LAB ") {
            require(
                channels[0] in 0.0..1.0 &&
                    channels[1] in -128.0..127.0 &&
                    channels[2] in -128.0..127.0
            ) {
                "ASE LAB 颜色值无效"
            }
            return labToArgb(channels[0] * 100.0, channels[1], channels[2])
        }
        require(channels.all { it in 0.0..1.0 }) { "ASE 颜色值超出范围" }
        return when (model) {
            "RGB " -> argb(channels[0], channels[1], channels[2])
            "Gray" -> argb(channels[0], channels[0], channels[0])
            else ->
                argb(
                    (1 - channels[0]) * (1 - channels[3]),
                    (1 - channels[1]) * (1 - channels[3]),
                    (1 - channels[2]) * (1 - channels[3]),
                )
        }
    }

    private fun labToArgb(lightness: Double, a: Double, b: Double): Long {
        val fy = (lightness + 16) / 116
        fun inverse(value: Double): Double {
            val cube = value * value * value
            return if (cube > 216.0 / 24389.0) cube else (116 * value - 16) / (24389.0 / 27.0)
        }
        val x50 = inverse(fy + a / 500) * (0.3457 / 0.3585)
        val y50 = inverse(fy)
        val z50 = inverse(fy - b / 200) * ((1 - 0.3457 - 0.3585) / 0.3585)
        val x = 0.955473421488075 * x50 - 0.02309845494876471 * y50 + 0.06325924320057072 * z50
        val y = -0.0283697093338637 * x50 + 1.0099953980813041 * y50 + 0.021041441191917323 * z50
        val z = 0.012314014864481998 * x50 - 0.020507649298898964 * y50 + 1.330365926242124 * z50
        fun gamma(value: Double): Double {
            val bounded = value.coerceIn(0.0, 1.0)
            return if (bounded <= 0.0031308) 12.92 * bounded
            else 1.055 * bounded.pow(1 / 2.4) - 0.055
        }
        return argb(
            gamma(12831.0 / 3959 * x - 329.0 / 214 * y - 1974.0 / 3959 * z),
            gamma(-851781.0 / 878810 * x + 1648619.0 / 878810 * y + 36519.0 / 878810 * z),
            gamma(705.0 / 12673 * x - 2585.0 / 12673 * y + 705.0 / 667 * z),
        )
    }

    private fun argb(red: Double, green: Double, blue: Double): Long {
        fun channel(value: Double) = (value * 255).roundToInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    private class Reader(
        private val bytes: ByteArray,
        private var position: Int = 0,
        private val end: Int = bytes.size,
    ) {
        val remaining
            get() = end - position

        fun u8(): Int {
            require(remaining > 0) { "ASE 色板数据被截断" }
            return bytes[position++].toInt() and 255
        }

        fun u16() = (u8() shl 8) or u8()

        fun u32() = (u16().toLong() shl 16) or u16().toLong()

        fun ascii(size: Int) = buildString { repeat(size) { append(u8().toChar()) } }

        fun number(): Double {
            val value = Float.fromBits(u32().toInt())
            require(value.isFinite()) { "ASE 颜色值不是有限数值" }
            return value.toDouble()
        }

        fun block(): Reader {
            val size = u32()
            require(size <= remaining) { "ASE 色板块长度无效或数据被截断" }
            val reader = Reader(bytes, position, position + size.toInt())
            position += size.toInt()
            return reader
        }

        fun name(): String {
            val units = u16()
            require(units in 1..maxNameUnits + 1 && units * 2 <= remaining) { "ASE 色板名称长度无效" }
            val name = buildString { repeat(units - 1) { append(u16().toChar()) } }
            require(u16() == 0) { "ASE 色板名称缺少结束符" }
            validateAseName(name)
            return name
        }
    }

    private class Writer {
        private val data = mutableListOf<Byte>()

        fun u16(value: Int) {
            append(value ushr 8)
            append(value)
        }

        fun u32(value: Int) {
            u16(value ushr 16)
            u16(value)
        }

        fun ascii(value: String) = value.forEach { append(it.code) }

        fun name(value: String) {
            u16(value.length + 1)
            value.forEach { u16(it.code) }
            u16(0)
        }

        fun block(type: Int, bytes: ByteArray) {
            require(bytes.size <= maxBytes - data.size - 6) { "ASE 色板文件超过大小限制" }
            u16(type)
            u32(bytes.size)
            bytes.forEach { append(it.toInt()) }
        }

        fun bytes() = data.toByteArray()

        private fun append(value: Int) {
            require(data.size < maxBytes) { "ASE 色板文件超过大小限制" }
            data.add(value.toByte())
        }
    }
}
