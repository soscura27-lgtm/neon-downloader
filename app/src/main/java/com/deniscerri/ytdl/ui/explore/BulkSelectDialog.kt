package com.deniscerri.ytdl.ui.explore

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.HistoryViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.downloads.QueuePlacementDialog
import com.deniscerri.ytdl.util.QueuePlacement
import com.deniscerri.ytdl.util.Extensions.enableFastScroll
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Selector masivo de vídeos de un canal, una playlist o un Mix.
 *
 * Lee la fuente en tiempo real (con botón Detener), permite buscar, filtrar por duración / fecha /
 * Shorts / ya descargados, ordenar, y elegir todos, los primeros N, un rango o vídeo por vídeo.
 * Al pulsar Descargar se usa el mismo flujo que "descargar todo" de la pantalla de inicio.
 */
class BulkSelectDialog : BottomSheetDialogFragment() {

    private lateinit var viewModel: BulkSelectViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var prefs: SharedPreferences
    private lateinit var listAdapter: BulkAdapter

    private lateinit var subtitle: TextView
    private lateinit var stopButton: MaterialButton
    private lateinit var tabs: ChipGroup
    private lateinit var progress: LinearProgressIndicator
    private lateinit var search: TextInputEditText
    private lateinit var sortChip: Chip
    private lateinit var durationChip: Chip
    private lateinit var dateChip: Chip
    private lateinit var shortsChip: Chip
    private lateinit var newChip: Chip
    private lateinit var selectButton: MaterialButton
    private lateinit var summary: TextView
    private lateinit var list: RecyclerView
    private lateinit var emptyBox: View
    private lateinit var emptyText: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var downloadButton: MaterialButton
    private lateinit var kindChip: Chip
    private lateinit var queueChip: Chip

    /** true mientras se pintan los datos: los cambios de las casillas no son del usuario. */
    private var rendering = false
    private var lastFilters: BulkSelectViewModel.Filters? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        viewModel = ViewModelProvider(this)[BulkSelectViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        val resultViewModel = ViewModelProvider(requireActivity())[ResultViewModel::class.java]
        val historyViewModel = ViewModelProvider(requireActivity())[HistoryViewModel::class.java]

        val args = requireArguments()
        val kind = runCatching {
            BulkSelectViewModel.SourceKind.valueOf(args.getString(ARG_KIND).orEmpty())
        }.getOrDefault(BulkSelectViewModel.SourceKind.PLAYLIST)
        val url = args.getString(ARG_URL).orEmpty()
        val tab = runCatching {
            BulkSelectViewModel.Tab.valueOf(args.getString(ARG_TAB).orEmpty())
        }.getOrDefault(BulkSelectViewModel.Tab.VIDEOS)

        viewModel.start(resultViewModel.repository, kind, url, tab) { historyViewModel.getAll() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_bulk_select, container, false)
    }

    override fun onStart() {
        super.onStart()
        // ocupa toda la pantalla: la lista puede tener miles de vídeos
        val sheet = (dialog as? BottomSheetDialog)
            ?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        sheet.requestLayout()
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        subtitle = view.findViewById(R.id.bulk_subtitle)
        stopButton = view.findViewById(R.id.bulk_stop)
        tabs = view.findViewById(R.id.bulk_tabs)
        progress = view.findViewById(R.id.bulk_progress)
        search = view.findViewById(R.id.bulk_search)
        sortChip = view.findViewById(R.id.bulk_chip_sort)
        durationChip = view.findViewById(R.id.bulk_chip_duration)
        dateChip = view.findViewById(R.id.bulk_chip_date)
        shortsChip = view.findViewById(R.id.bulk_chip_shorts)
        newChip = view.findViewById(R.id.bulk_chip_new)
        selectButton = view.findViewById(R.id.bulk_select_menu)
        summary = view.findViewById(R.id.bulk_summary)
        list = view.findViewById(R.id.bulk_list)
        emptyBox = view.findViewById(R.id.bulk_empty_box)
        emptyText = view.findViewById(R.id.bulk_empty)
        retryButton = view.findViewById(R.id.bulk_retry)
        downloadButton = view.findViewById(R.id.bulk_download)
        kindChip = view.findViewById(R.id.bulk_chip_kind)
        queueChip = view.findViewById(R.id.bulk_chip_queue)

        val hideThumbnails = prefs.getStringSet("hide_thumbnails", emptySet())!!.contains("home")
        listAdapter = BulkAdapter(hideThumbnails) { item -> viewModel.toggle(item.id) }
        list.adapter = listAdapter
        list.itemAnimator = null
        list.enableFastScroll()

        // si la vista se recrea (giro de pantalla), el buscador recupera lo que había escrito
        search.setText(viewModel.state.value.filters.query)
        search.doAfterTextChanged { text ->
            viewModel.updateFilters { it.copy(query = text?.toString().orEmpty()) }
        }
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(v)
                true
            } else {
                false
            }
        }

        tabs.setOnCheckedStateChangeListener { _, checkedIds ->
            if (rendering) return@setOnCheckedStateChangeListener
            val tab = when (checkedIds.firstOrNull()) {
                R.id.bulk_tab_shorts -> BulkSelectViewModel.Tab.SHORTS
                R.id.bulk_tab_live -> BulkSelectViewModel.Tab.LIVE
                else -> BulkSelectViewModel.Tab.VIDEOS
            }
            if (tab != viewModel.state.value.tab) viewModel.load(tab)
        }

        sortChip.setOnClickListener { askSort() }
        durationChip.setOnClickListener { askDuration() }
        dateChip.setOnClickListener { askDate() }
        shortsChip.setOnCheckedChangeListener { _, checked ->
            if (!rendering) viewModel.updateFilters { it.copy(hideShorts = checked) }
        }
        newChip.setOnCheckedChangeListener { _, checked ->
            if (!rendering) viewModel.updateFilters { it.copy(onlyNotDownloaded = checked) }
        }

        stopButton.setOnClickListener { viewModel.stop() }
        retryButton.setOnClickListener { viewModel.reload() }
        selectButton.setOnClickListener { showSelectMenu(it) }
        downloadButton.setOnClickListener { startDownload() }

        // el formato (vídeo / audio) se recuerda en los ajustes y es el mismo que en Explorar
        showKindChoice()
        kindChip.setOnClickListener {
            DownloadKind.choose(requireContext(), DownloadKind.load(prefs)) { kind ->
                DownloadKind.save(prefs, kind)
                showKindChoice()
            }
        }

        // el lugar elegido sobrevive a un giro de pantalla porque vive en el ViewModel
        showQueueChoice()
        queueChip.setOnClickListener {
            QueuePlacementDialog.choose(requireContext(), viewModel.queuePosition) { position ->
                viewModel.queuePosition = position
                showQueueChoice()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pintar el estado
    // ------------------------------------------------------------------------------------------

    private fun render(st: BulkSelectViewModel.UiState) {
        rendering = true
        try {
            val filters = st.filters

            // cabecera
            val status = when {
                st.loading -> getString(R.string.bulk_loading, st.total)
                st.stopped -> getString(R.string.bulk_stopped, st.total)
                else -> getString(R.string.bulk_loaded, st.total)
            }
            val lines = ArrayList<String>(3)
            lines.add(listOf(st.sourceName, status).filter { it.isNotBlank() }.joinToString(" · "))
            if (st.kind == BulkSelectViewModel.SourceKind.MIX) {
                lines.add(getString(R.string.bulk_mix_note, YoutubeUrls.MIX_LIMIT))
            }
            if (st.error != null && st.total > 0) {
                lines.add(getString(R.string.bulk_error, st.error))
            }
            subtitle.text = lines.joinToString("\n")
            stopButton.isVisible = st.loading
            progress.visibility = if (st.loading) View.VISIBLE else View.INVISIBLE

            // pestañas del canal
            tabs.isVisible = st.kind == BulkSelectViewModel.SourceKind.CHANNEL
            val tabId = when (st.tab) {
                BulkSelectViewModel.Tab.SHORTS -> R.id.bulk_tab_shorts
                BulkSelectViewModel.Tab.LIVE -> R.id.bulk_tab_live
                BulkSelectViewModel.Tab.VIDEOS -> R.id.bulk_tab_videos
            }
            if (tabs.checkedChipId != tabId) tabs.check(tabId)

            // filtros
            sortChip.text = getString(R.string.bulk_sort_chip, getString(sortLabel(filters.sort, st.kind)))
            highlight(sortChip, filters.sort != BulkSelectViewModel.Sort.SOURCE)
            durationChip.text = durationLabel(filters)
            highlight(durationChip, filters.hasDurationFilter)
            dateChip.isVisible = st.hasDates
            dateChip.text = getString(dateOptionLabel(filters.newerThanDays))
            highlight(dateChip, filters.newerThanDays != null)
            shortsChip.isVisible = st.tab != BulkSelectViewModel.Tab.SHORTS
            if (shortsChip.isChecked != filters.hideShorts) shortsChip.isChecked = filters.hideShorts
            newChip.isVisible = st.hasHistory
            if (newChip.isChecked != filters.onlyNotDownloaded) newChip.isChecked = filters.onlyNotDownloaded

            // lista
            val filtersChanged = lastFilters != null && lastFilters != filters
            lastFilters = filters
            listAdapter.selected = st.selected
            listAdapter.submitList(st.items) {
                listAdapter.refreshSelection()
                if (filtersChanged) list.scrollToPosition(0)
            }

            // mensaje de lista vacía
            val showEmpty = !st.loading && st.items.isEmpty()
            emptyBox.isVisible = showEmpty
            retryButton.isVisible = false
            if (showEmpty) {
                if (st.total > 0) {
                    emptyText.text = getString(R.string.bulk_empty)
                } else if (st.error != null) {
                    emptyText.text = getString(R.string.bulk_error, st.error)
                    retryButton.isVisible = true
                } else {
                    emptyText.text = getString(R.string.explore_list_empty)
                }
            }

            // resumen de la selección y botón de descarga
            val base = if (filters.isActive) {
                getString(R.string.bulk_summary_filtered, st.selected.size, st.items.size, st.total)
            } else {
                getString(R.string.bulk_summary, st.selected.size, st.total)
            }
            summary.text = if (st.selectAllMode) base + "\n" + getString(R.string.bulk_auto_select) else base

            val count = st.selected.size
            downloadButton.isEnabled = count > 0
            downloadButton.text = if (count > 0) {
                getString(R.string.bulk_download, count)
            } else {
                getString(R.string.bulk_download_empty)
            }
        } finally {
            rendering = false
        }
    }

    private fun sortLabel(sort: BulkSelectViewModel.Sort, kind: BulkSelectViewModel.SourceKind): Int {
        val isChannel = kind == BulkSelectViewModel.SourceKind.CHANNEL
        return when (sort) {
            BulkSelectViewModel.Sort.SOURCE -> if (isChannel) R.string.bulk_sort_newest else R.string.bulk_sort_list
            BulkSelectViewModel.Sort.REVERSED -> if (isChannel) R.string.bulk_sort_oldest else R.string.bulk_sort_list_reversed
            BulkSelectViewModel.Sort.VIEWS -> R.string.bulk_sort_views
            BulkSelectViewModel.Sort.LONGEST -> R.string.bulk_sort_longest
            BulkSelectViewModel.Sort.SHORTEST -> R.string.bulk_sort_shortest
            BulkSelectViewModel.Sort.TITLE -> R.string.bulk_sort_title
        }
    }

    private fun durationLabel(filters: BulkSelectViewModel.Filters): String {
        val min = filters.minMinutes
        val max = filters.maxMinutes
        return when {
            min != null && max != null -> getString(R.string.bulk_duration_between, min, max)
            min != null -> getString(R.string.bulk_duration_from, min)
            max != null -> getString(R.string.bulk_duration_upto, max)
            else -> getString(R.string.bulk_duration)
        }
    }

    private fun dateOptionLabel(days: Int?): Int {
        return DATE_OPTIONS.firstOrNull { it.first == days }?.second ?: R.string.bulk_date
    }

    /** Los filtros activos se resaltan con el color secundario. */
    private fun highlight(chip: Chip, active: Boolean) {
        val color = if (active) {
            MaterialColors.getColor(chip, com.google.android.material.R.attr.colorSecondaryContainer)
        } else {
            Color.TRANSPARENT
        }
        chip.chipBackgroundColor = ColorStateList.valueOf(color)
    }

    // ------------------------------------------------------------------------------------------
    // Diálogos de filtros y selección
    // ------------------------------------------------------------------------------------------

    private fun askSort() {
        val st = viewModel.state.value
        val sorts = BulkSelectViewModel.Sort.values()
        val labels = sorts.map { getString(sortLabel(it, st.kind)) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bulk_sort_dialog_title)
            .setSingleChoiceItems(labels, sorts.indexOf(st.filters.sort)) { dialog, which ->
                viewModel.updateFilters { it.copy(sort = sorts[which]) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askDate() {
        val current = viewModel.state.value.filters.newerThanDays
        val labels = DATE_OPTIONS.map { getString(it.second) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bulk_date_title)
            .setSingleChoiceItems(labels, DATE_OPTIONS.indexOfFirst { it.first == current }) { dialog, which ->
                viewModel.updateFilters { it.copy(newerThanDays = DATE_OPTIONS[which].first) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askDuration() {
        val filters = viewModel.state.value.filters
        val (content, minInput, maxInput) = numbersView(
            R.string.bulk_duration_note, R.string.bulk_min_minutes, R.string.bulk_max_minutes
        )
        filters.minMinutes?.let { minInput.setText(it.toString()) }
        filters.maxMinutes?.let { maxInput.setText(it.toString()) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bulk_duration_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.bulk_clear) { _, _ ->
                viewModel.updateFilters { it.copy(minMinutes = null, maxMinutes = null) }
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                var min = minInput.text?.toString()?.trim()?.toIntOrNull()
                var max = maxInput.text?.toString()?.trim()?.toIntOrNull()
                if (min != null && max != null && min > max) {
                    val tmp = min
                    min = max
                    max = tmp
                }
                viewModel.updateFilters { it.copy(minMinutes = min, maxMinutes = max) }
            }
            .show()
    }

    private fun askFirst() {
        val shown = viewModel.state.value.items.size
        val (content, countInput, _) = numbersView(R.string.bulk_first_note, R.string.bulk_first_hint, null)
        countInput.setText(minOf(50, shown.coerceAtLeast(1)).toString())

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bulk_first_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val count = countInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
                if (count > 0) viewModel.selectFirst(count)
            }
            .show()
    }

    private fun askRange() {
        val shown = viewModel.state.value.items.size
        val (content, fromInput, toInput) = numbersView(
            R.string.bulk_range_note, R.string.bulk_range_from, R.string.bulk_range_to
        )
        fromInput.setText("1")
        toInput.setText(minOf(50, shown.coerceAtLeast(1)).toString())

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bulk_range_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val from = fromInput.text?.toString()?.trim()?.toIntOrNull() ?: 1
                val to = toInput.text?.toString()?.trim()?.toIntOrNull() ?: shown
                if (from > 0 && to > 0) viewModel.selectRange(from, to)
            }
            .show()
    }

    private fun numbersView(
        note: Int?,
        hintA: Int,
        hintB: Int?
    ): Triple<View, TextInputEditText, TextInputEditText> {
        val content = layoutInflater.inflate(R.layout.dialog_bulk_numbers, null)
        content.findViewById<TextInputLayout>(R.id.bulk_input_a_layout).hint = getString(hintA)
        val layoutB = content.findViewById<TextInputLayout>(R.id.bulk_input_b_layout)
        if (hintB != null) {
            layoutB.hint = getString(hintB)
        } else {
            layoutB.isVisible = false
        }
        if (note != null) {
            val noteView = content.findViewById<TextView>(R.id.bulk_input_note)
            noteView.text = getString(note)
            noteView.isVisible = true
        }
        return Triple(
            content,
            content.findViewById(R.id.bulk_input_a),
            content.findViewById(R.id.bulk_input_b)
        )
    }

    private fun showSelectMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.bulk_select_menu, popup.menu)
        popup.menu.findItem(R.id.bulk_menu_new).isVisible = viewModel.state.value.hasHistory
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.bulk_menu_all -> viewModel.selectAllVisible()
                R.id.bulk_menu_first -> askFirst()
                R.id.bulk_menu_range -> askRange()
                R.id.bulk_menu_new -> viewModel.selectNotDownloaded()
                R.id.bulk_menu_invert -> viewModel.invertVisible()
                R.id.bulk_menu_deselect_visible -> viewModel.deselectVisible()
                R.id.bulk_menu_none -> viewModel.clearSelection()
            }
            true
        }
        popup.show()
    }

    // ------------------------------------------------------------------------------------------
    // Descarga
    // ------------------------------------------------------------------------------------------

    private fun showKindChoice() {
        val kind = DownloadKind.load(prefs)
        kindChip.text = DownloadKind.chipLabel(requireContext(), kind)
        kindChip.setChipIconResource(kind.iconRes)
        highlight(kindChip, kind != DownloadKind.VIDEO)
    }

    private fun showQueueChoice() {
        val position = viewModel.queuePosition
        queueChip.text = QueuePlacementDialog.chipLabel(requireContext(), position)
        highlight(queueChip, position > QueuePlacement.END)
    }

    /** Mismo flujo que "descargar todo" de la pantalla de inicio. */
    private fun startDownload() {
        val ids = viewModel.orderedSelection()
        if (ids.isEmpty()) return
        // un segundo toque mientras se prepara la lista no debe duplicar las descargas
        downloadButton.isEnabled = false

        // las descargas toman posiciones seguidas (P, P+1, P+2...) en el orden elegido; "al final" no pide nada
        QueuePlacement.request(viewModel.orderedSelectionUrls(), viewModel.queuePosition)

        val showCard = prefs.getBoolean("download_card", true)
        val kind = DownloadKind.load(prefs)
        downloadViewModel.turnResultItemsToProcessingDownloads(
            ids,
            downloadNow = !showCard,
            forcedType = kind.type,
            forcedAudioContainer = kind.container
        )
        if (showCard) {
            // la acción cierra esta ventana (popUpTo) y abre la de elegir formato
            findNavController().navigate(R.id.action_bulkSelectDialog_to_downloadMultipleBottomSheetDialog2)
        } else {
            Toast.makeText(requireContext(), getString(R.string.bulk_started, ids.size), Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun hideKeyboard(view: View) {
        val manager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    companion object {
        const val ARG_KIND = "kind"
        const val ARG_URL = "url"
        const val ARG_TAB = "tab"

        /** Opciones del filtro de fecha: días (null = cualquiera) y su texto. */
        private val DATE_OPTIONS: List<Pair<Int?, Int>> = listOf(
            null to R.string.bulk_date_any,
            7 to R.string.bulk_date_week,
            30 to R.string.bulk_date_month,
            90 to R.string.bulk_date_3months,
            365 to R.string.bulk_date_year,
            1095 to R.string.bulk_date_3years
        )
    }
}
