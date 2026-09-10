package io.github.quyen090hk.pxreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.quyen090hk.pxreader.settings.ReaderSettings
import io.github.quyen090hk.pxreader.settings.SettingsRepository
import io.github.quyen090hk.pxreader.settings.ThemeMode
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(settings: SettingsRepository, onBack: () -> Unit) {
    val current by settings.settings.collectAsStateWithLifecycle(initialValue = ReaderSettings())
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("阅读设置") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(
            modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("主题", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = current.themeMode == mode,
                            onClick = { scope.launch { settings.update { it.copy(themeMode = mode) } } },
                            label = { Text(when (mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) },
                        )
                    }
                }
            }
            Column {
                Text("字号 ${"%.0f".format(current.fontScale * 100)}%", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = current.fontScale,
                    onValueChange = { next -> scope.launch { settings.update { it.copy(fontScale = next) } } },
                    valueRange = 0.8f..1.8f,
                )
            }
            Column {
                Text("行距 ${"%.1f".format(current.lineHeight)}", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = current.lineHeight,
                    onValueChange = { next -> scope.launch { settings.update { it.copy(lineHeight = next) } } },
                    valueRange = 1.2f..2.4f,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("设置保存在本机；备份仅导出文档元数据、位置、书签和批注。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
