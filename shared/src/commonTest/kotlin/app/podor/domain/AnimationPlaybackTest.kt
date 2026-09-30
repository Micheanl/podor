package app.podor.domain

import kotlin.test.*
import kotlinx.serialization.json.Json

class AnimationPlaybackTest {
    private val animation =
        AnimationInfo(
            activeFrameId = 10,
            maxFrames = 256,
            maxCels = 8192,
            maxTags = 64,
            frames =
                listOf(
                    AnimationFrame(10, 40, listOf(CelExposure(1, 9))),
                    AnimationFrame(20, 70, listOf(CelExposure(1, 9))),
                    AnimationFrame(30, 20, listOf(CelExposure(1, 11))),
                ),
        )

    @Test
    fun exactDurationBoundariesAndMissedTicksKeepTheLatestDueFrame() {
        val plan = assertNotNull(AnimationPlaybackPlan.create(animation))
        for ((elapsed, frame, cycle) in
            listOf(
                Triple(-1L, 10, 0L),
                Triple(0L, 10, 0L),
                Triple(39_999_999L, 10, 0L),
                Triple(40_000_000L, 20, 0L),
                Triple(109_999_999L, 20, 0L),
                Triple(110_000_000L, 30, 0L),
                Triple(129_999_999L, 30, 0L),
                Triple(130_000_000L, 10, 1L),
                Triple(2_260_000_000L, 20, 17L),
            )) {
            assertEquals(PlaybackPosition(frame, cycle, false), plan.at(elapsed))
        }
        assertEquals(9, animation.exposure(20, 1)?.celId)
        assertEquals(11, animation.exposure(30, 1)?.celId)
        assertNull(animation.exposure(30, 2))
    }

    @Test
    fun finiteRepeatStopsOnTheActualFinalExposureWithoutOverflow() {
        val plan = assertNotNull(AnimationPlaybackPlan.create(animation, repeat = 2))
        assertEquals(PlaybackPosition(30, 1, false), plan.at(259_999_999L))
        assertEquals(PlaybackPosition(30, 1, true), plan.at(260_000_000L))
        assertEquals(PlaybackPosition(30, 1, true), plan.at(Long.MAX_VALUE))
        val infinite = assertNotNull(AnimationPlaybackPlan.create(animation))
        assertEquals(PlaybackPosition(10, 70_949_015_668L, false), infinite.at(Long.MAX_VALUE))
    }

    @Test
    fun unequalDurationsChooseTheFirstLaterEndAcrossEveryNanosecondBoundary() {
        val plan =
            assertNotNull(
                AnimationPlaybackPlan.create(
                    animation.copy(
                        frames =
                            listOf(
                                AnimationFrame(11, 1),
                                AnimationFrame(7, 3),
                                AnimationFrame(23, 2),
                                AnimationFrame(2, 60000),
                                AnimationFrame(91, 1),
                            )
                    )
                )
            )
        assertEquals(60_007_000_000L, plan.cycleNanos)
        for ((elapsed, frame, cycle) in
            listOf(
                Triple(Long.MIN_VALUE, 11, 0L),
                Triple(0L, 11, 0L),
                Triple(999_999L, 11, 0L),
                Triple(1_000_000L, 7, 0L),
                Triple(1_000_001L, 7, 0L),
                Triple(3_999_999L, 7, 0L),
                Triple(4_000_000L, 23, 0L),
                Triple(4_000_001L, 23, 0L),
                Triple(5_999_999L, 23, 0L),
                Triple(6_000_000L, 2, 0L),
                Triple(6_000_001L, 2, 0L),
                Triple(60_005_999_999L, 2, 0L),
                Triple(60_006_000_000L, 91, 0L),
                Triple(60_006_000_001L, 91, 0L),
                Triple(60_006_999_999L, 91, 0L),
                Triple(60_007_000_000L, 11, 1L),
                Triple(60_007_000_001L, 11, 1L),
            )) assertEquals(PlaybackPosition(frame, cycle, false), plan.at(elapsed))
    }

    @Test
    fun singleFrameUsesLongCyclesAndMaximumFiniteRepeatKeepsTheFinalExposure() {
        val single = animation.copy(frames = listOf(AnimationFrame(44, 1)))
        val infinite = assertNotNull(AnimationPlaybackPlan.create(single))
        assertEquals(1_000_000L, infinite.cycleNanos)
        assertEquals(PlaybackPosition(44, 0L, false), infinite.at(999_999L))
        assertEquals(PlaybackPosition(44, 1L, false), infinite.at(1_000_000L))
        assertEquals(PlaybackPosition(44, 9_223_372_036_854L, false), infinite.at(Long.MAX_VALUE))
        val finite = assertNotNull(AnimationPlaybackPlan.create(single, repeat = 65535))
        assertEquals(PlaybackPosition(44, 65534L, false), finite.at(65_534_999_999L))
        assertEquals(PlaybackPosition(44, 65534L, true), finite.at(65_535_000_000L))
        assertEquals(PlaybackPosition(44, 65534L, true), finite.at(Long.MAX_VALUE))
    }

    @Test
    fun PingPongDoesNotRepeatTheTurningEndpointAndHandlesOneAndTwoFrames() {
        val expected =
            mapOf(
                AnimationDirection.Forward to listOf(10, 20, 30),
                AnimationDirection.Reverse to listOf(30, 20, 10),
                AnimationDirection.PingPong to listOf(10, 20, 30, 20),
                AnimationDirection.PingPongReverse to listOf(30, 20, 10, 20),
            )
        expected.forEach { (direction, ids) ->
            val plan = assertNotNull(AnimationPlaybackPlan.create(animation, direction = direction))
            assertEquals(ids, plan.frames.map { it.id })
        }
        for (direction in AnimationDirection.entries) {
            val single = animation.copy(frames = animation.frames.take(1))
            assertEquals(
                listOf(10),
                assertNotNull(AnimationPlaybackPlan.create(single, direction = direction))
                    .frames
                    .map {
                        it.id
                    },
            )
        }
        val pair = animation.copy(frames = animation.frames.take(2))
        assertEquals(
            listOf(10, 20),
            assertNotNull(
                    AnimationPlaybackPlan.create(pair, direction = AnimationDirection.PingPong)
                )
                .frames
                .map { it.id },
        )
    }

    @Test
    fun TagUsesStableEndpointsAcrossReorderAndRejectsMissingOrMalformedRanges() {
        val tag =
            AnimationTag(
                4,
                "Walk",
                30,
                20,
                AnimationDirection.Reverse,
                1,
                listOf(90, 120, 210, 255),
            )
        val tagged = animation.copy(tags = listOf(tag))
        val plan = assertNotNull(AnimationPlaybackPlan.create(tagged, tagId = 4))
        assertEquals(listOf(30, 20), plan.frames.map { it.id })
        assertEquals(PlaybackPosition(20, 0, true), plan.at(90_000_000L))
        val reordered =
            tagged.copy(frames = listOf(tagged.frames[2], tagged.frames[0], tagged.frames[1]))
        assertEquals(
            listOf(20, 10, 30),
            assertNotNull(AnimationPlaybackPlan.create(reordered, tagId = 4)).frames.map { it.id },
        )
        assertNull(AnimationPlaybackPlan.create(tagged, tagId = 5))
        assertNull(
            AnimationPlaybackPlan.create(tagged.copy(frames = tagged.frames.dropLast(1)), tagId = 4)
        )
        assertNull(AnimationPlaybackPlan.create(animation.copy(frames = emptyList())))
        assertNull(
            AnimationPlaybackPlan.create(animation.copy(frames = listOf(AnimationFrame(1, 0))))
        )
        assertNull(AnimationPlaybackPlan.create(animation, repeat = -1))
        assertNull(AnimationPlaybackPlan.create(animation, repeat = 65536))
        assertNull(
            AnimationPlaybackPlan.create(
                animation.copy(frames = listOf(animation.frames[0], animation.frames[0]))
            )
        )
    }

    @Test
    fun TimelineWirePreservesFrameCelIdsDurationsAndExplicitDirection() {
        val decoded =
            Json.decodeFromString<AnimationInfo>(
                """{"enabled":true,"activeFrameId":20,"activeCelId":9,"maxFrames":256,"maxCels":8192,"maxTags":64,"frames":[{"id":10,"durationMs":40,"cels":[{"layerId":1,"celId":9}]},{"id":20,"durationMs":70,"cels":[{"layerId":1,"celId":9}]}],"tags":[{"id":4,"name":"Walk","fromFrame":10,"toFrame":20,"direction":"ping_pong_reverse","repeat":0,"color":[90,120,210,255]}]}"""
            )
        assertEquals(20, decoded.activeFrameId)
        assertEquals(9, decoded.activeCelId)
        assertEquals(listOf(40, 70), decoded.frames.map { it.durationMs })
        assertEquals(AnimationDirection.PingPongReverse, decoded.tags.single().direction)
        assertEquals(
            listOf(20, 10),
            assertNotNull(AnimationPlaybackPlan.create(decoded, 4)).frames.map { it.id },
        )
    }
}
