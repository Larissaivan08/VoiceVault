package com.example.voicevault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.voicevault.databinding.ActivityMainBinding
import com.example.voicevault.db.AppDb
import com.example.voicevault.db.Folder
import com.example.voicevault.db.Recording
import com.example.voicevault.io.AudioStore
import com.example.voicevault.ui.FolderChipAdapter
import com.example.voicevault.ui.RecordingAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val db by lazy { AppDb.get(this) }
    private val store by lazy { AudioStore(this) }

    private var currentFolderId: Long? = null // null = Raiz
    private val queryFlow = MutableStateFlow<String?>(null)

    private lateinit var folderChips: FolderChipAdapter
    private lateinit var recordings: RecordingAdapter

    // ---- Gravação ----
    private var recorder: MediaRecorder? = null
    private var timer: CountDownTimer? = null
    private var recordStartMs = 0L
    private var pendingFolder: Long? = null // pasta destino da gravação em andamento

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording()
        else toast("Sem permissão de microfone. Conceda em Configurações → Apps → VoiceVault.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupLists()
        setupButtons()
        observeData()
    }

    private fun setupLists() {
        folderChips = FolderChipAdapter { id -> selectFolder(id) }
        b.folderChips.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.folderChips.adapter = folderChips

        recordings = RecordingAdapter(
            onClick = { item -> openPlayer(item.id) },
            onLongClick = { item -> showRecordingMenu(item.id, item.title) },
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = recordings

        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, e: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, e: Int) {
                queryFlow.value = s?.toString()?.trim()?.ifEmpty { null }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupButtons() {
        b.btnRecord.setOnClickListener { toggleRecording() }
        b.btnAddFolder.setOnClickListener { promptText(
            title = "Nova pasta", hint = "Nome da pasta", prefill = "",
        ) { name -> createFolder(name) } }
        b.emptyHint.setOnClickListener { /* apenas informativo */ }
    }

    private fun observeData() {
        // Chips de pastas (Raiz + pastas com contagem)
        lifecycleScope.launch {
            db.folderDao().flowWithCount().collect { list ->
                folderChips.selectedId = currentFolderId
                folderChips.submitList(listOf<Any>("root") + list)
            }
        }
        // Lista de gravações: reage à pasta selecionada E à busca, em um único Flow.
        lifecycleScope.launch {
            combine(currentFolderFlow, queryFlow) { folder, q -> folder to q }
                .flatMapLatest { (folder, q) ->
                    if (folder == null && q != null) {
                        db.recordingDao().flowAll(q)   // buscando na Raiz → busca global
                    } else {
                        db.recordingDao().flowIn(folder, q)
                    }
                }
                .collect { items ->
                    recordings.submitList(items)
                    b.emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    private val currentFolderFlow = MutableStateFlow<Long?>(null)

    private fun selectFolder(id: Long?) {
        currentFolderId = id
        currentFolderFlow.value = id
    }

    private fun openPlayer(id: Long) {
        startActivity(
            Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_ID, id)
        )
    }

    // ---------- Pastas ----------

    private fun createFolder(name: String) {
        lifecycleScope.launch {
            if (db.folderDao().countByName(name) > 0) {
                toast("Já existe uma pasta com esse nome.")
                return@launch
            }
            val id = db.folderDao().insert(Folder(name = name))
            selectFolder(id)
            toast("Pasta \"$name\" criada.")
        }
    }

    private fun showRecordingMenu(id: Long, title: String) {
        val options = arrayOf("▶ Ouvir", "📂 Mover para pasta…", "✏ Renomear", "🗑 Excluir")
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_ID, id))
                    1 -> showMoveDialog(id)
                    2 -> promptText("Renomear", "Novo nome", title) { newName -> rename(id, newName) }
                    3 -> confirmDelete(id)
                }
            }
            .show()
    }

    private fun showMoveDialog(recordingId: Long) {
        lifecycleScope.launch {
            val folders = mutableListOf<Pair<String, Long?>>("🏠 Raiz" to null)
            folders += db.folderDao().listWithCount().map { it.name to it.id }
            val names = folders.map { it.first }.toTypedArray()
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Mover para…")
                .setItems(names) { _, i ->
                    launch {
                        db.recordingDao().moveToFolder(recordingId, folders[i].second)
                        toast("Gravação movida.")
                    }
                }
                .show()
        }
    }

    private fun rename(id: Long, newTitle: String) {
        lifecycleScope.launch {
            db.recordingDao().byId(id)?.let {
                db.recordingDao().update(it.copy(title = newTitle))
            }
        }
    }

    private fun confirmDelete(id: Long) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Excluir gravação?")
            .setMessage("O arquivo de áudio será apagado permanentemente.")
            .setPositiveButton("Excluir") { _, _ ->
                lifecycleScope.launch {
                    db.recordingDao().byId(id)?.let { db.recordingDao().delete(it) }
                    store.delete(id)
                    toast("Excluída.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Gravação ----------

    private fun toggleRecording() {
        if (recorder != null) stopRecording() else maybeStartRecording()
    }

    private fun maybeStartRecording() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        startRecording()
    }

    @Suppress("MissingPermission") // pedimos RECORD_AUDIO acima
    private fun startRecording() {
        pendingFolder = currentFolderId
        val tmp = java.io.File(cacheDir, "rec_tmp.m4a").also { it.delete() }
        try {
            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(tmp.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            toast("Falha ao iniciar gravação: ${e.message}")
            recorder = null
            return
        }
        recordStartMs = System.currentTimeMillis()
        b.timerLabel.visibility = View.VISIBLE
        b.btnRecord.text = "⏹ Parar"
        timer = object : CountDownTimer(Long.MAX_VALUE, 500) {
            override fun onTick(ms: Long) {
                val elapsed = System.currentTimeMillis() - recordStartMs
                b.timerLabel.text = RecordingAdapter.formatDuration(elapsed)
            }
            override fun onFinish() {}
        }.start()
    }

    private fun stopRecording() {
        timer?.cancel(); timer = null
        val rec = recorder ?: return
        recorder = null
        b.btnRecord.text = "🎙 Gravar"
        b.timerLabel.visibility = View.GONE
        val duration = System.currentTimeMillis() - recordStartMs

        lifecycleScope.launch {
            val id = db.recordingDao().insert(
                Recording(
                    title = "Gravação " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                        .format(Date(recordStartMs)),
                    folderId = pendingFolder,
                    durationMs = duration,
                    sizeBytes = 0,
                )
            )
            val dest = store.fileFor(id)
            java.io.File(cacheDir, "rec_tmp.m4a").let { tmp ->
                tmp.renameTo(dest)
                db.recordingDao().byId(id)?.let { r ->
                    db.recordingDao().update(r.copy(sizeBytes = dest.length()))
                }
            }
            toast("Salva! (${dest.length() / 1024} KB)")
        }
    }

    // ---------- Utilidades ----------

    private fun promptText(title: String, hint: String, prefill: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            this.hint = hint
            setText(prefill)
            setSelection(prefill.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { d, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) onOk(t) else toast("Nome vazio.")
            }
            .setNegativeButton("Cancelar") { d, _ -> d.cancel() }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        runCatching { recorder?.apply { stop(); release() } }
        recorder = null
    }
}

