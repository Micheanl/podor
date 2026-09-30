package app.podor.presentation

import app.podor.domain.AnimationFrame
import app.podor.domain.AnimationInfo
import app.podor.domain.DocumentInfo
import kotlin.test.*
import kotlinx.serialization.json.*

class AnimationTargetTest {
    private val document =
        DocumentInfo(
            active = 7,
            revision = 41,
            animation =
                AnimationInfo(
                    activeFrameId = 10,
                    activeCelId = 20,
                    maxFrames = 256,
                    maxCels = 8192,
                    maxTags = 64,
                    frames = listOf(AnimationFrame(10, 100), AnimationFrame(11, 100)),
                ),
        )

    private val command = buildJsonObject {
        put("type", "begin")
        put("x", 12)
        put("y", 24)
    }

    @Test
    fun queuedCommandKeepsTheFrameLayerCelAndRevisionCapturedAtInput() {
        val captured = command.withAnimationTarget(document)
        assertEquals(JsonPrimitive(10), captured["frame_id"])
        assertEquals(JsonPrimitive(20), captured["cel_id"])
        assertEquals(JsonPrimitive(7), captured["target_layer_id"])
        assertEquals(JsonPrimitive(41L), captured["revision"])
        command.forEach { (key, value) -> assertEquals(value, captured[key]) }
        val later =
            document.copy(
                active = 8,
                revision = 42,
                animation = document.animation!!.copy(activeFrameId = 11, activeCelId = 21),
            )
        assertEquals(captured, captured.withAnimationTarget(later))
    }

    @Test
    fun explicitEmptyCelAndRevisionAreNeverReplacedDuringCapture() {
        val targeted = buildJsonObject {
            put("type", "begin")
            put("frame_id", 11)
            put("cel_id", JsonNull)
            put("target_layer_id", 8)
            put("revision", 40L)
        }
        assertEquals(targeted, targeted.withAnimationTarget(document))
        assertEquals(targeted, targeted.withAnimationTarget(document, includeRevision = false))
        val emptyCel = document.copy(animation = document.animation!!.copy(activeCelId = null))
        assertEquals(JsonNull, command.withAnimationTarget(emptyCel)["cel_id"])
    }

    @Test
    fun inputCanCaptureATargetWithoutPinningTheWorkerRevision() {
        val captured = command.withAnimationTarget(document, includeRevision = false)
        assertEquals(JsonPrimitive(10), captured["frame_id"])
        assertEquals(JsonPrimitive(20), captured["cel_id"])
        assertEquals(JsonPrimitive(7), captured["target_layer_id"])
        assertFalse("revision" in captured)
        assertEquals(command, JsonObject(captured - setOf("frame_id", "cel_id", "target_layer_id")))
    }

    @Test
    fun workerRebindsNewlyCreatedCelAndRevisionWithinTheCapturedFrameAndLayer() {
        val emptyCel = document.copy(animation = document.animation!!.copy(activeCelId = null))
        val captured = command.withAnimationTarget(emptyCel, includeRevision = false)
        val rebound = captured.rebindAnimationStroke(document)
        assertEquals(JsonPrimitive(10), rebound["frame_id"])
        assertEquals(JsonPrimitive(20), rebound["cel_id"])
        assertEquals(JsonPrimitive(7), rebound["target_layer_id"])
        assertEquals(JsonPrimitive(41L), rebound["revision"])
        command.forEach { (key, value) -> assertEquals(value, rebound[key]) }
        assertEquals(JsonNull, captured["cel_id"])
        assertFalse("revision" in captured)
    }

    @Test
    fun workerRefreshesAnExistingTargetAndPreservesAnEmptyExposure() {
        val captured = command.withAnimationTarget(document)
        val later =
            document.copy(
                revision = 42,
                animation = document.animation!!.copy(activeCelId = null),
            )
        val rebound = captured.rebindAnimationStroke(later)
        assertEquals(JsonPrimitive(10), rebound["frame_id"])
        assertEquals(JsonPrimitive(7), rebound["target_layer_id"])
        assertEquals(JsonNull, rebound["cel_id"])
        assertEquals(JsonPrimitive(42L), rebound["revision"])
        assertEquals(JsonPrimitive(20), captured["cel_id"])
        assertEquals(JsonPrimitive(41L), captured["revision"])
    }

    @Test
    fun workerRejectsQueuedStrokesAfterTheFrameOrLayerChanges() {
        val captured = command.withAnimationTarget(document)
        val anotherFrame = document.copy(animation = document.animation!!.copy(activeFrameId = 11))
        assertFailsWith<IllegalArgumentException> { captured.rebindAnimationStroke(anotherFrame) }
        assertFailsWith<IllegalArgumentException> {
            captured.rebindAnimationStroke(document.copy(active = 8))
        }
    }

    @Test
    fun workerRejectsStrokesWithoutACapturedFrameAndLayer() {
        assertFailsWith<IllegalArgumentException> { command.rebindAnimationStroke(document) }
        val captured = command.withAnimationTarget(document)
        for (missing in listOf("frame_id", "target_layer_id")) {
            assertFailsWith<IllegalArgumentException> {
                JsonObject(captured - missing).rebindAnimationStroke(document)
            }
        }
    }

    @Test
    fun stillDocumentsPassCommandsThroughCaptureAndWorkerUnchanged() {
        val still = document.copy(animation = null)
        assertSame(command, command.withAnimationTarget(still))
        assertSame(command, command.withAnimationTarget(still, includeRevision = false))
        assertSame(command, command.rebindAnimationStroke(still))
    }

    @Test
    fun timelineAndHistoryCommandsKeepTheirExplicitTargets() {
        for (type in
            listOf(
                "select_frame",
                "new_cel",
                "link_cel",
                "set_frame_duration",
                "undo",
                "state",
                "new",
            )) {
            val timeline = buildJsonObject {
                put("type", type)
                put("frame_id", 11)
                put("layer_id", 8)
            }
            assertSame(timeline, timeline.withAnimationTarget(document))
        }
    }
}
