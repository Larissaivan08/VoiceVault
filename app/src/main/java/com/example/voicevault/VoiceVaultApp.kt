package com.example.voicevault

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.voicevault.db.AppDb
import com.example.voicevault.db.AudioFormat
import com.example.voicevault.db.Recording
import com.example.voicevault.io.AudioStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aplicação: inicializa o banco e recupera gravações não finalizadas.
 * A recuperação acontece mesmo que o app NÃO tenha sido aberto pelo usuário
 * (o Android chama Application.onCreate ao reiniciar qualquer componente,
 * inclusive o RecorderService).
 */
class VoiceVaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppDb.get(this) // abre/migra o banco
        recoverPending()
        cleanupOrphans()
    }

    /**
     * Se existe uma sessão pendente no banco, decide:
     *  - serviço ainda vivo gravando → nada a fazer;
     *  - processo do serviço morreu (crash/OOM) → tenta recuperar o arquivo temporário.
     */
    private fun recoverPending() {
        val db = AppDb.get(this)
        val store = AudioStore(this)
        Thread {
            val p = kotlinx.coroutines.runBlocking { db.pendingDao().get() } ?: return@Thread
            if (RecorderService.isRunning(this)) return@Thread // gravação viva

            val tmp = File(p.outPath)
            val fmt = AudioFormat.fromName(p.format)
            if (!tmp.exists() || tmp.length() == 0L) {
                kotlinx.coroutines.runBlocking { db.pendingDao().clear(); tmp.delete() }
                return@Thread
            }
            val durationMs =
                if (p.paused) MediaInfo.durationOf(tmp) else System.currentTimeMillis() - p.startedAt - p.pausedTotalMs
            val ok = runCatching {
                kotlinx.coroutines.runBlocking {
                    val id = db.recordingDao().insert(
                        Recording(
                            title = "Gravação " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                                .format(Date(p.startedAt)),
                            folderId = p.folderId,
                            durationMs = maxOf(durationMs, 100),
                            sizeBytes = tmp.length(),
                            format = fmt.name,
                        )
                    )
                    val dest = store.fileFor(id, fmt)
                    if (!tmp.renameTo(dest)) { // rename falhou (ex.: cache→files em volumes diferentes)
                        if (!tmp.copyToSafely(dest)) {
                            db.recordingDao().delete(db.recordingDao().byId(id)!!)
                            return@runBlocking
                        }
                        tmp.delete()
                    }
                    db.recordingDao().byId(id)?.let { r ->
                        db.recordingDao().update(r.copy(sizeBytes = dest.length()))
                    }
                    db.pendingDao().clear()
                }
            }.isSuccess
            if (!ok) tmp.delete()
        }.start()
    }

    /** Remove arquivos de áudio sem registro no banco (órfãos de sessões antigas). */
    private fun cleanupOrphans() {
        val db = AppDb.get(this)
        val dir = File(filesDir, "recordings")
        val pending = File(cacheDir, "pending") // pasta de temporários únicos por sessão
        Thread {
            val ids = kotlinx.coroutines.runBlocking { db.recordingDao().allIds() }.toHashSet()
            dir.listFiles()?.forEach { f ->
                val base = f.name.substringBefore('.')
                val id = base.toLongOrNull()
                if (id != null && id !in ids) f.delete()
            }
            // limpa temporários abandonados (com mais de 1 dia)
            val old = System.currentTimeMillis() - 24L * 3600_000
            pending.mkdirs()
            pending.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        }.start()
    }
}

/** Extensão segura de cópia (não lança se destino existir/IO falhar). */
private fun File.copyToSafely(dest: File): Boolean = runCatching {
    copyTo(dest, overwrite = false)
}.isSuccess

/** Lê a duração real de um arquivo de áudio via MediaMetadataRetriever. */
object MediaInfo {
    fun durationOf(file: File): Long = runCatching {
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        r.release()
        d
    }.getOrDefault(0L)
}
