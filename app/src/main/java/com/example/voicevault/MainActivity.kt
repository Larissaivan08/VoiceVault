package com.example.voicevault

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.example.voicevault.db.AudioFormat
import com.example.voicevault.db.Folder
import com.example.voicevault.db.FolderCount
import com.example.voicevault.db.Recording
import com.example.voicevault.io.AudioStore
import com.example.voicevault.ui.FolderListAdapter
import com.example.voicevault.ui.RecordingAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val db by lazy { AppDb.get(this) }
    private val store by lazy { AudioStore(this) }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var currentFolderId: Long? = null // null = Raiz
    private val queryFlow = MutableStateFlow<String?>(null)
    private val currentFolderFlow = MutableStateFlow<Long?>(null)

    private lateinit var folderList: FolderListAdapter
    private lateinit var recordings: RecordingAdapter

    private var chosenFormat = AudioFormat.M4A

    // ---- Gravação (via RecorderService — funciona em segundo plano) ----
    private var timer: CountDownTimer? = null
    private var recordStartMs = 0L
    private var pendingFolder: Long? = null
    private var pendingFormat = AudioFormat.M4A
    private var tmpFile: File? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording()
        else toast("Sem permissão de microfone. Conceda em Configurações → Apps → VoiceVault.")
    }

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // negar notificação não impede a gravação; só some o indicador
        startRecording()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        chosenFormat = AudioFormat.fromName(prefs.getString("format", "M4A"))
        updateFormatButton()

        setupLists()
        setupButtons()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        syncRecordingState()
        checkStoppedRecord()
    }

    private fun setupLists() {
        folderList = FolderListAdapter(
            onClick = { id -> selectFolder(id) },
            onLongClick = { f -> showFolderMenu(f) },
        )
        b.folderList.layoutManager = LinearLayoutManager(this) // vertical: um embaixo do outro
        b.folderList.adapter = folderList

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
        b.btnFormat.setOnClickListener { chooseFormat() }
    }

    private fun observeData() {
        // Painel lateral de pastas (Raiz + pastas com contagem)
        lifecycleScope.launch {
            db.folderDao().flowWithCount().collect { list ->
                folderList.selectedId = currentFolderId
                folderList.submitList(listOf<Any>("root") + list)
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

    private fun selectFolder(id: Long?) {
        currentFolderId = id
        currentFolderFlow.value = id
    }

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

    private fun openPlayer(id: Long) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_ID, id))
    }

    // ---------- Pastas: renomear / excluir ----------

    private fun showFolderMenu(folder: FolderCount) {
        val options = arrayOf("✏ Renomear pasta", "🗑 Excluir pasta")
        android.app.AlertDialog.Builder(this)
            .setTitle("📁 ${folder.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptText("Renomear pasta", "Novo nome", folder.name) { newName ->
                        renameFolder(folder.id, newName)
                    }
                    1 -> confirmDeleteFolder(folder)
                }
            }
            .show()
    }

    private fun renameFolder(id: Long, newName: String) {
        lifecycleScope.launch {
            if (db.folderDao().countByNameOther(newName, id) > 0) {
                toast("Já existe uma pasta com esse nome.")
                return@launch
            }
            db.folderDao().rename(id, newName)
            toast("Pasta renomeada.")
        }
    }

    private fun confirmDeleteFolder(folder: FolderCount) {
        val msg = if (folder.recordingCount > 0)
            "A pasta \"${folder.name}\" tem ${folder.recordingCount} gravação(ões). " +
            "Excluir a pasta apaga TODAS as gravações dela. Continuar?"
        else
            "Excluir a pasta \"${folder.name}\"?"
        android.app.AlertDialog.Builder(this)
            .setTitle("Excluir pasta?")
            .setMessage(msg)
            .setPositiveButton("Excluir") { _, _ ->
                lifecycleScope.launch {
                    // apaga os arquivos físicos antes da cascata no banco
                    db.recordingDao().inFolderSync(folder.id).forEach { store.delete(it.id) }
                    db.folderDao().deleteById(folder.id)
                    if (currentFolderId == folder.id) selectFolder(null)
                    toast("Pasta excluída.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Menu de gravação ----------

    private fun showRecordingMenu(id: Long, title: String) {
        val options = arrayOf("▶ Ouvir", "📂 Mover para pasta…", "✏ Renomear", "🗑 Excluir")
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openPlayer(id)
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
            db.recordingDao().byId(id)?.let { db.recordingDao().update(it.copy(title = newTitle)) }
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

    // ---------- Escolha de formato ----------

    private fun chooseFormat() {
        val labels = AudioFormat.entries.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Formato de gravação")
            .setSingleChoiceItems(labels, AudioFormat.entries.indexOf(chosenFormat)) { d, which ->
                chosenFormat = AudioFormat.entries[which]
                prefs.edit().putString("format", chosenFormat.name).apply()
                updateFormatButton()
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateFormatButton() {
        b.btnFormat.text = chosenFormat.name
    }

    // ---------- Gravação via serviço em segundo plano ----------

    private fun toggleRecording() {
        if (isRecordingActive()) stopServiceRecording() else maybeStartRecording()
    }

    private fun isRecordingActive(): Boolean = LastRecord.result is LastRecord.Recording

    private fun maybeStartRecording() {
        if (!RecorderService.hasMicPermission(this)) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        startRecording()
    }

    private fun startRecording() {
        pendingFolder = currentFolderId
        pendingFormat = chosenFormat
        val tmp = File(cacheDir, "rec_tmp.${pendingFormat.ext}").also { it.delete() }
        tmpFile = tmp
        recordStartMs = System.currentTimeMillis()

        val intent = Intent(this, RecorderService::class.java).apply {
            putExtra(RecorderService.EXTRA_OUTPUT, tmp.absolutePath)
            putExtra(RecorderService.EXTRA_FORMAT, pendingFormat.name)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            toast("Falha ao iniciar gravação: ${e.message}")
            return
        }
        updateRecordingUi(true)
    }

    private fun stopServiceRecording() {
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        // Aguarda o serviço parar (onDestroy grava LastRecord.Stopped) e então salva.
        updateRecordingUi(false)
        pollStoppedRecord(0)
    }

    /** Tenta buscar o resultado do serviço por alguns segundos após o "Parar". */
    private fun pollStoppedRecord(attempt: Int) {
        if (attempt > 20) return // ~5 s de espera máxima
        b.root.postDelayed({
            val st = LastRecord.result
            if (st is LastRecord.Stopped || st is LastRecord.Fail) {
                if (st is LastRecord.Fail) toast(st.message) else checkStoppedRecord()
            } else {
                pollStoppedRecord(attempt + 1)
            }
        }, 250)
    }

    private fun updateRecordingUi(recording: Boolean) {
        b.btnRecord.text = if (recording) "⏹ Parar" else "🎙 Gravar"
        b.timerLabel.visibility = if (recording) View.VISIBLE else View.GONE
        b.statusLabel.text = if (recording) "● gravando (2º plano ok)" else ""
        timer?.cancel()
        if (recording) {
            timer = object : CountDownTimer(Long.MAX_VALUE, 500) {
                override fun onTick(ms: Long) {
                    val elapsed = System.currentTimeMillis() - recordStartMs
                    b.timerLabel.text = RecordingAdapter.formatDuration(elapsed)
                }
                override fun onFinish() {}
            }.start()
        }
    }

    /** Sincroniza a UI quando voltamos ao app com gravação em andamento (ex.: tela apagada). */
    private fun syncRecordingState() {
        val st = LastRecord.result
        if (st is LastRecord.Recording) {
            recordStartMs = st.startedAt
            pendingFormat = AudioFormat.fromName(st.format)
            updateRecordingUi(true)
        } else if (!isRecordingActive()) {
            updateRecordingUi(false)
        }
    }

    /** Se o serviço terminou, pega o resultado e salva a gravação no banco. */
    private fun checkStoppedRecord() {
        val st = LastRecord.result
        if (st is LastRecord.Fail) {
            LastRecord.result = null
            toast(st.message)
            syncRecordingState()
            return
        }
        if (st !is LastRecord.Stopped) return
        LastRecord.result = null
        val file = File(st.outPath)
        if (!file.exists() || file.length() == 0L) {
            toast("Gravação vazia — nada salvo.")
            return
        }
        lifecycleScope.launch {
            val fmt = pendingFormat
            val id = db.recordingDao().insert(
                Recording(
                    title = "Gravação " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                        .format(Date(recordStartMs)),
                    folderId = pendingFolder,
                    durationMs = max(st.durationMs, 100),
                    sizeBytes = file.length(),
                    format = fmt.name,
                )
            )
            val dest = store.fileFor(id, fmt)
            file.renameTo(dest)
            db.recordingDao().byId(id)?.let { r ->
                db.recordingDao().update(r.copy(sizeBytes = dest.length()))
            }
            toast("Salva! (${dest.length() / 1024} KB, ${fmt.name})")
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
            .setPositiveButton("OK") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) onOk(t) else toast("Nome vazio.")
            }
            .setNegativeButton("Cancelar") { _, _ -> }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}
