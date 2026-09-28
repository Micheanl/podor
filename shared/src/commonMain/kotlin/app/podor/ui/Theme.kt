package app.podor.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.Language

object StudioTheme {
    val symmetryControlsWidth = 280.dp
    val symmetryGuideWidth = 1.dp
    val symmetryGuideHalo = 3.dp
    val symmetryGuideDash = 7.dp
    val symmetryGuideColor = Color(0xFFE9B3C1).copy(alpha = 0.8f)
    val symmetryGuideShade = Color.Black.copy(alpha = 0.3f)
    val gradientHandleRadius = 6.dp
    val gradientDockWidth = 590.dp
    val transformHitRadius = 13.dp
    val transformHandleRadius = 4.dp
    val transformRotationGap = 30.dp
    val transformOutlineWidth = 1.dp
    val transformOutlineHalo = 3.dp
    val transformOutlineShade = Color.Black.copy(alpha = 0.55f)
    val transformRotationDot = 2.dp
    val transformDockGap = 4.dp
    val transformDockWidth = 620.dp
    val transformDockPadding = 12.dp
    val clipboardMenuShape = RoundedCornerShape(18.dp)
    val clipboardShortcutSize = 10.sp
    val selectionDockPadding = 6.dp
    val selectionDockGap = 4.dp
    val selectionDockBorder = 1.dp
    const val selectionDashLengthLimit = 16_000f
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
    val viewControlsWidth = 260.dp
    val inspectorShape = RoundedCornerShape(24.dp)
    val inspectorMargin = 18.dp
    val moveDockPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
    val moveDockSpacing = 12.dp
    val moveDockBorderWidth = 1.dp
    val moveDockTitleSize = 11.sp
    val moveDockValueSize = 10.sp
    val controlSize = 44.dp
    val canvasSizePreviewHeight = 190.dp
    val canvasSizePreviewPadding = 22.dp
    val canvasAnchorSize = 32.dp
    val canvasAnchorGap = 4.dp
    val canvasPreviewLine = 1.5.dp
    val canvasCropShade = Color(0xFFDF798F).copy(alpha = 0.22f)
    val canvasSettingsGap = 18.dp
    val canvasFieldsGap = 12.dp
    val canvasLabelGap = 6.dp
    val canvasLabelSize = 12.sp
    val canvasCaptionSize = 11.sp
    val canvasAnchorDot = 4.dp
    val canvasAnchorIcon = 16.dp
    val canvasPreviewShape = RoundedCornerShape(18.dp)
    val canvasCheckerCell = 7.dp
    val canvasOutlineDash = floatArrayOf(5f, 4f)
    val iconSize = 21.dp
    val layerStatusIconSize = 12.dp
    val layerPreviewSize = 42.dp
    val layerRowPadding = 8.dp
    val layerPreviewInset = 10.dp
    val layerDragEdge = 48.dp
    val layerDragSpeed = 480.dp
    val layerDropLineWidth = 2.dp
    val layerDragShadow = 8.dp
    val layerShape = RoundedCornerShape(16.dp)
    const val layerDragSourceAlpha = 0.35f
    val sliderThumbSize = 18.dp
    val brushSettingsGap = 12.dp
    val brushGraphHeight = 136.dp
    val brushGraphPadding = 16.dp
    val brushGraphShape = RoundedCornerShape(18.dp)
    val brushGraphLine = 2.dp
    val brushGraphDot = 3.dp
    val brushCaptionSize = 11.sp
    val brushLabelSize = 12.sp
    val colorWheelSize = 240.dp
    val workspaceWidth = 1200.dp
    val projectCardWidth = 240.dp
    val projectPreviewHeight = 174.dp
    const val exportColumns = 3
    val launchIconSize = 88.dp
    val minimumWindowWidth = 400.dp
    val minimumWindowHeight = 600.dp
    val windowTitleHeight = 44.dp
    val windowButtonWidth = 46.dp
    val windowButtonInset = 6.dp
    val windowIconSize = 12.dp
    val windowResizeBorder = 6.dp
    val windowSpectrum =
        listOf(
            Color(0xFFDA6982),
            Color(0xFFDF9977),
            Color(0xFFD2BD7F),
            Color(0xFF78B5A2),
            Color(0xFF76A8CF),
            Color(0xFF9A86C5),
        )
    const val windowTintAlpha = 0.3f
    const val windowRimAlpha = 0.55f
    val windowRimHeight = 1.dp
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
    const val launchHoldMillis = 350
    const val revealMillis = 1500
    const val dissolveTextureSize = 96
    const val dissolveSoftness = 0.12f
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
