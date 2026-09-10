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

/** The layout policy is shared by TXT and EPUB so a book never changes its reading rules by format. */
enum class ReadingMode { PAGED, SCROLL }

enum class ReaderFont { SERIF, SANS }

data class ReaderSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1f,
    val lineHeight: Float = 1.7f,
    val letterSpacing: Float = 0f,
    val paragraphSpacing: Float = 0.7f,
    val justified: Boolean = true,
    val firstLineIndent: Boolean = true,
    val readingMode: ReadingMode = ReadingMode.PAGED,
    val readerFont: ReaderFont = ReaderFont.SERIF,
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<ReaderSettings> = context.readerSettingsDataStore.data.map { preferences ->
        ReaderSettings(
            themeMode = preferences[THEME]?.let { value -> ThemeMode.entries.firstOrNull { it.name == value } }
                ?: ThemeMode.SYSTEM,
            fontScale = preferences[FONT_SCALE] ?: 1f,
            lineHeight = preferences[LINE_HEIGHT] ?: 1.7f,
            letterSpacing = preferences[LETTER_SPACING] ?: 0f,
            paragraphSpacing = preferences[PARAGRAPH_SPACING] ?: 0.7f,
            justified = preferences[JUSTIFIED] ?: true,
            firstLineIndent = preferences[FIRST_LINE_INDENT] ?: true,
            readingMode = preferences[READING_MODE]?.let { value -> ReadingMode.entries.firstOrNull { it.name == value } }
                ?: ReadingMode.PAGED,
            readerFont = preferences[READER_FONT]?.let { value -> ReaderFont.entries.firstOrNull { it.name == value } }
                ?: ReaderFont.SERIF,
        )
    }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        context.readerSettingsDataStore.edit { preferences ->
            val old = ReaderSettings(
                themeMode = preferences[THEME]?.let { value -> ThemeMode.entries.firstOrNull { it.name == value } }
                    ?: ThemeMode.SYSTEM,
                fontScale = preferences[FONT_SCALE] ?: 1f,
                lineHeight = preferences[LINE_HEIGHT] ?: 1.7f,
                letterSpacing = preferences[LETTER_SPACING] ?: 0f,
                paragraphSpacing = preferences[PARAGRAPH_SPACING] ?: 0.7f,
                justified = preferences[JUSTIFIED] ?: true,
                firstLineIndent = preferences[FIRST_LINE_INDENT] ?: true,
                readingMode = preferences[READING_MODE]?.let { value -> ReadingMode.entries.firstOrNull { it.name == value } }
                    ?: ReadingMode.PAGED,
                readerFont = preferences[READER_FONT]?.let { value -> ReaderFont.entries.firstOrNull { it.name == value } }
                    ?: ReaderFont.SERIF,
            )
            val next = transform(old)
            preferences[THEME] = next.themeMode.name
            preferences[FONT_SCALE] = next.fontScale.coerceIn(0.8f, 1.8f)
            preferences[LINE_HEIGHT] = next.lineHeight.coerceIn(1.2f, 2.4f)
            preferences[LETTER_SPACING] = next.letterSpacing.coerceIn(-0.02f, 0.12f)
            preferences[PARAGRAPH_SPACING] = next.paragraphSpacing.coerceIn(0f, 2.4f)
            preferences[JUSTIFIED] = next.justified
            preferences[FIRST_LINE_INDENT] = next.firstLineIndent
            preferences[READING_MODE] = next.readingMode.name
            preferences[READER_FONT] = next.readerFont.name
        }
    }

    private companion object {
        val THEME: Preferences.Key<String> = stringPreferencesKey("theme")
        val FONT_SCALE: Preferences.Key<Float> = floatPreferencesKey("font_scale")
        val LINE_HEIGHT: Preferences.Key<Float> = floatPreferencesKey("line_height")
        val LETTER_SPACING: Preferences.Key<Float> = floatPreferencesKey("letter_spacing")
        val PARAGRAPH_SPACING: Preferences.Key<Float> = floatPreferencesKey("paragraph_spacing")
        val JUSTIFIED: Preferences.Key<Boolean> = androidx.datastore.preferences.core.booleanPreferencesKey("justified")
        val FIRST_LINE_INDENT: Preferences.Key<Boolean> = androidx.datastore.preferences.core.booleanPreferencesKey("first_line_indent")
        val READING_MODE: Preferences.Key<String> = stringPreferencesKey("reading_mode")
        val READER_FONT: Preferences.Key<String> = stringPreferencesKey("reader_font")
    }
}
