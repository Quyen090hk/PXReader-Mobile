package io.github.quyen090hk.pxreader.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.quyen090hk.pxreader.data.AnnotationColor
import io.github.quyen090hk.pxreader.data.DocumentFormat
import io.github.quyen090hk.pxreader.data.ReaderChapter
import io.github.quyen090hk.pxreader.data.ReaderRepository
import io.github.quyen090hk.pxreader.data.TextLocator
import io.github.quyen090hk.pxreader.data.db.BookmarkEntity
import io.github.quyen090hk.pxreader.settings.ReaderSettings
import io.github.quyen090hk.pxreader.ui.PxReaderViewModelFactory
import io.github.quyen090hk.pxreader.ui.ReaderViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(model, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.persistCurrentPosition()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            model.persistCurrentPosition()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
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
    val context = LocalContext.current
    var searchOpen by remember { mutableStateOf(false) }
    var annotationOpen by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var footnote by remember { mutableStateOf<ReaderFootnote?>(null) }
    val localView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(chromeVisible, context, localView) {
        val controller = context.activityOrNull()?.window?.let { window ->
            WindowInsetsControllerCompat(window, localView)
        }
        if (chromeVisible) controller?.show(WindowInsetsCompat.Type.systemBars())
        else controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val bookmarkAtCurrentLocation = state.locator?.let { locator ->
        state.bookmarks.any { it.chapterIndex == locator.chapterIndex && it.charStart == locator.charStart }
    } == true
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                    Text("阅读导航", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                    Text("书签和目录都在这里", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                        if (state.bookmarks.isNotEmpty()) {
                            item {
                                Text("书签", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                            }
                            items(state.bookmarks, key = BookmarkEntity::id) { bookmark ->
                                TextButton(
                                    onClick = {
                                        model.navigateTo(bookmark.toLocator())
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(bookmark.label ?: "第 ${bookmark.chapterIndex + 1} 章", maxLines = 1)
                                        Text("${(bookmark.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
                        }
                        item {
                            Text("目录", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                        }
                        items(reader?.chapters.orEmpty(), key = ReaderChapter::index) { chapter ->
                            TextButton(
                                onClick = {
                                    model.navigateTo(TextLocator.atChapterStart(chapter.index, chapter.href))
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    chapter.title,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 2,
                                    color = if (chapter.index == state.locator?.chapterIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (chromeVisible) {
                    CenterAlignedTopAppBar(
                        title = { Text(reader?.document?.title ?: "PXReader", maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = { model.persistCurrentPosition(); onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回书架") }
                        },
                        actions = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Outlined.Menu, contentDescription = "目录") }
                            IconButton(onClick = model::toggleBookmark) {
                                Icon(
                                    if (bookmarkAtCurrentLocation) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkAdd,
                                    contentDescription = if (bookmarkAtCurrentLocation) "移除书签" else "添加书签",
                                )
                            }
                            IconButton(onClick = { searchOpen = true }) { Icon(Icons.Outlined.Search, contentDescription = "搜索全文") }
                            IconButton(onClick = { reader?.let { onAnnotations(it.document.id) } }) { Icon(Icons.Outlined.MoreVert, contentDescription = "批注") }
                            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "阅读设置") }
                        },
                    )
                }
            },
        ) { padding ->
            when {
                state.loading -> Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Text("正在打开文档…", modifier = Modifier.padding(top = 12.dp))
                }
                state.error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) { Text(state.error, color = MaterialTheme.colorScheme.error) }
                reader != null && state.locator != null -> {
                    val chapter = reader.chapters.getOrNull(state.locator.chapterIndex) ?: reader.chapters.first()
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (chromeVisible) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                            ) {
                                Text(
                                    chapter.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                )
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (reader.format == DocumentFormat.TXT) {
                                TxtReaderHost(
                                    documentId = reader.document.id,
                                    chapter = chapter,
                                    locator = state.locator,
                                    annotations = state.annotations,
                                    settings = settings,
                                    onSelection = model::selectText,
                                    onLocation = model::updateEpubLocation,
                                    onToggleChrome = { chromeVisible = !chromeVisible },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                EpubReaderHost(
                                    documentId = reader.document.id,
                                    storedFileName = reader.document.storedFileName,
                                    chapter = chapter,
                                    locator = state.locator,
                                    annotations = state.annotations,
                                    settings = settings,
                                    onRequestHtml = model::epubHtml,
                                    onSelection = model::selectEpubText,
                                    onLocation = model::updateEpubLocation,
                                    onToggleChrome = { chromeVisible = !chromeVisible },
                                    onFootnote = { label, text -> footnote = ReaderFootnote(label, text) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            state.pendingSelection?.let { selection ->
                                Surface(
                                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(16.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    tonalElevation = 5.dp,
                                    shadowElevation = 4.dp,
                                ) {
                                    Row(
                                        Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    ) {
                                        Text("已选择 ${selection.quote.take(18)}", modifier = Modifier.weight(1f), maxLines = 1)
                                        TextButton(
                                            onClick = {
                                                context.getSystemService(ClipboardManager::class.java)
                                                    ?.setPrimaryClip(ClipData.newPlainText("PXReader 选文", selection.quote))
                                                model.clearSelection()
                                            },
                                        ) { Text("复制") }
                                        TextButton(onClick = { model.saveAnnotation("", AnnotationColor.YELLOW) }) { Text("标黄") }
                                        TextButton(onClick = model::clearSelection) { Text("取消") }
                                        Button(onClick = { annotationOpen = true }) { Text("写想法") }
                                    }
                                }
                            }
                        }
                        if (chromeVisible) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("第 ${chapter.index + 1} / ${reader.chapters.size} 章", style = MaterialTheme.typography.labelMedium)
                                        Text("${clockLabel()} · 余 ${remainingTimeLabel(chapter, state.locator)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    LinearProgressIndicator(
                                        progress = { state.locator.progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = model::previousChapter, enabled = chapter.index > 0) { Text("上一章") }
                                        Text("${(state.locator.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                                        TextButton(onClick = model::nextChapter, enabled = chapter.index < reader.chapters.lastIndex) { Text("下一章") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (searchOpen) SearchDialog(state, onDismiss = { searchOpen = false }, onSearch = model::search, onOpen = { hit -> model.navigateTo(hit.locator); searchOpen = false })
    if (annotationOpen) AnnotationDialog(onDismiss = { annotationOpen = false }, onSave = { note, color -> model.saveAnnotation(note, color); annotationOpen = false })
    footnote?.let { note ->
        ModalBottomSheet(onDismissRequest = { footnote = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(note.label, style = MaterialTheme.typography.titleMedium)
                Text(note.text.ifBlank { "此注释没有可显示的内容。" }, modifier = Modifier.padding(top = 12.dp, bottom = 28.dp))
            }
        }
    }
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

private data class ReaderFootnote(val label: String, val text: String)

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

private fun clockLabel(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun remainingTimeLabel(chapter: ReaderChapter, locator: TextLocator): String {
    // Chinese reading speed varies widely; 360 characters per minute is a calm, conservative
    // default and intentionally reports an estimate rather than a false-precise countdown.
    val remainingCharacters = (chapter.text.length - locator.charStart).coerceAtLeast(0)
    val minutes = (remainingCharacters / 360f).toInt().coerceAtLeast(1)
    return if (minutes < 60) "${minutes} 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分"
}

private fun AnnotationColor.label() = when (this) {
    AnnotationColor.YELLOW -> "黄"; AnnotationColor.GREEN -> "绿"; AnnotationColor.BLUE -> "蓝"; AnnotationColor.PINK -> "粉"; AnnotationColor.ORANGE -> "橙"
}

private fun BookmarkEntity.toLocator() = TextLocator(
    chapterIndex = chapterIndex,
    chapterHref = chapterHref,
    charStart = charStart,
    charEnd = charEnd,
    progress = progress,
    quote = quote,
    prefix = prefix,
    suffix = suffix,
    anchor = anchor,
)
