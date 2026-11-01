package com.deniscerri.ytdl.ui.more.settings.quality

import android.os.Bundle
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.ui.more.settings.BaseSettingsFragment
import com.deniscerri.ytdl.ui.more.settings.downloading.DownloadSettingsModule
import com.deniscerri.ytdl.ui.more.settings.processing.ProcessingSettingsModule

/**
 * Sección propia "Calidad y velocidad".
 *
 * Es una vista de acceso rápido sobre ajustes que ya existen en el proyecto
 * (calidad de video, bitrate de audio, fragmentos concurrentes, descargas
 * simultáneas, aria2c, límite de velocidad y tamaño de búfer). Comparte las claves
 * de SharedPreferences con las pantallas originales y reutiliza sus módulos de
 * lógica, así que nunca se desincroniza.
 */
class QualitySettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.quality_speed

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.quality_preferences, rootKey)

        getPreferences(preferenceScreen, mutableListOf()).forEach {
            DownloadSettingsModule.bindLogic(it, this)
            ProcessingSettingsModule.bindLogic(it, this)
        }
    }
}
