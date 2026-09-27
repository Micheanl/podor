package app.podor.engine

object NativeBridge {
    external fun create(width: Int, height: Int): Long

    external fun call(handle: Long, operation: Int, input: ByteArray): ByteArray

    external fun destroy(handle: Long)
}

actual fun createNativeEngine(width: Int, height: Int): NativeEngine {
    val handle = NativeBridge.create(width, height)
    check(handle != 0L) { "绘图引擎初始化失败" }
    return object : NativeEngine {
        private var closed = false

        override fun call(operation: Int, input: ByteArray): ByteArray {
            check(!closed) { "画布已关闭" }
            return NativeBridge.call(handle, operation, input)
        }

        override fun close() {
            if (!closed) {
                closed = true
                NativeBridge.destroy(handle)
            }
        }
    }
}
