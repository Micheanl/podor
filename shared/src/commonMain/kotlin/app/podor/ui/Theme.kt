package app.podor.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.Appearance
import app.podor.domain.Language

internal val LocalPaintColor = compositionLocalOf { Color.Black }

object StudioTheme {
    var appearance by mutableStateOf(Appearance.Dark)
        internal set

    private val light
        get() = appearance == Appearance.Light

    val feedbackInk
        get() = if (light) Color.Black else Color.White

    val menuTrailRadius = 12.dp
    val quickTrailRadius = 22.dp
    val launchSwirlBack
        get() = if (light) Color(0xFFF3F4F8) else Color(0xFF220011)

    val launchSwirlFront
        get() = if (light) Color(0xFF9DAAC2) else Color(0xFF00FFFF)

    val launchSwirlPixelSize = 4.dp
    val launchWordmarkSize = 34.sp
    val launchWordmarkSpacing = 5.sp
    val launchWordmarkGap = 20.dp
    val hairline = 0.75.dp
    val buttonShape = RoundedCornerShape(50)
    val cardShape = RoundedCornerShape(16.dp)
    val menuShape = RoundedCornerShape(12.dp)
    val modalShape = RoundedCornerShape(22.dp)
    val buttonLabelSize = 13.sp
    val controlBorder
        get() = if (light) Color.Black.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.08f)

    val referenceHandle = 7.dp
    val referenceHitRadius = 12.dp
    val referenceOutline = 1.dp
    val referenceToolbarGap = 10.dp
    val repositoryLinkGap = 10.dp
    val repositoryLinkLabelSize = 12.sp
    val curveGap = 8.dp
    val curveLabelSize = 11.sp
    val curveInset = 12.dp
    val curveHitRadius = 14.dp
    val curvePointRadius = 4.dp
    val curveLine = 1.5.dp
    val curveGuideDash = 4.dp
    val curveScaleHeight = 3.dp
    const val curveHistogramHeight = 0.65f
    val curveRed
        get() = if (light) Color(0xFFAD3C52) else Color(0xFFE995A1)

    val curveGreen
        get() = if (light) Color(0xFF28784E) else Color(0xFF92C9AE)

    val curveBlue
        get() = if (light) Color(0xFF3E66B4) else Color(0xFF91B3EA)

    val brushLibraryGap = 8.dp
    const val lassoPreviewAlpha = 0.18f
    const val lassoOutlineAlpha = 0.9f
    val lassoOutlineWidth = 1.25.dp
    val lassoOptionsWidth = 220.dp
    val lassoOptionsPadding = 16.dp
    val brushEditorPreviewHeight = 56.dp
    val pixelGridColor
        get() =
            if (light) Color(0xFF525765).copy(alpha = 0.32f)
            else Color(0xFF525765).copy(alpha = 0.4f)

    val tileGridColor
        get() =
            if (light) Color(0xFF343947).copy(alpha = 0.65f)
            else Color(0xFF343947).copy(alpha = 0.7f)

    val gridLineWidth = 0.75.dp
    val tileGridLineWidth = 1.25.dp
    val gridControlsWidth = 264.dp
    val gridInputSpacing = 8.dp
    val gridInputPadding = 8.dp
    val gridControlsPadding = 16.dp
    val brushLibraryCaptionSize = 11.sp
    val brushLibraryFavoriteSize = 30.dp
    val brushLibraryFavoriteIconSize = 16.dp
    val colorSelectionWidth = 280.dp
    val gradientMapPreviewHeight = 32.dp
    val indexedPaletteGridHeight = 160.dp
    val colorSelectionPadding = 16.dp
    val colorSelectionGap = 8.dp
    val colorSelectionLabelSize = 12.sp
    val adjustmentGap = 18.dp
    val adjustmentHintSize = 11.sp
    val adjustmentStatusSize = 14.dp
    val adjustmentStatusStroke = 1.5.dp
    val adjustmentStatusGap = 8.dp
    val combinedSelectionDash = 5.dp
    val combinedSelectionHalo = 2.dp
    val combinedSelectionLine = 1.5.dp
    val combinedSelectionFill = Color(0x55E9B3C1)
    val paletteShape = cardShape
    val palettePadding = 8.dp
    val paletteGap = 8.dp
    val paletteLabelSize = 12.sp
    val paletteSwatchSize = 36.dp
    val paletteProgressWidth = 2.dp
    val symmetryControlsWidth = 280.dp
    val symmetryGuideWidth = 1.dp
    val symmetryGuideHalo = 3.dp
    val symmetryGuideDash = 7.dp
    val symmetryGuideColor = Color(0xFFE5B5C3).copy(alpha = 0.8f)
    val symmetryGuideShade = Color.Black.copy(alpha = 0.3f)
    val gradientHandleRadius = 6.dp
    val gradientDockWidth = 590.dp
    val transformHitRadius = 13.dp
    val vectorHandleRadius = 4.dp
    val assistantHandleRadius = 5.dp
    val assistantGuideWidth = 1.dp
    const val assistantGuideAlpha = 0.22f
    val lineGeneratorWidth = 340.dp
    val lineGeneratorPadding = 12.dp
    val lineGeneratorControlsHeight = 320.dp
    val vectorGuideWidth = 1.dp
    val vectorObjectListHeight = 88.dp
    val transformHandleRadius = 4.dp
    val transformRotationGap = 30.dp
    val transformOutlineWidth = 1.dp
    val transformOutlineHalo = 3.dp
    val transformOutlineShade = Color.Black.copy(alpha = 0.55f)
    val transformRotationDot = 2.dp
    val transformDockGap = 4.dp
    val transformDockWidth = 620.dp
    val transformDockPadding = 12.dp
    val clipboardShortcutSize = 10.sp
    val selectionDockPadding = 6.dp
    val selectionDockGap = 4.dp
    val selectionDockBorder = 1.dp
    const val selectionDashLengthLimit = 16_000f
    val background
        get() = if (light) Color(0xFFEEEFF2) else Color(0xFF1C1C1E)

    val panel
        get() = if (light) Color(0xFFFAFAFC) else Color(0xFF242426)

    val elevated
        get() = if (light) Color(0xFFE4E6EB) else Color(0xFF303033)

    val border
        get() = if (light) Color(0xFFCDD0D7) else Color(0xFF404044)

    val muted
        get() = if (light) Color(0xFF626670) else Color(0xFFAAAAAF)

    val accent
        get() = if (light) Color(0xFF30343D) else Color(0xFFE4E5E9)

    val selection
        get() = if (light) Color(0xFFDCDDE5) else Color(0xFF424449)

    val selectionBorder
        get() = if (light) Color(0xFF9295A2) else Color(0xFF74777E)

    val onAccent
        get() = if (light) Color(0xFFFAFAFC) else Color(0xFF202124)

    val onSelection
        get() = if (light) Color(0xFF22252C) else Color(0xFFF5F5F7)

    val text
        get() = if (light) Color(0xFF22252C) else Color(0xFFF5F5F7)

    val hoverLight = 0.055f
    val pressLight = 0.085f
    val checkerLight = Color(0xFFDBDCDF)
    val checkerDark = Color(0xFFB9BBC0)
    val paperPreview = Color.White
    val canvasGray = Color(0xFF73767D)
    val canvasCheckerSize = 12.dp
    val canvasBackgroundHintPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    val canvasBackgroundHintSize = 10.sp
    val blendBackdrop = Color(0xFF7A88BA)
    val blendSource = Color(0xFFE6AABB)
    val railWidth = 68.dp
    val animationFrameWidth = 76.dp
    val animationCompactWidth = 560.dp
    val animationLinkSize = 14.dp
    val animationPlayIndicatorHeight = 2.dp
    val animationTrackNameWidth = 100.dp
    val animationRulerHeight = 20.dp
    val animationTrackHeight = 24.dp
    val animationTrackViewportHeight = 170.dp
    val animationKeyRadius = 3.dp
    val animationFrameHeight = 58.dp
    val animationGap = 8.dp
    val animationSettingsWidth = 256.dp
    val animationSettingsPadding = 12.dp
    val animationDurationInputWidth = 48.dp
    val animationTimelinePadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    val onionPrevious = Color(0xFF5393C9)
    val onionNext = Color(0xFFD7984B)
    val inspectorWidth = 300.dp
    val viewControlsWidth = 260.dp
    val inspectorShape = RoundedCornerShape(20.dp)
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
    val canvasPreviewShape = cardShape
    val canvasCheckerCell = 7.dp
    val canvasOutlineDash = floatArrayOf(5f, 4f)
    val iconSize = 21.dp
    val layerStatusIconSize = 12.dp
    val layerBlendGap = 14.dp
    val layerBlendLabelSize = 12.sp
    val layerBlendCaptionSize = 10.sp
    val layerBlendProgressHeight = 2.dp
    val layerBlendSampleWidth = 40.dp
    val layerBlendSampleHeight = 24.dp
    val layerBlendLabelGap = 4.dp
    val layerPreviewSize = 42.dp
    val layerMaskPreviewSize = 30.dp
    val layerMaskPreviewGap = 6.dp
    val layerTargetBorder = 1.dp
    val layerMaskMenuWidth = 224.dp
    val maskStackCardWidth = 112.dp
    val maskStackPreviewHeight = 48.dp
    val maskStackGap = 6.dp
    const val layerMaskDisabledAlpha = 0.45f
    val layerRowPadding = 8.dp
    val layerPreviewInset = 10.dp
    val layerIndent = 12.dp
    val layerMaxIndent = 60.dp
    val layerDragEdge = 48.dp
    val layerDragSpeed = 480.dp
    val layerDropLineWidth = 2.dp
    val layerDragShadow = 8.dp
    val layerShape = cardShape
    const val layerDragSourceAlpha = 0.35f
    val sliderThumbSize = 18.dp
    val brushSettingsGap = 12.dp
    val brushGraphHeight = 136.dp
    val brushGraphPadding = 16.dp
    val brushGraphShape = cardShape
    val brushGraphLine = 2.dp
    val brushGraphDot = 3.dp
    val brushCaptionSize = 11.sp
    val brushLabelSize = 12.sp
    val colorWheelSize = 240.dp
    val sliderFineDistance = 600.dp
    val colorSliderHeight = 24.dp
    val quickControlsWidth = 320.dp
    val quickControlHeight = 44.dp
    val quickTrackHeight = 24.dp
    val quickThumbSize = 26.dp
    val quickShadow = 6.dp
    const val layerLiftScale = 1.025f
    val workspaceWidth = 1200.dp
    val borderTrailRadius = 10.dp
    val cardTrailRadius = 16.dp
    val borderTrailWidth = 1.5.dp
    val borderTrailLength = 220.dp
    val borderTrailLight
        get() = if (light) Color(0xFF434B61) else Color(0xFFE1E9F7)

    const val borderTrailFraction = 0.28f
    val carouselCardWidth = 300.dp
    val carouselMinPreviewHeight = 96.dp
    val carouselPreviewHeight = 260.dp
    const val carouselAngle = 0.58f
    const val carouselDepth = 0.18f
    const val carouselNeighbors = 3
    val sonarSpacing = 26.dp
    val sonarDotRadius = 1.dp
    val sonarBandWidth = 90.dp
    const val sonarBaseAlpha = 0.07f
    const val sonarWaveAlpha = 0.22f
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
    val surfaceRim
        get() = if (light) Color(0xFFB5B8C3) else Color(0xFF62616B)

    const val colorRingRadius = 0.455f
    const val colorRingWidth = 0.075f
    const val colorPlaneFraction = 0.54f
}

object StudioMotion {
    const val borderTrailMillis = 2800
    const val carouselMillis = 480
    const val sonarMillis = 4200
    const val letterSwapMillis = 480
    const val letterSwapStagger = 0.45f
    const val pressMillis = 90
    const val feedbackMillis = 160
    const val releaseMillis = 220
    const val pressScale = 0.98f
    const val cardPressScale = 0.99f
    const val sliderActiveScale = 1.08f
    const val pageMillis = 220
    const val pageFadeMillis = 80
    const val pageTravel = 6f
    const val panelMillis = 280
    const val dismissMillis = 180
    const val launchHoldMillis = 1600
    const val launchSwirlSpeed = 0.9f
    const val revealMillis = 1500
    const val dissolveTextureSize = 96
    const val dissolveSoftness = 0.12f
    val easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val exitEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

@Composable
fun PodorTheme(
    language: Language = Language.Chinese,
    appearance: Appearance? = null,
    paintColor: Color = LocalPaintColor.current,
    content: @Composable () -> Unit,
) {
    SideEffect { if (appearance != null) StudioTheme.appearance = appearance }
    val base =
        if (StudioTheme.appearance == Appearance.Light) lightColorScheme() else darkColorScheme()
    CompositionLocalProvider(LocalLanguage provides language, LocalPaintColor provides paintColor) {
        MaterialTheme(
            shapes =
                Shapes(
                    extraSmall = RoundedCornerShape(8.dp),
                    small = StudioTheme.buttonShape,
                    medium = StudioTheme.cardShape,
                    large = StudioTheme.inspectorShape,
                    extraLarge = StudioTheme.modalShape,
                ),
            colorScheme =
                base.copy(
                    primary = StudioTheme.accent,
                    onPrimary = StudioTheme.onAccent,
                    secondary = StudioTheme.accent,
                    background = StudioTheme.background,
                    surface = StudioTheme.panel,
                    surfaceVariant = StudioTheme.elevated,
                    surfaceContainerLowest = StudioTheme.background,
                    surfaceContainerLow = StudioTheme.panel,
                    surfaceContainer = StudioTheme.panel,
                    surfaceContainerHigh = StudioTheme.elevated,
                    surfaceContainerHighest = StudioTheme.elevated,
                    surfaceTint = Color.Transparent,
                    primaryContainer = StudioTheme.selection,
                    onPrimaryContainer = StudioTheme.onSelection,
                    secondaryContainer = StudioTheme.elevated,
                    onSecondaryContainer = StudioTheme.text,
                    onSurface = StudioTheme.text,
                    onSurfaceVariant = StudioTheme.muted,
                    onBackground = StudioTheme.text,
                    inverseSurface = StudioTheme.text,
                    inverseOnSurface = StudioTheme.panel,
                    outline = StudioTheme.border,
                ),
            content = content,
        )
    }
}
