@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.podor.platform

import app.podor.data.ProjectFiles
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject

class IosFiles(private val host: () -> UIViewController) : ProjectFiles {
    private val directory =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first()
            as String
    private val recovery = "$directory/recovery.podor"

    override suspend fun readPreferences(): ByteArray? =
        withContext(Dispatchers.Default) {
            NSData.dataWithContentsOfFile("$directory/preferences.json")?.toBytes()
        }

    override suspend fun writePreferences(bytes: ByteArray) =
        withContext(Dispatchers.Default) { write("$directory/preferences.json", bytes) }

    override suspend fun saveBrushPack(bytes: ByteArray) =
        share(bytes, "brushes.podor-brushes.json")

    private var delegate: UIDocumentPickerDelegateProtocol? = null

    override suspend fun preserveRecovery(bytes: ByteArray) =
        withContext(Dispatchers.Default) {
            write(
                "$directory/recovery-damaged-${NSDate().timeIntervalSince1970.toLong()}.podor",
                bytes,
            )
        }

    override suspend fun open(): ByteArray? =
        withContext(Dispatchers.Main) {
            val result = CompletableDeferred<NSURL?>()
            val picker =
                UIDocumentPickerViewController(
                    documentTypes = listOf("public.data"),
                    inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
                )
            delegate =
                object : NSObject(), UIDocumentPickerDelegateProtocol {
                    override fun documentPicker(
                        controller: UIDocumentPickerViewController,
                        didPickDocumentsAtURLs: List<*>,
                    ) {
                        result.complete(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
                    }

                    override fun documentPickerWasCancelled(
                        controller: UIDocumentPickerViewController
                    ) {
                        result.complete(null)
                    }
                }
            picker.delegate = delegate
            try {
                host().presentViewController(picker, true, null)
                val url = result.await() ?: return@withContext null
                withContext(Dispatchers.Default) { NSData.dataWithContentsOfURL(url)?.toBytes() }
            } finally {
                delegate = null
            }
        }

    override suspend fun save(bytes: ByteArray, png: Boolean): Boolean =
        share(bytes, "作品.${if(png) "png" else "podor"}")

    private suspend fun share(bytes: ByteArray, name: String): Boolean {
        val path = "$directory/$name"
        withContext(Dispatchers.Default) { write(path, bytes) }
        return withContext(Dispatchers.Main) {
            val result = CompletableDeferred<Boolean>()
            val sheet = UIActivityViewController(listOf(NSURL.fileURLWithPath(path)), null)
            sheet.completionWithItemsHandler = { _, completed, _, _ -> result.complete(completed) }
            sheet.popoverPresentationController?.sourceView = host().view
            host().presentViewController(sheet, true, null)
            result.await()
        }
    }

    override suspend fun readRecovery(): ByteArray? =
        withContext(Dispatchers.Default) { NSData.dataWithContentsOfFile(recovery)?.toBytes() }

    override suspend fun writeRecovery(bytes: ByteArray) =
        withContext(Dispatchers.Default) { write(recovery, bytes) }

    private fun write(path: String, bytes: ByteArray) {
        val data = bytes.usePinned {
            NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
        }
        check(data.writeToFile(path, true)) { "工程保存失败" }
    }

    private fun NSData.toBytes(): ByteArray {
        require(length <= 256uL * 1024u * 1024u) { "工程文件过大" }
        return bytes?.readBytes(length.toInt()) ?: byteArrayOf()
    }
}
