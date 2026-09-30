package app.podor.domain

import kotlinx.serialization.Serializable

enum class PaletteFileFormat(val extension: String, val label: String) {
    Ase("ase", "Adobe ASE"),
    Gpl("gpl", "GPL"),
    JascPal("pal", "PAL"),
}

@Serializable
data class PaletteFileMetadata(val name: String = "", val columns: Int = 0) {
    fun valid(): Boolean =
        try {
            validateAseName(name)
            columns in 0..255 && name == name.trim(' ', '\t') && '\r' !in name && '\n' !in name
        } catch (_: IllegalArgumentException) {
            false
        }
}
