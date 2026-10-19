package com.example.voicevault.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import com.example.voicevault.R
import com.example.voicevault.databinding.ItemRecordingBinding
import com.example.voicevault.databinding.ItemFolderBinding
import com.example.voicevault.db.FolderCount
import com.example.voicevault.db.RecordingWithFolderName
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Grade 2 colunas de pastas (Raiz incluída), independente da lista de gravações. */
class FolderAdapter(
    private val onClick: (Long?) -> Unit,
    private val onLongClick: (FolderCount) -> Unit,
    private val isSelected: (Long?) -> Boolean,
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    var items: List<FolderCount?> = emptyList() // null = Raiz (primeiro item sempre)
        set(v) { field = v; notifyDataSetChanged() }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: FolderCount?) {
            if (item == null) {
                b.folderName.text = "🏠 Raiz"
                b.folderCount.text = ""
            } else {
                b.folderName.text = "📁 " + item.name
                b.folderCount.text = plural(item.recordingCount)
            }
            val sel = isSelected(item?.id)
            b.card.strokeWidth = if (sel) 6 else 0
            b.card.strokeColor = androidx.core.content.ContextCompat.getColor(
                b.root.context, if (sel) R.color.brand_red else android.R.color.darker_gray
            )
            b.card.alpha = if (sel) 1f else 0.85f
            b.root.setOnClickListener { onClick(item?.id) }
            b.root.setOnLongClickListener { if (item != null) { onLongClick(item); true } else false }
        }

        private fun plural(n: Int) = if (n == 1) "1 gravação" else "$n gravações"
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

        /** Compara conforme o critério de ordenação escolhido pelo usuário. */
        fun sorted(list: List<RecordingWithFolderName>, by: String, asc: Boolean): List<RecordingWithFolderName> {
            val cmp: Comparator<RecordingWithFolderName> = when (by) {
                "name" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                "size" -> compareBy { it.sizeBytes }
                "duration" -> compareBy { it.durationMs }
                else -> compareBy { it.createdAt }
            }
            val out = list.sortedWith(if (asc) cmp else cmp.reversed())
            return if (by == "name") out else out
        }

        private val DIFF = object : DiffUtil.ItemCallback<RecordingWithFolderName>() {
            override fun areItemsTheSame(a: RecordingWithFolderName, b: RecordingWithFolderName) =
                a.id == b.id
            override fun areContentsTheSame(a: RecordingWithFolderName, b: RecordingWithFolderName) =
                a == b
        }
    }
}
