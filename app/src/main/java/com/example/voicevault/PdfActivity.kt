package com.example.voicevault

import android.app.Activity
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
    private lateinit var foldersRow: LinearLayout
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
            setPadding(20.dp(), 24.dp(), 20.dp(), 16.dp())
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(Button(this).apply {
            text = "Áudios"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, 52.dp(), 1f))
        tabs.addView(Button(this).apply {
            text = "PDFs"
            isEnabled = false
        }, LinearLayout.LayoutParams(0, 52.dp(), 1f))
        root.addView(tabs)
        root.addView(TextView(this).apply {
            text = "Meus PDFs"
            textSize = 25f
            setPadding(0, 14.dp(), 0, 8.dp())
        })
        val actions = LinearLayout(this)
        actions.addView(Button(this).apply {
            text = "+ Importar PDF"
            setOnClickListener { picker.launch(arrayOf("application/pdf")) }
        }, LinearLayout.LayoutParams(0, 52.dp(), 1f))
        actions.addView(Button(this).apply {
            text = "+ Nova pasta"
            setOnClickListener { askName("Criar pasta", "") { name ->
                db.writableDatabase.insertOrThrow("folders", null,
                    ContentValues().apply { put("name", name) })
                refresh()
            } }
        }, LinearLayout.LayoutParams(0, 52.dp(), 1f))
        root.addView(actions)
        root.addView(TextView(this).apply {
            text = "Pastas de PDFs"
            textSize = 19f
            setPadding(0, 16.dp(), 0, 8.dp())
        })
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        foldersRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        documentsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(foldersRow)
        body.addView(TextView(this).apply {
            text = "Documentos"
            textSize = 19f
            setPadding(0, 16.dp(), 0, 8.dp())
        })
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
        fun addFolder(name: String, id: Long?) {
            foldersRow.addView(Button(this).apply {
                text = (if (folderId == id) "✓  " else "📁  ") + name
                isAllCaps = false
                setOnClickListener { folderId = id; refresh() }
                if (id != null) setOnLongClickListener {
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
            })
        }
        addFolder("Raiz", null)
        folders.forEach { addFolder(it.second, it.first) }
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
