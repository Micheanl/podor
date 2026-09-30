package app.podor.ui

import kotlin.math.*
import kotlin.test.*

class MorphIconGeometryTest {
    private fun line(x1: Double, y1: Double, x2: Double, y2: Double) =
        MorphContour(
            DoubleArray(8) { i ->
                val t = (i / 2) / 3.0
                if (i % 2 == 0) x1 + (x2 - x1) * t else y1 + (y2 - y1) * t
            }
        )

    private val menu =
        listOf(line(4.0, 6.0, 20.0, 6.0), line(4.0, 12.0, 20.0, 12.0), line(4.0, 18.0, 20.0, 18.0))
    private val cross = listOf(line(6.0, 6.0, 18.0, 18.0), line(6.0, 18.0, 18.0, 6.0))
    private val check =
        listOf(MorphContour(doubleArrayOf(20.0, 6.0, 9.0, 17.0, 6.0, 14.0, 4.0, 12.0)))

    private fun signature(contour: MorphContour) =
        contour.points.toList().chunked(2).map { it.joinToString(",") }.sorted().joinToString(";")

    private fun signatures(contours: List<MorphContour>) = contours.map(::signature).toSet()

    private fun finite(contours: List<MorphContour>) {
        contours.forEach { contour -> assertTrue(contour.points.all { it.isFinite() }) }
    }

    @Test
    fun aRotatedArrowKeepsEveryPairwiseDistanceThroughoutTheMorph() {
        val right =
            listOf(
                line(4.0, 12.0, 20.0, 12.0),
                MorphContour(doubleArrayOf(14.0, 6.0, 20.0, 12.0, 17.0, 15.0, 14.0, 18.0)),
            )
        val down = right.map { contour ->
            contour.copy(
                points =
                    DoubleArray(contour.points.size) { i ->
                        if (i % 2 == 0) 24 - contour.points[i + 1] else contour.points[i - 1]
                    }
            )
        }
        val plan = MorphPlan(right, down)
        val original = plan.interpolate(0.0).flatMap { it.points.toList().chunked(2) }
        for (t in listOf(0.1, 0.25, 0.5, 0.75, 0.9)) {
            val frame = plan.interpolate(t).flatMap { it.points.toList().chunked(2) }
            for (a in original.indices) for (b in original.indices) {
                val before = hypot(original[a][0] - original[b][0], original[a][1] - original[b][1])
                val actual = hypot(frame[a][0] - frame[b][0], frame[a][1] - frame[b][1])
                assertEquals(before, actual, 1e-8, "Rigid arrow at $t")
            }
        }
    }

    @Test
    fun splittingAndJoiningPathsCoversEverySourceAndTargetExactlyAtTheEndpoints() {
        for ((source, target) in listOf(menu to cross, cross to check, check to menu)) {
            val originals = source.map { it.points.copyOf() }
            val plan = MorphPlan(source, target)
            assertEquals(max(source.size, target.size), plan.output.size)
            assertEquals(signatures(source), signatures(plan.interpolate(0.0)))
            assertEquals(signatures(target), signatures(plan.interpolate(1.0)))
            finite(plan.interpolate(0.5))
            source.indices.forEach { assertContentEquals(originals[it], source[it].points) }
        }
    }

    @Test
    fun closedLoopsWithOppositeTraversalAndDifferentStartPointsStayTheSameShape() {
        val source = MorphContour(doubleArrayOf(2.0, 2.0, 6.0, 2.0, 6.0, 6.0, 2.0, 6.0), true)
        val target = MorphContour(doubleArrayOf(6.0, 6.0, 6.0, 2.0, 2.0, 2.0, 2.0, 6.0), true)
        val plan = MorphPlan(listOf(source), listOf(target))
        for (t in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val frame = plan.interpolate(t).single()
            assertTrue(frame.closed)
            frame.points.indices.forEach { assertEquals(source.points[it], frame.points[it], 1e-9) }
        }
    }

    @Test
    fun anOpeningLoopOnlyClosesWhenBothEndpointsAreClosed() {
        val triangle = MorphContour(doubleArrayOf(7.0, 4.0, 20.0, 12.0, 7.0, 20.0, 7.0, 12.0), true)
        val pause = listOf(line(7.0, 5.0, 7.0, 19.0), line(17.0, 5.0, 17.0, 19.0))
        val plan = MorphPlan(listOf(triangle), pause)
        assertTrue(plan.interpolate(0.5).all { !it.closed })
        assertEquals(signatures(pause), signatures(plan.interpolate(1.0)))
    }

    @Test
    fun largePathAssignmentsStillReachEveryTargetInsteadOfDroppingContours() {
        val source = (10..22).map { line(1.0, it.toDouble(), 5.0, it.toDouble()) }
        val target = (1..5).map { line(1.0, it.toDouble(), 5.0, it.toDouble()) }
        val plan = MorphPlan(source, target)
        assertEquals(13, plan.output.size)
        assertEquals(signatures(source), signatures(plan.interpolate(0.0)))
        assertEquals(signatures(target), signatures(plan.interpolate(1.0)))
        finite(plan.interpolate(0.5))
    }

    @Test
    fun degenerateDotsAndSpringOvershootProduceFiniteGeometry() {
        val dot = listOf(MorphContour(DoubleArray(8) { 12.0 }))
        for ((source, target) in listOf(dot to check, check to dot)) {
            val plan = MorphPlan(source, target)
            for (t in listOf(-0.05, 0.0, 0.5, 1.0, 1.05)) finite(plan.interpolate(t))
        }
    }

    @Test
    fun rapidRetargetingStartsAtTheExactVisibleShapeAndPreservesBoundedVelocity() {
        val motion = MorphTransition(menu)
        motion.retarget(cross)
        repeat(40) { index ->
            repeat(3) { motion.advance(1.0 / 60) }
            val visible = signatures(motion.frame)
            val velocity = motion.velocity.coerceIn(-14.0, 14.0)
            motion.retarget(if (index % 2 == 0) check else cross)
            assertEquals(visible, signatures(motion.frame))
            assertEquals(velocity, motion.velocity)
            assertEquals(0.0, motion.progress)
            assertTrue(motion.frame.size <= menu.size)
            finite(motion.frame)
        }
        repeat(90) { motion.advance(1.0 / 60) }
        assertFalse(motion.running)
        assertEquals(1.0, motion.progress)
        assertSame(cross, motion.frame)
    }

    @Test
    fun idleAndSettledIconsRequireNoMoreFrameAdvances() {
        val motion = MorphTransition(menu)
        assertFalse(motion.running)
        repeat(60) { assertFalse(motion.advance(1.0 / 60)) }
        assertSame(menu, motion.frame)
        motion.retarget(cross)
        var frames = 0
        while (motion.running && frames < 90) {
            motion.advance(1.0 / 60)
            frames++
        }
        assertTrue(frames in 1..89)
        assertSame(cross, motion.frame)
        repeat(60) { assertFalse(motion.advance(1.0 / 60)) }
        assertSame(cross, motion.frame)
    }

    @Test
    fun reducedMotionSnapsImmediatelyEvenDuringAFastInterruptedMorph() {
        val motion = MorphTransition(menu)
        motion.retarget(cross)
        repeat(3) { motion.advance(1.0 / 60) }
        assertTrue(motion.running)
        motion.retarget(check, reducedMotion = true)
        assertFalse(motion.running)
        assertEquals(1.0, motion.progress)
        assertEquals(0.0, motion.velocity)
        assertSame(check, motion.frame)
        assertFalse(motion.advance(1.0 / 60))
    }

    @Test
    fun unchangedInteractionTargetsDoNotRestartAnActiveOrSettledSpring() {
        val motion = MorphTransition(menu)
        motion.retarget(menu)
        assertFalse(motion.running)
        motion.retarget(cross)
        repeat(4) { motion.advance(1.0 / 60) }
        val progress = motion.progress
        val velocity = motion.velocity
        val visible = signatures(motion.frame)
        motion.retarget(cross)
        assertEquals(progress, motion.progress)
        assertEquals(velocity, motion.velocity)
        assertEquals(visible, signatures(motion.frame))
        repeat(90) { motion.advance(1.0 / 60) }
        motion.retarget(cross)
        assertFalse(motion.running)
        assertSame(cross, motion.frame)
    }

    @Test
    fun pressedHoveredAndSelectedStatesHaveStablePriorityAndDisabledAndReducedStayCanonical() {
        assertEquals(MorphIconPose.Rest, morphIconPose(true, false, false, false, false))
        assertEquals(MorphIconPose.Selected, morphIconPose(true, false, false, false, true))
        assertEquals(MorphIconPose.Hover, morphIconPose(true, false, true, false, true))
        assertEquals(MorphIconPose.Pressed, morphIconPose(true, false, true, true, true))
        for (hovered in listOf(false, true)) for (pressed in listOf(false, true)) for (selected in
            listOf(false, true)) {
            assertEquals(
                MorphIconPose.Rest,
                morphIconPose(false, false, hovered, pressed, selected),
            )
            assertEquals(MorphIconPose.Rest, morphIconPose(true, true, hovered, pressed, selected))
        }
    }

    @Test
    fun interruptedHoverPressAndSelectionMorphsReturnToTheExactIdleOutlineAndStop() {
        val idle = listOf(line(5.0, 12.0, 19.0, 12.0), line(12.0, 5.0, 12.0, 19.0))
        val states =
            listOf(
                MorphIconPose.Hover,
                MorphIconPose.Pressed,
                MorphIconPose.Selected,
                MorphIconPose.Rest,
            )
        val motion = MorphTransition(idle)
        for (pose in states) {
            val visible = signatures(motion.frame)
            val next = glyphInteractionContours(Glyph.Plus, idle, pose)
            motion.retarget(next)
            assertEquals(visible, signatures(motion.frame))
            repeat(4) { motion.advance(1.0 / 60) }
            finite(motion.frame)
        }
        repeat(90) { motion.advance(1.0 / 60) }
        assertFalse(motion.running)
        assertSame(idle, motion.frame)
        assertEquals(signatures(idle), signatures(motion.frame))
    }
}
