package app.podor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DocumentColorMode(val label: String) {
    @SerialName("rgba") Rgba("全彩色"),
    @SerialName("indexed") Indexed("索引色"),
}

@Serializable
data class IndexedPalette(val colors: List<List<Int>>, val transparent: Int, val order: List<Int>) {
    fun valid() =
        colors.size in 2..StudioDefaults.maxIndexedColors &&
            colors.all { it.size == 4 && it.all { channel -> channel in 0..255 } } &&
            transparent in colors.indices &&
            colors[transparent][3] == 0 &&
            order.sorted() == colors.indices.toList()

    fun argb(index: Int): Long {
        val color = colors[index]
        return (color[3].toLong() shl 24) or
            (color[0].toLong() shl 16) or
            (color[1].toLong() shl 8) or
            color[2].toLong()
    }

    companion object {
        fun defaults(): IndexedPalette {
            val colors =
                listOf(listOf(0, 0, 0, 0)) +
                    StudioDefaults.palette.map {
                        listOf(
                            (it shr 16 and 255).toInt(),
                            (it shr 8 and 255).toInt(),
                            (it and 255).toInt(),
                            255,
                        )
                    }
            return IndexedPalette(colors, 0, colors.indices.toList())
        }
    }
}
