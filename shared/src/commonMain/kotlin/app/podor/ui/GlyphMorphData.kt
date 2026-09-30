package app.podor.ui

import kotlin.math.PI
import kotlin.math.sin

internal val glyphMorphPaths: Map<Glyph, List<String>> =
    mapOf(
        Glyph.Brush to
            listOf(
                "m9.06 11.9 8.07-8.06a2.85 2.85 0 1 1 4.03 4.03l-8.06 8.08",
                "M7.07 14.94c-1.66 0-3 1.35-3 3.02 0 1.33-2.5 1.52-2 2.02 1.08 1.1 2.49 2.02 4 2.02 2.2 0 4-1.8 4-4.04a3.01 3.01 0 0 0-3-3.02z",
            ),
        Glyph.Sun to
            listOf(
                "M12,8A4,4 0,1 1,12,16A4,4 0,1 1,12,8M12,2L12,4M12,20L12,22M2,12L4,12M20,12L22,12M4.9,4.9L6.3,6.3M17.7,17.7L19.1,19.1M4.9,19.1L6.3,17.7M17.7,6.3L19.1,4.9"
            ),
        Glyph.Moon to listOf("M20.5,14.2A9,9 0,0 1,9.8,3.5A9,9 0,1 0,20.5,14.2Z"),
        Glyph.BrushSize to
            listOf("M12,5A7,7 0,1 1,12,19A7,7 0,1 1,12,5M3,3L6,3M3,3L3,6M21,18L21,21L18,21"),
        Glyph.Opacity to
            listOf("M12,3A9,9 0,1 1,12,21A9,9 0,1 1,12,3M12,3L12,21M15,6L15,18M18,9L18,15"),
        Glyph.Stabilize to listOf("M3,16C7,2 11,22 16,10C18,6 20,6 21,8M3,21L21,21"),
        Glyph.Deselect to
            listOf(
                "M3,8L3,3L8,3M16,3L21,3L21,8M21,16L21,21L16,21M8,21L3,21L3,16M8,8L16,16M16,8L8,16"
            ),
        Glyph.Eraser to
            listOf(
                "m7 21-4.3-4.3c-1-1-1-2.5 0-3.4l9.6-9.6c1-1 2.5-1 3.4 0l5.6 5.6c1 1 1 2.5 0 3.4L13 21",
                "M22 21H7",
                "m5 11 9 9",
            ),
        Glyph.Picker to
            listOf(
                "m2 22 1-1h3l9-9",
                "M3 21v-3l9-9",
                "m15 6 3.4-3.4a2.1 2.1 0 1 1 3 3L18 9l.4.4a2.1 2.1 0 1 1-3 3l-3.8-3.8a2.1 2.1 0 1 1 3-3l.4.4Z",
            ),
        Glyph.Hand to
            listOf(
                "M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2",
                "M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2",
                "M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8",
                "M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15",
            ),
        Glyph.Undo to
            listOf(
                "M9 14 4 9l5-5",
                "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11",
            ),
        Glyph.Redo to
            listOf(
                "m15 14 5-5-5-5",
                "M20 9H9.5A5.5 5.5 0 0 0 4 14.5A5.5 5.5 0 0 0 9.5 20H13",
            ),
        Glyph.Layers to
            listOf(
                "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z",
                "M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12",
                "M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17",
            ),
        Glyph.Mask to
            listOf(
                "M4 5H20A1 1 0 0 1 21 6V18A1 1 0 0 1 20 19H4A1 1 0 0 1 3 18V6A1 1 0 0 1 4 5Z M16 12A4 4 0 1 1 8 12A4 4 0 1 1 16 12Z"
            ),
        Glyph.Clipping to listOf("M6 4V11A2 2 0 0 0 8 13H17 M13 9L17 13L13 17 M5 20H19"),
        Glyph.Link to listOf("M9 7H7A5 5 0 0 0 7 17H9 M15 7H17A5 5 0 0 1 17 17H15 M8 12H16"),
        Glyph.Plus to
            listOf(
                "M5 12h14",
                "M12 5v14",
            ),
        Glyph.Minus to listOf("M5 12h14"),
        Glyph.Eye to
            listOf(
                "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0",
                "M 9,12 a 3,3 0 1,0 6,0 a 3,3 0 1,0 -6,0",
            ),
        Glyph.Hidden to
            listOf(
                "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49",
                "M14.084 14.158a3 3 0 0 1-4.242-4.242",
                "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143",
                "m2 2 20 20",
            ),
        Glyph.Trash to
            listOf(
                "M3 6h18",
                "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6",
                "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2",
                "M 10,11 L 10,17",
                "M 14,11 L 14,17",
            ),
        Glyph.Up to
            listOf(
                "m5 12 7-7 7 7",
                "M12 19V5",
            ),
        Glyph.Down to
            listOf(
                "M12 5v14",
                "m19 12-7 7-7-7",
            ),
        Glyph.Fit to
            listOf(
                "M3 7V5a2 2 0 0 1 2-2h2",
                "M17 3h2a2 2 0 0 1 2 2v2",
                "M21 17v2a2 2 0 0 1-2 2h-2",
                "M7 21H5a2 2 0 0 1-2-2v-2",
            ),
        Glyph.More to
            listOf(
                "M 11,12 a 1,1 0 1,0 2,0 a 1,1 0 1,0 -2,0",
                "M 18,12 a 1,1 0 1,0 2,0 a 1,1 0 1,0 -2,0",
                "M 4,12 a 1,1 0 1,0 2,0 a 1,1 0 1,0 -2,0",
            ),
        Glyph.Folder to
            listOf(
                "m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2"
            ),
        Glyph.Save to
            listOf(
                "M15.2 3a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
                "M17 21v-7a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v7",
                "M7 3v4a1 1 0 0 0 1 1h7",
            ),
        Glyph.Export to
            listOf(
                "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8",
                "M 16 6 12 2 8 6",
                "M 12,2 L 12,15",
            ),
        Glyph.Settings to
            listOf(
                "M20 7h-9",
                "M14 17H5",
                "M 14,17 a 3,3 0 1,0 6,0 a 3,3 0 1,0 -6,0",
                "M 4,7 a 3,3 0 1,0 6,0 a 3,3 0 1,0 -6,0",
            ),
        Glyph.Adjustments to
            listOf(
                "M 21,4 L 14,4",
                "M 10,4 L 3,4",
                "M 21,12 L 12,12",
                "M 8,12 L 3,12",
                "M 21,20 L 16,20",
                "M 12,20 L 3,20",
                "M 14,2 L 14,6",
                "M 8,10 L 8,14",
                "M 16,18 L 16,22",
            ),
        Glyph.Curves to listOf("M3,3 L3,21 L21,21 M6,17 C14,17 9,6 21,4"),
        Glyph.Close to
            listOf(
                "M18 6 6 18",
                "m6 6 12 12",
            ),
        Glyph.Check to listOf("M20 6 9 17l-5-5"),
        Glyph.Chevron to listOf("m6 9 6 6 6-6"),
        Glyph.Selection to
            listOf(
                "M5 3a2 2 0 0 0-2 2",
                "M19 3a2 2 0 0 1 2 2",
                "M21 19a2 2 0 0 1-2 2",
                "M5 21a2 2 0 0 1-2-2",
                "M9 3h1",
                "M9 21h1",
                "M14 3h1",
                "M14 21h1",
                "M3 9v1",
                "M21 9v1",
                "M3 14v1",
                "M21 14v1",
            ),
        Glyph.EllipseSelection to
            listOf(
                "M10.1 2.182a10 10 0 0 1 3.8 0 M13.9 21.818a10 10 0 0 1-3.8 0 M17.609 3.721a10 10 0 0 1 2.69 2.7 M2.182 13.9a10 10 0 0 1 0-3.8 M20.279 17.609a10 10 0 0 1-2.7 2.69 M21.818 10.1a10 10 0 0 1 0 3.8 M3.721 6.391a10 10 0 0 1 2.7-2.69 M6.391 20.279a10 10 0 0 1-2.69-2.7"
            ),
        Glyph.Lasso to
            listOf(
                "M7 22a5 5 0 0 1-2-4 M3.3 14A6.8 6.8 0 0 1 2 10c0-4.4 4.5-8 10-8s10 3.6 10 8-4.5 8-10 8a12 12 0 0 1-5-1 M5 18a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"
            ),
        Glyph.PixelGrid to
            listOf(
                "M5 3H19A2 2 0 0 1 21 5V19A2 2 0 0 1 19 21H5A2 2 0 0 1 3 19V5A2 2 0 0 1 5 3Z M9 3V21 M15 3V21 M3 9H21 M3 15H21"
            ),
        Glyph.TileGrid to
            listOf(
                "M4 3H9A1 1 0 0 1 10 4V9A1 1 0 0 1 9 10H4A1 1 0 0 1 3 9V4A1 1 0 0 1 4 3Z M15 3H20A1 1 0 0 1 21 4V9A1 1 0 0 1 20 10H15A1 1 0 0 1 14 9V4A1 1 0 0 1 15 3Z M4 14H9A1 1 0 0 1 10 15V20A1 1 0 0 1 9 21H4A1 1 0 0 1 3 20V15A1 1 0 0 1 4 14Z M15 14H20A1 1 0 0 1 21 15V20A1 1 0 0 1 20 21H15A1 1 0 0 1 14 20V15A1 1 0 0 1 15 14Z"
            ),
        Glyph.MagicWand to
            listOf(
                "M4 20 16 8 19 11 7 23Z M13 11 16 14 M6 3V7 M4 5H8 M18 1V5 M16 3H20 M21 16V20 M19 18H23"
            ),
        Glyph.SelectionAdd to
            listOf(
                "M5,3H12Q14,3 14,5V12Q14,14 12,14H5Q3,14 3,12V5Q3,3 5,3ZM10,14V19Q10,21 12,21H19Q21,21 21,19V12Q21,10 19,10H14M14,16H18M16,14V18"
            ),
        Glyph.SelectionSubtract to
            listOf(
                "M5,3H12Q14,3 14,5V10H12Q10,10 10,12V14H5Q3,14 3,12V5Q3,3 5,3ZM10,17V19Q10,21 12,21H19Q21,21 21,19V12Q21,10 19,10H17M14,16H18"
            ),
        Glyph.SelectionIntersect to
            listOf(
                "M3,7V5Q3,3 5,3H7M11,3H12Q14,3 14,5V7M3,11V12Q3,14 5,14H7M17,10H19Q21,10 21,12V14M21,18V19Q21,21 19,21H17M13,21H12Q10,21 10,19V17M10,10H14V14H10Z"
            ),
        Glyph.SelectionInvert to
            listOf(
                "M5,3H19Q21,3 21,5V19Q21,21 19,21H5Q3,21 3,19V5Q3,3 5,3ZM8,8H11M15,8H16V11M16,15V16H13M9,16H8V13M3,8L8,3M3,14L8,9M9,21L14,16M15,21L21,15"
            ),
        Glyph.Fill to
            listOf(
                "m19 11-8-8-8.6 8.6a2 2 0 0 0 0 2.8l5.2 5.2c.8.8 2 .8 2.8 0L19 11Z",
                "m5 2 5 5",
                "M2 13h15",
                "M22 20a2 2 0 1 1-4 0c0-1.6 1.7-2.4 2-4 .3 1.6 2 2.4 2 4Z",
            ),
        Glyph.Globe to
            listOf(
                "M 2,12 a 10,10 0 1,0 20,0 a 10,10 0 1,0 -20,0",
                "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
                "M2 12h20",
            ),
        Glyph.Keyboard to
            listOf(
                "M10 8h.01",
                "M12 12h.01",
                "M14 8h.01",
                "M16 12h.01",
                "M18 8h.01",
                "M6 8h.01",
                "M7 16h10",
                "M8 12h.01",
                "M 4,4 H 20 Q 22,4 22,6 V 18 Q 22,20 20,20 H 4 Q 2,20 2,18 V 6 Q 2,4 4,4 Z",
            ),
        Glyph.Plugin to
            listOf(
                "M15.39 4.39a1 1 0 0 0 1.68-.474 2.5 2.5 0 1 1 3.014 3.015 1 1 0 0 0-.474 1.68l1.683 1.682a2.414 2.414 0 0 1 0 3.414L19.61 15.39a1 1 0 0 1-1.68-.474 2.5 2.5 0 1 0-3.014 3.015 1 1 0 0 1 .474 1.68l-1.683 1.682a2.414 2.414 0 0 1-3.414 0L8.61 19.61a1 1 0 0 0-1.68.474 2.5 2.5 0 1 1-3.014-3.015 1 1 0 0 0 .474-1.68l-1.683-1.682a2.414 2.414 0 0 1 0-3.414L4.39 8.61a1 1 0 0 1 1.68.474 2.5 2.5 0 1 0 3.014-3.015 1 1 0 0 1-.474-1.68l1.683-1.682a2.414 2.414 0 0 1 3.414 0z"
            ),
        Glyph.Palette to
            listOf(
                "M 13,6.5 a 0.5,0.5 0 1,0 1,0 a 0.5,0.5 0 1,0 -1,0",
                "M 17,10.5 a 0.5,0.5 0 1,0 1,0 a 0.5,0.5 0 1,0 -1,0",
                "M 8,7.5 a 0.5,0.5 0 1,0 1,0 a 0.5,0.5 0 1,0 -1,0",
                "M 6,12.5 a 0.5,0.5 0 1,0 1,0 a 0.5,0.5 0 1,0 -1,0",
                "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z",
            ),
        Glyph.Swap to
            listOf(
                "m16 3 4 4-4 4",
                "M20 7H4",
                "m8 21-4-4 4-4",
                "M4 17h16",
            ),
        Glyph.Copy to
            listOf(
                "M 10,8 H 20 Q 22,8 22,10 V 20 Q 22,22 20,22 H 10 Q 8,22 8,20 V 10 Q 8,8 10,8 Z",
                "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
            ),
        Glyph.Cut to
            listOf(
                "M 3,6 a 3,3 0 1,0 6,0 a 3,3 0 1,0 -6,0",
                "M8.12 8.12 12 12",
                "M20 4 8.12 15.88",
                "M 3,18 a 3,3 0 1,0 6,0 a 3,3 0 1,0 -6,0",
                "M14.8 14.8 20 20",
            ),
        Glyph.Clipboard to
            listOf(
                "M 9,2 H 15 Q 16,2 16,3 V 5 Q 16,6 15,6 H 9 Q 8,6 8,5 V 3 Q 8,2 9,2 Z",
                "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2",
            ),
        Glyph.Merge to
            listOf(
                "m16.02 12 5.48 3.13a1 1 0 0 1 0 1.74L13 21.74a2 2 0 0 1-2 0l-8.5-4.87a1 1 0 0 1 0-1.74L7.98 12",
                "M13 13.74a2 2 0 0 1-2 0L2.5 8.87a1 1 0 0 1 0-1.74L11 2.26a2 2 0 0 1 2 0l8.5 4.87a1 1 0 0 1 0 1.74Z",
            ),
        Glyph.Home to
            listOf(
                "M 4,3 H 9 Q 10,3 10,4 V 9 Q 10,10 9,10 H 4 Q 3,10 3,9 V 4 Q 3,3 4,3 Z",
                "M 15,3 H 20 Q 21,3 21,4 V 9 Q 21,10 20,10 H 15 Q 14,10 14,9 V 4 Q 14,3 15,3 Z",
                "M 15,14 H 20 Q 21,14 21,15 V 20 Q 21,21 20,21 H 15 Q 14,21 14,20 V 15 Q 14,14 15,14 Z",
                "M 4,14 H 9 Q 10,14 10,15 V 20 Q 10,21 9,21 H 4 Q 3,21 3,20 V 15 Q 3,14 4,14 Z",
            ),
        Glyph.Search to
            listOf(
                "M 3,11 a 8,8 0 1,0 16,0 a 8,8 0 1,0 -16,0",
                "m21 21-4.3-4.3",
            ),
        Glyph.Favorite to
            listOf(
                "M12 3 14.8 8.7 21 9.6 16.5 14 17.6 20.2 12 17.3 6.4 20.2 7.5 14 3 9.6 9.2 8.7Z"
            ),
        Glyph.Update to listOf("M12,3a9,9 0,1 0,9 9M12,16V3M7.5,7.5L12,3l4.5,4.5"),
        Glyph.Sidebar to
            listOf(
                "M5 4H19A2 2 0 0 1 21 6V18A2 2 0 0 1 19 20H5A2 2 0 0 1 3 18V6A2 2 0 0 1 5 4Z M15 4V20 M8 9L11 12L8 15"
            ),
        Glyph.SidebarClosed to
            listOf(
                "M5 4H19A2 2 0 0 1 21 6V18A2 2 0 0 1 19 20H5A2 2 0 0 1 3 18V6A2 2 0 0 1 5 4Z M15 4V20 M11 9L8 12L11 15"
            ),
        Glyph.Lock to
            listOf(
                "M7 10V7A5 5 0 0 1 17 7V10 M6 10H18A2 2 0 0 1 20 12V20A2 2 0 0 1 18 22H6A2 2 0 0 1 4 20V12A2 2 0 0 1 6 10Z M12 15V17"
            ),
        Glyph.Rotate to
            listOf(
                "M20 7A9 9 0 0 0 5 5L3 7 M3 3V7H7 M4 17A9 9 0 0 0 19 19L21 17 M17 17H21V21 M12 7L17 12L12 17L7 12Z"
            ),
        Glyph.Mirror to listOf("M12 2V5 M12 8V11 M12 14V17 M12 20V22 M8 5L2 19H8Z M16 5L22 19H16Z"),
        Glyph.Move to
            listOf(
                "M12 3V21 M3 12H21 M9 6L12 3L15 6 M9 18L12 21L15 18 M6 9L3 12L6 15 M18 9L21 12L18 15"
            ),
        Glyph.Transform to
            listOf(
                "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
                "M14 15H9v-5",
                "M16 3h5v5",
                "M21 3 9 15",
            ),
        Glyph.Gradient to
            listOf(
                "M2,9 a7,7 0 1,0 14,0 a7,7 0 1,0 -14,0",
                "M8,15 a7,7 0 1,0 14,0 a7,7 0 1,0 -14,0",
            ),
        Glyph.Smudge to
            listOf(
                "M22 14a8 8 0 0 1-8 8 M18 11v-1a2 2 0 0 0-2-2a2 2 0 0 0-2 2 M14 10V9a2 2 0 0 0-2-2a2 2 0 0 0-2 2v1 M10 9.5V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v10 M18 11a2 2 0 1 1 4 0v3a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"
            ),
        Glyph.MirrorVertical to
            listOf(
                "m17 3-5 5-5-5h10",
                "m17 21-5-5-5 5h10",
                "M4 12H2",
                "M10 12H8",
                "M16 12h-2",
                "M22 12h-2",
            ),
        Glyph.ImportImage to
            listOf(
                "M16 5h6 M19 2v6 M21 11.5V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7.5 m8.5 12-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
                "M7,9 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0",
            ),
        Glyph.Reference to
            listOf(
                "M8 4h11a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2 M3 8v12a2 2 0 0 0 2 2h11 M7 16l4-4 3 3 3-5 4 6 M9.5 7.5h0.01"
            ),
        Glyph.Vector to
            listOf(
                "M5 17C5 5 19 19 19 7M5 17L5 5M19 7L19 19M3 15h4v4H3zM17 5h4v4h-4zM3 3h4v4H3zM17 17h4v4h-4z"
            ),
        Glyph.Rectangle to listOf("M4 5h16v14H4z"),
        Glyph.Ellipse to listOf("M21 12a9 7 0 1 1-18 0a9 7 0 1 1 18 0"),
        Glyph.Line to listOf("M5 19L19 5M3 17h4v4H3zM17 3h4v4h-4z"),
        Glyph.Assistant to
            listOf("M12,3 L3,21 M12,3 L21,21 M7,14 L17,14 M12,2 L12,6 M3,21 L8,19 M21,21 L16,19"),
        Glyph.Animation to
            listOf(
                "M5 3H19A2 2 0 0 1 21 5V19A2 2 0 0 1 19 21H5A2 2 0 0 1 3 19V5A2 2 0 0 1 5 3 M7 3V21 M17 3V21 M3 8H7 M3 16H7 M17 8H21 M17 16H21 M10 9L14 12L10 15Z"
            ),
        Glyph.Play to listOf("M7 4L20 12L7 20Z"),
        Glyph.Pause to listOf("M7 5V19 M17 5V19"),
        Glyph.Stop to listOf("M6 6H18V18H6Z"),
        Glyph.Forward to listOf("M4 12H20M14 6L20 12L14 18"),
        Glyph.Backward to listOf("M20 12H4M10 6L4 12L10 18"),
        Glyph.SwapReverse to listOf("M20 7H4M8 3L4 7L8 11M4 17H20M16 13L20 17L16 21"),
        Glyph.Unlink to listOf("M8 7H7A5 5 0 0 0 7 17H9 M16 7H17A5 5 0 0 1 17 17H15 M4 4L20 20"),
        Glyph.AlphaLock to
            listOf(
                "M5 3H19A2 2 0 0 1 21 5V19A2 2 0 0 1 19 21H5A2 2 0 0 1 3 19V5A2 2 0 0 1 5 3Z M12 3V21 M3 12H21"
            ),
        Glyph.Blur to
            listOf(
                "M12,3A9,9 0,1 1,12,21A9,9 0,1 1,12,3 M12,6.5A5.5,5.5 0,1 1,12,17.5A5.5,5.5 0,1 1,12,6.5 M12,10A2,2 0,1 1,12,14A2,2 0,1 1,12,10"
            ),
        Glyph.FavoriteFilled to
            listOf(
                "M12 3 14.8 8.7 21 9.6 16.5 14 17.6 20.2 12 17.3 6.4 20.2 7.5 14 3 9.6 9.2 8.7Z M9 11.5L11 13.5L15 9.5"
            ),
        Glyph.LassoFill to
            listOf(
                "M20.5 3.5c-7-1-16.5 1.8-17 10.4-.2 3.8 2.4 6.6 6.2 6.6 8.4-.4 11.7-10 10.8-17Z M3.5 20.5 15 9"
            ),
        Glyph.Minimize to listOf("M3 12H21"),
        Glyph.Maximize to listOf("M4 4H20V20H4Z"),
        Glyph.Restore to listOf("M8 3H21V16 M3 8H16V21H3Z"),
    )

internal enum class MorphIconPose {
    Rest,
    Hover,
    Pressed,
    Selected,
}

internal fun morphIconPose(
    enabled: Boolean,
    reducedMotion: Boolean,
    hovered: Boolean,
    pressed: Boolean,
    selected: Boolean,
): MorphIconPose =
    when {
        !enabled || reducedMotion -> MorphIconPose.Rest
        pressed -> MorphIconPose.Pressed
        hovered -> MorphIconPose.Hover
        selected -> MorphIconPose.Selected
        else -> MorphIconPose.Rest
    }

internal fun glyphInteractionContours(
    glyph: Glyph,
    source: List<MorphContour>,
    pose: MorphIconPose,
): List<MorphContour> {
    val amount =
        when (pose) {
            MorphIconPose.Rest -> return source
            MorphIconPose.Hover -> 1.0
            MorphIconPose.Pressed -> -0.65
            MorphIconPose.Selected -> 0.55
        }
    return source.mapIndexed { contourIndex, contour ->
        val points = contour.points.copyOf()
        for (i in points.indices step 2) {
            val x = points[i]
            val y = points[i + 1]
            val nx = (x - 12) / 10
            val ny = (y - 12) / 10
            val archX = (1 - nx * nx).coerceAtLeast(0.0)
            val archY = (1 - ny * ny).coerceAtLeast(0.0)
            val t = i.toDouble() / (points.size - 2)
            val (dx, dy) =
                when (glyph) {
                    Glyph.Aseprite,
                    Glyph.SvgLogo -> 0.0 to 0.0
                    Glyph.Brush ->
                        if (contourIndex == 0) 0.0 to 0.0 else 0.9 * archY to -0.8 * archX
                    Glyph.Sun ->
                        if (contourIndex == 0) 0.5 * nx to -0.25 * ny else -0.8 * ny to 0.8 * nx
                    Glyph.Moon -> 0.7 * archY * (1 + nx) to -0.45 * archX
                    Glyph.BrushSize -> if (contourIndex == 0) 0.9 * nx to -0.45 * ny else 0.0 to 0.0
                    Glyph.Opacity ->
                        if (contourIndex == 0) 0.0 to 0.0 else -0.7 * contourIndex to 0.0
                    Glyph.Stabilize ->
                        if (contourIndex == 0) 0.0 to -1.2 * sin(t * PI * 2) else 0.0 to 0.0
                    Glyph.Deselect ->
                        if (contourIndex < 4) 0.7 * nx to 0.3 * ny else 0.0 to 0.7 * sin(t * PI)
                    Glyph.Eraser -> if (contourIndex == 1) 0.0 to -0.9 else 0.7 * ny to 0.0
                    Glyph.Picker -> if (contourIndex == 2) 0.0 to -0.9 * archX else 0.45 * ny to 0.0
                    Glyph.Hand -> -0.7 * archY to 0.9 * (1 - ny).coerceAtLeast(0.0) * archX
                    Glyph.Undo ->
                        if (contourIndex == 0) -0.8 to 0.0 else -0.8 * (1 - t) to -0.7 * sin(t * PI)
                    Glyph.Redo ->
                        if (contourIndex == 0) 0.8 to 0.0 else 0.8 * (1 - t) to -0.7 * sin(t * PI)
                    Glyph.Layers -> 0.0 to (contourIndex - 1) * 0.8
                    Glyph.Mask -> if (contourIndex == 0) 0.0 to 0.0 else 0.8 * nx to -0.35 * ny
                    Glyph.Clipping ->
                        if (contourIndex == 2) 0.0 to 0.0 else 0.7 * archY to 0.45 * archX
                    Glyph.Link ->
                        if (contourIndex == 0) 0.7 to 0.0
                        else if (contourIndex == 1) -0.7 to 0.0 else 0.0 to 0.6 * archX
                    Glyph.Unlink ->
                        if (contourIndex == 0) -0.6 to 0.0
                        else if (contourIndex == 1) 0.6 to 0.0 else 0.0 to 0.5 * archX
                    Glyph.Plus ->
                        if (contourIndex == 0) 0.55 * nx to -0.65 * archX else 0.0 to -0.7 * ny
                    Glyph.Minus,
                    Glyph.Minimize -> 0.5 * nx to -0.75 * archX
                    Glyph.Eye -> if (contourIndex == 0) 0.0 to 0.7 * ny else 0.7 to 0.0
                    Glyph.Hidden -> if (contourIndex == 3) 0.0 to 0.0 else 0.65 * archY to -0.5 * ny
                    Glyph.Trash ->
                        when (contourIndex) {
                            0,
                            2 -> 0.8 * ny to -0.8 - 0.5 * nx
                            3,
                            4 -> 0.0 to 0.7
                            else -> 0.0 to 0.0
                        }
                    Glyph.Up -> 0.0 to -0.9 * (1 - ny) / 2
                    Glyph.Down -> 0.0 to 0.9 * (1 + ny) / 2
                    Glyph.Fit -> 0.8 * nx to 0.35 * ny
                    Glyph.More -> 0.0 to if (contourIndex == 0) -1.0 else 0.5
                    Glyph.Folder -> 0.65 * archY to -1.1 * ((y - 9) / 5).coerceIn(0.0, 1.0) * archX
                    Glyph.Save ->
                        if (contourIndex == 0) 0.0 to 0.0
                        else if (contourIndex == 1) 0.0 to -0.7 else 0.0 to 0.8
                    Glyph.Export ->
                        if (contourIndex == 0) 0.0 to 0.0 else 0.0 to -0.9 * (1 - (y - 2) / 18)
                    Glyph.Settings ->
                        when (contourIndex) {
                            0 -> 0.9 * (1 - t) to 0.0
                            1 -> -0.9 * (1 - t) to 0.0
                            2 -> -0.9 to 0.0
                            else -> 0.9 to 0.0
                        }
                    Glyph.Adjustments ->
                        when (contourIndex) {
                            0 -> 1.1 * t to 0.0
                            1 -> 1.1 * (1 - t) to 0.0
                            2 -> -0.8 * t to 0.0
                            3 -> -0.8 * (1 - t) to 0.0
                            4 -> 0.7 * t to 0.0
                            5 -> 0.7 * (1 - t) to 0.0
                            6 -> 1.1 to 0.0
                            7 -> -0.8 to 0.0
                            else -> 0.7 to 0.0
                        }
                    Glyph.Blur -> if (contourIndex == 0) 0.0 to 0.0 else 0.65 * nx to -0.65 * ny
                    Glyph.Curves ->
                        if (contourIndex == 0) 0.0 to 0.0
                        else -0.9 * sin(t * PI) to -1.1 * sin(t * PI)
                    Glyph.Close -> 0.0 to (if (contourIndex == 0) 0.65 else -0.65) * sin(t * PI)
                    Glyph.Check -> 0.65 * archY to -0.9 * archX
                    Glyph.Chevron -> 0.7 * nx to -0.9 * archX
                    Glyph.Selection,
                    Glyph.EllipseSelection -> 0.8 * nx to 0.35 * ny
                    Glyph.Lasso,
                    Glyph.LassoFill -> 0.8 * archY * ny to -0.75 * archX
                    Glyph.PixelGrid,
                    Glyph.AlphaLock ->
                        if (contourIndex == 0) 0.0 to 0.0 else 0.65 * archY to -0.45 * archX
                    Glyph.TileGrid,
                    Glyph.Home ->
                        (if (contourIndex % 2 == 0) -0.5 else 0.5) to
                            (if (contourIndex < 2) -0.2 else 0.2)
                    Glyph.MagicWand -> if (contourIndex < 2) 0.0 to 0.0 else 0.65 * nx to 0.3 * ny
                    Glyph.SelectionAdd,
                    Glyph.SelectionSubtract,
                    Glyph.SelectionIntersect ->
                        if (contourIndex == 0) 0.0 to 0.0 else 0.55 * archY to -0.6 * archX
                    Glyph.SelectionInvert -> if (contourIndex == 0) 0.0 to 0.0 else 0.75 * ny to 0.0
                    Glyph.Fill ->
                        if (contourIndex == 3) 0.4 * nx to 0.6 else -0.55 * ny to 0.35 * archX
                    Glyph.Globe -> if (contourIndex == 0) 0.0 to 0.0 else 0.9 * archY to 0.0
                    Glyph.Keyboard ->
                        if (contourIndex == 8) 0.0 to 0.0
                        else 0.0 to if (contourIndex == 6) -0.75 else (contourIndex % 2) * 0.6
                    Glyph.Plugin -> 0.55 * archY * nx to -0.8 * archX * ny
                    Glyph.Palette -> if (contourIndex == 4) 0.0 to 0.0 else 0.75 * ny to -0.75 * nx
                    Glyph.Swap,
                    Glyph.SwapReverse -> 0.8 * ny to 0.0
                    Glyph.Copy -> if (contourIndex == 0) 0.0 to 0.0 else -0.7 to -0.35
                    Glyph.Cut ->
                        when (contourIndex) {
                            0 -> 0.6 to 0.0
                            3 -> -0.6 to 0.0
                            else -> 0.5 * ny to 0.3 * nx
                        }
                    Glyph.Clipboard -> if (contourIndex == 0) 0.0 to -0.8 else 0.0 to 0.0
                    Glyph.Merge -> 0.0 to if (contourIndex == 0) -0.55 else 0.55
                    Glyph.Search ->
                        if (contourIndex == 0) 0.65 * (x - 11) / 8 to -0.25 * (y - 11) / 8
                        else 0.0 to 0.0
                    Glyph.Favorite,
                    Glyph.FavoriteFilled ->
                        if (contourIndex == 0) 0.65 * archY * nx to -0.65 * archX * ny
                        else 0.0 to 0.0
                    Glyph.Update ->
                        if (contourIndex == 0) 0.3 * ny to -0.3 * nx else 0.0 to -0.75 * archY
                    Glyph.Sidebar,
                    Glyph.SidebarClosed ->
                        if (contourIndex == 0) 0.0 to 0.0
                        else if (contourIndex == 1) -0.8 to 0.0 else -0.8 * archY to 0.0
                    Glyph.Lock -> if (contourIndex == 0) 0.75 * ny to -0.65 * archX else 0.0 to 0.0
                    Glyph.Rotate -> if (contourIndex == 4) 0.65 * ny to -0.65 * nx else 0.0 to 0.0
                    Glyph.Mirror ->
                        if (contourIndex < 4) 0.0 to 0.0
                        else (if (contourIndex == 4) -0.7 else 0.7) to 0.45 * ny
                    Glyph.Move ->
                        if (contourIndex < 2) 0.55 * nx to 0.25 * ny else 0.8 * nx to 0.45 * ny
                    Glyph.Transform -> if (contourIndex == 0) 0.0 to 0.0 else 0.7 * nx to -0.7 * ny
                    Glyph.Gradient -> if (contourIndex == 0) 0.6 to -0.3 else -0.6 to 0.3
                    Glyph.Smudge -> 0.7 * archY * (1 - nx) to 0.85 * archX * (1 - ny)
                    Glyph.MirrorVertical ->
                        if (contourIndex < 2) 0.25 * nx to -0.8 * ny else 0.0 to 0.0
                    Glyph.ImportImage ->
                        if (contourIndex < 2) 0.0 to -0.7
                        else if (contourIndex == 4) 0.5 to -0.3 else 0.0 to 0.0
                    Glyph.Reference -> if (contourIndex == 2) 0.0 to -0.9 * archX else 0.0 to 0.0
                    Glyph.Vector ->
                        if (contourIndex == 0) 0.7 * sin(t * PI) to -0.9 * sin(t * PI * 2)
                        else 0.0 to 0.0
                    Glyph.Rectangle,
                    Glyph.Maximize -> 0.6 * nx to -0.3 * ny + 0.35 * archX
                    Glyph.Ellipse -> 0.8 * nx to -0.55 * ny
                    Glyph.Line ->
                        if (contourIndex == 0) 0.55 * sin(t * PI) to 0.55 * sin(t * PI)
                        else 0.0 to 0.0
                    Glyph.Assistant ->
                        if (contourIndex == 2) 0.0 to -0.9
                        else if (contourIndex == 0 || contourIndex == 1) 0.55 * ny * nx to 0.0
                        else 0.0 to 0.0
                    Glyph.Animation ->
                        if (contourIndex == 7) 0.9 * archY to 0.0
                        else if (contourIndex in 3..6) 0.0 to 0.6 else 0.0 to 0.0
                    Glyph.Play -> 0.65 * (1 + nx) to -0.45 * ny
                    Glyph.Pause -> if (contourIndex == 0) -0.55 to -0.45 * ny else 0.55 to 0.45 * ny
                    Glyph.Stop -> 0.6 * nx to -0.35 * ny + 0.3 * archX
                    Glyph.Forward ->
                        0.8 * (1 + nx) / 2 to if (contourIndex == 0) -0.4 * archX else 0.0
                    Glyph.Backward ->
                        -0.8 * (1 - nx) / 2 to if (contourIndex == 0) -0.4 * archX else 0.0
                    Glyph.Restore -> if (contourIndex == 0) 0.6 to -0.3 else -0.4 to 0.3
                }
            points[i] = (x + amount * dx).coerceIn(0.5, 23.5)
            points[i + 1] = (y + amount * dy).coerceIn(0.5, 23.5)
        }
        contour.copy(points = points)
    }
}
