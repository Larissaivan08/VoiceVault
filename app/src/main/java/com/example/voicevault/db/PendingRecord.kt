package com.example.voicevault.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sessão de gravação em andamento / aguardando finalização.
 * Persistida no banco (não em memória!) para que o salvamento sobreviva
 * à morte do processo e ao "parar pela notificação" sem abrir o app.
 */
@Entity(tableName = "pending")
data class PendingRecord(
    @PrimaryKey val id: Long = 1, // sempre uma linha só
    val outPath: String,          // arquivo temporário único da sessão
    val format: String,           // nome do enum AudioFormat
    val folderId: Long?,          // pasta escolhida na hora de gravar
    val startedAt: Long,
    val pausedTotalMs: Long = 0,  // tempo total pausado (para exibir o cronômetro certo)
    val paused: Boolean = false,
)
