package com.example.voicevault.io

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Exportação de gravações: compartilhar (WhatsApp, e-mail…) ou salvar no aparelho (Downloads/Música). */
object Exporter {

    /** Intent de compartilhamento via FileProvider. */
    fun shareIntent(context: Context, file: File, mime: String, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Exportar \"$title\""
        )
    }

    /**
     * Copia o arquivo para Downloads/VoiceVault via MediaStore — funciona sem
     * permissão de armazenamento no Android 10+. Retorna a URI pública criada.
     */
    fun saveToDevice(context: Context, file: File, displayName: String, mime: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/VoiceVault")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                val out = resolver.openOutputStream(uri) ?: error("Não foi possível abrir o destino")
                out.use { stream -> file.inputStream().use { input -> input.copyTo(stream) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        } else {
            // Android 8/9: cópia direta para Downloads (WRITE_EXTERNAL_STORAGE pedida antes)
            @Suppress("DEPRECATION")
            val dl = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "VoiceVault").apply { mkdirs() }
            val dest = File(dl, displayName)
            runCatching {
                file.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }.getOrNull()
        }
    }
}
