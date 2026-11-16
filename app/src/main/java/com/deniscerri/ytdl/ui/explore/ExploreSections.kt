package com.deniscerri.ytdl.ui.explore

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale

/**
 * Apartados de Explorar: secciones de YouTube, películas y series gratis y legales que se ven dentro de la
 * app, las apps de streaming instaladas (Netflix, Disney+...) y GIF y stickers.
 *
 * Solo se enlazan servicios oficiales. En los que protegen sus vídeos (DRM) se puede ver, pero no descargar;
 * el botón de descarga lo avisa.
 */
object ExploreSections {

    enum class Group(val titleRes: Int) {
        YOUTUBE(R.string.sections_group_youtube),
        MOVIES(R.string.sections_group_movies),
        APPS(R.string.sections_group_apps),
        FUN(R.string.sections_group_fun)
    }

    class Section(
        val id: String,
        val group: Group,
        /** Nombre traducible (0 = usar [name], que es una marca). */
        val titleRes: Int = 0,
        val name: String? = null,
        val subtitleRes: Int,
        val iconRes: Int,
        /** Se abre en el navegador de Explorar. */
        val url: String? = null,
        /** Se abre la app con el primero de estos paquetes que esté instalado. */
        val packages: List<String> = emptyList(),
        /** Línea pequeña de arriba: "Gratis · se puede descargar", "Solo ver"... (0 = nada). */
        val badgeRes: Int = 0
    ) {
        fun title(context: Context): String = if (titleRes != 0) context.getString(titleRes) else name.orEmpty()
    }

    /** Lo que se sabe de una web abierta en Explorar, para el botón de descarga. */
    class Site(
        val name: String,
        /** Protege sus vídeos (DRM): se ven, pero yt-dlp no los puede descargar. */
        val watchOnly: Boolean = false,
        /** Web de GIF y stickers: se guarda la imagen, no un vídeo. */
        val images: Boolean = false
    )

    val all: List<Section> = listOf(
        // YouTube
        Section("yt_home", Group.YOUTUBE, R.string.sections_yt_home, subtitleRes = R.string.sections_yt_home_sub,
            iconRes = R.drawable.ic_home, url = YoutubeUrls.HOME),
        Section("yt_shorts", Group.YOUTUBE, name = "Shorts", subtitleRes = R.string.sections_yt_shorts_sub,
            iconRes = R.drawable.baseline_swipe_24, url = YoutubeUrls.SHORTS),
        Section("yt_subs", Group.YOUTUBE, R.string.explore_subscriptions, subtitleRes = R.string.sections_yt_subs_sub,
            iconRes = R.drawable.ic_subscriptions, url = YoutubeUrls.SUBSCRIPTIONS),
        Section("yt_library", Group.YOUTUBE, R.string.explore_library, subtitleRes = R.string.sections_yt_library_sub,
            iconRes = R.drawable.ic_video_library, url = YoutubeUrls.LIBRARY),
        Section("yt_history", Group.YOUTUBE, R.string.explore_history, subtitleRes = R.string.sections_yt_history_sub,
            iconRes = R.drawable.ic_history, url = YoutubeUrls.HISTORY),
        Section("yt_later", Group.YOUTUBE, R.string.sections_yt_later, subtitleRes = R.string.sections_yt_later_sub,
            iconRes = R.drawable.ic_clock, url = YoutubeUrls.WATCH_LATER),
        Section("yt_liked", Group.YOUTUBE, R.string.sections_yt_liked, subtitleRes = R.string.sections_yt_liked_sub,
            iconRes = R.drawable.ic_thumb_up, url = YoutubeUrls.LIKED),
        Section("yt_music", Group.YOUTUBE, name = "YouTube Music", subtitleRes = R.string.sections_yt_music_sub,
            iconRes = R.drawable.ic_music, url = YoutubeUrls.MUSIC),

        // Películas y series (gratis y legales)
        Section("yt_movies", Group.MOVIES, R.string.sections_yt_movies, subtitleRes = R.string.sections_yt_movies_sub,
            iconRes = R.drawable.ic_movie, url = YoutubeUrls.MOVIES, badgeRes = R.string.sections_badge_watch),
        Section("pluto", Group.MOVIES, name = "Pluto TV", subtitleRes = R.string.sections_pluto_sub,
            iconRes = R.drawable.baseline_live_tv_24, url = "https://pluto.tv/", badgeRes = R.string.sections_badge_free_download),
        Section("tubi", Group.MOVIES, name = "Tubi", subtitleRes = R.string.sections_tubi_sub,
            iconRes = R.drawable.ic_tv, url = "https://tubitv.com/", badgeRes = R.string.sections_badge_free_download),
        Section("plex", Group.MOVIES, name = "Plex", subtitleRes = R.string.sections_plex_sub,
            iconRes = R.drawable.ic_theaters, url = "https://watch.plex.tv/", badgeRes = R.string.sections_badge_free_watch),
        Section("vix", Group.MOVIES, name = "ViX", subtitleRes = R.string.sections_vix_sub,
            iconRes = R.drawable.ic_tv, url = "https://vix.com/", badgeRes = R.string.sections_badge_free_watch),
        Section("filminlatino", Group.MOVIES, name = "FilminLatino", subtitleRes = R.string.sections_filminlatino_sub,
            iconRes = R.drawable.ic_theaters, url = "https://www.filminlatino.mx/", badgeRes = R.string.sections_badge_free_watch),
        Section("archive_movies", Group.MOVIES, R.string.sections_archive_movies, subtitleRes = R.string.sections_archive_movies_sub,
            iconRes = R.drawable.ic_account_balance, url = "https://archive.org/details/feature_films",
            badgeRes = R.string.sections_badge_free_download),
        Section("archive_tv", Group.MOVIES, R.string.sections_archive_tv, subtitleRes = R.string.sections_archive_tv_sub,
            iconRes = R.drawable.ic_account_balance, url = "https://archive.org/details/classic_tv",
            badgeRes = R.string.sections_badge_free_download),

        // Apps de streaming instaladas (se abren en su app; sin cuenta no hay nada que ver en la web)
        Section("app_netflix", Group.APPS, name = "Netflix", subtitleRes = R.string.sections_app_sub,
            iconRes = R.drawable.ic_open_in_new, packages = listOf("com.netflix.mediaclient")),
        Section("app_disney", Group.APPS, name = "Disney+", subtitleRes = R.string.sections_app_sub,
            iconRes = R.drawable.ic_open_in_new, packages = listOf("com.disney.disneyplus")),
        Section("app_prime", Group.APPS, name = "Prime Video", subtitleRes = R.string.sections_app_sub,
            iconRes = R.drawable.ic_open_in_new, packages = listOf("com.amazon.avod.thirdpartyclient")),
        Section("app_max", Group.APPS, name = "Max", subtitleRes = R.string.sections_app_sub,
            iconRes = R.drawable.ic_open_in_new, packages = listOf("com.wbd.stream", "com.hbo.hbonow")),
        Section("app_crunchyroll", Group.APPS, name = "Crunchyroll", subtitleRes = R.string.sections_app_sub,
            iconRes = R.drawable.ic_open_in_new, packages = listOf("com.crunchyroll.crunchyroid")),
        Section("app_paramount", Group.APPS, name = "Paramount+", subtitleRes = R.string.sections_app_sub,
            iconRes = R.drawable.ic_open_in_new, packages = listOf("com.cbs.ott", "com.cbs.app")),
        Section("app_vix", Group.APPS, name = "ViX", subtitleRes = R.string.sections_app_sub,
            iconRes = R.drawable.ic_open_in_new, packages = listOf("com.univision.prendetv")),
        Section("app_pluto", Group.APPS, name = "Pluto TV", subtitleRes = R.string.sections_app_sub,
            iconRes = R.drawable.ic_open_in_new, packages = listOf("tv.pluto.android")),
        Section("app_tubi", Group.APPS, name = "Tubi", subtitleRes = R.string.sections_app_sub,
            iconRes = R.drawable.ic_open_in_new, packages = listOf("com.tubitv")),
        Section("app_plex", Group.APPS, name = "Plex", subtitleRes = R.string.sections_app_sub,
            iconRes = R.drawable.ic_open_in_new, packages = listOf("com.plexapp.android")),

        // GIF y stickers
        Section("tenor", Group.FUN, R.string.sections_tenor, subtitleRes = R.string.sections_tenor_sub,
            iconRes = R.drawable.ic_gif, url = "https://tenor.com/", badgeRes = R.string.sections_badge_send),
        Section("giphy_stickers", Group.FUN, R.string.sections_giphy, subtitleRes = R.string.sections_giphy_sub,
            iconRes = R.drawable.ic_emoji, url = "https://giphy.com/stickers", badgeRes = R.string.sections_badge_send)
    )

    /** Paquetes de las apps de streaming: el manifiesto los declara en <queries> para poder verlos. */
    val appPackages: List<String> get() = all.flatMap { it.packages }

    private val SITES: List<Pair<String, Site>> = listOf(
        "pluto.tv" to Site("Pluto TV"),
        "tubitv.com" to Site("Tubi"),
        "archive.org" to Site("Internet Archive"),
        "plex.tv" to Site("Plex", watchOnly = true),
        "vix.com" to Site("ViX", watchOnly = true),
        "filminlatino.mx" to Site("FilminLatino", watchOnly = true),
        "netflix.com" to Site("Netflix", watchOnly = true),
        "disneyplus.com" to Site("Disney+", watchOnly = true),
        "primevideo.com" to Site("Prime Video", watchOnly = true),
        "max.com" to Site("Max", watchOnly = true),
        "hbomax.com" to Site("Max", watchOnly = true),
        "crunchyroll.com" to Site("Crunchyroll", watchOnly = true),
        "paramountplus.com" to Site("Paramount+", watchOnly = true),
        "tenor.com" to Site("Tenor", images = true),
        "giphy.com" to Site("GIPHY", images = true)
    )

    /** Datos de la web abierta (null si no es uno de los servicios conocidos). */
    fun siteFor(host: String?): Site? {
        val h = host?.lowercase(Locale.ROOT) ?: return null
        return SITES.firstOrNull { (suffix, _) -> h == suffix || h.endsWith(".$suffix") }?.second
    }

    /** Las secciones que se pueden mostrar ahora: las apps solo si están instaladas. */
    fun available(context: Context): List<Section> {
        val pm = context.packageManager
        return all.filter { section ->
            section.packages.isEmpty() || section.packages.any { isInstalled(pm, it) }
        }
    }

    /** Intent para abrir la app de la sección (null si no hay ninguna instalada). */
    fun launchIntent(context: Context, section: Section): Intent? {
        val pm = context.packageManager
        return section.packages.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean {
        return pm.getLaunchIntentForPackage(pkg) != null
    }

    // ------------------------------------------------------------------------------------------
    // Hoja de apartados
    // ------------------------------------------------------------------------------------------

    /** Muestra la hoja con los apartados agrupados. [onPick] recibe la sección elegida (la hoja ya se cerró). */
    fun show(context: Context, onPick: (Section) -> Unit): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_explore_sections, null)
        val list = view.findViewById<RecyclerView>(R.id.sections_list)

        val rows = ArrayList<Row>()
        val sections = available(context)
        Group.values().forEach { group ->
            val inGroup = sections.filter { it.group == group }
            if (inGroup.isEmpty()) return@forEach
            rows.add(Row.Header(context.getString(group.titleRes)))
            inGroup.forEach { rows.add(Row.Item(it)) }
        }

        val widthDp = context.resources.configuration.screenWidthDp
        val span = (widthDp / 170).coerceIn(2, 5)
        val manager = GridLayoutManager(context, span)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (rows[position] is Row.Header) span else 1
        }
        list.layoutManager = manager
        list.adapter = SectionsAdapter(rows) { section ->
            dialog.dismiss()
            onPick(section)
        }

        dialog.setContentView(view)
        dialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
        return dialog
    }

    private sealed class Row {
        class Header(val title: String) : Row()
        class Item(val section: Section) : Row()
    }

    private class SectionsAdapter(
        private val rows: List<Row>,
        private val onPick: (Section) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) HEADER else ITEM

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == HEADER) {
                object : RecyclerView.ViewHolder(
                    inflater.inflate(R.layout.item_explore_section_header, parent, false)
                ) {}
            } else {
                object : RecyclerView.ViewHolder(
                    inflater.inflate(R.layout.item_explore_section, parent, false)
                ) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val context = holder.itemView.context
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.title
                is Row.Item -> {
                    val section = row.section
                    val root = holder.itemView
                    root.findViewById<ImageView>(R.id.section_icon).setImageResource(section.iconRes)
                    root.findViewById<TextView>(R.id.section_title).text = section.title(context)
                    root.findViewById<TextView>(R.id.section_subtitle).setText(section.subtitleRes)
                    val badge = root.findViewById<TextView>(R.id.section_badge)
                    if (section.badgeRes != 0) {
                        badge.setText(section.badgeRes)
                        badge.visibility = View.VISIBLE
                    } else {
                        badge.visibility = View.GONE
                    }
                    root.contentDescription = section.title(context)
                    root.setOnClickListener { onPick(section) }
                }
            }
        }

        companion object {
            private const val HEADER = 0
            private const val ITEM = 1
        }
    }
}
