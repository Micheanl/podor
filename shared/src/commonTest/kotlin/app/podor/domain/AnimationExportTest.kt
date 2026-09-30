package app.podor.domain

import kotlin.test.*
import kotlinx.serialization.json.Json

class AnimationExportTest {
    private val capabilities =
        Json.decodeFromString<AnimationExportCapabilities>(
            """{"operation":26,"maxFrames":256,"maxSequenceFrames":510,"maxPixels":16777216,"maxPixelVisits":268435456,"maxOutputBytes":134217728,"maxScratchBytes":67108864,"maxAtlasDimension":8192,"maxAtlasPadding":32}"""
        )
    private val animation =
        AnimationInfo(
            activeFrameId = 10,
            maxFrames = 256,
            maxCels = 8192,
            maxTags = 64,
            frames =
                listOf(AnimationFrame(10, 20), AnimationFrame(20, 70), AnimationFrame(30, 130)),
            tags =
                listOf(
                    AnimationTag(
                        4,
                        "Walk",
                        20,
                        30,
                        AnimationDirection.Reverse,
                        4,
                        listOf(90, 120, 210, 255),
                    )
                ),
        )
    private val document =
        DocumentInfo(
            width = 8,
            height = 8,
            revision = 24,
            animation = animation,
            animationExport = capabilities,
        )

    @Test
    fun capabilityWirePreservesNativeBudgetsAndHidesUnsupportedEngines() {
        assertTrue(capabilities.available)
        assertEquals(26, capabilities.operation)
        assertEquals(256, capabilities.maxFrames)
        assertEquals(510, capabilities.maxSequenceFrames)
        assertEquals(16_777_216L, capabilities.maxPixels)
        assertEquals(268_435_456L, capabilities.maxPixelVisits)
        assertEquals(134_217_728L, capabilities.maxOutputBytes)
        assertEquals(67_108_864L, capabilities.maxScratchBytes)
        assertEquals(8192, capabilities.maxAtlasDimension)
        assertEquals(32, capabilities.maxAtlasPadding)
        val options = AnimationExportOptions()
        assertEquals(
            AnimationExportIssue.Unsupported,
            options.validation(document.copy(animationExport = null)),
        )
        for (limits in
            listOf(
                capabilities.copy(operation = 5),
                capabilities.copy(maxFrames = 0),
                capabilities.copy(maxOutputBytes = 0),
                capabilities.copy(maxScratchBytes = -1),
            )) {
            assertFalse(limits.available)
            assertEquals(
                AnimationExportIssue.Unsupported,
                options.validation(document.copy(animationExport = limits)),
            )
        }
    }

    @Test
    fun defaultGifRequestExplicitlyChoosesLossyPoliciesWithoutAtlasOrEditingFields() {
        val options = AnimationExportOptions()
        assertNull(options.validation(document))
        assertEquals(
            """{"revision":24,"format":"gif","scope":{"kind":"all","direction":"forward","repeat":0},"color_policy":"quantize","alpha":{"kind":"threshold","cutoff":128},"timing":"round"}""",
            options.requestJson(24).toString(),
        )
    }

    @Test
    fun tagRequestUsesOnlyTheStableTagIdAndExactChoicesAreExplicit() {
        val options =
            AnimationExportOptions(
                scope = AnimationExportScope.Tag(4),
                colorPolicy = GifColorPolicy.Exact,
                alpha = GifAlphaPolicy.Exact,
                timing = GifTimingPolicy.Exact,
            )
        assertNull(options.validation(document))
        assertEquals(
            """{"revision":24,"format":"gif","scope":{"kind":"tag","id":4},"color_policy":"exact","alpha":{"kind":"exact"},"timing":"exact"}""",
            options.requestJson(24).toString(),
        )
    }

    @Test
    fun rangeRequestRetainsFrameIdsAcrossInsertionAndReorder() {
        val options =
            AnimationExportOptions(
                scope = AnimationExportScope.Range(30, 20, AnimationDirection.PingPongReverse, 1),
                alpha = GifAlphaPolicy.Matte(listOf(17, 80, 255)),
            )
        val inserted =
            animation.copy(
                frames =
                    listOf(
                        animation.frames[2],
                        AnimationFrame(99, 50),
                        animation.frames[0],
                        animation.frames[1],
                    )
            )
        assertNull(options.validation(document))
        assertNull(options.validation(document.copy(animation = inserted)))
        assertEquals(
            """{"revision":4294967300,"format":"gif","scope":{"kind":"range","from_frame":30,"to_frame":20,"direction":"ping_pong_reverse","repeat":1},"color_policy":"quantize","alpha":{"kind":"matte","color":[17,80,255]},"timing":"round"}""",
            options.requestJson(4_294_967_300).toString(),
        )
        val tinyWorkBudget =
            document.copy(
                animation = inserted,
                animationExport = capabilities.copy(maxPixelVisits = 639),
            )
        assertEquals(AnimationExportIssue.WorkLimit, options.validation(tinyWorkBudget))
        assertNull(
            options.validation(
                tinyWorkBudget.copy(animationExport = capabilities.copy(maxPixelVisits = 640))
            )
        )
    }

    @Test
    fun allDirectionsAndTotalCycleCountsHaveTheDocumentedWireNames() {
        val cases =
            listOf(
                Triple(
                    AnimationDirection.Forward,
                    0,
                    """{"kind":"all","direction":"forward","repeat":0}""",
                ),
                Triple(
                    AnimationDirection.Reverse,
                    1,
                    """{"kind":"all","direction":"reverse","repeat":1}""",
                ),
                Triple(
                    AnimationDirection.PingPong,
                    4,
                    """{"kind":"all","direction":"ping_pong","repeat":4}""",
                ),
                Triple(
                    AnimationDirection.PingPongReverse,
                    65535,
                    """{"kind":"all","direction":"ping_pong_reverse","repeat":65535}""",
                ),
            )
        for ((direction, repeat, expected) in cases) {
            val options =
                AnimationExportOptions(scope = AnimationExportScope.All(direction, repeat))
            assertNull(options.validation(document))
            assertEquals(expected, options.requestJson(24)["scope"].toString())
        }
        for (repeat in listOf(-1, 65536)) {
            assertEquals(
                AnimationExportIssue.InvalidSettings,
                AnimationExportOptions(scope = AnimationExportScope.All(repeat = repeat))
                    .validation(document),
            )
        }
    }

    @Test
    fun deletedFrameAnchorsAndUnknownOrDuplicateTagsNeverFallBackToAllFrames() {
        val range = AnimationExportOptions(scope = AnimationExportScope.Range(10, 30))
        val tag = AnimationExportOptions(scope = AnimationExportScope.Tag(4))
        val deleted =
            document.copy(animation = animation.copy(frames = animation.frames.dropLast(1)))
        assertEquals(AnimationExportIssue.InvalidScope, range.validation(deleted))
        assertEquals(AnimationExportIssue.InvalidScope, tag.validation(deleted))
        assertEquals(
            AnimationExportIssue.InvalidScope,
            AnimationExportOptions(scope = AnimationExportScope.Range(999, 20))
                .validation(document),
        )
        assertEquals(
            AnimationExportIssue.InvalidScope,
            AnimationExportOptions(scope = AnimationExportScope.Tag(999)).validation(document),
        )
        assertEquals(
            AnimationExportIssue.InvalidScope,
            tag.validation(document.copy(animation = animation.copy(tags = emptyList()))),
        )
        assertEquals(
            AnimationExportIssue.InvalidScope,
            tag.validation(
                document.copy(animation = animation.copy(tags = animation.tags + animation.tags))
            ),
        )
        assertEquals(
            AnimationExportIssue.InvalidScope,
            AnimationExportOptions(scope = AnimationExportScope.Tag(0))
                .validation(
                    document.copy(
                        animation = animation.copy(tags = listOf(animation.tags[0].copy(id = 0)))
                    )
                ),
        )
    }

    @Test
    fun tagDirectionRepeatAndSpanDriveValidationWithoutCallerOverrides() {
        val tag = AnimationExportOptions(scope = AnimationExportScope.Tag(4))
        val limited = document.copy(animationExport = capabilities.copy(maxPixelVisits = 256))
        assertNull(tag.validation(limited))
        assertEquals(
            AnimationExportIssue.WorkLimit,
            tag.validation(limited.copy(animationExport = capabilities.copy(maxPixelVisits = 255))),
        )
        val reordered =
            animation.copy(
                frames = listOf(animation.frames[1], animation.frames[0], animation.frames[2])
            )
        assertEquals(
            AnimationExportIssue.WorkLimit,
            tag.validation(limited.copy(animation = reordered)),
        )
        assertEquals(
            AnimationExportIssue.InvalidSettings,
            tag.validation(
                document.copy(
                    animation =
                        animation.copy(tags = listOf(animation.tags[0].copy(repeat = 65536)))
                )
            ),
        )
    }

    @Test
    fun malformedTimelinesAndUnsignedRevisionAreRejected() {
        for (value in
            listOf<AnimationInfo?>(
                null,
                animation.copy(enabled = false),
                animation.copy(frames = emptyList()),
                animation.copy(frames = animation.frames + animation.frames[0]),
                animation.copy(frames = listOf(AnimationFrame(0, 20))),
                animation.copy(frames = listOf(AnimationFrame(10, 0))),
                animation.copy(frames = listOf(AnimationFrame(10, 60001))),
            )) {
            assertEquals(
                AnimationExportIssue.InvalidTimeline,
                AnimationExportOptions().validation(document.copy(animation = value)),
            )
        }
        assertEquals(
            AnimationExportIssue.InvalidSettings,
            AnimationExportOptions().validation(document.copy(revision = -1)),
        )
    }

    @Test
    fun timingExactChecksOnlySelectedSourceDurationsAndRoundKeepsTheRequestExplicit() {
        val uneven =
            document.copy(
                animation =
                    animation.copy(
                        frames =
                            listOf(AnimationFrame(10, 15), animation.frames[1], animation.frames[2])
                    )
            )
        assertEquals(
            AnimationExportIssue.TimingPrecision,
            AnimationExportOptions(timing = GifTimingPolicy.Exact).validation(uneven),
        )
        assertNull(
            AnimationExportOptions(
                    timing = GifTimingPolicy.Exact,
                    scope = AnimationExportScope.Range(20, 30),
                )
                .validation(uneven)
        )
        assertNull(
            AnimationExportOptions(
                    timing = GifTimingPolicy.Exact,
                    scope = AnimationExportScope.Tag(4),
                )
                .validation(uneven)
        )
        assertNull(AnimationExportOptions(timing = GifTimingPolicy.Round).validation(uneven))
        assertEquals(listOf(15, 70, 130), uneven.animation!!.frames.map { it.durationMs })
    }

    @Test
    fun alphaPoliciesRequireAnExplicitValidThresholdOrThreeByteMatte() {
        for (alpha in
            listOf(
                GifAlphaPolicy.Exact,
                GifAlphaPolicy.Threshold(1),
                GifAlphaPolicy.Threshold(128),
                GifAlphaPolicy.Threshold(255),
                GifAlphaPolicy.Matte(listOf(0, 128, 255)),
            )) {
            assertNull(AnimationExportOptions(alpha = alpha).validation(document))
        }
        for (alpha in
            listOf(
                GifAlphaPolicy.Threshold(0),
                GifAlphaPolicy.Threshold(256),
                GifAlphaPolicy.Matte(emptyList()),
                GifAlphaPolicy.Matte(listOf(0, 0, 0, 255)),
                GifAlphaPolicy.Matte(listOf(-1, 0, 0)),
                GifAlphaPolicy.Matte(listOf(0, 256, 0)),
            )) {
            assertEquals(
                AnimationExportIssue.InvalidSettings,
                AnimationExportOptions(alpha = alpha).validation(document),
            )
        }
    }

    @Test
    fun nativeDistinctFrameAndPingPongSequenceCapsIncludeSingletonsAndPairs() {
        val full = document.withFrames(256)
        assertNull(
            AnimationExportOptions(scope = AnimationExportScope.All(AnimationDirection.PingPong))
                .validation(full)
        )
        assertEquals(
            AnimationExportIssue.FrameLimit,
            AnimationExportOptions(scope = AnimationExportScope.All(AnimationDirection.PingPong))
                .validation(
                    full.copy(animationExport = capabilities.copy(maxSequenceFrames = 509))
                ),
        )
        assertEquals(
            AnimationExportIssue.FrameLimit,
            AnimationExportOptions().validation(document.withFrames(257)),
        )
        for (count in listOf(1, 2)) {
            assertNull(
                AnimationExportOptions(
                        scope = AnimationExportScope.All(AnimationDirection.PingPongReverse)
                    )
                    .validation(
                        document
                            .withFrames(count)
                            .copy(animationExport = capabilities.copy(maxSequenceFrames = count))
                    )
            )
        }
    }

    @Test
    fun nativePixelVisitCapIncludesPreflightAndOneCycleWithoutExpandingRepeats() {
        val large = document.copy(width = 4096, height = 4096).withFrames(8)
        assertNull(AnimationExportOptions().validation(large))
        assertNull(
            AnimationExportOptions(scope = AnimationExportScope.All(repeat = 65535))
                .validation(large)
        )
        assertEquals(
            AnimationExportIssue.WorkLimit,
            AnimationExportOptions().validation(large.withFrames(9)),
        )
        assertNull(
            AnimationExportOptions(scope = AnimationExportScope.All(AnimationDirection.PingPong))
                .validation(large.withFrames(6))
        )
        assertEquals(
            AnimationExportIssue.WorkLimit,
            AnimationExportOptions(scope = AnimationExportScope.All(AnimationDirection.PingPong))
                .validation(large.withFrames(7)),
        )
        assertEquals(
            AnimationExportIssue.CanvasLimit,
            AnimationExportOptions().validation(large.copy(width = 4097)),
        )
        assertEquals(
            AnimationExportIssue.CanvasLimit,
            AnimationExportOptions()
                .validation(document.copy(width = Int.MAX_VALUE, height = Int.MAX_VALUE)),
        )
    }

    @Test
    fun linkedCelsRemainDistinctTimelineExposuresInTheWorkBudget() {
        val linked =
            document.copy(
                animation =
                    animation.copy(
                        frames = animation.frames.map { it.copy(cels = listOf(CelExposure(1, 9))) }
                    ),
                animationExport = capabilities.copy(maxPixelVisits = 383),
            )
        assertEquals(AnimationExportIssue.WorkLimit, AnimationExportOptions().validation(linked))
        assertNull(
            AnimationExportOptions()
                .validation(linked.copy(animationExport = capabilities.copy(maxPixelVisits = 384)))
        )
        val frames = assertNotNull(linked.animation).frames
        assertEquals(listOf(10, 20, 30), frames.map { it.id })
        assertEquals(listOf(20, 70, 130), frames.map { it.durationMs })
    }

    @Test
    fun atlasWireExcludesGifPoliciesAndKeepsAutoColumnsAndZeroPaddingExplicit() {
        val options =
            AnimationExportOptions(
                format = AnimationExportFormat.Atlas,
                alpha = GifAlphaPolicy.Threshold(0),
                timing = GifTimingPolicy.Exact,
            )
        assertNull(options.validation(document))
        assertNull(
            options.validation(
                document.copy(animation = animation.copy(frames = listOf(AnimationFrame(10, 15))))
            )
        )
        assertEquals(
            """{"revision":24,"format":"atlas","scope":{"kind":"all","direction":"forward","repeat":0},"columns":0,"padding":0}""",
            options.requestJson(24).toString(),
        )
        assertEquals(
            """{"revision":24,"format":"atlas","scope":{"kind":"tag","id":4},"columns":2,"padding":32}""",
            options
                .copy(scope = AnimationExportScope.Tag(4), columns = 2, padding = 32)
                .requestJson(24)
                .toString(),
        )
        assertEquals(
            AnimationExportIssue.InvalidSettings,
            options.copy(scope = AnimationExportScope.Tag(4), columns = 3).validation(document),
        )
        assertEquals(
            AnimationExportIssue.InvalidSettings,
            options.copy(padding = 33).validation(document),
        )
        assertEquals(
            AnimationExportIssue.InvalidSettings,
            options.copy(columns = -1).validation(document),
        )
    }

    @Test
    fun atlasFitsAsymmetricSourcesAndChecksPaddingUnusedCellsAndNativePixelLimit() {
        val options = AnimationExportOptions(format = AnimationExportFormat.Atlas)
        assertNull(options.validation(document.copy(width = 8192, height = 1).withFrames(2)))
        assertNull(options.validation(document.copy(width = 1, height = 8192).withFrames(2)))
        assertEquals(
            AnimationExportIssue.AtlasLimit,
            options
                .copy(columns = 2)
                .validation(document.copy(width = 8192, height = 1).withFrames(2)),
        )
        assertEquals(
            AnimationExportIssue.AtlasLimit,
            options
                .copy(padding = 1)
                .validation(document.copy(width = 8192, height = 1).withFrames(1)),
        )
        assertNull(
            options
                .copy(columns = 2)
                .validation(document.copy(width = 2048, height = 2048).withFrames(4))
        )
        assertEquals(
            AnimationExportIssue.AtlasLimit,
            options
                .copy(columns = 2)
                .validation(document.copy(width = 2049, height = 2048).withFrames(4)),
        )
        val five =
            document
                .copy(
                    width = 1600,
                    height = 1200,
                    animationExport = capabilities.copy(maxPixelVisits = 20_000_000),
                )
                .withFrames(5)
        assertEquals(AnimationExportIssue.WorkLimit, options.validation(five))
        assertNull(options.copy(columns = 5).validation(five))
    }

    @Test
    fun atlasBudgetArithmeticRejectsExtremePaddingWithoutWrapping() {
        val options = AnimationExportOptions(format = AnimationExportFormat.Atlas)
        val extremes =
            document
                .copy(
                    animationExport =
                        capabilities.copy(
                            maxPixels = Long.MAX_VALUE,
                            maxPixelVisits = Long.MAX_VALUE,
                            maxAtlasDimension = Int.MAX_VALUE,
                            maxAtlasPadding = Int.MAX_VALUE,
                        )
                )
                .withFrames(256)
        assertEquals(
            AnimationExportIssue.AtlasLimit,
            options.copy(padding = Int.MAX_VALUE).validation(extremes),
        )
        assertEquals(
            AnimationExportIssue.InvalidSettings,
            options.copy(columns = Int.MAX_VALUE).validation(extremes),
        )
        assertEquals(
            AnimationExportIssue.WorkLimit,
            options.validation(
                document.copy(animationExport = capabilities.copy(maxPixelVisits = 447))
            ),
        )
        assertNull(
            options.validation(
                document.copy(animationExport = capabilities.copy(maxPixelVisits = 448))
            )
        )
    }

    private fun DocumentInfo.withFrames(count: Int): DocumentInfo =
        copy(
            animation =
                animation!!.copy(
                    frames = List(count) { AnimationFrame(it + 1, 20) },
                    tags = emptyList(),
                )
        )
}
