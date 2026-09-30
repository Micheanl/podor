package app.podor.domain

import kotlin.test.*

class AppVersionTest {
    @Test
    fun numericVersionsDoNotConfuseTenWithOneAndRejectUnstableInput() {
        assertTrue(AppVersion.parse("0.10.0") > AppVersion.parse("0.2.9"))
        assertTrue(AppVersion.parse("1.0.0") > AppVersion.parse("0.99.99"))
        assertEquals(0, AppVersion.parse("0.2.0").compareTo(AppVersion.parse("0.2.0")))
        for (input in
            listOf("01.0.0", "1.0.0-beta", "v1.0.0", "../1.0.0", "999999999999999999999.0.0")) {
            assertFailsWith<IllegalArgumentException> { AppVersion.parse(input) }
        }
    }
}
