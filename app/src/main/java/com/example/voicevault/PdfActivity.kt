package com.example.voicevault

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import com.google.android.material.card.MaterialCardView
import androidx.core.graphics.ColorUtils
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Local PDF library; independent of audio folders and database. */
class PdfActivity : AppCompatActivity() {
    private lateinit var db: PdfDb
    private lateinit var foldersRow: GridLayout
    private lateinit var documentsList: LinearLayout
    private var folderId: Long? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) chooseDestination(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = PdfDb()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 20.dp(), 18.dp(), 12.dp())
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0x12EF5E64, 20)
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }
        tabs.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Áudios"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, 48.dp(), 1f))
        tabs.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "PDFs"
            isAllCaps = false
            isEnabled = false
        }, LinearLayout.LayoutParams(0, 48.dp(), 1f))
        root.addView(tabs)
        root.addView(TextView(this).apply {
            text = "Biblioteca de PDFs"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
            setPadding(2.dp(), 20.dp(), 0, 2.dp())
        })
        root.addView(TextView(this).apply {
            text = "Organize seus documentos com segurança, mesmo offline."
            textSize = 14f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            setPadding(2.dp(), 0, 0, 17.dp())
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "+ Importar PDF"
            isAllCaps = false
            textSize = 13f
            setOnClickListener { picker.launch(arrayOf("application/pdf")) }
        }, LinearLayout.LayoutParams(0, 56.dp(), 1f))
        actions.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "+ Nova pasta"
            isAllCaps = false
            textSize = 13f
            setOnClickListener { askName("Criar pasta", "") { name ->
                db.writableDatabase.insertOrThrow("folders", null,
                    ContentValues().apply { put("name", name) })
                refresh()
            } }
        }, LinearLayout.LayoutParams(0, 56.dp(), 1f).apply { marginStart = 6.dp() })
        root.addView(actions)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(sectionTitle("Pastas"))
        foldersRow = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_MARGINS
            useDefaultMargins = false
        }
        body.addView(foldersRow, LinearLayout.LayoutParams(-1, -2))
        body.addView(sectionTitle("Documentos da pasta"))
        documentsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(documentsList)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
        consumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) chooseDestination(uri)
    }

    private fun allFolders(): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        db.readableDatabase.rawQuery("SELECT id,name FROM folders ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) result.add(c.getLong(0) to c.getString(1))
        }
        return result
    }

    private fun chooseDestination(uri: Uri) {
        val folders = allFolders()
        val choices = arrayOf("Raiz (sem pasta)", "+ Criar nova pasta") +
            folders.map { it.second }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Salvar PDF em qual pasta?")
            .setItems(choices) { _, index ->
                when (index) {
                    0 -> importPdf(uri, null)
                    1 -> askName("Nova pasta de PDFs", "") { name ->
                        val id = db.writableDatabase.insertOrThrow("folders", null,
                            ContentValues().apply { put("name", name) })
                        importPdf(uri, id)
                    }
                    else -> importPdf(uri, folders[index - 2].first)
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun importPdf(uri: Uri, destination: Long?) {
        try {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Documento.pdf"
            val safeName = name.substringAfterLast('/').take(160)
                .let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
            val file = File(File(filesDir, "pdf_documents").apply { mkdirs() },
                UUID.randomUUID().toString() + ".pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Arquivo inacessível")
            if (file.length() == 0L) error("Arquivo vazio")
            val id = db.writableDatabase.insertOrThrow("documents", null, ContentValues().apply {
                put("name", safeName)
                put("path", file.absolutePath)
                if (destination == null) putNull("folderId") else put("folderId", destination)
            })
            if (id < 0) error("Falha ao registrar PDF")
            folderId = destination
            refresh()
            Toast.makeText(this, "PDF salvo no aparelho.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível importar o PDF.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        foldersRow.removeAllViews()
        val folders = allFolders()
        val colors = intArrayOf(0xFFDB797E.toInt(), 0xFF459FE1.toInt(),
            0xFFAE72DE.toInt(), 0xFF46B99B.toInt(), 0xFFE8B34F.toInt())
        fun addFolder(name: String, id: Long?, index: Int) {
            val selected = folderId == id
            val tint = colors[index % colors.size]
            val card = MaterialCardView(this).apply {
                radius = 18.dp().toFloat()
                cardElevation = if (selected) 4.dp().toFloat() else 1.dp().toFloat()
                setCardBackgroundColor(ColorUtils.setAlphaComponent(tint, if (selected) 52 else 25))
                strokeColor = tint
                strokeWidth = if (selected) 2.dp() else 1.dp()
                isClickable = true
                setOnClickListener { folderId = id; refresh() }
            }
            val count = db.readableDatabase.rawQuery(
                if (id == null) "SELECT COUNT(*) FROM documents WHERE folderId IS NULL"
                else "SELECT COUNT(*) FROM documents WHERE folderId = ?",
                if (id == null) null else arrayOf(id.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = null
                setPadding(14.dp(), 17.dp(), 10.dp(), 17.dp())
            }
            content.addView(TextView(this).apply {
                text = name
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
                background = null
            })
            content.addView(TextView(this).apply {
                text = "$count PDFs"
                textSize = 13f
                alpha = 0.72f
                setPadding(0, 5.dp(), 0, 0)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
                background = null
            })
            card.addView(content)
            if (id != null) card.setOnLongClickListener {
                AlertDialog.Builder(this@PdfActivity).setTitle(name)
                    .setItems(arrayOf("Renomear", "Excluir pasta")) { _, option ->
                        if (option == 0) askName("Renomear pasta", name) { newName ->
                            db.writableDatabase.execSQL("UPDATE folders SET name=? WHERE id=?",
                                arrayOf(newName, id))
                            refresh()
                        } else AlertDialog.Builder(this@PdfActivity)
                            .setTitle("Excluir pasta?")
                            .setMessage("Os PDFs dessa pasta serão movidos para a Raiz.")
                            .setPositiveButton("Excluir") { _, _ ->
                                db.writableDatabase.execSQL("UPDATE documents SET folderId=NULL WHERE folderId=?", arrayOf(id))
                                db.writableDatabase.delete("folders", "id=?", arrayOf(id.toString()))
                                folderId = null
                                refresh()
                            }.setNegativeButton("Cancelar", null).show()
                    }.show()
                true
            }
            foldersRow.addView(card, GridLayout.LayoutParams(
                GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f)
            ).apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(5.dp(), 5.dp(), 5.dp(), 5.dp())
            })
        }
        addFolder("Raiz", null, 0)
        folders.forEachIndexed { index, pair -> addFolder(pair.second, pair.first, index + 1) }
        documentsList.removeAllViews()
        val docs = mutableListOf<Triple<Long, String, String>>()
        val where = if (folderId == null) "folderId IS NULL" else "folderId=?"
        val args = folderId?.let { arrayOf(it.toString()) }
        db.readableDatabase.query("documents", arrayOf("id", "name", "path"),
            where, args, null, null, "id DESC").use { c ->
            while (c.moveToNext()) docs.add(Triple(c.getLong(0), c.getString(1), c.getString(2)))
        }
        if (docs.isEmpty()) documentsList.addView(TextView(this).apply { text = "Nenhum PDF nesta pasta." })
        docs.forEach { (id, name, path) ->
            documentsList.addView(Button(this).apply {
                text = "PDF  ·  $name"
                isAllCaps = false
                setOnClickListener {
                    try {
                        val file = File(path)
                        val uri = FileProvider.getUriForFile(this@PdfActivity,
                            "$packageName.fileprovider", file)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Abrir PDF"))
                    } catch (e: Exception) {
                        Toast.makeText(this@PdfActivity, "Instale um leitor de PDF para abrir.", Toast.LENGTH_LONG).show()
                    }
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@PdfActivity).setTitle(name)
                        .setItems(arrayOf("Mover", "Renomear", "Excluir")) { _, choice ->
                            when (choice) {
                                0 -> {
                                    val targets = listOf<Pair<Long?, String>>(null to "Raiz") +
                                        allFolders().map { it.first to it.second }
                                    AlertDialog.Builder(this@PdfActivity).setTitle("Mover PDF")
                                        .setItems(targets.map { it.second }.toTypedArray()) { _, selected ->
                                            val next = targets[selected].first
                                            db.writableDatabase.execSQL("UPDATE documents SET folderId=? WHERE id=?",
                                                arrayOf(next, id))
                                            folderId = next
                                            refresh()
                                        }.show()
                                }
                                1 -> askName("Renomear PDF", name) { next ->
                                    db.writableDatabase.execSQL("UPDATE documents SET name=? WHERE id=?",
                                        arrayOf(next, id))
                                    refresh()
                                }
                                2 -> AlertDialog.Builder(this@PdfActivity).setTitle("Excluir PDF?")
                                    .setPositiveButton("Excluir") { _, _ ->
                                        db.writableDatabase.delete("documents", "id=?", arrayOf(id.toString()))
                                        File(path).delete()
                                        refresh()
                                    }.setNegativeButton("Cancelar", null).show()
                            }
                        }.show()
                    true
                }
            })
        }
    }

    private fun themeColor(attr: Int, fallback: Int): Int =
        com.google.android.material.color.MaterialColors.getColor(this, attr, fallback)

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = radiusDp.dp().toFloat()
        setColor(color)
    }

    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = 20f
        setTypeface(null, Typeface.BOLD)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        setPadding(2.dp(), 20.dp(), 0, 10.dp())
    }

    private fun askName(title: String, current: String, action: (String) -> Unit) {
        val input = EditText(this).apply { setText(current); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) runCatching { action(name) }
                    .onFailure { Toast.makeText(this, "Não foi possível salvar.", Toast.LENGTH_SHORT).show() }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private inner class PdfDb : SQLiteOpenHelper(this@PdfActivity, "pdf_library.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE documents(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,path TEXT NOT NULL,folderId INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}
