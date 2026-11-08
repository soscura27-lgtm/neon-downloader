package com.deniscerri.ytdl.ui.downloads

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.util.QueuePlacement
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Ventanas para elegir el lugar de la cola: "al final", "la siguiente" o "en la posición N".
 * Las usan Explorar, el selector masivo y la pantalla de la cola.
 */
object QueuePlacementDialog {

    /** Texto corto para el botón del selector masivo: "Cola: al final" / "Cola: la siguiente" / "Cola: posición 3". */
    fun chipLabel(context: Context, position: Int): String {
        return when {
            position <= QueuePlacement.END -> context.getString(R.string.queue_chip_end)
            position == QueuePlacement.NEXT -> context.getString(R.string.queue_chip_next)
            else -> context.getString(R.string.queue_chip_position, position)
        }
    }

    /**
     * Pregunta dónde poner lo que se va a descargar. Llama a [onChosen] con
     * [QueuePlacement.END], [QueuePlacement.NEXT] o la posición pedida (2, 3...).
     * Si el usuario cierra la ventana, no pasa nada.
     */
    fun choose(context: Context, current: Int, onChosen: (Int) -> Unit) {
        val labels = arrayOf(
            context.getString(R.string.queue_place_end),
            context.getString(R.string.queue_place_next),
            context.getString(R.string.queue_place_position)
        )
        val checked = when {
            current <= QueuePlacement.END -> 0
            current == QueuePlacement.NEXT -> 1
            else -> 2
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.queue_place_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> onChosen(QueuePlacement.END)
                    1 -> onChosen(QueuePlacement.NEXT)
                    else -> askPosition(
                        context,
                        R.string.queue_place_position_title,
                        if (current > QueuePlacement.NEXT) current else 3,
                        onChosen
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Pide un número de posición (1 = la siguiente). Solo llama a [onChosen] con un número válido. */
    fun askPosition(context: Context, titleRes: Int, initial: Int, onChosen: (Int) -> Unit) {
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_bulk_numbers, null)
        content.findViewById<TextInputLayout>(R.id.bulk_input_a_layout).hint =
            context.getString(R.string.queue_place_position_hint)
        content.findViewById<View>(R.id.bulk_input_b_layout).visibility = View.GONE
        content.findViewById<TextView>(R.id.bulk_input_note).apply {
            text = context.getString(R.string.queue_place_position_note)
            visibility = View.VISIBLE
        }
        val input = content.findViewById<TextInputEditText>(R.id.bulk_input_a)
        input.setText(initial.coerceAtLeast(1).toString())
        input.selectAll()

        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val position = input.text?.toString()?.trim()?.toIntOrNull()
                if (position != null && position >= 1) onChosen(position)
            }
            .show()
    }
}
