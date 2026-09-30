package app.podor.domain

import kotlin.math.abs
import kotlinx.serialization.Serializable

enum class CurveChannel(val label: String, val symbol: String) {
    Rgb("RGB", "RGB"),
    Red("红色通道", "R"),
    Green("绿色通道", "G"),
    Blue("蓝色通道", "B"),
}

@Serializable data class CurvePoint(val x: Int, val y: Int)

@Serializable
data class ToneCurve(
    val points: List<CurvePoint> = listOf(CurvePoint(0, 0), CurvePoint(255, 255))
) {
    fun valid() =
        points.size in 2..StudioDefaults.maxCurvePoints &&
            points.first().x == 0 &&
            points.last().x == 255 &&
            points.all { it.x in 0..255 && it.y in 0..255 } &&
            points.zipWithNext().all { (a, b) -> a.x < b.x }

    fun move(index: Int, point: CurvePoint): ToneCurve {
        val x =
            when (index) {
                0 -> 0
                points.lastIndex -> 255
                else -> point.x.coerceIn(points[index - 1].x + 1, points[index + 1].x - 1)
            }
        return copy(
            points =
                points.toMutableList().also { it[index] = CurvePoint(x, point.y.coerceIn(0, 255)) }
        )
    }

    fun insert(point: CurvePoint): ToneCurve {
        val value = CurvePoint(point.x.coerceIn(0, 255), point.y.coerceIn(0, 255))
        val existing = points.indexOfFirst { it.x == value.x }
        if (existing >= 0) return move(existing, value)
        if (points.size >= StudioDefaults.maxCurvePoints) return this
        return copy(points = (points + value).sortedBy { it.x })
    }

    fun remove(index: Int) =
        if (index in 1 until points.lastIndex)
            copy(points = points.filterIndexed { i, _ -> i != index })
        else this

    fun samples(): FloatArray {
        require(valid())
        val n = points.size
        val h = DoubleArray(n - 1) { (points[it + 1].x - points[it].x).toDouble() }
        val d = DoubleArray(n - 1) { (points[it + 1].y - points[it].y) / h[it] }
        val slopes = DoubleArray(n)
        slopes[0] = d[0]
        slopes[n - 1] = d[n - 2]
        if (n > 2) {
            fun edge(h0: Double, h1: Double, d0: Double, d1: Double): Double {
                val value = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
                return if (value * d0 <= 0) 0.0
                else if (d0 * d1 <= 0 && abs(value) > 3 * abs(d0)) 3 * d0 else value
            }
            slopes[0] = edge(h[0], h[1], d[0], d[1])
            slopes[n - 1] = edge(h[n - 2], h[n - 3], d[n - 2], d[n - 3])
            for (i in 1 until n - 1) {
                if (d[i - 1] * d[i] > 0) {
                    val w1 = 2 * h[i] + h[i - 1]
                    val w2 = h[i] + 2 * h[i - 1]
                    slopes[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
                }
            }
        }
        var segment = 0
        return FloatArray(256) { x ->
            while (segment + 1 < n - 1 && x > points[segment + 1].x) segment++
            val t = (x - points[segment].x) / h[segment]
            val t2 = t * t
            val t3 = t2 * t
            ((2 * t3 - 3 * t2 + 1) * points[segment].y +
                    (t3 - 2 * t2 + t) * h[segment] * slopes[segment] +
                    (-2 * t3 + 3 * t2) * points[segment + 1].y +
                    (t3 - t2) * h[segment] * slopes[segment + 1])
                .coerceIn(0.0, 255.0)
                .toFloat()
        }
    }
}

@Serializable
data class ColorCurves(
    val rgb: ToneCurve = ToneCurve(),
    val red: ToneCurve = ToneCurve(),
    val green: ToneCurve = ToneCurve(),
    val blue: ToneCurve = ToneCurve(),
) {
    operator fun get(channel: CurveChannel) =
        when (channel) {
            CurveChannel.Rgb -> rgb
            CurveChannel.Red -> red
            CurveChannel.Green -> green
            CurveChannel.Blue -> blue
        }

    fun withCurve(channel: CurveChannel, curve: ToneCurve) =
        when (channel) {
            CurveChannel.Rgb -> copy(rgb = curve)
            CurveChannel.Red -> copy(red = curve)
            CurveChannel.Green -> copy(green = curve)
            CurveChannel.Blue -> copy(blue = curve)
        }

    fun valid() = CurveChannel.entries.all { this[it].valid() }
}
