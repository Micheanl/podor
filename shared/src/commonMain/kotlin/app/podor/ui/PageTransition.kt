package app.podor.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

@Composable
fun PageTransition(
    page: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val pages = updateTransition(page, label = "inspector pages")
    val direction = if (pages.targetState >= pages.currentState) 1f else -1f
    pages.AnimatedContent(
        modifier.clipToBounds(),
        transitionSpec = {
            fadeIn(
                    tween(
                        StudioMotion.pageMillis - StudioMotion.pageFadeMillis,
                        delayMillis = StudioMotion.pageFadeMillis,
                        easing = StudioMotion.easing,
                    )
                )
                .togetherWith(fadeOut(tween(StudioMotion.pageFadeMillis)))
                .apply { targetContentZIndex = 1f }
                .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(0) }))
        },
        contentAlignment = Alignment.TopStart,
    ) { activePage ->
        val turn =
            transition.animateFloat(
                transitionSpec = { tween(StudioMotion.pageMillis, easing = StudioMotion.easing) },
                label = "page turn",
            ) { state ->
                when (state) {
                    EnterExitState.PreEnter -> direction
                    EnterExitState.Visible -> 0f
                    EnterExitState.PostExit -> -direction
                }
            }
        val active = activePage == page
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    rotationY = -turn.value * StudioMotion.pageAngle
                    translationX = turn.value * StudioMotion.pageTravel.dp.toPx()
                    transformOrigin = TransformOrigin(if (direction > 0f) 0f else 1f, 0.5f)
                    cameraDistance = StudioMotion.pageCameraDistance.dp.toPx()
                }
                .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
                .onPreviewKeyEvent { !active }
                .pointerInput(active) {
                    if (!active)
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial)
                                .changes
                                .forEach { it.consume() }
                        }
                }
        ) {
            content(activePage)
        }
    }
}
