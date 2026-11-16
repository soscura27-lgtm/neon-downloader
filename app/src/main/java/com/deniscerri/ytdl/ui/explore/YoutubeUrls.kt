package com.deniscerri.ytdl.ui.explore

import android.net.Uri
import com.deniscerri.ytdl.util.extractors.ytdlp.YTDLPUtil
import java.util.Locale

/**
 * Reconoce qué se está viendo en el navegador de YouTube de la app y arma las direcciones
 * "limpias" que entiende yt-dlp: sin parámetros de seguimiento y con el formato correcto
 * para vídeos sueltos, Shorts, directos, clips, playlists, Mix, álbumes de YouTube Music y
 * publicaciones con imágenes.
 */
object YoutubeUrls {

    const val HOME = "https://m.youtube.com/"
    const val SHORTS = "https://m.youtube.com/shorts/"
    const val SUBSCRIPTIONS = "https://m.youtube.com/feed/subscriptions"
    const val LIBRARY = "https://m.youtube.com/feed/library"
    const val HISTORY = "https://m.youtube.com/feed/history"
    const val WATCH_LATER = "https://m.youtube.com/playlist?list=WL"
    const val LIKED = "https://m.youtube.com/playlist?list=LL"
    const val MOVIES = "https://m.youtube.com/feed/storefront"
    const val MUSIC = "https://music.youtube.com/"
    const val SIGN_IN = "https://m.youtube.com/signin?action_handle_signin=true&app=m&next=%2F"

    /** Dirección con la que se leen las cookies de la sesión de Google. */
    const val COOKIES_URL = "https://www.youtube.com"

    /** Un Mix de YouTube no tiene final: se leen como máximo estos vídeos. */
    const val MIX_LIMIT = YTDLPUtil.MIX_MAX_ITEMS

    enum class Kind {
        /** Nada que se pueda descargar (inicio, búsqueda, suscripciones...). */
        NONE,

        /** Un vídeo suelto (también un Short, un directo o una canción de YouTube Music). */
        VIDEO,

        /** Un vídeo que se está viendo dentro de una playlist o un Mix. */
        VIDEO_IN_LIST,

        /** La página de una playlist. */
        PLAYLIST,

        /** Un canal: no tiene botón de descarga directa, pero se pueden elegir sus vídeos. */
        CHANNEL,

        /** Un clip (un trozo de un vídeo): yt-dlp descarga solo ese trozo. */
        CLIP,

        /** Un álbum de YouTube Music (browse/MPREb...). */
        ALBUM,

        /** Una publicación de un canal: imágenes, GIF o un vídeo compartido. */
        POST
    }

    data class Page(
        val kind: Kind,
        val videoId: String? = null,
        val listId: String? = null,
        /** "@usuario", "channel/UC..." , "c/nombre" o "user/nombre" (solo en canales). */
        val channelPath: String? = null,
        /** Pestaña del canal que se está viendo: "videos", "shorts" o "streams" (null = ninguna en concreto). */
        val channelTab: String? = null,
        /** Dirección exacta para yt-dlp cuando no basta con un id (clips y álbumes). */
        val directUrl: String? = null,
        /** Id de la publicación (solo en publicaciones). */
        val postId: String? = null,
        val isShort: Boolean = false,
        val isLive: Boolean = false,
        /** Se está viendo en YouTube Music. */
        val isMusic: Boolean = false
    ) {
        val isDownloadable: Boolean
            get() = kind == Kind.VIDEO || kind == Kind.VIDEO_IN_LIST || kind == Kind.PLAYLIST ||
                    kind == Kind.CLIP || kind == Kind.ALBUM

        val hasList: Boolean
            get() = listId != null && (kind == Kind.VIDEO_IN_LIST || kind == Kind.PLAYLIST)

        val isMix: Boolean
            get() = hasList && isMixId(listId!!)

        /** Se pueden elegir vídeos sueltos de aquí (canal, playlist o Mix). */
        val canChooseVideos: Boolean
            get() = (kind == Kind.CHANNEL && channelPath != null) || hasList

        /** Hay un vídeo concreto en pantalla (se puede escuchar, ver sus comentarios o bajar su miniatura). */
        val hasVideo: Boolean
            get() = videoId != null && (kind == Kind.VIDEO || kind == Kind.VIDEO_IN_LIST)
    }

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val LIST_ID = Regex("[A-Za-z0-9_-]{2,80}")
    private val CLIP_ID = Regex("[A-Za-z0-9_-]{10,80}")
    private val POST_ID = Regex("[A-Za-z0-9_-]{10,80}")
    private val ALBUM_ID = Regex("MPREb_[A-Za-z0-9_-]{5,80}")
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

    /** YouTube y los dominios de Google que usa (inicio de sesión, imágenes, vídeo). */
    fun isInternalHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return isYoutubeHost(h) ||
                GOOGLE_HOST.matches(h) ||
                OTHER_INTERNAL_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    fun isYoutubeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return isYoutubeHost(runCatching { Uri.parse(url).host }.getOrNull())
    }

    fun parse(url: String?): Page {
        if (url.isNullOrBlank()) return Page(Kind.NONE)
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return Page(Kind.NONE)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return Page(Kind.NONE)
        if (!isYoutubeHost(host)) return Page(Kind.NONE)

        val music = host == "music.youtube.com"
        val path = uri.path.orEmpty()
        val list = uri.getQueryParameter("list")?.takeIf { LIST_ID.matches(it) }
        val short = path.startsWith("/shorts/")
        val live = path.startsWith("/live/")

        val rawVideo: String? = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            path == "/watch" -> uri.getQueryParameter("v")
            short -> path.removePrefix("/shorts/").substringBefore('/')
            live -> path.removePrefix("/live/").substringBefore('/')
            path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore('/')
            path.startsWith("/v/") -> path.removePrefix("/v/").substringBefore('/')
            else -> null
        }
        val video = rawVideo?.takeIf { isVideoId(it) }

        // un clip: youtube.com/clip/Ugkx...
        if (path.startsWith("/clip/")) {
            val clip = path.removePrefix("/clip/").substringBefore('/').takeIf { CLIP_ID.matches(it) }
            if (clip != null) {
                return Page(Kind.CLIP, directUrl = "https://www.youtube.com/clip/$clip", isMusic = music)
            }
        }

        // un álbum de YouTube Music: music.youtube.com/browse/MPREb_...
        if (path.startsWith("/browse/")) {
            val album = path.removePrefix("/browse/").substringBefore('/').takeIf { ALBUM_ID.matches(it) }
            if (album != null) {
                return Page(Kind.ALBUM, directUrl = "https://music.youtube.com/browse/$album", isMusic = true)
            }
        }

        // una publicación: youtube.com/post/Ugkx... o /channel/UC.../community?lb=Ugkx...
        val post = when {
            path.startsWith("/post/") -> path.removePrefix("/post/").substringBefore('/')
            path.endsWith("/community") || path.endsWith("/posts") -> uri.getQueryParameter("lb")
            else -> null
        }?.takeIf { POST_ID.matches(it) }
        if (post != null) {
            return Page(Kind.POST, postId = post, isMusic = music)
        }

        return when {
            video != null && list != null ->
                Page(Kind.VIDEO_IN_LIST, video, list, isShort = short, isLive = live, isMusic = music)
            video != null -> Page(Kind.VIDEO, video, isShort = short, isLive = live, isMusic = music)
            list != null && (path == "/playlist" || path == "/watch") -> Page(Kind.PLAYLIST, null, list, isMusic = music)
            else -> {
                // path ya viene decodificado: "/%40usuario" y "/@usuario" son el mismo canal
                val channel = channelPathOf(path)
                if (channel != null) {
                    Page(Kind.CHANNEL, channelPath = channel, channelTab = channelTabOf(path), isMusic = music)
                } else {
                    Page(Kind.NONE, isMusic = music)
                }
            }
        }
    }

    /** "/@usuario/videos" -> "@usuario"; "/channel/UCxxxx/featured" -> "channel/UCxxxx"; otra cosa -> null. */
    private fun channelPathOf(path: String): String? {
        val segments = path.trim('/').split('/')
        val first = segments.firstOrNull().orEmpty()
        return when {
            first.startsWith("@") && first.length > 1 -> first
            (first == "channel" || first == "c" || first == "user") &&
                    segments.size >= 2 && segments[1].isNotEmpty() -> "$first/${segments[1]}"
            else -> null
        }
    }

    /** "/@usuario/shorts" -> "shorts"; "/channel/UCxxxx/streams" -> "streams"; otra cosa -> null. */
    private fun channelTabOf(path: String): String? {
        val segments = path.trim('/').split('/')
        val first = segments.firstOrNull().orEmpty()
        val tab = if (first.startsWith("@")) segments.getOrNull(1) else segments.getOrNull(2)
        return tab?.lowercase(Locale.ROOT)?.takeIf { it == "videos" || it == "shorts" || it == "streams" }
    }

    /** Dirección base del canal; a ella se añade /videos, /shorts o /streams. */
    fun channelUrl(channelPath: String): String {
        val encoded = channelPath.split('/').joinToString("/") { Uri.encode(it, "@") }
        return "https://www.youtube.com/$encoded"
    }

    fun videoUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    /** Las canciones de YouTube Music se piden por su propia dirección: así yt-dlp trae los datos de la canción. */
    fun videoUrl(page: Page): String? {
        val id = page.videoId ?: return null
        return if (page.isMusic) "https://music.youtube.com/watch?v=$id" else videoUrl(id)
    }

    fun shortUrl(videoId: String): String = "https://www.youtube.com/shorts/$videoId"

    fun playlistUrl(listId: String): String = "https://www.youtube.com/playlist?list=$listId"

    fun mixUrl(videoId: String, listId: String): String =
        "https://www.youtube.com/watch?v=$videoId&list=$listId"

    fun postUrl(postId: String): String = "https://www.youtube.com/post/$postId"

    /** Miniaturas del vídeo, de la más grande a la más pequeña (no todos los vídeos tienen la primera). */
    fun thumbnailUrls(videoId: String): List<String> = listOf(
        "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
        "https://i.ytimg.com/vi/$videoId/sddefault.jpg",
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    )

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
