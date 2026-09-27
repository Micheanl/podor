package app.podor.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.podor.domain.Language

object StudioTheme {
    val background = Color(0xFF17181B)
    val panel = Color(0xFF222327)
    val elevated = Color(0xFF2D2E33)
    val border = Color(0xFF393A41)
    val muted = Color(0xFFA4A5B0)
    val accent = Color(0xFFE9B3C1)
    val selection = Color(0xFF78354A)
    val selectionBorder = Color(0xFFAB6279)
    val onAccent = Color(0xFF381621)
    val onSelection = Color(0xFFFFE8EE)
    val text = Color(0xFFF2F2F5)
    val surfaceLight = Color.White.copy(alpha = 0.045f)
    val surfaceShade = Color.Black.copy(alpha = 0.06f)
    val hoverLight = 0.055f
    val pressLight = 0.085f
    val checkerLight = Color(0xFFDBDCDF)
    val checkerDark = Color(0xFFB9BBC0)
    val blendBackdrop = Color(0xFF7A88BA)
    val blendSource = Color(0xFFE6AABB)
    val railWidth = 68.dp
    val inspectorWidth = 300.dp
    val controlSize = 44.dp
    val iconSize = 21.dp
    val sliderThumbSize = 18.dp
    val colorWheelSize = 240.dp
    val workspaceWidth = 1200.dp
    val projectCardWidth = 240.dp
    val projectPreviewHeight = 174.dp
    val launchHighlight = Color(0x99FFE8EE)
    val launchSpectrum =
        listOf(
            Color(0xFFD8A275),
            Color(0xFFE0CA97),
            Color(0xFF98C4A5),
            Color(0xFF79B8CD),
            Color(0xFF8C9FCF),
            Color(0xFFB18ECB),
            Color(0xFFD196B3),
        )
    val surfaceRim = Color(0xFF62616B)
    const val colorRingRadius = 0.455f
    const val colorRingWidth = 0.075f
    const val colorPlaneFraction = 0.54f
}

object StudioMotion {
    const val pressMillis = 90
    const val feedbackMillis = 160
    const val releaseMillis = 220
    const val pressScale = 0.96f
    const val cardPressScale = 0.99f
    const val sliderActiveScale = 1.18f
    const val pageMillis = 380
    const val pageFadeMillis = 80
    const val pageAngle = 24f
    const val pageTravel = 18f
    const val pageCameraDistance = 1200f
    const val iconSelectMillis = 260
    const val iconSelectAngle = -10f
    const val panelMillis = 280
    const val dismissMillis = 180
    const val launchMillis = 3400
    const val launchHoldMillis = 500
    const val revealMillis = 1500
    const val launchFlowEnd = 0.8f
    const val launchHandoffOverlap = 0.035f
    const val launchRibbonWidth = 0.28f
    const val launchFlowTravel = 1.1f
    const val dissolveTextureSize = 96
    const val dissolveSoftness = 0.12f
    val launchEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val exitEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

@Composable
fun PodorTheme(language: Language = Language.Chinese, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLanguage provides language) {
        MaterialTheme(
            shapes =
                Shapes(
                    extraSmall = RoundedCornerShape(12.dp),
                    small = RoundedCornerShape(14.dp),
                    medium = RoundedCornerShape(18.dp),
                    large = RoundedCornerShape(24.dp),
                    extraLarge = RoundedCornerShape(28.dp),
                ),
            colorScheme =
                darkColorScheme(
                    primary = StudioTheme.accent,
                    onPrimary = StudioTheme.onAccent,
                    secondary = StudioTheme.accent,
                    background = StudioTheme.background,
                    surface = StudioTheme.panel,
                    surfaceVariant = StudioTheme.elevated,
                    onSurface = StudioTheme.text,
                    onSurfaceVariant = StudioTheme.muted,
                    outline = StudioTheme.border,
                ),
            content = content,
        )
    }
}
