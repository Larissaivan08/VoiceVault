package com.example.voicevault

/** Estado da gravação em tempo real, compartilhado Service <-> Activity (mesmo processo). */
object LiveSession {
    @Volatile var active = false
    @Volatile var startedAt = 0L
    @Volatile var pausedTotalMs = 0L
    @Volatile var paused = false
    @Volatile var format = "M4A"

    fun begin(start: Long, fmt: String) {
        active = true; startedAt = start; pausedTotalMs = 0; paused = false; format = fmt
    }
    fun setPaused(p: Boolean, at: Long) {
        if (p == paused) return
        if (p) paused = true
        else { pausedTotalMs += at - pauseMark; paused = false }
    }
    private var pauseMark = 0L
    fun markPause(at: Long) { pauseMark = at }
    fun end() { active = false; paused = false }
}
