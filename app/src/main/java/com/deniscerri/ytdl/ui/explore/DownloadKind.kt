package com.deniscerri.ytdl.ui.explore

import android.content.Context
import android.content.SharedPreferences
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Qué se descarga desde Explorar y desde el selector masivo: vídeo, audio MP3 o audio original.
 *
 * La elección se recuerda y es la misma en las dos pantallas. Vale para un vídeo suelto, una
 * playlist, un Mix o una selección masiva.
 */
enum class DownloadKind(
    val key: String,
    val type: DownloadType,
    /** Formato de audio forzado: "mp3", "" = el original de YouTube sin convertir, null = no se toca. */
    val container: String?,
    val labelRes: Int,
    val optionRes: Int,
    val iconRes: Int
) {
    VIDEO("video", DownloadType.video, null, R.string.kind_video, R.string.kind_video_option, R.drawable.ic_video),
    AUDIO_MP3("mp3", DownloadType.audio, "mp3", R.string.kind_mp3, R.string.kind_mp3_option, R.drawable.ic_music),
    AUDIO_ORIGINAL("audio", DownloadType.audio, "", R.string.kind_audio, R.string.kind_audio_option, R.drawable.ic_music);

    val isAudio: Boolean
        get() = type == DownloadType.audio

    companion object {
        const val PREF_KEY = "explore_download_kind"

        fun from(key: String?): DownloadKind = values().firstOrNull { it.key == key } ?: VIDEO

        fun load(prefs: SharedPreferences): DownloadKind = from(prefs.getString(PREF_KEY, null))

        fun save(prefs: SharedPreferences, kind: DownloadKind) {
            prefs.edit().putString(PREF_KEY, kind.key).apply()
        }

        /** Texto del botón: "Formato: Audio MP3". */
        fun chipLabel(context: Context, kind: DownloadKind): String {
            return context.getString(R.string.kind_chip, context.getString(kind.labelRes))
        }

        /** Pregunta en qué formato descargar. Si el usuario cierra la ventana, no pasa nada. */
        fun choose(context: Context, current: DownloadKind, onChosen: (DownloadKind) -> Unit) {
            val kinds = values()
            val labels = kinds.map { context.getString(it.optionRes) }.toTypedArray()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.kind_dialog_title)
                .setSingleChoiceItems(labels, kinds.indexOf(current)) { dialog, which ->
                    dialog.dismiss()
                    onChosen(kinds[which])
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
