package app.podor.presentation

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import app.podor.data.ProjectFiles
import app.podor.domain.*
import app.podor.engine.*
import kotlinx.coroutines.*

class ReferenceImage(
    val id: Long,
    val name: String,
    val bitmap: ImageBitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    placement: ReferencePlacement,
) {
    var placement by mutableStateOf(placement)
}

class ReferenceController(private val files: ProjectFiles, private val scope: CoroutineScope) {
    var images by mutableStateOf<List<ReferenceImage>>(emptyList())
        private set

    var selectedId by mutableStateOf<Long?>(null)
        private set

    val selected: ReferenceImage?
        get() = images.firstOrNull { it.id == selectedId }

    var visible by mutableStateOf(false)
    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var nextId = 0L
    private var job: Job? = null
    val canPaste
        get() = files.clipboard != null

    val canAdd
        get() = !loading && images.size < StudioDefaults.maxReferenceImages

    fun select(id: Long?) {
        if (id == null || images.any { it.id == id }) selectedId = id
    }

    fun remove() {
        val index = images.indexOfFirst { it.id == selectedId }
        if (index < 0) return
        images = images.filterIndexed { i, _ -> i != index }
        selectedId = images.getOrNull(index.coerceAtMost(images.lastIndex))?.id
    }

    fun load(paste: Boolean, document: DocumentInfo = DocumentInfo()) {
        visible = true
        if (!canAdd || (paste && !canPaste)) return
        loading = true
        error = null
        job = scope.launch {
            try {
                val source =
                    if (paste) {
                        val image = files.clipboard!!.read() ?: error("剪贴板中没有图片")
                        image.png to "剪贴板图片"
                    } else {
                        val image = files.openReference() ?: return@launch
                        image.bytes to (image.reference?.name ?: "参考图")
                    }
                val reference =
                    withContext(Dispatchers.Default) {
                        require(source.first.size <= StudioDefaults.maxClipboardBytes) { "参考图文件过大" }
                        val engine = createNativeEngine(1, 1)
                        val bytes =
                            try {
                                engine.call(EngineOperation.REFERENCE_IMAGE, source.first)
                            } finally {
                                engine.close()
                            }
                        fun int(offset: Int) =
                            (0..3).fold(0) { result, i ->
                                result or ((bytes[offset + i].toInt() and 255) shl (8 * i))
                            }
                        require(bytes.size >= 16) { "参考图数据无效" }
                        val width = int(8)
                        val height = int(12)
                        require(
                            width in 1..StudioDefaults.maxReferenceEdge &&
                                height in 1..StudioDefaults.maxReferenceEdge &&
                                bytes.size == 16 + width * height * 4
                        ) {
                            "参考图数据无效"
                        }
                        ReferenceImage(
                            ++nextId,
                            source.second,
                            rgbaBitmap(bytes, 16, width, height),
                            int(0),
                            int(4),
                            ReferencePlacement.fit(width, height, document),
                        )
                    }
                ensureActive()
                images = images + reference
                selectedId = reference.id
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "无法读取参考图"
            } finally {
                loading = false
            }
        }
    }

    fun cancelLoading() {
        job?.cancel()
    }

    fun clear() {
        cancelLoading()
        images = emptyList()
        selectedId = null
        error = null
    }

    fun clearError() {
        error = null
    }
}
