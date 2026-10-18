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
import android.os.SystemClock
import com.example.voicevault.db.AppDb
import com.example.voicevault.db.Recording
import com.example.voicevault.io.AudioStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.voicevault.db.AudioFormat
import com.example.voicevault.db.AudioQuality

/**
 * Serviço em primeiro plano que grava o áudio.
 * Roda mesmo com a tela apagada ou o app minimizado.
 */
class RecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private var startedElapsed = 0L
    private var pauseStarted = 0L
    private var pausedElapsed = 0L
    private var folderId: Long? = null
    private var format = AudioFormat.M4A
    private var outputPath = ""
    private var hasStopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { pauseRecording(); return START_NOT_STICKY }
            ACTION_RESUME -> { resumeRecording(); return START_NOT_STICKY }
        }

        if (recorder != null) return START_STICKY // já gravando

        val fmt = AudioFormat.fromName(intent?.getStringExtra(EXTRA_FORMAT))
        format = fmt
        folderId = if (intent?.getBooleanExtra(EXTRA_HAS_FOLDER, false) == true) intent.getLongExtra(EXTRA_FOLDER, 0L) else null
        val outPath = intent?.getStringExtra(EXTRA_OUTPUT) ?: return START_NOT_STICKY
        outputPath = outPath

        createChannel()
        // Android 14+ (API 34): serviços com tipo microphone exigem a permissão RECORD_AUDIO
        // concedida ANTES do startForeground — senão lança SecurityException e a gravação
        // em segundo plano "não funciona".
        if (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            LastRecord.result = LastRecord.Fail("Sem permissão de microfone")
            stopSelf()
            return START_NOT_STICKY
        }
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, 1,
            buildNotification("Gravando…", "Toque para abrir o VoiceVault"), fgsType)

        try {
            recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (!fmt.configure(this)) throw IllegalStateException("Formato $fmt não suportado")
                val q = AudioQuality.fromName(intent?.getStringExtra(EXTRA_QUALITY))
                setAudioEncodingBitRate(q.bitRate)
                setAudioSamplingRate(q.sampleRate)
                setOutputFile(outPath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            LastRecord.result = LastRecord.Fail(e.message ?: "Falha na gravação")
            return START_NOT_STICKY
        }
        startedAt = System.currentTimeMillis()
        startedElapsed = SystemClock.elapsedRealtime()
        pausedElapsed = 0L
        pauseStarted = 0L
        LastRecord.result = LastRecord.Recording(startedAt, outPath, fmt.name, startedElapsedMs = startedElapsed)
        return START_STICKY
    }

    private fun pauseRecording() {
        if (recorder != null && pauseStarted == 0L) {
            if (runCatching { recorder!!.pause() }.isSuccess) {
                pauseStarted = SystemClock.elapsedRealtime()
                setPaused(true)
                updateNotification("Pausado", "Gravação pausada — toque para retomar")
            }
        }
    }

    private fun resumeRecording() {
        if (recorder != null && pauseStarted > 0L) {
            if (runCatching { recorder!!.resume() }.isSuccess) {
                if (pauseStarted > 0L) pausedElapsed += SystemClock.elapsedRealtime() - pauseStarted
                pauseStarted = 0L
                setPaused(false)
                updateNotification("Gravando…", "Toque para abrir o VoiceVault")
            }
        }
    }

    private fun setPaused(value: Boolean) {
        val r = LastRecord.result
        if (r is LastRecord.Recording) LastRecord.result = r.copy(paused = value, pausedElapsedMs = pausedElapsed, pauseStartedElapsedMs = pauseStarted)
    }

    override fun onDestroy() {
        if (!hasStopped) finishAndSave()
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * Persist independently of MainActivity. This also runs when the user stops
     * from the notification while the Activity is not open.
     */
    private fun finishAndSave() {
        hasStopped = true
        val rec = recorder ?: return
        recorder = null
        val now = SystemClock.elapsedRealtime()
        val paused = pausedElapsed + if (pauseStarted > 0L) now - pauseStarted else 0L
        val duration = (now - startedElapsed - paused).coerceAtLeast(0L)
        val valid = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        val source = File(outputPath)
        if (!valid || !source.isFile || source.length() == 0L) {
            LastRecord.result = LastRecord.Fail("Falha ao finalizar a gravação")
            return
        }
        try {
            runBlocking(Dispatchers.IO) {
                val dao = AppDb.get(applicationContext).recordingDao()
                val id = dao.insert(Recording(
                    title = "Gravação " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(startedAt)),
                    folderId = folderId,
                    durationMs = duration.coerceAtLeast(100L),
                    sizeBytes = source.length(),
                    format = format.name
                ))
                val dest = AudioStore(applicationContext).fileFor(id, format)
                val moved = source.renameTo(dest)
                if (!moved) {
                    source.copyTo(dest, overwrite = false)
                    if (dest.length() != source.length()) error("Arquivo copiado parcialmente")
                    source.delete()
                }
                if (!dest.isFile || dest.length() == 0L) error("Destino não encontrado")
                dao.byId(id)?.let { dao.update(it.copy(sizeBytes = dest.length())) }
            }
            LastRecord.result = LastRecord.Stopped(duration, outputPath)
        } catch (e: Exception) {
            LastRecord.result = LastRecord.Fail("Não foi possível salvar. Confira o armazenamento.")
        }
    }

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
                if (pausedNow()) ACTION_RESUME else ACTION_PAUSE
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceVault — $title")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, if (pausedNow()) "▶ Retomar" else "⏸ Pausar", pauseResumeIntent)
            .addAction(0, "⏹ Parar", stopIntent)
            .build()
    }

    private fun pausedNow(): Boolean =
        (LastRecord.result as? LastRecord.Recording)?.paused == true

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

    companion object {
        const val CHANNEL_ID = "recording"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_HAS_FOLDER = "has_folder"
        const val ACTION_STOP = "com.example.voicevault.STOP"
        const val ACTION_PAUSE = "com.example.voicevault.PAUSE"
        const val ACTION_RESUME = "com.example.voicevault.RESUME"

        fun hasMicPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/** Canal de comunicação simples Activity <-> Service (sem AIDL/broadcast). */
object LastRecord {
    sealed class State { abstract val outPath: String }
    data class Recording(
        val startedAt: Long,
        override val outPath: String,
        val format: String,
        val paused: Boolean = false,
        val startedElapsedMs: Long = 0L,
        val pausedElapsedMs: Long = 0L,
        val pauseStartedElapsedMs: Long = 0L,
    ) : State()
    data class Stopped(val durationMs: Long, override val outPath: String) : State()
    data class Fail(val message: String) : State() { override val outPath = "" }

    @Volatile var result: State? = null
}
