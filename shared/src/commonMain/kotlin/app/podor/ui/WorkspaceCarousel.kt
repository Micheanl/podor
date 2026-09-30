package app.podor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.podor.domain.RecentProject
import kotlin.math.*

@Composable
internal fun WorkspaceCarousel(
    projects: List<RecentProject>,
    modifier: Modifier = Modifier,
    card: @Composable (RecentProject, Boolean, () -> Unit, Dp) -> Unit,
) {
    if (projects.isEmpty()) return
    val ids = remember(projects) { projects.map { it.reference.id } }
    var selected by remember(ids) { mutableIntStateOf(0) }
    var drag by remember(ids) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val position =
        key(ids) {
            animateFloatAsState(
                selected.toFloat() - drag,
                tween(
                    if (dragging) 0 else StudioMotion.carouselMillis,
                    easing = StudioMotion.easing,
                ),
            )
        }
    fun indexAt(value: Int) = ((value % projects.size) + projects.size) % projects.size
    fun move(delta: Int) {
        selected += delta
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            Modifier.weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false
                    else
                        when (it.key) {
                            Key.DirectionLeft -> {
                                move(-1)
                                true
                            }
                            Key.DirectionRight -> {
                                move(1)
                                true
                            }
                            else -> false
                        }
                }
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            val width = minOf(StudioTheme.carouselCardWidth, maxWidth * 0.7f)
            val previewHeight =
                (maxHeight - 100.dp).coerceIn(
                    StudioTheme.carouselMinPreviewHeight,
                    StudioTheme.carouselPreviewHeight,
                )
            val step = with(LocalDensity.current) { width.toPx() * 0.7f }
            val radius = with(LocalDensity.current) { (maxWidth * 0.43f).toPx() }
            Box(
                Modifier.fillMaxSize()
                    .draggable(
                        rememberDraggableState { drag += it / step },
                        Orientation.Horizontal,
                        enabled = projects.size > 1,
                        onDragStarted = { dragging = true },
                        onDragStopped = {
                            dragging = false
                            move((-drag).roundToInt())
                            drag = 0f
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val center = position.value
                val nearest = center.roundToInt()
                val visible =
                    (-StudioTheme.carouselNeighbors..StudioTheme.carouselNeighbors)
                        .map { nearest + it }
                        .sortedBy { abs(it - center) }
                        .distinctBy { indexAt(it) }
                        .take(StudioTheme.carouselNeighbors * 2 + 1)
                for (slot in visible) {
                    val index = indexAt(slot)
                    key(projects[index].reference.id) {
                        val distance = slot - center
                        val angle = (distance * StudioTheme.carouselAngle).coerceIn(-1.5f, 1.5f)
                        Box(
                            Modifier.width(width).zIndex(-abs(distance)).graphicsLayer {
                                translationX = sin(angle) * radius
                                rotationY = -angle * 180f / PI.toFloat()
                                val depth =
                                    1f - StudioTheme.carouselDepth * abs(distance).coerceAtMost(3f)
                                scaleX = depth
                                scaleY = depth
                                alpha = (1f - abs(distance) * 0.2f).coerceIn(0.25f, 1f)
                                cameraDistance = 16f * density
                            }
                        ) {
                            card(
                                projects[index],
                                index == indexAt(selected),
                                { selected = slot },
                                previewHeight,
                            )
                        }
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.graphicsLayer { rotationZ = 90f }) {
                ToolButton(Glyph.Chevron, "上一件作品", enabled = projects.size > 1) { move(-1) }
            }
            Text(
                "${indexAt(selected) + 1} / ${projects.size}",
                color = StudioTheme.muted,
                fontSize = 12.sp,
            )
            Box(Modifier.graphicsLayer { rotationZ = -90f }) {
                ToolButton(Glyph.Chevron, "下一件作品", enabled = projects.size > 1) { move(1) }
            }
        }
    }
}
