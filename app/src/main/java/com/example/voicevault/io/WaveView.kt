package com.example.voicevault.io

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Decorative looping waveform; audio is recorded independently by RecorderService. */
class WaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private var active = false
    private var phase = 0.0

    fun push(level: Float) { /* Kept for compatibility; visual is decorative. */ }

    fun setActive(enabled: Boolean) {
        active = enabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val count = 55
        val gap = w / count
        for (i in 0 until count) {
            val xNorm = i.toFloat() / (count - 1)
            val envelope = sin(Math.PI * xNorm).toFloat().coerceAtLeast(0f)
            val pulse = abs(sin(i * 0.23 + phase) * sin(i * 0.11 - phase * 0.55))
            val length = (0.06 + (0.72 * envelope * (0.35 + pulse * 0.65))) * h
            val x = (i + 0.5f) * gap
            paint.color = android.graphics.Color.rgb(
                239, (90 + 75 * envelope).toInt(), (100 + 38 * xNorm).toInt())
            paint.alpha = (80 + 175 * envelope).toInt().coerceIn(0,255)
            canvas.drawLine(x, h / 2f - length.toFloat() / 2f,
                x, h / 2f + length.toFloat() / 2f, paint)
        }
        phase += if (active) 0.095 else 0.045
        postInvalidateDelayed(40L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The View stops receiving frame callbacks when detached.
    }
}

/** Legacy no-op visual level collector. The waveform no longer opens a second mic. */
class MicLevel(private val onLevel: (Float) -> Unit) {
    fun start() {}
    fun stop() {}
}
