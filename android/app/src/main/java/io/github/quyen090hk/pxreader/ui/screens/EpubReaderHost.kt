package io.github.quyen090hk.pxreader.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import io.github.quyen090hk.pxreader.data.ReaderChapter
import io.github.quyen090hk.pxreader.data.db.AnnotationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

@Composable
fun EpubReaderHost(
    documentId: String,
    storedFileName: String,
    chapter: ReaderChapter,
    locator: io.github.quyen090hk.pxreader.data.TextLocator,
    annotations: List<AnnotationEntity>,
    fontScale: Float,
    lineHeight: Float,
    onRequestHtml: suspend (Int) -> String,
    onSelection: (Int, Int, Int, String, String, String) -> Unit,
    onProgress: (Float) -> Unit,
    modifier: Modifier,
) {
    var html by remember(documentId, chapter.index) { mutableStateOf<String?>(null) }
    LaunchedEffect(documentId, chapter.index) {
        html = try {
            onRequestHtml(chapter.index)
        } catch (_: Exception) {
            null
        }
    }
    if (html == null) {
        androidx.compose.foundation.layout.Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    val context = LocalContext.current
    val source = remember(documentId, storedFileName) { File(context.filesDir, "documents/$storedFileName") }
    val chapterAnnotations = annotations.filter { it.chapterIndex == chapter.index }
    key("$documentId:${chapter.index}:${chapterAnnotations.joinToString { it.id + it.updatedAt }}") {
        EpubWebView(
            source = source,
            documentId = documentId,
            chapter = chapter,
            restoreFraction = if (chapter.text.isEmpty()) 0f else locator.charStart.toFloat() / chapter.text.length,
            annotations = chapterAnnotations,
            html = requireNotNull(html),
            fontScale = fontScale,
            lineHeight = lineHeight,
            onSelection = onSelection,
            onProgress = onProgress,
            modifier = modifier,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubWebView(
    source: File,
    documentId: String,
    chapter: ReaderChapter,
    restoreFraction: Float,
    annotations: List<AnnotationEntity>,
    html: String,
    fontScale: Float,
    lineHeight: Float,
    onSelection: (Int, Int, Int, String, String, String) -> Unit,
    onProgress: (Float) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/epub/", EpubZipPathHandler(documentId, source))
                .build()
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true // only app-injected selection/scroll bridge; EPUB scripts are stripped.
                    domStorageEnabled = false
                    databaseEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                isVerticalScrollBarEnabled = true
                addJavascriptInterface(EpubBridge(chapter.index, onSelection, onProgress), "PXReaderBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(BRIDGE_SCRIPT, null)
                        view.evaluateJavascript(highlightScript(annotations), null)
                        val fraction = (view.getTag(android.R.id.content) as? Float ?: 0f).coerceIn(0f, 1f)
                        view.evaluateJavascript("window.scrollTo(0, document.documentElement.scrollHeight * $fraction);", null)
                    }
                }
            }
        },
        update = { view ->
            view.setTag(android.R.id.content, restoreFraction)
            val contentKey = "$documentId:${chapter.index}"
            if (view.tag != contentKey) {
                view.tag = contentKey
                val baseUrl = "https://appassets.androidplatform.net/epub/$documentId/${chapter.href.orEmpty()}"
                view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            } else {
                view.evaluateJavascript(themeScript(fontScale, lineHeight), null)
            }
        },
        modifier = modifier,
    )
}

private class EpubBridge(
    private val chapterIndex: Int,
    private val selection: (Int, Int, Int, String, String, String) -> Unit,
    private val progress: (Float) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @JavascriptInterface
    fun selection(payload: String) {
        runCatching {
            val value = JSONObject(payload)
            val start = value.getInt("start")
            val end = value.getInt("end")
            val quote = value.getString("quote")
            val prefix = value.optString("prefix")
            val suffix = value.optString("suffix")
            mainHandler.post { selection(chapterIndex, start, end, quote, prefix, suffix) }
        }
    }

    @JavascriptInterface
    fun progress(value: Float) = mainHandler.post { progress(value.coerceIn(0f, 1f)) }
}

private class EpubZipPathHandler(
    private val expectedDocumentId: String,
    private val source: File,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val decoded = Uri.decode(path).removePrefix("/epub/")
        val documentId = decoded.substringBefore('/')
        val entryPath = decoded.substringAfter('/', "")
        if (documentId != expectedDocumentId || entryPath.isBlank() || entryPath.split('/').any { it == ".." }) return null
        return runCatching {
            ZipFile(source).use { archive ->
                val entry = archive.getEntry(entryPath) ?: return null
                val bytes = archive.getInputStream(entry).readBytes()
                val extension = entryPath.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("xml") || mime.contains("css")) "utf-8" else null, bytes.inputStream())
            }
        }.getOrNull()
    }
}

private fun themeScript(fontScale: Float, lineHeight: Float) =
    "document.documentElement.style.setProperty('--px-font-size','${fontScale}rem');document.documentElement.style.setProperty('--px-line-height','${lineHeight}');"

private fun highlightScript(annotations: List<AnnotationEntity>): String {
    val payload = org.json.JSONArray().apply {
        annotations.forEach { annotation ->
            put(JSONObject().apply {
                put("id", annotation.id)
                put("quote", annotation.quote)
                put("color", annotation.color)
            })
        }
    }
    return "window.PXReaderHighlights && window.PXReaderHighlights.apply(${JSONObject.quote(payload.toString())});"
}

private const val BRIDGE_SCRIPT = """
(() => {
  if (window.__pxReaderBridgeInstalled) return;
  window.__pxReaderBridgeInstalled = true;
  const offset = (node, point) => {
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let total = 0, current;
    while ((current = walker.nextNode())) {
      if (current === node) return total + point;
      total += current.nodeValue.length;
    }
    return total;
  };
  const reportSelection = () => {
    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return;
    const range = selection.getRangeAt(0);
    const quote = selection.toString().trim();
    if (!quote) return;
    const text = document.body.innerText || '';
    const start = offset(range.startContainer, range.startOffset);
    const end = offset(range.endContainer, range.endOffset);
    window.PXReaderBridge.selection(JSON.stringify({start, end, quote, prefix:text.slice(Math.max(0,start-48),start), suffix:text.slice(end,end+48)}));
  };
  document.addEventListener('selectionchange', reportSelection);
  window.PXReaderHighlights = {
    apply: (raw) => {
      let annotations = [];
      try { annotations = JSON.parse(raw); } catch (_) { return; }
      const mark = (needle, color, id) => {
        if (!needle) return false;
        const lowerNeedle = needle.toLocaleLowerCase();
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
          acceptNode: (node) => node.parentElement && node.parentElement.tagName !== 'MARK' && node.nodeValue.toLocaleLowerCase().includes(lowerNeedle) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT
        });
        const nodes = [];
        while (walker.nextNode()) nodes.push(walker.currentNode);
        for (const node of nodes) {
          const value = node.nodeValue, lower = value.toLocaleLowerCase(), at = lower.indexOf(lowerNeedle);
          if (at < 0) continue;
          const fragment = document.createDocumentFragment();
          fragment.append(value.slice(0, at));
          const span = document.createElement('mark');
          span.dataset.pxAnnotationId = id;
          span.style.background = color === 'green' ? '#caeece' : color === 'blue' ? '#bbdefb' : color === 'pink' ? '#f8bbd0' : color === 'orange' ? '#ffe0b2' : '#fff59d';
          span.textContent = value.slice(at, at + needle.length);
          fragment.append(span, value.slice(at + needle.length));
          node.parentNode.replaceChild(fragment, node);
          return true;
        }
        return false;
      };
      annotations.forEach((annotation) => {
        if (mark(annotation.quote, annotation.color, annotation.id)) return;
        const fallback = (annotation.quote.match(/[\p{L}\p{N}]{2,}/u) || [])[0] || annotation.quote.slice(0, 16);
        mark(fallback, annotation.color, annotation.id);
      });
    }
  };
  let pending = false;
  window.addEventListener('scroll', () => {
    if (pending) return;
    pending = true;
    requestAnimationFrame(() => {
      pending = false;
      const max = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
      window.PXReaderBridge.progress(window.scrollY / max);
    });
  }, {passive:true});
})();
"""
