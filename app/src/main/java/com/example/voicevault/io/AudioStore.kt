package com.example.voicevault.io

import android.content.Context
import com.example.voicevault.db.AudioFormat
import java.io.File

/** Dono do caminho dos arquivos de áudio. Um arquivo por gravação: <id>.<ext> */
class AudioStore(context: Context) {

    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }

    fun fileFor(id: Long, format: AudioFormat = AudioFormat.M4A): File = File(dir, "$id.${format.ext}")

    /** Encontra o arquivo físico de uma gravação em qualquer extensão conhecida. */
    fun findExisting(id: Long): File? =
        AudioFormat.entries.map { fileFor(id, it) }.firstOrNull { it.exists() }

    /** Apaga o arquivo físico (chamado junto com a exclusão no banco). */
    fun delete(id: Long) {
        findExisting(id)?.delete()
    }
}
