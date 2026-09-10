package io.github.quyen090hk.pxreader.backup

import android.content.Context
import android.net.Uri
import io.github.quyen090hk.pxreader.data.PROTOCOL_VERSION
import io.github.quyen090hk.pxreader.data.db.AnnotationEntity
import io.github.quyen090hk.pxreader.data.db.BookmarkEntity
import io.github.quyen090hk.pxreader.data.db.DocumentEntity
import io.github.quyen090hk.pxreader.data.db.PxReaderDao
import io.github.quyen090hk.pxreader.data.db.ReadingPositionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class BackupExporter(private val context: Context, private val dao: PxReaderDao) {
    suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val root = JSONObject().apply {
            put("schemaVersion", PROTOCOL_VERSION)
            put("kind", "pxreader-backup")
            put("exportedAt", nowIso())
            put("source", "android")
            put("documents", JSONArray(dao.allDocuments().map(DocumentEntity::toProtocol)))
            put("positions", JSONArray(dao.allPositions().map(ReadingPositionEntity::toProtocol)))
            put("bookmarks", JSONArray(dao.allBookmarks().map(BookmarkEntity::toProtocol)))
            put("annotations", JSONArray(dao.allAnnotations().map(AnnotationEntity::toProtocol)))
        }
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(root.toString(2))
        } ?: error("无法写入选择的位置。")
    }

    suspend fun exportAnnotationsMarkdown(uri: Uri, documentId: String) = withContext(Dispatchers.IO) {
        val document = requireNotNull(dao.document(documentId))
        val annotations = dao.allAnnotations().filter { it.documentId == documentId }
        val output = buildString {
            appendLine("# ${document.title} — 批注")
            appendLine()
            annotations.forEach { annotation ->
                appendLine("## ${iso(annotation.createdAt)}")
                appendLine()
                appendLine("> ${annotation.quote.replace("\n", "\n> ")}")
                annotation.note?.takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    appendLine(it)
                }
                appendLine()
            }
        }
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(output) }
            ?: error("无法写入选择的位置。")
    }
}

private fun DocumentEntity.toProtocol() = JSONObject().apply {
    put("schemaVersion", PROTOCOL_VERSION)
    put("documentId", id)
    put("format", format)
    put("title", title)
    put("author", author ?: JSONObject.NULL)
    put("originalFileName", originalFileName)
    put("byteSize", byteSize)
    put("contentHash", contentHash)
    put("tags", runCatching { JSONArray(tagsJson) }.getOrDefault(JSONArray()))
    put("createdAt", iso(addedAt))
    put("updatedAt", iso(updatedAt))
}

private fun ReadingPositionEntity.toProtocol() = JSONObject().apply {
    put("schemaVersion", PROTOCOL_VERSION)
    put("documentId", documentId)
    put("locator", locatorJson(chapterIndex, chapterHref, charStart, charEnd, progress, quote, prefix, suffix, anchor))
    put("updatedAt", iso(updatedAt))
}

private fun BookmarkEntity.toProtocol() = JSONObject().apply {
    put("schemaVersion", PROTOCOL_VERSION)
    put("id", id)
    put("documentId", documentId)
    put("label", label ?: JSONObject.NULL)
    put("locator", locatorJson(chapterIndex, chapterHref, charStart, charEnd, progress, quote, prefix, suffix, anchor))
    put("createdAt", iso(createdAt))
    put("updatedAt", iso(updatedAt))
}

private fun AnnotationEntity.toProtocol() = JSONObject().apply {
    put("schemaVersion", PROTOCOL_VERSION)
    put("id", id)
    put("documentId", documentId)
    put("quote", quote)
    put("note", note ?: JSONObject.NULL)
    put("color", color)
    put("locator", locatorJson(chapterIndex, chapterHref, charStart, charEnd, progress, quote, prefix, suffix, anchor))
    put("createdAt", iso(createdAt))
    put("updatedAt", iso(updatedAt))
}

private fun locatorJson(
    chapterIndex: Int,
    chapterHref: String?,
    charStart: Int,
    charEnd: Int,
    progress: Float,
    quote: String,
    prefix: String,
    suffix: String,
    anchor: String?,
) = JSONObject().apply {
    put("kind", "text")
    put("chapterIndex", chapterIndex)
    put("chapterHref", chapterHref ?: JSONObject.NULL)
    put("charStart", charStart)
    put("charEnd", charEnd)
    put("progress", progress)
    put("quote", quote)
    put("prefix", prefix)
    put("suffix", suffix)
    put("anchor", anchor ?: JSONObject.NULL)
}

private fun nowIso() = Instant.now().toString()
private fun iso(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).toString()
