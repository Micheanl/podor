package app.podor.domain

enum class ColorHarmony(val label: String, private vararg val hueOffsets: Float) {
    Complementary("互补色", 0f, 180f),
    SplitComplementary("分裂互补色", 0f, 150f, 210f),
    Analogous("邻近色", 0f, -30f, 30f),
    Triadic("三角色", 0f, 120f, 240f),
    Tetradic("四角色", 0f, 60f, 180f, 240f);

    fun colors(color: Long): List<Long> {
        require(color in 0L..0xFFFFFFFFL)
        return colors(HsvColor.fromArgb(color), (color ushr 24).toInt())
    }

    fun colors(hsv: HsvColor, alpha: Int = 255): List<Long> {
        require(hsv.hue.isFinite() && hsv.saturation.isFinite() && hsv.brightness.isFinite())
        require(hsv.saturation in 0f..1f && hsv.brightness in 0f..1f)
        require(alpha in 0..255)
        val hue = (hsv.hue % 360f + 360f) % 360f
        return hueOffsets.map { offset ->
            (hsv.copy(hue = hue + offset).toArgb() and 0xFFFFFFL) or (alpha.toLong() shl 24)
        }
    }
}
