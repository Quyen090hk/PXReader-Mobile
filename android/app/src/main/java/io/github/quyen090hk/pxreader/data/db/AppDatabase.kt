package io.github.quyen090hk.pxreader.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "documents",
    indices = [Index("title"), Index("lastOpenedAt"), Index(value = ["contentHash"], unique = true)],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val format: String,
    val title: String,
    val author: String?,
    val originalFileName: String,
    val storedFileName: String,
    val byteSize: Long,
    val contentHash: String,
    val tagsJson: String,
    val chapterCount: Int,
    val addedAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long?,
)

@Entity(tableName = "reading_positions")
data class ReadingPositionEntity(
    @PrimaryKey val documentId: String,
    val chapterIndex: Int,
    val chapterHref: String?,
    val charStart: Int,
    val charEnd: Int,
    val progress: Float,
    val quote: String,
    val prefix: String,
    val suffix: String,
    val anchor: String?,
    val updatedAt: Long,
)

@Entity(tableName = "annotations", indices = [Index("documentId"), Index("updatedAt")])
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val quote: String,
    val note: String?,
    val color: String,
    val chapterIndex: Int,
    val chapterHref: String?,
    val charStart: Int,
    val charEnd: Int,
    val progress: Float,
    val prefix: String,
    val suffix: String,
    val anchor: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "bookmarks", indices = [Index("documentId")])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val label: String?,
    val chapterIndex: Int,
    val chapterHref: String?,
    val charStart: Int,
    val charEnd: Int,
    val progress: Float,
    val quote: String,
    val prefix: String,
    val suffix: String,
    val anchor: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "search_units", indices = [Index("documentId")])
data class SearchUnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: String,
    val chapterIndex: Int,
    val chapterHref: String?,
    val title: String,
    val body: String,
)

@Fts4
@Entity(tableName = "search_units_fts")
data class SearchUnitFtsEntity(val body: String)

@Dao
interface PxReaderDao {
    @Query("SELECT * FROM documents ORDER BY COALESCE(lastOpenedAt, addedAt) DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun document(id: String): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY title COLLATE NOCASE")
    suspend fun allDocuments(): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(document: DocumentEntity)

    @Query("UPDATE documents SET lastOpenedAt = :time, updatedAt = :time WHERE id = :documentId")
    suspend fun markOpened(documentId: String, time: Long)

    @Query("UPDATE documents SET tagsJson = :tagsJson, updatedAt = :time WHERE id = :documentId")
    suspend fun updateTags(documentId: String, tagsJson: String, time: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(position: ReadingPositionEntity)

    @Query("SELECT * FROM reading_positions WHERE documentId = :documentId LIMIT 1")
    suspend fun position(documentId: String): ReadingPositionEntity?

    @Query("SELECT * FROM reading_positions")
    suspend fun allPositions(): List<ReadingPositionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnnotation(annotation: AnnotationEntity)

    @Query("SELECT * FROM annotations WHERE documentId = :documentId ORDER BY updatedAt DESC")
    fun observeAnnotations(documentId: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations ORDER BY updatedAt DESC")
    suspend fun allAnnotations(): List<AnnotationEntity>

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteAnnotation(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks")
    suspend fun allBookmarks(): List<BookmarkEntity>

    @Insert
    suspend fun insertSearchUnit(unit: SearchUnitEntity): Long

    @Query("INSERT INTO search_units_fts(rowid, body) VALUES (:rowId, :body)")
    suspend fun insertSearchFts(rowId: Long, body: String)

    @Query("DELETE FROM search_units_fts WHERE rowid IN (SELECT id FROM search_units WHERE documentId = :documentId)")
    suspend fun deleteSearchFtsForDocument(documentId: String)

    @Query("DELETE FROM search_units WHERE documentId = :documentId")
    suspend fun deleteSearchUnitsForDocument(documentId: String)

    @Query("""
        SELECT u.* FROM search_units u
        INNER JOIN search_units_fts f ON f.rowid = u.id
        WHERE u.documentId = :documentId AND f.body MATCH :query
        LIMIT :limit
    """)
    suspend fun ftsSearch(documentId: String, query: String, limit: Int): List<SearchUnitEntity>

    @Query("""
        SELECT * FROM search_units
        WHERE documentId = :documentId AND instr(lower(body), lower(:query)) > 0
        LIMIT :limit
    """)
    suspend fun exactSearch(documentId: String, query: String, limit: Int): List<SearchUnitEntity>

    @Transaction
    suspend fun replaceSearchUnits(documentId: String, units: List<SearchUnitEntity>) {
        deleteSearchFtsForDocument(documentId)
        deleteSearchUnitsForDocument(documentId)
        units.forEach { unit ->
            val id = insertSearchUnit(unit)
            insertSearchFts(id, unit.body)
        }
    }
}

@Database(
    entities = [
        DocumentEntity::class,
        ReadingPositionEntity::class,
        AnnotationEntity::class,
        BookmarkEntity::class,
        SearchUnitEntity::class,
        SearchUnitFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PxReaderDao
}

