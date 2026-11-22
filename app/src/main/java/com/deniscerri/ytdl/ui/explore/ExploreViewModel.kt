package com.deniscerri.ytdl.ui.explore

import android.os.Bundle
import androidx.lifecycle.ViewModel

/**
 * Guarda el estado del navegador de Explorar mientras se cambia de pestaña (el WebView se destruye con la vista,
 * pero la página y su historial se restauran desde [webState]). Al girar el teléfono en Explorar la pantalla no se
 * rehace (ver MainActivity.onConfigurationChanged), así que el navegador sigue tal cual.
 */
class ExploreViewModel : ViewModel() {
    var webState: Bundle? = null
    var desktopMode: Boolean = false

    /** El usuario salió de la pantalla completa de este vídeo con "atrás" (se queda así hasta otro vídeo). */
    internal var immersiveDismissedFor: String? = null

    /** La app giró la pantalla a horizontal para la pantalla completa (al salir se devuelve el giro al teléfono). */
    internal var landscapeRequested = false

    /**
     * Se salió de la pantalla completa con "atrás": la pantalla queda en vertical hasta que el teléfono esté de verdad
     * en vertical (como en la app de YouTube); entonces el giro vuelve a seguir al teléfono.
     */
    internal var portraitRequested = false

    /** Avisos de la página (lo que suena) para la pantalla de Explorar que exista en cada momento. */
    internal class MediaRelay {
        var target: ((String?) -> Unit)? = null
    }
}
