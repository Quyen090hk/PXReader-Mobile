package io.github.quyen090hk.pxreader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
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
    val allTags = remember(state.documents) { state.documents.flatMap(DocumentEntity::tags).distinct().sorted() }
    val visibleDocuments = state.documents.filter { selectedTag == null || it.tags().contains(selectedTag) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PXReader") },
                actions = {
                    IconButton(onClick = { backupPicker.launch("pxreader-backup-v1.json") }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "导出备份")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("本地文档库", style = MaterialTheme.typography.titleLarge)
                    Text("${state.documents.size} 本 · TXT / EPUB", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) }) {
                    Icon(Icons.Outlined.FolderOpen, null)
                    Spacer(Modifier.width(6.dp))
                    Text("导入")
                }
            }
            state.message?.let { message ->
                AssistChip(onClick = model::consumeMessage, label = { Text(message) }, modifier = Modifier.padding(top = 10.dp))
            }
            if (state.importing) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Text("正在复制、校验和建立索引…")
                }
            }
            if (allTags.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    contentPadding = PaddingValues(top = 8.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.Start,
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = selectedTag == null, onClick = { selectedTag = null }, label = { Text("全部") })
                            allTags.forEach { tag ->
                                FilterChip(selected = selectedTag == tag, onClick = { selectedTag = tag }, label = { Text(tag) })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (visibleDocuments.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("还没有文档", style = MaterialTheme.typography.titleMedium)
                    Text("从系统文件选择器导入 TXT 或 EPUB。", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(visibleDocuments, key = DocumentEntity::id) { document ->
                        DocumentCard(document, onOpen = { onOpenDocument(document.id) }, onEditTags = { editTagsFor = document })
                    }
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
private fun DocumentCard(document: DocumentEntity, onOpen: () -> Unit, onEditTags: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(document.title, style = MaterialTheme.typography.titleMedium)
                    Text("${document.format.uppercase()} · ${document.chapterCount} 章节 · ${formatBytes(document.byteSize)}")
                    document.lastOpenedAt?.let { Text("最近阅读：${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(onClick = onEditTags) { Text("标签") }
            }
            document.tags().takeIf { it.isNotEmpty() }?.let { tags ->
                Spacer(Modifier.height(8.dp))
                Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TagDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标签") },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("用逗号分隔") }) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(value) }) { Text("保存") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
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
