package com.example.voicevault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.voicevault.db.AppDb
import com.example.voicevault.db.AudioFormat
import com.example.voicevault.db.AudioQuality
import com.example.voicevault.db.PendingRecord
import com.example.voicevault.db.Recording
import com.example.voicevault.io.AudioStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano que grava o áudio E finaliza/salva a gravação
 * de forma autônoma (independente da Activity estar viva).
 * A sessão fica persistida na tabela "pending" para sobreviver a crash do processo.
 */
class RecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private var pausedTotalMs = 0L
    private var pauseStartedAt = 0L
    private var isPaused = false
    private var outPath = ""
    private var fmtName = "M4A"
    private var folderId: Long? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finishRecording(); return START_NOT_STICKY }
            ACTION_PAUSE -> { pauseRecording(); return START_NOT_STICKY }
            ACTION_RESUME -> { resumeRecording(); return START_NOT_STICKY }
        }

        if (recorder != null) return START_STICKY // já gravando

        val fmt = AudioFormat.fromName(intent?.getStringExtra(EXTRA_FORMAT))
        outPath = intent?.getStringExtra(EXTRA_OUTPUT) ?: return START_NOT_STICKY
        folderId = if (intent.hasExtra(EXTRA_FOLDER)) intent.getLongExtra(EXTRA_FOLDER, -1)
            .takeIf { it >= 0 } else null
        fmtName = fmt.name

        createChannel()
        RunningServices.add(RecorderService::class.java.name)
        // Android 14+ (API 34): tipo microphone exige RECORD_AUDIO concedida ANTES do startForeground.
        if (Build.VERSION.SDK_INT >= 34 && !hasMicPermission(this)) {
            File(outPath).delete()
            stopSelf()
            return START_NOT_STICKY
        }

        // Persiste a sessão pendente ANTES de começar — sobrevive a crash do processo.
        kotlinx.coroutines.runBlocking {
            AppDb.get(this@RecorderService).pendingDao().upsert(
                PendingRecord(id = 1, outPath = outPath, format = fmtName,
                    folderId = folderId, startedAt = System.currentTimeMillis())
            )
        }
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, 1,
            buildNotification("Gravando…", "Toque para abrir o VoiceVault"), fgsType)

        try {
            recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (!fmt.configure(this)) error("Formato $fmt não suportado neste aparelho")
                val q = AudioQuality.fromName(intent.getStringExtra(EXTRA_QUALITY))
                setAudioEncodingBitRate(q.bitRate)
                setAudioSamplingRate(q.sampleRate)
                setOutputFile(outPath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            File(outPath).delete()
            notifyUser(e.message ?: "Falha ao iniciar gravação")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startedAt = System.currentTimeMillis()
        LiveSession.begin(startedAt, fmtName)
        persistPending()
        return START_STICKY
    }

    // ---------- Pausar / retomar ----------

    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching { recorder?.pause() }.onSuccess {
            isPaused = true
            pauseStartedAt = System.currentTimeMillis()
            updateNotification("Pausado", "Gravação pausada — toque para retomar")
            LiveSession.markPause(System.currentTimeMillis())
            LiveSession.setPaused(true, System.currentTimeMillis())
            persistPending()
        }
    }

    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching { recorder?.resume() }.onSuccess {
            pausedTotalMs += System.currentTimeMillis() - pauseStartedAt
            isPaused = false
            updateNotification("Gravando…", "Toque para abrir o VoiceVault")
            LiveSession.setPaused(false, System.currentTimeMillis())
            persistPending()
        }
    }

    private fun persistPending() {
        scope.launch {
            AppDb.get(this@RecorderService).pendingDao().upsert(
                PendingRecord(1, outPath, fmtName, folderId, startedAt, pausedTotalMs, isPaused)
            )
        }
    }

    // ---------- Finalização autônoma (não depende da MainActivity!) ----------

    /** Para o MediaRecorder e SALVA no banco aqui mesmo, no serviço. */
    private fun finishRecording() {
        val rec = recorder
        if (rec == null) { stopSelf(); return }
        recorder = null
        LiveSession.end()

        val durationMs = elapsedMs()
        val file = File(outPath)

        // 1) parar o gravador CONFIRMANDO o sucesso
        val stopOk = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (!stopOk || !file.exists() || file.length() == 0L) {
            // falha real ou arquivo vazio → descarta com aviso honesto
            val recoverable = file.exists() && file.length() > 0L
            if (!recoverable) file.delete()
            notifyUser(if (recoverable)
                "Gravação interrompida — tentando recuperar o arquivo."
            else "A gravação terminou sem gerar áudio; nada foi salvo.")
            scope.launch { AppDb.get(this@RecorderService).pendingDao().clear() }
            if (recoverable) saveToDb(file, fallbackDurationMs = MediaInfo.durationOf(file))
            stopSelf()
            return
        }

        // 2) caminho normal: stop OK + arquivo válido
        saveToDb(file, fallbackDurationMs = durationMs)
        stopSelf()
    }

    private fun elapsedMs(): Long {
        val extraPause = if (isPaused) System.currentTimeMillis() - pauseStartedAt else 0
        return System.currentTimeMillis() - startedAt - pausedTotalMs - extraPause
    }

    /** Insere no banco + move o arquivo; verifica cada passo (integridade).
     *  Usa um escopo detached que SOBREVIVE ao onDestroy()/stopSelf() do serviço. */
    private fun saveToDb(tmp: File, fallbackDurationMs: Long) {
        val appCtx = applicationContext
        val db = AppDb.get(appCtx)
        val store = AudioStore(appCtx)
        val fmt = AudioFormat.fromName(fmtName)
        val folder = folderId
        val start = startedAt
        val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        saveScope.launch {
            try {
                val id = db.recordingDao().insert(
                    Recording(
                        title = "Gravação " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                            .format(Date(start)),
                        folderId = folder,
                        durationMs = maxOf(fallbackDurationMs, 100),
                        sizeBytes = tmp.length(),
                        format = fmt.name,
                    )
                )
                val dest = store.fileFor(id, fmt)
                val moved = tmp.renameTo(dest) ||
                    (runCatching { tmp.copyTo(dest, overwrite = false); true }.getOrDefault(false)
                        .also { if (it) tmp.delete() })
                if (!moved || !dest.exists() || dest.length() == 0L) {
                    // banco registrou mas o arquivo não chegou → desfaz o registro
                    db.recordingDao().byId(id)?.let { db.recordingDao().delete(it) }
                    tmp.delete()
                    NotificationHelper.show(appCtx, "Falha ao salvar o arquivo da gravação.")
                } else {
                    db.recordingDao().byId(id)?.let { r ->
                        db.recordingDao().update(r.copy(sizeBytes = dest.length()))
                    }
                    NotificationHelper.show(appCtx,
                        "Gravação salva (${dest.length() / 1024} KB, ${fmt.name}) ✅")
                }
            } finally {
                db.pendingDao().clear()
                kotlin.coroutines.Continuation // noop
            }
        }
    }

    // ---------- Notificações ----------

    private fun notifyUser(msg: String) = NotificationHelper.show(this, msg)

    private fun notifySaved(sizeBytes: Long, fmt: String) =
        NotificationHelper.show(this, "Gravação salva (${sizeBytes / 1024} KB, $fmt) ✅")

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    private fun buildNotification(title: String, text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseResumeIntent = PendingIntent.getService(
            this, 2, Intent(this, RecorderService::class.java).setAction(
                if (isPaused) ACTION_RESUME else ACTION_PAUSE
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceVault — $title")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, if (isPaused) "▶ Retomar" else "⏸ Pausar", pauseResumeIntent)
            .addAction(0, "⏹ Parar", stopIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(1, buildNotification(title, text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Gravação de voz", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        // Se o sistema matou o serviço sem passar pelo ACTION_STOP,
        // ainda tentamos finalizar corretamente.
        if (recorder != null) finishRecording()
        RunningServices.remove(RecorderService::class.java.name)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FOLDER = "folder"
        const val ACTION_STOP = "com.example.voicevault.STOP"
        const val ACTION_PAUSE = "com.example.voicevault.PAUSE"
        const val ACTION_RESUME = "com.example.voicevault.RESUME"

        fun hasMicPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /** O serviço está vivo agora? Checa o processo E o estado ao vivo. */
        fun isRunning(ctx: Context): Boolean {
            if (LiveSession.active) return true
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mine = android.os.Process.myPid()
            for (svc in am.getRunningServices(Int.MAX_VALUE)) {
                if (svc.service.className == RecorderService::class.java.name && svc.pid == mine)
                    return true
            }
            return false
        }
    }
}

/** Registro simples de serviços vivos neste processo. */
object RunningServices {
    private val set = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun add(name: String) { if (!set.contains(name)) set.add(name) }
    fun remove(name: String) = set.remove(name)
    fun contains(name: String) = set.contains(name)
}
