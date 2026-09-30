package app.podor.ui

import kotlin.math.*

internal data class MorphContour(val points: DoubleArray, val closed: Boolean = false)

internal class MorphPlan(source: List<MorphContour>, target: List<MorphContour>) {
    private class Item(val a: MorphContour, val b: MorphContour) {
        val ca = centroid(a.points)
        val cb = centroid(b.points)
        var similarity = similarity(a.points, b.points, ca, cb)
        val centered = DoubleArray(a.points.size) { a.points[it] - ca[it % 2] }
        val aligned = DoubleArray(a.points.size)
        var offset: DoubleArray? = null
        var drift: DoubleArray? = null

        init {
            alignTarget()
        }

        fun alignTarget() {
            val cos = cos(-similarity.angle) / similarity.scale
            val sin = sin(-similarity.angle) / similarity.scale
            for (i in b.points.indices step 2) {
                val x = b.points[i] - cb[0]
                val y = b.points[i + 1] - cb[1]
                aligned[i] = x * cos - y * sin
                aligned[i + 1] = x * sin + y * cos
            }
        }
    }

    private val items: List<Item>
    val output: List<MorphContour>

    init {
        require(source.isNotEmpty() && target.isNotEmpty())
        val size = source.first().points.size
        require(size >= 4 && size % 2 == 0)
        require(source.all { it.points.size == size } && target.all { it.points.size == size })
        val pairs = pairContours(source, target)
        items = pairs.map { (a, b) ->
            val (alignedA, alignedB) = alignContours(source[a], target[b])
            Item(alignedA, alignedB)
        }
        if (items.size > 1) {
            val a = items.flatMap { it.a.points.asIterable() }.toDoubleArray()
            val b = items.flatMap { it.b.points.asIterable() }.toDoubleArray()
            val center = centroid(a)
            val global = similarity(a, b, center, centroid(b))
            if (global.residual < 0.005) {
                items.forEach { item ->
                    item.similarity = global
                    item.alignTarget()
                    val x = item.ca[0] - center[0]
                    val y = item.ca[1] - center[1]
                    val cos = cos(global.angle) * global.scale
                    val sin = sin(global.angle) * global.scale
                    item.offset = doubleArrayOf(x, y)
                    item.drift =
                        doubleArrayOf(
                            item.cb[0] - item.ca[0] - (x * cos - y * sin - x),
                            item.cb[1] - item.ca[1] - (x * sin + y * cos - y),
                        )
                }
            }
        }
        output = items.map { MorphContour(DoubleArray(size), it.a.closed && it.b.closed) }
    }

    fun interpolate(progress: Double): List<MorphContour> {
        items.forEachIndexed { index, item ->
            val out = output[index].points
            if (progress == 0.0 || progress == 1.0) {
                (if (progress == 0.0) item.a.points else item.b.points).copyInto(out)
            } else {
                val scale = exp(ln(item.similarity.scale) * progress)
                val cos = cos(item.similarity.angle * progress) * scale
                val sin = sin(item.similarity.angle * progress) * scale
                var cx = item.ca[0] + (item.cb[0] - item.ca[0]) * progress
                var cy = item.ca[1] + (item.cb[1] - item.ca[1]) * progress
                item.offset?.let { offset ->
                    val drift = item.drift!!
                    cx =
                        item.ca[0] + drift[0] * progress + offset[0] * cos -
                            offset[1] * sin -
                            offset[0]
                    cy =
                        item.ca[1] + drift[1] * progress + offset[0] * sin + offset[1] * cos -
                            offset[1]
                }
                for (i in out.indices step 2) {
                    val x = item.centered[i] + (item.aligned[i] - item.centered[i]) * progress
                    val y =
                        item.centered[i + 1] +
                            (item.aligned[i + 1] - item.centered[i + 1]) * progress
                    out[i] = cx + x * cos - y * sin
                    out[i + 1] = cy + x * sin + y * cos
                }
            }
        }
        return output
    }
}

private data class MorphSimilarity(val angle: Double, val scale: Double, val residual: Double)

private fun centroid(points: DoubleArray): DoubleArray {
    val result = DoubleArray(2)
    for (i in points.indices) result[i % 2] += points[i]
    return result.apply { for (i in indices) this[i] /= points.size / 2 }
}

private fun similarity(
    a: DoubleArray,
    b: DoubleArray,
    ca: DoubleArray,
    cb: DoubleArray,
): MorphSimilarity {
    var dot = 0.0
    var cross = 0.0
    var energyA = 0.0
    var energyB = 0.0
    for (i in a.indices step 2) {
        val ax = a[i] - ca[0]
        val ay = a[i + 1] - ca[1]
        val bx = b[i] - cb[0]
        val by = b[i + 1] - cb[1]
        dot += ax * bx + ay * by
        cross += ax * by - ay * bx
        energyA += ax * ax + ay * ay
        energyB += bx * bx + by * by
    }
    val angle = atan2(cross, dot)
    val correlation = cos(angle) * dot + sin(angle) * cross
    val scale = (if (energyA > 1e-12) correlation / energyA else 1.0).coerceAtLeast(1e-6)
    val residual =
        if (energyB > 1e-12)
            sqrt(max(0.0, scale * scale * energyA - 2 * scale * correlation + energyB) / energyB)
        else 0.0
    return MorphSimilarity(angle, scale, residual)
}

private fun reorder(points: DoubleArray, reversed: Boolean, offset: Int): DoubleArray {
    val count = points.size / 2
    return DoubleArray(points.size) { i ->
        val point = (i / 2 + offset) % count
        points[2 * (if (reversed) count - 1 - point else point) + i % 2]
    }
}

private fun alignContours(a: MorphContour, b: MorphContour): Pair<MorphContour, MorphContour> {
    val ca = centroid(a.points)
    val cb = centroid(b.points)
    val varyA = a.closed && !b.closed
    val base = if (varyA) a else b
    val offsets = if (a.closed || b.closed) base.points.size / 2 else 1
    var best = base.points
    var bestScore = Double.POSITIVE_INFINITY
    for (reversed in listOf(false, true)) for (offset in 0 until offsets) {
        val candidate = reorder(base.points, reversed, offset)
        val fit =
            if (varyA) similarity(candidate, b.points, ca, cb)
            else similarity(a.points, candidate, ca, cb)
        val score = fit.residual + 0.05 * abs(fit.angle) / PI
        if (score < bestScore) {
            bestScore = score
            best = candidate
        }
    }
    return if (varyA) a.copy(points = best) to b else a to b.copy(points = best)
}

private fun contourLength(points: DoubleArray): Double {
    var result = 0.0
    for (i in 2 until points.size step 2) result +=
        hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1])
    return result
}

private fun pairContours(
    source: List<MorphContour>,
    target: List<MorphContour>,
): List<Pair<Int, Int>> {
    val reversed = source.size < target.size
    val large = if (reversed) target else source
    val small = if (reversed) source else target
    val costs = large.map { a ->
        val ca = centroid(a.points)
        val length = contourLength(a.points)
        small.map { b ->
            val cb = centroid(b.points)
            hypot(ca[0] - cb[0], ca[1] - cb[1]) + 0.35 * abs(length - contourLength(b.points))
        }
    }
    var best = IntArray(large.size)
    var bestCost = Double.POSITIVE_INFINITY
    val assignment = IntArray(large.size)
    val uses = IntArray(small.size)
    val exhaustive =
        if (large.size == small.size) large.size <= 8
        else small.size.toDouble().pow(large.size) <= 100_000
    if (exhaustive) {
        fun visit(index: Int, cost: Double, covered: Int) {
            if (cost >= bestCost || small.size - covered > large.size - index) return
            if (index == large.size) {
                bestCost = cost
                best = assignment.copyOf()
                return
            }
            for (j in small.indices) {
                if (large.size == small.size && uses[j] != 0) continue
                assignment[index] = j
                val first = uses[j]++ == 0
                visit(index + 1, cost + costs[index][j], covered + if (first) 1 else 0)
                uses[j]--
            }
        }
        visit(0, 0.0, 0)
    } else {
        if (large.size == small.size) {
            val candidates =
                costs
                    .flatMapIndexed { i, row -> row.mapIndexed { j, cost -> Triple(i, j, cost) } }
                    .sortedBy { it.third }
            best.fill(-1)
            for ((i, j) in candidates) if (best[i] == -1 && uses[j] == 0) {
                best[i] = j
                uses[j] = 1
            }
        } else {
            best = IntArray(large.size) { i -> small.indices.minBy { costs[i][it] } }
            for (j in best) uses[j]++
            for (j in small.indices) if (uses[j] == 0) {
                val donor =
                    large.indices
                        .filter { uses[best[it]] > 1 }
                        .minBy { costs[it][j] - costs[it][best[it]] }
                uses[best[donor]]--
                best[donor] = j
                uses[j]++
            }
        }
    }
    return best.mapIndexed { i, j -> if (reversed) j to i else i to j }
}

internal class MorphTransition(initial: List<MorphContour>) {
    private var target = initial
    private var plan: MorphPlan? = null
    var progress = 1.0
        private set

    var velocity = 0.0
        private set

    val running: Boolean
        get() = plan != null

    val frame: List<MorphContour>
        get() = plan?.interpolate(progress) ?: target

    fun retarget(next: List<MorphContour>, reducedMotion: Boolean = false) {
        if (reducedMotion) {
            target = next
            plan = null
            progress = 1.0
            velocity = 0.0
        } else {
            if (next === target) return
            val source = frame.map { it.copy(points = it.points.copyOf()) }
            target = next
            plan = MorphPlan(source, next)
            progress = 0.0
            velocity = velocity.coerceIn(-14.0, 14.0)
        }
    }

    fun advance(seconds: Double): Boolean {
        if (!running || seconds <= 0.0) return running
        val dt = seconds.coerceAtMost(1.0 / 30)
        val steps = max(1, ceil(dt * 240).toInt())
        val interval = dt / steps
        repeat(steps) {
            velocity += (420 * (1 - progress) - 30 * velocity) * interval
            progress += velocity * interval
        }
        if (abs(1 - progress) < 0.001 && abs(velocity) < 0.02) {
            plan = null
            progress = 1.0
            velocity = 0.0
        }
        return running
    }
}
