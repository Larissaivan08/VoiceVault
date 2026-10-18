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
import androidx.core.content.ContextCompat
import com.example.voicevault.db.AudioFormat

/**
 * Serviço em primeiro plano que grava o áudio.
 * Roda mesmo com a tela apagada ou o app minimizado.
 */
class RecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { pauseRecording(); return START_NOT_STICKY }
            ACTION_RESUME -> { resumeRecording(); return START_NOT_STICKY }
        }

        if (recorder != null) return START_STICKY // já gravando

        val fmt = AudioFormat.fromName(intent?.getStringExtra(EXTRA_FORMAT))
        val outPath = intent?.getStringExtra(EXTRA_OUTPUT) ?: return START_NOT_STICKY

        createChannel()
        startForeground(1, buildNotification("Gravando…", "Toque para abrir o VoiceVault"))

        try {
            recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (!fmt.configure(this)) throw IllegalStateException("Formato $fmt não suportado")
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
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
        LastRecord.result = LastRecord.Recording(startedAt, outPath, fmt.name)
        return START_STICKY
    }

    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { recorder?.pause() }
            setPaused(true)
            updateNotification("Pausado", "Gravação pausada — toque para retomar")
        }
    }

    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { recorder?.resume() }
            setPaused(false)
            updateNotification("Gravando…", "Toque para abrir o VoiceVault")
        }
    }

    private fun setPaused(value: Boolean) {
        val r = LastRecord.result
        if (r is LastRecord.Recording) LastRecord.result = r.copy(paused = value)
    }

    override fun onDestroy() {
        super.onDestroy()
        val rec = recorder
        recorder = null
        if (rec != null) {
            val duration = System.currentTimeMillis() - startedAt
            runCatching { rec.stop() }
            runCatching { rec.release() }
            val path = LastRecord.result?.outPath
            LastRecord.result = LastRecord.Stopped(duration, path ?: "")
        } else {
            // foi parado sem ter gravado
            if (LastRecord.result !is LastRecord.Fail) LastRecord.result = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
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
    ) : State()
    data class Stopped(val durationMs: Long, override val outPath: String) : State()
    data class Fail(val message: String) : State() { override val outPath = "" }

    @Volatile var result: State? = null
}
