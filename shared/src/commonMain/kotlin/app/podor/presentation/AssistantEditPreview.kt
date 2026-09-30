package app.podor.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.podor.domain.DrawingAssistantSpec

class AssistantEditPreview(val id: Int, val revision: Long, val initial: DrawingAssistantSpec) {
    var value by mutableStateOf(initial)
        internal set

    var updating by mutableStateOf(false)
        internal set

    var committing by mutableStateOf(false)
        internal set
}
