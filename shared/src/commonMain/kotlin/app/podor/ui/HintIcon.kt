package app.podor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HintIcon(glyph: Glyph, label: String, modifier: Modifier = Modifier) {
    val description = tr(label)
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip(containerColor = StudioTheme.elevated, contentColor = StudioTheme.text) {
                Text(description)
            }
        },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier.size(28.dp).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            StudioIcon(glyph, StudioTheme.muted)
        }
    }
}
