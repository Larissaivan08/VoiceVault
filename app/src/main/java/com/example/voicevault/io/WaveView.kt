package com.example.voicevault.io

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.random.Random

/**
 * Visualizador de forma de onda estilo "gravador": barras verticais que
 * acompanham o nível do microfone em tempo real (durante a gravação) ou
 * um padrão fixo (quando parado).
 */
class WaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bars = ArrayDeque<Float>()
    private var active = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1783FF")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    /** Alimenta um sample de amplitude normalizado (0..1). */
    fun push(level: Float) {
        bars.addLast(level.coerceIn(0.02f, 1f))
        while (bars.size > width / 14) bars.removeFirst()
        invalidate()
    }

    fun setActive(a: Boolean) {
        active = a
        if (!a) bars.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return
        val n = bars.size
        val step = width.toFloat() / n
        for ((i, v) in bars.withIndex()) {
            val x = i * step + step / 2
            val h = v * height * 0.9f
            canvas.drawLine(x, height / 2f - h / 2, x, height / 2f + h / 2, paint)
        }
    }
}

/**
 * Captura de nível de microfone em paralelo à gravação (para alimentar o WaveView).
 * Usa AudioRecord separado — só para visualização; o áudio gravado vem do MediaRecorder.
 */
class MicLevel(private val onLevel: (Float) -> Unit) {
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val rate = 8000
            val bufSize = android.media.AudioRecord.getMinBufferSize(
                rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)
            val rec = try {
                android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
            } catch (e: Exception) { null }
            if (rec == null || rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
                // fallback: animação pseudo-aleatória (visual apenas)
                while (running) {
                    onLevel(Random.nextFloat() * 0.85f + 0.1f)
                    Thread.sleep(120)
                }
                return@Thread
            }
            val buf = ShortArray(bufSize / 2)
            rec.startRecording()
            while (running) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) sum += (buf[i].toDouble() * buf[i])
                    val rms = Math.sqrt(sum / read) / 32768.0
                    onLevel(((rms * 6).toFloat()).coerceIn(0.05f, 1f))
                }
                Thread.sleep(120)
            }
            runCatching { rec.stop() }
            rec.release()
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}
