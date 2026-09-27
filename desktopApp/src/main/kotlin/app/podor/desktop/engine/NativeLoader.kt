package app.podor.desktop.engine

import java.nio.file.Files

object NativeLoader {
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val library = System.mapLibraryName("podor_engine")
        val stream =
            requireNotNull(javaClass.getResourceAsStream("/native/$library")) {
                "缺少 Rust 绘图引擎，请先运行 :desktopApp:buildEngine"
            }
        val directory = Files.createTempDirectory("podor-native-")
        val path = directory.resolve(library)
        directory.toFile().deleteOnExit()
        path.toFile().deleteOnExit()
        stream.use { Files.copy(it, path) }
        System.load(path.toAbsolutePath().toString())
        loaded = true
    }
}
