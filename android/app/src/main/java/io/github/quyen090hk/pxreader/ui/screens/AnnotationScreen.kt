package io.github.quyen090hk.pxreader.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("批注") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回阅读") } },
                actions = { TextButton(onClick = { exporter.launch("${document?.title ?: "pxreader"}-annotations.md") }) { Text("导出") } },
            )
        },
    ) { padding ->
        if (annotations.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(54.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Text("✦", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.tertiary) }
                Text("把重要的句子留在这里", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
                Text("在阅读页选择文字，即可添加高亮或笔记。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(document?.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${annotations.size} 条摘录与笔记", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(annotations, key = AnnotationEntity::id) { annotation ->
                    AnnotationCard(annotation = annotation, onClick = { onJump(documentId, annotation.locator()) })
                }
            }
        }
    }
}

@Composable
private fun AnnotationCard(annotation: AnnotationEntity, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("“${annotation.quote}”", style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            annotation.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("第 ${annotation.chapterIndex + 1} 章 · 点按回到原文", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun AnnotationEntity.locator() = TextLocator(
    chapterIndex, chapterHref, charStart, charEnd, progress, quote, prefix, suffix, anchor,
)
