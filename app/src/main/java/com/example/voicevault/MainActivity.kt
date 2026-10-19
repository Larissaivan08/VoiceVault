package com.example.voicevault

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
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
import android.graphics.BitmapFactory
import java.io.FileOutputStream
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val selectProfilePhoto = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val target = File(filesDir, "profile_avatar.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: error("Não foi possível abrir a imagem")
                loadProfile()
            } catch (e: Exception) {
                toast("Não foi possível salvar a foto.")
            }
        }
    }

    private fun loadProfile() {
        b.profileName.text = prefs.getString("profile_name", "usuario") ?: "usuario"
        val photo = File(filesDir, "profile_avatar.jpg")
        if (photo.isFile) {
            val bitmap = BitmapFactory.decodeFile(photo.absolutePath)
            if (bitmap != null) b.profilePhoto.setImageBitmap(bitmap)
        }
    }

    private fun editProfileName() {
        promptText("Nome de usuário", "Seu nome", b.profileName.text.toString()) { name ->
            prefs.edit().putString("profile_name", name).apply()
            b.profileName.text = name
        }
    }

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
        loadProfile()
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
        b.profilePhoto.setOnClickListener { selectProfilePhoto.launch("image/*") }
        b.profilePhotoEdit.setOnClickListener { selectProfilePhoto.launch("image/*") }
        b.profileName.setOnClickListener { editProfileName() }
        b.btnRecord.setOnClickListener { toggleRecording() }
        b.btnPauseResume.setOnClickListener { togglePauseResume() }
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

    private fun rebuildFolderChips(folders: List<FolderCount>) {
        val grid = b.folderGrid
        grid.removeAllViews()
        val items = listOf(FolderCount(-1L, "Raiz", "#DB797E", 0L, 0)) + folders
        items.forEachIndexed { index, folder ->
            val folderId = if (folder.id == -1L) null else folder.id
            val selected = currentFolderId == folderId
            val color = Color.parseColor(folder.colorHex)
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(16.dp(), 18.dp(), 12.dp(), 18.dp())
                minimumHeight = 84.dp()
                background = GradientDrawable().apply {
                    cornerRadius = 18.dp().toFloat()
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, if (selected) 58 else 28))
                    setStroke(if (selected) 2.dp() else 1.dp(),
                        androidx.core.graphics.ColorUtils.setAlphaComponent(color, if (selected) 255 else 70))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { selectFolder(folderId); rebuildFolderChips(folders) }
                if (folderId != null) setOnLongClickListener { showFolderMenu(folder); true }
            }
            val foregroundColor = com.google.android.material.color.MaterialColors.getColor(
                card, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            card.addView(TextView(this).apply {
                text = folder.name
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(foregroundColor)
                background = null
                includeFontPadding = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(TextView(this).apply {
                text = if (folderId == null) "Sem pasta" else if (folder.recordingCount == 1) "1 áudio" else "${folder.recordingCount} áudios"
                textSize = 13f
                setTextColor(foregroundColor)
                alpha = 0.75f
                background = null
                includeFontPadding = false
                setPadding(0, 6.dp(), 0, 0)
            })
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f)).apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(5.dp(), 5.dp(), 5.dp(), 5.dp())
            }
            grid.addView(card, params)
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
            chooseFolderColor(id)
        }
    }

    private fun openPlayer(id: Long) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_ID, id))
    }

    // ---------- Pastas: renomear / excluir ----------

    private fun showFolderMenu(folder: FolderCount) {
        val options = arrayOf("✏ Renomear pasta", "🎨 Alterar cor", "🗑 Excluir pasta")
        android.app.AlertDialog.Builder(this)
            .setTitle("📁 ${folder.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptText("Renomear pasta", "Novo nome", folder.name) { newName ->
                        renameFolder(folder.id, newName)
                    }
                    1 -> chooseFolderColor(folder.id)
                    2 -> confirmDeleteFolder(folder)
                }
            }
            .show()
    }

    private fun chooseFolderColor(id: Long) {
        val colors = arrayOf("#DB797E", "#459FE1", "#AE72DE", "#46B99B", "#E8B34F", "#E56BB0")
        val names = arrayOf("Coral", "Azul", "Roxo", "Verde", "Amarelo", "Rosa")
        android.app.AlertDialog.Builder(this).setTitle("Cor da pasta")
            .setItems(names) { _, choice ->
                lifecycleScope.launch {
                    val folder = db.folderDao().byId(id)
                    if (folder != null) db.folderDao().update(folder.copy(colorHex = colors[choice]))
                }
            }.show()
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
            try {
                val result = withContext(Dispatchers.IO) {
                    val recording = db.recordingDao().byId(id)
                        ?: return@withContext "Gravação não encontrada."
                    val fmt = AudioFormat.fromName(recording.format)
                    val source = store.findExisting(id)
                        ?: return@withContext "Arquivo de áudio não encontrado."
                    val safeBase = title.replace(Regex("[^A-Za-z0-9 _.-]"), "_")
                        .trim().ifEmpty { "Gravacao_$id" }.take(90)
                    val name = "$safeBase.${fmt.ext}"
                    if (Exporter.saveToDevice(applicationContext, source, name, fmt.mime) == null)
                        "Falha ao exportar. Confira o espaço livre e as permissões."
                    else null
                }
                if (result == null)
                    toast("Áudio salvo em Downloads/Gravador Smart.")
                else toast(result)
            } catch (e: Exception) {
                toast("Não foi possível salvar em Downloads.")
            }
        }
    }

    private fun showMoveDialog(recordingId: Long) {
        lifecycleScope.launch {
            val recording = db.recordingDao().byId(recordingId)
            if (recording == null) {
                toast("Gravação não encontrada.")
                return@launch
            }
            val destinations = listOf<Pair<String, Long?>>("Raiz (sem pasta)" to null) +
                db.folderDao().listWithCount().map { it.name to it.id }
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Mover para qual pasta?")
                .setSingleChoiceItems(
                    destinations.map { it.first }.toTypedArray(),
                    destinations.indexOfFirst { it.second == recording.folderId }
                ) { dialog, selected ->
                    dialog.dismiss()
                    val target = destinations[selected]
                    lifecycleScope.launch {
                        try {
                            val changed = db.recordingDao().moveToFolder(recordingId, target.second)
                            if (changed == 0) {
                                toast("Não foi possível mover: gravação não encontrada.")
                            } else {
                                selectFolder(target.second)
                                queryFlow.value = null
                                b.search.setText("")
                                val currentFolders = db.folderDao().listWithCount()
                                rebuildFolderChips(currentFolders)
                                toast("Áudio movido para ${target.first}.")
                            }
                        } catch (e: Exception) {
                            toast("Erro ao mover o áudio. Tente novamente.")
                        }
                    }
                }.setNegativeButton("Cancelar", null).show()
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
        val state = LastRecord.result as? LastRecord.Recording
        b.btnRecord.text = if (recording) "■" else "🎙"
        b.recDot.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        b.statusLabel.text = if (state?.paused == true) "Pausado" else if (recording) "Gravando…" else ""
        b.btnPauseResume.isEnabled = recording
        b.btnPauseResume.text = if (state?.paused == true) "Retomar" else "Pausar"
        b.wave.setActive(recording)
        timer?.cancel()
        if (recording) {
            timer = object : CountDownTimer(Long.MAX_VALUE, 250) {
                override fun onTick(ms: Long) { refreshRecordingTimer() }
                override fun onFinish() {}
            }.start()
            refreshRecordingTimer()
        } else {
            b.timerLabel.text = "00:00:00"
        }
    }

    private fun elapsedRecordingMs(st: LastRecord.Recording): Long {
        val now = SystemClock.elapsedRealtime()
        val pausedNow = if (st.paused && st.pauseStartedElapsedMs > 0L)
            now - st.pauseStartedElapsedMs else 0L
        return (now - st.startedElapsedMs - st.pausedElapsedMs - pausedNow).coerceAtLeast(0L)
    }

    private fun refreshRecordingTimer() {
        val st = LastRecord.result as? LastRecord.Recording ?: return
        b.timerLabel.text = formatHMS(elapsedRecordingMs(st))
        val label = if (st.paused) "Retomar" else "Pausar"
        if (b.btnPauseResume.text.toString() != label) b.btnPauseResume.text = label
        b.statusLabel.text = if (st.paused) "Pausado" else "Gravando…"
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
        val state = LastRecord.result as? LastRecord.Recording ?: return
        val action = if (state.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE
        startService(Intent(this, RecorderService::class.java).setAction(action))
        // UI reads the service-confirmed state on the next timer tick.
    }

    /** Sincroniza a UI quando voltamos ao app com gravação em andamento (ex.: tela apagada). */
    private fun syncRecordingState() {
        val st = LastRecord.result
        if (st is LastRecord.Recording) {
            recordStartMs = st.startedAt
            pendingFormat = AudioFormat.fromName(st.format)
            updateRecordingUi(true)
            if (st.paused) { b.btnPauseResume.text = "Retomar"; b.statusLabel.text = "Pausado" }
            else { b.btnPauseResume.text = "Pausar" }
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
