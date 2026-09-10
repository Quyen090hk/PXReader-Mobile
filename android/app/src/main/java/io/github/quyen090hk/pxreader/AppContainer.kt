package io.github.quyen090hk.pxreader

import android.content.Context
import androidx.room.Room
import io.github.quyen090hk.pxreader.backup.BackupExporter
import io.github.quyen090hk.pxreader.data.ReaderRepository
import io.github.quyen090hk.pxreader.data.db.AppDatabase
import io.github.quyen090hk.pxreader.importer.DocumentImporter
import io.github.quyen090hk.pxreader.settings.SettingsRepository

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    val database: AppDatabase = Room.databaseBuilder(
        applicationContext,
        AppDatabase::class.java,
        "pxreader.db",
    ).build()

    val readerRepository = ReaderRepository(applicationContext, database.dao())
    val importer = DocumentImporter(applicationContext, database.dao(), readerRepository)
    val settings = SettingsRepository(applicationContext)
    val backupExporter = BackupExporter(applicationContext, database.dao())
}
