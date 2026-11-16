package com.deniscerri.ytdl.ui.explore

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.CookieItem
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.CookieViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadProgressViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.downloads.QueuePlacementDialog
import com.deniscerri.ytdl.util.QueuePlacement
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Explorar: YouTube dentro de la app (buscador, inicio, suscripciones, biblioteca) con inicio de sesión de
 * Google, apartados de películas y series gratis y legales, y GIF y stickers.
 *
 * Todas las opciones de descarga viven en un solo botón en la esquina de la barra superior (nada flota encima
 * del vídeo): descargar en vídeo o audio, escuchar en segundo plano, elegir vídeos, ver los comentarios con GIF,
 * guardar la miniatura o las imágenes de una publicación, o descargar de cualquier otra web compatible.
 */
class ExploreFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private lateinit var state: ExploreViewModel
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var cookieViewModel: CookieViewModel
    private lateinit var progressViewModel: DownloadProgressViewModel

    private var webView: ExploreWebView? = null
    private lateinit var searchField: TextInputEditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var downloadCorner: View
    private lateinit var downloadLabel: TextView
    private lateinit var moreButton: MaterialButton
    private lateinit var player: ExplorePlayer
    private lateinit var errorView: View
    private lateinit var fullscreenContainer: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var currentUrl: String = YoutubeUrls.HOME
    private var currentPage: YoutubeUrls.Page = YoutubeUrls.Page(YoutubeUrls.Kind.NONE)
    private var lastSignedIn: Boolean? = null
    private var signInBlockedShown = false

    private var fetchJob: Job? = null
    private var progressDialog: AlertDialog? = null
    private var actionsSheet: BottomSheetDialog? = null
    private var sectionsSheet: BottomSheetDialog? = null
    private var commentsViewer: CommentsViewer? = null

    private var adBlockScript: ScriptHandler? = null

    // Reproducción en segundo plano: lo que suena en la página y si ahora mismo suena con la app fuera
    private var backgroundScript: ScriptHandler? = null
    private var mediaState: ExploreBackgroundPlayback.State? = null
    private var mediaChannel = false
    private var backgroundActive = false
    private var savedRendererPriority: Pair<Int, Boolean>? = null
    private val mediaHandler = Handler(Looper.getMainLooper())
    private val mediaCommander: (String) -> Unit = { command -> runMediaCommand(command) }

    /** WebView antiguos sin canal de mensajes: se pregunta a la página cada poco. */
    private val pollMedia = object : Runnable {
        override fun run() {
            val wv = webView ?: return
            wv.evaluateJavascript(MEDIA_STATE_SCRIPT) { raw -> onMediaMessage(raw) }
            mediaHandler.postDelayed(this, MEDIA_POLL_MS)
        }
    }

    /** Pausa y vuelta de la app entera (no de esta pestaña): al cambiar de pestaña decide onDestroyView. */
    private val activityObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_PAUSE -> onAppHidden()
            Lifecycle.Event.ON_RESUME -> onAppShown()
            else -> Unit
        }
    }

    /** Subida de archivos desde la página (fotos, GIF...): la respuesta del selector va a este callback. */
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        callback.onReceiveValue(chosenFiles(result.resultCode, result.data))
    }

    // si el formato se cambia desde el selector masivo, el botón de la esquina se actualiza solo
    private val kindListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DownloadKind.PREF_KEY) showKind()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_explore, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity()
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        state = ViewModelProvider(activity)[ExploreViewModel::class.java]
        resultViewModel = ViewModelProvider(activity)[ResultViewModel::class.java]
        downloadViewModel = ViewModelProvider(activity)[DownloadViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(activity)[DownloadCardViewModel::class.java]
        cookieViewModel = ViewModelProvider(activity)[CookieViewModel::class.java]
        progressViewModel = ViewModelProvider(activity)[DownloadProgressViewModel::class.java]

        searchField = view.findViewById(R.id.explore_search)
        progress = view.findViewById(R.id.explore_progress)
        downloadCorner = view.findViewById(R.id.explore_download_corner)
        downloadLabel = view.findViewById(R.id.explore_download_label)
        moreButton = view.findViewById(R.id.explore_more)
        errorView = view.findViewById(R.id.explore_error)
        fullscreenContainer = view.findViewById(R.id.explore_fullscreen)

        setupSearch()
        view.findViewById<MaterialButton>(R.id.explore_sections).setOnClickListener { showSections() }
        moreButton.setOnClickListener { showMoreMenu(it) }
        downloadCorner.setOnClickListener { showActions() }
        view.findViewById<View>(R.id.explore_retry).setOnClickListener {
            errorView.visibility = View.GONE
            webView?.reload()
        }
        player = ExplorePlayer(
            context = requireContext(),
            bar = view.findViewById(R.id.explore_player_bar),
            title = view.findViewById(R.id.explore_player_title),
            previous = view.findViewById(R.id.explore_player_prev),
            playPause = view.findViewById(R.id.explore_player_play),
            next = view.findViewById(R.id.explore_player_next),
            stop = view.findViewById(R.id.explore_player_stop),
            // si se reanuda el audio, el vídeo de la página se calla para que no suenen los dos a la vez
            onResumeRequested = { pausePage { } },
            onProblem = { reason -> toastText(getString(R.string.music_error, reason)) }
        )
        viewLifecycleOwner.lifecycle.addObserver(player)
        showKind()
        prefs.registerOnSharedPreferenceChangeListener(kindListener)

        setupWebView(view.findViewById(R.id.explore_webview))
        setupBackHandling()
        observeDuplicates()
        observeDownloadLevel(view)
        ExploreBackgroundPlayback.attach(mediaCommander)
        activity.lifecycle.addObserver(activityObserver)
    }

    // ------------------------------------------------------------------------------------------
    // Navegador
    // ------------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: ExploreWebView) {
        webView = wv

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(wv, true)
        applyDarkPreference(cookies)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            // las webs de películas y series sirven imágenes de otros dominios
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        applyBrowserMode(wv)
        applyAdBlock(wv)
        installBackgroundPlayback(wv)

        wv.webViewClient = ExploreClient()
        wv.webChromeClient = ExploreChromeClient()
        wv.setOnLongClickListener { onLongPress(wv) }

        val restored = state.webState?.let { saved ->
            runCatching { wv.restoreState(saved) }.getOrNull()
        }
        if (restored == null) {
            wv.loadUrl(YoutubeUrls.HOME)
        }
    }

    /** YouTube en oscuro, igual que el resto de la app, salvo que el usuario ya haya elegido otro tema en YouTube. */
    private fun applyDarkPreference(cookies: CookieManager) {
        runCatching {
            val url = YoutubeUrls.COOKIES_URL
            val pref = cookies.getCookie(url).orEmpty()
                .split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("PREF=") }
                ?.removePrefix("PREF=")
            val params = LinkedHashMap<String, String>()
            pref?.split("&")?.forEach { pair ->
                val index = pair.indexOf('=')
                if (index > 0) params[pair.substring(0, index)] = pair.substring(index + 1)
            }
            if (params.containsKey("f6")) return
            params["f6"] = "400"
            val value = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            cookies.setCookie(url, "PREF=$value; Domain=.youtube.com; Path=/; Max-Age=31536000; Secure")
            cookies.flush()
        }
    }

    private fun applyBrowserMode(wv: WebView) {
        val desktop = state.desktopMode
        wv.settings.apply {
            userAgentString = ExploreBrowser.userAgent(wv.context, desktop)
            useWideViewPort = true
            loadWithOverviewMode = desktop
            setSupportZoom(desktop)
            builtInZoomControls = desktop
            displayZoomControls = false
        }
        ExploreBrowser.hideWebViewIdentity(wv.context, wv.settings, desktop)
    }

    private fun load(url: String) {
        webView?.loadUrl(url)
    }

    // ------------------------------------------------------------------------------------------
    // Bloqueador de anuncios
    // ------------------------------------------------------------------------------------------

    /** Activa o desactiva el bloqueador según el ajuste y (re)instala el script que se ejecuta al empezar cada página. */
    private fun applyAdBlock(wv: WebView) {
        val enabled = prefs.getBoolean(AdBlocker.PREF_ENABLED, true)
        AdBlocker.enabled = enabled

        adBlockScript?.let { handler -> runCatching { handler.remove() } }
        adBlockScript = null

        if (enabled && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            adBlockScript = runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    wv,
                    AdBlocker.script(wv.context),
                    AdBlocker.ORIGIN_RULES
                )
            }.getOrNull()
        }
    }

    /**
     * Si este WebView es demasiado antiguo para ejecutar scripts al empezar la página, se inyecta el mismo
     * script durante la carga (la limpieza de datos llega más tarde, pero ocultar y omitir anuncios funciona).
     */
    private fun injectAdBlockFallback(view: WebView?, url: String?) {
        if (!AdBlocker.enabled || adBlockScript != null || view == null) return
        if (!YoutubeUrls.isYoutubeUrl(url)) return
        view.evaluateJavascript(AdBlocker.script(view.context), null)
    }

    private fun toggleAdBlock() {
        val wv = webView ?: return
        val enabled = !prefs.getBoolean(AdBlocker.PREF_ENABLED, true)
        prefs.edit().putBoolean(AdBlocker.PREF_ENABLED, enabled).apply()
        applyAdBlock(wv)
        toast(if (enabled) R.string.explore_adblock_on else R.string.explore_adblock_off)
        wv.reload()
    }

    private inner class ExploreClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            return handleNavigation(uri, request.isForMainFrame)
        }

        // Bloqueador de anuncios: las peticiones de publicidad se contestan en vacío
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request == null) return null
            return AdBlocker.intercept(request)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            errorView.visibility = View.GONE
            progress.visibility = View.VISIBLE
            onUrlChanged(url)
            injectAdBlockFallback(view, url)
            injectBackgroundFallback(view)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            progress.visibility = View.INVISIBLE
            onUrlChanged(url ?: view?.url)
            CookieManager.getInstance().flush()
            checkSignIn()
            injectAdBlockFallback(view, url)
            injectBackgroundFallback(view)
        }

        // YouTube cambia de página sin recargar (también al deslizar Shorts): aquí se entera la app de cada cambio
        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            onUrlChanged(url)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                errorView.visibility = View.VISIBLE
            }
        }
    }

    private inner class ExploreChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress.setProgressCompat(newProgress, true)
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null || customView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            fullscreenContainer.visibility = View.VISIBLE
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        override fun getDefaultVideoPoster(): Bitmap? {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        // Películas y series con DRM (YouTube Películas, ViX, Plex...): la reproducción protegida necesita este permiso
        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            val protectedMedia = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (protectedMedia.isNotEmpty()) {
                request.grant(protectedMedia.toTypedArray())
            } else {
                request.deny()
            }
        }

        // Subir fotos, GIF o stickers desde el teléfono donde la página lo permita
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            if (filePathCallback == null || fileChooserParams == null) return false
            return openFileChooser(filePathCallback, fileChooserParams)
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        updateCorner()
    }

    /**
     * true = la navegación ya está resuelta aquí; false = que la cargue el WebView.
     * Explorar es un navegador: YouTube, el inicio de sesión de Google, las webs de películas y series y las de GIF
     * se abren aquí. Los enlaces de salida de YouTube (descripciones y comentarios) se abren fuera, como siempre.
     */
    private fun handleNavigation(uri: Uri, mainFrame: Boolean): Boolean {
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> Unit
            "about", "blob", "data" -> return false
            "intent" -> {
                if (mainFrame) followIntentFallback(uri.toString())
                return true
            }
            // vnd.youtube://, market://, whatsapp://... son intentos de abrir otra app
            else -> return true
        }
        if (mainFrame && YoutubeUrls.isYoutubeHost(uri.host) && uri.path == "/redirect") {
            uri.getQueryParameter("q")?.takeIf { it.isNotBlank() }?.let { openOutside(it) }
            return true
        }
        return false
    }

    /**
     * Webs de películas y series que intentan abrir su app (intent://): si traen una dirección web de
     * respaldo de la misma web, se sigue aquí. En YouTube se ignoran, como siempre.
     */
    private fun followIntentFallback(raw: String) {
        if (YoutubeUrls.isYoutubeUrl(currentUrl)) return
        val intent = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
        val fallback = intent.getStringExtra("browser_fallback_url") ?: return
        val fallbackHost = runCatching { Uri.parse(fallback).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return
        val currentHost = runCatching { Uri.parse(currentUrl).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return
        if (!isHttp(fallback)) return
        val sameSite = fallbackHost == currentHost ||
                fallbackHost.endsWith(".$currentHost") || currentHost.endsWith(".$fallbackHost")
        if (sameSite) load(fallback)
    }

    private fun openOutside(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return
        if (YoutubeUrls.isInternalHost(uri.host)) {
            webView?.loadUrl(url)
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    private fun openInExternalBrowser(url: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), null))
        }
    }

    private fun onUrlChanged(url: String?) {
        if (url.isNullOrBlank() || url == "about:blank" || view == null) return
        currentUrl = url
        currentPage = YoutubeUrls.parse(url)
        updateCorner()
        if (!signInBlockedShown && (url.contains("/signin/rejected") || url.contains("disallowed_useragent"))) {
            signInBlockedShown = true
            showSignInBlocked()
        }
    }

    /** Lo que se ve AHORA en el navegador (al deslizar Shorts la dirección cambia sin recargar la página). */
    private fun refreshPage(): YoutubeUrls.Page {
        val url = webView?.url
        if (!url.isNullOrBlank() && url != "about:blank") {
            currentUrl = url
            currentPage = YoutubeUrls.parse(url)
        }
        return currentPage
    }

    private fun setupBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val wv = webView
                    when {
                        customView != null -> exitFullscreen()
                        wv != null && wv.canGoBack() -> wv.goBack()
                        else -> {
                            // no hay más historial: se cede el "atrás" a la app y se vuelve a vigilar
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                            if (isAdded && view != null) isEnabled = true
                        }
                    }
                }
            })
    }

    /** Lo llama la barra inferior al volver a tocar la pestaña Explorar. */
    fun onTabReselected() {
        val wv = webView ?: return
        if (wv.scrollY > 0) wv.scrollTo(0, 0) else wv.loadUrl(YoutubeUrls.HOME)
    }

    // ------------------------------------------------------------------------------------------
    // Buscador
    // ------------------------------------------------------------------------------------------

    private fun setupSearch() {
        searchField.setOnEditorActionListener { v, actionId, event ->
            val enterPressed = event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                submitSearch(v.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun submitSearch(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) return
        hideKeyboard()
        searchField.setText("")
        searchField.clearFocus()

        val looksLikeLink = query.startsWith("http://", ignoreCase = true) ||
                query.startsWith("https://", ignoreCase = true) ||
                (!query.contains(" ") && Patterns.WEB_URL.matcher(query).matches())
        if (!looksLikeLink) {
            load("https://m.youtube.com/results?search_query=" + Uri.encode(query))
            return
        }

        val link = if (query.startsWith("http", ignoreCase = true)) query else "https://$query"
        if (YoutubeUrls.isYoutubeHost(Uri.parse(link).host)) {
            load(link)
        } else {
            // otra web (TikTok, Instagram, Vimeo...): yt-dlp la descarga directamente
            downloadVideo(link)
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    // ------------------------------------------------------------------------------------------
    // Menús y apartados
    // ------------------------------------------------------------------------------------------

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.explore_menu, popup.menu)
        popup.menu.findItem(R.id.explore_menu_desktop).isChecked = state.desktopMode
        popup.menu.findItem(R.id.explore_menu_adblock).apply {
            isChecked = prefs.getBoolean(AdBlocker.PREF_ENABLED, true)
            title = getString(R.string.explore_adblock_menu, AdBlocker.blockedCount)
        }
        popup.menu.findItem(R.id.explore_menu_ask_position).isChecked = prefs.getBoolean(PREF_ASK_POSITION, true)
        popup.menu.findItem(R.id.explore_menu_background).isChecked = ExploreBackgroundPlayback.isEnabled(prefs)
        popup.menu.findItem(R.id.explore_menu_forward).isEnabled = webView?.canGoForward() == true
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.explore_menu_home -> load(YoutubeUrls.HOME)
                R.id.explore_menu_sections -> showSections()
                R.id.explore_menu_account -> showAccountMenu(moreButton)
                R.id.explore_menu_forward -> webView?.goForward()
                R.id.explore_menu_reload -> webView?.reload()
                R.id.explore_menu_desktop -> toggleDesktopMode()
                R.id.explore_menu_adblock -> toggleAdBlock()
                R.id.explore_menu_ask_position -> {
                    val ask = !prefs.getBoolean(PREF_ASK_POSITION, true)
                    prefs.edit().putBoolean(PREF_ASK_POSITION, ask).apply()
                }
                R.id.explore_menu_copy -> copyLink(refreshUrl())
                R.id.explore_menu_external -> openInExternalBrowser(refreshUrl())
                R.id.explore_menu_background -> {
                    val enabled = !ExploreBackgroundPlayback.isEnabled(prefs)
                    prefs.edit().putBoolean(ExploreBackgroundPlayback.PREF_ENABLED, enabled).apply()
                    toast(if (enabled) R.string.explore_background_on else R.string.explore_background_off)
                }
            }
            true
        }
        popup.show()
    }

    private fun toggleDesktopMode() {
        val wv = webView ?: return
        state.desktopMode = !state.desktopMode
        applyBrowserMode(wv)
        wv.reload()
    }

    /** Apartados: secciones de YouTube, películas y series, apps de streaming y GIF/stickers. */
    private fun showSections() {
        sectionsSheet?.dismiss()
        sectionsSheet = ExploreSections.show(requireContext()) { section -> openSection(section) }
    }

    private fun openSection(section: ExploreSections.Section) {
        if (view == null) return
        val url = section.url
        if (url != null) {
            load(url)
            return
        }
        val intent = ExploreSections.launchIntent(requireContext(), section)
        if (intent == null) {
            toast(R.string.sections_app_missing)
        } else {
            runCatching { startActivity(intent) }.onFailure { toast(R.string.sections_app_missing) }
        }
    }

    private fun showAccountMenu(anchor: View) {
        val signedIn = isSignedIn()
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.explore_account_menu, popup.menu)
        popup.menu.findItem(R.id.explore_account_sign_in).isVisible = !signedIn
        popup.menu.findItem(R.id.explore_account_use_downloads).apply {
            isVisible = signedIn
            isChecked = prefs.getBoolean(PREF_USE_ACCOUNT, false)
        }
        popup.menu.findItem(R.id.explore_account_sign_out).isVisible = signedIn
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.explore_account_sign_in -> load(YoutubeUrls.SIGN_IN)
                R.id.explore_account_use_downloads -> {
                    if (prefs.getBoolean(PREF_USE_ACCOUNT, false)) {
                        disableAccountDownloads()
                    } else {
                        askUseAccountForDownloads()
                    }
                }
                R.id.explore_account_sign_out -> confirmSignOut()
            }
            true
        }
        popup.show()
    }

    // ------------------------------------------------------------------------------------------
    // Cuenta de Google
    // ------------------------------------------------------------------------------------------

    private fun isSignedIn(): Boolean {
        val cookie = CookieManager.getInstance().getCookie(YoutubeUrls.COOKIES_URL) ?: return false
        return cookie.contains("SAPISID=") ||
                cookie.contains("__Secure-3PAPISID=") ||
                cookie.contains("LOGIN_INFO=")
    }

    /** Se llama al terminar cada página: detecta cuando acaba de iniciarse sesión. */
    private fun checkSignIn() {
        val now = isSignedIn()
        val before = lastSignedIn
        lastSignedIn = now
        if (before == false && now &&
            !prefs.getBoolean(PREF_ACCOUNT_PROMPTED, false) &&
            !prefs.getBoolean(PREF_USE_ACCOUNT, false)
        ) {
            prefs.edit().putBoolean(PREF_ACCOUNT_PROMPTED, true).apply()
            askUseAccountForDownloads()
        }
    }

    private fun askUseAccountForDownloads() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.explore_use_account_title)
            .setMessage(R.string.explore_use_account_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> enableAccountDownloads() }
            .show()
    }

    private fun enableAccountDownloads() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { syncAccountCookiesSafely(force = true) }
            if (ok) {
                prefs.edit()
                    .putBoolean(PREF_USE_ACCOUNT, true)
                    .putBoolean("use_cookies", true)
                    .apply()
                toast(R.string.explore_use_account_on)
            } else {
                toast(R.string.explore_use_account_failed)
            }
        }
    }

    private fun disableAccountDownloads(showToast: Boolean = true) {
        prefs.edit().putBoolean(PREF_USE_ACCOUNT, false).apply()
        requireActivity().lifecycleScope.launch(Dispatchers.IO) {
            cookieViewModel.getAll()
                .filter { it.url == YoutubeUrls.COOKIES_URL && it.description == COOKIE_DESCRIPTION }
                .forEach { cookieViewModel.delete(it) }
        }
        if (showToast) toast(R.string.explore_use_account_off)
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.explore_sign_out_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> signOut() }
            .show()
    }

    private fun signOut() {
        disableAccountDownloads(showToast = false)
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            cookies.flush()
            WebStorage.getInstance().deleteAllData()
            if (isAdded && view != null) {
                applyDarkPreference(cookies)
                lastSignedIn = false
                webView?.loadUrl(YoutubeUrls.HOME)
                toast(R.string.explore_signed_out)
            }
        }
    }

    private fun showSignInBlocked() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.explore_signin_blocked_title)
            .setMessage(R.string.explore_signin_blocked_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.explore_try_desktop) { _, _ ->
                val wv = webView
                if (wv != null) {
                    state.desktopMode = true
                    applyBrowserMode(wv)
                    wv.loadUrl(YoutubeUrls.SIGN_IN)
                }
            }
            .show()
    }

    /**
     * Copia las cookies de la sesión de Google del navegador a las cookies de descarga de la app
     * (para vídeos con restricción de edad, privados y listas propias).
     */
    private suspend fun syncAccountCookies(force: Boolean): Boolean {
        if (!isSignedIn()) return false
        val content = cookieViewModel.getCookiesFromDB(YoutubeUrls.COOKIES_URL).getOrNull()
        if (content.isNullOrBlank()) return false

        val existing = cookieViewModel.getAll().firstOrNull {
            it.url == YoutubeUrls.COOKIES_URL && it.description == COOKIE_DESCRIPTION
        }
        if (!force && existing != null && existing.enabled && existing.content == content) return true

        val id = cookieViewModel.insert(
            CookieItem(0, YoutubeUrls.COOKIES_URL, content, COOKIE_DESCRIPTION, true)
        )
        if (force && existing != null && !existing.enabled) {
            cookieViewModel.changeCookieEnabledState(id, true)
        }
        cookieViewModel.updateCookiesFile().join()
        return true
    }

    private suspend fun syncAccountCookiesSafely(force: Boolean = false): Boolean {
        return try {
            syncAccountCookies(force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Antes de descargar se refrescan las cookies, porque YouTube las renueva mientras se navega. */
    private fun withFreshCookies(block: () -> Unit) {
        if (!prefs.getBoolean(PREF_USE_ACCOUNT, false)) {
            block()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { syncAccountCookiesSafely() }
            block()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Botón de la esquina y hoja de opciones
    // ------------------------------------------------------------------------------------------

    /** Debajo del icono de descarga, el formato elegido: "VÍDEO", "MP3" o "AUDIO". */
    private fun showKind() {
        if (view == null) return
        downloadLabel.setText(DownloadKind.load(prefs).shortLabelRes)
        updateCorner()
    }

    /** El botón siempre está en la esquina; se ve apagado cuando en la página no hay nada que descargar. */
    private fun updateCorner() {
        if (view == null) return
        val page = currentPage
        val url = currentUrl
        val active = if (YoutubeUrls.isYoutubeUrl(url)) {
            page.isDownloadable || page.canChooseVideos || page.kind == YoutubeUrls.Kind.POST
        } else {
            isHttp(url) && ExploreSections.siteFor(hostOf(url))?.watchOnly != true
        }
        downloadCorner.alpha = if (active) 1f else 0.45f
    }

    /** Todas las opciones de lo que se está viendo, en una hoja que sube desde abajo. */
    private fun showActions() {
        val wv = webView ?: return
        val page = refreshPage()
        val url = currentUrl
        val host = hostOf(url)
        val youtube = YoutubeUrls.isYoutubeHost(host)
        val title = cleanPageTitle(wv.title.orEmpty()).ifBlank { host ?: url }
        val kindName = { kind: DownloadKind -> getString(kind.labelRes) }

        val actions = ArrayList<ExploreActionsSheet.Action>()
        var subtitle: String? = null
        var showFormat = false
        var primaryLabel: ((DownloadKind) -> String)? = null
        var primaryIcon = R.drawable.baseline_download_24
        var onPrimary: (() -> Unit)? = null
        var note: String? = null

        if (youtube) {
            val videoId = page.videoId
            when (page.kind) {
                YoutubeUrls.Kind.VIDEO -> {
                    subtitle = getString(
                        when {
                            page.isShort -> R.string.actions_what_short
                            page.isLive -> R.string.actions_what_live
                            page.isMusic -> R.string.actions_what_song
                            else -> R.string.actions_what_video
                        }
                    )
                    showFormat = true
                    primaryLabel = { kind -> getString(R.string.actions_primary_single, kindName(kind)) }
                    onPrimary = { downloadVideo(YoutubeUrls.videoUrl(page) ?: url) }
                }
                YoutubeUrls.Kind.VIDEO_IN_LIST -> {
                    subtitle = getString(R.string.actions_what_video_in_list) +
                            if (page.isMix) " · " + getString(R.string.actions_what_mix) else ""
                    showFormat = true
                    primaryLabel = { kind -> getString(R.string.actions_primary_this_video, kindName(kind)) }
                    onPrimary = { downloadVideo(YoutubeUrls.videoUrl(page) ?: url) }
                    actions.add(ExploreActionsSheet.Action(R.drawable.baseline_format_list_numbered_24, listLabel(page)) {
                        downloadList(page)
                    })
                }
                YoutubeUrls.Kind.PLAYLIST -> {
                    subtitle = getString(if (page.isMix) R.string.actions_what_mix else R.string.actions_what_playlist)
                    showFormat = true
                    primaryLabel = { kind ->
                        getString(
                            if (page.isMix) R.string.actions_primary_mix else R.string.actions_primary_playlist,
                            kindName(kind)
                        )
                    }
                    onPrimary = { downloadList(page) }
                }
                YoutubeUrls.Kind.CHANNEL -> {
                    subtitle = getString(R.string.actions_what_channel)
                    // el selector masivo usa el mismo formato
                    showFormat = true
                    primaryLabel = { getString(R.string.actions_primary_channel) }
                    primaryIcon = R.drawable.ic_checklist
                    onPrimary = { openBulkSelect(page) }
                }
                YoutubeUrls.Kind.CLIP -> {
                    subtitle = getString(R.string.actions_what_clip)
                    showFormat = true
                    primaryLabel = { kind -> getString(R.string.actions_primary_clip, kindName(kind)) }
                    onPrimary = { downloadVideo(page.directUrl ?: url) }
                }
                YoutubeUrls.Kind.ALBUM -> {
                    subtitle = getString(R.string.actions_what_album)
                    showFormat = true
                    primaryLabel = { kind -> getString(R.string.actions_primary_album, kindName(kind)) }
                    onPrimary = { downloadListUrl(page.directUrl ?: url, isMix = false) }
                }
                YoutubeUrls.Kind.POST -> {
                    subtitle = getString(R.string.actions_what_post)
                    primaryLabel = { getString(R.string.actions_primary_post) }
                    primaryIcon = R.drawable.ic_image
                    onPrimary = { savePageImages(limit = MAX_POST_IMAGES) }
                    actions.add(ExploreActionsSheet.Action(R.drawable.ic_video, getString(R.string.actions_post_video)) {
                        downloadPostVideo()
                    })
                }
                YoutubeUrls.Kind.NONE -> note = getString(R.string.actions_note_nothing)
            }

            if (page.canChooseVideos && page.kind != YoutubeUrls.Kind.CHANNEL) {
                actions.add(ExploreActionsSheet.Action(R.drawable.ic_checklist, getString(R.string.bulk_choose_from_list)) {
                    openBulkSelect(page)
                })
            }
            if (page.hasVideo && videoId != null) {
                actions.add(
                    ExploreActionsSheet.Action(
                        R.drawable.ic_headphones,
                        getString(R.string.music_listen_title),
                        getString(R.string.actions_listen_sub)
                    ) { listenVideo(videoId) }
                )
            }
            if (page.hasList) {
                actions.add(ExploreActionsSheet.Action(R.drawable.ic_headphones, getString(R.string.actions_listen_list)) {
                    listenList(page, videoId)
                })
            }
            if (page.hasVideo && videoId != null) {
                actions.add(
                    ExploreActionsSheet.Action(
                        R.drawable.ic_comment,
                        getString(R.string.actions_comments),
                        getString(R.string.actions_comments_sub)
                    ) { openComments(videoId, title) }
                )
                actions.add(ExploreActionsSheet.Action(R.drawable.ic_image, getString(R.string.actions_thumbnail)) {
                    saveImages(listOf(YoutubeUrls.thumbnailUrls(videoId)), title)
                })
                actions.add(
                    ExploreActionsSheet.Action(
                        R.drawable.ic_open_in_new,
                        getString(R.string.actions_open_youtube_app),
                        getString(R.string.actions_open_youtube_app_sub)
                    ) { openInYoutubeApp(YoutubeUrls.videoUrl(videoId)) }
                )
            }
        } else {
            val site = ExploreSections.siteFor(host)
            subtitle = site?.name ?: host
            when {
                site?.watchOnly == true -> note = getString(R.string.actions_note_watch_only, site.name)
                site?.images == true -> {
                    primaryLabel = { getString(R.string.actions_primary_gif) }
                    primaryIcon = R.drawable.ic_gif
                    onPrimary = { savePageImages(limit = 1) }
                    actions.add(ExploreActionsSheet.Action(R.drawable.baseline_share_24, getString(R.string.explore_share_image)) {
                        sharePageImage()
                    })
                }
                isHttp(url) -> {
                    showFormat = true
                    primaryLabel = { kind -> getString(R.string.actions_primary_page, kindName(kind)) }
                    onPrimary = { downloadVideo(url) }
                    note = getString(R.string.actions_note_page)
                    actions.add(ExploreActionsSheet.Action(R.drawable.ic_image, getString(R.string.actions_page_images)) {
                        savePageImages(limit = MAX_PAGE_IMAGES)
                    })
                }
            }
        }

        if (isHttp(url)) {
            actions.add(ExploreActionsSheet.Action(R.drawable.ic_copy, getString(R.string.explore_copy_link)) {
                copyLink(url)
            })
            actions.add(ExploreActionsSheet.Action(R.drawable.baseline_share_24, getString(R.string.actions_share_link)) {
                shareLink(url, title)
            })
            if (!youtube) {
                actions.add(ExploreActionsSheet.Action(R.drawable.ic_arrow_outward, getString(R.string.explore_open_external)) {
                    openInExternalBrowser(url)
                })
            }
        }

        actionsSheet?.dismiss()
        actionsSheet = ExploreActionsSheet.show(
            requireContext(),
            ExploreActionsSheet.Content(
                title = title,
                subtitle = subtitle,
                showFormat = showFormat,
                primaryLabel = primaryLabel,
                primaryIcon = primaryIcon,
                onPrimary = onPrimary,
                note = note,
                actions = actions
            ),
            DownloadKind.load(prefs)
        ) { kind ->
            DownloadKind.save(prefs, kind)
            showKind()
        }
    }

    /**
     * Abre el selector masivo con la fuente de la página que se está viendo:
     * un canal (con sus pestañas Vídeos / Shorts / Directos), una playlist o un Mix.
     */
    private fun openBulkSelect(page: YoutubeUrls.Page) {
        val kind: BulkSelectViewModel.SourceKind
        val url: String
        var tab = BulkSelectViewModel.Tab.VIDEOS
        when {
            page.kind == YoutubeUrls.Kind.CHANNEL && page.channelPath != null -> {
                kind = BulkSelectViewModel.SourceKind.CHANNEL
                url = YoutubeUrls.channelUrl(page.channelPath)
                // si se está viendo la pestaña Shorts o Directos del canal, se abre directamente esa
                tab = BulkSelectViewModel.Tab.values().firstOrNull { it.path == page.channelTab }
                    ?: BulkSelectViewModel.Tab.VIDEOS
            }
            page.hasList -> {
                kind = if (page.isMix) {
                    BulkSelectViewModel.SourceKind.MIX
                } else {
                    BulkSelectViewModel.SourceKind.PLAYLIST
                }
                url = YoutubeUrls.listUrl(page) ?: return
            }
            else -> {
                toast(R.string.explore_not_downloadable)
                return
            }
        }

        withFreshCookies {
            val nav = findNavController()
            if (nav.currentDestination?.id == R.id.exploreFragment) {
                nav.navigate(
                    R.id.bulkSelectDialog,
                    bundleOf(
                        BulkSelectDialog.ARG_KIND to kind.name,
                        BulkSelectDialog.ARG_URL to url,
                        BulkSelectDialog.ARG_TAB to tab.name
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Nivel de descarga global
    // ------------------------------------------------------------------------------------------

    /** Barra de arriba: porcentaje del lote en curso (cuántas van, cuántas descargando y cuántas esperan). */
    private fun observeDownloadLevel(root: View) {
        val strip = root.findViewById<View>(R.id.explore_dl_strip)
        val percentText = root.findViewById<TextView>(R.id.explore_dl_percent)
        val detailText = root.findViewById<TextView>(R.id.explore_dl_detail)
        val bar = root.findViewById<LinearProgressIndicator>(R.id.explore_dl_bar)

        strip.setOnClickListener {
            val nav = findNavController()
            if (nav.currentDestination?.id == R.id.exploreFragment) {
                nav.navigate(R.id.downloadQueueMainFragment)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                progressViewModel.overall.collect { overall ->
                    if (overall == null) {
                        strip.visibility = View.GONE
                        return@collect
                    }
                    strip.visibility = View.VISIBLE
                    percentText.text = getString(R.string.download_percent, overall.percent)
                    detailText.text = describeLevel(overall)
                    bar.setProgressCompat(overall.percent, true)
                }
            }
        }
    }

    private fun describeLevel(overall: DownloadProgressViewModel.Overall): String {
        val parts = ArrayList<String>(2)
        parts.add(
            when {
                overall.done -> getString(R.string.dl_strip_done, overall.finished, overall.total)
                overall.active == 0 && overall.waiting == 0 && overall.paused > 0 ->
                    getString(R.string.dl_strip_paused, overall.finished, overall.total)
                else -> getString(
                    R.string.dl_strip_detail,
                    overall.finished,
                    overall.total,
                    overall.active,
                    overall.waiting
                )
            }
        )
        if (overall.failed > 0) parts.add(getString(R.string.dl_strip_failed, overall.failed))
        return parts.joinToString(" · ")
    }

    private fun listLabel(page: YoutubeUrls.Page): String {
        return if (page.isMix) {
            getString(R.string.explore_download_whole_mix, YoutubeUrls.MIX_LIMIT)
        } else {
            getString(R.string.explore_download_whole_playlist)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pulsación larga: enlaces, miniaturas, imágenes, GIF y stickers
    // ------------------------------------------------------------------------------------------

    private fun onLongPress(wv: WebView): Boolean {
        val result = wv.hitTestResult
        return when (result.type) {
            WebView.HitTestResult.IMAGE_TYPE -> {
                val src = result.extra
                if (!isSavableImage(src)) return false
                showLinkActions(null, src)
                true
            }
            WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val imageFromHit = if (result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) result.extra else null
                val handler = Handler(Looper.getMainLooper()) { message ->
                    if (isAdded && view != null) {
                        val image = message.data.getString("src")?.takeIf { it.isNotBlank() } ?: imageFromHit
                        showLinkActions(message.data.getString("url"), image)
                    }
                    true
                }
                wv.requestFocusNodeHref(handler.obtainMessage())
                true
            }
            else -> false
        }
    }

    private fun showLinkActions(href: String?, imageSrc: String?) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (!href.isNullOrBlank() && isHttp(href)) {
            if (YoutubeUrls.isYoutubeUrl(href)) {
                val page = YoutubeUrls.parse(href)
                val videoId = page.videoId
                if (videoId != null) {
                    labels.add(getString(R.string.explore_download_video))
                    actions.add { downloadVideo(YoutubeUrls.videoUrl(page) ?: YoutubeUrls.videoUrl(videoId)) }
                    labels.add(getString(R.string.music_listen_link))
                    actions.add { listenLink(videoId) }
                }
                if (page.kind == YoutubeUrls.Kind.CLIP && page.directUrl != null) {
                    labels.add(getString(R.string.actions_primary_clip, getString(DownloadKind.load(prefs).labelRes)))
                    actions.add { downloadVideo(page.directUrl) }
                }
                if (page.kind == YoutubeUrls.Kind.ALBUM && page.directUrl != null) {
                    labels.add(getString(R.string.actions_primary_album, getString(DownloadKind.load(prefs).labelRes)))
                    actions.add { downloadListUrl(page.directUrl, isMix = false) }
                }
                if (page.hasList) {
                    labels.add(listLabel(page))
                    actions.add { downloadList(page) }
                }
                if (page.canChooseVideos) {
                    labels.add(
                        getString(
                            if (page.kind == YoutubeUrls.Kind.CHANNEL) R.string.bulk_choose_from_channel
                            else R.string.bulk_choose_from_list
                        )
                    )
                    actions.add { openBulkSelect(page) }
                }
            } else {
                labels.add(getString(R.string.explore_download_link))
                actions.add { downloadVideo(href) }
            }
            labels.add(getString(R.string.explore_copy_link))
            actions.add { copyLink(href) }
        }

        if (isSavableImage(imageSrc)) {
            val image = imageSrc!!
            val candidates = MediaSaver.candidatesFor(image)
            labels.add(getString(R.string.explore_save_image))
            actions.add { saveImages(listOf(candidates), null) }
            labels.add(getString(R.string.explore_share_image))
            actions.add { shareImage(candidates, null) }
            if (isHttp(image)) {
                labels.add(getString(R.string.explore_copy_image_link))
                actions.add { copyLink(image) }
            }
        }

        if (labels.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** Una imagen de verdad: dirección web, o una imagen incrustada que no sea un marcador de 1 px. */
    private fun isSavableImage(src: String?): Boolean {
        if (src.isNullOrBlank()) return false
        if (isHttp(src)) return true
        return src.startsWith("data:image", ignoreCase = true) && src.length > MIN_DATA_IMAGE_LENGTH
    }

    // ------------------------------------------------------------------------------------------
    // Descargas
    // ------------------------------------------------------------------------------------------

    /**
     * Antes de descargar, si ya hay descargas esperando turno, pregunta en qué lugar de la cola poner esta
     * ("al final", "la siguiente" o "la 3"). Si la cola está vacía, o el usuario lo desactivó en el menú, sigue
     * directamente y al final. Cerrar la ventana cancela la descarga.
     */
    private fun askQueuePosition(onChosen: (Int) -> Unit) {
        if (!prefs.getBoolean(PREF_ASK_POSITION, true)) {
            onChosen(QueuePlacement.END)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val waiting = withContext(Dispatchers.IO) { downloadViewModel.getQueuedCount() }
            if (!isAdded || view == null) return@launch
            if (waiting > 0) {
                QueuePlacementDialog.choose(requireContext(), QueuePlacement.END, onChosen)
            } else {
                onChosen(QueuePlacement.END)
            }
        }
    }

    private fun downloadVideo(url: String) {
        askQueuePosition { position ->
            // END borra cualquier petición vieja de este vídeo; otro valor queda lista para cuando llegue a la cola
            QueuePlacement.request(listOf(url), position)
            startVideoDownload(url)
        }
    }

    private fun startVideoDownload(url: String) {
        val kind = DownloadKind.load(prefs)
        withFreshCookies {
            if (kind.isAudio) {
                // el audio se eligió de forma explícita con el formato: se pone en cola ya, sin hoja de opciones
                Toast.makeText(
                    requireContext(),
                    getString(R.string.kind_audio_started, url),
                    Toast.LENGTH_SHORT
                ).show()
                requireActivity().lifecycleScope.launch(Dispatchers.IO) {
                    val item = downloadViewModel.createDownloadItemFromResult(
                        result = downloadViewModel.createEmptyResultItem(url),
                        givenType = kind.type
                    )
                    val container = kind.container
                    if (container != null && item.type == DownloadType.audio) item.container = container
                    downloadViewModel.queueDownloads(listOf(item))
                }
            } else if (prefs.getBoolean("download_card", true)) {
                showSingleDownloadSheet(downloadViewModel.createEmptyResultItem(url), kind.type)
            } else {
                Toast.makeText(
                    requireContext(),
                    "${getString(R.string.downloading)} $url",
                    Toast.LENGTH_SHORT
                ).show()
                requireActivity().lifecycleScope.launch(Dispatchers.IO) {
                    val item = downloadViewModel.createDownloadItemFromResult(
                        result = downloadViewModel.createEmptyResultItem(url),
                        givenType = kind.type
                    )
                    downloadViewModel.queueDownloads(listOf(item))
                }
            }
        }
    }

    private fun showSingleDownloadSheet(resultItem: ResultItem, type: DownloadType) {
        val nav = findNavController()
        if (nav.currentDestination?.id != R.id.exploreFragment) return
        downloadCardViewModel.setResultItem(resultItem)
        downloadCardViewModel.setDownloadItem(null)
        val bundle = Bundle()
        bundle.putSerializable("type", downloadViewModel.getDownloadType(type, resultItem.url))
        nav.navigate(R.id.downloadBottomSheetDialog, bundle)
    }

    /** Lee la playlist o el Mix completo y abre la hoja de descarga múltiple (o los pone en cola directamente). */
    private fun downloadList(page: YoutubeUrls.Page) {
        val listUrl = YoutubeUrls.listUrl(page) ?: return
        downloadListUrl(listUrl, page.isMix)
    }

    /** Igual que [downloadList], para cualquier lista que entienda yt-dlp (también álbumes de YouTube Music). */
    private fun downloadListUrl(listUrl: String, isMix: Boolean) {
        if (fetchJob?.isActive == true) return
        askQueuePosition { position -> readAndDownloadList(listUrl, isMix, position) }
    }

    private fun readAndDownloadList(listUrl: String, isMix: Boolean, position: Int) {
        readList(listUrl, isMix, saveInDatabase = true) { items ->
            val ids = items.map { it.id }.filter { it != 0L }
            if (ids.isEmpty()) {
                showListProblem(getString(R.string.explore_list_empty))
                return@readList
            }

            // el lugar elegido en la cola vale para todos: se colocan seguidos, en el mismo orden
            QueuePlacement.request(items.filter { it.id != 0L }.map { it.url }, position)
            val showCard = prefs.getBoolean("download_card", true)
            val kind = DownloadKind.load(prefs)
            downloadViewModel.turnResultItemsToProcessingDownloads(
                ids,
                downloadNow = !showCard,
                forcedType = kind.type,
                forcedAudioContainer = kind.container
            )
            if (showCard) {
                val nav = findNavController()
                if (nav.currentDestination?.id == R.id.exploreFragment) {
                    nav.navigate(R.id.downloadMultipleBottomSheetDialog2)
                }
            } else {
                toast(R.string.downloading)
            }
        }
    }

    /**
     * Lee la lista completa (con la ventana de progreso y el botón cancelar) y entrega los vídeos a [onItems].
     * Para descargar se guardan en la base de datos, porque hacen falta sus ids; para escuchar no se toca la
     * base de datos.
     */
    private fun readList(
        listUrl: String,
        isMix: Boolean,
        saveInDatabase: Boolean,
        onItems: suspend (List<ResultItem>) -> Unit
    ) {
        if (fetchJob?.isActive == true) return

        withFreshCookies {
            val message = showProgressDialog { fetchJob?.cancel() }
            resultViewModel.repository.itemCount.value = -1

            fetchJob = viewLifecycleOwner.lifecycleScope.launch {
                val counter = launch {
                    resultViewModel.repository.itemCount.collect { count ->
                        if (count > 0) {
                            message.text = getString(R.string.explore_reading_list_count, count)
                        }
                    }
                }
                try {
                    val items = withContext(Dispatchers.IO) {
                        if (isMix) {
                            // un Mix solo se lee bien con yt-dlp y con tope (no tiene final)
                            resultViewModel.repository.getYoutubeMix(
                                listUrl,
                                resetResults = saveInDatabase,
                                addToResults = saveInDatabase
                            )
                        } else {
                            resultViewModel.repository.getResultsFromSource(
                                listUrl,
                                resetResults = saveInDatabase,
                                addToResults = saveInDatabase
                            )
                        }
                    }
                    progressDialog?.dismiss()
                    onItems(items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    progressDialog?.dismiss()
                    showListProblem(getString(R.string.explore_list_error, e.message ?: e.toString()))
                } finally {
                    counter.cancel()
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Imágenes, GIF y stickers: guardar y enviar
    // ------------------------------------------------------------------------------------------

    /** Guarda imágenes (cada una con sus direcciones candidatas) en Imágenes/Neon Downloader. */
    private fun saveImages(items: List<List<String>>, baseName: String?) {
        if (items.isEmpty()) {
            toast(R.string.media_none_found)
            return
        }
        toast(R.string.media_saving)
        val app = requireContext().applicationContext
        val referer = currentUrl
        requireActivity().lifecycleScope.launch {
            val result = MediaSaver.save(app, items, referer, baseName)
            val folder = result.folder.orEmpty()
            val message = when {
                result.saved == 0 -> app.getString(R.string.media_save_failed)
                result.failed > 0 -> app.getString(R.string.media_saved_partial, result.saved, result.failed)
                result.saved == 1 -> app.getString(R.string.media_saved, folder)
                else -> app.getString(R.string.media_saved_many, result.saved, folder)
            }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Envía una imagen, un GIF o un sticker a otra app (WhatsApp, Telegram...) con el menú de compartir. */
    private fun shareImage(candidates: List<String>, baseName: String?) {
        toast(R.string.media_sharing)
        val activity = requireActivity()
        val referer = currentUrl
        activity.lifecycleScope.launch {
            val intent = MediaSaver.shareIntent(activity, candidates, referer, baseName)
            if (intent == null) {
                Toast.makeText(activity, R.string.media_save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            runCatching { activity.startActivity(intent) }
        }
    }

    /** Las imágenes grandes de la página (en una publicación de YouTube, solo las de la publicación). */
    private fun savePageImages(limit: Int) {
        readPageMedia { media ->
            val images = media.images.take(limit)
            if (images.isEmpty()) {
                toast(R.string.media_none_found)
            } else {
                saveImages(images.map { MediaSaver.candidatesFor(it) }, cleanPageTitle(media.title))
            }
        }
    }

    private fun sharePageImage() {
        readPageMedia { media ->
            val first = media.images.firstOrNull()
            if (first == null) {
                toast(R.string.media_none_found)
            } else {
                shareImage(MediaSaver.candidatesFor(first), cleanPageTitle(media.title))
            }
        }
    }

    /** Una publicación que comparte un vídeo: se descarga ese vídeo. */
    private fun downloadPostVideo() {
        readPageMedia { media ->
            val link = media.video
            if (link.isNullOrBlank()) {
                toast(R.string.post_no_video)
                return@readPageMedia
            }
            val page = YoutubeUrls.parse(link)
            downloadVideo(YoutubeUrls.videoUrl(page) ?: link)
        }
    }

    private class PageMedia(val title: String, val images: List<String>, val video: String?)

    private fun readPageMedia(onRead: (PageMedia) -> Unit) {
        val wv = webView ?: return
        wv.evaluateJavascript(PAGE_MEDIA_SCRIPT) { raw ->
            if (!isAdded || view == null) return@evaluateJavascript
            val media = runCatching {
                val json = JSONObject(raw)
                val list = json.optJSONArray("i")
                val images = ArrayList<String>()
                if (list != null) {
                    for (i in 0 until list.length()) {
                        list.optString(i).takeIf { it.isNotBlank() }?.let { images.add(it) }
                    }
                }
                PageMedia(json.optString("t"), images, json.optString("v").takeIf { it.isNotBlank() })
            }.getOrNull() ?: PageMedia("", emptyList(), null)
            onRead(media)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Comentarios con GIF y stickers
    // ------------------------------------------------------------------------------------------

    private fun openComments(videoId: String, title: String?) {
        commentsViewer?.dismiss()
        val viewer = CommentsViewer(
            context = requireContext(),
            videoId = videoId,
            videoTitle = title,
            onOpenInExplore = { url -> if (view != null) load(url) },
            onFileChooser = { callback, params -> openFileChooser(callback, params) }
        )
        commentsViewer = viewer
        viewer.show()
    }

    /** La app oficial de YouTube: la única que deja comentar con GIF o stickers. */
    private fun openInYoutubeApp(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(CommentsViewer.YOUTUBE_PACKAGE)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(R.string.youtube_app_missing)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Subir archivos desde la página
    // ------------------------------------------------------------------------------------------

    private fun openFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val intent = runCatching { params.createIntent() }.getOrNull()
            ?: Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return try {
            fileLauncher.launch(intent)
            true
        } catch (e: Exception) {
            // sin selector de archivos: que el WebView lo dé por cancelado
            fileCallback = null
            false
        }
    }

    private fun chosenFiles(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            return Array(clip.itemCount) { clip.getItemAt(it).uri }
        }
        data.data?.let { return arrayOf(it) }
        return WebChromeClient.FileChooserParams.parseResult(resultCode, data)
    }

    // ------------------------------------------------------------------------------------------
    // Escuchar solo el audio (en segundo plano)
    // ------------------------------------------------------------------------------------------

    /** Lo que se sabe de la página en el momento de pasar al audio: título, canal y segundo en el que iba el vídeo. */
    private class PageSnapshot(val title: String, val author: String, val positionMs: Long)

    /** Si ya suena algo, pregunta si lo nuevo va ahora, a continuación o al final de la cola; si no, empieza directamente. */
    private fun chooseListenMode(onChosen: (ExplorePlayer.Mode) -> Unit) {
        if (!player.hasQueue()) {
            onChosen(ExplorePlayer.Mode.NOW)
            return
        }
        val modes = ExplorePlayer.Mode.values()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.music_listen_title)
            .setItems(
                arrayOf(
                    getString(R.string.music_mode_now),
                    getString(R.string.music_mode_next),
                    getString(R.string.music_mode_end)
                )
            ) { _, which -> onChosen(modes[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** El vídeo que se está viendo, solo en audio: sigue desde el mismo segundo y con la pantalla apagada. */
    private fun listenVideo(videoId: String) {
        chooseListenMode { mode ->
            withFreshCookies {
                pausePage { snapshot ->
                    val item = ExplorePlayer.mediaItem(videoId, cleanPageTitle(snapshot.title), snapshot.author)
                    startMusic(listOf(item), 0, snapshot.positionMs, mode)
                }
            }
        }
    }

    /** Una playlist o un Mix completos, solo en audio; si se pidió desde un vídeo de la lista, empieza por ese. */
    private fun listenList(page: YoutubeUrls.Page, fromVideoId: String?) {
        val listUrl = YoutubeUrls.listUrl(page) ?: return
        if (fetchJob?.isActive == true) return
        chooseListenMode { mode ->
            readList(listUrl, page.isMix, saveInDatabase = false) { results ->
                val items = results.mapNotNull { ExplorePlayer.mediaItem(it) }
                if (items.isEmpty()) {
                    showListProblem(getString(R.string.explore_list_empty))
                    return@readList
                }
                val startUrl = fromVideoId?.let { YoutubeUrls.videoUrl(it) }
                val start = items.indexOfFirst { it.mediaId == startUrl }.coerceAtLeast(0)
                pausePage { snapshot ->
                    // el segundo en el que iba el vídeo solo sirve si se empieza por ese mismo vídeo
                    val position = if (startUrl != null && items[start].mediaId == startUrl) snapshot.positionMs else 0L
                    startMusic(items, start, position, mode)
                }
            }
        }
    }

    /** Pulsación larga sobre un enlace a un vídeo: escucharlo sin abrirlo (se busca su título para la notificación). */
    private fun listenLink(videoId: String) {
        chooseListenMode { mode ->
            withFreshCookies {
                viewLifecycleOwner.lifecycleScope.launch {
                    val info = withContext(Dispatchers.IO) {
                        runCatching {
                            resultViewModel.repository.getResultsFromSource(
                                YoutubeUrls.videoUrl(videoId),
                                resetResults = false,
                                addToResults = false,
                                singleItem = true
                            ).firstOrNull()
                        }.getOrNull()
                    }
                    val item = info?.let { ExplorePlayer.mediaItem(it) }
                        ?: ExplorePlayer.mediaItem(videoId, null, null)
                    pausePage { startMusic(listOf(item), 0, 0L, mode) }
                }
            }
        }
    }

    /** Pausa el vídeo de la página (para que no suene a la vez que el audio) y devuelve lo que se sabe de él. */
    private fun pausePage(onDone: (PageSnapshot) -> Unit) {
        val wv = webView
        if (wv == null) {
            onDone(PageSnapshot("", "", 0L))
            return
        }
        wv.evaluateJavascript(PAUSE_PAGE_SCRIPT) { raw ->
            var snapshot = PageSnapshot("", "", 0L)
            runCatching {
                val json = JSONObject(raw)
                val seconds = json.optDouble("p", -1.0)
                snapshot = PageSnapshot(
                    title = json.optString("t"),
                    author = json.optString("a"),
                    positionMs = if (seconds > 0) (seconds * 1000).toLong() else 0L
                )
            }
            if (isAdded && view != null) onDone(snapshot)
        }
    }

    /** El título de la pestaña es "Título - YouTube", y con avisos pendientes "(3) Título - YouTube". */
    private fun cleanPageTitle(raw: String): String {
        return raw.replace(Regex("^\\(\\d+\\)\\s*"), "").removeSuffix(" - YouTube").trim()
    }

    private fun startMusic(items: List<MediaItem>, startIndex: Int, positionMs: Long, mode: ExplorePlayer.Mode) {
        if (!isAdded || view == null) return
        // la página ya está en pausa: su notificación sobra (el reproductor de audio pone la suya)
        ExploreBackgroundPlayback.stopService()
        player.play(items, startIndex, positionMs, mode)
        toast(
            when (mode) {
                ExplorePlayer.Mode.NEXT -> R.string.music_added_next
                ExplorePlayer.Mode.END -> R.string.music_added_end
                ExplorePlayer.Mode.NOW -> R.string.music_starting
            }
        )
    }

    private fun showProgressDialog(onCancel: () -> Unit): TextView {
        val content = layoutInflater.inflate(R.layout.dialog_explore_progress, null)
        val text = content.findViewById<TextView>(R.id.explore_progress_text)
        progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(content)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .setOnCancelListener { onCancel() }
            .show()
        return text
    }

    private fun showListProblem(message: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Si una descarga rápida ya existe, la app lo avisa igual que en Inicio. */
    private fun observeDuplicates() {
        viewLifecycleOwner.lifecycleScope.launch {
            downloadViewModel.alreadyExistsUiState.collectLatest { res ->
                if (res.isNotEmpty() && findNavController().currentDestination?.id == R.id.exploreFragment) {
                    val bundle = bundleOf(Pair("duplicates", ArrayList(res)))
                    delay(500)
                    findNavController().navigate(R.id.downloadsAlreadyExistDialog, bundle)
                    downloadViewModel.alreadyExistsUiState.value = mutableListOf()
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------------------------------

    /** Dirección de lo que se ve ahora (ver [refreshPage]). */
    private fun refreshUrl(): String {
        refreshPage()
        return currentUrl
    }

    private fun hostOf(url: String?): String? =
        runCatching { Uri.parse(url).host }.getOrNull()?.lowercase(Locale.ROOT)

    private fun isHttp(url: String?): Boolean =
        url != null && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

    private fun copyLink(url: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        toast(R.string.explore_link_copied)
    }

    private fun shareLink(url: String, title: String?) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        if (!title.isNullOrBlank()) send.putExtra(Intent.EXTRA_SUBJECT, title)
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    private fun toast(resId: Int) {
        context?.let { Toast.makeText(it, resId, Toast.LENGTH_SHORT).show() }
    }

    private fun toastText(text: String) {
        context?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------------------------------
    // Reproducción en segundo plano (música y vídeo siguen al salir de la app o apagar la pantalla)
    // ------------------------------------------------------------------------------------------

    /**
     * Script que mantiene la página "visible" y cuenta lo que suena, en todas las webs, y el canal por el que lo cuenta.
     * Si el WebView no tiene canal de mensajes, se le pregunta a la página cada pocos segundos.
     */
    private fun installBackgroundPlayback(wv: WebView) {
        val script = ExploreBackgroundPlayback.script(wv.context)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            backgroundScript = runCatching {
                WebViewCompat.addDocumentStartJavaScript(wv, script, ALL_ORIGINS)
            }.recoverCatching {
                WebViewCompat.addDocumentStartJavaScript(wv, script, YOUTUBE_ORIGINS)
            }.getOrNull()
        }
        mediaChannel = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val listener = WebViewCompat.WebMessageListener { _, message, _, isMainFrame, _ ->
                if (isMainFrame) onMediaMessage(message.data)
            }
            mediaChannel = runCatching {
                WebViewCompat.addWebMessageListener(wv, MEDIA_CHANNEL, ALL_ORIGINS, listener)
                true
            }.recoverCatching {
                WebViewCompat.addWebMessageListener(wv, MEDIA_CHANNEL, YOUTUBE_ORIGINS, listener)
                true
            }.getOrDefault(false)
        }
        if (!mediaChannel) {
            mediaHandler.removeCallbacks(pollMedia)
            mediaHandler.postDelayed(pollMedia, MEDIA_POLL_MS)
        }
    }

    private fun injectBackgroundFallback(view: WebView?) {
        if (view == null || backgroundScript != null) return
        view.evaluateJavascript(ExploreBackgroundPlayback.script(view.context), null)
    }

    /**
     * La página contó qué suena (o se le preguntó). Como en Brave, en cuanto algo suena aparece la notificación del
     * reproductor: así el servicio que mantiene viva la app ya está en marcha antes de salir de ella.
     */
    private fun onMediaMessage(raw: String?) {
        if (view == null) return
        val state = ExploreBackgroundPlayback.State.fromJson(raw) ?: return
        mediaState = state
        ExploreBackgroundPlayback.update(state)
        if (state.playing && ExploreBackgroundPlayback.isEnabled(prefs) && !ExploreBackgroundPlayback.serviceActive) {
            ExploreBackgroundPlayback.startService(requireContext())
        }
    }

    /** Órdenes de la notificación, la pantalla de bloqueo o los audífonos. */
    private fun runMediaCommand(command: String) {
        val wv = webView ?: return
        when (command) {
            "play", "pause", "toggle" -> {
                // la app está fuera y la página dormida: se despierta para que pueda volver a sonar
                if (command != "pause" && !isResumed && !backgroundActive && ExploreBackgroundPlayback.isEnabled(prefs)) {
                    wv.onResume()
                    enterBackgroundMode(wv)
                }
                wv.evaluateJavascript("window.__neonMediaCommand && window.__neonMediaCommand('$command')", null)
            }
            "suspend" -> {
                // ya no se quiere sonar fuera de la app: si no está a la vista, el navegador se pausa como siempre
                if (backgroundActive) {
                    leaveBackgroundMode(wv)
                    if (!isResumed) wv.onPause()
                }
            }
        }
    }

    /** La app se oculta (inicio, otra app, pantalla apagada): si algo suena, sigue sonando. */
    private fun onAppHidden() {
        val wv = webView ?: return
        val activity = activity ?: return
        val keepPlaying = mediaState?.playing == true &&
                ExploreBackgroundPlayback.isEnabled(prefs) &&
                !activity.isChangingConfigurations
        if (keepPlaying && (ExploreBackgroundPlayback.serviceActive || ExploreBackgroundPlayback.startService(activity))) {
            enterBackgroundMode(wv)
        } else {
            wv.onPause()
        }
    }

    /** La app vuelve a estar a la vista (la notificación sigue mientras algo suene, como en Brave). */
    private fun onAppShown() {
        val wv = webView ?: return
        if (backgroundActive) leaveBackgroundMode(wv)
        wv.onResume()
    }

    /**
     * La página sigue sonando con la app fuera: el navegador no se entera de que la ventana se ocultó (si se
     * enterara, el motor pausaría el vídeo) y el proceso que dibuja la página conserva su prioridad.
     */
    private fun enterBackgroundMode(wv: ExploreWebView) {
        backgroundActive = true
        wv.keepAlive = true
        keepRendererAlive(wv, true)
    }

    private fun leaveBackgroundMode(wv: ExploreWebView) {
        backgroundActive = false
        wv.keepAlive = false
        keepRendererAlive(wv, false)
    }

    /** Con la app fuera, el proceso que dibuja la página no se rebaja de prioridad (Android no lo congela). */
    private fun keepRendererAlive(wv: WebView, keep: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            if (keep) {
                if (savedRendererPriority == null) {
                    savedRendererPriority = wv.rendererRequestedPriority to wv.rendererPriorityWaivedWhenNotVisible
                }
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            } else {
                savedRendererPriority?.let { (priority, waived) -> wv.setRendererPriorityPolicy(priority, waived) }
                savedRendererPriority = null
            }
        }
    }

    /**
     * La página se cierra. Si se cambió de pestaña (o Android cerró la pantalla) mientras sonaba algo de YouTube,
     * el audio sigue en el reproductor de la app desde el mismo segundo. Al girar la pantalla no: la página vuelve.
     */
    private fun finishBackgroundPlayback() {
        mediaHandler.removeCallbacks(pollMedia)
        val activity = activity
        activity?.lifecycle?.removeObserver(activityObserver)
        val state = mediaState
        if (state?.playing == true && activity != null && !activity.isChangingConfigurations &&
            this::prefs.isInitialized && ExploreBackgroundPlayback.isEnabled(prefs) &&
            ExploreBackgroundPlayback.handOverToMusic(activity, state)
        ) {
            Toast.makeText(activity.applicationContext, R.string.explore_background_handoff, Toast.LENGTH_SHORT).show()
        }
        ExploreBackgroundPlayback.detach(mediaCommander)
        ExploreBackgroundPlayback.stopService()
        webView?.keepAlive = false
        backgroundActive = false
        mediaState = null
        savedRendererPriority = null
    }

    // ------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------------------------------

    // El navegador se pausa o se reanuda cuando la app entera se oculta o vuelve (onAppHidden / onAppShown), no al
    // cambiar de pestaña: en ese caso la vista se destruye y onDestroyView decide si lo que suena sigue en el
    // reproductor de audio.
    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroyView() {
        if (this::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(kindListener)
        finishBackgroundPlayback()
        fetchJob?.cancel()
        fetchJob = null
        progressDialog?.dismiss()
        progressDialog = null
        actionsSheet?.dismiss()
        actionsSheet = null
        sectionsSheet?.dismiss()
        sectionsSheet = null
        commentsViewer?.dismiss()
        commentsViewer = null
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        exitFullscreen()
        webView?.let { wv ->
            adBlockScript = null
            backgroundScript = null
            runCatching {
                val bundle = Bundle()
                wv.saveState(bundle)
                state.webState = bundle
            }
            CookieManager.getInstance().flush()
            wv.stopLoading()
            wv.setOnLongClickListener(null)
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = WebChromeClient()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroyView()
    }

    companion object {
        private const val PREF_ASK_POSITION = "explore_ask_queue_position"
        private const val PREF_USE_ACCOUNT = "explore_use_account"
        private const val PREF_ACCOUNT_PROMPTED = "explore_account_prompted"
        private const val COOKIE_DESCRIPTION = "Explorar (cuenta de Google)"

        /** Canal por el que la página cuenta lo que suena (res/raw/explore_background.js). */
        private const val MEDIA_CHANNEL = "neonMedia"
        private const val MEDIA_POLL_MS = 3000L
        private const val MEDIA_STATE_SCRIPT = "window.__neonMediaState ? window.__neonMediaState() : null"
        private val ALL_ORIGINS: Set<String> = setOf("*")
        private val YOUTUBE_ORIGINS: Set<String> = setOf("https://*.youtube.com", "https://youtube.com")

        /** Máximo de imágenes que se guardan de una publicación o de una página. */
        private const val MAX_POST_IMAGES = 20
        private const val MAX_PAGE_IMAGES = 10

        /** Las imágenes incrustadas más cortas que esto son marcadores vacíos (miniaturas que aún no cargan). */
        private const val MIN_DATA_IMAGE_LENGTH = 2000

        /** Pausa los vídeos de la página y devuelve { t: título de la pestaña, a: canal, p: segundo en el que iba }. */
        private const val PAUSE_PAGE_SCRIPT = """
            (function () {
                var videos = document.getElementsByTagName('video');
                var main = document.querySelector('video.html5-main-video') || videos[0] || null;
                var position = main ? main.currentTime : -1;
                for (var i = 0; i < videos.length; i++) { try { videos[i].pause(); } catch (e) {} }
                var author = '';
                try {
                    var node = document.querySelector('[itemprop="author"] [itemprop="name"]');
                    if (node) author = node.getAttribute('content') || node.textContent || '';
                } catch (e) {}
                return {
                    t: document.title || '',
                    a: author,
                    p: (typeof position === 'number' && isFinite(position)) ? position : -1
                };
            })()
        """

        /**
         * Imágenes de la página, de la más grande a la más pequeña, sin avatares ni iconos: { t: título,
         * i: [direcciones], v: vídeo compartido en una publicación de YouTube }. En YouTube solo se mira dentro
         * de la publicación si se encuentra; en otras webs se añaden og:image / og:video (Tenor, GIPHY...).
         */
        private const val PAGE_MEDIA_SCRIPT = """
            (function () {
                function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }
                var yt = /(^|\.)youtube\.com$/.test(location.hostname);
                var seen = {}, out = [];
                function add(u) {
                    u = abs(u || '');
                    if (!u || seen[u] || !/^https?:/i.test(u)) { return; }
                    seen[u] = 1; out.push(u);
                }
                var scope = document;
                if (yt) {
                    var post = document.querySelector('ytm-backstage-post-thread-renderer, ytm-backstage-post-renderer, ytm-post-renderer, ytd-backstage-post-thread-renderer, ytd-backstage-post-renderer, ytd-post-renderer, [class*="backstage-post"], [class*="post-renderer"]');
                    if (post) { scope = post; }
                }
                var found = [];
                var imgs = scope.querySelectorAll('img');
                for (var i = 0; i < imgs.length; i++) {
                    var img = imgs[i];
                    var src = img.currentSrc || img.src || img.getAttribute('data-src') || '';
                    if (!src || /^data:/i.test(src)) { continue; }
                    var r = img.getBoundingClientRect();
                    var area = Math.max((img.naturalWidth || 0) * (img.naturalHeight || 0), r.width * r.height);
                    if (area < 140 * 140) { continue; }
                    found.push({ u: src, a: area });
                }
                found.sort(function (x, y) { return y.a - x.a; });
                for (var j = 0; j < found.length; j++) { add(found[j].u); }
                if (!yt) {
                    var metas = ['meta[property="og:image"]', 'meta[property="og:image:secure_url"]', 'meta[name="twitter:image"]',
                                 'meta[property="og:video"]', 'meta[property="og:video:secure_url"]'];
                    for (var k = 0; k < metas.length; k++) {
                        var m = document.querySelector(metas[k]);
                        if (m && m.content) { add(m.content); }
                    }
                    var vids = scope.querySelectorAll('video[src], video source[src]');
                    for (var n = 0; n < vids.length && n < 5; n++) { add(vids[n].getAttribute('src')); }
                }
                var video = '';
                if (yt) {
                    var a = scope.querySelector('a[href*="/watch?v="], a[href*="/shorts/"], a[href*="youtu.be/"]');
                    if (a) { video = abs(a.getAttribute('href')); }
                }
                return { t: document.title || '', i: out.slice(0, 30), v: video };
            })()
        """
    }
}
