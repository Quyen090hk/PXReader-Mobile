package io.github.quyen090hk.pxreader.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.quyen090hk.pxreader.backup.BackupExporter
import io.github.quyen090hk.pxreader.data.ReaderRepository
import io.github.quyen090hk.pxreader.data.TextLocator
import io.github.quyen090hk.pxreader.data.db.AnnotationEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationRoute(
    documentId: String,
    repository: ReaderRepository,
    backupExporter: BackupExporter,
    onBack: () -> Unit,
    onJump: (String, TextLocator) -> Unit,
) {
    val annotations by repository.annotations(documentId).collectAsStateWithLifecycle(initialValue = emptyList())
    val document by produceState<io.github.quyen090hk.pxreader.data.db.DocumentEntity?>(initialValue = null, documentId) { value = repository.document(documentId) }
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch { backupExporter.exportAnnotationsMarkdown(uri, documentId) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批注 · ${document?.title ?: ""}", maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = { exporter.launch("${document?.title ?: "pxreader"}-annotations.md") }) { Text("导出") } },
            )
        },
    ) { padding ->
        if (annotations.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("尚无批注")
                Text("在阅读页选择文字后即可添加高亮或笔记。", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(annotations, key = AnnotationEntity::id) { annotation ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onJump(documentId, annotation.locator()) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(annotation.quote, style = MaterialTheme.typography.titleSmall)
                            annotation.note?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                            Text("第 ${annotation.chapterIndex + 1} 章", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun AnnotationEntity.locator() = TextLocator(
    chapterIndex, chapterHref, charStart, charEnd, progress, quote, prefix, suffix, anchor,
)
