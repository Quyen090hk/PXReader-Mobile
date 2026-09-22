package io.github.quyen090hk.pxreader.reader

import android.net.Uri
import android.util.Xml
import io.github.quyen090hk.pxreader.data.ReaderChapter
import io.github.quyen090hk.pxreader.data.db.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.LinkedHashMap
import java.util.zip.ZipFile

data class InspectedDocument(val title: String, val author: String?, val chapterCount: Int)
data class ExtractedCover(val bytes: ByteArray, val extension: String)

object TxtReader {
    private const val CHUNK_SIZE = 9_000
    private val heading = Regex(
        "(?m)^(第[零〇一二三四五六七八九十百千万\\d]+[章节回卷集部][^\\n]{0,48}|Chapter\\s+\\d+[^\\n]{0,64}|CHAPTER\\s+\\d+[^\\n]{0,64})",
    )

    fun chapters(file: File): List<ReaderChapter> {
        val normalized = decode(file).replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
        val matches = heading.findAll(normalized).toList()
        if (matches.size >= 2) {
            return matches.mapIndexed { index, match ->
                val end = matches.getOrNull(index + 1)?.range?.first ?: normalized.length
                ReaderChapter(
                    index = index,
                    title = match.value.trim(),
                    href = null,
                    text = normalized.substring(match.range.first, end).trim(),
                )
            }
        }
        return normalized.chunked(CHUNK_SIZE).mapIndexed { index, part ->
            ReaderChapter(index, "片段 ${index + 1}", null, part.trim())
        }.ifEmpty { listOf(ReaderChapter(0, "正文", null, "")) }
    }

    private fun decode(file: File): String {
        val bytes = FileInputStream(file).use { it.readBytes() }
        val candidates = listOf("UTF-8", "GB18030", "Big5")
        candidates.forEach { name ->
            runCatching {
                Charset.forName(name).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull()?.let { return it }
        }
        return bytes.toString(Charsets.UTF_8)
    }
}

data class EpubSpineItem(val index: Int, val href: String, val title: String)

class EpubBook(private val file: File) {
    private val packageData: EpubPackage by lazy { parsePackage() }
    private val chapterHtmlCache = object : LinkedHashMap<String, String>(HTML_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > HTML_CACHE_SIZE
    }

    fun inspect(): InspectedDocument = InspectedDocument(
        title = packageData.title ?: file.nameWithoutExtension,
        author = packageData.author,
        chapterCount = packageData.spine.size,
    )

    fun cover(): ExtractedCover? {
        val coverPath = packageData.coverHref ?: return null
        val extension = coverPath.substringAfterLast('.', "").lowercase().let { value ->
            when (value) {
                "jpeg" -> "jpg"
                "jpg", "png", "webp" -> value
                else -> return null
            }
        }
        return runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(coverPath) ?: return null
                if (entry.size !in 1..MAX_COVER_BYTES) return null
                ExtractedCover(zip.getInputStream(entry).use { it.readBytes() }, extension)
            }
        }.getOrNull()
    }

    fun chapters(): List<ReaderChapter> = packageData.spine.map { spine ->
        ReaderChapter(
            index = spine.index,
            title = spine.title,
            href = spine.href,
            text = chapterText(spine.index),
        )
    }

    fun chapterHtml(index: Int, knownHref: String? = null): String {
        // The local index already knows the spine href. Using it avoids reparsing the OPF and
        // table of contents before the first visible chapter can be rendered.
        val href = knownHref ?: packageData.spine.getOrNull(index)?.href ?: error("EPUB chapter does not exist")
        synchronized(chapterHtmlCache) { chapterHtmlCache[href] }?.let { return it }
        val source = readEntry(href) ?: error("EPUB chapter file is missing")
        return sanitizeHtml(source).also { sanitized ->
            synchronized(chapterHtmlCache) { chapterHtmlCache[href] = sanitized }
        }
    }

    fun chapterText(index: Int): String = epubIndexedText(chapterHtml(index))

    fun chapterHref(index: Int): String? = packageData.spine.getOrNull(index)?.href

    private fun parsePackage(): EpubPackage = ZipFile(file).use { zip ->
        val containerXml = zip.getEntry("META-INF/container.xml")
            ?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
            ?: error("Invalid EPUB: missing META-INF/container.xml")
        val opfPath = parseContainer(containerXml) ?: error("Invalid EPUB: missing package rootfile")
        val opfXml = zip.getEntry(opfPath)
            ?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
            ?: error("Invalid EPUB: missing package document")
        parseOpf(opfPath, opfXml, zip)
    }

    private fun parseContainer(xml: String): String? {
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && localName(parser.name) == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
        }
        return null
    }

    private fun parseOpf(opfPath: String, xml: String, zip: ZipFile): EpubPackage {
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val manifest = mutableMapOf<String, ManifestItem>()
        val spineIds = mutableListOf<String>()
        var title: String? = null
        var author: String? = null
        var legacyCoverId: String? = null
        var capture: String? = null
        val opfDir = opfPath.substringBeforeLast('/', "")
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "title" -> capture = "title"
                    "creator" -> capture = "creator"
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: continue
                        val href = parser.getAttributeValue(null, "href") ?: continue
                        manifest[id] = ManifestItem(
                            href = resolvePath(opfDir, href),
                            mediaType = parser.getAttributeValue(null, "media-type").orEmpty(),
                            properties = parser.getAttributeValue(null, "properties").orEmpty(),
                        )
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let(spineIds::add)
                    "meta" -> if (parser.getAttributeValue(null, "name")?.equals("cover", ignoreCase = true) == true) {
                        legacyCoverId = parser.getAttributeValue(null, "content")
                    }
                }
                XmlPullParser.TEXT -> when (capture) {
                    "title" -> if (title.isNullOrBlank()) title = parser.text.trim().takeIf { it.isNotEmpty() }
                    "creator" -> if (author.isNullOrBlank()) author = parser.text.trim().takeIf { it.isNotEmpty() }
                }
                XmlPullParser.END_TAG -> if (localName(parser.name) in setOf("title", "creator")) capture = null
            }
        }
        val rawSpine = spineIds.mapNotNull { id -> manifest[id] }
            .filter { it.mediaType.contains("html", true) || it.href.endsWith(".xhtml", true) || it.href.endsWith(".html", true) }
        if (rawSpine.isEmpty()) error("Invalid EPUB: package has no readable spine")
        val navTitles = readNavTitles(
            manifest.values.firstOrNull { it.properties.split(Regex("\\s+")).contains("nav") }?.href,
            zip,
            rawSpine,
        )
        val ncxTitles = readNcxTitles(
            manifest.values.firstOrNull { item ->
                item.mediaType.contains("ncx", ignoreCase = true) || item.href.endsWith(".ncx", ignoreCase = true)
            }?.href,
            zip,
            rawSpine,
        )
        val spine = rawSpine.mapIndexed { index, item ->
            EpubSpineItem(
                index,
                item.href,
                navTitles[item.href]
                    ?: ncxTitles[item.href]
                    ?: titleFromHtml(zip, item.href, title)
                    ?: fileName(item.href),
            )
        }
        val coverHref = manifest.values.firstOrNull { it.properties.split(Regex("\\s+")).contains("cover-image") }?.href
            ?: legacyCoverId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull { item ->
                item.mediaType.startsWith("image/") && item.href.substringAfterLast('/').contains("cover", ignoreCase = true)
            }?.href
        return EpubPackage(title, author, spine, coverHref)
    }

    private fun readNavTitles(
        navPath: String?,
        zip: ZipFile,
        spine: List<ManifestItem>,
    ): Map<String, String> {
        if (navPath == null) return emptyMap()
        val navHtml = zip.getEntry(navPath)?.let { zip.getInputStream(it).bufferedReader().use { it.readText() } } ?: return emptyMap()
        val doc = Jsoup.parse(navHtml)
        val toc = doc.select("nav").firstOrNull { nav ->
            nav.attributes().any { it.key.endsWith("type") && it.value.split(Regex("\\s+")).contains("toc") }
        } ?: doc.select("nav").firstOrNull() ?: return emptyMap()
        return toc.select("a[href]").mapNotNull { anchor ->
            val full = resolvePath(navPath.substringBeforeLast('/', ""), anchor.attr("href").substringBefore('#'))
            if (spine.any { it.href == full }) full to anchor.text().trim() else null
        }.toMap()
    }

    private fun readNcxTitles(
        ncxPath: String?,
        zip: ZipFile,
        spine: List<ManifestItem>,
    ): Map<String, String> {
        if (ncxPath == null) return emptyMap()
        val ncxXml = zip.getEntry(ncxPath)
            ?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
            ?: return emptyMap()
        val spinePaths = spine.mapTo(hashSetOf()) { it.href }
        val titles = linkedMapOf<String, String>()
        val document = Jsoup.parse(ncxXml, "", Parser.xmlParser())
        document.getAllElements()
            .filter { element -> element.tagName().substringAfter(':').equals("navPoint", ignoreCase = true) }
            .forEach { navPoint ->
                val source = navPoint.getAllElements()
                    .firstOrNull { element -> element.tagName().substringAfter(':').equals("content", ignoreCase = true) }
                    ?.attr("src")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val label = navPoint.getAllElements()
                    .firstOrNull { element -> element.tagName().substringAfter(':').equals("navLabel", ignoreCase = true) }
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                val relativePath = Uri.decode(source.substringBefore('#').substringBefore('?'))
                val fullPath = resolvePath(ncxPath.substringBeforeLast('/', ""), relativePath)
                if (fullPath in spinePaths) {
                    // EPUB 2 often points both a section and its first nested chapter at the same
                    // XHTML file. The later, more specific entry is the useful reader title.
                    titles[fullPath] = label
                }
            }
        return titles
    }

    private fun titleFromHtml(zip: ZipFile, href: String, bookTitle: String?): String? = runCatching {
        val html = zip.getEntry(href)?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } } ?: return null
        val document = Jsoup.parse(html)
        document.select("h1, h2, h3")
            .asSequence()
            .map { it.text().trim() }
            .firstOrNull { heading -> heading.isNotEmpty() && !heading.equals(bookTitle, ignoreCase = true) }
            ?: document.title().trim().takeIf { value -> value.isNotEmpty() && !value.equals(bookTitle, ignoreCase = true) }
    }.getOrNull()

    private fun readEntry(path: String): String? = ZipFile(file).use { zip ->
        zip.getEntry(path)?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
    }

    private fun sanitizeHtml(source: String): String {
        val document = Jsoup.parse(source)
        document.select("script, iframe, object, embed, base").remove()
        document.select("meta[name=viewport]").remove()
        document.head().prependElement("meta")
            .attr("name", "viewport")
            .attr("content", "width=device-width, initial-scale=1.0, maximum-scale=1.0")
        document.getAllElements().forEach { element ->
            element.attributes().asList().forEach { attribute ->
                val key = attribute.key.lowercase()
                val value = attribute.value.trim().lowercase()
                if (key.startsWith("on") || (key in setOf("src", "href") && (value.startsWith("http:") || value.startsWith("https:") || value.startsWith("javascript:")))) {
                    element.removeAttr(attribute.key)
                }
            }
        }
        document.head().appendElement("style").text(
            "body{margin:0;padding:1.75rem 1.4rem 3.5rem;line-height:var(--px-line-height,1.7);font-family:Georgia,'Noto Serif SC',serif;font-size:var(--px-font-size,1rem);letter-spacing:.005em;color:var(--px-foreground,#1d1b20);background:var(--px-background,#fffbfe)}img{max-width:100%;height:auto;border-radius:.75rem}h1,h2,h3{line-height:1.28;margin-top:1.8em}",
        )
        return document.outerHtml()
    }

    private data class ManifestItem(val href: String, val mediaType: String, val properties: String)
    private data class EpubPackage(val title: String?, val author: String?, val spine: List<EpubSpineItem>, val coverHref: String?)

    private companion object {
        const val MAX_COVER_BYTES = 15L * 1024L * 1024L
        const val HTML_CACHE_SIZE = 12
    }

    private fun localName(name: String) = name.substringAfter(':')
    private fun fileName(path: String) = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "未命名章节" }
}

class DocumentReader(private val documentsDirectory: File) {
    private val epubCache = object : LinkedHashMap<String, CachedEpub>(EPUB_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEpub>?): Boolean = size > EPUB_CACHE_SIZE
    }

    suspend fun inspect(file: File, format: String): InspectedDocument = withContext(Dispatchers.IO) {
        when (format.lowercase()) {
            "txt" -> TxtReader.chapters(file).let { chapters -> InspectedDocument(file.nameWithoutExtension, null, chapters.size) }
            "epub" -> epub(file).inspect()
            else -> error("Unsupported format: $format")
        }
    }

    suspend fun extractCover(file: File, format: String): ExtractedCover? = withContext(Dispatchers.IO) {
        if (format.equals("epub", ignoreCase = true)) epub(file).cover() else null
    }

    suspend fun open(document: DocumentEntity): List<ReaderChapter> =
        openFile(File(documentsDirectory, document.storedFileName), document.format)

    suspend fun openFile(file: File, format: String): List<ReaderChapter> = withContext(Dispatchers.IO) {
        check(file.isFile) { "The imported source file is missing. Import it again." }
        when (format.lowercase()) {
            "txt" -> TxtReader.chapters(file)
            "epub" -> epub(file).chapters()
            else -> error("Unsupported format: $format")
        }
    }

    suspend fun epubHtml(document: DocumentEntity, chapterIndex: Int, chapterHref: String?): String = withContext(Dispatchers.IO) {
        val file = File(documentsDirectory, document.storedFileName)
        epub(file).chapterHtml(chapterIndex, chapterHref)
    }

    private fun epub(file: File): EpubBook = synchronized(epubCache) {
        val key = file.absolutePath
        val signature = "${file.length()}:${file.lastModified()}"
        epubCache[key]?.takeIf { it.signature == signature }?.book
            ?: EpubBook(file).also { book -> epubCache[key] = CachedEpub(signature, book) }
    }

    private data class CachedEpub(val signature: String, val book: EpubBook)

    private companion object {
        const val EPUB_CACHE_SIZE = 3
    }
}

private fun resolvePath(baseDir: String, rawPath: String): String {
    val parts = mutableListOf<String>()
    (listOf(baseDir) + rawPath.replace('\\', '/')).joinToString("/").split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return parts.joinToString("/")
}
