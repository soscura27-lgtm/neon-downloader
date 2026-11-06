package com.deniscerri.ytdl.ui.explore

import android.net.Uri
import com.deniscerri.ytdl.util.extractors.ytdlp.YTDLPUtil
import java.util.Locale

/**
 * Reconoce qué se está viendo en el navegador de YouTube de la app y arma las direcciones
 * "limpias" que entiende yt-dlp: sin parámetros de seguimiento y con el formato correcto
 * para vídeos sueltos, playlists y Mix.
 */
object YoutubeUrls {

    const val HOME = "https://m.youtube.com/"
    const val SUBSCRIPTIONS = "https://m.youtube.com/feed/subscriptions"
    const val LIBRARY = "https://m.youtube.com/feed/library"
    const val HISTORY = "https://m.youtube.com/feed/history"
    const val SIGN_IN = "https://m.youtube.com/signin?action_handle_signin=true&app=m&next=%2F"

    /** Dirección con la que se leen las cookies de la sesión de Google. */
    const val COOKIES_URL = "https://www.youtube.com"

    /** Un Mix de YouTube no tiene final: se leen como máximo estos vídeos. */
    const val MIX_LIMIT = YTDLPUtil.MIX_MAX_ITEMS

    enum class Kind {
        /** Nada que se pueda descargar (inicio, búsqueda, suscripciones...). */
        NONE,

        /** Un vídeo suelto. */
        VIDEO,

        /** Un vídeo que se está viendo dentro de una playlist o un Mix. */
        VIDEO_IN_LIST,

        /** La página de una playlist. */
        PLAYLIST,

        /** Un canal (no se descarga desde el botón). */
        CHANNEL
    }

    data class Page(
        val kind: Kind,
        val videoId: String? = null,
        val listId: String? = null
    ) {
        val isDownloadable: Boolean
            get() = kind == Kind.VIDEO || kind == Kind.VIDEO_IN_LIST || kind == Kind.PLAYLIST

        val hasList: Boolean
            get() = listId != null && (kind == Kind.VIDEO_IN_LIST || kind == Kind.PLAYLIST)

        val isMix: Boolean
            get() = hasList && isMixId(listId!!)
    }

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val LIST_ID = Regex("[A-Za-z0-9_-]{2,80}")
    private val GOOGLE_HOST = Regex("(.+\\.)?google\\.[a-z]{2,3}(\\.[a-z]{2})?")
    private val OTHER_INTERNAL_SUFFIXES = listOf(
        "gstatic.com",
        "googleusercontent.com",
        "googleapis.com",
        "ggpht.com",
        "ytimg.com",
        "googlevideo.com",
        "youtube-nocookie.com"
    )

    fun isVideoId(id: String?): Boolean = id != null && VIDEO_ID.matches(id)

    /** Las listas que empiezan por RD son Mix (radios generadas a partir de un vídeo). */
    fun isMixId(listId: String): Boolean = listId.startsWith("RD")

    fun isYoutubeHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com")
    }

    /** Dominios que el navegador de la app puede abrir por sí mismo (YouTube y el inicio de sesión de Google). */
    fun isInternalHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return isYoutubeHost(h) ||
                GOOGLE_HOST.matches(h) ||
                OTHER_INTERNAL_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    fun parse(url: String?): Page {
        if (url.isNullOrBlank()) return Page(Kind.NONE)
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return Page(Kind.NONE)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return Page(Kind.NONE)
        if (!isYoutubeHost(host)) return Page(Kind.NONE)

        val path = uri.path.orEmpty()
        val list = uri.getQueryParameter("list")?.takeIf { LIST_ID.matches(it) }

        val rawVideo: String? = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            path == "/watch" -> uri.getQueryParameter("v")
            path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/')
            path.startsWith("/live/") -> path.removePrefix("/live/").substringBefore('/')
            path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore('/')
            else -> null
        }
        val video = rawVideo?.takeIf { isVideoId(it) }

        return when {
            video != null && list != null -> Page(Kind.VIDEO_IN_LIST, video, list)
            video != null -> Page(Kind.VIDEO, video)
            list != null && (path == "/playlist" || path == "/watch") -> Page(Kind.PLAYLIST, null, list)
            path.startsWith("/@") || path.startsWith("/channel/") ||
                    path.startsWith("/c/") || path.startsWith("/user/") -> Page(Kind.CHANNEL)
            else -> Page(Kind.NONE)
        }
    }

    fun videoUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    fun playlistUrl(listId: String): String = "https://www.youtube.com/playlist?list=$listId"

    fun mixUrl(videoId: String, listId: String): String =
        "https://www.youtube.com/watch?v=$videoId&list=$listId"

    /**
     * Dirección con la que se lee la lista completa. Las playlists normales usan /playlist?list=;
     * los Mix hay que pedirlos desde un vídeo (watch?v=..&list=RD..), si no YouTube no los entrega.
     */
    fun listUrl(page: Page): String? {
        val list = page.listId ?: return null
        if (isMixId(list)) {
            val seed = page.videoId
                ?: list.removePrefix("RD").takeIf { list.length == 13 && isVideoId(it) }
            if (seed != null) return mixUrl(seed, list)
        }
        return playlistUrl(list)
    }
}
