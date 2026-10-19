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
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Gravador Smart")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                try {
                    val out = resolver.openOutputStream(uri)
                        ?: error("Não foi possível abrir o destino")
                    out.use { output -> file.inputStream().use { it.copyTo(output) } }
                    val publish = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    if (resolver.update(uri, publish, null, null) < 1)
                        error("Não foi possível concluir a exportação")
                    uri
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                val folder = File(downloads, "Gravador Smart")
                if (!folder.isDirectory && !folder.mkdirs())
                    error("Não foi possível criar a pasta Downloads")
                val dest = File(folder, displayName)
                file.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        }.getOrNull()
    }
}
