package com.deniscerri.ytdl.ui.explore

import android.os.Bundle
import androidx.lifecycle.ViewModel

/**
 * Guarda el estado del navegador de Explorar mientras se cambia de pestaña o se gira la pantalla
 * (el WebView se destruye con la vista, pero la página y su historial se restauran desde aquí).
 */
class ExploreViewModel : ViewModel() {
    var webState: Bundle? = null
    var desktopMode: Boolean = false
}
