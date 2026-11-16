package com.deniscerri.ytdl.ui.lyrics

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.util.Extensions.toStringTimeStamp
import com.deniscerri.ytdl.util.extractors.LrcLibUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Letra: una pantalla con solo la letra de lo que suena, para leerla, copiarla entera o elegir qué parte copiar.
 *
 * Sigue lo que suena en Explorar (la página de YouTube) o en "Escuchar" (el reproductor de audio): al pasar a la
 * siguiente canción del Mix o de la playlist, la letra cambia sola. Si la letra trae tiempos se marca la línea que va
 * sonando y, al tocar una línea, la canción va a esa parte.
 *
 * Las letras vienen de YouTube Music (las de su app, de Musixmatch o LyricFind) y de LRCLIB (ver [LyricsFinder]); si
 * la encontrada no es la buena, "Buscar otra letra" deja elegir otra, y esa se recuerda para esa canción.
 */
class LyricsDialog : AppCompatDialogFragment() {

    private lateinit var prefs: SharedPreferences
    private var nowPlaying: NowPlaying? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var songView: TextView
    private lateinit var artistView: TextView
    private lateinit var sourceView: TextView
    private lateinit var offsetRow: View
    private lateinit var offsetValue: TextView
    private lateinit var linesView: RecyclerView
    private lateinit var textScroll: ScrollView
    private lateinit var textView: TextView
    private lateinit var stateView: View
    private lateinit var loadingView: View
    private lateinit var stateIcon: ImageView
    private lateinit var messageView: TextView
    private lateinit var stateAction: MaterialButton
    private lateinit var selectHint: TextView
    private lateinit var copyButton: MaterialButton
    private lateinit var selectButton: MaterialButton
    private lateinit var adapter: LinesAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null

    /** La canción que suena (la que se sigue). */
    private var track: LyricsTrack? = null

    /** De qué canción es la letra que se enseña (null si se buscó a mano sin que sonara nada). */
    private var lyricsKey: String? = null
    private var lyrics: LyricsFinder.Lyrics? = null

    /** De dónde viene lo que suena (la página o "Escuchar"). */
    private var source: LyricsSource? = null

    /** En la página empezó otro vídeo: se espera a que su título se asiente antes de buscar. */
    private var pendingKey: String? = null
    private var pendingSince = 0L

    private var screen: Screen? = null
    private var textMode = false
    private var searching = false
    private var firstScrollPending = false
    private var lastUserScroll = 0L
    private var openedAt = 0L
    private var keepingScreenOn = false
    private var textSizeSp = DEFAULT_TEXT_SP

    private enum class Screen { WAITING, LOADING, LYRICS, NOT_FOUND, OFFLINE, NOTHING }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------------------------------

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = AppCompatDialog(requireContext(), R.style.ThemeOverlay_App_FullScreenDialog)
        // la ventana ocupa toda la pantalla y el contenido se aparta de las barras del sistema
        dialog.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_lyrics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        textSizeSp = prefs.getFloat(PREF_TEXT_SIZE, DEFAULT_TEXT_SP).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        toolbar = view.findViewById(R.id.lyrics_toolbar)
        songView = view.findViewById(R.id.lyrics_song)
        artistView = view.findViewById(R.id.lyrics_artist)
        sourceView = view.findViewById(R.id.lyrics_source)
        offsetRow = view.findViewById(R.id.lyrics_offset_row)
        offsetValue = view.findViewById(R.id.lyrics_offset_value)
        linesView = view.findViewById(R.id.lyrics_lines)
        textScroll = view.findViewById(R.id.lyrics_text_scroll)
        textView = view.findViewById(R.id.lyrics_text)
        stateView = view.findViewById(R.id.lyrics_state)
        loadingView = view.findViewById(R.id.lyrics_loading)
        stateIcon = view.findViewById(R.id.lyrics_state_icon)
        messageView = view.findViewById(R.id.lyrics_message)
        stateAction = view.findViewById(R.id.lyrics_state_action)
        selectHint = view.findViewById(R.id.lyrics_select_hint)
        copyButton = view.findViewById(R.id.lyrics_copy)
        selectButton = view.findViewById(R.id.lyrics_select)

        setupToolbar()
        setupLines(view)
        setupOffset(view)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        copyButton.setOnClickListener { copyLyrics() }
        selectButton.setOnClickListener { toggleTextMode() }
        showScreen(Screen.WAITING)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (nowPlaying == null) {
            nowPlaying = NowPlaying(requireContext(), requireArguments().getBoolean(ARG_PREFER_MUSIC))
        }
        openedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        nowPlaying?.release()
        nowPlaying = null
        setKeepScreenOn(false)
        super.onStop()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        loadJob?.cancel()
        loadJob = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------------------------------
    // Seguir lo que suena
    // ------------------------------------------------------------------------------------------

    private fun tick() {
        val playing = nowPlaying ?: return
        val active = playing.active()
        source = active
        val current = active?.track()
        val now = SystemClock.elapsedRealtime()

        when {
            current == null -> {
                pendingKey = null
                if (track == null && lyrics == null && !searching) {
                    // todavía se está conectando con "Escuchar": no se dice "no suena nada" antes de tiempo
                    val waiting = playing.connecting || now - openedAt < WAIT_MS
                    showScreen(if (waiting) Screen.WAITING else Screen.NOTHING)
                }
            }
            current.key != track?.key -> {
                // en la página el título cambia un poco después que el vídeo: se espera a que se asiente
                val settle = if (playing.isPage(active) && track != null) SETTLE_MS else 0L
                if (pendingKey != current.key) {
                    pendingKey = current.key
                    pendingSince = now
                }
                if (now - pendingSince >= settle) {
                    pendingKey = null
                    track = current
                    load(current)
                }
            }
            // la misma canción; puede traer datos nuevos (la duración llega cuando empieza a sonar)
            else -> track = current
        }

        val shown = lyrics
        val following = shown != null && shown.synced && current != null &&
                lyricsKey == current.key && screen == Screen.LYRICS
        adapter.setFollow(following)
        if (following && active != null) updateCurrentLine(active.positionMs() - offsetFor(lyricsKey))
        setKeepScreenOn(active?.isPlaying() == true && screen == Screen.LYRICS)
    }

    /** Busca la letra de [target] (al momento si ya se encontró antes). */
    private fun load(target: LyricsTrack, force: Boolean = false) {
        loadJob?.cancel()
        searching = false
        lyrics = null
        lyricsKey = target.key
        textMode = false
        offsetRow.isVisible = false
        val (song, artist) = TitleParser.parse(target.title, target.artist)
        showHeader(song.ifBlank { target.title }, artist)
        sourceView.isVisible = false

        if (!force) {
            LyricsFinder.cached(target)?.let {
                showLyrics(it, target.key)
                return
            }
        }
        showScreen(Screen.LOADING)
        val app = requireContext().applicationContext
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { LyricsFinder.find(app, target, force) } }
            if (lyricsKey != target.key || !isAdded) return@launch
            val found = result.getOrNull()
            when {
                result.isFailure -> showScreen(Screen.OFFLINE)
                found == null -> showScreen(Screen.NOT_FOUND)
                else -> showLyrics(found, target.key)
            }
        }
    }

    private fun showHeader(song: String, artist: String) {
        songView.text = song
        songView.isVisible = song.isNotBlank()
        artistView.text = artist
        artistView.isVisible = artist.isNotBlank()
    }

    private fun showLyrics(found: LyricsFinder.Lyrics, key: String?) {
        lyrics = found
        lyricsKey = key
        val fallback = track?.let { TitleParser.parse(it.title, it.artist) }
        showHeader(found.title.ifBlank { fallback?.first.orEmpty() }, found.artist.ifBlank { fallback?.second.orEmpty() })
        sourceView.text = sourceText(found)
        sourceView.isVisible = true
        toolbar.menu.findItem(R.id.lyrics_menu_offset)?.isVisible = found.synced
        if (!found.synced) offsetRow.isVisible = false
        updateOffsetText()

        adapter.submit(found.lines, found.synced)
        textView.text = found.text
        applyLinesPadding()
        linesView.scrollToPosition(0)
        textScroll.scrollTo(0, 0)
        lastUserScroll = 0L
        firstScrollPending = found.synced
        showScreen(Screen.LYRICS, force = true)
    }

    private fun sourceText(found: LyricsFinder.Lyrics): String {
        var origin = getString(
            when (found.source) {
                LyricsFinder.Source.YOUTUBE_MUSIC -> R.string.neon_lyrics_source_ytm
                LyricsFinder.Source.LRCLIB -> R.string.neon_lyrics_source_lrclib
            }
        )
        if (found.sourceNote.isNotBlank()) origin += " · " + found.sourceNote
        return getString(if (found.synced) R.string.neon_lyrics_source_synced else R.string.neon_lyrics_source_plain, origin)
    }

    private fun showScreen(target: Screen, force: Boolean = false) {
        if (screen == target && !force) return
        screen = target
        val hasLyrics = target == Screen.LYRICS
        linesView.isVisible = hasLyrics && !textMode
        textScroll.isVisible = hasLyrics && textMode
        selectHint.isVisible = hasLyrics && textMode
        stateView.isVisible = !hasLyrics
        copyButton.isEnabled = hasLyrics
        selectButton.isEnabled = hasLyrics
        selectButton.setText(if (textMode) R.string.neon_lyrics_lines else R.string.neon_lyrics_select)
        selectButton.setIconResource(if (textMode) R.drawable.ic_lyrics else R.drawable.ic_select_all)
        toolbar.menu.findItem(R.id.lyrics_menu_share)?.isEnabled = hasLyrics
        toolbar.menu.findItem(R.id.lyrics_menu_reload)?.isEnabled = track != null
        if (hasLyrics) return

        val busy = target == Screen.WAITING || target == Screen.LOADING
        loadingView.isVisible = busy
        stateIcon.isVisible = !busy
        messageView.setText(
            when (target) {
                Screen.WAITING -> R.string.neon_lyrics_waiting
                Screen.LOADING -> R.string.neon_lyrics_loading
                Screen.NOT_FOUND -> R.string.neon_lyrics_not_found
                Screen.OFFLINE -> R.string.neon_lyrics_offline
                else -> R.string.neon_lyrics_nothing
            }
        )
        stateAction.isVisible = !busy
        stateAction.setText(
            when (target) {
                Screen.OFFLINE -> R.string.neon_lyrics_retry
                Screen.NOT_FOUND -> R.string.neon_lyrics_search
                else -> R.string.neon_lyrics_search_song
            }
        )
        stateAction.setOnClickListener {
            val current = track
            if (target == Screen.OFFLINE && current != null) load(current) else showSearchDialog()
        }
        if (target == Screen.NOTHING || (target == Screen.WAITING && track == null)) {
            showHeader("", "")
            sourceView.isVisible = false
        }
    }

    // ------------------------------------------------------------------------------------------
    // Líneas y la que suena
    // ------------------------------------------------------------------------------------------

    private fun setupLines(root: View) {
        adapter = LinesAdapter(
            activeColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimary),
            normalColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface),
            onClick = { index -> seekToLine(index) }
        )
        adapter.textSizeSp = textSizeSp
        linesView.layoutManager = LinearLayoutManager(requireContext())
        linesView.adapter = adapter
        linesView.itemAnimator = null
        linesView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // el usuario mueve la letra con el dedo: se deja de seguir la línea un momento
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) lastUserScroll = SystemClock.elapsedRealtime()
            }
        })
        linesView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) linesView.post { applyLinesPadding() }
        }
    }

    /** Con tiempos, la primera y la última línea también pueden quedar en el centro de la pantalla. */
    private fun applyLinesPadding() {
        val height = linesView.height
        if (lyrics?.synced == true && height > 0) {
            val pad = (height * 0.42f).toInt()
            linesView.setPadding(0, pad, 0, pad)
        } else {
            linesView.setPadding(0, dp(8), 0, dp(32))
        }
    }

    private fun updateCurrentLine(position: Long) {
        val lines = lyrics?.lines ?: return
        var lo = 0
        var hi = lines.size - 1
        var index = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= position) {
                index = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (index == adapter.current && !firstScrollPending) return
        adapter.setCurrent(index)
        if (textMode) return
        if (firstScrollPending) {
            firstScrollPending = false
            jumpTo(index)
            return
        }
        if (index >= 0 && SystemClock.elapsedRealtime() - lastUserScroll > USER_SCROLL_PAUSE_MS) centerOn(index)
    }

    /** Al abrir: directamente a la línea que suena, sin recorrer toda la letra. */
    private fun jumpTo(index: Int) {
        val manager = linesView.layoutManager as? LinearLayoutManager ?: return
        if (index < 0) {
            manager.scrollToPositionWithOffset(0, 0)
            return
        }
        manager.scrollToPositionWithOffset(index, 0)
        linesView.post { centerOn(index) }
    }

    /** Deja la línea en el centro de la pantalla, deslizando suave. */
    private fun centerOn(index: Int) {
        val manager = linesView.layoutManager ?: return
        val scroller = object : LinearSmoothScroller(requireContext()) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float = 90f / displayMetrics.densityDpi
        }
        scroller.targetPosition = index
        manager.startSmoothScroll(scroller)
    }

    /** Se tocó una línea: la canción va a esa parte. */
    private fun seekToLine(index: Int) {
        val shown = lyrics ?: return
        if (!shown.synced) return
        val line = shown.lines.getOrNull(index) ?: return
        val playing = source ?: return
        if (lyricsKey != track?.key) return
        playing.seekTo(line.timeMs + offsetFor(lyricsKey))
        lastUserScroll = 0L
        adapter.setCurrent(index)
        centerOn(index)
    }

    // ------------------------------------------------------------------------------------------
    // Ajustar el tiempo
    // ------------------------------------------------------------------------------------------

    private fun setupOffset(root: View) {
        root.findViewById<View>(R.id.lyrics_offset_minus).setOnClickListener { changeOffset(-OFFSET_STEP_MS) }
        root.findViewById<View>(R.id.lyrics_offset_plus).setOnClickListener { changeOffset(OFFSET_STEP_MS) }
        root.findViewById<View>(R.id.lyrics_offset_done).setOnClickListener { offsetRow.isVisible = false }
        offsetValue.setOnClickListener { changeOffset(-offsetFor(lyricsKey)) }
        updateOffsetText()
    }

    private fun offsetFor(key: String?): Long = key?.let { offsets[it] } ?: 0L

    private fun changeOffset(delta: Long) {
        val key = lyricsKey ?: return
        offsets[key] = (offsetFor(key) + delta).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        updateOffsetText()
        firstScrollPending = true
    }

    private fun updateOffsetText() {
        val seconds = offsetFor(lyricsKey) / 1000.0
        val value = if (seconds == 0.0) String.format(Locale.getDefault(), "%.1f", seconds)
        else String.format(Locale.getDefault(), "%+.1f", seconds)
        offsetValue.text = getString(R.string.neon_lyrics_offset_value, value)
    }

    // ------------------------------------------------------------------------------------------
    // Copiar, elegir texto, compartir, tamaño
    // ------------------------------------------------------------------------------------------

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.inflateMenu(R.menu.lyrics_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.lyrics_menu_share -> shareLyrics()
                R.id.lyrics_menu_search -> showSearchDialog()
                R.id.lyrics_menu_bigger -> changeTextSize(TEXT_STEP_SP)
                R.id.lyrics_menu_smaller -> changeTextSize(-TEXT_STEP_SP)
                R.id.lyrics_menu_offset -> offsetRow.isVisible = !offsetRow.isVisible
                R.id.lyrics_menu_reload -> track?.let { load(it, force = true) }
            }
            true
        }
    }

    private fun copyLyrics() {
        val shown = lyrics ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val label = shown.title.ifBlank { getString(R.string.neon_lyrics_title) }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, shown.text))
        // desde Android 13 el propio sistema avisa de que se copió
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(requireContext(), R.string.neon_lyrics_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /** De las líneas al texto (para mantener pulsado y elegir qué parte copiar) y al revés. */
    private fun toggleTextMode() {
        if (lyrics == null) return
        textMode = !textMode
        showScreen(Screen.LYRICS, force = true)
        if (textMode) {
            textScroll.scrollTo(0, 0)
        } else {
            firstScrollPending = lyrics?.synced == true
        }
    }

    private fun shareLyrics() {
        val shown = lyrics ?: return
        val header = listOf(shown.title, shown.artist).filter { it.isNotBlank() }.joinToString(" — ")
        val text = if (header.isBlank()) shown.text else header + "\n\n" + shown.text
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, shown.title)
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.neon_lyrics_share))) }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun changeTextSize(delta: Float) {
        textSizeSp = (textSizeSp + delta).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
        prefs.edit().putFloat(PREF_TEXT_SIZE, textSizeSp).apply()
        adapter.textSizeSp = textSizeSp
        adapter.notifyDataSetChanged()
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        firstScrollPending = lyrics?.synced == true
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (keepingScreenOn == on) return
        keepingScreenOn = on
        val window = dialog?.window ?: return
        // mientras suena con la letra delante, la pantalla no se apaga sola
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------------------------------
    // Buscar otra letra a mano
    // ------------------------------------------------------------------------------------------

    private fun defaultQuery(): String {
        lyrics?.let { return "${it.artist} ${it.title}".trim() }
        val current = track ?: return ""
        val (song, artist) = TitleParser.parse(current.title, current.artist)
        return "$artist $song".trim()
    }

    private fun showSearchDialog() {
        val context = requireContext()
        val input = EditText(context).apply {
            setText(defaultQuery())
            setSelectAllOnFocus(true)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            hint = getString(R.string.neon_lyrics_search_hint)
        }
        val container = FrameLayout(context).apply {
            val margin = dp(20)
            setPadding(margin, margin / 2, margin, 0)
            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.neon_lyrics_search)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.search) { _, _ -> runSearch(input.text.toString()) }
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss()
                runSearch(input.text.toString())
                true
            } else {
                false
            }
        }
        dialog.show()
    }

    private fun runSearch(raw: String) {
        val query = raw.trim()
        if (query.isBlank()) return
        val before = screen
        loadJob?.cancel()
        searching = true
        showScreen(Screen.LOADING)
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { LyricsFinder.searchLrclib(query) } }
            searching = false
            if (!isAdded) return@launch
            val results = result.getOrNull().orEmpty()
            // lo que había antes vuelve a verse (si no se elige nada, no se pierde)
            if (lyrics != null) showScreen(Screen.LYRICS, force = true) else if (before != null) showScreen(before, force = true)
            when {
                result.isFailure -> toast(R.string.neon_lyrics_offline)
                results.isEmpty() -> toast(R.string.neon_lyrics_no_results)
                else -> showResults(results)
            }
        }
    }

    private fun showResults(results: List<LrcLibUtil.Lyrics>) {
        val context = requireContext()
        val playingSeconds = track?.durationSec ?: 0
        val rows = object : ArrayAdapter<LrcLibUtil.Lyrics>(context, 0, results) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.lyrics_search_result_item, parent, false)
                val item = getItem(position)!!
                row.findViewById<TextView>(R.id.result_title).text = item.trackName
                row.findViewById<TextView>(R.id.result_subtitle).text =
                    listOf(item.artistName, item.albumName).filter { it.isNotBlank() }.joinToString(" • ")
                val info = mutableListOf(getString(if (item.isSynced) R.string.lyrics_synced else R.string.lyrics_plain))
                if (item.durationSec > 0) {
                    var runtime = (item.durationSec * 1000L).toStringTimeStamp()
                    if (playingSeconds > 0) {
                        val diff = item.durationSec - playingSeconds
                        runtime += if (diff == 0) " (=)" else " (${if (diff > 0) "+" else ""}${diff}s)"
                    }
                    info.add(runtime)
                }
                row.findViewById<TextView>(R.id.result_info).text = info.joinToString(" • ")
                row.findViewById<TextView>(R.id.result_preview).apply {
                    val preview = (if (item.isSynced) item.synced.map { it.text } else item.plain.lines())
                        .filter { it.isNotBlank() }
                        .take(2)
                        .joinToString("\n")
                    text = preview
                    isVisible = preview.isNotBlank()
                }
                return row
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.neon_lyrics_results)
            .setAdapter(rows) { _, which -> choose(results[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun choose(choice: LrcLibUtil.Lyrics) {
        val current = track
        val chosen = LyricsFinder.choose(requireContext().applicationContext, current, choice, current?.durationSec ?: 0)
        textMode = false
        showLyrics(chosen, current?.key)
        if (current != null) toast(R.string.neon_lyrics_chosen)
    }

    // ------------------------------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------------------------------

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Las líneas de la letra; la que suena va resaltada y algo más grande. */
    private class LinesAdapter(
        @ColorInt private val activeColor: Int,
        @ColorInt private val normalColor: Int,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<LinesAdapter.Holder>() {

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        private var lines: List<LyricsFinder.Line> = emptyList()
        private var synced = false
        private var follow = false
        var textSizeSp = DEFAULT_TEXT_SP

        var current = -1
            private set

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newLines: List<LyricsFinder.Line>, isSynced: Boolean) {
            lines = newLines
            synced = isSynced
            current = -1
            notifyDataSetChanged()
        }

        /** Con algo sonando se marca la línea actual; sin nada, toda la letra se ve igual. */
        @SuppressLint("NotifyDataSetChanged")
        fun setFollow(value: Boolean) {
            if (follow == value) return
            follow = value
            notifyDataSetChanged()
        }

        fun setCurrent(index: Int) {
            if (index == current) return
            val old = current
            current = index
            if (old in lines.indices) notifyItemChanged(old, PAYLOAD_HIGHLIGHT)
            if (index in lines.indices) notifyItemChanged(index, PAYLOAD_HIGHLIGHT)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyrics_line, parent, false) as TextView
            val holder = Holder(view)
            view.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (synced && position != RecyclerView.NO_POSITION) onClick(position)
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder, position, animate = false)

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) =
            bind(holder, position, animate = payloads.isNotEmpty())

        override fun getItemCount(): Int = lines.size

        private fun bind(holder: Holder, position: Int, animate: Boolean) {
            val view = holder.text
            val line = lines[position]
            val blank = line.text.isBlank()
            view.text = if (blank && synced) "♪" else line.text
            // en una letra sin tiempos, las líneas vacías separan estrofas: un hueco más pequeño
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (blank && !synced) textSizeSp * 0.45f else textSizeSp)
            view.isClickable = synced
            val highlight = synced && follow
            val isCurrent = highlight && position == current
            val alpha = if (!highlight || isCurrent) 1f else INACTIVE_ALPHA
            val scale = if (isCurrent) ACTIVE_SCALE else 1f
            view.setTextColor(if (isCurrent) activeColor else normalColor)
            view.animate().cancel()
            if (animate) {
                view.animate().alpha(alpha).scaleX(scale).scaleY(scale).setDuration(HIGHLIGHT_MS).start()
            } else {
                view.alpha = alpha
                view.scaleX = scale
                view.scaleY = scale
            }
        }
    }

    companion object {
        private const val TAG = "neon_lyrics"
        private const val ARG_PREFER_MUSIC = "prefer_music"
        private const val PREF_TEXT_SIZE = "neon_lyrics_text_size"

        private const val TICK_MS = 200L
        private const val WAIT_MS = 1500L
        private const val SETTLE_MS = 800L
        private const val USER_SCROLL_PAUSE_MS = 3500L

        private const val DEFAULT_TEXT_SP = 22f
        private const val MIN_TEXT_SP = 14f
        private const val MAX_TEXT_SP = 36f
        private const val TEXT_STEP_SP = 2f

        private const val OFFSET_STEP_MS = 500L
        private const val MAX_OFFSET_MS = 30_000L

        private const val INACTIVE_ALPHA = 0.42f
        private const val ACTIVE_SCALE = 1.06f
        private const val HIGHLIGHT_MS = 220L
        private const val PAYLOAD_HIGHLIGHT = "highlight"

        /** Ajuste de tiempo elegido para cada canción (mientras la app siga abierta). */
        private val offsets = HashMap<String, Long>()

        /**
         * Abre la letra de lo que suena. [preferMusic]: si suenan a la vez la página de Explorar y "Escuchar", se
         * sigue "Escuchar" (si no, la página).
         */
        fun show(manager: FragmentManager, preferMusic: Boolean) {
            if (manager.isStateSaved || manager.isDestroyed) return
            if (manager.findFragmentByTag(TAG) != null) return
            LyricsDialog()
                .apply { arguments = bundleOf(ARG_PREFER_MUSIC to preferMusic) }
                .show(manager, TAG)
        }
    }
}
