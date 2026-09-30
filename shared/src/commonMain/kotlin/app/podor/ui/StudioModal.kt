package app.podor.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun StudioModal(
    title: String,
    glyph: Glyph,
    onDismissRequest: () -> Unit,
    width: Dp = 440.dp,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val transition = rememberTransition(visible)
    val alpha by
        transition.animateFloat(
            transitionSpec = {
                tween(
                    if (targetState) StudioMotion.panelMillis else StudioMotion.dismissMillis,
                    easing = if (targetState) StudioMotion.easing else StudioMotion.exitEasing,
                )
            }
        ) {
            if (it) 1f else 0f
        }
    val scale by
        transition.animateFloat(
            transitionSpec = {
                tween(
                    if (targetState) StudioMotion.panelMillis else StudioMotion.dismissMillis,
                    easing = StudioMotion.easing,
                )
            }
        ) {
            if (it) 1f else 0.985f
        }
    val travel by
        transition.animateFloat(
            transitionSpec = {
                tween(
                    if (targetState) StudioMotion.panelMillis else StudioMotion.dismissMillis,
                    easing = StudioMotion.easing,
                )
            }
        ) {
            if (it) 0f else 1f
        }
    val dismiss = { visible.targetState = false }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) onDismissRequest()
    }
    Dialog(onDismissRequest = dismiss) {
        Box(Modifier.padding(StudioTheme.quickShadow)) {
            Surface(
                Modifier.width(width).heightIn(max = 720.dp).graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                    translationY = 12.dp.toPx() * travel
                },
                shape = StudioTheme.modalShape,
                color = StudioTheme.panel,
                shadowElevation = StudioTheme.modalShadow,
                border = BorderStroke(StudioTheme.hairline, StudioTheme.border),
            ) {
                Column(
                    Modifier.padding(StudioTheme.modalPadding),
                    verticalArrangement = Arrangement.spacedBy(StudioTheme.modalGap),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StudioIcon(
                            glyph,
                            StudioTheme.muted,
                            Modifier.size(StudioTheme.modalIconSize),
                        )
                        Text(
                            tr(title),
                            Modifier.weight(1f).padding(start = StudioTheme.workspaceGap),
                            fontSize = StudioTheme.modalTitleSize,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ToolButton(Glyph.Close, "关闭", onClick = dismiss)
                    }
                    content(dismiss)
                }
            }
        }
    }
}

@Composable
fun StudioAlertDialog(
    title: String,
    glyph: Glyph,
    confirmLabel: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit = {},
    enabled: Boolean = true,
    showCancel: Boolean = true,
    cancelLabel: String = "取消",
    text: @Composable ColumnScope.() -> Unit,
) {
    var confirmed by remember { mutableStateOf(false) }
    StudioModal(
        title,
        glyph,
        {
            if (confirmed) onConfirm()
            onDismissRequest()
        },
    ) { dismiss ->
        Column(
            Modifier.weight(1f, false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = text,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCancel)
                StudioTextButton(dismiss) {
                    ButtonLabel(tr(cancelLabel), color = StudioTheme.muted)
                }
            Spacer(Modifier.width(10.dp))
            ActionButton(
                confirmLabel,
                {
                    confirmed = true
                    dismiss()
                },
                enabled = enabled,
            )
        }
    }
}
