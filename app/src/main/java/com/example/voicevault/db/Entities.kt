package com.example.voicevault.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Uma pasta criada pelo usuário para organizar gravações. */
@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#DB797E",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Uma gravação. O arquivo físico fica em filesDir/recordings/<id>.m4a
 * folderId = null significa "Raiz".
 */
@Entity(
    tableName = "recordings",
    foreignKeys = [
        ForeignKey(
            entity = Folder::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE, // excluir a pasta apaga as gravações dela
        )
    ],
    indices = [Index("folderId")],
)
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val folderId: Long? = null,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis(),
    /** Nome do enum [com.example.voicevault.db.AudioFormat] usado na gravação. */
    val format: String = "M4A",
)

/** Linha usada na listagem (gravação + nome da pasta onde está). */
data class RecordingWithFolderName(
    val id: Long,
    val title: String,
    val folderId: Long?,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val format: String = "M4A",
    val folderName: String?, // null quando está na Raiz
)

/** Pasta com contagem de gravações (para a trilha de pastas no topo). */
data class FolderCount(
    val id: Long,
    val name: String,
    val colorHex: String,
    val createdAt: Long,
    val recordingCount: Int,
)
