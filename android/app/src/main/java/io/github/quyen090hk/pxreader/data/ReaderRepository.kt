package io.github.quyen090hk.pxreader.data

import android.content.Context
import io.github.quyen090hk.pxreader.data.db.AnnotationEntity
import io.github.quyen090hk.pxreader.data.db.BookmarkEntity
import io.github.quyen090hk.pxreader.data.db.DocumentEntity
import io.github.quyen090hk.pxreader.data.db.PxReaderDao
import io.github.quyen090hk.pxreader.data.db.ReadingPositionEntity
import io.github.quyen090hk.pxreader.data.db.SearchUnitEntity
import io.github.quyen090hk.pxreader.reader.DocumentReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class SearchHit(
    val title: String,
    val snippet: String,
    val locator: TextLocator,
)

class ReaderRepository(context: Context, private val dao: PxReaderDao) {
    private val documentsDirectory = File(context.filesDir, "documents").apply { mkdirs() }
    private val reader = DocumentReader(documentsDirectory)
    // A navigation pop clears ReaderViewModel immediately. Keep the final position write owned
    // by the repository, and discard older queued positions before they can overwrite it.
    private val positionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val positionWriteMutex = Mutex()
    private val positionSequence = AtomicLong()
    private val latestPositionSequence = ConcurrentHashMap<String, Long>()

    val documents: Flow<List<DocumentEntity>> = dao.observeDocuments()

    fun annotations(documentId: String): Flow<List<AnnotationEntity>> = dao.observeAnnotations(documentId)

    fun bookmarks(documentId: String): Flow<List<BookmarkEntity>> = dao.observeBookmarks(documentId)

    suspend fun document(documentId: String): DocumentEntity? = dao.document(documentId)

    suspend fun open(documentId: String): ReaderDocument = withContext(Dispatchers.IO) {
        val document = requireNotNull(dao.document(documentId)) { "Document no longer exists." }
        // EPUB rendering reads only the current spine item. Import already stored the chapter
        // metadata and character counts, so opening a book must not hydrate its full text body.
        val indexed = if (document.format.equals("epub", ignoreCase = true)) {
            dao.indexedChapterSummaries(documentId)
        } else {
            emptyList()
        }
        val chapters = if (indexed.isNotEmpty() && indexed.size == document.chapterCount) {
            indexed.map { unit ->
                ReaderChapter(
                    index = unit.chapterIndex,
                    title = unit.title,
                    href = unit.chapterHref,
                    text = "",
                    contentLength = unit.contentLength,
                )
            }
        } else {
            reader.open(document)
        }
        dao.markOpened(documentId, System.currentTimeMillis())
        ReaderDocument(document, chapters)
    }

    suspend fun epubHtml(documentId: String, chapterIndex: Int, chapterHref: String?): String = withContext(Dispatchers.IO) {
        val document = requireNotNull(dao.document(documentId)) { "Document no longer exists." }
        reader.epubHtml(document, chapterIndex, chapterHref)
    }

    suspend fun position(documentId: String): TextLocator? = dao.position(documentId)?.toLocator()

    suspend fun savePosition(documentId: String, locator: TextLocator) {
        enqueuePositionSave(documentId, locator).join()
    }

    fun enqueuePositionSave(documentId: String, locator: TextLocator): Job {
        val sequence = positionSequence.incrementAndGet()
        latestPositionSequence.merge(documentId, sequence) { old, next -> maxOf(old, next) }
        return positionScope.launch {
            positionWriteMutex.withLock {
                if (latestPositionSequence[documentId] == sequence) {
                    dao.upsertPosition(locator.toEntity(documentId, System.currentTimeMillis()))
                }
            }
        }
    }

    suspend fun addAnnotation(draft: AnnotationDraft) {
        val now = System.currentTimeMillis()
        dao.upsertAnnotation(
            AnnotationEntity(
                id = newStableId(),
                documentId = draft.documentId,
                quote = draft.quote,
                note = draft.note?.trim()?.takeIf { it.isNotEmpty() },
                color = draft.color.name.lowercase(),
                chapterIndex = draft.locator.chapterIndex,
                chapterHref = draft.locator.chapterHref,
                charStart = draft.locator.charStart,
                charEnd = draft.locator.charEnd,
                progress = draft.locator.progress,
                prefix = draft.locator.prefix,
                suffix = draft.locator.suffix,
                anchor = draft.locator.anchor,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun deleteAnnotation(id: String) = dao.deleteAnnotation(id)

    suspend fun addBookmark(documentId: String, locator: TextLocator, label: String?) {
        val now = System.currentTimeMillis()
        dao.upsertBookmark(
            BookmarkEntity(
                id = newStableId(),
                documentId = documentId,
                label = label?.trim()?.takeIf { it.isNotEmpty() },
                chapterIndex = locator.chapterIndex,
                chapterHref = locator.chapterHref,
                charStart = locator.charStart,
                charEnd = locator.charEnd,
                progress = locator.progress,
                quote = locator.quote,
                prefix = locator.prefix,
                suffix = locator.suffix,
                anchor = locator.anchor,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun deleteBookmark(id: String) = dao.deleteBookmark(id)

    suspend fun updateTags(documentId: String, tags: Set<String>) {
        val normalized = tags.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        dao.updateTags(documentId, JSONArray(normalized).toString(), System.currentTimeMillis())
    }

    suspend fun tags(document: DocumentEntity): List<String> = runCatching {
        val value = JSONArray(document.tagsJson)
        List(value.length()) { value.getString(it) }
    }.getOrDefault(emptyList())

    suspend fun index(document: DocumentEntity) = withContext(Dispatchers.Default) {
        val units = reader.open(document).map { chapter ->
            SearchUnitEntity(
                documentId = document.id,
                chapterIndex = chapter.index,
                chapterHref = chapter.href,
                title = chapter.title,
                body = chapter.text,
            )
        }
        dao.replaceSearchUnits(document.id, units)
    }

    suspend fun search(documentId: String, query: String): List<SearchHit> = withContext(Dispatchers.Default) {
        val needle = query.trim()
        if (needle.isEmpty()) return@withContext emptyList()
        val containsCjk = needle.any { it.code in 0x3400..0x9fff }
        val units = if (containsCjk) {
            dao.exactSearch(documentId, needle, 200)
        } else {
            runCatching { dao.ftsSearch(documentId, toFtsQuery(needle), 200) }
                .getOrElse { emptyList() }
                .ifEmpty { dao.exactSearch(documentId, needle, 200) }
        }
        units.mapNotNull { unit -> unit.toSearchHit(needle) }
    }

    private fun toFtsQuery(query: String): String = query
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" AND ") { "\"${it.replace("\"", "")}" }
}

private fun SearchUnitEntity.toSearchHit(query: String): SearchHit? {
    val offset = body.indexOf(query, ignoreCase = true)
    if (offset < 0) return null
    val quote = body.substring(offset, (offset + query.length).coerceAtMost(body.length))
    val prefixStart = (offset - 48).coerceAtLeast(0)
    val suffixEnd = (offset + quote.length + 48).coerceAtMost(body.length)
    val snippet = buildString {
        if (prefixStart > 0) append('…')
        append(body.substring(prefixStart, suffixEnd).replace(Regex("\\s+"), " "))
        if (suffixEnd < body.length) append('…')
    }
    return SearchHit(
        title = title,
        snippet = snippet,
        locator = TextLocator(
            chapterIndex = chapterIndex,
            chapterHref = chapterHref,
            charStart = offset,
            charEnd = offset + quote.length,
            progress = 0f,
            quote = quote,
            prefix = body.substring(prefixStart, offset),
            suffix = body.substring(offset + quote.length, suffixEnd),
        ),
    )
}

private fun ReadingPositionEntity.toLocator() = TextLocator(
    chapterIndex, chapterHref, charStart, charEnd, progress, quote, prefix, suffix, anchor,
)

private fun TextLocator.toEntity(documentId: String, now: Long) = ReadingPositionEntity(
    documentId, chapterIndex, chapterHref, charStart, charEnd, progress, quote, prefix, suffix, anchor, now,
)
