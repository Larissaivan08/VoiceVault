package com.example.voicevault.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert
    suspend fun insert(folder: Folder): Long

    @Update
    suspend fun update(folder: Folder)

    @Delete
    suspend fun delete(folder: Folder)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: Long): Folder?

    /** Lista síncrona (para o diálogo "Mover para…"). */
    @Query(
        """SELECT f.*, (SELECT COUNT(*) FROM recordings r WHERE r.folderId = f.id) AS recordingCount
           FROM folders f ORDER BY f.name COLLATE NOCASE"""
    )
    suspend fun listWithCount(): List<FolderCount>

    @Query("SELECT COUNT(*) FROM folders WHERE name = :name COLLATE NOCASE")
    suspend fun countByName(name: String): Int

    @Query("SELECT COUNT(*) FROM folders WHERE name = :name COLLATE NOCASE AND id != :selfId")
    suspend fun countByNameOther(name: String, selfId: Long): Int

    /** Pastas com quantas gravações cada uma tem (0 quando vazia). */
    @Transaction
    @Query(
        """SELECT f.*, (SELECT COUNT(*) FROM recordings r WHERE r.folderId = f.id) AS recordingCount
           FROM folders f ORDER BY f.name COLLATE NOCASE"""
    )
    fun flowWithCount(): Flow<List<FolderCount>>

    @Query("SELECT * FROM folders WHERE id = :id")
    fun byIdFlow(id: Long): Flow<Folder?>
}

@Dao
interface RecordingDao {
    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun byId(id: Long): Recording?

    @Query("DELETE FROM recordings WHERE folderId = :folderId")
    suspend fun deleteInFolder(folderId: Long)

    @Query("SELECT * FROM recordings WHERE folderId = :folderId")
    suspend fun inFolderSync(folderId: Long): List<Recording>

    /** Lista filtrada por pasta (null = Raiz) e/ou busca por nome. */
    @Query(
        """SELECT r.*, f.name AS folderName FROM recordings r
           LEFT JOIN folders f ON f.id = r.folderId
           WHERE ((:folderId IS NULL AND r.folderId IS NULL) OR (:folderId IS NOT NULL AND r.folderId = :folderId))
             AND (:query IS NULL OR r.title LIKE '%' || :query || '%')
           ORDER BY r.createdAt DESC"""
    )
    fun flowIn(folderId: Long?, query: String?): Flow<List<RecordingWithFolderName>>

    /** Todas as gravações, com filtro opcional de busca (busca global). */
    @Query(
        """SELECT r.*, f.name AS folderName FROM recordings r
           LEFT JOIN folders f ON f.id = r.folderId
           WHERE (:query IS NULL OR r.title LIKE '%' || :query || '%')
           ORDER BY r.createdAt DESC"""
    )
    fun flowAll(query: String?): Flow<List<RecordingWithFolderName>>

    /** Move a gravação para outra pasta (null = Raiz). */
    @Query("UPDATE recordings SET folderId = :folderId WHERE id = :recordingId")
    suspend fun moveToFolder(recordingId: Long, folderId: Long?)

    /** Total de gravações em todas as pastas. */
    @Query("SELECT COUNT(*) FROM recordings")
    fun countFlow(): Flow<Int>

    /** Todas as gravações (para varredura de arquivos órfãos na inicialização). */
    @Query("SELECT id FROM recordings")
    suspend fun allIds(): List<Long>
}

@Dao
interface PendingDao {
    @Insert
    suspend fun upsert(p: PendingRecord)

    @Query("SELECT * FROM pending WHERE id = 1")
    suspend fun get(): PendingRecord?

    @Query("DELETE FROM pending")
    suspend fun clear()
}
