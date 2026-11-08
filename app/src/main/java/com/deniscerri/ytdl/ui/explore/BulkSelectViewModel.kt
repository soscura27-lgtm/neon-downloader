package com.deniscerri.ytdl.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deniscerri.ytdl.database.models.HistoryItem
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.repository.ResultRepository
import com.deniscerri.ytdl.util.QueuePlacement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Cerebro del selector masivo de vídeos: lee un canal, una playlist o un Mix en tiempo real, aplica
 * búsqueda, filtros y orden, y lleva la cuenta de lo que el usuario ha seleccionado.
 *
 * Los vídeos se guardan en la lista de resultados de la app (con su id) según llegan, así que lo
 * seleccionado se puede convertir directamente en descargas aunque la lectura no haya terminado.
 */
class BulkSelectViewModel : ViewModel() {

    enum class SourceKind { CHANNEL, PLAYLIST, MIX }

    /** Pestañas de un canal de YouTube. */
    enum class Tab(val path: String) {
        VIDEOS("videos"),
        SHORTS("shorts"),
        LIVE("streams")
    }

    enum class Sort { SOURCE, REVERSED, VIEWS, LONGEST, SHORTEST, TITLE }

    data class Filters(
        val query: String = "",
        val minMinutes: Int? = null,
        val maxMinutes: Int? = null,
        val newerThanDays: Int? = null,
        val hideShorts: Boolean = false,
        val onlyNotDownloaded: Boolean = false,
        val sort: Sort = Sort.SOURCE
    ) {
        val hasDurationFilter: Boolean
            get() = minMinutes != null || maxMinutes != null

        /** Hay algún filtro que oculte vídeos (el orden no cuenta). */
        val isActive: Boolean
            get() = query.isNotBlank() || hasDurationFilter || newerThanDays != null ||
                    hideShorts || onlyNotDownloaded
    }

    data class UiState(
        val kind: SourceKind = SourceKind.PLAYLIST,
        val tab: Tab = Tab.VIDEOS,
        /** Vídeos que pasan los filtros, ya ordenados. */
        val items: List<ResultItem> = emptyList(),
        /** Vídeos leídos hasta ahora (con o sin filtros). */
        val total: Int = 0,
        val selected: Set<Long> = emptySet(),
        /** "Todos" pulsado mientras aún se lee: lo que vaya llegando se selecciona solo. */
        val selectAllMode: Boolean = false,
        val loading: Boolean = false,
        val stopped: Boolean = false,
        val error: String? = null,
        val sourceName: String = "",
        val hasDates: Boolean = false,
        val hasHistory: Boolean = false,
        val filters: Filters = Filters()
    )

    /** Datos de cada vídeo calculados una sola vez para que filtrar y ordenar sea rápido. */
    private class Meta(
        val text: String,
        val seconds: Int,
        val videoKey: String,
        val isShort: Boolean
    )

    private val lock = Any()
    private val recomputeLock = Any()
    private val all = ArrayList<ResultItem>()
    private val meta = HashMap<Long, Meta>()
    private var hasDates = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Lugar de la cola para lo que se descargue desde aquí: [QueuePlacement.END] (al final, normal),
     * [QueuePlacement.NEXT] (los siguientes en empezar) o una posición concreta (2, 3...).
     */
    @Volatile
    var queuePosition: Int = QueuePlacement.END

    private var repository: ResultRepository? = null
    private var sourceUrl = ""
    private var started = false
    private var loadJob: Job? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var downloadedKeys: Set<String> = emptySet()

    private val refresh = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        // Mientras llegan vídeos se refresca la lista como mucho unas 6 veces por segundo
        viewModelScope.launch(Dispatchers.Default) {
            refresh.collect {
                recompute()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lectura de la fuente
    // ------------------------------------------------------------------------------------------

    fun start(
        repository: ResultRepository,
        kind: SourceKind,
        url: String,
        initialTab: Tab,
        history: () -> List<HistoryItem>
    ) {
        if (started) return
        started = true
        this.repository = repository
        this.sourceUrl = url
        _state.update { it.copy(kind = kind) }

        viewModelScope.launch {
            val keys = withContext(Dispatchers.IO) {
                runCatching { history().map { videoKey(it.url) }.toSet() }.getOrDefault(emptySet())
            }
            downloadedKeys = keys
            _state.update { it.copy(hasHistory = keys.isNotEmpty()) }
            requestRefresh()
        }
        load(initialTab)
    }

    /** Lee de nuevo la fuente (otra pestaña del canal, o reintentar tras un error). */
    fun load(tab: Tab) {
        val repo = repository ?: return
        loadJob?.cancel()
        val myGeneration = ++generation

        synchronized(recomputeLock) {
            synchronized(lock) {
                all.clear()
                meta.clear()
                hasDates = false
            }
            _state.update {
                it.copy(
                    tab = tab,
                    items = emptyList(),
                    total = 0,
                    selected = emptySet(),
                    selectAllMode = false,
                    loading = true,
                    stopped = false,
                    error = null,
                    sourceName = "",
                    hasDates = false
                )
            }
        }

        val kind = _state.value.kind
        val url = if (kind == SourceKind.CHANNEL) sourceUrl.trimEnd('/') + "/" + tab.path else sourceUrl

        loadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.streamList(url, approximateDates = kind == SourceKind.CHANNEL) { chunk ->
                        if (myGeneration == generation) onChunk(chunk)
                    }
                }
                if (myGeneration == generation) {
                    _state.update { it.copy(loading = false, selectAllMode = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (myGeneration == generation) {
                    _state.update {
                        it.copy(loading = false, selectAllMode = false, error = e.message ?: e.toString())
                    }
                }
            }
            if (myGeneration == generation) {
                withContext(Dispatchers.Default) { recompute() }
            }
        }
    }

    fun reload() = load(_state.value.tab)

    /** Detiene la lectura: lo leído hasta ahora se puede seguir eligiendo. */
    fun stop() {
        if (!_state.value.loading) return
        generation++
        loadJob?.cancel()
        loadJob = null
        _state.update { it.copy(loading = false, stopped = true, selectAllMode = false) }
        viewModelScope.launch(Dispatchers.Default) { recompute() }
    }

    /** Llega un grupo de vídeos nuevos (se ejecuta en el hilo que lee yt-dlp). */
    private fun onChunk(chunk: List<ResultItem>) {
        if (chunk.isEmpty()) return
        val current = _state.value
        val autoSelect = current.selectAllMode
        val filters = current.filters
        val cutoff = cutoffSeconds(filters)
        val autoSelected = ArrayList<Long>()

        synchronized(lock) {
            for (item in chunk) {
                val m = buildMeta(item)
                meta[item.id] = m
                all.add(item)
                if (item.uploadTimestamp > 0) hasDates = true
                if (autoSelect && passes(item, m, filters, cutoff, downloadedKeys)) {
                    autoSelected.add(item.id)
                }
            }
        }

        if (autoSelected.isNotEmpty()) {
            _state.update { st ->
                if (st.selectAllMode) st.copy(selected = st.selected + autoSelected) else st
            }
        }
        requestRefresh()
    }

    private fun requestRefresh() {
        refresh.tryEmit(Unit)
    }

    /** Vuelve a calcular la lista visible a partir de lo leído y los filtros. */
    private fun recompute() {
        synchronized(recomputeLock) {
            val filters = _state.value.filters
            val items: List<ResultItem>
            val metas: Map<Long, Meta>
            val dates: Boolean
            synchronized(lock) {
                items = ArrayList(all)
                metas = HashMap(meta)
                dates = hasDates
            }
            val visible = filterAndSort(items, metas, filters)
            val name = sourceName(items)
            _state.update {
                it.copy(items = visible, total = items.size, sourceName = name, hasDates = dates)
            }
        }
    }

    private fun sourceName(items: List<ResultItem>): String {
        val first = items.firstOrNull() ?: return ""
        return if (_state.value.kind == SourceKind.CHANNEL) {
            first.author
        } else {
            first.playlistTitle.ifBlank { first.author }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Filtros y orden
    // ------------------------------------------------------------------------------------------

    fun updateFilters(change: (Filters) -> Filters) {
        _state.update { it.copy(filters = change(it.filters)) }
        requestRefresh()
    }

    private fun cutoffSeconds(filters: Filters): Long {
        val days = filters.newerThanDays ?: return Long.MIN_VALUE
        return System.currentTimeMillis() / 1000 - days * 86400L
    }

    private fun passes(
        item: ResultItem,
        m: Meta,
        filters: Filters,
        cutoff: Long,
        downloaded: Set<String>
    ): Boolean {
        if (filters.hideShorts && m.isShort) return false

        if (filters.hasDurationFilter) {
            if (m.seconds < 0) return false
            val min = filters.minMinutes
            val max = filters.maxMinutes
            if (min != null && m.seconds < min * 60) return false
            if (max != null && m.seconds > max * 60) return false
        }

        // sin fecha conocida no se puede descartar: solo se oculta lo que se sabe que es más antiguo
        if (filters.newerThanDays != null && item.uploadTimestamp > 0 && item.uploadTimestamp < cutoff) {
            return false
        }

        if (filters.onlyNotDownloaded && downloaded.contains(m.videoKey)) return false

        if (filters.query.isNotBlank()) {
            for (token in tokens(filters.query)) {
                if (token.exclude) {
                    if (m.text.contains(token.word)) return false
                } else if (!m.text.contains(token.word)) {
                    return false
                }
            }
        }
        return true
    }

    private class Token(val word: String, val exclude: Boolean)

    /** "gato -perro" -> debe contener "gato" y no contener "perro". */
    private fun tokens(query: String): List<Token> {
        return normalize(query).split(WHITESPACE)
            .filter { it.isNotEmpty() && it != "-" }
            .map { word ->
                if (word.startsWith("-")) Token(word.substring(1), true) else Token(word, false)
            }
    }

    private fun filterAndSort(
        items: List<ResultItem>,
        metas: Map<Long, Meta>,
        filters: Filters
    ): List<ResultItem> {
        val cutoff = cutoffSeconds(filters)
        val downloaded = downloadedKeys
        val visible = if (filters.isActive) {
            items.filter { item ->
                val m = metas[item.id] ?: return@filter true
                passes(item, m, filters, cutoff, downloaded)
            }
        } else {
            items
        }
        return sorted(visible, metas, filters.sort)
    }

    private fun sorted(items: List<ResultItem>, metas: Map<Long, Meta>, sort: Sort): List<ResultItem> {
        fun seconds(item: ResultItem) = metas[item.id]?.seconds ?: -1
        return when (sort) {
            Sort.SOURCE -> items
            Sort.REVERSED -> items.asReversed()
            Sort.VIEWS -> items.sortedByDescending { it.viewCount }
            Sort.LONGEST -> items.sortedByDescending { seconds(it) }
            // los de duración desconocida (directos) quedan al final
            Sort.SHORTEST -> items.sortedBy { seconds(it).let { s -> if (s < 0) Int.MAX_VALUE else s } }
            Sort.TITLE -> {
                val collator = Collator.getInstance(Locale.getDefault())
                items.sortedWith { a, b -> collator.compare(a.title, b.title) }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Selección
    // ------------------------------------------------------------------------------------------

    fun toggle(id: Long) {
        _state.update { st ->
            val selected = HashSet(st.selected)
            if (!selected.add(id)) selected.remove(id)
            st.copy(selected = selected, selectAllMode = false)
        }
    }

    /** Añade a la selección todos los vídeos que se ven ahora. Si aún se lee, también los que lleguen. */
    fun selectAllVisible() {
        _state.update { st ->
            st.copy(
                selected = st.selected + st.items.map { it.id },
                selectAllMode = st.loading
            )
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet(), selectAllMode = false) }
    }

    fun deselectVisible() {
        _state.update { st ->
            st.copy(selected = st.selected - st.items.map { it.id }.toSet(), selectAllMode = false)
        }
    }

    fun invertVisible() {
        _state.update { st ->
            val selected = HashSet(st.selected)
            st.items.forEach { if (!selected.remove(it.id)) selected.add(it.id) }
            st.copy(selected = selected, selectAllMode = false)
        }
    }

    /** Sustituye la selección por los primeros [count] vídeos de la lista visible. */
    fun selectFirst(count: Int) {
        _state.update { st ->
            st.copy(selected = st.items.take(count.coerceAtLeast(0)).map { it.id }.toSet(), selectAllMode = false)
        }
    }

    /** Sustituye la selección por las posiciones [from]..[to] (desde 1) de la lista visible. */
    fun selectRange(from: Int, to: Int) {
        _state.update { st ->
            val start = (minOf(from, to) - 1).coerceAtLeast(0)
            val end = maxOf(from, to).coerceAtMost(st.items.size)
            val ids = if (start < end) st.items.subList(start, end).map { it.id } else emptyList()
            st.copy(selected = ids.toSet(), selectAllMode = false)
        }
    }

    /** Añade los visibles que aún no están en el historial de descargas. */
    fun selectNotDownloaded() {
        val downloaded = downloadedKeys
        _state.update { st ->
            val ids = st.items.filter { !downloaded.contains(videoKey(it.url)) }.map { it.id }
            st.copy(selected = st.selected + ids, selectAllMode = false)
        }
    }

    /**
     * Ids de todo lo seleccionado, en el orden elegido (por ejemplo del más antiguo al más reciente),
     * incluidos los que ahora queden ocultos por un filtro.
     */
    fun orderedSelection(): List<Long> = orderedSelectionItems().map { it.id }

    /** Direcciones de lo seleccionado, en el mismo orden que [orderedSelection]. */
    fun orderedSelectionUrls(): List<String> = orderedSelectionItems().map { it.url }

    private fun orderedSelectionItems(): List<ResultItem> {
        val st = _state.value
        val items: List<ResultItem>
        val metas: Map<Long, Meta>
        synchronized(lock) {
            items = all.filter { st.selected.contains(it.id) }
            metas = HashMap(meta)
        }
        return sorted(items, metas, st.filters.sort)
    }

    // ------------------------------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------------------------------

    private fun buildMeta(item: ResultItem): Meta {
        val seconds = parseSeconds(item.duration)
        return Meta(
            text = normalize("${item.title} ${item.author}"),
            seconds = seconds,
            videoKey = videoKey(item.url),
            isShort = item.url.contains("/shorts/") || seconds in 1..SHORT_MAX_SECONDS
        )
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 150L
        private const val SHORT_MAX_SECONDS = 60

        private val WHITESPACE = Regex("\\s+")
        private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
        private val VIDEO_KEY = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})")

        /** Minúsculas y sin acentos, para que "canción" y "cancion" coincidan. */
        fun normalize(text: String): String {
            val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
            return DIACRITICS.replace(decomposed, "").lowercase(Locale.ROOT).trim()
        }

        /** "1:05:07" / "05:07" / "7" -> segundos; -1 si no se conoce ("-1"). */
        fun parseSeconds(duration: String): Int {
            if (duration.isBlank()) return -1
            var total = 0
            for (part in duration.trim().split(":")) {
                val n = part.toIntOrNull() ?: return -1
                if (n < 0) return -1
                total = total * 60 + n
            }
            return total
        }

        /** Identificador del vídeo (para compararlo con el historial); si no es de YouTube, la propia dirección. */
        fun videoKey(url: String): String = VIDEO_KEY.find(url)?.groupValues?.get(1) ?: url
    }
}
