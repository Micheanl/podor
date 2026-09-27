package app.podor.domain

import androidx.compose.ui.geometry.Offset

data class PenSample(val offset: Offset, val pressure: Float)

data class PenInput(
    val samples: List<PenSample>,
    val eraser: Boolean,
    val cancelled: Boolean = false,
)

interface PenEvent {
    val penInput: PenInput
}
