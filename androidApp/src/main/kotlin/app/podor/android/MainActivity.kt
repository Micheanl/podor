package app.podor.android

import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.podor.data.ProjectFiles
import app.podor.domain.AppIdentity
import app.podor.presentation.StudioController
import app.podor.ui.PodorApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var openResult: CompletableDeferred<Uri?>? = null
    private var saveResult: CompletableDeferred<Uri?>? = null
    private val openPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            openResult?.complete(it)
        }
    private val projectPicker =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) {
            saveResult?.complete(it)
        }
    private val pngPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
            saveResult?.complete(it)
        }
    private val brushPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
            saveResult?.complete(it)
        }
    private lateinit var controller: StudioController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("podor_engine")
        enableEdgeToEdge()
        controller = StudioController(AndroidFiles(), lifecycleScope)
        setContent { PodorApp(controller) }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }

    private inner class AndroidFiles : ProjectFiles {
        private val preferences = AtomicFile(File(filesDir, "preferences.json"))

        override suspend fun readPreferences(): ByteArray? =
            withContext(Dispatchers.IO) {
                if (preferences.baseFile.exists() && preferences.baseFile.length() <= 1024 * 1024)
                    preferences.readFully()
                else null
            }

        override suspend fun writePreferences(bytes: ByteArray) = writeAtomic(preferences, bytes)

        override suspend fun saveBrushPack(bytes: ByteArray) = saveDocument(bytes, false, true)

        override suspend fun open(): ByteArray? {
            val uri =
                withContext(Dispatchers.Main) {
                    val result = CompletableDeferred<Uri?>()
                    openResult = result
                    try {
                        openPicker.launch(arrayOf("application/octet-stream", "*/*"))
                        result.await()
                    } finally {
                        openResult = null
                    }
                } ?: return null
            return withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input ->
                    readBounded(input)
                }
            }
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean =
            saveDocument(bytes, png, false)

        private suspend fun saveDocument(
            bytes: ByteArray,
            png: Boolean,
            brushes: Boolean,
        ): Boolean {
            val uri =
                withContext(Dispatchers.Main) {
                    val result = CompletableDeferred<Uri?>()
                    saveResult = result
                    try {
                        if (brushes) brushPicker.launch("brushes.podor-brushes.json")
                        else if (png) pngPicker.launch("作品.png")
                        else projectPicker.launch("作品.${AppIdentity.projectExtension}")
                        result.await()
                    } finally {
                        saveResult = null
                    }
                } ?: return false
            withContext(Dispatchers.IO) {
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
            }
            return true
        }

        private suspend fun writeAtomic(file: AtomicFile, bytes: ByteArray) =
            withContext(Dispatchers.IO) {
                val stream = file.startWrite()
                try {
                    stream.write(bytes)
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
            }

    }

    companion object {
        private const val MAX_BYTES = 256 * 1024 * 1024

        private fun readBounded(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "工程文件过大" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
