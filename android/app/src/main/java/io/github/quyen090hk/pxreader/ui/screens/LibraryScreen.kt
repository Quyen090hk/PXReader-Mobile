package io.github.quyen090hk.pxreader.ui.screens

import android.net.Uri
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.quyen090hk.pxreader.backup.BackupExporter
import io.github.quyen090hk.pxreader.data.ReaderRepository
import io.github.quyen090hk.pxreader.data.DocumentFormat
import io.github.quyen090hk.pxreader.data.db.DocumentEntity
import io.github.quyen090hk.pxreader.importer.DocumentImporter
import io.github.quyen090hk.pxreader.importer.DocumentScanner
import io.github.quyen090hk.pxreader.importer.ScanOptions
import io.github.quyen090hk.pxreader.ui.LibraryViewModel
import io.github.quyen090hk.pxreader.ui.PxReaderViewModelFactory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryRoute(
    repository: ReaderRepository,
    importer: DocumentImporter,
    scanner: DocumentScanner,
    backupExporter: BackupExporter,
    incoming: StateFlow<List<Uri>>,
    onIncomingConsumed: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val model: LibraryViewModel = viewModel(factory = remember { PxReaderViewModelFactory { LibraryViewModel(repository, importer, scanner) } })
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
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.importUris(uris, onOpenDocument)
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { backupExporter.exportBackup(uri) }
    }
    var pendingScanOptions by remember { mutableStateOf(ScanOptions()) }
    val treeScanner = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { model.scanTree(it, pendingScanOptions) }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.scanSharedStorage(pendingScanOptions) { }
    }
    val allFilesAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.scanSharedStorage(pendingScanOptions) { }
    }
    val requestDeviceScan = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesAccess.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
        } else {
            legacyPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    var importSheetVisible by remember { mutableStateOf(false) }
    var scanDialogVisible by remember { mutableStateOf(false) }
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
                        Text("你的本地书架", style = MaterialTheme.typography.labelSmall, letterSpacing = 0.08.em)
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
            if (state.documents.isEmpty()) {
                item {
                    LibraryHero(onImport = { importSheetVisible = true })
                }
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
            if (state.scanning) {
                item {
                    val progress = state.scanProgress
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("正在扫描本地书籍", style = MaterialTheme.typography.titleMedium)
                            Text("已检查 ${progress?.examined ?: 0} 个文件 · 发现 ${progress?.candidates ?: 0} 本", style = MaterialTheme.typography.bodySmall)
                            progress?.currentPath?.takeIf { it.isNotBlank() }?.let { path ->
                                Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
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
                    Text("书架", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                    Text("${visibleDocuments.size} 本", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.documents.isNotEmpty()) {
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(onClick = { importSheetVisible = true }, shape = MaterialTheme.shapes.small) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("导入")
                        }
                    }
                }
            }
            if (visibleDocuments.isEmpty()) {
                item {
                    EmptyLibrary(
                        onImport = { importSheetVisible = true },
                    )
                }
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
    if (importSheetVisible) {
        ImportSheet(
            onDismiss = { importSheetVisible = false },
            onScan = {
                importSheetVisible = false
                scanDialogVisible = true
            },
            onSelectFiles = {
                importSheetVisible = false
                picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream"))
            },
        )
    }
    if (scanDialogVisible) {
        ScanImportDialog(
            onDismiss = {
                scanDialogVisible = false
                importSheetVisible = true
            },
            onConfirm = { scope, options ->
                pendingScanOptions = options
                scanDialogVisible = false
                when (scope) {
                    ScanScope.FOLDER -> treeScanner.launch(null)
                    ScanScope.DEVICE -> model.scanSharedStorage(options, requestDeviceScan)
                }
            },
        )
    }
}

@Composable
private fun LibraryHero(onImport: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer)))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("从第一本书开始。", style = MaterialTheme.typography.displaySmall, color = colors.onPrimaryContainer)
        Text(
            "从设备发现 TXT 与 EPUB，或直接选择文件；PXReader 会把它们整理进你的离线书架。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onPrimaryContainer.copy(alpha = 0.82f),
        )
        FilledTonalButton(onClick = onImport, shape = MaterialTheme.shapes.small) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("导入")
        }
    }
}

private enum class ScanScope { DEVICE, FOLDER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onSelectFiles: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("导入书籍", style = MaterialTheme.typography.headlineSmall)
            Text(
                "优先扫描设备中的书籍；需要时也可以直接选择一个或多个文件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ImportMethodCard(
                title = "扫描发现",
                description = "扫描整个设备或指定目录，自动识别、去重并整理 TXT 与 EPUB。",
                action = "配置扫描",
                icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                accent = MaterialTheme.colorScheme.primaryContainer,
                onClick = onScan,
            )
            ImportMethodCard(
                title = "选择文件",
                description = "从系统文件选择器导入一个或多个 TXT、EPUB 文件。",
                action = "选择文件",
                icon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                accent = MaterialTheme.colorScheme.secondaryContainer,
                onClick = onSelectFiles,
            )
        }
    }
}

@Composable
private fun ImportMethodCard(
    title: String,
    description: String,
    action: String,
    icon: @Composable () -> Unit,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ScanImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (ScanScope, ScanOptions) -> Unit,
) {
    var scope by remember { mutableStateOf(ScanScope.DEVICE) }
    var includeEpub by remember { mutableStateOf(true) }
    var includeTxt by remember { mutableStateOf(true) }
    var minimumKb by remember { mutableStateOf("20") }
    var favourite by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("") }
    val minimumKbValue = minimumKb.toLongOrNull()
    val selectedFormats = buildSet {
        if (includeEpub) add(DocumentFormat.EPUB)
        if (includeTxt) add(DocumentFormat.TXT)
    }
    val initialTags = buildSet {
        if (favourite) add("收藏")
        category.split(',', '，').map(String::trim).filter(String::isNotEmpty).forEach(::add)
    }
    val options = ScanOptions(
        formats = selectedFormats,
        minimumBytes = (minimumKbValue ?: 20L) * 1024L,
        initialTags = initialTags,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置扫描") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("扫描范围", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = scope == ScanScope.DEVICE,
                            onClick = { scope = ScanScope.DEVICE },
                            label = { Text("设备存储") },
                        )
                        FilterChip(
                            selected = scope == ScanScope.FOLDER,
                            onClick = { scope = ScanScope.FOLDER },
                            label = { Text("指定目录") },
                        )
                    }
                    Text(
                        if (scope == ScanScope.DEVICE) "扫描共享存储中的书籍；首次使用需要系统文件访问授权。" else "通过系统文件选择器授权一个目录，并递归扫描其中的书籍。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("文件类型", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeEpub, onCheckedChange = { includeEpub = it })
                        Text("EPUB")
                        Spacer(Modifier.width(14.dp))
                        Checkbox(checked = includeTxt, onCheckedChange = { includeTxt = it })
                        Text("TXT")
                    }
                }
                OutlinedTextField(
                    value = minimumKb,
                    onValueChange = { value -> minimumKb = value.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("最小文件大小") },
                    suffix = { Text("KB") },
                    supportingText = { Text("过滤常见的空文件和临时文件") },
                    singleLine = true,
                    isError = minimumKbValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("入库标签", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = favourite, onCheckedChange = { favourite = it })
                        Text("收藏")
                    }
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("分类标签（可选）") },
                        supportingText = { Text("用逗号分隔；仅应用于本次新入库书籍") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(scope, options) },
                enabled = selectedFormats.isNotEmpty() && minimumKbValue != null,
            ) {
                Text(if (scope == ScanScope.DEVICE) "开始扫描" else "选择目录")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
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
            Text("导入时可扫描设备或指定目录，也可以直接选择 TXT 与 EPUB 文件。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onImport) { Text("导入书籍") }
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
                CoverThumbnail(document, formatColor, formatOnColor)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(document.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    document.author?.takeIf { it.isNotBlank() }?.let { author ->
                        Text(author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text(
                        "${document.chapterCount} 章节 · ${formatBytes(document.byteSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    document.lastOpenedAt?.let {
                        Text("上次阅读 ${formatTime(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    document.sourcePath?.takeIf { it.isNotBlank() }?.let { path ->
                        Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun CoverThumbnail(document: DocumentEntity, fallbackColor: androidx.compose.ui.graphics.Color, fallbackContentColor: androidx.compose.ui.graphics.Color) {
    val context = LocalContext.current
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(initialValue = null, document.coverFileName) {
        value = withContext(Dispatchers.IO) {
            document.coverFileName
                ?.let { File(context.filesDir, "covers/$it") }
                ?.takeIf(File::isFile)
                ?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
        }
    }
    if (bitmap == null) {
        Box(
            modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small).background(fallbackColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(document.format.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fallbackContentColor)
        }
    } else {
        Image(
            bitmap = requireNotNull(bitmap).asImageBitmap(),
            contentDescription = "《${document.title}》封面",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small),
        )
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
