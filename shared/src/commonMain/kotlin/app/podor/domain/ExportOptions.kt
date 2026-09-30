package app.podor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ExportFormat(
    val label: String,
    val extension: String,
    val supportsTransparency: Boolean,
    val preservesLayers: Boolean = false,
) {
    @SerialName("png") Png("PNG", "png", true),
    @SerialName("jpeg") Jpeg("JPEG", "jpg", false),
    @SerialName("webp") Webp("WebP", "webp", true),
    @SerialName("ora") Ora("ORA", "ora", true, true),
    @SerialName("tiff") Tiff("TIFF", "tiff", true),
    @SerialName("bmp") Bmp("BMP", "bmp", true),
    @SerialName("psd") Psd("PSD", "psd", true, true),
    @SerialName("indexed_png") IndexedPng("Indexed PNG", "png", true),
    @SerialName("svg") Svg("SVG", "svg", true),
}

@Serializable
enum class IndexedExportPolicy {
    @SerialName("exact") Exact,
    @SerialName("quantize") Quantize,
}

@Serializable
data class ExportOptions(
    val format: ExportFormat = ExportFormat.Png,
    val transparent: Boolean = false,
    val quality: Int = StudioDefaults.exportQuality,
    @SerialName("indexed_policy")
    val indexedPolicy: IndexedExportPolicy = IndexedExportPolicy.Exact,
    @SerialName("bake_layers") val bakeLayers: Boolean = false,
    @SerialName("frame_id") val frameId: Int? = null,
)

fun DocumentInfo.requiresBakedExport(format: ExportFormat): Boolean =
    when (format) {
        ExportFormat.Psd ->
            layers.any {
                it.kind == LayerKind.Adjustment || it.kind == LayerKind.Vector || it.masks.size > 1
            }
        ExportFormat.Ora ->
            layers.any {
                it.kind == LayerKind.Adjustment ||
                    it.kind == LayerKind.Vector ||
                    it.maskEntries.isNotEmpty() ||
                    it.clipping
            }
        else -> false
    }
