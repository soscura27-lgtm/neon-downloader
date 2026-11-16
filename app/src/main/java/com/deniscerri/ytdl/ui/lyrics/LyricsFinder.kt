package com.deniscerri.ytdl.ui.lyrics

import android.content.Context
import android.os.SystemClock
import android.util.LruCache
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.util.extractors.LrcLibUtil
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Canción de la que se busca la letra: lo que se sabe de lo que suena. */
data class LyricsTrack(
    /** Vídeo de YouTube (si se sabe). */
    val videoId: String?,
    /** Título tal cual llega (de la página o del reproductor). */
    val title: String,
    /** Canal o artista. */
    val artist: String,
    /** Duración en segundos (0 si no se sabe). */
    val durationSec: Int
) {
    /** Si cambia, es otra canción. */
    val key: String
        get() = videoId ?: (title.trim().lowercase(Locale.ROOT) + "|" + artist.trim().lowercase(Locale.ROOT))
}

/**
 * Busca la letra de una canción, en este orden:
 *  1. La que el usuario eligió a mano otra vez para esta misma canción.
 *  2. YouTube Music, por el vídeo: es la letra de esa canción exacta (de Musixmatch o LyricFind) y además da el
 *     nombre real de la canción y del artista, que sirven para el paso siguiente.
 *  3. LRCLIB (letras libres, casi siempre con tiempos), con esos nombres y la duración del vídeo.
 *
 * La letra solo se marca como sincronizada si sus tiempos valen para este vídeo (misma duración o la canción
 * misma); si no, se enseña como texto. Lo encontrado se guarda unos minutos en memoria.
 *
 * Todo bloquea: llamar desde un hilo de fondo.
 */
object LyricsFinder {

    data class Line(val timeMs: Long, val text: String)

    enum class Source { YOUTUBE_MUSIC, LRCLIB }

    data class Lyrics(
        /** Canción y artista encontrados. */
        val title: String,
        val artist: String,
        /** Lo que se enseña: con tiempos (timeMs >= 0) si [synced]. */
        val lines: List<Line>,
        val synced: Boolean,
        /** Lo que se copia: la letra entera, con las estrofas separadas. */
        val text: String,
        val source: Source,
        /** "Fuente: Musixmatch", "Source: LyricFind"... (lo que dice YouTube Music). */
        val sourceNote: String = "",
        val lrclibId: Long = 0L
    )

    private const val PREF_CHOICES = "neon_lyrics_choices"
    private const val MAX_CHOICES = 300
    private const val NOT_FOUND_TTL_MS = 10 * 60 * 1000L

    /** Tolerancia para dar por buenos los tiempos de una letra de LRCLIB (como su propia búsqueda). */
    private const val SYNC_TOLERANCE_SEC = 3

    private val found = LruCache<String, Lyrics>(40)
    private val missing = LruCache<String, Long>(40)

    /** Lo ya encontrado para esta canción (sin buscar). */
    fun cached(track: LyricsTrack): Lyrics? = found.get(track.key)

    /**
     * La letra de [track], o null si no hay. Lanza IOException si no se pudo llegar a ninguna fuente (sin conexión).
     * Con [force] se vuelve a buscar aunque ya se tenga (y se olvida lo elegido a mano).
     */
    fun find(context: Context, track: LyricsTrack, force: Boolean = false): Lyrics? {
        val key = track.key
        if (force) {
            found.remove(key)
            missing.remove(key)
            forgetChoice(context, track)
        } else {
            found.get(key)?.let { return it }
            missing.get(key)?.let { if (SystemClock.elapsedRealtime() - it < NOT_FOUND_TTL_MS) return null }
        }
        val attempt = Attempt()
        val result = search(context, track, attempt)
        if (result != null) {
            found.put(key, result)
            missing.remove(key)
            return result
        }
        if (!attempt.reached) throw attempt.error ?: IOException("sin conexión")
        missing.put(key, SystemClock.elapsedRealtime())
        return null
    }

    /** Búsqueda libre en LRCLIB (para "Buscar otra letra"). */
    fun searchLrclib(query: String): List<LrcLibUtil.Lyrics> = LrcLibUtil.search(query)

    /** El usuario eligió esta letra para esta canción: se usará siempre que vuelva a sonar. */
    fun choose(context: Context, track: LyricsTrack?, choice: LrcLibUtil.Lyrics, durationSec: Int): Lyrics {
        val lyrics = fromLrclib(choice, synced = choice.isSynced && timingFits(choice.durationSec, durationSec))
        if (track != null) {
            found.put(track.key, lyrics)
            missing.remove(track.key)
            if (choice.id > 0) saveChoice(context, track, choice.id)
        }
        return lyrics
    }

    // ------------------------------------------------------------------------------------------
    // Búsqueda
    // ------------------------------------------------------------------------------------------

    /** Lleva la cuenta de si alguna fuente respondió (para distinguir "no hay letra" de "no hay conexión"). */
    private class Attempt {
        var reached = false
        var error: Exception? = null

        fun <T> ask(block: () -> T?): T? {
            return try {
                block().also { reached = true }
            } catch (e: Exception) {
                error = e
                null
            }
        }
    }

    private fun search(context: Context, track: LyricsTrack, attempt: Attempt): Lyrics? {
        // 1. lo que se eligió a mano para esta canción
        savedChoice(context, track)?.let { id ->
            attempt.ask { LrcLibUtil.byId(id) }?.let { chosen ->
                return fromLrclib(chosen, synced = chosen.isSynced && timingFits(chosen.durationSec, track.durationSec))
            }
        }

        val locale = Locale.getDefault()
        val hl = locale.language.ifBlank { "es" }
        val gl = locale.country.ifBlank { "US" }

        // 2. YouTube Music por el vídeo
        val info = track.videoId?.let { id -> attempt.ask { YoutubeMusicLyrics.info(id, hl, gl) } }
        val duration = info?.durationSec?.takeIf { it > 0 } ?: track.durationSec
        val youtubeLyrics = info?.let { known ->
            known.lyricsBrowseId
                ?.let { id -> attempt.ask { YoutubeMusicLyrics.lyrics(id, hl, gl) } }
                ?.let { lyrics -> fromYoutube(lyrics, known, track, synced = lyrics.timed && known.timingMatchesSong) }
        }
        if (youtubeLyrics?.synced == true) return youtubeLyrics

        // 3. LRCLIB con los nombres reales (sincronizada si la duración coincide)
        val names = TitleParser.candidates(track, info)
        var lrclibPlain: LrcLibUtil.Lyrics? = null
        for ((title, artist) in names.take(2)) {
            val result = attempt.ask { LrcLibUtil.find(title, artist, "", duration) } ?: continue
            if (result.isSynced && timingFits(result.durationSec, duration)) return fromLrclib(result, synced = true)
            if (lrclibPlain == null) lrclibPlain = result
        }

        // 4. sin tiempos que valgan: la de YouTube Music (exacta para el vídeo) o la de LRCLIB como texto
        youtubeLyrics?.let { return it }
        lrclibPlain?.let { return fromLrclib(it, synced = false) }

        // 5. búsqueda libre en LRCLIB, solo si el título y el artista coinciden
        for ((title, artist) in names.take(2)) {
            val results = attempt.ask { LrcLibUtil.search("$artist $title".trim()) } ?: continue
            val best = TitleParser.bestMatch(results, title, artist, duration) ?: continue
            return fromLrclib(best, synced = best.isSynced && timingFits(best.durationSec, duration))
        }
        return null
    }

    /** Los tiempos de una letra valen si dura lo mismo que lo que suena (o si no se sabe cuánto dura). */
    private fun timingFits(lyricsDurationSec: Int, playingDurationSec: Int): Boolean {
        if (playingDurationSec <= 0 || lyricsDurationSec <= 0) return true
        return abs(lyricsDurationSec - playingDurationSec) <= SYNC_TOLERANCE_SEC
    }

    private fun fromYoutube(
        found: YoutubeMusicLyrics.Found,
        info: YoutubeMusicLyrics.Info,
        track: LyricsTrack,
        synced: Boolean
    ): Lyrics {
        val lines = if (synced) found.lines else found.text.lines().map { Line(-1, it.trim()) }
        return Lyrics(
            title = info.title.ifBlank { TitleParser.parse(track.title, track.artist).first },
            artist = info.artists.joinToString(", ").ifBlank { TitleParser.parse(track.title, track.artist).second },
            lines = lines,
            synced = synced,
            text = found.text,
            source = Source.YOUTUBE_MUSIC,
            sourceNote = found.sourceNote
        )
    }

    private fun fromLrclib(lyrics: LrcLibUtil.Lyrics, synced: Boolean): Lyrics {
        val lines = if (synced) {
            lyrics.synced.map { Line(it.timeMs, it.text.trim()) }
        } else {
            lyrics.plain.replace("\r\n", "\n").trim().lines().map { Line(-1, it.trim()) }
        }
        return Lyrics(
            title = lyrics.trackName,
            artist = lyrics.artistName,
            lines = lines,
            synced = synced,
            text = lyrics.plain.replace("\r\n", "\n").trim(),
            source = Source.LRCLIB,
            lrclibId = lyrics.id
        )
    }

    // ------------------------------------------------------------------------------------------
    // Letras elegidas a mano
    // ------------------------------------------------------------------------------------------

    private fun choices(context: Context): JSONObject {
        val raw = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_CHOICES, null)
        return runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())
    }

    private fun savedChoice(context: Context, track: LyricsTrack): Long? =
        choices(context).optLong(track.key, 0L).takeIf { it > 0 }

    private fun saveChoice(context: Context, track: LyricsTrack, id: Long) {
        val all = choices(context)
        all.remove(track.key)
        all.put(track.key, id)
        // solo las últimas: las más viejas se olvidan
        while (all.length() > MAX_CHOICES) {
            val oldest = all.keys().next()
            all.remove(oldest)
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_CHOICES, all.toString()).apply()
    }

    private fun forgetChoice(context: Context, track: LyricsTrack) {
        val all = choices(context)
        if (!all.has(track.key)) return
        all.remove(track.key)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_CHOICES, all.toString()).apply()
    }
}

/**
 * Del título de un vídeo de YouTube a canción y artista: "Bad Bunny - DtMF (Video Oficial)" con el canal
 * "Bad Bunny" -> ("DtMF", "Bad Bunny"); "DtMF" con el canal "Bad Bunny - Topic" -> ("DtMF", "Bad Bunny").
 */
internal object TitleParser {

    /** Paréntesis y corchetes que no son parte del nombre de la canción. */
    private val junkBrackets = Regex(
        """\s*[(\[【][^)\]】]*(official|oficial|video|vídeo|videoclip|audio|lyric|lyrics|letra|visuali[sz]er|""" +
                """\bhd\b|\bhq\b|\b4k\b|\bmv\b|m/v|remaster|explicit|expl[ií]cito|clean)[^)\]】]*[)\]】]""",
        RegexOption.IGNORE_CASE
    )
    private val trailingSections = Regex("""\s+(\||//|｜)\s+.*$""")
    private val dashSplit = Regex("""\s+[-–—~]\s+""")
    private val featuring = Regex("""\s*[(\[]?\s*\b(ft\.?|feat\.?|featuring)\s+[^)\]]*[)\]]?""", RegexOption.IGNORE_CASE)
    private val topicSuffix = Regex("""\s*-\s*(topic|tema)\s*$""", RegexOption.IGNORE_CASE)
    private val channelWords = Regex("""(\s*\b(official|oficial|music)|vevo)\s*$""", RegexOption.IGNORE_CASE)
    private val camelCase = Regex("""(?<=[a-zà-ÿ])(?=[A-ZÀ-Þ])""")

    /** (canción, artista) a partir del título y el canal. */
    fun parse(rawTitle: String, rawArtist: String): Pair<String, String> {
        val title = cleanTitle(rawTitle)
        val channel = cleanChannel(rawArtist)
        val parts = title.split(dashSplit, limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            val left = parts[0].trim()
            val right = parts[1].trim()
            // lo normal es "Artista - Canción"; "Canción - Artista" solo si el canal es el de la derecha
            return if (channel.isNotBlank() && similar(right, channel) && !similar(left, channel)) {
                left to right
            } else {
                right to left
            }
        }
        return title to channel
    }

    /** Combinaciones de (canción, artista) para buscar en LRCLIB, de la más fiable a la menos. */
    fun candidates(track: LyricsTrack, info: YoutubeMusicLyrics.Info?): List<Pair<String, String>> {
        val out = LinkedHashMap<String, Pair<String, String>>()
        fun add(title: String, artist: String) {
            val t = title.trim()
            val a = artist.trim()
            if (t.isBlank()) return
            out.putIfAbsent(normalize(t) + "|" + normalize(a), t to a)
        }
        if (info != null && info.title.isNotBlank()) {
            val main = info.artists.firstOrNull().orEmpty()
            add(cleanTitle(info.title), main)
            if (info.artists.size > 1) add(cleanTitle(info.title), info.artists.joinToString(", "))
        }
        val (title, artist) = parse(track.title, track.artist)
        add(title, artist)
        val withoutFeat = featuring.replace(title, "").trim()
        if (withoutFeat != title) add(withoutFeat, artist)
        return out.values.toList()
    }

    /** De una búsqueda libre, el resultado cuyo título y artista coinciden de verdad (null si ninguno). */
    fun bestMatch(results: List<LrcLibUtil.Lyrics>, title: String, artist: String, durationSec: Int): LrcLibUtil.Lyrics? {
        val wantedTitle = normalize(featuring.replace(title, ""))
        val wantedArtist = normalize(artist)
        return results
            .filter { r ->
                val t = normalize(featuring.replace(r.trackName, ""))
                val a = normalize(r.artistName)
                val titleOk = t.isNotBlank() && wantedTitle.isNotBlank() && (t == wantedTitle || t.contains(wantedTitle) || wantedTitle.contains(t))
                val artistOk = wantedArtist.isBlank() || a.contains(wantedArtist) || wantedArtist.contains(a)
                titleOk && artistOk
            }
            .minWithOrNull(
                compareBy<LrcLibUtil.Lyrics>(
                    { normalize(it.trackName) != wantedTitle },
                    { if (durationSec > 0 && it.durationSec > 0) abs(it.durationSec - durationSec) else 0 },
                    { !it.isSynced }
                )
            )
    }

    fun cleanTitle(raw: String): String {
        var title = raw.trim()
        title = junkBrackets.replace(title, "")
        title = trailingSections.replace(title, "")
        return title.replace(Regex("\\s{2,}"), " ").trim()
    }

    fun cleanChannel(raw: String): String {
        var channel = topicSuffix.replace(raw.trim(), "")
        // "LuisFonsiVEVO" -> "LuisFonsi" -> "Luis Fonsi"
        repeat(2) { channel = channelWords.replace(channel, "") }
        if (!channel.contains(' ')) channel = channel.split(camelCase).joinToString(" ")
        return channel.trim()
    }

    /** Minúsculas, sin acentos ni signos: para comparar nombres. */
    fun normalize(text: String): String {
        val plain = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return plain.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    private fun similar(a: String, b: String): Boolean {
        val x = normalize(a).replace(" ", "")
        val y = normalize(b).replace(" ", "")
        if (x.isBlank() || y.isBlank()) return false
        return x == y || x.contains(y) || y.contains(x)
    }
}
