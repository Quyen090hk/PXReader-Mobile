package io.github.quyen090hk.pxreader

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.quyen090hk.pxreader.backup.BackupExporter
import io.github.quyen090hk.pxreader.data.ReaderRepository
import io.github.quyen090hk.pxreader.data.db.AppDatabase
import io.github.quyen090hk.pxreader.importer.DocumentImporter
import io.github.quyen090hk.pxreader.importer.DocumentScanner
import io.github.quyen090hk.pxreader.settings.SettingsRepository

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    val database: AppDatabase = Room.databaseBuilder(
        applicationContext,
        AppDatabase::class.java,
        "pxreader.db",
    ).addMigrations(MIGRATION_1_2).build()

    val readerRepository = ReaderRepository(applicationContext, database.dao())
    val importer = DocumentImporter(applicationContext, database.dao(), readerRepository)
    val scanner = DocumentScanner(applicationContext, importer)
    val settings = SettingsRepository(applicationContext)
    val backupExporter = BackupExporter(applicationContext, database.dao())

    private companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN sourceUri TEXT")
                db.execSQL("ALTER TABLE documents ADD COLUMN sourcePath TEXT")
                db.execSQL("ALTER TABLE documents ADD COLUMN coverFileName TEXT")
            }
        }
    }
}
