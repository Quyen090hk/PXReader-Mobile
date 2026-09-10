package io.github.quyen090hk.pxreader.importer

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import io.github.quyen090hk.pxreader.data.DocumentFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

data class ScanProgress(
    val examined: Int = 0,
    val candidates: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val rejected: Int = 0,
    val currentPath: String = "",
)

data class ScanReport(
    val examined: Int,
    val candidates: Int,
    val imported: Int,
    val duplicates: Int,
    val rejected: Int,
)

data class ScanOptions(
    val formats: Set<DocumentFormat> = setOf(DocumentFormat.TXT, DocumentFormat.EPUB),
    val minimumBytes: Long = 20L * 1024L,
    val initialTags: Set<String> = emptySet(),
)

class DocumentScanner(
    private val context: Context,
    private val importer: DocumentImporter,
) {
    val hasAllFilesAccess: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    suspend fun scanTree(root: Uri, options: ScanOptions, onProgress: (ScanProgress) -> Unit): ScanReport = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        runCatching { resolver.takePersistableUriPermission(root, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val queue = ArrayDeque<TreeNode>()
        queue.add(TreeNode(DocumentsContract.getTreeDocumentId(root), ""))
        val totals = MutableTotals()
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val node = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, node.documentId)
            resolver.query(childrenUri, TREE_PROJECTION, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val typeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val id = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex).orEmpty()
                    val mimeType = cursor.getString(typeIndex).orEmpty()
                    val byteSize = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex)
                    val displayPath = listOf(node.relativePath, name).filter(String::isNotBlank).joinToString("/")
                    totals.examined += 1
                    when {
                        mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> queue.add(TreeNode(id, displayPath))
                        supportedFormat(name) in options.formats && byteSize >= options.minimumBytes -> {
                            totals.candidates += 1
                            importCandidate(
                                uri = DocumentsContract.buildDocumentUriUsingTree(root, id),
                                displayPath = displayPath,
                                options = options,
                                totals = totals,
                            )
                        }
                    }
                    onProgress(totals.progress(displayPath))
                }
            }
        }
        totals.report()
    }

    suspend fun scanSharedStorage(options: ScanOptions, onProgress: (ScanProgress) -> Unit): ScanReport = withContext(Dispatchers.IO) {
        check(hasAllFilesAccess) { "请先授权“所有文件访问”后再扫描设备。" }
        val queue = ArrayDeque<File>()
        queue.add(Environment.getExternalStorageDirectory())
        val totals = MutableTotals()
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val directory = queue.removeFirst()
            if (directory.name == "Android") continue
            val children = runCatching { directory.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
            children.forEach { child ->
                coroutineContext.ensureActive()
                if (child.isDirectory) {
                    if (child.canRead()) queue.add(child)
                    return@forEach
                }
                totals.examined += 1
                if (supportedFormat(child.name) in options.formats && child.length() >= options.minimumBytes && child.canRead()) {
                    totals.candidates += 1
                    importCandidate(Uri.fromFile(child), child.absolutePath, options, totals)
                }
                onProgress(totals.progress(child.absolutePath))
            }
        }
        totals.report()
    }

    private suspend fun importCandidate(uri: Uri, displayPath: String, options: ScanOptions, totals: MutableTotals) {
        when (importer.import(uri, sourcePath = displayPath, initialTags = options.initialTags)) {
            is ImportOutcome.Imported -> totals.imported += 1
            is ImportOutcome.Duplicate -> totals.duplicates += 1
            is ImportOutcome.Rejected -> totals.rejected += 1
        }
    }

    private fun supportedFormat(name: String): DocumentFormat? = when {
        name.endsWith(".txt", ignoreCase = true) -> DocumentFormat.TXT
        name.endsWith(".epub", ignoreCase = true) -> DocumentFormat.EPUB
        else -> null
    }

    private data class TreeNode(val documentId: String, val relativePath: String)

    private class MutableTotals {
        var examined = 0
        var candidates = 0
        var imported = 0
        var duplicates = 0
        var rejected = 0

        fun progress(path: String) = ScanProgress(examined, candidates, imported, duplicates, rejected, path)
        fun report() = ScanReport(examined, candidates, imported, duplicates, rejected)
    }

    private companion object {
        val TREE_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
