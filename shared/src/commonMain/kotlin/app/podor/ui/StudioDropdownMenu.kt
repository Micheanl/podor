package app.podor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StudioDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded,
        onDismissRequest,
        modifier = Modifier.borderTrail(expanded, StudioTheme.menuTrailRadius),
        shape = StudioTheme.menuShape,
        containerColor = StudioTheme.panel,
        tonalElevation = 0.dp,
        border = BorderStroke(StudioTheme.hairline, StudioTheme.controlBorder),
        content = content,
    )
}
