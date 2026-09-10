package io.github.quyen090hk.pxreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.quyen090hk.pxreader.settings.ReaderSettings
import io.github.quyen090hk.pxreader.settings.ReaderFont
import io.github.quyen090hk.pxreader.settings.ReadingMode
import io.github.quyen090hk.pxreader.settings.SettingsRepository
import io.github.quyen090hk.pxreader.settings.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(settings: SettingsRepository, onBack: () -> Unit) {
    val current by settings.settings.collectAsStateWithLifecycle(initialValue = ReaderSettings())
    val scope = rememberCoroutineScope()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("阅读偏好") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("按照你的节奏阅读", style = MaterialTheme.typography.headlineSmall)
            Text("这些设置仅保存在当前设备，随时可以调整。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            PreferenceCard(title = "外观", subtitle = "选择适合当前环境的界面") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = current.themeMode == mode,
                            onClick = { scope.launch { settings.update { it.copy(themeMode = mode) } } },
                            label = { Text(mode.label()) },
                        )
                    }
                }
            }

            PreferenceCard(title = "字号", subtitle = "${"%.0f".format(current.fontScale * 100)}%") {
                Slider(
                    value = current.fontScale,
                    onValueChange = { next -> scope.launch { settings.update { it.copy(fontScale = next) } } },
                    valueRange = 0.8f..1.8f,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("小", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("大", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            PreferenceCard(title = "行距", subtitle = "${"%.1f".format(current.lineHeight)} 倍") {
                Slider(
                    value = current.lineHeight,
                    onValueChange = { next -> scope.launch { settings.update { it.copy(lineHeight = next) } } },
                    valueRange = 1.2f..2.4f,
                )
                Text("让长段落保持舒适呼吸感。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            PreferenceCard(title = "阅读布局", subtitle = "TXT 与 EPUB 共用同一套排版规则") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingMode.entries.forEach { mode ->
                        FilterChip(
                            selected = current.readingMode == mode,
                            onClick = { scope.launch { settings.update { it.copy(readingMode = mode) } } },
                            label = { Text(if (mode == ReadingMode.PAGED) "分页" else "连续滚动") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFont.entries.forEach { font ->
                        FilterChip(
                            selected = current.readerFont == font,
                            onClick = { scope.launch { settings.update { it.copy(readerFont = font) } } },
                            label = { Text(if (font == ReaderFont.SERIF) "衬线" else "无衬线") },
                        )
                    }
                }
            }

            PreferenceCard(title = "文字细节", subtitle = "让中文段落保持稳定、易读的节奏") {
                Text("字间距  ${"%.2f".format(current.letterSpacing)} em", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = current.letterSpacing,
                    onValueChange = { next -> scope.launch { settings.update { it.copy(letterSpacing = next) } } },
                    valueRange = -0.02f..0.12f,
                )
                Text("段落间距  ${"%.1f".format(current.paragraphSpacing)} em", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = current.paragraphSpacing,
                    onValueChange = { next -> scope.launch { settings.update { it.copy(paragraphSpacing = next) } } },
                    valueRange = 0f..2.4f,
                )
                ToggleRow(
                    label = "两端对齐",
                    checked = current.justified,
                    onCheckedChange = { checked -> scope.launch { settings.update { it.copy(justified = checked) } } },
                )
                ToggleRow(
                    label = "段首缩进两个汉字",
                    checked = current.firstLineIndent,
                    onCheckedChange = { checked -> scope.launch { settings.update { it.copy(firstLineIndent = checked) } } },
                )
            }

            Spacer(Modifier.padding(top = 12.dp))
            Text(
                "备份会导出文档元数据、位置、书签和批注；原始文件仍由你保管。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreferenceCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(PaddingValues(18.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            content()
        }
    }
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "自动"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}
