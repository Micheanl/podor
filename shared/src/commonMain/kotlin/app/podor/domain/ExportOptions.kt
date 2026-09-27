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
}

@Serializable
data class ExportOptions(
    val format: ExportFormat = ExportFormat.Png,
    val transparent: Boolean = false,
    val quality: Int = StudioDefaults.exportQuality,
)
