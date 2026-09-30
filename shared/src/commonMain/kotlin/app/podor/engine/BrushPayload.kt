package app.podor.engine

import app.podor.domain.BrushSettings
import app.podor.domain.SymmetrySettings
import kotlinx.serialization.json.*

internal fun engineBrushJson(
    settings: BrushSettings,
    smudge: Boolean = false,
    eraser: Boolean = false,
    symmetry: SymmetrySettings = SymmetrySettings(),
    index: Int? = null,
): JsonObject = buildJsonObject {
    val preset = settings.preset
    put("size", settings.size)
    index?.let { put("index", it) }
    put("opacity", settings.opacity)
    put("hardness", preset.hardness)
    put("tip", preset.tip.name.lowercase())
    put("texture", preset.texture.engineName)
    put("raster", preset.raster.engineName)
    put("aspect", preset.aspect)
    put("angle", preset.angle)
    put("follow_direction", preset.followDirection)
    put("grain", preset.grain)
    put("spacing", preset.spacing)
    put("stabilization", preset.stabilization)
    put("pressure_curve", preset.pressureCurve)
    put("size_pressure", preset.sizePressure)
    put("opacity_pressure", preset.opacityPressure)
    put("mix", if (smudge) preset.mix else 0f)
    put("paper", preset.paper)
    put("smudge", smudge)
    put("eraser", eraser)
    putJsonObject("symmetry") {
        put("mode", symmetry.mode.name.lowercase())
        put("x", symmetry.x)
        put("y", symmetry.y)
    }
    putJsonArray("color") {
        add((settings.color shr 16 and 255).toInt())
        add((settings.color shr 8 and 255).toInt())
        add((settings.color and 255).toInt())
    }
}
