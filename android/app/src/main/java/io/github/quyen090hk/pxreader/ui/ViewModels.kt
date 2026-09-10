package io.github.quyen090hk.pxreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.quyen090hk.pxreader.data.AnnotationColor
import io.github.quyen090hk.pxreader.data.AnnotationDraft
import io.github.quyen090hk.pxreader.data.ReaderChapter
import io.github.quyen090hk.pxreader.data.ReaderDocument
import io.github.quyen090hk.pxreader.data.ReaderRepository
import io.github.quyen090hk.pxreader.data.SearchHit
import io.github.quyen090hk.pxreader.data.TextLocator
import io.github.quyen090hk.pxreader.data.db.AnnotationEntity
import io.github.quyen090hk.pxreader.data.db.DocumentEntity
import io.github.quyen090hk.pxreader.importer.DocumentImporter
import io.github.quyen090hk.pxreader.importer.DocumentScanner
import io.github.quyen090hk.pxreader.importer.ImportOutcome
import io.github.quyen090hk.pxreader.importer.ScanProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class LibraryUiState(
    val documents: List<DocumentEntity> = emptyList(),
    val importing: Boolean = false,
    val scanning: Boolean = false,
    val scanProgress: ScanProgress? = null,
    val message: String? = null,
)

class LibraryViewModel(
    private val repository: ReaderRepository,
    private val importer: DocumentImporter,
    private val scanner: DocumentScanner,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.documents.collectLatest { documents -> mutableState.update { it.copy(documents = documents) } }
        }
    }

    fun importUris(uris: List<android.net.Uri>, onImported: (String) -> Unit = {}) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            mutableState.update { it.copy(importing = true, message = null) }
            var firstImported: String? = null
            val messages = uris.map { uri ->
                when (val result = importer.import(uri)) {
                    is ImportOutcome.Imported -> {
                        if (firstImported == null) firstImported = result.document.id
                        "已导入《${result.document.title}》"
                    }
                    is ImportOutcome.Duplicate -> "《${result.document.title}》已在书库中"
                    is ImportOutcome.Rejected -> result.reason
                }
            }
            mutableState.update { it.copy(importing = false, message = messages.joinToString("；")) }
            firstImported?.let(onImported)
        }
    }

    fun updateTags(documentId: String, input: String) = viewModelScope.launch {
        repository.updateTags(documentId, input.split(',', '，').map(String::trim).filter(String::isNotEmpty).toSet())
    }

    fun scanTree(uri: android.net.Uri) = launchScan { report -> scanner.scanTree(uri, report) }

    fun scanSharedStorage(onPermissionRequired: () -> Unit) {
        if (!scanner.hasAllFilesAccess) {
            mutableState.update { it.copy(message = "扫描整个设备需要系统授予“所有文件访问”权限。") }
            onPermissionRequired()
            return
        }
        launchScan { report -> scanner.scanSharedStorage(report) }
    }

    private fun launchScan(work: suspend ((ScanProgress) -> Unit) -> io.github.quyen090hk.pxreader.importer.ScanReport) {
        viewModelScope.launch {
            mutableState.update { it.copy(scanning = true, scanProgress = ScanProgress(), message = null) }
            val result = runCatching { work { progress ->
                if (progress.examined % 10 == 0 || progress.candidates > 0) {
                    mutableState.update { it.copy(scanProgress = progress) }
                }
            } }
            result.onSuccess { report ->
                mutableState.update {
                    it.copy(
                        scanning = false,
                        scanProgress = null,
                        message = "扫描完成：发现 ${report.candidates} 本，新增 ${report.imported} 本，已存在 ${report.duplicates} 本。",
                    )
                }
            }.onFailure { error ->
                mutableState.update { it.copy(scanning = false, scanProgress = null, message = error.message ?: "扫描未完成。") }
            }
        }
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }
}

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val reader: ReaderDocument? = null,
    val locator: TextLocator? = null,
    val locationRevision: Long = 0,
    val annotations: List<AnnotationEntity> = emptyList(),
    val pendingSelection: TextLocator? = null,
    val searchHits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
)

class ReaderViewModel(
    private val documentId: String,
    private val repository: ReaderRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.annotations(documentId).collectLatest { annotations ->
                mutableState.update { it.copy(annotations = annotations) }
            }
        }
        viewModelScope.launch {
            runCatching {
                val reader = repository.open(documentId)
                val restored = repository.position(documentId)
                reader to normalize(restored ?: TextLocator.atChapterStart(0, reader.chapters.firstOrNull()?.href), reader)
            }.onSuccess { (reader, locator) ->
                mutableState.update { it.copy(loading = false, reader = reader, locator = locator, locationRevision = 1) }
                repository.savePosition(documentId, locator)
            }.onFailure { error ->
                mutableState.update { it.copy(loading = false, error = error.message ?: "无法打开此文档。") }
            }
        }
    }

    fun navigateTo(locator: TextLocator) {
        val reader = state.value.reader ?: return
        val normalized = normalize(locator, reader)
        mutableState.update { it.copy(locator = normalized, pendingSelection = null, locationRevision = it.locationRevision + 1) }
        viewModelScope.launch { repository.savePosition(documentId, normalized) }
    }

    fun updateProgress(fractionInChapter: Float) {
        val reader = state.value.reader ?: return
        val current = state.value.locator ?: return
        val chapter = reader.chapters.getOrNull(current.chapterIndex) ?: return
        val char = (chapter.text.length * fractionInChapter.coerceIn(0f, 1f)).roundToInt()
        val updated = locatorFor(chapter, char, char, reader)
        mutableState.update { it.copy(locator = updated) }
        viewModelScope.launch { repository.savePosition(documentId, updated) }
    }

    fun selectText(start: Int, end: Int) {
        val reader = state.value.reader ?: return
        val chapter = reader.chapters.getOrNull(state.value.locator?.chapterIndex ?: 0) ?: return
        if (end <= start) return
        mutableState.update { it.copy(pendingSelection = locatorFor(chapter, start, end, reader)) }
    }

    fun selectEpubText(chapterIndex: Int, start: Int, end: Int, quote: String, prefix: String, suffix: String) {
        val reader = state.value.reader ?: return
        val chapter = reader.chapters.getOrNull(chapterIndex) ?: return
        if (end <= start || quote.isBlank()) return
        val locator = locatorFor(chapter, start, end, reader).copy(quote = quote, prefix = prefix, suffix = suffix)
        mutableState.update { it.copy(pendingSelection = locator) }
    }

    fun clearSelection() = mutableState.update { it.copy(pendingSelection = null) }

    fun saveAnnotation(note: String, color: AnnotationColor) {
        val selection = state.value.pendingSelection ?: return
        if (selection.quote.isBlank()) return
        viewModelScope.launch {
            repository.addAnnotation(AnnotationDraft(documentId, selection.quote, note, color, selection))
            mutableState.update { it.copy(pendingSelection = null) }
        }
    }

    fun deleteAnnotation(id: String) = viewModelScope.launch { repository.deleteAnnotation(id) }

    fun search(query: String) = viewModelScope.launch {
        mutableState.update { it.copy(searching = true) }
        val hits = runCatching { repository.search(documentId, query) }.getOrDefault(emptyList())
        mutableState.update { it.copy(searching = false, searchHits = hits) }
    }

    suspend fun epubHtml(chapterIndex: Int): String = repository.epubHtml(documentId, chapterIndex)

    private fun normalize(raw: TextLocator, reader: ReaderDocument): TextLocator {
        val chapter = reader.chapters.getOrElse(raw.chapterIndex.coerceIn(0, reader.chapters.lastIndex)) { ReaderChapter(0, "正文", null, "") }
        return locatorFor(chapter, raw.charStart, raw.charEnd, reader).copy(
            quote = raw.quote,
            prefix = raw.prefix,
            suffix = raw.suffix,
            anchor = raw.anchor,
        )
    }

    private fun locatorFor(chapter: ReaderChapter, rawStart: Int, rawEnd: Int, reader: ReaderDocument): TextLocator {
        val start = rawStart.coerceIn(0, chapter.text.length)
        val end = rawEnd.coerceIn(start, chapter.text.length)
        val quote = chapter.text.substring(start, end)
        val prefixStart = (start - 48).coerceAtLeast(0)
        val suffixEnd = (end + 48).coerceAtMost(chapter.text.length)
        val fraction = if (chapter.text.isEmpty()) 0f else start.toFloat() / chapter.text.length
        val progress = ((chapter.index + fraction) / reader.chapters.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        return TextLocator(chapter.index, chapter.href, start, end, progress, quote, chapter.text.substring(prefixStart, start), chapter.text.substring(end, suffixEnd))
    }
}

@Suppress("UNCHECKED_CAST")
class PxReaderViewModelFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
