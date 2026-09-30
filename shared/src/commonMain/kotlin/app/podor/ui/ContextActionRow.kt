package app.podor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

@Composable
internal fun ContextActionRow(
    modifier: Modifier,
    floating: Boolean,
    maximumWidth: Dp = StudioTheme.transformDockWidth,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    if (floating) {
        val reveal = remember { Animatable(if (StudioMotion.reducedMotion) 1f else 0f) }
        LaunchedEffect(Unit) {
            reveal.animateTo(1f, tween(StudioMotion.panelMillis, easing = StudioMotion.easing))
        }
        Row(
            modifier
                .widthIn(max = maximumWidth)
                .graphicsLayer {
                    alpha = reveal.value
                    translationY = StudioTheme.workspaceGap.toPx() * (1f - reveal.value)
                }
                .shadow(
                    StudioTheme.floatingShadow,
                    CircleShape,
                    clip = false,
                    ambientColor = StudioTheme.floatingAmbient,
                    spotColor = StudioTheme.floatingSpot,
                )
                .clip(CircleShape)
                .background(StudioTheme.panel)
                .border(StudioTheme.hairline, StudioTheme.controlBorder, CircleShape)
                .padding(StudioTheme.selectionDockPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.selectionDockGap),
        ) {
            Row(
                Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioTheme.selectionDockGap),
                content = content,
            )
            trailing()
        }
    } else
        FlowRow(
            modifier.fillMaxWidth().background(StudioTheme.panel),
            itemVerticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.selectionDockGap),
            verticalArrangement = Arrangement.spacedBy(StudioTheme.selectionDockGap),
        ) {
            content()
            trailing()
        }
}
