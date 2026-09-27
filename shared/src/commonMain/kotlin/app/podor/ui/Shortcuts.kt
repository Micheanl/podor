package app.podor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.*
import app.podor.domain.Shortcut
import app.podor.domain.ShortcutAction
import app.podor.presentation.StudioController

private val keys =
    listOf(
        Key.A,
        Key.B,
        Key.C,
        Key.D,
        Key.E,
        Key.F,
        Key.G,
        Key.H,
        Key.I,
        Key.J,
        Key.K,
        Key.L,
        Key.M,
        Key.N,
        Key.O,
        Key.P,
        Key.Q,
        Key.R,
        Key.S,
        Key.T,
        Key.U,
        Key.V,
        Key.W,
        Key.X,
        Key.Y,
        Key.Z,
        Key.Zero,
        Key.One,
        Key.Two,
        Key.Three,
        Key.Four,
        Key.Five,
        Key.Six,
        Key.Seven,
        Key.Eight,
        Key.Nine,
    )
private val keyNames =
    keys.zip("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".map { it.toString() }).toMap()

fun KeyEvent.shortcut(): Shortcut? =
    keyNames[key]?.let {
        Shortcut(it, isCtrlPressed || isMetaPressed, isShiftPressed, isAltPressed)
    }

@Composable
fun StudioController.shortcutLabel(action: ShortcutAction) =
    "${tr(action.label)} · ${preferences.shortcut(action).display()}"
