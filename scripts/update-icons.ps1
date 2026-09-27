$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$catalog = Get-Content (Join-Path $root 'gradle/libs.versions.toml') -Raw
$revision = [regex]::Match($catalog, 'lucideIcons = "([a-f0-9]+)"').Groups[1].Value
if (-not $revision) { throw '缺少 Lucide 版本' }
$base = "https://raw.githubusercontent.com/lucide-icons/lucide/$revision"
$icons = [ordered]@{
    Brush='brush'; Eraser='eraser'; Picker='pipette'; Hand='hand'; Undo='undo-2'; Redo='redo-2'
    Layers='layers'; Plus='plus'; Minus='minus'; Eye='eye'; Hidden='eye-off'; Trash='trash-2'
    Up='arrow-up'; Down='arrow-down'; Fit='scan'; More='ellipsis'; Folder='folder-open'
    Save='save'; Export='share'; Settings='settings-2'; Adjustments='sliders-horizontal'
    Close='x'; Check='check'; Chevron='chevron-down'; Selection='square-dashed'; Fill='paint-bucket'
    Globe='globe'; Keyboard='keyboard'; Plugin='puzzle'; Palette='palette'; Swap='arrow-right-left'
    Copy='copy'; Merge='layers-2'; Home='layout-grid'; Search='search'
}
$drawable = Join-Path $root 'shared/src/commonMain/composeResources/drawable'
$entries = foreach ($icon in $icons.GetEnumerator()) {
    [xml]$svg = (Invoke-WebRequest "$base/icons/$($icon.Value).svg").Content
    $paths = foreach ($node in $svg.svg.ChildNodes) {
        $d = switch ($node.LocalName) {
            'path' { $node.d }
            'circle' {
                $x = [double]$node.cx; $y = [double]$node.cy; $r = [double]$node.r
                "M $($x-$r),$y a $r,$r 0 1,0 $($r*2),0 a $r,$r 0 1,0 $(-$r*2),0"
            }
            'line' { "M $($node.x1),$($node.y1) L $($node.x2),$($node.y2)" }
            'polyline' { "M $($node.points)" }
            'polygon' { "M $($node.points) Z" }
            'rect' {
                $x=[double]$node.x; $y=[double]$node.y; $w=[double]$node.width; $h=[double]$node.height; $r=[double]$node.rx
                "M $($x+$r),$y H $($x+$w-$r) Q $($x+$w),$y $($x+$w),$($y+$r) V $($y+$h-$r) Q $($x+$w),$($y+$h) $($x+$w-$r),$($y+$h) H $($x+$r) Q $x,$($y+$h) $x,$($y+$h-$r) V $($y+$r) Q $x,$y $($x+$r),$y Z"
            }
            default { throw "不支持的 SVG 元素：$($node.LocalName)" }
        }
        '    <path android:fillColor="#00000000" android:strokeColor="#FF000000" android:strokeWidth="1.8" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="' + $d + '" />'
    }
    $name = 'ic_' + $icon.Key.ToLowerInvariant()
    $xml = '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">' + "`n" + ($paths -join "`n") + "`n</vector>`n"
    [IO.File]::WriteAllText((Join-Path $drawable "$name.xml"), $xml)
    "    $($icon.Key)(Res.drawable.$name),"
}
$source = @"
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
$($entries -join "`n")
    Update(Res.drawable.ic_update),
}

@Composable
fun StudioIcon(glyph: Glyph, tint: Color = StudioTheme.text, modifier: Modifier = Modifier) {
    Icon(painterResource(glyph.resource), null, modifier.size(StudioTheme.iconSize), tint)
}
"@
[IO.File]::WriteAllText((Join-Path $root 'shared/src/commonMain/kotlin/app/podor/ui/Icons.kt'), $source + "`n")
$licenses = Join-Path $root 'licenses'
New-Item -ItemType Directory -Path $licenses -Force | Out-Null
Invoke-WebRequest "$base/LICENSE" -OutFile (Join-Path $licenses 'lucide.txt')
Write-Output "已更新 $($icons.Count) 个图标"
