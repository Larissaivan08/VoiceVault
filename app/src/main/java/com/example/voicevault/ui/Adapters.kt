package com.example.voicevault.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.voicevault.databinding.ItemRecordingBinding
import com.example.voicevault.db.FolderCount
import com.example.voicevault.db.RecordingWithFolderName
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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
            val where = if (item.folderName != null) "📁 ${item.folderName}  •  " else ""
            b.subtitle.text = "%s%s  •  %s  •  %s  •  %s".format(
                where,
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
