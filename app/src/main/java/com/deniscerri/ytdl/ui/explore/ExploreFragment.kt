package com.deniscerri.ytdl.ui.explore

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.core.view.isVisible
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
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
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Explorar: YouTube dentro de la app (buscador, inicio, suscripciones, biblioteca) con inicio de sesión
 * de Google, y un botón para descargar el vídeo, la playlist o el Mix que se está viendo.
 */
class ExploreFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private lateinit var state: ExploreViewModel
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var cookieViewModel: CookieViewModel
    private lateinit var progressViewModel: DownloadProgressViewModel

    private var webView: WebView? = null
    private lateinit var searchField: TextInputEditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var selectFab: ExtendedFloatingActionButton
    private lateinit var kindChip: Chip
    private lateinit var errorView: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var accountButton: MaterialButton

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var currentUrl: String = YoutubeUrls.HOME
    private var currentPage: YoutubeUrls.Page = YoutubeUrls.Page(YoutubeUrls.Kind.NONE)
    private var lastSignedIn: Boolean? = null
    private var signInBlockedShown = false

    private var fetchJob: Job? = null
    private var progressDialog: AlertDialog? = null

    private var adBlockScript: ScriptHandler? = null

    // si el formato se cambia desde el selector masivo, el botón de aquí se actualiza solo
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
        fab = view.findViewById(R.id.explore_fab)
        selectFab = view.findViewById(R.id.explore_fab_select)
        kindChip = view.findViewById(R.id.explore_chip_kind)
        errorView = view.findViewById(R.id.explore_error)
        fullscreenContainer = view.findViewById(R.id.explore_fullscreen)
        accountButton = view.findViewById(R.id.explore_account)

        setupSearch()
        view.findViewById<MaterialButton>(R.id.explore_subscriptions).setOnClickListener {
            load(YoutubeUrls.SUBSCRIPTIONS)
        }
        accountButton.setOnClickListener { showAccountMenu(it) }
        view.findViewById<MaterialButton>(R.id.explore_more).setOnClickListener { showMoreMenu(it) }
        view.findViewById<View>(R.id.explore_retry).setOnClickListener {
            errorView.visibility = View.GONE
            webView?.reload()
        }
        fab.setOnClickListener { onFabClick() }
        selectFab.setOnClickListener { openBulkSelect(currentPage) }
        kindChip.setOnClickListener {
            DownloadKind.choose(requireContext(), DownloadKind.load(prefs)) { kind ->
                DownloadKind.save(prefs, kind)
                showKind()
            }
        }
        showKind()
        prefs.registerOnSharedPreferenceChangeListener(kindListener)

        setupWebView(view.findViewById(R.id.explore_webview))
        setupBackHandling()
        observeDuplicates()
        observeDownloadLevel(view)
        updateAccountUi()
    }

    // ------------------------------------------------------------------------------------------
    // Navegador
    // ------------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
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
        }
        applyBrowserMode(wv)
        hideWebViewIdentity(wv.settings)
        applyAdBlock(wv)

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

    private fun buildUserAgent(context: Context, desktop: Boolean): String {
        val default = WebSettings.getDefaultUserAgent(context)
        val chrome = Regex("Chrome/([0-9.]+)").find(default)?.groupValues?.get(1) ?: "138.0.0.0"
        return if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        } else {
            // sin las marcas de WebView ("; wv" y "Version/4.0") para que parezca Chrome
            default.replace("; wv", "").replace(" Version/4.0", "")
        }
    }

    private fun applyBrowserMode(wv: WebView) {
        val desktop = state.desktopMode
        wv.settings.apply {
            userAgentString = buildUserAgent(wv.context, desktop)
            useWideViewPort = true
            loadWithOverviewMode = desktop
            setSupportZoom(desktop)
            builtInZoomControls = desktop
            displayZoomControls = false
        }
    }

    /**
     * Google rechaza el inicio de sesión cuando detecta un WebView incrustado. Se quitan las dos pistas
     * más claras: la cabecera X-Requested-With y la marca "Android WebView" de los client hints.
     */
    private fun hideWebViewIdentity(settings: WebSettings) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching {
                val current = WebSettingsCompat.getUserAgentMetadata(settings)
                val brands = current.brandVersionList.map { brand ->
                    if (brand.brand.contains("WebView", ignoreCase = true)) {
                        UserAgentMetadata.BrandVersion.Builder(brand).setBrand("Google Chrome").build()
                    } else {
                        brand
                    }
                }
                WebSettingsCompat.setUserAgentMetadata(
                    settings,
                    UserAgentMetadata.Builder(current).setBrandVersionList(brands).build()
                )
            }
        }
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
        if (!YoutubeUrls.isYoutubeHost(runCatching { Uri.parse(url).host }.getOrNull())) return
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
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            progress.visibility = View.INVISIBLE
            onUrlChanged(url ?: view?.url)
            CookieManager.getInstance().flush()
            checkSignIn()
            injectAdBlockFallback(view, url)
        }

        // YouTube cambia de página sin recargar: aquí se entera la app de cada cambio
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
            fab.hide()
            selectFab.hide()
            kindChip.isVisible = false
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        override fun getDefaultVideoPoster(): Bitmap? {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        updateFab()
    }

    /** true = la navegación ya está resuelta aquí; false = que la cargue el WebView. */
    private fun handleNavigation(uri: Uri, mainFrame: Boolean): Boolean {
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> Unit
            "about" -> return false
            // intent://, vnd.youtube://, market://... son intentos de abrir otra app
            else -> return true
        }
        val host = uri.host
        if (YoutubeUrls.isInternalHost(host)) {
            if (mainFrame && YoutubeUrls.isYoutubeHost(host) && uri.path == "/redirect") {
                // enlaces de salida de descripciones y comentarios
                uri.getQueryParameter("q")?.takeIf { it.isNotBlank() }?.let { openOutside(it) }
                return true
            }
            return false
        }
        // contenido de otros sitios dentro de la página (anuncios, marcos): que lo cargue el WebView
        if (!mainFrame) return false
        openOutside(uri.toString())
        return true
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
        updateFab()
        if (!signInBlockedShown && (url.contains("/signin/rejected") || url.contains("disallowed_useragent"))) {
            signInBlockedShown = true
            showSignInBlocked()
        }
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
    // Menús
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
        popup.menu.findItem(R.id.explore_menu_forward).isEnabled = webView?.canGoForward() == true
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.explore_menu_home -> load(YoutubeUrls.HOME)
                R.id.explore_menu_library -> load(YoutubeUrls.LIBRARY)
                R.id.explore_menu_history -> load(YoutubeUrls.HISTORY)
                R.id.explore_menu_forward -> webView?.goForward()
                R.id.explore_menu_reload -> webView?.reload()
                R.id.explore_menu_desktop -> toggleDesktopMode()
                R.id.explore_menu_adblock -> toggleAdBlock()
                R.id.explore_menu_ask_position -> {
                    val ask = !prefs.getBoolean(PREF_ASK_POSITION, true)
                    prefs.edit().putBoolean(PREF_ASK_POSITION, ask).apply()
                }
                R.id.explore_menu_copy -> copyLink(currentUrl)
                R.id.explore_menu_external -> openInExternalBrowser(currentUrl)
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

    private fun updateAccountUi() {
        if (view == null) return
        val attr = if (isSignedIn()) {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorOnSurface
        }
        accountButton.iconTint = ColorStateList.valueOf(MaterialColors.getColor(accountButton, attr))
    }

    /** Se llama al terminar cada página: detecta cuando acaba de iniciarse sesión. */
    private fun checkSignIn() {
        val now = isSignedIn()
        val before = lastSignedIn
        lastSignedIn = now
        updateAccountUi()
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
                updateAccountUi()
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
    // Descargas
    // ------------------------------------------------------------------------------------------

    /** Pone en el botón el formato elegido: "Formato: Audio MP3". */
    private fun showKind() {
        if (view == null) return
        val kind = DownloadKind.load(prefs)
        kindChip.text = DownloadKind.chipLabel(requireContext(), kind)
        kindChip.setChipIconResource(kind.iconRes)
    }

    private fun updateFab() {
        if (view == null) return
        val page = currentPage
        if (customView != null) {
            fab.hide()
            selectFab.hide()
            kindChip.isVisible = false
            return
        }

        // el botón "Formato" aparece siempre que se pueda descargar algo de la página
        kindChip.isVisible = page.isDownloadable || page.canChooseVideos

        if (page.isDownloadable) {
            fab.text = getString(
                when {
                    page.kind == YoutubeUrls.Kind.PLAYLIST && page.isMix -> R.string.explore_download_mix
                    page.kind == YoutubeUrls.Kind.PLAYLIST -> R.string.explore_download_playlist
                    else -> R.string.explore_download
                }
            )
            fab.show()
        } else {
            fab.hide()
        }

        // "Elegir vídeos": canales, playlists y Mix (también al ver un vídeo que pertenece a una lista)
        if (page.canChooseVideos) selectFab.show() else selectFab.hide()
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

    private fun onFabClick() {
        val page = currentPage
        val videoId = page.videoId
        when {
            page.kind == YoutubeUrls.Kind.VIDEO && videoId != null -> downloadVideo(YoutubeUrls.videoUrl(videoId))
            page.kind == YoutubeUrls.Kind.PLAYLIST -> downloadList(page)
            page.kind == YoutubeUrls.Kind.VIDEO_IN_LIST -> askVideoOrList(page)
            else -> toast(R.string.explore_not_downloadable)
        }
    }

    private fun listLabel(page: YoutubeUrls.Page): String {
        return if (page.isMix) {
            getString(R.string.explore_download_whole_mix, YoutubeUrls.MIX_LIMIT)
        } else {
            getString(R.string.explore_download_whole_playlist)
        }
    }

    private fun askVideoOrList(page: YoutubeUrls.Page) {
        val videoId = page.videoId ?: return
        val options = arrayOf(
            getString(R.string.explore_download_only_video),
            listLabel(page),
            getString(R.string.bulk_choose_from_list)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.explore_download_choose_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> downloadVideo(YoutubeUrls.videoUrl(videoId))
                    1 -> downloadList(page)
                    else -> openBulkSelect(page)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Pulsación larga sobre una miniatura o un enlace: descargar sin abrir el vídeo. */
    private fun onLongPress(wv: WebView): Boolean {
        val type = wv.hitTestResult.type
        if (type != WebView.HitTestResult.SRC_ANCHOR_TYPE &&
            type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        ) {
            return false
        }
        val handler = Handler(Looper.getMainLooper()) { message ->
            if (isAdded && view != null) {
                showLinkActions(message.data.getString("url"))
            }
            true
        }
        wv.requestFocusNodeHref(handler.obtainMessage())
        return true
    }

    private fun showLinkActions(href: String?) {
        if (href.isNullOrBlank()) return
        val page = YoutubeUrls.parse(href)
        if (!page.isDownloadable && !page.canChooseVideos) return

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val videoId = page.videoId
        if (videoId != null) {
            labels.add(getString(R.string.explore_download_video))
            actions.add { downloadVideo(YoutubeUrls.videoUrl(videoId)) }
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
        labels.add(getString(R.string.explore_copy_link))
        actions.add { copyLink(href) }

        MaterialAlertDialogBuilder(requireContext())
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

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
                // el audio se eligió de forma explícita con el botón "Formato": se pone en cola ya, sin hoja de opciones
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
        if (fetchJob?.isActive == true) return
        askQueuePosition { position -> readAndDownloadList(page, listUrl, position) }
    }

    private fun readAndDownloadList(page: YoutubeUrls.Page, listUrl: String, position: Int) {
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
                        if (page.isMix) {
                            // un Mix solo se lee bien con yt-dlp y con tope (no tiene final)
                            resultViewModel.repository.getYoutubeMix(listUrl, resetResults = true)
                        } else {
                            resultViewModel.repository.getResultsFromSource(listUrl, resetResults = true)
                        }
                    }
                    val ids = items.map { it.id }.filter { it != 0L }
                    progressDialog?.dismiss()

                    if (ids.isEmpty()) {
                        showListProblem(getString(R.string.explore_list_empty))
                    } else {
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

    private fun copyLink(url: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        toast(R.string.explore_link_copied)
    }

    private fun toast(resId: Int) {
        context?.let { Toast.makeText(it, resId, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        updateAccountUi()
    }

    override fun onPause() {
        webView?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroyView() {
        if (this::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(kindListener)
        fetchJob?.cancel()
        fetchJob = null
        progressDialog?.dismiss()
        progressDialog = null
        exitFullscreen()
        webView?.let { wv ->
            adBlockScript = null
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
    }
}
