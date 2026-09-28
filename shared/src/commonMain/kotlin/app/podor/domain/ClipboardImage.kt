package app.podor.domain

data class ClipboardOrigin(val width: Int, val height: Int, val left: Int, val top: Int)

data class ClipboardImage(val png: ByteArray, val origin: ClipboardOrigin? = null)

enum class ClipboardAction(val shortcut: ShortcutAction) {
    Copy(ShortcutAction.Copy),
    CopyVisible(ShortcutAction.CopyVisible),
    Cut(ShortcutAction.Cut),
    Paste(ShortcutAction.Paste),
}
