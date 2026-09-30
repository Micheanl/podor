package app.podor.ui

import kotlin.math.*
import kotlin.test.*

class MorphIconSamplingTest {
    private fun point(contour: MorphContour, index: Int, x: Double, y: Double) {
        assertEquals(x, contour.points[index * 2], 0.0001)
        assertEquals(y, contour.points[index * 2 + 1], 0.0001)
    }

    @Test
    fun relativeSubpathStartsFollowThePreviousEndpointAndCloseRestoresTheOrigin() {
        val open = sampleMorphContours(listOf("M2 3L4 3m2 5l2 0"))
        assertEquals(2, open.size)
        point(open[0], 0, 2.0, 3.0)
        point(open[0], 63, 4.0, 3.0)
        point(open[1], 0, 6.0, 8.0)
        point(open[1], 63, 8.0, 8.0)
        val closed = sampleMorphContours(listOf("M2 3L4 3L4 5zm2 5l2 0"))
        assertEquals(2, closed.size)
        assertTrue(closed[0].closed)
        point(closed[1], 0, 4.0, 8.0)
        point(closed[1], 63, 6.0, 8.0)
    }

    @Test
    fun circlesWithoutCloseCommandsRemainClosedAndIsolatedPointsStayFinite() {
        val sun = sampleMorphContours(glyphMorphPaths.getValue(Glyph.Sun))
        assertEquals(9, sun.size)
        assertTrue(sun.first().closed)
        assertTrue(sun.drop(1).none { it.closed })
        val dot = sampleMorphContours(listOf("m5 7")).single()
        assertFalse(dot.closed)
        repeat(64) { point(dot, it, 5.0, 7.0) }
    }

    @Test
    fun everyOperationGlyphProducesFiniteUniformContours() {
        assertEquals(
            Glyph.entries.filter { it != Glyph.Aseprite && it != Glyph.SvgLogo }.toSet(),
            glyphMorphPaths.keys,
        )
        for ((glyph, data) in glyphMorphPaths) {
            val contours = sampleMorphContours(data)
            assertTrue(contours.isNotEmpty(), glyph.name)
            for (contour in contours) {
                assertEquals(128, contour.points.size, glyph.name)
                assertTrue(contour.points.all { it.isFinite() }, glyph.name)
            }
        }
    }

    @Test
    fun realPlaybackPanelVisibilityAndThemeIconsCanMorphWithoutLosingEitherEndpoint() {
        fun signatures(contours: List<MorphContour>) =
            contours
                .map { contour ->
                    contour.points
                        .toList()
                        .chunked(2)
                        .map { it.joinToString(",") }
                        .sorted()
                        .joinToString(";")
                }
                .toSet()
        val states =
            listOf(
                Glyph.Play to Glyph.Pause,
                Glyph.Play to Glyph.Stop,
                Glyph.Sidebar to Glyph.SidebarClosed,
                Glyph.Eye to Glyph.Hidden,
                Glyph.Sun to Glyph.Moon,
                Glyph.Favorite to Glyph.FavoriteFilled,
                Glyph.Maximize to Glyph.Restore,
            )
        for ((a, b) in states) {
            val source = sampleMorphContours(glyphMorphPaths.getValue(a))
            val target = sampleMorphContours(glyphMorphPaths.getValue(b))
            val plan = MorphPlan(source, target)
            assertEquals(signatures(source), signatures(plan.interpolate(0.0)))
            assertEquals(signatures(target), signatures(plan.interpolate(1.0)))
            for (t in listOf(0.25, 0.5, 0.75)) assertTrue(
                plan.interpolate(t).all { contour -> contour.points.all { it.isFinite() } }
            )
        }
    }

    @Test
    fun everyGlyphHasDistinctInternalInteractionShapesWithinItsViewportWithoutMutatingTheOriginal() {
        for ((glyph, data) in glyphMorphPaths) {
            val idle = sampleMorphContours(data)
            val originals = idle.map { it.points.copyOf() }
            assertSame(idle, glyphInteractionContours(glyph, idle, MorphIconPose.Rest))
            val variants =
                listOf(MorphIconPose.Hover, MorphIconPose.Pressed, MorphIconPose.Selected).map {
                    pose ->
                    val target = glyphInteractionContours(glyph, idle, pose)
                    assertEquals(idle.size, target.size, glyph.name)
                    assertEquals(idle.map { it.closed }, target.map { it.closed }, glyph.name)
                    assertTrue(
                        target
                            .flatMap { it.points.toList() }
                            .all { it.isFinite() && it in 0.0..24.0 },
                        glyph.name,
                    )
                    val a = idle.flatMap { it.points.toList() }.toDoubleArray()
                    val b = target.flatMap { it.points.toList() }.toDoubleArray()
                    assertTrue(
                        similarityResidual(a, b) > 0.000001,
                        "${glyph.name}: $pose only moves or scales the whole glyph",
                    )
                    target
                }
            assertFalse(
                variants[0].zip(variants[1]).all { (a, b) -> a.points.contentEquals(b.points) },
                glyph.name,
            )
            assertFalse(
                variants[0].zip(variants[2]).all { (a, b) -> a.points.contentEquals(b.points) },
                glyph.name,
            )
            idle.indices.forEach { assertContentEquals(originals[it], idle[it].points, glyph.name) }
        }
    }

    @Test
    fun exportAndSaveAnimateTheirMovingPartsWhileKeepingTheContainerFixed() {
        for (glyph in listOf(Glyph.Export, Glyph.Save)) {
            val idle = sampleMorphContours(glyphMorphPaths.getValue(glyph))
            val hovered = glyphInteractionContours(glyph, idle, MorphIconPose.Hover)
            assertContentEquals(idle[0].points, hovered[0].points)
            assertTrue(
                idle.drop(1).zip(hovered.drop(1)).any { (a, b) ->
                    !a.points.contentEquals(b.points)
                }
            )
        }
    }

    @Test
    fun allInteractionTransitionsHaveFiniteIntermediateOutlinesAndSettleOnTheirOwnShape() {
        for ((glyph, data) in glyphMorphPaths) {
            val idle = sampleMorphContours(data)
            val motion = MorphTransition(idle)
            for (pose in
                listOf(
                    MorphIconPose.Hover,
                    MorphIconPose.Pressed,
                    MorphIconPose.Selected,
                    MorphIconPose.Rest,
                )) {
                val next = glyphInteractionContours(glyph, idle, pose)
                motion.retarget(next)
                var frames = 0
                while (motion.running && frames < 90) {
                    motion.advance(1.0 / 60)
                    assertTrue(
                        motion.frame.all { contour -> contour.points.all { it.isFinite() } },
                        glyph.name,
                    )
                    frames++
                }
                assertTrue(frames in 1..89, "${glyph.name}: $pose")
                assertSame(next, motion.frame, glyph.name)
                assertFalse(motion.advance(1.0 / 60), glyph.name)
            }
        }
    }

    private fun similarityResidual(a: DoubleArray, b: DoubleArray): Double {
        val count = a.size / 2
        val ax = (0 until count).sumOf { a[it * 2] } / count
        val ay = (0 until count).sumOf { a[it * 2 + 1] } / count
        val bx = (0 until count).sumOf { b[it * 2] } / count
        val by = (0 until count).sumOf { b[it * 2 + 1] } / count
        var dot = 0.0
        var cross = 0.0
        var magnitude = 0.0
        for (i in a.indices step 2) {
            val x = a[i] - ax
            val y = a[i + 1] - ay
            val u = b[i] - bx
            val v = b[i + 1] - by
            dot += x * u + y * v
            cross += x * v - y * u
            magnitude += x * x + y * y
        }
        val scaleCos = dot / magnitude
        val scaleSin = cross / magnitude
        return (0 until count).sumOf { index ->
            val x = a[index * 2] - ax
            val y = a[index * 2 + 1] - ay
            val dx = bx + x * scaleCos - y * scaleSin - b[index * 2]
            val dy = by + x * scaleSin + y * scaleCos - b[index * 2 + 1]
            dx * dx + dy * dy
        } / count
    }
}
