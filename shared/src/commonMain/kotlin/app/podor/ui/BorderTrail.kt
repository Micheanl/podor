package app.podor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Suppress("UNUSED_PARAMETER")
@Composable
fun Modifier.borderTrail(active: Boolean, radius: Dp = StudioTheme.borderTrailRadius): Modifier =
    this
