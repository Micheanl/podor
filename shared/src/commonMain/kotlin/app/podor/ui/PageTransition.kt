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
    val reducedMotion = StudioMotion.reducedMotion
    val pages = updateTransition(page, label = "inspector pages")
    val direction = if (pages.targetState >= pages.currentState) 1f else -1f
    pages.AnimatedContent(
        modifier.clipToBounds(),
        transitionSpec = {
            (if (reducedMotion) EnterTransition.None.togetherWith(ExitTransition.None)
                else
                    fadeIn(
                            tween(
                                StudioMotion.pageMillis - StudioMotion.pageFadeMillis,
                                delayMillis = StudioMotion.pageFadeMillis,
                                easing = StudioMotion.easing,
                            )
                        )
                        .togetherWith(fadeOut(tween(StudioMotion.pageFadeMillis))))
                .apply { targetContentZIndex = 1f }
                .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(0) }))
        },
        contentAlignment = Alignment.TopStart,
    ) { activePage ->
        val turn =
            transition.animateFloat(
                transitionSpec = {
                    tween(
                        if (reducedMotion) 0 else StudioMotion.pageMillis,
                        easing = StudioMotion.easing,
                    )
                },
                label = "page turn",
            ) { state ->
                if (reducedMotion) 0f
                else
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
                    translationX = turn.value * StudioMotion.pageTravel.dp.toPx()
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
