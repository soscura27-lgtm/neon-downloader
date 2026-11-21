package com.deniscerri.ytdl.ui.explore

import android.os.Bundle
import android.view.ViewGroup
import androidx.lifecycle.ViewModel
import androidx.webkit.ScriptHandler

/**
 * Guarda el estado del navegador de Explorar mientras se cambia de pestaña o se gira la pantalla.
 *
 * Al cambiar de pestaña el WebView se destruye con la vista y la página (con su historial) se restaura desde
 * [webState]. Al girar el teléfono, en cambio, se guarda el WebView entero en [retained] y la pantalla nueva lo vuelve
 * a colocar: la página no se recarga y lo que suena sigue sonando, como en la app de YouTube.
 */
class ExploreViewModel : ViewModel() {
    var webState: Bundle? = null
    var desktopMode: Boolean = false

    /** El navegador mientras la pantalla se vuelve a crear (al girar el teléfono). */
    internal var retained: RetainedBrowser? = null

    /** El usuario salió de la pantalla completa de este vídeo con "atrás" (se queda así hasta otro vídeo). */
    internal var immersiveDismissedFor: String? = null

    /** La app giró la pantalla a horizontal para la pantalla completa (al salir se devuelve el giro al teléfono). */
    internal var landscapeRequested = false

    /**
     * Se salió de la pantalla completa con "atrás": la pantalla queda en vertical hasta que el teléfono esté de verdad
     * en vertical (como en la app de YouTube); entonces el giro vuelve a seguir al teléfono.
     */
    internal var portraitRequested = false

    /** Lo que vive con el WebView y no se puede volver a crear sin recargar la página. */
    internal class RetainedBrowser(
        val webView: ExploreWebView,
        val adBlockScript: ScriptHandler?,
        val backgroundScript: ScriptHandler?,
        val watchScript: ScriptHandler?,
        val mediaChannel: Boolean,
        val mediaRelay: MediaRelay
    )

    /** Avisos de la página (lo que suena) para la pantalla de Explorar que exista en cada momento. */
    internal class MediaRelay {
        var target: ((String?) -> Unit)? = null
    }

    override fun onCleared() {
        // la actividad se cerró del todo mientras el navegador esperaba: ya no hace falta
        retained?.webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        retained = null
        super.onCleared()
    }
}
