package io.github.quyen090hk.pxreader.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.quyen090hk.pxreader.data.DocumentFormat
import io.github.quyen090hk.pxreader.data.db.DocumentEntity
import io.github.quyen090hk.pxreader.data.db.PxReaderDao
import io.github.quyen090hk.pxreader.data.db.SearchUnitEntity
import io.github.quyen090hk.pxreader.reader.DocumentReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    private val documentsDirectory = File(context.filesDir, "documents").apply { mkdirs() }
    private val coversDirectory = File(context.filesDir, "covers").apply { mkdirs() }
    private val stagingDirectory = File(context.cacheDir, "import-staging").apply { mkdirs() }
    private val reader = DocumentReader(documentsDirectory)
    private val finalizeMutex = Mutex()

    suspend fun import(uri: Uri, sourcePath: String? = null, initialTags: Set<String> = emptySet()): ImportOutcome = withContext(Dispatchers.IO) {
        var temporary: File? = null
        try {
            val source = sourceInfo(uri)
            val format = identifyFormat(source.name, source.mimeType)
                ?: return@withContext ImportOutcome.Rejected("仅支持 TXT 或 EPUB 文件。")
            val staged = File.createTempFile("pxreader-", ".partial", stagingDirectory).also { temporary = it }
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            openInput(uri)?.use { input ->
                FileOutputStream(staged).use { output ->
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
            finalizeMutex.withLock {
                dao.document(documentId)?.let { return@withLock ImportOutcome.Duplicate(it) }
                val formatName = format.name.lowercase()
                val metadata = if (format == DocumentFormat.EPUB) reader.inspect(staged, formatName) else null
                val chapters = reader.openFile(staged, formatName)
                val units = chapters.map { chapter ->
                    SearchUnitEntity(
                        documentId = documentId,
                        chapterIndex = chapter.index,
                        chapterHref = chapter.href,
                        title = chapter.title,
                        body = chapter.text,
                    )
                }
                val destinationName = "${documentId.removePrefix("sha256:")}.${format.extension}"
                val destination = File(documentsDirectory, destinationName)
                coroutineContext.ensureActive()
                // Do not cancel between the database commit and returning its result: cleanup
                // after a committed transaction would otherwise delete a valid book file.
                withContext(NonCancellable) {
                    var newDestination = false
                    var newCover: File? = null
                    try {
                        val coverFileName = reader.extractCover(staged, formatName)?.let { cover ->
                            val name = "${documentId.removePrefix("sha256:")}.${cover.extension}"
                            val file = File(coversDirectory, name)
                            if (!file.exists()) {
                                newCover = file
                                FileOutputStream(file).use { output -> output.write(cover.bytes) }
                            }
                            name
                        }
                        newDestination = promote(staged, destination)
                        val now = System.currentTimeMillis()
                        val document = DocumentEntity(
                            id = documentId,
                            format = formatName,
                            title = (metadata?.title ?: source.name.substringBeforeLast('.')).ifBlank { source.name.substringBeforeLast('.') },
                            author = metadata?.author,
                            originalFileName = source.name,
                            sourceUri = uri.toString(),
                            sourcePath = sourcePath ?: uri.path?.takeIf { uri.scheme == ContentResolver.SCHEME_FILE },
                            storedFileName = destinationName,
                            coverFileName = coverFileName,
                            byteSize = bytes,
                            contentHash = documentId,
                            tagsJson = JSONArray(initialTags.map(String::trim).filter(String::isNotEmpty).distinct().sorted()).toString(),
                            chapterCount = chapters.size,
                            addedAt = now,
                            updatedAt = now,
                            lastOpenedAt = null,
                        )
                        dao.commitImportedDocument(document, units)
                        ImportOutcome.Imported(document)
                    } catch (error: Exception) {
                        if (newDestination) destination.delete()
                        newCover?.delete()
                        throw error
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ImportOutcome.Rejected(error.message ?: "导入失败：文件可能已损坏。")
        } finally {
            temporary?.delete()
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

    private fun promote(from: File, to: File): Boolean {
        if (to.exists()) return false
        if (from.renameTo(to)) return true
        try {
            from.copyTo(to, overwrite = false)
            return true
        } catch (error: Exception) {
            to.delete()
            throw error
        }
    }

    private data class SourceInfo(val name: String, val mimeType: String?)

    private companion object {
        const val MAX_DOCUMENT_BYTES = 128L * 1024L * 1024L
    }
}
