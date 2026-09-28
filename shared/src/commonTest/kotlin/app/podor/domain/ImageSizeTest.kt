package app.podor.domain

import kotlin.test.*

class ImageSizeTest {
    @Test
    fun proportionsFollowEitherDimensionAndUnlockAllowsIndependentEdits() {
        val original = ImageSize(1600, 1200)
        assertTrue(original.locked)
        assertFalse(original.changed)
        assertEquals("600", original.withWidth("800").height)
        assertEquals("800", original.withHeight("600").width)
        val independent = original.withLock(false).withWidth("800").withHeight("300")
        assertEquals("800", independent.width)
        assertEquals("300", independent.height)
        assertEquals("600", independent.withLock(true).height)
        assertTrue(independent.valid)
        assertEquals("1", ImageSize(8192, 1).withWidth("1").height)
        assertEquals("1", ImageSize(1, 8192).withHeight("1").width)
    }

    @Test
    fun emptyInvalidAndOversizedValuesCannotBeApplied() {
        val original = ImageSize(1600, 1200)
        assertFalse(original.withWidth("").valid)
        assertEquals("", original.withWidth("").height)
        assertFalse(original.withHeight("0").valid)
        assertFalse(original.withWidth("99999").valid)
        assertFalse(original.withLock(false).withWidth("8192").withHeight("8192").valid)
        assertEquals("128", original.withWidth("12x8").width)
        assertTrue(original.withWidth("").withHeight("600").valid)
        assertTrue(original.scaled(50).valid)
        assertEquals("800", original.scaled(50).width)
        assertEquals("600", original.scaled(50).height)
        assertFalse(ImageSize(4096, 4096).scaled(200).valid)
        assertFalse(original.scaled(100).changed)
    }
}
