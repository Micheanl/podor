package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import org.jetbrains.compose.resources.painterResource

private val sampledGlyphs by lazy {
    glyphMorphPaths.mapValues { (glyph, paths) ->
        lazy {
            val source = sampleMorphContours(paths)
            MorphIconPose.entries.associateWith { glyphInteractionContours(glyph, source, it) }
        }
    }
}

internal fun sampleMorphContours(paths: List<String>): List<MorphContour> {
    val result = mutableListOf<MorphContour>()
    val parser = PathParser()
    val measure = PathMeasure()
    for (data in paths) {
        val nodes = parser.parsePathString(data).toNodes().toList()
        val contour = mutableListOf<PathNode>()
        var endpoint = Offset.Zero
        fun finish() {
            if (contour.isEmpty()) return
            val start = contour.first() as PathNode.MoveTo
            val origin = Offset(start.x, start.y)
            val path = PathParser().addPathNodes(contour).toPath()
            measure.setPath(path, false)
            val length = measure.length
            endpoint = if (length > 0f) measure.getPosition(length) else origin
            val closed =
                contour.last() == PathNode.Close ||
                    (length > 0f && (endpoint - origin).getDistance() < 0.0001f)
            val points = DoubleArray(128)
            for (i in 0 until 64) {
                val point =
                    if (length > 0f) measure.getPosition(length * i / if (closed) 64f else 63f)
                    else origin
                points[i * 2] = point.x.toDouble()
                points[i * 2 + 1] = point.y.toDouble()
            }
            result += MorphContour(points, closed)
            contour.clear()
        }
        for (node in nodes) {
            when (node) {
                is PathNode.MoveTo -> {
                    finish()
                    contour += node
                }
                is PathNode.RelativeMoveTo -> {
                    finish()
                    contour += PathNode.MoveTo(endpoint.x + node.dx, endpoint.y + node.dy)
                }
                else -> contour += node
            }
        }
        finish()
    }
    return result
}

@Composable
internal fun MorphIcon(
    glyph: Glyph,
    tint: Color,
    modifier: Modifier,
    reducedMotion: Boolean,
    strokeWidth: Float,
    pose: MorphIconPose,
) {
    var displayedGlyph by remember { mutableStateOf(glyph) }
    var displayedPose by remember { mutableStateOf(pose) }
    var motion by remember { mutableStateOf<MorphTransition?>(null) }
    val frameTick = remember { mutableIntStateOf(0) }

    LaunchedEffect(glyph, pose, reducedMotion) {
        if (glyph == displayedGlyph && pose == displayedPose && motion == null)
            return@LaunchedEffect
        val destination = if (reducedMotion) null else sampledGlyphs[glyph]?.value?.get(pose)
        val source =
            if (reducedMotion || motion != null) null
            else sampledGlyphs[displayedGlyph]?.value?.get(displayedPose)
        if (destination == null || (motion == null && source == null)) {
            displayedGlyph = glyph
            displayedPose = pose
            motion = null
            return@LaunchedEffect
        }
        val transition = motion ?: MorphTransition(source!!)
        transition.retarget(destination)
        motion = transition
        frameTick.intValue++
        var previous = withFrameNanos { it }
        while (transition.running) {
            val now = withFrameNanos { it }
            transition.advance((now - previous) / 1_000_000_000.0)
            previous = now
            frameTick.intValue++
        }
        displayedGlyph = glyph
        displayedPose = pose
        motion = null
    }

    val current = motion
    if (reducedMotion || (current == null && displayedPose == MorphIconPose.Rest)) {
        Icon(
            painterResource((if (reducedMotion) glyph else displayedGlyph).resource),
            null,
            modifier,
            tint,
        )
    } else {
        val path = remember { Path() }
        Canvas(modifier) {
            frameTick.intValue
            path.reset()
            val contours =
                current?.frame ?: sampledGlyphs[displayedGlyph]!!.value.getValue(displayedPose)
            for (contour in contours) {
                val points = contour.points
                path.moveTo(points[0].toFloat(), points[1].toFloat())
                for (i in 2 until points.size step 2) path.lineTo(
                    points[i].toFloat(),
                    points[i + 1].toFloat(),
                )
                if (contour.closed) path.close()
            }
            scale(size.width / 24f, size.height / 24f, Offset.Zero) {
                drawPath(
                    path,
                    tint,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
