package app.podor.data

import app.podor.domain.AseGroup
import app.podor.domain.AsePalette
import app.podor.domain.AseSwatch
import app.podor.domain.AseSwatchType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class AsePaletteTest {
    private val redFixture =
        hex(
            "41534546000100000000000100010000001c00047ea2d83ddd8c0000524742203f80000000000000000000000000"
        )

    @Test
    fun independentBinaryFixturePreservesUtf16NameAndGlobalType() {
        val palette = AsePaletteCodec.decode(redFixture)
        assertEquals(
            AsePalette(listOf(AseSwatch("红🖌", 0xFFFF0000, AseSwatchType.Global))),
            palette,
        )
        assertContentEquals(redFixture, AsePaletteCodec.encode(palette))
    }

    @Test
    fun nestedEmptyAndRepeatedGroupsRetainTheirRangesOrderAndTypes() {
        val source =
            file(
                group("Flowers"),
                color("Red", "RGB ", floatArrayOf(1f, 0f, 0f), 0),
                group("Petals"),
                color("Blue", "RGB ", floatArrayOf(0f, 0f, 1f), 1),
                end(),
                group("Empty"),
                end(),
                end(),
                group("Flowers"),
                color("Green", "RGB ", floatArrayOf(0f, 1f, 0f), 2),
                end(),
                color("Black", "RGB ", floatArrayOf(0f, 0f, 0f), 2),
            )
        val palette = AsePaletteCodec.decode(source)
        assertEquals(
            listOf(
                AseGroup(listOf("Flowers"), 0, 2),
                AseGroup(listOf("Flowers", "Petals"), 1, 2),
                AseGroup(listOf("Flowers", "Empty"), 2, 2),
                AseGroup(listOf("Flowers"), 2, 3),
            ),
            palette.groups,
        )
        assertEquals(
            listOf(0xFFFF0000L, 0xFF0000FFL, 0xFF00FF00L, 0xFF000000L),
            palette.swatches.map { it.color },
        )
        assertEquals(
            listOf(
                AseSwatchType.Global,
                AseSwatchType.Spot,
                AseSwatchType.Process,
                AseSwatchType.Process,
            ),
            palette.swatches.map { it.type },
        )
        assertEquals(listOf("Flowers", "Petals"), palette.swatches[1].path)
        assertEquals(emptyList(), palette.swatches.last().path)
        assertEquals(palette, AsePaletteCodec.decode(AsePaletteCodec.encode(palette)))
        assertEquals(palette, Json.decodeFromString<AsePalette>(Json.encodeToString(palette)))
    }

    @Test
    fun convertsGrayDeviceCmykAndD50LabIntoSrgb() {
        val palette =
            AsePaletteCodec.decode(
                file(
                    color("Gray", "Gray", floatArrayOf(0.5f)),
                    color("CMYK", "CMYK", floatArrayOf(0.25f, 0.5f, 0.75f, 0.2f)),
                    color("Cyan", "CMYK", floatArrayOf(1f, 0f, 0f, 0f)),
                    color("Black", "CMYK", floatArrayOf(0f, 0f, 0f, 1f)),
                    color("Lab gray", "LAB ", floatArrayOf(0.5f, 0f, 0f)),
                    color("Lab white", "LAB ", floatArrayOf(1f, 0f, 0f)),
                    color("Lab black", "LAB ", floatArrayOf(0f, 0f, 0f)),
                    color("Lab red", "LAB ", floatArrayOf(0.5429054f, 80.80492f, 69.89099f)),
                )
            )
        assertEquals(
            listOf(
                0xFF808080L,
                0xFF996633L,
                0xFF00FFFFL,
                0xFF000000L,
                0xFF777777L,
                0xFFFFFFFFL,
                0xFF000000L,
                0xFFFF0000L,
            ),
            palette.swatches.map { it.color },
        )
        assertEquals(palette, AsePaletteCodec.decode(AsePaletteCodec.encode(palette)))
    }

    @Test
    fun generatedRgbPalettePreservesAllSlotsAndDerivesGroupsWithoutReordering() {
        val swatches =
            List(256) {
                AseSwatch(
                    "#$it",
                    0xFF000000L or (it.toLong() shl 16),
                    path = if (it % 3 == 0) listOf("A", "B") else emptyList(),
                )
            }
        val restored = AsePaletteCodec.decode(AsePaletteCodec.encode(AsePalette(swatches)))
        assertEquals(swatches, restored.swatches)
        assertEquals(
            AsePalette(listOf(AseSwatch("Same", 0xFF123456), AseSwatch("Same", 0xFF123456))),
            AsePaletteCodec.decode(
                AsePaletteCodec.encode(
                    AsePalette(listOf(AseSwatch("Same", 0xFF123456), AseSwatch("Same", 0xFF123456)))
                )
            ),
        )
        assertEquals(
            AsePalette(emptyList()),
            AsePaletteCodec.decode(AsePaletteCodec.encode(AsePalette(emptyList()))),
        )
    }

    @Test
    fun rejectsEveryTruncatedPrefixInvalidLengthsCountsVersionsAndTrailingData() {
        for (length in redFixture.indices) {
            assertFailsWith<IllegalArgumentException>("prefix $length") {
                AsePaletteCodec.decode(redFixture.copyOf(length))
            }
        }
        val failures =
            listOf(
                redFixture.copyOf().apply { this[0] = 0 },
                redFixture.copyOf().apply { this[5] = 2 },
                redFixture.copyOf().apply { this[7] = 1 },
                redFixture.copyOf().apply { this[11] = 0 },
                redFixture.copyOf().apply { this[11] = 2 },
                redFixture.copyOf().apply { for (i in 8..11) this[i] = 0xFF.toByte() },
                redFixture.copyOf().apply { for (i in 14..17) this[i] = 0xFF.toByte() },
                redFixture + byteArrayOf(0),
                file(
                    block(
                        1,
                        text("A") +
                            "RGB ".encodeToByteArray() +
                            floats(floatArrayOf(1f, 0f, 0f)) +
                            short(2) +
                            byteArrayOf(0),
                    )
                ),
                file(block(0xC002, byteArrayOf(0))),
            )
        failures.forEach {
            assertFailsWith<IllegalArgumentException> { AsePaletteCodec.decode(it) }
        }
    }

    @Test
    fun rejectsUnsupportedBlocksModelsTypesAndNonFiniteOrOutOfRangeChannels() {
        val failures =
            listOf(
                file(block(0x0002, byteArrayOf())),
                file(color("Unknown", "HSB ", floatArrayOf(0f, 0f, 0f))),
                file(color("Unknown", "RGB ", floatArrayOf(0f, 0f, 0f), 3)),
                file(color("Bad", "RGB ", floatArrayOf(Float.NaN, 0f, 0f))),
                file(color("Bad", "Gray", floatArrayOf(Float.POSITIVE_INFINITY))),
                file(color("Bad", "CMYK", floatArrayOf(0f, 0f, 0f, Float.NEGATIVE_INFINITY))),
                file(color("Bad", "RGB ", floatArrayOf(-0.001f, 0f, 0f))),
                file(color("Bad", "Gray", floatArrayOf(1.001f))),
                file(color("Bad", "CMYK", floatArrayOf(0f, 0f, 0f, 1.1f))),
                file(color("Bad", "LAB ", floatArrayOf(1.1f, 0f, 0f))),
                file(color("Bad", "LAB ", floatArrayOf(0.5f, -129f, 0f))),
                file(color("Bad", "LAB ", floatArrayOf(0.5f, 0f, 128f))),
                file(color("Bad", "LAB ", floatArrayOf(0.5f, Float.NaN, 0f))),
            )
        failures.forEach {
            assertFailsWith<IllegalArgumentException> { AsePaletteCodec.decode(it) }
        }
    }

    @Test
    fun utf16RequiresPairedSurrogatesAndExactlyOneFinalNull() {
        val suffix = "RGB ".encodeToByteArray() + floats(floatArrayOf(0f, 0f, 0f)) + short(2)
        for (name in
            listOf(
                short(0),
                short(2) + short(0xD800) + short(0),
                short(2) + short(0xDC00) + short(0),
                short(2) + short('A'.code) + short(1),
                short(3) + short(0) + short('A'.code) + short(0),
                short(65535),
            )) {
            assertFailsWith<IllegalArgumentException> {
                AsePaletteCodec.decode(file(block(1, name + suffix)))
            }
        }
        assertEquals(
            "",
            AsePaletteCodec.decode(file(color("", "Gray", floatArrayOf(0f))))
                .swatches
                .single()
                .name,
        )
        for (name in
            listOf("\uD800", "\uDC00", "A\u0000B", "A".repeat(AsePaletteCodec.maxNameUnits + 1))) {
            assertFailsWith<IllegalArgumentException> {
                AsePaletteCodec.encode(AsePalette(listOf(AseSwatch(name, 0xFF000000))))
            }
        }
    }

    @Test
    fun groupStructureRejectsUnmatchedDeepAndInconsistentRanges() {
        val nested = mutableListOf<ByteArray>()
        repeat(AsePaletteCodec.maxGroupDepth + 1) { nested.add(group("G")) }
        repeat(AsePaletteCodec.maxGroupDepth + 1) { nested.add(end()) }
        for (source in listOf(file(group("Open")), file(end()), file(*nested.toTypedArray()))) {
            assertFailsWith<IllegalArgumentException> { AsePaletteCodec.decode(source) }
        }
        val swatches =
            listOf(
                AseSwatch("R", 0xFFFF0000, path = listOf("A")),
                AseSwatch("B", 0xFF0000FF, path = listOf("B")),
            )
        for (groups in
            listOf(
                listOf(AseGroup(listOf("A"), -1, 1)),
                listOf(AseGroup(listOf("A"), 0, 3)),
                listOf(AseGroup(emptyList(), 0, 1)),
                listOf(AseGroup(listOf("A"), 0, 2), AseGroup(listOf("B"), 1, 2)),
                listOf(AseGroup(listOf("B"), 1, 2), AseGroup(listOf("A"), 0, 1)),
                listOf(AseGroup(listOf("Wrong"), 0, 2)),
            )) {
            assertFailsWith<IllegalArgumentException> {
                AsePaletteCodec.encode(AsePalette(swatches, groups))
            }
        }
        val empty =
            AsePalette(
                emptyList(),
                listOf(AseGroup(listOf("A"), 0, 0), AseGroup(listOf("A", "B"), 0, 0)),
            )
        assertEquals(empty, AsePaletteCodec.decode(AsePaletteCodec.encode(empty)))
    }

    @Test
    fun sizeLimitsRejectBeforeAllocatingFromDeclaredLengthsAndExportNeverDropsAlpha() {
        assertFailsWith<IllegalArgumentException> {
            AsePaletteCodec.decode(ByteArray(AsePaletteCodec.maxBytes + 1))
        }
        val color = color("R", "RGB ", floatArrayOf(1f, 0f, 0f))
        assertFailsWith<IllegalArgumentException> {
            AsePaletteCodec.decode(file(*Array(AsePaletteCodec.maxColors + 1) { color }))
        }
        assertFailsWith<IllegalArgumentException> {
            AsePaletteCodec.encode(
                AsePalette(List(AsePaletteCodec.maxColors + 1) { AseSwatch("R", 0xFFFF0000) })
            )
        }
        val groups =
            Array(AsePaletteCodec.maxGroups * 2 + 2) { if (it % 2 == 0) group("G") else end() }
        assertFailsWith<IllegalArgumentException> { AsePaletteCodec.decode(file(*groups)) }
        val large =
            AsePalette(
                emptyList(),
                List(AsePaletteCodec.maxGroups) {
                    AseGroup(listOf("G".repeat(AsePaletteCodec.maxNameUnits)), 0, 0)
                },
            )
        assertFailsWith<IllegalArgumentException> { AsePaletteCodec.encode(large) }
        for (value in listOf(-1L, 0x100000000L, 0x00123456L, 0x80123456L)) {
            assertFailsWith<IllegalArgumentException> {
                AsePaletteCodec.encode(AsePalette(listOf(AseSwatch("Alpha", value))))
            }
        }
    }

    @Test
    fun metadataValidationChecksTheSameStructureAndByteBudgetWithoutEncoding() {
        val swatches = listOf(AseSwatch("R", 0xFFFF0000, path = listOf("A")))
        val valid =
            AsePalette(
                swatches,
                listOf(AseGroup(listOf("A"), 0, 1), AseGroup(listOf("A", "Empty"), 1, 1)),
            )
        assertTrue(valid.valid())
        assertTrue(AsePaletteCodec.isValid(AsePalette(swatches)))
        assertTrue(AsePaletteCodec.decode(redFixture).valid())
        assertTrue(AsePalette(emptyList()).valid())
        val invalid =
            listOf(
                valid.copy(swatches = listOf(swatches.single().copy(color = 0x80FF0000))),
                valid.copy(swatches = listOf(swatches.single().copy(name = "\uD800"))),
                valid.copy(swatches = listOf(swatches.single().copy(path = listOf("Wrong")))),
                valid.copy(groups = listOf(AseGroup(listOf("A"), 0, 2))),
                valid.copy(groups = listOf(AseGroup(listOf("A", "Missing parent"), 0, 1))),
                valid.copy(groups = listOf(AseGroup(listOf("A"), 1, 1))),
                AsePalette(List(AsePaletteCodec.maxColors + 1) { AseSwatch("R", 0xFFFF0000) }),
                AsePalette(
                    emptyList(),
                    List(AsePaletteCodec.maxGroups) {
                        AseGroup(listOf("G".repeat(AsePaletteCodec.maxNameUnits)), 0, 0)
                    },
                ),
            )
        invalid.forEach {
            assertFalse(it.valid())
            assertFalse(AsePaletteCodec.isValid(it))
            assertFailsWith<IllegalArgumentException> { AsePaletteCodec.encode(it) }
        }
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun short(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun integer(value: Int) =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun text(value: String): ByteArray =
        short(value.length + 1) + value.flatMap { short(it.code).toList() }.toByteArray() + short(0)

    private fun floats(values: FloatArray) =
        values.flatMap { integer(it.toBits()).toList() }.toByteArray()

    private fun block(type: Int, bytes: ByteArray) = short(type) + integer(bytes.size) + bytes

    private fun color(name: String, model: String, values: FloatArray, type: Int = 2) =
        block(1, text(name) + model.encodeToByteArray() + floats(values) + short(type))

    private fun group(name: String) = block(0xC001, text(name))

    private fun end() = block(0xC002, byteArrayOf())

    private fun file(vararg blocks: ByteArray) =
        "ASEF".encodeToByteArray() +
            short(1) +
            short(0) +
            integer(blocks.size) +
            blocks.flatMap { it.toList() }.toByteArray()
}
