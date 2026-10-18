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
import androidx.appcompat.app.AppCompatDelegate
import android.widget.GridLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.example.voicevault.ui.RecordingAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
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

    private lateinit var recordings: RecordingAdapter

    private var chosenFormat = AudioFormat.M4A
    private var chosenQuality = AudioQuality.HIGH

    // ---- Gravação (via RecorderService — funciona em segundo plano) ----
    private var timer: CountDownTimer? = null
    private var recordStartMs = 0L
    private var pendingFolder: Long? = null
    private var pendingFormat = AudioFormat.M4A
    private var pendingQuality = AudioQuality.HIGH
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

        val currentTheme = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        if (AppCompatDelegate.getDefaultNightMode() != currentTheme) AppCompatDelegate.setDefaultNightMode(currentTheme)
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
        b.btnPauseResume.setOnClickListener { togglePauseResume() }
        b.btnMarker.setOnClickListener { addMarker() }
        b.btnQuality.setOnClickListener { chooseQuality() }
        b.btnAddFolder.setOnClickListener { promptText(
            title = "Nova pasta", hint = "Nome da pasta", prefill = "",
        ) { name -> createFolder(name) } }
        b.btnFormat.setOnClickListener { chooseFormat() }
        b.btnTheme.setOnClickListener { chooseTheme() }
    }

    private fun observeData() {
        // Painel lateral de pastas (Raiz + pastas com contagem)
        lifecycleScope.launch {
            db.folderDao().flowWithCount().collect { list -> rebuildFolderChips(list) }
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

    /** Folder cards, two columns, with long-press management. */
    private fun rebuildFolderChips(folders: List<FolderCount>) {
        val grid = b.folderGrid
        grid.removeAllViews()
        val items = listOf(FolderCount(-1L, "Raiz", "#DB797E", 0L, 0)) + folders
        val palette = intArrayOf(0xFFDB797E.toInt(), 0xFF459FE1.toInt(),
            0xFFAE72DE.toInt(), 0xFF46B99B.toInt())
        items.forEachIndexed { index, folder ->
            val isRoot = folder.id == -1L
            val id = if (isRoot) null else folder.id
            val selected = currentFolderId == id
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 18f * resources.displayMetrics.density
                cardElevation = if (selected) 7f else 2f
                setCardBackgroundColor(
                    if (selected) androidx.core.graphics.ColorUtils.setAlphaComponent(Color.parseColor(folder.colorHex), 55)
                    else com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorSurfaceContainer, 0)
                )
                strokeWidth = if (selected) (2 * resources.displayMetrics.density).toInt() else 0
                strokeColor = Color.parseColor(folder.colorHex)
                isClickable = true
                setOnClickListener { selectFolder(id); rebuildFolderChips(folders) }
                if (!isRoot) setOnLongClickListener { showFolderMenu(folder); true }
            }
            val content = TextView(this).apply {
                text = "📁  ${folder.name}\n      ${if (isRoot) "Sem pasta" else "${folder.recordingCount} áudios"}"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(16.dp(), 20.dp(), 8.dp(), 20.dp())
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
            }
            card.addView(content)
            val lp = GridLayout.LayoutParams(GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f))
            lp.width = 0
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT
            lp.setMargins(5.dp(), 5.dp(), 5.dp(), 5.dp())
            grid.addView(card, lp)
        }
    }
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun chooseTheme() {
        val modes = intArrayOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
        val names = arrayOf("Automático (sistema)", "Claro", "Escuro")
        val current = modes.indexOf(AppCompatDelegate.getDefaultNightMode()).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Aparência")
            .setSingleChoiceItems(names, current) { dialog, which ->
                prefs.edit().putInt("theme_mode", modes[which]).apply()
                AppCompatDelegate.setDefaultNightMode(modes[which])
                dialog.dismiss()
            }.show()
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
        pausedTotalMs = 0L
        pauseStartedAt = 0L
        markers.clear()
        pendingFolder = currentFolderId
        pendingFormat = chosenFormat
        pendingQuality = chosenQuality
        val tmp = File(cacheDir, "rec_tmp_${UUID.randomUUID()}.${pendingFormat.ext}")
        tmpFile = tmp
        recordStartMs = System.currentTimeMillis()

        val intent = Intent(this, RecorderService::class.java).apply {
            putExtra(RecorderService.EXTRA_OUTPUT, tmp.absolutePath)
            putExtra(RecorderService.EXTRA_FORMAT, pendingFormat.name)
            putExtra(RecorderService.EXTRA_QUALITY, pendingQuality.name)
            putExtra(RecorderService.EXTRA_HAS_FOLDER, pendingFolder != null)
            pendingFolder?.let { putExtra(RecorderService.EXTRA_FOLDER, it) }
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
        b.btnRecord.text = if (recording) "■" else "🎙"
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
                    val elapsed = System.currentTimeMillis() - recordStartMs - pausedTotalMs
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
    private fun startWave() = b.wave.setActive(true)
    private fun stopWave() = b.wave.setActive(false)

    // ---- pausar / retomar e marcadores ----
    private var pausedTotalMs = 0L
    private var pauseStartedAt = 0L

    private fun togglePauseResume() {
        val st = LastRecord.result as? LastRecord.Recording ?: return
        val action = if (st.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE
        startService(Intent(this, RecorderService::class.java).setAction(action))
        if (st.paused) { pausedTotalMs += System.currentTimeMillis() - pauseStartedAt
            b.btnPauseResume.text = "⏸ Pausar"; updateStatusPaused(false) }
        else { pauseStartedAt = System.currentTimeMillis()
            b.btnPauseResume.text = "▶ Retomar"; updateStatusPaused(true) }
    }

    private fun updateStatusPaused(paused: Boolean) {
        b.statusLabel.text = if (paused) "Pausado" else "Gravando…"
        if (paused) stopWave() else startWave()
    }

    private fun addMarker() {
        val elapsed = System.currentTimeMillis() - recordStartMs - pausedTotalMs
        markers.add(formatHMS(max(0, elapsed)))
        toast("Marcador em ${formatHMS(max(0, elapsed))}")
    }
    private val markers = mutableListOf<String>()

    /** Sincroniza a UI quando voltamos ao app com gravação em andamento (ex.: tela apagada). */
    private fun syncRecordingState() {
        val st = LastRecord.result
        if (st is LastRecord.Recording) {
            recordStartMs = st.startedAt
            pendingFormat = AudioFormat.fromName(st.format)
            updateRecordingUi(true)
            if (st.paused) { b.btnPauseResume.text = "▶ Retomar"; updateStatusPaused(true) }
            else { b.btnPauseResume.text = "⏸ Pausar" }
        } else if (!isRecordingActive()) {
            updateRecordingUi(false)
        }
    }

    /** The foreground service is responsible for persisting completed recordings. */
    private fun checkStoppedRecord() {
        when (val state = LastRecord.result) {
            is LastRecord.Fail -> {
                LastRecord.result = null
                toast(state.message)
                syncRecordingState()
            }
            is LastRecord.Stopped -> {
                LastRecord.result = null
                toast("Gravação finalizada e salva.")
                syncRecordingState()
            }
            else -> Unit
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
