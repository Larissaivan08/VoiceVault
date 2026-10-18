package com.example.voicevault.io

import android.content.Context
import java.io.File

/** Dono do caminho dos arquivos de áudio. Um arquivo por gravação: <id>.m4a */
class AudioStore(context: Context) {

    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }

    fun fileFor(id: Long): File = File(dir, "$id.m4a")

    /** Apaga o arquivo físico (chamado junto com a exclusão no banco). */
    fun delete(id: Long) {
        fileFor(id).delete()
    }
}
