package app.podor.presentation

import androidx.compose.runtime.*
import app.podor.domain.*

class AdjustmentPreview(
    val layerId: Int,
    val revision: Long,
    val initialSettings: AdjustmentSettings,
    val previousTool: Tool,
    val original: RenderFrame,
    val histogram: List<List<Float>> = emptyList(),
) {
    var settings by mutableStateOf(initialSettings)
        internal set

    var renderedSettings by mutableStateOf<AdjustmentSettings?>(null)
        internal set

    var frame by mutableStateOf(original)
        internal set

    var changed by mutableStateOf(false)
        internal set

    var comparing by mutableStateOf(false)
    var inputValid by mutableStateOf(true)

    var committing by mutableStateOf(false)
        internal set

    val updating: Boolean
        get() = settings != renderedSettings
}
