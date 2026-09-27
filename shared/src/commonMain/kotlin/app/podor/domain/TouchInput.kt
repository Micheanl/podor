package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.atan2

data class TouchContact(val id: Int, val offset: Offset)

data class TouchInput(
    val contacts: List<TouchContact>,
    val gesturing: Boolean,
    val cancelled: Boolean = false,
    val samples: List<Offset> = emptyList(),
)

interface TouchEvent {
    val touchInput: TouchInput
}

data class TouchTransform(val center: Offset, val pan: Offset, val zoom: Float, val rotation: Float)

class TouchGesture {
    private var previous = emptyList<TouchContact>()

    fun update(contacts: List<TouchContact>): TouchTransform? {
        val ordered = contacts.sortedBy { it.id }.take(2)
        val before = previous
        previous = ordered
        if (ordered.size != 2 || before.size != 2 || ordered.map { it.id } != before.map { it.id })
            return null
        val oldVector = before[1].offset - before[0].offset
        val newVector = ordered[1].offset - ordered[0].offset
        val oldLength = oldVector.getDistance()
        val newLength = newVector.getDistance()
        val oldCenter = (before[0].offset + before[1].offset) / 2f
        val newCenter = (ordered[0].offset + ordered[1].offset) / 2f
        val separated = oldLength >= 1f && newLength >= 1f
        return TouchTransform(
            oldCenter,
            newCenter - oldCenter,
            if (separated) newLength / oldLength else 1f,
            if (separated)
                atan2(
                    oldVector.x * newVector.y - oldVector.y * newVector.x,
                    oldVector.x * newVector.x + oldVector.y * newVector.y,
                ) * (180f / PI.toFloat())
            else 0f,
        )
    }

    fun reset() {
        previous = emptyList()
    }
}
