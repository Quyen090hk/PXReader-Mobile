package io.github.quyen090hk.pxreader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.quyen090hk.pxreader.backup.BackupExporter
import io.github.quyen090hk.pxreader.data.ReaderRepository
import io.github.quyen090hk.pxreader.data.db.DocumentEntity
import io.github.quyen090hk.pxreader.importer.DocumentImporter
import io.github.quyen090hk.pxreader.ui.LibraryViewModel
import io.github.quyen090hk.pxreader.ui.PxReaderViewModelFactory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryRoute(
    repository: ReaderRepository,
    importer: DocumentImporter,
    backupExporter: BackupExporter,
    incoming: StateFlow<List<Uri>>,
    onIncomingConsumed: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val model: LibraryViewModel = viewModel(factory = remember { PxReaderViewModelFactory { LibraryViewModel(repository, importer) } })
    val incomingUris by incoming.collectAsStateWithLifecycle()
    LaunchedEffect(incomingUris) {
        if (incomingUris.isNotEmpty()) {
            model.importUris(incomingUris, onOpenDocument)
            onIncomingConsumed()
        }
    }
    LibraryScreen(model, backupExporter, onOpenDocument, onSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    model: LibraryViewModel,
    backupExporter: BackupExporter,
    onOpenDocument: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.importUris(uris, onOpenDocument)
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { backupExporter.exportBackup(uri) }
    }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var editTagsFor by remember { mutableStateOf<DocumentEntity?>(null) }
    val allTags = remember(state.documents) { state.documents.flatMap { it.tags() }.distinct().sorted() }
    val visibleDocuments = state.documents.filter { selectedTag == null || selectedTag in it.tags() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PXReader", style = MaterialTheme.typography.titleLarge)
                        Text("YOUR LOCAL LIBRARY", style = MaterialTheme.typography.labelSmall, letterSpacing = 0.12.em)
                    }
                },
                actions = {
                    IconButton(onClick = { backupPicker.launch("pxreader-backup-v1.json") }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "导出备份")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "阅读设置")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = padding.calculateTopPadding() + 8.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                LibraryHero(
                    documentCount = state.documents.size,
                    onImport = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
                )
            }
            state.message?.let { message ->
                item { AssistChip(onClick = model::consumeMessage, label = { Text(message) }) }
            }
            if (state.importing) {
                item {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("正在整理你的新文档…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (allTags.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = selectedTag == null, onClick = { selectedTag = null }, label = { Text("全部") })
                        }
                        items(allTags, key = { it }) { tag ->
                            FilterChip(selected = selectedTag == tag, onClick = { selectedTag = tag }, label = { Text(tag) })
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("你的文档", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                    Text("${visibleDocuments.size} 本", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (visibleDocuments.isEmpty()) {
                item { EmptyLibrary(onImport = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) }) }
            } else {
                items(visibleDocuments, key = DocumentEntity::id) { document ->
                    DocumentCard(document, onOpen = { onOpenDocument(document.id) }, onEditTags = { editTagsFor = document })
                }
            }
        }
    }
    editTagsFor?.let { document ->
        TagDialog(
            initial = document.tags().joinToString(", "),
            onDismiss = { editTagsFor = null },
            onSave = { tags -> model.updateTags(document.id, tags); editTagsFor = null },
        )
    }
}

@Composable
private fun LibraryHero(documentCount: Int, onImport: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer)))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("阅读，随时继续。", style = MaterialTheme.typography.displaySmall, color = colors.onPrimaryContainer)
        Text(
            if (documentCount == 0) "导入一本 TXT 或 EPUB，建立你的离线书架。" else "你有 $documentCount 本本地文档，阅读进度已安全保存在设备上。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onPrimaryContainer.copy(alpha = 0.82f),
        )
        FilledTonalButton(onClick = onImport, shape = MaterialTheme.shapes.small) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("导入文档")
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 42.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text("书架还是空的", style = MaterialTheme.typography.titleLarge)
            Text("从文件选择器导入 TXT 或 EPUB。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onImport) { Text("选择文件") }
        }
    }
}

@Composable
private fun DocumentCard(document: DocumentEntity, onOpen: () -> Unit, onEditTags: () -> Unit) {
    val isEpub = document.format.equals("epub", ignoreCase = true)
    val formatColor = if (isEpub) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
    val formatOnColor = if (isEpub) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Box(
                    modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small).background(formatColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(document.format.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = formatOnColor)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(document.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${document.chapterCount} 章节 · ${formatBytes(document.byteSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    document.lastOpenedAt?.let {
                        Text("上次阅读 ${formatTime(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(onClick = onEditTags, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("标签") }
            }
            document.tags().takeIf { it.isNotEmpty() }?.let { tags ->
                Text(tags.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun TagDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("整理标签") },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("用逗号分隔") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun DocumentEntity.tags(): List<String> = runCatching {
    val value = JSONArray(tagsJson)
    List(value.length()) { value.getString(it) }
}.getOrDefault(emptyList())

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / 1024f / 1024f)} MB"
}

private fun formatTime(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
