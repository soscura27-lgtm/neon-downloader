package com.deniscerri.ytdl.ui.explore

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.deniscerri.ytdl.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup

/**
 * Hoja con todas las opciones de descarga de lo que se está viendo en Explorar. Se abre desde el botón de la
 * esquina de la barra superior, así nada tapa el vídeo ni los botones de YouTube (Shorts, me gusta, comentar...).
 *
 * Arriba: qué se está viendo y el formato (vídeo, audio MP3, audio original). Después la acción principal
 * (botón grande) y debajo el resto: escuchar, elegir vídeos, comentarios con GIF, miniatura, imágenes...
 */
object ExploreActionsSheet {

    class Action(
        val iconRes: Int,
        val title: String,
        val subtitle: String? = null,
        val run: () -> Unit
    )

    class Content(
        val title: String,
        val subtitle: String?,
        /** Hay algo de vídeo o audio que descargar: se enseña la elección de formato. */
        val showFormat: Boolean,
        /** Texto del botón grande según el formato elegido (null = no hay acción principal). */
        val primaryLabel: ((DownloadKind) -> String)?,
        val primaryIcon: Int = R.drawable.baseline_download_24,
        val onPrimary: (() -> Unit)?,
        /** Aviso bajo el botón (servicio que solo se puede ver, página sin nada que descargar...). */
        val note: String?,
        val actions: List<Action>
    )

    fun show(
        context: Context,
        content: Content,
        kind: DownloadKind,
        onKindChanged: (DownloadKind) -> Unit
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_explore_actions, null)

        view.findViewById<TextView>(R.id.actions_title).text = content.title
        view.findViewById<TextView>(R.id.actions_subtitle).apply {
            text = content.subtitle.orEmpty()
            isVisible = !content.subtitle.isNullOrBlank()
        }

        // formato
        val formatLabel = view.findViewById<View>(R.id.actions_format_label)
        val formats = view.findViewById<ChipGroup>(R.id.actions_format)
        formatLabel.isVisible = content.showFormat
        formats.isVisible = content.showFormat
        val chipIds = mapOf(
            DownloadKind.VIDEO to R.id.actions_format_video,
            DownloadKind.AUDIO_MP3 to R.id.actions_format_mp3,
            DownloadKind.AUDIO_ORIGINAL to R.id.actions_format_audio
        )
        var current = kind
        formats.check(chipIds.getValue(kind))

        // acción principal
        val primary = view.findViewById<MaterialButton>(R.id.actions_primary)
        val label = content.primaryLabel
        val onPrimary = content.onPrimary
        if (label != null && onPrimary != null) {
            primary.isVisible = true
            primary.text = label(current)
            primary.setIconResource(content.primaryIcon)
            primary.setOnClickListener {
                dialog.dismiss()
                onPrimary()
            }
        } else {
            primary.isVisible = false
        }

        formats.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chosen = chipIds.entries.firstOrNull { it.value == id }?.key ?: return@setOnCheckedStateChangeListener
            if (chosen == current) return@setOnCheckedStateChangeListener
            current = chosen
            onKindChanged(chosen)
            if (label != null) primary.text = label(chosen)
        }

        view.findViewById<TextView>(R.id.actions_note).apply {
            text = content.note.orEmpty()
            isVisible = !content.note.isNullOrBlank()
        }

        // resto de acciones
        val list = view.findViewById<LinearLayout>(R.id.actions_list)
        val inflater = LayoutInflater.from(context)
        content.actions.forEach { action ->
            val row = inflater.inflate(R.layout.item_explore_action, list, false)
            row.findViewById<ImageView>(R.id.action_icon).setImageResource(action.iconRes)
            row.findViewById<TextView>(R.id.action_title).text = action.title
            row.findViewById<TextView>(R.id.action_subtitle).apply {
                text = action.subtitle.orEmpty()
                isVisible = !action.subtitle.isNullOrBlank()
            }
            row.setOnClickListener {
                dialog.dismiss()
                action.run()
            }
            list.addView(row)
        }
        view.findViewById<View>(R.id.actions_divider).isVisible =
            content.actions.isNotEmpty() && (primary.isVisible || content.showFormat || !content.note.isNullOrBlank())

        dialog.setContentView(view)
        dialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
        return dialog
    }
}
