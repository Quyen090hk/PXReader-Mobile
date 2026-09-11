package io.github.quyen090hk.pxreader.data

import io.github.quyen090hk.pxreader.data.db.DocumentEntity
import java.util.UUID

const val PROTOCOL_VERSION = "1.0.0"

enum class DocumentFormat(val extension: String) {
    TXT("txt"), EPUB("epub");

    companion object {
        fun from(value: String): DocumentFormat? = entries.firstOrNull { it.name.equals(value, true) }
    }
}

data class TextLocator(
    val chapterIndex: Int,
    val chapterHref: String?,
    val charStart: Int,
    val charEnd: Int = charStart,
    val progress: Float,
    val quote: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val anchor: String? = null,
) {
    companion object {
        fun atChapterStart(index: Int, href: String?, progress: Float = 0f) =
            TextLocator(index, href, 0, 0, progress)
    }
}

data class ReaderChapter(
    val index: Int,
    val title: String,
    val href: String?,
    val text: String,
    val contentLength: Int = text.length,
)

data class ReaderDocument(
    val document: DocumentEntity,
    val chapters: List<ReaderChapter>,
) {
    val format: DocumentFormat get() = DocumentFormat.from(document.format) ?: DocumentFormat.TXT
}

data class AnnotationDraft(
    val documentId: String,
    val quote: String,
    val note: String?,
    val color: AnnotationColor,
    val locator: TextLocator,
)

enum class AnnotationColor { YELLOW, GREEN, BLUE, PINK, ORANGE }

fun newStableId(): String = UUID.randomUUID().toString()
