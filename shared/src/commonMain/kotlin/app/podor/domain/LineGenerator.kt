package app.podor.domain

import kotlinx.serialization.json.*

enum class LineGeneratorKind(val label: String, val wire: String) {
    Concentration("集中线", "concentration"),
    Speed("速度线", "speed"),
}

data class GeneratorEllipse(val rx: Float, val ry: Float) {
    fun valid() = rx.isFinite() && ry.isFinite() && rx > 0f && ry > 0f

    fun request() = buildJsonObject {
        put("rx", rx)
        put("ry", ry)
    }
}

data class LineGeneratorSettings(
    val kind: LineGeneratorKind,
    val seed: Long,
    val count: Int,
    val stroke: VectorStroke,
    val opacity: Float,
    val randomness: Float,
    val taperStart: Float,
    val taperEnd: Float,
    val center: AssistantPoint,
    val inner: GeneratorEllipse,
    val outer: GeneratorEllipse,
    val angleStart: Float,
    val angleSweep: Float,
    val origin: AssistantPoint,
    val angle: Float,
    val length: Float,
    val spacing: Float,
) {
    fun valid(maxLines: Int) =
        seed in 0..4294967295L &&
            count in 1..maxLines &&
            stroke.valid() &&
            stroke.width in 0.01f..8192f &&
            stroke.miterLimit in 1f..100f &&
            listOf(opacity, randomness, taperStart, taperEnd).all {
                it.isFinite() && it in 0f..1f
            } &&
            when (kind) {
                LineGeneratorKind.Concentration ->
                    center.valid(Float.MAX_VALUE) &&
                        inner.valid() &&
                        outer.valid() &&
                        outer.rx > inner.rx &&
                        outer.ry > inner.ry &&
                        angleStart.isFinite() &&
                        angleStart in -360f..360f &&
                        angleSweep.isFinite() &&
                        angleSweep > 0f &&
                        angleSweep <= 360f
                LineGeneratorKind.Speed ->
                    origin.valid(Float.MAX_VALUE) &&
                        angle.isFinite() &&
                        angle in -360f..360f &&
                        length.isFinite() &&
                        length > 0f &&
                        spacing.isFinite() &&
                        spacing > 0f
            }

    fun request(): JsonObject = buildJsonObject {
        put("kind", kind.wire)
        put("seed", seed)
        put("count", count)
        putJsonObject("stroke") {
            put("color", JsonArray(stroke.color.map { JsonPrimitive(it) }))
            put("width", stroke.width)
            put("cap", stroke.cap.name.lowercase())
            put("join", stroke.join.name.lowercase())
            put("miter_limit", stroke.miterLimit)
        }
        put("opacity", opacity)
        put("randomness", randomness)
        put("taper_start", taperStart)
        put("taper_end", taperEnd)
        when (kind) {
            LineGeneratorKind.Concentration -> {
                putJsonObject("center") {
                    put("x", center.x)
                    put("y", center.y)
                }
                put("inner", inner.request())
                put("outer", outer.request())
                put("angle_start", angleStart)
                put("angle_sweep", angleSweep)
            }
            LineGeneratorKind.Speed -> {
                putJsonObject("origin") {
                    put("x", origin.x)
                    put("y", origin.y)
                }
                put("angle", angle)
                put("length", length)
                put("spacing", spacing)
            }
        }
    }
}

fun defaultLineGenerator(
    document: DocumentInfo,
    brush: BrushSettings,
    kind: LineGeneratorKind,
): LineGeneratorSettings {
    val w = document.width.toFloat()
    val h = document.height.toFloat()
    val count =
        if (kind == LineGeneratorKind.Concentration) StudioDefaults.concentrationCount
        else StudioDefaults.speedLineCount
    return LineGeneratorSettings(
        kind,
        StudioDefaults.lineGeneratorSeed,
        count,
        VectorStroke(
            vectorRgba(brush.color),
            minOf(brush.size, minOf(w, h) * 0.012f).coerceAtLeast(1f),
            VectorCap.Butt,
            VectorJoin.Miter,
        ),
        StudioDefaults.lineGeneratorOpacity,
        StudioDefaults.lineGeneratorRandomness,
        0f,
        if (kind == LineGeneratorKind.Concentration) 1f else 0f,
        AssistantPoint(w / 2f, h / 2f),
        GeneratorEllipse((w * 0.18f).coerceAtLeast(4f), (h * 0.18f).coerceAtLeast(4f)),
        GeneratorEllipse((w * 0.85f).coerceAtLeast(12f), (h * 0.85f).coerceAtLeast(12f)),
        -180f,
        360f,
        AssistantPoint(w * 0.15f, h / 2f),
        0f,
        (w * 0.7f).coerceAtLeast(1f),
        (h * 0.7f / count).coerceAtLeast(1f),
    )
}
