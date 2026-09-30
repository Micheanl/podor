package app.podor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.podor.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

enum class Glyph(val resource: DrawableResource) {
    Brush(Res.drawable.ic_brush),
    Sun(Res.drawable.ic_sun),
    Moon(Res.drawable.ic_moon),
    BrushSize(Res.drawable.ic_brushsize),
    Opacity(Res.drawable.ic_opacity),
    Stabilize(Res.drawable.ic_stabilize),
    Deselect(Res.drawable.ic_deselect),
    Eraser(Res.drawable.ic_eraser),
    Picker(Res.drawable.ic_picker),
    Hand(Res.drawable.ic_hand),
    Undo(Res.drawable.ic_undo),
    Redo(Res.drawable.ic_redo),
    Layers(Res.drawable.ic_layers),
    Mask(Res.drawable.ic_mask),
    Clipping(Res.drawable.ic_clipping),
    Link(Res.drawable.ic_link),
    Plus(Res.drawable.ic_plus),
    Minus(Res.drawable.ic_minus),
    Eye(Res.drawable.ic_eye),
    Hidden(Res.drawable.ic_hidden),
    Trash(Res.drawable.ic_trash),
    Up(Res.drawable.ic_up),
    Down(Res.drawable.ic_down),
    Fit(Res.drawable.ic_fit),
    More(Res.drawable.ic_more),
    Folder(Res.drawable.ic_folder),
    Save(Res.drawable.ic_save),
    Export(Res.drawable.ic_export),
    Settings(Res.drawable.ic_settings),
    Adjustments(Res.drawable.ic_adjustments),
    Blur(Res.drawable.ic_blur),
    Curves(Res.drawable.ic_curves),
    Close(Res.drawable.ic_close),
    Check(Res.drawable.ic_check),
    Chevron(Res.drawable.ic_chevron),
    Selection(Res.drawable.ic_selection),
    EllipseSelection(Res.drawable.ic_ellipseselection),
    Lasso(Res.drawable.ic_lasso),
    LassoFill(Res.drawable.ic_lassofill),
    PixelGrid(Res.drawable.ic_pixelgrid),
    TileGrid(Res.drawable.ic_tilegrid),
    MagicWand(Res.drawable.ic_magicwand),
    SelectionAdd(Res.drawable.ic_selectionadd),
    SelectionSubtract(Res.drawable.ic_selectionsubtract),
    SelectionIntersect(Res.drawable.ic_selectionintersect),
    SelectionInvert(Res.drawable.ic_selectioninvert),
    Fill(Res.drawable.ic_fill),
    Globe(Res.drawable.ic_globe),
    Keyboard(Res.drawable.ic_keyboard),
    Plugin(Res.drawable.ic_plugin),
    Palette(Res.drawable.ic_palette),
    Swap(Res.drawable.ic_swap),
    Copy(Res.drawable.ic_copy),
    Cut(Res.drawable.ic_cut),
    Clipboard(Res.drawable.ic_clipboard),
    Merge(Res.drawable.ic_merge),
    Home(Res.drawable.ic_home),
    Search(Res.drawable.ic_search),
    Favorite(Res.drawable.ic_favorite),
    FavoriteFilled(Res.drawable.ic_favorite_filled),
    Update(Res.drawable.ic_update),
    Sidebar(Res.drawable.ic_sidebar),
    SidebarClosed(Res.drawable.ic_sidebar_closed),
    Lock(Res.drawable.ic_lock),
    AlphaLock(Res.drawable.ic_alpha_lock),
    Rotate(Res.drawable.ic_rotate),
    Mirror(Res.drawable.ic_mirror),
    Move(Res.drawable.ic_move),
    Transform(Res.drawable.ic_transform),
    Gradient(Res.drawable.ic_gradient),
    Smudge(Res.drawable.ic_smudge),
    MirrorVertical(Res.drawable.ic_mirrorvertical),
    ImportImage(Res.drawable.ic_importimage),
    Reference(Res.drawable.ic_reference),
    Vector(Res.drawable.ic_vector),
    Rectangle(Res.drawable.ic_rectangle),
    Ellipse(Res.drawable.ic_ellipse),
    Line(Res.drawable.ic_line),
    Assistant(Res.drawable.ic_assistant),
    Animation(Res.drawable.ic_animation),
    Play(Res.drawable.ic_play),
    Pause(Res.drawable.ic_pause),
    Stop(Res.drawable.ic_stop),
    Forward(Res.drawable.ic_forward),
    Backward(Res.drawable.ic_backward),
    SwapReverse(Res.drawable.ic_swap_reverse),
    Unlink(Res.drawable.ic_unlink),
    Minimize(Res.drawable.ic_minimize),
    Maximize(Res.drawable.ic_maximize),
    Restore(Res.drawable.ic_restore),
    Aseprite(Res.drawable.format_aseprite),
    SvgLogo(Res.drawable.format_svg),
}

internal val LocalButtonIconEnabled = staticCompositionLocalOf { true }

@Composable
fun StudioIcon(
    glyph: Glyph,
    tint: Color = StudioTheme.text,
    modifier: Modifier = Modifier,
    interactionSource: InteractionSource? = LocalButtonInteraction.current,
    enabled: Boolean = LocalButtonIconEnabled.current,
    selected: Boolean = false,
) {
    if (glyph == Glyph.Aseprite || glyph == Glyph.SvgLogo) {
        Image(painterResource(glyph.resource), null, modifier.size(StudioTheme.iconSize))
        return
    }
    val fallback = remember { MutableInteractionSource() }
    val source = interactionSource ?: fallback
    val hovered = source.collectIsHoveredAsState().value
    val pressed = source.collectIsPressedAsState().value
    val interactionModifier =
        if (interactionSource == null && enabled) {
            Modifier.hoverable(fallback).observeIconPress(fallback)
        } else Modifier
    MorphIcon(
        glyph,
        tint,
        modifier.size(StudioTheme.iconSize).then(interactionModifier),
        StudioMotion.reducedMotion,
        StudioTheme.iconStrokeWidth,
        morphIconPose(enabled, StudioMotion.reducedMotion, hovered, pressed, selected),
    )
}

@Composable
internal fun FormatIcon(
    label: String,
    tint: Color = StudioTheme.text,
    modifier: Modifier = Modifier,
) {
    if (label == "SVG") StudioIcon(Glyph.SvgLogo, modifier = modifier)
    else
        Box(modifier.size(StudioTheme.iconSize), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = tint,
                fontSize = StudioTheme.canvasCaptionSize * if (label.length > 3) 0.7f else 0.9f,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
}

private fun Modifier.observeIconPress(source: MutableInteractionSource): Modifier =
    pointerInput(source) {
        var press: PressInteraction.Press? = null
        var pointerId: PointerId? = null
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (press == null) {
                        val down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                        if (down != null) {
                            val started = PressInteraction.Press(down.position)
                            pointerId = down.id
                            press = started
                            source.tryEmit(started)
                        }
                    } else {
                        val tracked = event.changes.firstOrNull { it.id == pointerId }
                        if (tracked != null && !tracked.pressed) {
                            source.tryEmit(PressInteraction.Release(press!!))
                            press = null
                            pointerId = null
                        }
                    }
                }
            }
        } finally {
            press?.let { source.tryEmit(PressInteraction.Cancel(it)) }
        }
    }
