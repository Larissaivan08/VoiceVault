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
import com.example.voicevault.db.AudioQuality
import com.example.voicevault.io.Exporter
import com.example.voicevault.db.Folder
import com.example.voicevault.db.FolderCount
import com.example.voicevault.db.Recording
import com.example.voicevault.io.AudioStore
import com.example.voicevault.io.MicLevel
import androidx.recyclerview.widget.GridLayoutManager
import com.example.voicevault.ui.FolderAdapter
import com.example.voicevault.ui.RecordingAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val db by lazy { AppDb.get(this) }
    private val store by lazy { AudioStore(this) }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var currentFolderId: Long? = null // null = Raiz
    private val queryFlow = MutableStateFlow<String?>(null)
    private val currentFolderFlow = MutableStateFlow<Long?>(null)

    private lateinit var recordings: RecordingAdapter
    private lateinit var folders: FolderAdapter
    private var lastFolderList: List<FolderCount> = emptyList()
    private var sortKey = "date"   // date | name | size | duration
    private var sortAsc = false

    private var chosenFormat = AudioFormat.M4A
    private var chosenQuality = AudioQuality.HIGH

    // ---- Gravação (via RecorderService — funciona em segundo plano) ----
    private var timer: CountDownTimer? = null
    private var recordStartMs = 0L
    private var sessionFormat = AudioFormat.M4A
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
        chosenQuality = AudioQuality.fromName(prefs.getString("quality", "HIGH"))
        updateFormatButton()
        updateQualityLabel()
        sortKey = prefs.getString("sortKey", "date")!!
        sortAsc = prefs.getBoolean("sortAsc", false)
        b.btnSort.text = "Ordenar: " + if (sortAsc) "mais antigas" else "mais recentes"

        setupLists()
        setupButtons()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        refreshRecordingState()
    }

    private fun setupLists() {
        recordings = RecordingAdapter(
            onClick = { item -> openPlayer(item.id) },
            onLongClick = { item -> showRecordingMenu(item.id, item.title) },
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = recordings

        folders = FolderAdapter(
            onClick = { id -> selectFolder(id) },
            onLongClick = { f -> showFolderMenu(f) },
            isSelected = { id -> currentFolderId == id },
        )
        b.folderGrid.layoutManager = GridLayoutManager(this, 2)
        b.folderGrid.adapter = folders
        b.folderGrid.isNestedScrollingEnabled = true

        b.btnSort.setOnClickListener { chooseSort() }

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
        b.btnPauseResume.setOnClickListener { togglePauseResume() }
        b.btnMarker.setOnClickListener { addMarker() }
        b.btnQuality.setOnClickListener { chooseQuality() }
        b.btnAddFolder.setOnClickListener { promptText(
            title = "Nova pasta", hint = "Nome da pasta", prefill = "",
        ) { name -> createFolder(name) } }
        b.btnFormat.setOnClickListener { chooseFormat() }
    }

    private fun observeData() {
        // Grade de pastas (Raiz + pastas com contagem), independente da gravação
        lifecycleScope.launch {
            db.folderDao().flowWithCount().collect { list ->
                lastFolderList = list
                folders.items = listOf(null) + list
                updateRecordInFolderLabel()
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
                    recordings.submitList(
                        RecordingAdapter.sorted(items, sortKey, sortAsc)
                    )
                    b.emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    private fun selectFolder(id: Long?) {
        currentFolderId = id
        currentFolderFlow.value = id
        updateRecordInFolderLabel()
        folders.notifyDataSetChanged()
    }

    /** Indicação PERSISTENTE e clara de qual pasta vai receber a nova gravação. */
    private fun updateRecordInFolderLabel() {
        val name = currentFolderId?.let { id -> lastFolderList.firstOrNull { it.id == id }?.name }
        b.recordInFolder.text = if (name != null) "📂 Gravando em: $name" else "🏠 Gravando em: Raiz"
    }

    private fun chooseSort() {
        val labels = arrayOf("Mais recentes", "Mais antigas", "Nome (A–Z)", "Nome (Z–A)",
            "Maior tamanho", "Menor tamanho", "Maior duração", "Menor duração")
        android.app.AlertDialog.Builder(this)
            .setTitle("Ordenar gravações")
            .setItems(labels) { _, which ->
                sortKey = when (which) {
                    0, 1 -> "date"; 2, 3 -> "name"; 4, 5 -> "size"; else -> "duration"
                }
                sortAsc = which % 2 == 1 || which in 2..3
                b.btnSort.text = "Ordenar: " + labels[which]
                prefs.edit().putString("sortKey", sortKey).putBoolean("sortAsc", sortAsc).apply()
                currentFolderFlow.value = currentFolderFlow.value // re-emite p/ reordenar
            }
            .show()
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
                    folders.notifyDataSetChanged()
                    toast("Pasta excluída.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Menu de gravação ----------

    private fun showRecordingMenu(id: Long, title: String) {
        val options = arrayOf(
            "▶ Ouvir", "📤 Exportar (compartilhar app…)", "💾 Salvar no aparelho (Downloads)",
            "📂 Mover para pasta…", "✏ Renomear", "🗑 Excluir",
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openPlayer(id)
                    1 -> exportShare(id, title)
                    2 -> saveToDevice(id, title)
                    3 -> showMoveDialog(id)
                    4 -> promptText("Renomear", "Novo nome", title) { newName -> rename(id, newName) }
                    5 -> confirmDelete(id)
                }
            }
            .show()
    }

    // ---------- Exportação ----------

    private fun exportShare(id: Long, title: String) {
        lifecycleScope.launch {
            val r = db.recordingDao().byId(id) ?: return@launch
            val fmt = AudioFormat.fromName(r.format)
            val file = store.findExisting(id) ?: run { toast("Arquivo não encontrado."); return@launch }
            startActivity(Exporter.shareIntent(this@MainActivity, file, fmt.mime, title))
        }
    }

    private val askWrite = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast("Sem permissão de armazenamento — não foi possível salvar.")
        else pendingSave?.let { (id, title) -> doSaveToDevice(id, title) }
        pendingSave = null
    }
    private var pendingSave: Pair<Long, String>? = null

    private fun saveToDevice(id: Long, title: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            pendingSave = id to title
            askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doSaveToDevice(id, title)
    }

    private fun doSaveToDevice(id: Long, title: String) {
        lifecycleScope.launch {
            val r = db.recordingDao().byId(id) ?: return@launch
            val fmt = AudioFormat.fromName(r.format)
            val file = store.findExisting(id) ?: run { toast("Arquivo não encontrado."); return@launch }
            val safeName = title.replace(Regex("[^A-Za-z0-9 _-.]"), "_") + "." + fmt.ext
            val uri = Exporter.saveToDevice(this@MainActivity, file, safeName, fmt.mime)
            if (uri != null) toast("Salvo em Download/VoiceVault ✅\nAbra o gerenciador de arquivos ou a app Música/Downloads.")
            else toast("Falha ao salvar no aparelho.")
        }
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
        b.btnFormat.text = "Formato: ${chosenFormat.name}"
    }

    private fun updateQualityLabel() {
        b.qualityValue.text = chosenQuality.label
    }

    private fun chooseQuality() {
        val labels = AudioQuality.entries.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Qualidade de gravação")
            .setSingleChoiceItems(labels, AudioQuality.entries.indexOf(chosenQuality)) { d, which ->
                chosenQuality = AudioQuality.entries[which]
                prefs.edit().putString("quality", chosenQuality.name).apply()
                updateQualityLabel()
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Gravação via serviço em segundo plano ----------

    private fun toggleRecording() {
        if (isRecordingActive()) stopServiceRecording() else maybeStartRecording()
    }

    private fun isRecordingActive(): Boolean = LiveSession.active

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
        // Arquivo temporario UNICO por sessao (timestamp+UUID) - nunca reutiliza rec_tmp fixo.
        val dir = File(cacheDir, "pending").apply { mkdirs() }
        val tmp = File(dir, "rec_" + System.currentTimeMillis() + "_" +
            java.util.UUID.randomUUID() + "." + chosenFormat.ext)
        tmpFile = tmp
        sessionFormat = chosenFormat
        recordStartMs = System.currentTimeMillis()

        val intent = Intent(this, RecorderService::class.java).apply {
            putExtra(RecorderService.EXTRA_OUTPUT, tmp.absolutePath)
            putExtra(RecorderService.EXTRA_FORMAT, chosenFormat.name)
            putExtra(RecorderService.EXTRA_QUALITY, chosenQuality.name)
            putExtra(RecorderService.EXTRA_FOLDER, currentFolderId ?: -1L)
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
        // O PROPRIO servico finaliza e salva no banco - a Activity so reseta a UI.
        updateRecordingUi(false)
    }

    private fun updateRecordingUi(recording: Boolean) {
        b.btnRecord.text = if (recording) "⏹ Parar" else "● Gravar"
        b.recDot.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        b.statusLabel.text = if (recording) "Gravando…" else ""
        b.btnPauseResume.isEnabled = recording
        b.btnMarker.isEnabled = recording
        b.wave.setActive(recording)
        if (recording) startWave() else stopWave()
        timer?.cancel()
        if (recording) {
            timer = object : CountDownTimer(Long.MAX_VALUE, 500) {
                override fun onTick(ms: Long) {
                    val elapsed = System.currentTimeMillis() - LiveSession.startedAt - LiveSession.pausedTotalMs
                    b.timerLabel.text = formatHMS(max(0, elapsed))
                }
                override fun onFinish() {}
            }.start()
        } else {
            b.timerLabel.text = "00:00:00"
        }
    }

    private fun formatHMS(ms: Long): String {
        val t = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    }

    // ---- forma de onda ----
    private val micLevel = MicLevel { level -> runOnUiThread { b.wave.push(level) } }
    private fun startWave() = micLevel.start()
    private fun stopWave() = micLevel.stop()

    // ---- pausar / retomar e marcadores ----
    private var pausedTotalMs = 0L
    private var pauseStartedAt = 0L

    private fun togglePauseResume() {
        val action = if (LiveSession.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE
        startService(Intent(this, RecorderService::class.java).setAction(action))
        if (LiveSession.paused) { b.btnPauseResume.text = "⏸ Pausar"; updateStatusPaused(false) }
        else { b.btnPauseResume.text = "▶ Retomar"; updateStatusPaused(true) }
    }

    private fun updateStatusPaused(paused: Boolean) {
        b.statusLabel.text = if (paused) "Pausado" else "Gravando…"
        if (paused) stopWave() else startWave()
    }

    private fun addMarker() {
        val elapsed = System.currentTimeMillis() - LiveSession.startedAt - LiveSession.pausedTotalMs
        markers.add(formatHMS(max(0, elapsed)))
        toast("Marcador em ${formatHMS(max(0, elapsed))}")
    }
    private val markers = mutableListOf<String>()

    /** Sincroniza a UI quando voltamos ao app com gravacao em andamento (ex.: tela apagada). */
    private fun refreshRecordingState() {
        if (LiveSession.active) {
            recordStartMs = LiveSession.startedAt
            sessionFormat = AudioFormat.fromName(LiveSession.format)
            pausedTotalMs = LiveSession.pausedTotalMs
            updateRecordingUi(true)
            if (LiveSession.paused) { b.btnPauseResume.text = "▶ Retomar"; updateStatusPaused(true) }
            else { b.btnPauseResume.text = "⏸ Pausar" }
        } else {
            updateRecordingUi(false)
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
        micLevel.stop()
    }
}
