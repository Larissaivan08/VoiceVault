package com.example.voicevault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import android.widget.Toast

/** Avisa o usuário mesmo quando nenhuma Activity está visível (toast + notificação). */
object NotificationHelper {
    private const val RESULT_CHANNEL = "results"

    fun show(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(RESULT_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(RESULT_CHANNEL, "Resultados", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
        val n = NotificationCompat.Builder(ctx, RESULT_CHANNEL)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle("VoiceVault")
            .setContentText(msg)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), n) }
    }
}
