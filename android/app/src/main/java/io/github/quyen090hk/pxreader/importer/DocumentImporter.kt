package io.github.quyen090hk.pxreader.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.quyen090hk.pxreader.data.DocumentFormat
import io.github.quyen090hk.pxreader.data.ReaderRepository
import io.github.quyen090hk.pxreader.data.db.DocumentEntity
import io.github.quyen090hk.pxreader.data.db.PxReaderDao
import io.github.quyen090hk.pxreader.reader.DocumentReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface ImportOutcome {
    data class Imported(val document: DocumentEntity) : ImportOutcome
    data class Duplicate(val document: DocumentEntity) : ImportOutcome
    data class Rejected(val reason: String) : ImportOutcome
}

class DocumentImporter(
    private val context: Context,
    private val dao: PxReaderDao,
    private val repository: ReaderRepository,
) {
    private val documentsDirectory = File(context.filesDir, "documents").apply { mkdirs() }
    private val coversDirectory = File(context.filesDir, "covers").apply { mkdirs() }
    private val stagingDirectory = File(context.cacheDir, "import-staging").apply { mkdirs() }
    private val reader = DocumentReader(documentsDirectory)

    suspend fun import(uri: Uri, sourcePath: String? = null): ImportOutcome = withContext(Dispatchers.IO) {
        val source = sourceInfo(uri)
        val format = identifyFormat(source.name, source.mimeType)
            ?: return@withContext ImportOutcome.Rejected("仅支持 TXT 或 EPUB 文件。")
        val temporary = File.createTempFile("pxreader-", ".partial", stagingDirectory)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            openInput(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        bytes += read
                        if (bytes > MAX_DOCUMENT_BYTES) error("文件超过 128 MB 的移动端首版上限。")
                        hash.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return@withContext ImportOutcome.Rejected("无法读取此文件。")
            if (bytes == 0L) return@withContext ImportOutcome.Rejected("不能导入空文件。")

            val documentId = "sha256:${hash.digest().joinToString("") { "%02x".format(it) }}"
            dao.document(documentId)?.let { return@withContext ImportOutcome.Duplicate(it) }

            val metadata = reader.inspect(temporary, format.name.lowercase())
            val destinationName = "${documentId.removePrefix("sha256:")}.${format.extension}"
            val destination = File(documentsDirectory, destinationName)
            val coverFileName = reader.extractCover(temporary, format.name.lowercase())?.let { cover ->
                val name = "${documentId.removePrefix("sha256:")}.${cover.extension}"
                FileOutputStream(File(coversDirectory, name)).use { output -> output.write(cover.bytes) }
                name
            }
            promote(temporary, destination)
            val now = System.currentTimeMillis()
            val document = DocumentEntity(
                id = documentId,
                format = format.name.lowercase(),
                title = metadata.title.ifBlank { source.name.substringBeforeLast('.') },
                author = metadata.author,
                originalFileName = source.name,
                sourceUri = uri.toString(),
                sourcePath = sourcePath ?: uri.path?.takeIf { uri.scheme == ContentResolver.SCHEME_FILE },
                storedFileName = destinationName,
                coverFileName = coverFileName,
                byteSize = bytes,
                contentHash = documentId,
                tagsJson = JSONArray().toString(),
                chapterCount = metadata.chapterCount,
                addedAt = now,
                updatedAt = now,
                lastOpenedAt = null,
            )
            dao.upsertDocument(document)
            repository.index(document)
            ImportOutcome.Imported(document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ImportOutcome.Rejected(error.message ?: "导入失败：文件可能已损坏。")
        } finally {
            temporary.delete()
        }
    }

    private fun sourceInfo(uri: Uri): SourceInfo {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(requireNotNull(uri.path) { "文件路径缺失。" })
            return SourceInfo(file.name, MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()))
        }
        val resolver = context.contentResolver
        var name: String? = null
        runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) }.getOrNull()?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
        return SourceInfo(name ?: "未命名文件", resolver.getType(uri))
    }

    private fun openInput(uri: Uri) = if (uri.scheme == ContentResolver.SCHEME_FILE) {
        FileInputStream(File(requireNotNull(uri.path) { "文件路径缺失。" }))
    } else {
        context.contentResolver.openInputStream(uri)
    }

    private fun identifyFormat(name: String, mime: String?): DocumentFormat? = when {
        name.endsWith(".txt", true) || mime.equals("text/plain", true) -> DocumentFormat.TXT
        name.endsWith(".epub", true) || mime.equals("application/epub+zip", true) -> DocumentFormat.EPUB
        else -> null
    }

    private fun promote(from: File, to: File) {
        if (to.exists()) return
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = false)
            from.delete()
        }
    }

    private data class SourceInfo(val name: String, val mimeType: String?)

    private companion object {
        const val MAX_DOCUMENT_BYTES = 128L * 1024L * 1024L
    }
}
