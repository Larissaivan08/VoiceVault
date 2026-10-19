package com.example.voicevault

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicevault.databinding.ActivityPlayerBinding
import com.example.voicevault.db.AppDb
import com.example.voicevault.db.AudioFormat
import com.example.voicevault.io.Exporter
import com.example.voicevault.io.AudioStore
import com.example.voicevault.ui.RecordingAdapter
import kotlinx.coroutines.launch

/** Tela de reprodução: play/pause, seek, renomear, mover e excluir. */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "recording_id"
    }

    private lateinit var b: ActivityPlayerBinding
    private val db by lazy { AppDb.get(this) }
    private val store by lazy { AudioStore(this) }

    private var player: MediaPlayer? = null
    private var recordingId = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var updater: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        recordingId = intent.getLongExtra(EXTRA_ID, -1)
        if (recordingId <= 0) { finish(); return }

        lifecycleScope.launch {
            val rec = db.recordingDao().byId(recordingId)
            if (rec == null) { finish(); return@launch }
            b.title.text = rec.title
            b.meta.text = "%s  •  %s  •  %s".format(
                RecordingAdapter.formatDuration(rec.durationMs),
                RecordingAdapter.formatSize(rec.sizeBytes),
                rec.format,
            )
            preparePlayer()
        }

        b.btnPlayPause.setOnClickListener { togglePlay() }
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(p)
                b.currentLabel.text = RecordingAdapter.formatDuration(p.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        b.btnDelete.setOnClickListener { confirmDelete() }
        b.btnShare.setOnClickListener { shareRecording() }
    }

    private fun preparePlayer() {
        val file = store.findExisting(recordingId)
        if (file == null || !file.exists()) { toast("Arquivo não encontrado."); finish(); return }
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { stopProgressUpdates(); resetUiToStart() }
            }
            b.seek.max = player?.duration ?: 0
        } catch (e: Exception) {
            releasePlayer()
            toast("Não foi possível reproduzir este áudio.")
            finish()
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            b.btnPlayPause.text = "▶"
            stopProgressUpdates()
        } else {
            p.start()
            b.btnPlayPause.text = "⏸"
            startProgressUpdates()
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        updater = object : Runnable {
            override fun run() {
                player?.let {
                    b.seek.progress = it.currentPosition
                    b.currentLabel.text = RecordingAdapter.formatDuration(it.currentPosition.toLong())
                }
                handler.postDelayed(this, 250)
            }
        }
        handler.post(updater!!)
    }

    private fun stopProgressUpdates() {
        updater?.let { handler.removeCallbacks(it) }
        updater = null
    }

    private fun resetUiToStart() {
        b.btnPlayPause.text = "▶"
        b.seek.progress = 0
        b.currentLabel.text = "00:00"
    }

    private fun shareRecording() {
        lifecycleScope.launch {
            val record = db.recordingDao().byId(recordingId) ?: return@launch
            val file = store.findExisting(recordingId)
            if (file == null) {
                toast("Arquivo não encontrado.")
                return@launch
            }
            val format = AudioFormat.fromName(record.format)
            startActivity(Exporter.shareIntent(this@PlayerActivity, file, format.mime, record.title))
        }
    }

    private fun confirmDelete() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Excluir gravação?")
            .setMessage("O arquivo de áudio será apagado permanentemente.")
            .setPositiveButton("Excluir") { _, _ ->
                releasePlayer()
                lifecycleScope.launch {
                    db.recordingDao().byId(recordingId)?.let { db.recordingDao().delete(it) }
                    store.delete(recordingId)
                    finish()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun releasePlayer() {
        stopProgressUpdates()
        runCatching { player?.release() }
        player = null
    }

    override fun onPause() {
        super.onPause()
        player?.let { if (it.isPlaying) { it.pause(); b.btnPlayPause.text = "▶"; stopProgressUpdates() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
