package app.podor.data

import app.podor.domain.AseGroup
import app.podor.domain.AsePalette
import app.podor.domain.AseSwatch
import app.podor.domain.AseSwatchType
import app.podor.domain.StudioDefaults
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextPaletteTest {
    @Test
    fun independentGplFixtureRetainsUtf8NamesHeaderColumnsAndDuplicateSlots() {
        val source =
            "GIMP Palette\nName:   花与光 🖌  \nColumns: 12\n# comment\n\n255 0 0   玫瑰 🥀  \n255 0 0 Second rose\n0\t128\t255\n"
        val decoded = TextPaletteCodec.decode(source.encodeToByteArray())
        assertEquals(TextPaletteFormat.Gpl, decoded.format)
        assertEquals("花与光 🖌", decoded.name)
        assertEquals(12, decoded.columns)
        assertEquals(
            listOf(
                AseSwatch("玫瑰 🥀", 0xFFFF0000),
                AseSwatch("Second rose", 0xFFFF0000),
                AseSwatch("", 0xFF0080FF),
            ),
            decoded.palette.swatches,
        )
        assertEquals(decoded, TextPaletteCodec.decode(TextPaletteCodec.encode(decoded)))
    }

    @Test
    fun gplEncoderWritesIndependentLfFixtureWithoutChangingNames() {
        val source =
            TextPaletteFile(
                AsePalette(listOf(AseSwatch("Red #1", 0xFFFF0000), AseSwatch("", 0xFF000000))),
                TextPaletteFormat.Gpl,
                "Studio",
                2,
            )
        val expected = "GIMP Palette\nName: Studio\nColumns: 2\n#\n255 0 0 Red #1\n0 0 0\n"
        assertContentEquals(expected.encodeToByteArray(), TextPaletteCodec.encode(source))
    }

    @Test
    fun oldGplUsesExplicitFilenameFallbackAndDoesNotConsumeFirstColor() {
        val decoded =
            TextPaletteCodec.decode(
                "GIMP Palette\r\n12 34 56 First\r\n# 次行\r\n12 34 56 Second".encodeToByteArray(),
                fallbackName = "Legacy",
            )
        assertEquals("Legacy", decoded.name)
        assertEquals(0, decoded.columns)
        assertEquals(listOf("First", "Second"), decoded.palette.swatches.map { it.name })
        assertEquals(listOf(0xFF0C2238L, 0xFF0C2238L), decoded.palette.swatches.map { it.color })
        val withoutColumns =
            TextPaletteCodec.decode("GIMP Palette\nName: Modern\n1 2 3".encodeToByteArray())
        assertEquals(AseSwatch("", 0xFF010203), withoutColumns.palette.swatches.single())
    }

    @Test
    fun independentJascFixtureIsColorOnlyAndExportsCrLf() {
        val fixture =
            "JASC-PAL\r\n0100\r\n3\r\n255 0 0\r\n255 0 0\r\n0 128 255\r\n".encodeToByteArray()
        val decoded = TextPaletteCodec.decode(fixture)
        assertEquals(TextPaletteFormat.JascPal, decoded.format)
        assertEquals(
            AsePalette(
                listOf(
                    AseSwatch("", 0xFFFF0000),
                    AseSwatch("", 0xFFFF0000),
                    AseSwatch("", 0xFF0080FF),
                )
            ),
            decoded.palette,
        )
        assertContentEquals(fixture, TextPaletteCodec.encode(decoded))
        assertEquals(
            decoded,
            TextPaletteCodec.decode(
                "JASC-PAL\n0100\n3\n255 0 0\n255 0 0\n0\t128\t255".encodeToByteArray()
            ),
        )
    }

    @Test
    fun unrepresentableTypesGroupsAndNamesRequireExplicitFlattening() {
        val source =
            AsePalette(
                listOf(
                    AseSwatch("Rose", 0xFFFF0000, AseSwatchType.Spot, listOf("Flowers")),
                    AseSwatch("Rose", 0xFFFF0000, AseSwatchType.Global),
                ),
                listOf(AseGroup(listOf("Flowers"), 0, 1), AseGroup(listOf("Empty"), 1, 1)),
            )
        for (format in TextPaletteFormat.entries) {
            assertTrue(TextPaletteCodec.requiresFlatten(source, format))
            assertFailsWith<IllegalArgumentException> { TextPaletteCodec.encode(source, format) }
            val decoded =
                TextPaletteCodec.decode(
                    TextPaletteCodec.encode(source, format, flattenMetadata = true)
                )
            assertEquals(
                source.swatches.map { it.color },
                decoded.palette.swatches.map { it.color },
            )
            assertTrue(decoded.palette.groups.isEmpty())
            assertTrue(
                decoded.palette.swatches.all {
                    it.type == AseSwatchType.Process && it.path.isEmpty()
                }
            )
            assertEquals(
                if (format == TextPaletteFormat.Gpl) listOf("Rose", "Rose") else listOf("", ""),
                decoded.palette.swatches.map { it.name },
            )
        }
        val named = AsePalette(listOf(AseSwatch("#FF0000", 0xFFFF0000)))
        assertFalse(TextPaletteCodec.requiresFlatten(named, TextPaletteFormat.Gpl))
        assertTrue(TextPaletteCodec.requiresFlatten(named, TextPaletteFormat.JascPal))
        assertFailsWith<IllegalArgumentException> {
            TextPaletteCodec.encode(named, TextPaletteFormat.JascPal)
        }
        assertEquals(
            AsePalette(listOf(AseSwatch("", 0xFFFF0000))),
            TextPaletteCodec.decode(TextPaletteCodec.encode(named, TextPaletteFormat.JascPal, true))
                .palette,
        )
    }

    @Test
    fun emptyGroupAndFileMetadataLossCannotPassStrictExport() {
        val emptyGroup = AsePalette(emptyList(), listOf(AseGroup(listOf("Empty"), 0, 0)))
        assertFailsWith<IllegalArgumentException> {
            TextPaletteCodec.encode(emptyGroup, TextPaletteFormat.Gpl)
        }
        val file = TextPaletteFile(AsePalette(emptyList()), TextPaletteFormat.JascPal, "Named", 3)
        assertTrue(TextPaletteCodec.requiresFlatten(file))
        assertFailsWith<IllegalArgumentException> { TextPaletteCodec.encode(file) }
        assertContentEquals(
            "JASC-PAL\r\n0100\r\n0\r\n".encodeToByteArray(),
            TextPaletteCodec.encode(file, true),
        )
    }

    @Test
    fun transparentAndOutOfRangeArgbNeverExportEvenWithFlattening() {
        for (color in listOf(0L, 0x80FFFFFFL, -1L, 0x100000000L)) {
            for (format in TextPaletteFormat.entries) {
                assertFailsWith<IllegalArgumentException> {
                    TextPaletteCodec.encode(AsePalette(listOf(AseSwatch("", color))), format, true)
                }
            }
        }
    }

    @Test
    fun malformedIntegersDoNotOverflowOrClamp() {
        for (rgb in
            listOf(
                "-1 0 0",
                "+1 0 0",
                "256 0 0",
                "0 0 999999999999999999999999999",
                "1.0 0 0",
                "NaN 0 0",
                "0xFF 0 0",
                "1 2",
                "1 2 3x",
                "1,2,3",
                " ",
            )) {
            reject("GIMP Palette\n$rgb\n")
            reject("JASC-PAL\n0100\n1\n$rgb\n")
        }
        reject("JASC-PAL\n0100\n1\n1 2 3 Extra\n")
        for (count in
            listOf(
                "-1",
                "+1",
                "257",
                "999999999999999999999999999",
                "1.0",
                "NaN",
                "",
                " 1",
            )) reject("JASC-PAL\n0100\n$count\n")
        for (columns in listOf("-1", "256", "999999999999999999999999999", "NaN")) reject(
            "GIMP Palette\nName: Test\nColumns: $columns\n"
        )
        assertEquals(
            255,
            TextPaletteCodec.decode(
                    "GIMP Palette\nName: Test\nColumns: 255\n000255 000 0".encodeToByteArray()
                )
                .columns,
        )
    }

    @Test
    fun wrongHeadersTruncatedRecordsAndCountMismatchRejectAtomically() {
        for (source in
            listOf(
                "",
                "GIMP Palette trailing\n",
                "\uFEFFGIMP Palette\n",
                "JASC-PAL\n",
                "JASC-PAL\n0101\n0\n",
                "JASC-PAL\n0100\n",
                "JASC-PAL\n0100\n2\n1 2 3\n",
                "JASC-PAL\n0100\n0\n1 2 3\n",
                "JASC-PAL\n0100\n1\n1 2 3\n\n",
                "GIMP Palette\n1 2\n",
                "GIMP Palette\nName: Test\nColumns: 1\n1 2 3\nColumns: 2\n",
            )) {
            val bytes = source.encodeToByteArray()
            val before = bytes.copyOf()
            assertFailsWith<IllegalArgumentException> { TextPaletteCodec.decode(bytes) }
            assertContentEquals(before, bytes)
        }
    }

    @Test
    fun invalidUtf8AsciiNulAndBareCarriageReturnReject() {
        val prefix = "GIMP Palette\n# ".encodeToByteArray()
        assertFailsWith<IllegalArgumentException> {
            TextPaletteCodec.decode(prefix + byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertFailsWith<IllegalArgumentException> {
            TextPaletteCodec.decode(
                "GIMP Palette\n1 2 3 ".encodeToByteArray() +
                    byteArrayOf(0xF0.toByte(), 0x9F.toByte())
            )
        }
        reject("GIMP Palette\n1 2 3 bad\u0000name\n")
        reject("JASC-PAL\n0100\n1\n1 2 3 名\n")
        reject("GIMP Palette\n1 2 3\r4 5 6\n")
        reject("GIMP Palette\n1 2 3\r")
    }

    @Test
    fun exactColorAndFileLimitsAreInclusiveAndDoNotDeduplicate() {
        val swatches = List(StudioDefaults.maxPaletteColors) { AseSwatch("", 0xFF123456) }
        for (format in TextPaletteFormat.entries) {
            val palette = AsePalette(swatches)
            assertEquals(
                palette,
                TextPaletteCodec.decode(TextPaletteCodec.encode(palette, format)).palette,
            )
            assertFailsWith<IllegalArgumentException> {
                TextPaletteCodec.encode(AsePalette(swatches + swatches.first()), format)
            }
        }
        reject("GIMP Palette\n" + "1 2 3\n".repeat(StudioDefaults.maxPaletteColors + 1))
        val prefix = "GIMP Palette\n#".encodeToByteArray()
        val maximum =
            prefix + ByteArray(TextPaletteCodec.maxBytes - prefix.size) { 'x'.code.toByte() }
        assertTrue(TextPaletteCodec.decode(maximum).palette.swatches.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            TextPaletteCodec.decode(maximum + byteArrayOf(10))
        }
    }

    @Test
    fun namesAreBoundedValidUnicodeAndNeverSilentlyTrimmedOnExport() {
        val boundary = "花".repeat(StudioDefaults.maxPaletteNameUnits)
        val file =
            TextPaletteFile(
                AsePalette(listOf(AseSwatch(boundary, 0xFF123456))),
                TextPaletteFormat.Gpl,
                boundary,
            )
        assertEquals(file, TextPaletteCodec.decode(TextPaletteCodec.encode(file)))
        reject("GIMP Palette\nName: ${boundary}花\n")
        reject("GIMP Palette\n1 2 3 ${boundary}花\n")
        for (name in
            listOf(
                " Leading",
                "Trailing ",
                "\tTab",
                "Line\nBreak",
                "Line\rBreak",
                "Nul\u0000",
                "\uD800",
                "\uDC00",
            )) {
            assertFailsWith<IllegalArgumentException> {
                TextPaletteCodec.encode(
                    AsePalette(listOf(AseSwatch(name, 0xFF123456))),
                    TextPaletteFormat.Gpl,
                    true,
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            TextPaletteCodec.decode("GIMP Palette\n".encodeToByteArray(), "\uD800")
        }
        assertFailsWith<IllegalArgumentException> {
            TextPaletteCodec.encode(file.copy(columns = 256), true)
        }
    }

    private fun reject(source: String) {
        assertFailsWith<IllegalArgumentException> {
            TextPaletteCodec.decode(source.encodeToByteArray())
        }
    }
}
