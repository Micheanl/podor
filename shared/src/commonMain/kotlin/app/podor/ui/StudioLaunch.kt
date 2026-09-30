package app.podor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podor.domain.AppIdentity
import app.podor.presentation.StudioController
import app.podor.presentation.UpdateController
import app.podor.resources.Res
import app.podor.resources.brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

@Composable
fun rememberStudioStartupReady(controller: StudioController): Boolean {
    var prepared by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller.ready, controller.error) {
        if (!prepared && (controller.ready || controller.error != null)) {
            StudioTheme.appearance = controller.preferences.appearance
            prepared = true
        }
    }
    return prepared
}

@Composable
fun PodorApp(
    controller: StudioController,
    updates: UpdateController? = null,
    titleBarHeight: Dp = 0.dp,
    onTitleDragRegion: (Rect) -> Unit = {},
    titleBar: @Composable (Boolean) -> Unit = {},
) {
    var workspace by
        remember(controller) { mutableStateOf(controller.preferences.workspaceAppearance) }
    val drawingInput = controller.drawingInput
    SideEffect {
        if (!drawingInput) workspace = controller.preferences.workspaceAppearance
    }
    PodorTheme(
        controller.preferences.language,
        controller.preferences.appearance,
        androidx.compose.ui.graphics.Color(controller.brush.color),
        workspaceAppearance = workspace,
    ) {
        val integrated = titleBarHeight > 0.dp && !controller.showWorkspace
        StudioLaunch(
            controller.ready,
            if (integrated) 0.dp else titleBarHeight,
            { launching ->
                if (launching || !integrated) titleBar(launching)
            },
        ) {
            if (controller.showWorkspace) WorkspaceHome(controller, updates)
            else
                StudioApp(
                    controller,
                    updates,
                    if (integrated) ({ titleBar(false) }) else null,
                    onTitleDragRegion,
                )
        }
        UnsavedChangesDialog(controller)
    }
}

@Composable
fun StudioLaunch(
    ready: Boolean,
    titleBarHeight: Dp = 0.dp,
    titleBar: @Composable (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val reducedMotion = StudioMotion.reducedMotion
    var shownEnough by remember { mutableStateOf(reducedMotion) }
    var dismissed by remember { mutableStateOf(reducedMotion) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) dismissed = true else delay(StudioMotion.launchHoldMillis.toLong())
        shownEnough = true
    }
    val reveal = remember { Animatable(0f) }
    val texture by
        produceState<ImageBitmap?>(null, dismissed, reducedMotion) {
            value =
                if (dismissed || reducedMotion) null
                else withContext(Dispatchers.Default) { createDissolveTexture() }
        }
    LaunchedEffect(ready, shownEnough, dismissed, reducedMotion) {
        if (ready && shownEnough && !dismissed && !reducedMotion) {
            reveal.animateTo(1f, tween(StudioMotion.revealMillis, easing = LinearEasing))
            dismissed = true
        }
    }
    val visible = !dismissed && !reducedMotion
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            if (visible && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                dismissed = true
                true
            } else false
        }
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(StudioTheme.background)
                .padding(top = titleBarHeight)
                .onPreviewKeyEvent { visible }
        ) {
            content()
        }
        if (visible) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = trValue("跳过启动动画", LocalLanguage.current),
                ) {
                    dismissed = true
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val progress = ((reveal.value - 0.3f) / 0.7f).coerceIn(0f, 1f)
                            alpha = 1f - StudioMotion.easing.transform(progress)
                        }
                        .background(StudioTheme.launchSwirlBack)
                ) {
                    LaunchSwirl(Modifier.fillMaxSize())
                }
                val mask = texture
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painterResource(Res.drawable.brand),
                        AppIdentity.name,
                        Modifier.size(StudioTheme.launchIconSize)
                            .then(
                                if (mask != null) Modifier.dissolve(mask) { reveal.value }
                                else Modifier.graphicsLayer { alpha = 1f - reveal.value }
                            ),
                    )
                    Spacer(Modifier.height(StudioTheme.launchWordmarkGap))
                    Text(
                        AppIdentity.name,
                        color = StudioTheme.text,
                        fontSize = StudioTheme.launchWordmarkSize,
                        letterSpacing = StudioTheme.launchWordmarkSpacing,
                        fontWeight = FontWeight.Light,
                        modifier =
                            Modifier.graphicsLayer {
                                alpha = (1f - reveal.value / 0.3f).coerceIn(0f, 1f)
                            },
                    )
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter)) { titleBar(visible) }
    }
}
