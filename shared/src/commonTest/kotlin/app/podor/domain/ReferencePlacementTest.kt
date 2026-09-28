package app.podor.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.*

class ReferencePlacementTest {
    @Test
    fun fittingPreservesAspectAndCentersInCustomCanvas() {
        val placement = ReferencePlacement.fit(400, 200, DocumentInfo(width = 1000, height = 800))
        assertEquals(Offset(500f, 400f), placement.bounds.center)
        assertEquals(2f, placement.bounds.width / placement.bounds.height)
        assertEquals(420f, placement.bounds.width, 0.01f)
    }

    @Test
    fun everyCornerKeepsItsOppositeFixedAndCannotFlipOrCollapse() {
        val before = ReferencePlacement(Rect(30f, 40f, 230f, 140f), mirrored = true)
        before.corners.forEachIndexed { index, corner ->
            val anchor = before.corners[(index + 2) % 4]
            val gesture = ReferenceGesture(before, corner, index)
            val scaled = gesture.update(anchor + (corner - anchor) * 2f)
            assertEquals(anchor, scaled.corners[(index + 2) % 4])
            assertEquals(400f, scaled.bounds.width)
            assertEquals(200f, scaled.bounds.height)
            assertTrue(scaled.mirrored)
            val crossed = gesture.update(anchor - (corner - anchor))
            assertTrue(crossed.bounds.width > 0 && crossed.bounds.height > 0)
            assertEquals(2f, crossed.bounds.width / crossed.bounds.height)
        }
    }
}
