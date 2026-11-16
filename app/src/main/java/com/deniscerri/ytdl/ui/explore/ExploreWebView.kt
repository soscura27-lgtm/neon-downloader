package com.deniscerri.ytdl.ui.explore

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * Navegador de Explorar que puede seguir sonando en segundo plano, como Brave.
 *
 * El motor del WebView (Chromium) pausa los vídeos de una página en cuanto su ventana deja de verse: en Android
 * la reproducción de vídeo en segundo plano viene desactivada. Brave la activa cambiando el propio motor; aquí se
 * consigue sin tocarlo: mientras [keepAlive] es true, al navegador no se le cuenta que la ventana se ocultó (app
 * minimizada o pantalla apagada), así que ni el motor ni la página pausan nada.
 */
class ExploreWebView : WebView {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    /** true: la página se cree a la vista aunque la app esté fuera o la pantalla apagada. */
    var keepAlive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // se le cuenta al navegador la visibilidad que toca ahora mismo
            super.onWindowVisibilityChanged(if (value) View.VISIBLE else windowVisibility)
        }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (keepAlive) View.VISIBLE else visibility)
    }
}
