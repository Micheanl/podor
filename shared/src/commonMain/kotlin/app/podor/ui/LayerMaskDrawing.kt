package app.podor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import app.podor.domain.LayerTransform
import app.podor.domain.ResampleFilter
import app.podor.presentation.LayerMaskFrame

internal class LayerMaskPaints {
    val content = Paint()
    val alpha = Paint().apply { blendMode = BlendMode.DstIn }
    val gray =
        Paint().apply {
            blendMode = BlendMode.DstIn
            colorFilter =
                ColorFilter.colorMatrix(ColorMatrix(FloatArray(20).apply { this[15] = 1f }))
        }
    val replace =
        Paint().apply {
            isAntiAlias = false
            blendMode = BlendMode.Src
        }
    val over = Paint().apply { isAntiAlias = false }
}

@Composable internal fun rememberLayerMaskPaints() = remember { LayerMaskPaints() }

internal fun DrawScope.withLayerContentTransform(
    offset: Offset,
    transform: LayerTransform?,
    source: Rect?,
    draw: DrawScope.() -> Unit,
) =
    withTransform(
        {
            if (transform != null && source != null) {
                val center = transform.center(source)
                translate(center.x, center.y)
                rotate(transform.angle, Offset.Zero)
                scale(
                    transform.width / source.width * if (transform.flipX) -1f else 1f,
                    transform.height / source.height * if (transform.flipY) -1f else 1f,
                    Offset.Zero,
                )
                translate(-source.center.x, -source.center.y)
            } else translate(offset.x, offset.y)
        },
        draw,
    )

internal fun layerSourceVisible(
    area: Rect,
    offset: Offset,
    transform: LayerTransform?,
    source: Rect?,
): Rect {
    if (transform == null || source == null) return area.translate(-offset)
    val corners =
        listOf(area.topLeft, area.topRight, area.bottomLeft, area.bottomRight).map {
            transform.sourcePoint(source, it)
        }
    return Rect(
        corners.minOf { it.x },
        corners.minOf { it.y },
        corners.maxOf { it.x },
        corners.maxOf { it.y },
    )
}

internal fun DrawScope.withLayerMask(
    mask: LayerMaskFrame?,
    paper: Rect,
    visible: Rect,
    paints: LayerMaskPaints,
    quality: FilterQuality,
    offset: Offset = Offset.Zero,
    transform: LayerTransform? = null,
    source: Rect? = null,
    renderer: PixelPreviewRenderer? = null,
    draw: DrawScope.() -> Unit,
) {
    if (mask == null || !mask.enabled) {
        draw()
        return
    }
    paints.replace.filterQuality = quality
    paints.over.filterQuality = quality
    val canvas = drawContext.canvas
    canvas.saveLayer(paper, paints.content)
    draw()
    canvas.saveLayer(paper, if (mask.split) paints.gray else paints.alpha)
    val gray = mask.default / 255f
    drawRect(
        if (mask.split) Color(gray, gray, gray) else Color.Black.copy(alpha = gray),
        topLeft = paper.topLeft,
        size = paper.size,
    )
    val tileOffset = if (mask.split) Offset.Zero else offset
    val tileTransform = if (mask.split) null else transform
    val tileVisible = layerSourceVisible(visible, tileOffset, tileTransform, source)
    if (tileTransform?.filter == ResampleFilter.Nearest && source != null && renderer != null) {
        with(renderer) {
            mask.tiles.forEach { tile ->
                val origin =
                    Offset(
                        mask.bounds.left + tile.x * tile.size,
                        mask.bounds.top + tile.y * tile.size,
                    )
                val bounds =
                    Rect(
                            origin,
                            androidx.compose.ui.geometry.Size(
                                tile.size.toFloat(),
                                tile.size.toFloat(),
                            ),
                        )
                        .intersect(mask.bounds)
                if (!bounds.isEmpty && bounds.overlaps(tileVisible)) {
                    drawTile(
                        tile.image,
                        bounds,
                        origin,
                        tileOffset,
                        tileTransform,
                        source,
                        replace = true,
                    )
                }
            }
        }
    } else
        withLayerContentTransform(tileOffset, tileTransform, source) {
            clipRect(mask.bounds.left, mask.bounds.top, mask.bounds.right, mask.bounds.bottom) {
                mask.tiles.forEach { tile ->
                    val x = mask.bounds.left + tile.x * tile.size
                    val y = mask.bounds.top + tile.y * tile.size
                    if (
                        x < tileVisible.right &&
                            y < tileVisible.bottom &&
                            x + tile.size > tileVisible.left &&
                            y + tile.size > tileVisible.top
                    ) {
                        canvas.drawImage(tile.image, Offset(x, y), paints.replace)
                    }
                }
            }
        }
    if (mask.split) {
        withLayerContentTransform(offset, null, null) {
            val selectedVisible = visible.translate(-offset)
            mask.selectedTiles.forEach { tile ->
                val x = tile.x * tile.size
                val y = tile.y * tile.size
                if (
                    x < selectedVisible.right &&
                        y < selectedVisible.bottom &&
                        x + tile.size > selectedVisible.left &&
                        y + tile.size > selectedVisible.top
                ) {
                    canvas.drawImage(tile.image, Offset(x.toFloat(), y.toFloat()), paints.over)
                }
            }
        }
    }
    canvas.restore()
    canvas.restore()
}
