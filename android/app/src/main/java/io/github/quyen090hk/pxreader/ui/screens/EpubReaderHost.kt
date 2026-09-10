package io.github.quyen090hk.pxreader.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import io.github.quyen090hk.pxreader.data.ReaderChapter
import io.github.quyen090hk.pxreader.data.TextLocator
import io.github.quyen090hk.pxreader.data.db.AnnotationEntity
import io.github.quyen090hk.pxreader.settings.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * EPUB and TXT share this host instead of having separate, incompatible layout rules. The Web
 * renderer gives us CJK line-breaking and CSS columns while TextLocator keeps the source offset
 * stable when the viewport, font, or line-height changes.
 */
@Composable
fun EpubReaderHost(
    documentId: String,
    storedFileName: String,
    chapter: ReaderChapter,
    locator: TextLocator,
    locationRevision: Long,
    annotations: List<AnnotationEntity>,
    settings: ReaderSettings,
    onRequestHtml: suspend (Int) -> String,
    onSelection: (Int, Int, Int, String, String, String) -> Unit,
    onLocation: (Int, Int, String?) -> Unit,
    onToggleChrome: () -> Unit,
    onFootnote: (String, String) -> Unit,
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
    ReaderWebHost(
        source = source,
        documentId = documentId,
        chapter = chapter,
        locator = locator,
        locationRevision = locationRevision,
        annotations = annotations,
        html = requireNotNull(html),
        settings = settings,
        onSelection = onSelection,
        onLocation = { charStart, anchor -> onLocation(chapter.index, charStart, anchor) },
        onToggleChrome = onToggleChrome,
        onFootnote = onFootnote,
        modifier = modifier,
    )
}

@Composable
fun TxtReaderHost(
    documentId: String,
    chapter: ReaderChapter,
    locator: TextLocator,
    locationRevision: Long,
    annotations: List<AnnotationEntity>,
    settings: ReaderSettings,
    onSelection: (Int, Int) -> Unit,
    onLocation: (Int, Int, String?) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier,
) {
    val html = remember(chapter.index, chapter.text) { plainTextHtml(chapter.text) }
    ReaderWebHost(
        source = null,
        documentId = documentId,
        chapter = chapter,
        locator = locator,
        locationRevision = locationRevision,
        annotations = annotations,
        html = html,
        settings = settings,
        onSelection = { _, start, end, _, _, _ -> onSelection(start, end) },
        onLocation = { charStart, anchor -> onLocation(chapter.index, charStart, anchor) },
        onToggleChrome = onToggleChrome,
        onFootnote = { _, _ -> Unit },
        modifier = modifier,
    )
}

@Composable
private fun ReaderWebHost(
    source: File?,
    documentId: String,
    chapter: ReaderChapter,
    locator: TextLocator,
    locationRevision: Long,
    annotations: List<AnnotationEntity>,
    html: String,
    settings: ReaderSettings,
    onSelection: (Int, Int, Int, String, String, String) -> Unit,
    onLocation: (Int, String?) -> Unit,
    onToggleChrome: () -> Unit,
    onFootnote: (String, String) -> Unit,
    modifier: Modifier,
) {
    val chapterAnnotations = annotations.filter { it.chapterIndex == chapter.index }
    key("$documentId:${chapter.index}:${chapterAnnotations.joinToString { it.id + it.updatedAt }}") {
        ReaderWebView(
            source = source,
            documentId = documentId,
            chapter = chapter,
            locator = locator,
            locationRevision = locationRevision,
            annotations = chapterAnnotations,
            html = html,
            settings = settings,
            onSelection = onSelection,
            onLocation = onLocation,
            onToggleChrome = onToggleChrome,
            onFootnote = onFootnote,
            modifier = modifier,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderWebView(
    source: File?,
    documentId: String,
    chapter: ReaderChapter,
    locator: TextLocator,
    locationRevision: Long,
    annotations: List<AnnotationEntity>,
    html: String,
    settings: ReaderSettings,
    onSelection: (Int, Int, Int, String, String, String) -> Unit,
    onLocation: (Int, String?) -> Unit,
    onToggleChrome: () -> Unit,
    onFootnote: (String, String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val foreground = MaterialTheme.colorScheme.onSurface.toCssHex()
    val background = MaterialTheme.colorScheme.surface.toCssHex()
    AndroidView(
        factory = {
            val loader = source?.let { file ->
                WebViewAssetLoader.Builder()
                    .addPathHandler("/epub/", EpubZipPathHandler(documentId, file))
                    .build()
            }
            PxReaderWebView(context).apply {
                this.settings.apply {
                    javaScriptEnabled = true // Only the app-injected bridge; EPUB scripts are removed before render.
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                addJavascriptInterface(
                    ReaderBridge(chapter.index, onSelection, onLocation, onToggleChrome, onFootnote),
                    "PXReaderBridge",
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader?.shouldInterceptRequest(request.url) ?: super.shouldInterceptRequest(view, request)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun onPageFinished(view: WebView, url: String) {
                        val readerView = view as? PxReaderWebView ?: return
                        // A WebView may retain its native horizontal offset while replacing HTML.
                        // Reset it before the column geometry is created, then restore the source
                        // locator after layout has a measurable first text node.
                        readerView.scrollTo(0, 0)
                        readerView.postDelayed({
                            readerView.evaluateJavascript(BRIDGE_SCRIPT, null)
                            readerView.evaluateJavascript(readerView.themeScript, null)
                            readerView.appliedThemeScript = readerView.themeScript
                            readerView.evaluateJavascript(readerView.highlightScript, null)
                            readerView.evaluateJavascript(
                                "window.PXReaderLayout && window.PXReaderLayout.restore(${readerView.restoreCharOffset}, ${JSONObject.quote(readerView.restoreAnchor ?: "")});",
                                null,
                            )
                        }, 48)
                    }
                }
            }
        },
        update = { view ->
            view.pagedMode = settings.readingMode.name == "PAGED"
            view.restoreCharOffset = locator.charStart.coerceAtLeast(0)
            view.restoreAnchor = locator.anchor
            view.themeScript = themeScript(settings, foreground, background)
            view.highlightScript = highlightScript(annotations)
            val contentKey = "$documentId:${chapter.index}"
            if (view.loadedContentKey != contentKey) {
                view.loadedContentKey = contentKey
                view.appliedLocationRevision = locationRevision
                view.scrollTo(0, 0)
                val baseUrl = source?.let {
                    "https://appassets.androidplatform.net/epub/$documentId/${chapter.href.orEmpty()}"
                } ?: "https://appassets.androidplatform.net/text/$documentId/${chapter.index}.html"
                view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            } else {
                val themeChanged = view.appliedThemeScript != view.themeScript
                if (themeChanged) {
                    view.evaluateJavascript(view.themeScript, null)
                    view.appliedThemeScript = view.themeScript
                }
                view.evaluateJavascript(view.highlightScript, null)
                when {
                    view.appliedLocationRevision != locationRevision -> {
                        view.appliedLocationRevision = locationRevision
                        view.evaluateJavascript(
                            "window.PXReaderLayout && window.PXReaderLayout.restore(${view.restoreCharOffset}, ${JSONObject.quote(view.restoreAnchor ?: "")});",
                            null,
                        )
                    }
                    themeChanged -> view.evaluateJavascript(
                        "window.PXReaderLayout && window.PXReaderLayout.restore(${view.restoreCharOffset}, ${JSONObject.quote(view.restoreAnchor ?: "")});",
                        null,
                    )
                }
            }
        },
        modifier = modifier,
    )
}

/** Keeps WebView-local state without Android View tags, which require app resource IDs. */
private class PxReaderWebView(context: Context) : WebView(context) {
    private val swipeSlop = ViewConfiguration.get(context).scaledTouchSlop * 2
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var touchMode = TouchMode.UNDECIDED
    private var velocityTracker: VelocityTracker? = null
    private var pendingDragFraction = 0f
    private var dragFramePosted = false
    private val dragFrame = Runnable {
        dragFramePosted = false
        evaluateJavascript(
            "window.PXReaderLayout && window.PXReaderLayout.drag($pendingDragFraction);",
            null,
        )
    }

    var pagedMode: Boolean = false
    var loadedContentKey: String? = null
    var restoreCharOffset: Int = 0
    var restoreAnchor: String? = null
    var appliedLocationRevision: Long = -1L
    var themeScript: String = ""
    var appliedThemeScript: String? = null
    var highlightScript: String = ""

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pagedMode) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                touchMode = TouchMode.UNDECIDED
                pendingDragFraction = 0f
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (touchMode == TouchMode.UNDECIDED) {
                    when {
                        event.eventTime - downAt > LONG_PRESS_GUARD_MS -> {
                            touchMode = TouchMode.SELECTION
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        abs(dx) > swipeSlop || abs(dy) > swipeSlop -> {
                            touchMode = if (abs(dx) > abs(dy) * 1.1f) {
                                cancelLongPress()
                                scrollTo(0, 0)
                                evaluateJavascript(
                                    "window.getSelection && window.getSelection().removeAllRanges(); window.PXReaderLayout && window.PXReaderLayout.beginDrag();",
                                    null,
                                )
                                TouchMode.HORIZONTAL
                            } else {
                                cancelLongPress()
                                scrollTo(0, 0)
                                TouchMode.VERTICAL_BLOCKED
                            }
                        }
                    }
                }
                when (touchMode) {
                    TouchMode.HORIZONTAL -> {
                        scrollTo(0, 0)
                        scheduleDrag(dx / width.coerceAtLeast(1).toFloat())
                        return true
                    }
                    TouchMode.VERTICAL_BLOCKED -> {
                        // Paged reading owns only the horizontal axis.
                        scrollTo(0, 0)
                        return true
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                parent?.requestDisallowInterceptTouchEvent(false)
                when (touchMode) {
                    TouchMode.HORIZONTAL -> {
                        finishDrag(event.x - downX)
                        releaseVelocityTracker()
                        return true
                    }
                    TouchMode.VERTICAL_BLOCKED -> {
                        scrollTo(0, 0)
                        releaseVelocityTracker()
                        return true
                    }
                    else -> releaseVelocityTracker()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (touchMode == TouchMode.HORIZONTAL) {
                    cancelPendingDragFrame()
                    scrollTo(0, 0)
                    evaluateJavascript("window.PXReaderLayout && window.PXReaderLayout.finishDrag(0, 0);", null)
                    releaseVelocityTracker()
                    return true
                }
                if (touchMode == TouchMode.VERTICAL_BLOCKED) {
                    scrollTo(0, 0)
                    releaseVelocityTracker()
                    return true
                }
                releaseVelocityTracker()
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scheduleDrag(fraction: Float) {
        pendingDragFraction = fraction.coerceIn(-1f, 1f)
        if (dragFramePosted) return
        dragFramePosted = true
        postOnAnimation(dragFrame)
    }

    private fun finishDrag(dx: Float) {
        cancelPendingDragFrame()
        val widthPx = width.coerceAtLeast(1).toFloat()
        val fraction = (dx / widthPx).coerceIn(-1f, 1f)
        velocityTracker?.computeCurrentVelocity(1000)
        val screensPerSecond = (velocityTracker?.xVelocity ?: 0f) / widthPx
        val projected = fraction + screensPerSecond * FLING_PROJECTION_SECONDS
        val delta = when {
            projected <= -SETTLE_THRESHOLD || screensPerSecond <= -FLING_THRESHOLD -> 1
            projected >= SETTLE_THRESHOLD || screensPerSecond >= FLING_THRESHOLD -> -1
            else -> 0
        }
        scrollTo(0, 0)
        evaluateJavascript(
            "window.PXReaderLayout && (window.PXReaderLayout.drag($fraction), window.PXReaderLayout.finishDrag($delta, ${abs(screensPerSecond)}));",
            null,
        )
    }

    private fun cancelPendingDragFrame() {
        if (!dragFramePosted) return
        removeCallbacks(dragFrame)
        dragFramePosted = false
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private enum class TouchMode { UNDECIDED, HORIZONTAL, VERTICAL_BLOCKED, SELECTION }

    private companion object {
        const val LONG_PRESS_GUARD_MS = 480L
        const val FLING_PROJECTION_SECONDS = 0.18f
        const val SETTLE_THRESHOLD = 0.22f
        const val FLING_THRESHOLD = 0.72f
    }
}

private class ReaderBridge(
    private val chapterIndex: Int,
    private val selection: (Int, Int, Int, String, String, String) -> Unit,
    private val location: (Int, String?) -> Unit,
    private val toggleChrome: () -> Unit,
    private val footnote: (String, String) -> Unit,
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
    fun location(payload: String) {
        runCatching {
            val value = JSONObject(payload)
            val start = value.getInt("start")
            val anchor = value.optString("anchor").takeIf { it.isNotBlank() }
            mainHandler.post { location(start, anchor) }
        }
    }

    @JavascriptInterface
    fun toggleChrome() = mainHandler.post(toggleChrome)

    @JavascriptInterface
    fun footnote(payload: String) {
        runCatching {
            val value = JSONObject(payload)
            mainHandler.post { footnote(value.optString("label", "注释"), value.optString("text")) }
        }
    }
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
                WebResourceResponse(
                    mime,
                    if (mime.startsWith("text/") || mime.contains("xml") || mime.contains("css")) "utf-8" else null,
                    bytes.inputStream(),
                )
            }
        }.getOrNull()
    }
}

private fun themeScript(settings: ReaderSettings, foreground: String, background: String): String {
    val mode = if (settings.readingMode.name == "PAGED") "paged" else "scroll"
    val font = if (settings.readerFont.name == "SERIF") {
        "'Noto Serif CJK SC','Source Han Serif SC','Noto Serif',serif"
    } else {
        "'Noto Sans CJK SC','Source Han Sans SC','Noto Sans',sans-serif"
    }
    val align = if (settings.justified) "justify" else "start"
    val indent = if (settings.firstLineIndent) "2em" else "0"
    val css = """
        :root{color-scheme:light;background:$background;color:$foreground;--px-content-inset:1em}
        html,body{margin:0;min-height:100%;background:$background;color:$foreground}
        body{box-sizing:border-box;width:100vw !important;min-width:100vw !important;max-width:none !important;padding:0 !important;font-family:$font !important;font-size:${settings.fontScale}rem !important;line-height:${settings.lineHeight} !important;letter-spacing:${settings.letterSpacing}em !important;font-kerning:normal;font-variant-east-asian:proportional-width;line-break:strict;word-break:normal;overflow-wrap:anywhere;-webkit-text-size-adjust:100%;text-autospace:normal}
        p,li,blockquote{text-align:$align;text-justify:inter-ideograph}
        p{margin:0 0 ${settings.paragraphSpacing}em;text-indent:$indent}
        p:empty{min-height:${settings.paragraphSpacing}em}
        h1,h2,h3,h4,h5,h6{line-height:1.32;break-after:avoid;break-inside:avoid;margin:1.65em 0 .72em;text-indent:0}
        blockquote{margin:1em 0;padding-left:1em;border-left:3px solid color-mix(in srgb,$foreground 18%,transparent);text-indent:0}
        img,svg,video,canvas{max-width:100% !important;max-inline-size:100% !important;height:auto !important;object-fit:contain}
        table{max-width:100% !important;display:block;overflow:auto} pre{white-space:pre-wrap;word-break:break-word;tab-size:2} code{font-family:monospace;font-size:.9em}
        ruby{ruby-position:over} rt{font-size:.52em;letter-spacing:0} mark[data-px-annotation-id]{color:inherit;border-radius:.16em;padding:0 .03em}
        html.px-paged{height:var(--px-page-height,100vh) !important;min-height:var(--px-page-height,100vh) !important;overflow:hidden !important;scroll-behavior:auto;overscroll-behavior:none;touch-action:pan-x}
        html.px-paged body{height:var(--px-page-height,100vh) !important;min-height:var(--px-page-height,100vh) !important;max-height:var(--px-page-height,100vh) !important;padding:var(--px-content-inset) !important;column-width:calc(100vw - 2em);column-gap:2em;column-fill:auto;overflow:visible;overscroll-behavior:none;touch-action:pan-x}
        html.px-paged p,html.px-paged li,html.px-paged blockquote{orphans:1;widows:1}
        html.px-paged h1,html.px-paged h2,html.px-paged h3,html.px-paged figure,html.px-paged pre,html.px-paged table{break-inside:avoid}
        html.px-scroll{overflow-x:hidden;overflow-y:auto;scroll-behavior:smooth}
        html.px-scroll body{max-width:46rem !important;margin:auto !important;padding:var(--px-content-inset) !important}
    """.trimIndent().replace("\n", " ")
    return """
        (() => {
          const viewport = window.visualViewport;
          document.documentElement.style.setProperty('--px-page-height', Math.max(1, Math.round((viewport && viewport.height) || window.innerHeight)) + 'px');
          let style = document.getElementById('px-reader-theme');
          if (!style) { style = document.createElement('style'); style.id = 'px-reader-theme'; document.head.appendChild(style); }
          style.textContent = ${JSONObject.quote(css)};
          document.documentElement.classList.remove('px-paged','px-scroll');
          document.documentElement.classList.add('px-$mode');
        })();
    """.trimIndent()
}

private fun Color.toCssHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

private fun highlightScript(annotations: List<AnnotationEntity>): String {
    val payload = org.json.JSONArray().apply {
        annotations.forEach { annotation ->
            put(JSONObject().apply {
                put("id", annotation.id)
                put("start", annotation.charStart)
                put("end", annotation.charEnd)
                put("quote", annotation.quote)
                put("color", annotation.color)
            })
        }
    }
    return "window.PXReaderHighlights && window.PXReaderHighlights.apply(${JSONObject.quote(payload.toString())});"
}

private fun plainTextHtml(text: String): String {
    val body = text.replace("\r\n", "\n").replace('\r', '\n')
        .split(Regex("\\n[\\t ]*\\n+"))
        .joinToString("\n") { paragraph ->
            "<p>${TextUtils.htmlEncode(paragraph.trim()).replace("\n", "<br>")}</p>"
        }
    return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1\"></head><body>$body</body></html>"
}

private const val BRIDGE_SCRIPT = """
(() => {
  if (window.__pxReaderBridgeInstalled) return;
  window.__pxReaderBridgeInstalled = true;
  const body = () => document.body;
  const visualViewport = () => window.visualViewport || {width: window.innerWidth, height: window.innerHeight};
  // window.scrollX and CSS multi-columns both use CSS pixels. devicePixelRatio must not be
  // applied here: doing so skips multiple columns on high-density phone screens.
  const pageWidth = () => Math.max(1, Math.round(visualViewport().width));
  const walker = () => document.createTreeWalker(body(), NodeFilter.SHOW_TEXT);
  const textOffset = (node, point) => {
    let total = 0, current, tree = walker();
    while ((current = tree.nextNode())) {
      if (current === node) return total + Math.min(point, current.nodeValue.length);
      total += current.nodeValue.length;
    }
    return total;
  };
  const textPoint = (offset) => {
    let total = 0, current, tree = walker();
    while ((current = tree.nextNode())) {
      const next = total + current.nodeValue.length;
      if (offset <= next) return {node:current, point:Math.max(0, offset-total)};
      total = next;
    }
    return null;
  };
  const nodeAnchor = (node, point) => {
    const parts = [];
    while (node && node !== body()) {
      const parent = node.parentNode;
      if (!parent) return '';
      parts.unshift(Array.prototype.indexOf.call(parent.childNodes, node));
      node = parent;
    }
    return node === body() ? parts.join('.') + '@' + point : '';
  };
  const anchorPoint = (anchor) => {
    if (!anchor || !anchor.includes('@')) return null;
    const pair = anchor.split('@'), path = pair[0]; let node = body();
    if (!node) return null;
    if (path) for (const index of path.split('.')) {
      node = node && node.childNodes[Number(index)];
      if (!node) return null;
    }
    return node && node.nodeType === Node.TEXT_NODE ? {node, point:Number(pair[1]) || 0} : null;
  };
  const caretAt = () => {
    const width = visualViewport().width;
    const x = Math.min(Math.max(18, width * .12), width - 18), y = 28;
    if (document.caretRangeFromPoint) {
      const range = document.caretRangeFromPoint(x, y);
      if (range && range.startContainer && range.startContainer.nodeType === Node.TEXT_NODE) return {node:range.startContainer, point:range.startOffset};
    }
    if (document.caretPositionFromPoint) {
      const pos = document.caretPositionFromPoint(x, y);
      if (pos && pos.offsetNode && pos.offsetNode.nodeType === Node.TEXT_NODE) return {node:pos.offsetNode, point:pos.offset};
    }
    return textPoint(0);
  };
  const report = () => {
    const point = caretAt();
    if (!point) return;
    window.PXReaderBridge.location(JSON.stringify({start:textOffset(point.node, point.point), anchor:nodeAnchor(point.node, point.point)}));
  };
  const rangeFor = (offset, anchor) => {
    const point = anchorPoint(anchor) || textPoint(offset);
    if (!point) return null;
    const range = document.createRange(); range.setStart(point.node, Math.min(point.point, point.node.nodeValue.length)); range.collapse(true); return range;
  };
  const drawablePoint = (point) => {
    const candidates = [point]; let found = false, current, tree = walker();
    while ((current = tree.nextNode())) {
      if (current === point.node) { found = true; continue; }
      if (found && current.nodeValue.trim()) candidates.push({node:current, point:0});
    }
    for (const candidate of candidates) {
      const range = document.createRange(); range.setStart(candidate.node, Math.min(candidate.point, candidate.node.nodeValue.length)); range.collapse(true);
      if (range.getClientRects()[0]) return candidate;
    }
    return point;
  };
  const scrollToPoint = (point) => {
    point = drawablePoint(point);
    const range = document.createRange(); range.setStart(point.node, Math.min(point.point, point.node.nodeValue.length)); range.collapse(true);
    const rect = range.getClientRects()[0];
    if (!rect) return;
    if (document.documentElement.classList.contains('px-paged')) {
      const absoluteLeft = rect.left + window.scrollX;
      const width = pageWidth();
      const target = Math.max(0, Math.floor(absoluteLeft / width) * width);
      window.scrollBy({left:target - window.scrollX, top:-window.scrollY, behavior:'instant'});
    } else {
      const target = Math.max(0, rect.top + window.scrollY - 24);
      window.scrollBy({left:-window.scrollX, top:target - window.scrollY, behavior:'instant'});
    }
  };
  let pageMotion = false;
  let pageAnimation = 0;
  let dragState = null;
  const pageMetrics = () => {
    const width = pageWidth();
    const scrollWidth = Math.max(document.documentElement.scrollWidth, document.body.scrollWidth);
    return {width, max:Math.max(0, scrollWidth - width), count:Math.max(1, Math.ceil(scrollWidth / width))};
  };
  const stopPageAnimation = () => {
    if (!pageAnimation) return;
    cancelAnimationFrame(pageAnimation);
    pageAnimation = 0;
  };
  const pageLeft = (index, metrics) => Math.min(metrics.max, Math.max(0, index) * metrics.width);
  const animateToPage = (index, releaseVelocity = 0) => {
    stopPageAnimation();
    const metrics = pageMetrics();
    const page = Math.max(0, Math.min(metrics.count - 1, index));
    const start = window.scrollX;
    const target = pageLeft(page, metrics);
    const distance = target - start;
    pageMotion = true;
    if (Math.abs(distance) < .5) {
      window.scrollTo({left:target, top:0, behavior:'instant'});
      pageMotion = false;
      report();
      return;
    }
    const distanceRatio = Math.min(1, Math.abs(distance) / metrics.width);
    const velocity = Math.min(3, Math.abs(Number(releaseVelocity) || 0));
    const duration = Math.max(140, Math.min(300, 170 + distanceRatio * 110 - velocity * 24));
    const startedAt = performance.now();
    const frame = (now) => {
      const progress = Math.min(1, (now - startedAt) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      window.scrollTo({left:start + distance * eased, top:0, behavior:'instant'});
      if (progress < 1) {
        pageAnimation = requestAnimationFrame(frame);
      } else {
        pageAnimation = 0;
        window.scrollTo({left:target, top:0, behavior:'instant'});
        pageMotion = false;
        report();
      }
    };
    pageAnimation = requestAnimationFrame(frame);
  };
  window.PXReaderLayout = {
    current: () => { const point = caretAt(); return point ? {start:textOffset(point.node, point.point), anchor:nodeAnchor(point.node, point.point)} : {start:0, anchor:''}; },
    restore: (start, anchor) => requestAnimationFrame(() => {
      stopPageAnimation();
      dragState = null;
      pageMotion = false;
      const range = rangeFor(Number(start) || 0, anchor);
      if (range) scrollToPoint({node:range.startContainer, point:range.startOffset});
      setTimeout(report, 80);
    }),
    reflow: (locator) => requestAnimationFrame(() => { const saved = locator || window.PXReaderLayout.current(); window.PXReaderLayout.restore(saved.start, saved.anchor); }),
    beginDrag: () => {
      if (!document.documentElement.classList.contains('px-paged')) return;
      stopPageAnimation();
      const metrics = pageMetrics();
      const page = Math.max(0, Math.min(metrics.count - 1, Math.round(window.scrollX / metrics.width)));
      const origin = pageLeft(page, metrics);
      dragState = {page, origin, metrics};
      pageMotion = true;
      window.scrollTo({left:origin, top:0, behavior:'instant'});
    },
    drag: (fraction) => {
      if (!dragState) return;
      const amount = Math.max(-1, Math.min(1, Number(fraction) || 0));
      const target = dragState.origin - amount * dragState.metrics.width;
      window.scrollTo({left:Math.max(0, Math.min(dragState.metrics.max, target)), top:0, behavior:'instant'});
    },
    finishDrag: (delta, releaseVelocity) => {
      const state = dragState;
      dragState = null;
      const metrics = state ? state.metrics : pageMetrics();
      const page = state ? state.page : Math.round(window.scrollX / metrics.width);
      const step = Math.max(-1, Math.min(1, Math.sign(Number(delta) || 0)));
      animateToPage(page + step, releaseVelocity);
    },
    page: (delta) => {
      if (!document.documentElement.classList.contains('px-paged')) return;
      dragState = null;
      const width = pageWidth();
      animateToPage(Math.round(window.scrollX / width) + Math.sign(delta), 0);
    },
  };
  const annotationColor = (color) => color === 'green' ? '#caeece' : color === 'blue' ? '#bbdefb' : color === 'pink' ? '#f8bbd0' : color === 'orange' ? '#ffe0b2' : '#fff59d';
  const markRange = (start, end, color, id) => {
    if (end <= start) return;
    const nodes = []; let current, tree = walker();
    while ((current = tree.nextNode())) {
      if (!current.parentElement || current.parentElement.closest('mark[data-px-annotation-id]')) continue;
      nodes.push({node:current, start:textOffset(current, 0), end:textOffset(current, current.nodeValue.length)});
    }
    nodes.forEach((item) => {
      if (item.end <= start || item.start >= end) return;
      const range = document.createRange();
      range.setStart(item.node, Math.max(0, start - item.start));
      range.setEnd(item.node, Math.min(item.node.nodeValue.length, end - item.start));
      if (range.collapsed) return;
      const mark = document.createElement('mark');
      mark.dataset.pxAnnotationId = id; mark.style.background = annotationColor(color);
      range.surroundContents(mark);
    });
  };
  window.PXReaderHighlights = {
    apply: (raw) => {
      if (body().dataset.pxHighlights === raw) return;
      let annotations = []; try { annotations = JSON.parse(raw); } catch (_) { return; }
      annotations.sort((a,b) => Number(b.start) - Number(a.start)).forEach((item) => markRange(Number(item.start), Number(item.end), item.color, item.id));
      body().dataset.pxHighlights = raw;
    }
  };
  let selectionTimer;
  const reportSelection = () => {
    clearTimeout(selectionTimer);
    selectionTimer = setTimeout(() => {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return;
      const range = selection.getRangeAt(0), quote = selection.toString().trim();
      if (!quote) return;
      const text = body().innerText || '';
      const start = textOffset(range.startContainer, range.startOffset), end = textOffset(range.endContainer, range.endOffset);
      window.PXReaderBridge.selection(JSON.stringify({start,end,quote,prefix:text.slice(Math.max(0,start-48),start),suffix:text.slice(end,end+48)}));
    }, 120);
  };
  document.addEventListener('selectionchange', reportSelection);
  document.addEventListener('click', (event) => {
    const link = event.target.closest && event.target.closest('a[href^="#"]');
    if (link) {
      const target = document.getElementById(decodeURIComponent(link.getAttribute('href').slice(1)));
      const looksLikeFootnote = target && /footnote|footnotes|endnote|note|fn/i.test("" + link.id + " " + link.className + " " + target.id + " " + target.className);
      if (looksLikeFootnote) { event.preventDefault(); window.PXReaderBridge.footnote(JSON.stringify({label:link.textContent.trim() || '注释',text:target.innerText.trim()})); return; }
    }
    if (!window.getSelection()?.isCollapsed) return;
    const x = event.clientX / visualViewport().width;
    if (document.documentElement.classList.contains('px-paged') && x < .26) window.PXReaderLayout.page(-1);
    else if (document.documentElement.classList.contains('px-paged') && x > .74) window.PXReaderLayout.page(1);
    else if (x >= .26 && x <= .74) window.PXReaderBridge.toggleChrome();
  });
  let pending = false;
  window.addEventListener('scroll', () => {
    if (pageMotion) return;
    if (pending) return; pending = true;
    requestAnimationFrame(() => { pending = false; report(); });
  }, {passive:true});
  window.addEventListener('resize', () => {
    const saved = window.PXReaderLayout.current(), viewport = window.visualViewport;
    document.documentElement.style.setProperty('--px-page-height', Math.max(1, Math.round((viewport && viewport.height) || window.innerHeight)) + 'px');
    setTimeout(() => window.PXReaderLayout.restore(saved.start, saved.anchor), 0);
  });
})();
"""
