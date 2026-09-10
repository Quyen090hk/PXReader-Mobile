package io.github.quyen090hk.pxreader.ui.screens

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.quyen090hk.pxreader.data.AnnotationColor
import io.github.quyen090hk.pxreader.data.DocumentFormat
import io.github.quyen090hk.pxreader.data.ReaderChapter
import io.github.quyen090hk.pxreader.data.ReaderRepository
import io.github.quyen090hk.pxreader.data.TextLocator
import io.github.quyen090hk.pxreader.data.db.AnnotationEntity
import io.github.quyen090hk.pxreader.settings.ReaderSettings
import io.github.quyen090hk.pxreader.ui.PxReaderViewModelFactory
import io.github.quyen090hk.pxreader.ui.ReaderViewModel
import kotlinx.coroutines.launch

@Composable
fun ReaderRoute(
    documentId: String,
    repository: ReaderRepository,
    settings: ReaderSettings,
    initialLocator: TextLocator?,
    onBack: () -> Unit,
    onAnnotations: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val model: ReaderViewModel = viewModel(
        key = documentId,
        factory = remember(documentId) { PxReaderViewModelFactory { ReaderViewModel(documentId, repository) } },
    )
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.reader, initialLocator) {
        if (state.reader != null && initialLocator != null) model.navigateTo(initialLocator)
    }
    ReaderScreen(state, model, settings, onBack, onAnnotations, onSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    state: io.github.quyen090hk.pxreader.ui.ReaderUiState,
    model: ReaderViewModel,
    settings: ReaderSettings,
    onBack: () -> Unit,
    onAnnotations: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val reader = state.reader
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var searchOpen by remember { mutableStateOf(false) }
    var annotationOpen by remember { mutableStateOf(false) }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("目录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                LazyColumn {
                    items(reader?.chapters.orEmpty()) { chapter ->
                        TextButton(
                            onClick = {
                                model.navigateTo(TextLocator.atChapterStart(chapter.index, chapter.href))
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        ) { Text(chapter.title, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(reader?.document?.title ?: "PXReader", maxLines = 1) },
                    navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                    actions = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) { Text("目录") }
                        TextButton(onClick = { searchOpen = true }) { Text("搜索") }
                        TextButton(onClick = { reader?.let { onAnnotations(it.document.id) } }) { Text("批注") }
                        TextButton(onClick = onSettings) { Text("设置") }
                    },
                )
            },
        ) { padding ->
            when {
                state.loading -> Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Text("正在打开文档…", modifier = Modifier.padding(top = 12.dp))
                }
                state.error != null -> Column(Modifier.fillMaxSize().padding(24.dp)) { Text(state.error, color = MaterialTheme.colorScheme.error) }
                reader != null && state.locator != null -> {
                    val chapter = reader.chapters.getOrNull(state.locator.chapterIndex) ?: reader.chapters.first()
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Text(
                            "${chapter.title} · ${(state.locator.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                        if (reader.format == DocumentFormat.TXT) {
                            TextReaderSurface(
                                chapter = chapter,
                                locator = state.locator,
                                locationRevision = state.locationRevision,
                                annotations = state.annotations,
                                settings = settings,
                                onSelection = model::selectText,
                                onProgress = model::updateProgress,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            EpubReaderHost(
                                documentId = reader.document.id,
                                storedFileName = reader.document.storedFileName,
                                chapter = chapter,
                                locator = state.locator,
                                annotations = state.annotations,
                                fontScale = settings.fontScale,
                                lineHeight = settings.lineHeight,
                                onRequestHtml = model::epubHtml,
                                onSelection = model::selectEpubText,
                                onProgress = model::updateProgress,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        state.pendingSelection?.let { selection ->
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("已选择 ${selection.quote.take(18)}", modifier = Modifier.weight(1f), maxLines = 1)
                                TextButton(onClick = model::clearSelection) { Text("取消") }
                                Button(onClick = { annotationOpen = true }) { Text("添加批注") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (searchOpen) SearchDialog(state, onDismiss = { searchOpen = false }, onSearch = model::search, onOpen = { hit -> model.navigateTo(hit.locator); searchOpen = false })
    if (annotationOpen) AnnotationDialog(onDismiss = { annotationOpen = false }, onSave = { note, color -> model.saveAnnotation(note, color); annotationOpen = false })
}

@Composable
private fun SearchDialog(
    state: io.github.quyen090hk.pxreader.ui.ReaderUiState,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onOpen: (io.github.quyen090hk.pxreader.data.SearchHit) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("全文搜索") },
        text = {
            Column {
                Row {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("关键词") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSearch(query) }, enabled = query.isNotBlank() && !state.searching) { Text("搜索") }
                }
                if (state.searching) CircularProgressIndicator(Modifier.padding(12.dp))
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(state.searchHits) { hit ->
                        TextButton(onClick = { onOpen(hit) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(hit.title, style = MaterialTheme.typography.labelLarge)
                                Text(hit.snippet, maxLines = 2)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun AnnotationDialog(onDismiss: () -> Unit, onSave: (String, AnnotationColor) -> Unit) {
    var note by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(AnnotationColor.YELLOW) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加高亮或笔记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("笔记（可选）") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnnotationColor.entries.forEach { item ->
                        TextButton(onClick = { color = item }) { Text(if (item == color) "● ${item.label()}" else item.label()) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(note, color) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TextReaderSurface(
    chapter: ReaderChapter,
    locator: TextLocator,
    locationRevision: Long,
    annotations: List<AnnotationEntity>,
    settings: ReaderSettings,
    onSelection: (Int, Int) -> Unit,
    onProgress: (Float) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            ScrollView(context).apply {
                isFillViewport = true
                addView(SelectionAwareTextView(context).apply {
                    setTextIsSelectable(true)
                    setPadding(32, 24, 32, 48)
                    gravity = Gravity.START
                })
                setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    val maximum = (getChildAt(0).height - height).coerceAtLeast(1)
                    onProgress(scrollY.toFloat() / maximum)
                }
            }
        },
        update = { scroll ->
            val textView = scroll.getChildAt(0) as SelectionAwareTextView
            textView.onSelection = { start, end -> onSelection(start, end) }
            textView.textSize = 18f * settings.fontScale
            textView.setLineSpacing(0f, settings.lineHeight)
            textView.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textView.text = chapter.spanned(annotations)
            val revisionKey = "${chapter.index}:$locationRevision"
            if (scroll.tag != revisionKey) {
                scroll.tag = revisionKey
                scroll.post {
                    val maximum = (textView.height - scroll.height).coerceAtLeast(0)
                    val ratio = if (chapter.text.isEmpty()) 0f else locator.charStart.toFloat() / chapter.text.length
                    scroll.scrollTo(0, (maximum * ratio).toInt())
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

private class SelectionAwareTextView(context: android.content.Context) : TextView(context) {
    var onSelection: ((Int, Int) -> Unit)? = null
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (selStart >= 0 && selEnd > selStart) onSelection?.invoke(selStart, selEnd)
    }
}

private fun ReaderChapter.spanned(annotations: List<AnnotationEntity>): SpannableString {
    val styled = SpannableString(text)
    annotations.filter { it.chapterIndex == index }.forEach { annotation ->
        val start = annotation.charStart.coerceIn(0, text.length)
        val end = annotation.charEnd.coerceIn(start, text.length)
        if (end > start) styled.setSpan(BackgroundColorSpan(annotation.colorArgb()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return styled
}

private fun AnnotationEntity.colorArgb(): Int = when (color.lowercase()) {
    "green" -> AndroidColor.rgb(202, 238, 206)
    "blue" -> AndroidColor.rgb(187, 222, 251)
    "pink" -> AndroidColor.rgb(248, 187, 208)
    "orange" -> AndroidColor.rgb(255, 224, 178)
    else -> AndroidColor.rgb(255, 245, 157)
}

private fun AnnotationColor.label() = when (this) {
    AnnotationColor.YELLOW -> "黄"; AnnotationColor.GREEN -> "绿"; AnnotationColor.BLUE -> "蓝"; AnnotationColor.PINK -> "粉"; AnnotationColor.ORANGE -> "橙"
}
