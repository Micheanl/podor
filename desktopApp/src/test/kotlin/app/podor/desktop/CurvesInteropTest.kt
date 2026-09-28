package app.podor.desktop

import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.serialization.json.*

class CurvesInteropTest {
    @Test
    fun editorCurveSamplesMatchNativeLookupForAllChannelsAndMaximumPoints() {
        NativeLoader.load()
        val image = BufferedImage(256, 1, BufferedImage.TYPE_INT_ARGB)
        for (x in 0..255) image.setRGB(x, 0, 0xFF000000.toInt() or (x shl 16) or (x shl 8) or x)
        val source = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val shapes =
            listOf(
                ToneCurve(),
                ToneCurve(listOf(CurvePoint(0, 255), CurvePoint(255, 0))),
                ToneCurve(
                    listOf(
                        CurvePoint(0, 10),
                        CurvePoint(64, 35),
                        CurvePoint(192, 210),
                        CurvePoint(255, 240),
                    )
                ),
                ToneCurve(
                    (0 until StudioDefaults.maxCurvePoints).map {
                        CurvePoint(it * 17, if (it % 2 == 0) 30 else 220)
                    }
                ),
            )
        for (shape in shapes) {
            val engine = createNativeEngine(1, 1)
            try {
                val state =
                    Json.parseToJsonElement(
                            engine.call(EngineOperation.LOAD, source).decodeToString()
                        )
                        .jsonObject
                val curves = ColorCurves(shape, shapes[2], shapes[1], shapes[3])
                val settings =
                    AdjustmentSettings.defaults(AdjustmentKind.Curves).copy(curves = curves)
                val request = buildJsonObject {
                    put("id", state.getValue("active"))
                    put("revision", state.getValue("revision"))
                    put("settings", Json.encodeToJsonElement(settings))
                }
                val command = buildJsonObject {
                    put("type", "apply_adjustment")
                    put("request", request)
                }
                    .toString()
                    .encodeToByteArray()
                assertTrue(command.size <= 4096)
                engine.call(EngineOperation.COMMAND, command)
                val output =
                    engine.call(
                        EngineOperation.EXPORT_IMAGE,
                        """{"format":"png","transparent":true}""".encodeToByteArray(),
                    )
                val actual = ImageIO.read(output.inputStream())
                val master = curves.rgb.samples()
                for ((c, channel) in
                    listOf(CurveChannel.Red, CurveChannel.Green, CurveChannel.Blue).withIndex()) {
                    val table = curves[channel].samples()
                    for (x in 0..255) {
                        val expected = table[master[x].roundToInt()].roundToInt()
                        val value = actual.getRGB(x, 0) shr ((2 - c) * 8) and 255
                        assertTrue(abs(expected - value) <= 1, "$channel at $x: $expected / $value")
                    }
                }
                assertFails { engine.call(EngineOperation.CURVE_HISTOGRAM, byteArrayOf(1)) }
            } finally {
                engine.close()
            }
        }
    }
}
