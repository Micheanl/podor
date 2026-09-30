package app.podor.domain

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
data class AssistantPoint(val x: Float, val y: Float) {
    fun offset() = Offset(x, y)

    fun valid(limit: Float) = x.isFinite() && y.isFinite() && abs(x) <= limit && abs(y) <= limit
}

fun Offset.assistantPoint() = AssistantPoint(x, y)

@Serializable
data class DrawingAssistantSet(
    val nextId: Int = 1,
    val snapId: Int? = null,
    val items: List<DrawingAssistant> = emptyList(),
)

@Serializable
data class DrawingAssistant(
    val id: Int,
    val name: String,
    val visible: Boolean,
    val geometry: AssistantGeometry,
) {
    fun spec() = DrawingAssistantSpec(name, visible, geometry)
}

@Serializable
data class DrawingAssistantSpec(
    val name: String,
    val visible: Boolean = true,
    val geometry: AssistantGeometry,
) {
    fun request(): JsonObject = parameters.encodeToJsonElement(this).jsonObject

    fun valid(limit: Float) =
        name.isNotBlank() && name.encodeToByteArray().size <= 256 && geometry.valid(limit)

    companion object {
        private val parameters = Json { encodeDefaults = true }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface AssistantGeometry {
    @Serializable
    @SerialName("parallel")
    data class Parallel(val a: AssistantPoint, val b: AssistantPoint) : AssistantGeometry

    @Serializable
    @SerialName("radial")
    data class Radial(val center: AssistantPoint) : AssistantGeometry

    @Serializable
    @SerialName("perspective")
    data class Perspective(val families: List<AssistantFamily>) : AssistantGeometry

    fun valid(limit: Float): Boolean =
        when (this) {
            is Parallel -> a.valid(limit) && b.valid(limit) && a != b
            is Radial -> center.valid(limit)
            is Perspective ->
                families.size == 3 &&
                    families.any { it is AssistantFamily.Vanishing } &&
                    families.all { it.valid(limit) } &&
                    families.indices.all { index ->
                        families.take(index).none { it.sameAxis(families[index]) }
                    }
        }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface AssistantFamily {
    @Serializable
    @SerialName("finite_vanishing_point")
    data class Vanishing(val point: AssistantPoint) : AssistantFamily

    @Serializable
    @SerialName("infinite_direction")
    data class Infinite(val direction: AssistantPoint) : AssistantFamily

    fun valid(limit: Float) =
        when (this) {
            is Vanishing -> point.valid(limit)
            is Infinite -> direction.valid(limit) && (direction.x != 0f || direction.y != 0f)
        }

    fun sameAxis(other: AssistantFamily): Boolean =
        when {
            this is Vanishing && other is Vanishing -> point == other.point
            this is Infinite && other is Infinite -> {
                val a = direction.offset()
                val b = other.direction.offset()
                abs(a.x.toDouble() * b.y - a.y.toDouble() * b.x) <=
                    a.getDistance().toDouble() * b.getDistance() * 1e-9
            }
            else -> false
        }
}

enum class AssistantPreset(val label: String) {
    Parallel("平行线助手"),
    Radial("集中线助手"),
    OnePoint("一点透视"),
    TwoPoint("两点透视"),
    ThreePoint("三点透视");

    fun spec(document: DocumentInfo): DrawingAssistantSpec {
        val w = document.width.toFloat()
        val h = document.height.toFloat()
        val geometry =
            when (this) {
                Parallel ->
                    AssistantGeometry.Parallel(
                        AssistantPoint(w / 4f, h / 2f),
                        AssistantPoint(w * 3f / 4f, h / 2f),
                    )
                Radial -> AssistantGeometry.Radial(AssistantPoint(w / 2f, h / 2f))
                OnePoint ->
                    AssistantGeometry.Perspective(
                        listOf(
                            AssistantFamily.Vanishing(AssistantPoint(w / 2f, h / 3f)),
                            AssistantFamily.Infinite(AssistantPoint(1f, 0f)),
                            AssistantFamily.Infinite(AssistantPoint(0f, 1f)),
                        )
                    )
                TwoPoint ->
                    AssistantGeometry.Perspective(
                        listOf(
                            AssistantFamily.Vanishing(AssistantPoint(w / 8f, h / 3f)),
                            AssistantFamily.Vanishing(AssistantPoint(w * 7f / 8f, h / 3f)),
                            AssistantFamily.Infinite(AssistantPoint(0f, 1f)),
                        )
                    )
                ThreePoint ->
                    AssistantGeometry.Perspective(
                        listOf(
                            AssistantFamily.Vanishing(AssistantPoint(w / 8f, h / 3f)),
                            AssistantFamily.Vanishing(AssistantPoint(w * 7f / 8f, h / 3f)),
                            AssistantFamily.Vanishing(AssistantPoint(w / 2f, h * 1.5f)),
                        )
                    )
            }
        return DrawingAssistantSpec("${name} ${document.assistants.nextId}", geometry = geometry)
    }
}

fun AssistantGeometry.handles(document: DocumentInfo): List<Offset> =
    when (this) {
        is AssistantGeometry.Parallel -> listOf(a.offset(), b.offset())
        is AssistantGeometry.Radial -> listOf(center.offset())
        is AssistantGeometry.Perspective ->
            families.map {
                when (it) {
                    is AssistantFamily.Vanishing -> it.point.offset()
                    is AssistantFamily.Infinite -> {
                        val direction = it.direction.offset()
                        Offset(document.width / 2f, document.height / 2f) +
                            direction / direction.getDistance() *
                                (minOf(document.width, document.height) / 4f)
                    }
                }
            }
    }

fun AssistantGeometry.withHandle(
    index: Int,
    point: Offset,
    document: DocumentInfo,
): AssistantGeometry =
    when (this) {
        is AssistantGeometry.Parallel ->
            if (index == 0) copy(a = point.assistantPoint()) else copy(b = point.assistantPoint())
        is AssistantGeometry.Radial -> copy(center = point.assistantPoint())
        is AssistantGeometry.Perspective ->
            copy(
                families =
                    families.mapIndexed { position, family ->
                        if (position != index) family
                        else
                            when (family) {
                                is AssistantFamily.Vanishing ->
                                    family.copy(point = point.assistantPoint())
                                is AssistantFamily.Infinite ->
                                    family.copy(
                                        direction =
                                            (point -
                                                    Offset(
                                                        document.width / 2f,
                                                        document.height / 2f,
                                                    ))
                                                .assistantPoint()
                                    )
                            }
                    }
            )
    }
