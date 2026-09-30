package app.podor.domain

import kotlin.test.*

class ToneCurveTest {
    @Test
    fun controlPointsStayOrderedBoundedAndEndpointsCannotBeDeleted() {
        val curve = ToneCurve().insert(CurvePoint(60, 80)).insert(CurvePoint(180, 160))
        assertEquals(CurvePoint(179, 255), curve.move(1, CurvePoint(220, 280)).points[1])
        assertEquals(CurvePoint(61, 0), curve.move(2, CurvePoint(0, -4)).points[2])
        assertEquals(curve, curve.remove(0))
        assertEquals(curve, curve.remove(3))
        assertEquals(3, curve.remove(1).points.size)
        assertEquals(CurvePoint(0, 20), curve.move(0, CurvePoint(20, 20)).points[0])
        assertFalse(
            ToneCurve(listOf(CurvePoint(0, 0), CurvePoint(0, 120), CurvePoint(255, 255))).valid()
        )
        var full = ToneCurve()
        for (x in 1..100) full = full.insert(CurvePoint(x, x))
        assertEquals(StudioDefaults.maxCurvePoints, full.points.size)
        assertTrue(full.valid())
    }

    @Test
    fun smoothCurveHitsEveryKnotAndNeverOvershootsEachSegment() {
        assertContentEquals(FloatArray(256) { it.toFloat() }, ToneCurve().samples())
        assertContentEquals(
            FloatArray(256) { 255f - it },
            ToneCurve(listOf(CurvePoint(0, 255), CurvePoint(255, 0))).samples(),
        )
        for (ys in
            listOf(listOf(0, 30, 210, 255), listOf(20, 200, 60, 160), listOf(80, 80, 80, 80))) {
            val points = listOf(0, 64, 192, 255).zip(ys) { x, y -> CurvePoint(x, y) }
            val values = ToneCurve(points).samples()
            for (p in points) assertEquals(p.y.toFloat(), values[p.x], 0.001f)
            for ((a, b) in points.zipWithNext()) for (x in a.x..b.x) {
                assertTrue(values[x] in minOf(a.y, b.y).toFloat()..maxOf(a.y, b.y).toFloat())
            }
        }
    }
}
