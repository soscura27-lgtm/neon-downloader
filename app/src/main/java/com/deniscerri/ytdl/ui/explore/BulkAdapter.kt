package com.deniscerri.ytdl.ui.explore

import android.icu.text.CompactDecimalFormat
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.ResultItem
import com.google.android.material.card.MaterialCardView
import com.squareup.picasso.Picasso
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lista del selector masivo. Reutiliza la tarjeta de la selección de playlist (playlist_item) y
 * muestra el número de posición, para poder elegir rangos ("del 20 al 80").
 */
class BulkAdapter(
    private val hideThumbnails: Boolean,
    private val onToggle: (ResultItem) -> Unit
) : ListAdapter<ResultItem, BulkAdapter.ViewHolder>(DIFF) {

    /** Ids seleccionados (la pantalla lo actualiza y llama a [refreshSelection]). */
    var selected: Set<Long> = emptySet()

    private val compactNumbers: CompactDecimalFormat by lazy {
        CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
    }
    private val monthFormat: SimpleDateFormat by lazy {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMM"), Locale.getDefault())
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.playlist_card)
        val thumbnail: ImageView = view.findViewById(R.id.downloads_image_view)
        val index: TextView = view.findViewById(R.id.index)
        val duration: TextView = view.findViewById(R.id.duration)
        val title: TextView = view.findViewById(R.id.title)
        val author: TextView = view.findViewById(R.id.author)
        val check: CheckBox = view.findViewById(R.id.checkBox)

        init {
            // el toque lo gestiona toda la tarjeta; la casilla solo muestra el estado
            check.isClickable = false
            check.isFocusable = false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.playlist_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        holder.title.text = item.title.ifBlank { item.url }
        holder.author.text = details(holder.itemView, item)

        val showDuration = item.duration.isNotBlank() && item.duration != "-1"
        holder.duration.visibility = if (showDuration) View.VISIBLE else View.GONE
        holder.duration.text = item.duration

        if (hideThumbnails || item.thumb.isBlank()) {
            Picasso.get().cancelRequest(holder.thumbnail)
            holder.thumbnail.setImageDrawable(null)
        } else {
            Picasso.get().load(item.thumb).resize(480, 0).onlyScaleDown().into(holder.thumbnail)
        }

        bindState(holder, item, position)
        holder.card.setOnClickListener { onToggle(item) }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        // solo cambió el número de posición o la selección
        bindState(holder, getItem(position), position)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        Picasso.get().cancelRequest(holder.thumbnail)
        super.onViewRecycled(holder)
    }

    private fun bindState(holder: ViewHolder, item: ResultItem, position: Int) {
        holder.index.text = (position + 1).toString()
        holder.check.isChecked = selected.contains(item.id)
    }

    /** Vuelve a pintar la casilla y el número de posición de las filas visibles. */
    fun refreshSelection() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)
    }

    private fun details(view: View, item: ResultItem): String {
        val parts = ArrayList<String>(3)
        if (item.author.isNotBlank()) parts.add(item.author)
        if (item.viewCount >= 0) {
            parts.add(view.context.getString(R.string.bulk_views, compactNumbers.format(item.viewCount)))
        }
        if (item.uploadTimestamp > 0) {
            parts.add(monthFormat.format(Date(item.uploadTimestamp * 1000)))
        }
        return parts.joinToString(" · ")
    }

    companion object {
        private const val PAYLOAD_STATE = "state"

        private val DIFF = object : DiffUtil.ItemCallback<ResultItem>() {
            override fun areItemsTheSame(oldItem: ResultItem, newItem: ResultItem) = oldItem.id == newItem.id

            // los vídeos leídos no cambian: el mismo id es el mismo contenido
            override fun areContentsTheSame(oldItem: ResultItem, newItem: ResultItem) = oldItem.id == newItem.id
        }
    }
}
