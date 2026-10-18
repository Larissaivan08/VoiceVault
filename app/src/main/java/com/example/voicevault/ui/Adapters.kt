package com.example.voicevault.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.voicevault.databinding.ItemFolderChipBinding
import com.example.voicevault.databinding.ItemRecordingBinding
import com.example.voicevault.db.FolderCount
import com.example.voicevault.db.RecordingWithFolderName
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Item da lista lateral de pastas (Raiz + pastas do usuário, um embaixo do outro). */
class FolderListAdapter(
    private val onClick: (Long?) -> Unit,          // null = Raiz
    private val onLongClick: (FolderCount) -> Unit, // menu: renomear/excluir
) : ListAdapter<Any, FolderListAdapter.VH>(DIFF) {

    var selectedId: Long? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFolderChipBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = currentList[position]
        val id: Long? = if (item is FolderCount) item.id else null
        holder.binding.chip.text = if (item is FolderCount) {
            "📁 ${item.name}  (${item.recordingCount})"
        } else {
            "🏠 Raiz"
        }
        holder.binding.chip.isChecked = selectedId == id
        holder.binding.chip.setOnClickListener { onClick(id) }
        if (item is FolderCount) {
            holder.binding.chip.setOnLongClickListener { onLongClick(item); true }
        } else {
            holder.binding.chip.setOnLongClickListener(null)
        }
    }

    class VH(val binding: ItemFolderChipBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(a: Any, b: Any) =
                a === b ||
                    (a is FolderCount && b is FolderCount && a.id == b.id) ||
                    (a is String && b is String)
            override fun areContentsTheSame(a: Any, b: Any) = a == b
        }
    }
}

/** Lista de gravações da pasta selecionada. */
class RecordingAdapter(
    private val onClick: (RecordingWithFolderName) -> Unit,
    private val onLongClick: (RecordingWithFolderName) -> Unit,
) : ListAdapter<RecordingWithFolderName, RecordingAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRecordingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemRecordingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: RecordingWithFolderName) {
            b.title.text = item.title
            b.subtitle.text = "%s  •  %s  •  %s  •  %s".format(
                formatDuration(item.durationMs),
                formatSize(item.sizeBytes),
                item.format,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(item.createdAt)),
            )
            b.root.setOnClickListener { onClick(item) }
            b.root.setOnLongClickListener { onLongClick(item); true }
        }
    }

    companion object {
        fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, (totalSec % 3600) / 60, totalSec % 60)
            else String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }

        private val DIFF = object : DiffUtil.ItemCallback<RecordingWithFolderName>() {
            override fun areItemsTheSame(a: RecordingWithFolderName, b: RecordingWithFolderName) =
                a.id == b.id
            override fun areContentsTheSame(a: RecordingWithFolderName, b: RecordingWithFolderName) =
                a == b
        }
    }
}
