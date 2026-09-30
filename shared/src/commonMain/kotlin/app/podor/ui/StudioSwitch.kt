package app.podor.ui

import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

@Composable
fun StudioSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Switch(checked, onCheckedChange, modifier, enabled = enabled)
    }
}
