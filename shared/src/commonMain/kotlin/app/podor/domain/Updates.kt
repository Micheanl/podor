package app.podor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublishedRelease(
    @SerialName("tag_name") val tag: String,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    companion object {
        fun parse(value: String): AppVersion {
            require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(value))
            val parts = value.split('.').map(String::toInt)
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}

@Serializable
data class AppRelease(
    val version: String,
    val notes: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val platform: String,
) {
    fun validate() {
        AppVersion.parse(version)
        require(platform == "windows-x64")
        require(size in 1..maxDownloadBytes)
        require(Regex("[a-fA-F0-9]{64}").matches(sha256))
        require(notes.length <= 16_384)
    }

    companion object {
        const val maxDownloadBytes = 512L * 1024 * 1024
    }
}

enum class UpdatePhase {
    Idle,
    Checking,
    Current,
    Available,
    Downloading,
    Downloaded,
    Failed,
}

enum class UpdateProblem(val label: String) {
    Connection("连接失败，请稍后重试"),
    Manifest("更新信息无效"),
    Integrity("安装包校验失败，请重新下载"),
    Storage("无法保存安装包，请检查磁盘空间"),
    Unconfigured("更新通道尚未配置"),
}

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val release: AppRelease? = null,
    val received: Long = 0,
    val installer: String? = null,
    val problem: UpdateProblem? = null,
)
