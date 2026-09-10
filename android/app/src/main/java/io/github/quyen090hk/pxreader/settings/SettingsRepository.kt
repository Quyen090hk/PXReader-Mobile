package io.github.quyen090hk.pxreader.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerSettingsDataStore by preferencesDataStore(name = "reader_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ReaderSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1f,
    val lineHeight: Float = 1.7f,
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<ReaderSettings> = context.readerSettingsDataStore.data.map { preferences ->
        ReaderSettings(
            themeMode = preferences[THEME]?.let { value -> ThemeMode.entries.firstOrNull { it.name == value } }
                ?: ThemeMode.SYSTEM,
            fontScale = preferences[FONT_SCALE] ?: 1f,
            lineHeight = preferences[LINE_HEIGHT] ?: 1.7f,
        )
    }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        context.readerSettingsDataStore.edit { preferences ->
            val old = ReaderSettings(
                themeMode = preferences[THEME]?.let { value -> ThemeMode.entries.firstOrNull { it.name == value } }
                    ?: ThemeMode.SYSTEM,
                fontScale = preferences[FONT_SCALE] ?: 1f,
                lineHeight = preferences[LINE_HEIGHT] ?: 1.7f,
            )
            val next = transform(old)
            preferences[THEME] = next.themeMode.name
            preferences[FONT_SCALE] = next.fontScale.coerceIn(0.8f, 1.8f)
            preferences[LINE_HEIGHT] = next.lineHeight.coerceIn(1.2f, 2.4f)
        }
    }

    private companion object {
        val THEME: Preferences.Key<String> = stringPreferencesKey("theme")
        val FONT_SCALE: Preferences.Key<Float> = floatPreferencesKey("font_scale")
        val LINE_HEIGHT: Preferences.Key<Float> = floatPreferencesKey("line_height")
    }
}
