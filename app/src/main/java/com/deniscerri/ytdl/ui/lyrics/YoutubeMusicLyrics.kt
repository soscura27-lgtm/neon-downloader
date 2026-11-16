package com.deniscerri.ytdl.ui.lyrics

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Letras de YouTube Music: las mismas que enseña su app (vienen de Musixmatch o de LyricFind).
 *
 * Se usa la API interna de la web de YouTube Music (la misma que usan ytmusicapi y otros reproductores libres):
 *  - "next" con el vídeo: nombre real de la canción, artistas, álbum, duración, tipo de vídeo y el identificador
 *    de su pestaña "Letra".
 *  - "browse" con ese identificador: la letra. Con el cliente de Android llega con tiempos (línea a línea); con
 *    el de la web, solo el texto.
 *
 * Todo bloquea: llamar desde un hilo de fondo.
 */
internal object YoutubeMusicLyrics {

    private const val API = "https://music.youtube.com/youtubei/v1/"
    private const val ORIGIN = "https://music.youtube.com"
    private const val WEB_CLIENT = "WEB_REMIX"
    private const val ANDROID_CLIENT = "ANDROID_MUSIC"
    private const val ANDROID_VERSION = "7.21.50"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

    private val JSON = "application/json".toMediaType()

    /** Identificador de visitante que devuelve YouTube; se reenvía en las siguientes peticiones. */
    @Volatile
    private var visitorData: String? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** Lo que YouTube Music sabe del vídeo. */
    class Info(
        val title: String,
        val artists: List<String>,
        val album: String,
        val durationSec: Int,
        /** MUSIC_VIDEO_TYPE_ATV (la canción), _OMV (videoclip oficial), _UGC (subido por alguien)... */
        val videoType: String,
        /** Duración de la otra versión (canción ↔ videoclip), si YouTube Music la conoce. */
        val counterpartDurationSec: Int,
        val lyricsBrowseId: String?
    ) {
        /**
         * Los tiempos de la letra son los de la canción: valen para este vídeo si es la canción misma (o su audio
         * oficial), o un videoclip que dura lo mismo que la canción.
         */
        val timingMatchesSong: Boolean
            get() = when (videoType) {
                "MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC" -> true
                "MUSIC_VIDEO_TYPE_OMV" -> counterpartDurationSec > 0 && durationSec > 0 &&
                        abs(counterpartDurationSec - durationSec) <= 3
                else -> false
            }
    }

    /** La letra tal como llega: con tiempos ([lines] con timeMs >= 0) o solo el texto. */
    class Found(val lines: List<LyricsFinder.Line>, val text: String, val timed: Boolean, val sourceNote: String)

    // ------------------------------------------------------------------------------------------
    // Información del vídeo
    // ------------------------------------------------------------------------------------------

    /** null si YouTube Music no conoce el vídeo. IOException si no se pudo preguntar. */
    fun info(videoId: String, hl: String, gl: String): Info? {
        val body = JSONObject()
            .put("context", context(WEB_CLIENT, webVersion(), hl, gl))
            .put("videoId", videoId)
            .put("isAudioOnly", true)
        val json = post("next", body) ?: return null
        val watchNext = json.obj(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer"
        ) ?: return null
        val tabs = watchNext.optJSONArray("tabs") ?: return null

        var lyricsId: String? = null
        var queue: JSONArray? = null
        for (i in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(i)?.optJSONObject("tabRenderer") ?: continue
            tab.obj("content", "musicQueueRenderer", "content", "playlistPanelRenderer")
                ?.optJSONArray("contents")
                ?.let { if (queue == null) queue = it }
            val browse = tab.obj("endpoint", "browseEndpoint") ?: continue
            val pageType = browse.obj(
                "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig"
            )?.optString("pageType").orEmpty()
            val id = browse.optString("browseId")
            // la pestaña "Letra" (la segunda); si no hay letra, viene sin enlace ("unselectable")
            if (id.isNotBlank() && (pageType == "MUSIC_PAGE_TYPE_TRACK_LYRICS" || (pageType.isBlank() && i == 1))) {
                lyricsId = id
            }
        }

        val current = queue?.let { currentTrack(it, videoId) }
        return Info(
            title = current?.title.orEmpty(),
            artists = current?.artists ?: emptyList(),
            album = current?.album.orEmpty(),
            durationSec = current?.durationSec ?: 0,
            videoType = current?.videoType.orEmpty(),
            counterpartDurationSec = current?.counterpartDurationSec ?: 0,
            lyricsBrowseId = lyricsId
        )
    }

    private class Track(
        val title: String,
        val artists: List<String>,
        val album: String,
        val durationSec: Int,
        val videoType: String,
        val counterpartDurationSec: Int
    )

    /** La pista que suena en la cola de "A continuación" (la primera, o la marcada como seleccionada). */
    private fun currentTrack(queue: JSONArray, videoId: String): Track? {
        var fallback: Track? = null
        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            val wrapper = item.optJSONObject("playlistPanelVideoWrapperRenderer")
            val video = item.optJSONObject("playlistPanelVideoRenderer")
                ?: wrapper?.obj("primaryRenderer", "playlistPanelVideoRenderer")
                ?: continue
            val counterpart = wrapper?.optJSONArray("counterpart")
                ?.optJSONObject(0)
                ?.obj("counterpartRenderer", "playlistPanelVideoRenderer")
            val track = parseTrack(video, counterpart)
            if (video.optString("videoId") == videoId || video.optBoolean("selected")) return track
            if (fallback == null) fallback = track
        }
        return fallback
    }

    private fun parseTrack(video: JSONObject, counterpart: JSONObject?): Track {
        val artists = ArrayList<String>()
        var album = ""
        // "Artista & Artista • Álbum • 2024" (o "Canal • 1.2 M de vistas" en vídeos subidos por alguien)
        val runs = video.optJSONObject("longBylineText")?.optJSONArray("runs")
        var group = 0
        if (runs != null) {
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                val text = run.optString("text")
                if (text.trim() == "•") {
                    group++
                    continue
                }
                val browse = run.obj("navigationEndpoint", "browseEndpoint")
                val browseId = browse?.optString("browseId").orEmpty()
                when {
                    browseId.startsWith("MPRE") -> album = text.trim()
                    group == 0 && text.isNotBlank() && text.trim() !in SEPARATORS -> artists.add(text.trim())
                }
            }
        }
        return Track(
            title = video.runsText("title").trim(),
            artists = artists,
            album = album,
            durationSec = parseDuration(video.runsText("lengthText")),
            videoType = video.obj(
                "navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig"
            )?.optString("musicVideoType").orEmpty(),
            counterpartDurationSec = counterpart?.let { parseDuration(it.runsText("lengthText")) } ?: 0
        )
    }

    // ------------------------------------------------------------------------------------------
    // Letra
    // ------------------------------------------------------------------------------------------

    /**
     * La letra de la pestaña "Letra". Primero con el cliente de Android (que la da con tiempos) y, si no, con el
     * de la web (solo el texto). null si no hay letra.
     */
    fun lyrics(browseId: String, hl: String, gl: String): Found? {
        val android = runCatching {
            post(
                "browse",
                JSONObject()
                    .put("context", context(ANDROID_CLIENT, ANDROID_VERSION, hl, gl))
                    .put("browseId", browseId)
            )
        }.getOrNull()
        android?.let { parseTimed(it) ?: parsePlain(it) }?.let { return it }

        val web = post(
            "browse",
            JSONObject()
                .put("context", context(WEB_CLIENT, webVersion(), hl, gl))
                .put("browseId", browseId)
        ) ?: return null
        return parsePlain(web)
    }

    /** contents.elementRenderer...timedLyricsModel.lyricsData.timedLyricsData[{lyricLine, cueRange}] */
    private fun parseTimed(json: JSONObject): Found? {
        val data = json.obj(
            "contents", "elementRenderer", "newElement", "type", "componentType", "model",
            "timedLyricsModel", "lyricsData"
        ) ?: return null
        val timed = data.optJSONArray("timedLyricsData") ?: return null
        val lines = ArrayList<LyricsFinder.Line>()
        for (i in 0 until timed.length()) {
            val item = timed.optJSONObject(i) ?: continue
            val start = item.optJSONObject("cueRange")?.opt("startTimeMilliseconds")?.toString()?.toLongOrNull()
                ?: continue
            lines.add(LyricsFinder.Line(start, item.optString("lyricLine").trim()))
        }
        if (lines.none { it.text.isNotBlank() }) return null
        lines.sortBy { it.timeMs }
        return Found(
            lines = lines,
            text = lines.joinToString("\n") { it.text }.trim(),
            timed = true,
            sourceNote = data.optString("sourceMessage").trim()
        )
    }

    /** contents.sectionListRenderer.contents[].musicDescriptionShelfRenderer { description, footer } */
    private fun parsePlain(json: JSONObject): Found? {
        val contents = json.obj("contents", "sectionListRenderer")?.optJSONArray("contents") ?: return null
        for (i in 0 until contents.length()) {
            val shelf = contents.optJSONObject(i)?.optJSONObject("musicDescriptionShelfRenderer") ?: continue
            val text = shelf.runsText("description").replace("\r\n", "\n").trim()
            if (text.isBlank()) continue
            return Found(
                lines = text.lines().map { LyricsFinder.Line(-1, it.trim()) },
                text = text,
                timed = false,
                sourceNote = shelf.runsText("footer").trim()
            )
        }
        return null
    }

    // ------------------------------------------------------------------------------------------
    // Peticiones
    // ------------------------------------------------------------------------------------------

    private fun context(clientName: String, clientVersion: String, hl: String, gl: String): JSONObject {
        val client = JSONObject()
            .put("clientName", clientName)
            .put("clientVersion", clientVersion)
            .put("hl", hl)
            .put("gl", gl)
        visitorData?.let { client.put("visitorData", it) }
        return JSONObject().put("client", client).put("user", JSONObject())
    }

    /** Versión de la web de YouTube Music: "1.AAAAMMDD.01.00" con la fecha de hoy, como hace ytmusicapi. */
    private fun webVersion(): String {
        val today = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        return "1.$today.01.00"
    }

    /** null si YouTube responde que no existe (400/404). IOException si no se pudo preguntar. */
    private fun post(endpoint: String, body: JSONObject): JSONObject? {
        val builder = Request.Builder()
            .url("$API$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
        visitorData?.let { builder.header("X-Goog-Visitor-Id", it) }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 400 || response.code == 404) return null
            if (!response.isSuccessful) throw IOException("YouTube Music: HTTP ${response.code}")
            val json = JSONObject(response.body.string())
            json.optJSONObject("responseContext")
                ?.optString("visitorData")
                ?.takeIf { it.isNotBlank() }
                ?.let { visitorData = it }
            return json
        }
    }

    // ------------------------------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------------------------------

    private val SEPARATORS = setOf("&", ",", "y", "and", "x", "·")

    private fun JSONObject.obj(vararg keys: String): JSONObject? {
        var current: JSONObject? = this
        for (key in keys) current = current?.optJSONObject(key) ?: return null
        return current
    }

    /** Texto de un campo con "runs" (la forma en que YouTube manda casi todos los textos). */
    private fun JSONObject.runsText(key: String): String {
        val field = optJSONObject(key) ?: return ""
        val runs = field.optJSONArray("runs") ?: return field.optString("simpleText")
        val text = StringBuilder()
        for (i in 0 until runs.length()) text.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return text.toString()
    }

    /** "3:52" o "1:02:10" -> segundos. */
    private fun parseDuration(text: String): Int {
        val parts = text.trim().split(":").map { it.trim().toIntOrNull() ?: return 0 }
        if (parts.isEmpty() || parts.size > 3) return 0
        return parts.fold(0) { total, part -> total * 60 + part }
    }
}
