package app.podor.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.podor.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

enum class Glyph(val resource: DrawableResource) {
    Brush(Res.drawable.ic_brush),
    Eraser(Res.drawable.ic_eraser),
    Picker(Res.drawable.ic_picker),
    Hand(Res.drawable.ic_hand),
    Undo(Res.drawable.ic_undo),
    Redo(Res.drawable.ic_redo),
    Layers(Res.drawable.ic_layers),
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
}

@Composable
fun StudioIcon(glyph: Glyph, tint: Color = StudioTheme.text, modifier: Modifier = Modifier) {
    Icon(painterResource(glyph.resource), null, modifier.size(StudioTheme.iconSize), tint)
}
