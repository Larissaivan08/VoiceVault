package com.example.voicevault.db

import android.media.MediaRecorder

/** Qualidade de gravação (bitrate + taxa de amostragem). */
enum class AudioQuality(val label: String, val bitRate: Int, val sampleRate: Int) {
    HIGH("Alta qualidade (AAC 128 kbps)", 128_000, 44_100),
    STANDARD("Qualidade padrão (96 kbps)", 96_000, 44_100),
    LOW("Arquivo menor (64 kbps)", 64_000, 22_050);

    companion object {
        fun fromName(name: String?): AudioQuality =
            entries.firstOrNull { it.name == name } ?: HIGH
    }
}

/** Formatos de áudio suportados pela gravação. */
enum class AudioFormat(val ext: String, val mime: String, val label: String) {
    M4A("m4a", "audio/mp4", "M4A (AAC) — padrão, ótima qualidade"),
    AAC("aac", "audio/aac", "AAC em ADTS (.aac) — arquivo menor"),
    MP3("mp3", "audio/mpeg", "MP3 — se o aparelho tiver encoder nativo");

    /**
     * Configura formato + encoder no MediaRecorder.
     * @return true se o dispositivo aceitou o formato.
     */
    fun configure(recorder: MediaRecorder): Boolean = runCatching {
        when (this) {
            M4A -> {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            AAC -> {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            MP3 -> {
                // O AOSP não tem constante pública para MP3; tentamos via reflection
                // (alguns fabricantes adicionam OutputFormat/AudioEncoder.MP3).
                val outConst = constantOrNull(
                    MediaRecorder.OutputFormat::class.java, "MP3"
                ) ?: error("Este aparelho não tem encoder MP3 nativo.")
                val encConst = constantOrNull(
                    MediaRecorder.AudioEncoder::class.java, "MP3"
                ) ?: error("Este aparelho não tem encoder MP3 nativo.")
                recorder.setOutputFormat(outConst)
                recorder.setAudioEncoder(encConst)
            }
        }
        true
    }.getOrDefault(false)

    private fun constantOrNull(clazz: Class<*>, name: String): Int? = try {
        clazz.getField(name).getInt(null)
    } catch (e: Throwable) {
        null
    }

    companion object {
        fun fromName(name: String?): AudioFormat =
            entries.firstOrNull { it.name == name } ?: M4A
    }
}
