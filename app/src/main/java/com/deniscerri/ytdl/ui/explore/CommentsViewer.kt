package com.deniscerri.ytdl.ui.explore

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.deniscerri.ytdl.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Comentarios del vídeo con GIF, imágenes y stickers.
 *
 * Desde octubre-noviembre de 2026 YouTube admite GIF (de Tenor) e imágenes en los comentarios, pero su web
 * móvil (la de Explorar) todavía no los muestra; la web de escritorio sí. Esta ventana abre el vídeo en la
 * web de escritorio y deja visibles solo los comentarios, ajustados al teléfono y con la misma sesión de
 * Google: se pueden leer, dar me gusta y responder. Enviar un GIF o un sticker solo lo acepta la app
 * oficial de YouTube (lo que llega desde la web lo quita), por eso hay un acceso directo a esa app.
 */
class CommentsViewer(
    private val context: Context,
    private val videoId: String,
    private val videoTitle: String?,
    /** Se tocó algo de YouTube que no es este vídeo (un canal, otro vídeo): se abre en Explorar. */
    private val onOpenInExplore: (String) -> Unit,
    /** Selector de archivos de la pantalla de Explorar (para adjuntar imágenes donde YouTube lo permita). */
    private val onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean
) {

    private var dialog: AppCompatDialog? = null
    private var webView: WebView? = null
    private var progress: LinearProgressIndicator? = null
    private val scripts = ArrayList<ScriptHandler>()

    val isShowing: Boolean
        get() = dialog?.isShowing == true

    fun show() {
        if (dialog != null) return
        val d = AppCompatDialog(context, R.style.ThemeOverlay_App_FullScreenDialog)
        val root = LayoutInflater.from(d.context).inflate(R.layout.dialog_explore_comments, null)
        d.setContentView(root)
        d.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // la ventana ocupa toda la pantalla y el contenido se aparta de las barras y del teclado
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        val toolbar = root.findViewById<MaterialToolbar>(R.id.comments_toolbar)
        toolbar.subtitle = videoTitle?.takeIf { it.isNotBlank() }
        toolbar.setNavigationOnClickListener { d.dismiss() }
        toolbar.inflateMenu(R.menu.explore_comments_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.comments_menu_open_app -> openInYoutubeApp()
                R.id.comments_menu_reload -> webView?.reload()
            }
            true
        }

        val hint = root.findViewById<View>(R.id.comments_send_hint)
        root.findViewById<View>(R.id.comments_open_app).setOnClickListener { openInYoutubeApp() }
        root.findViewById<View>(R.id.comments_hint_close).setOnClickListener { hint.visibility = View.GONE }

        progress = root.findViewById(R.id.comments_progress)
        setupWebView(root.findViewById(R.id.comments_webview))

        d.setOnDismissListener { release() }
        d.setOnCancelListener { release() }
        dialog = d
        d.show()
        webView?.loadUrl(watchUrl())
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun watchUrl(): String = "https://www.youtube.com/watch?v=$videoId"

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        webView = wv
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // los GIF de los comentarios pueden ser vídeos cortos en bucle: que se animen solos
            mediaPlaybackRequiresUserGesture = false
            // la página se maqueta al ancho del teléfono (la web de escritorio no trae viewport de móvil)
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = ExploreBrowser.userAgent(wv.context, desktop = true)
        }
        ExploreBrowser.hideWebViewIdentity(wv.context, wv.settings, desktop = true)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                scripts.add(WebViewCompat.addDocumentStartJavaScript(wv, commentsScript(wv.context), ORIGINS))
            }
            if (AdBlocker.enabled) {
                runCatching {
                    scripts.add(WebViewCompat.addDocumentStartJavaScript(wv, AdBlocker.script(wv.context), ORIGINS))
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleNavigation(uri, request.isForMainFrame)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null) return null
                // el reproductor está oculto: su vídeo no se descarga (ni suena)
                val host = request.url?.host?.lowercase(Locale.ROOT).orEmpty()
                val path = request.url?.path.orEmpty()
                if ((host == "googlevideo.com" || host.endsWith(".googlevideo.com")) && path.startsWith("/videoplayback")) {
                    return WebResourceResponse(
                        "text/plain", "UTF-8", 204, "No Content",
                        mapOf("Access-Control-Allow-Origin" to "*"),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return AdBlocker.intercept(request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progress?.visibility = View.VISIBLE
                injectFallback(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progress?.visibility = View.INVISIBLE
                CookieManager.getInstance().flush()
                injectFallback(view, url)
            }

            // YouTube cambia de página sin recargar: si se fue a otra cosa (un canal, otro vídeo), se abre en Explorar
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url == null) return
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
                if (uri.host?.lowercase(Locale.ROOT) !in PAGE_HOSTS) return
                val path = uri.path.orEmpty()
                if (KEEP_PATHS.any { path.startsWith(it) }) return
                if (YoutubeUrls.parse(url).videoId == videoId) return
                handOver(url)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress?.setProgressCompat(newProgress, true)
                progress?.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                return onFileChooser(filePathCallback, fileChooserParams)
            }
        }
    }

    /** true = resuelto aquí; false = que lo cargue el visor. */
    private fun handleNavigation(uri: Uri, mainFrame: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return true
        if (!mainFrame) return false
        val url = uri.toString()
        val host = uri.host
        if (host?.lowercase(Locale.ROOT) in PAGE_HOSTS) {
            val path = uri.path.orEmpty()
            if (path == "/redirect") {
                uri.getQueryParameter("q")?.takeIf { it.isNotBlank() }?.let { openOutside(it) }
                return true
            }
            if (KEEP_PATHS.any { path.startsWith(it) }) return false
            if (YoutubeUrls.parse(url).videoId == videoId) return false
            handOver(url)
            return true
        }
        // el inicio de sesión de Google se hace aquí mismo
        if (YoutubeUrls.isInternalHost(host)) return false
        openOutside(url)
        return true
    }

    private fun handOver(url: String) {
        webView?.stopLoading()
        dismiss()
        onOpenInExplore(url)
    }

    private fun openOutside(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    /** Abre el vídeo en la app oficial de YouTube, que es la única que deja comentar con GIF o stickers. */
    private fun openInYoutubeApp() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl()))
            .setPackage(YOUTUBE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.youtube_app_missing, Toast.LENGTH_SHORT).show()
        }
    }

    /** WebView antiguo sin scripts al empezar la página: se inyecta el mismo script durante la carga. */
    private fun injectFallback(view: WebView?, url: String?) {
        if (view == null || scripts.isNotEmpty()) return
        if (!YoutubeUrls.isYoutubeUrl(url)) return
        view.evaluateJavascript(commentsScript(view.context), null)
    }

    private fun release() {
        val wv = webView ?: return
        webView = null
        dialog = null
        scripts.forEach { runCatching { it.remove() } }
        scripts.clear()
        CookieManager.getInstance().flush()
        runCatching {
            wv.stopLoading()
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = WebChromeClient()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
    }

    companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        private val ORIGINS: Set<String> = setOf("https://www.youtube.com", "https://youtube.com")

        /** Páginas de YouTube que, si no son este vídeo, se abren en Explorar (consentimiento y cuentas se quedan aquí). */
        private val PAGE_HOSTS = setOf("www.youtube.com", "youtube.com", "m.youtube.com", "music.youtube.com")

        /** Rutas de YouTube que no son "otra página" (inicio y cierre de sesión, cuenta). */
        private val KEEP_PATHS = listOf("/signin", "/logout", "/account", "/sw.js", "/redirect")

        @Volatile
        private var cachedScript: String? = null

        private fun commentsScript(context: Context): String {
            cachedScript?.let { return it }
            val text = context.applicationContext.resources.openRawResource(R.raw.explore_comments)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            cachedScript = text
            return text
        }
    }
}
