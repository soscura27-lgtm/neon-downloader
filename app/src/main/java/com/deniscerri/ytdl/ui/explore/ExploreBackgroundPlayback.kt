package com.deniscerri.ytdl.ui.explore

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.services.ExploreMediaService
import com.deniscerri.ytdl.services.MusicService
import org.json.JSONObject

/**
 * Reproducción en segundo plano de Explorar.
 *
 * Une lo que suena en el navegador de Explorar con [ExploreMediaService], el servicio que mantiene viva la página
 * con la app minimizada o la pantalla apagada y enseña la notificación con sus controles. La página cuenta qué suena
 * (res/raw/explore_background.js) y la pantalla de Explorar ejecuta las órdenes que llegan de la notificación,
 * de la pantalla de bloqueo o de los audífonos.
 *
 * Al cambiar de pestaña dentro de la app la página se cierra; entonces lo que sonaba pasa al reproductor de audio
 * ("Escuchar") en el mismo segundo, para que no se corte.
 *
 * Todo se usa desde el hilo principal.
 */
object ExploreBackgroundPlayback {

    const val PREF_ENABLED = "explore_background_play"

    /** Lo que suena en la página. */
    data class State(
        val playing: Boolean,
        val positionSec: Long,
        val durationSec: Long,
        val title: String,
        val artist: String,
        val url: String,
        /** El mismo segundo, en milisegundos (para seguir la letra línea a línea). */
        val positionMs: Long = positionSec * 1000,
        /** Está cargando: el segundo no avanza aunque esté en marcha. */
        val buffering: Boolean = false,
        /** Velocidad de reproducción (1 = normal). */
        val rate: Float = 1f
    ) {
        /** Vídeo de YouTube que suena (null si es otra web). */
        val videoId: String?
            get() = YoutubeUrls.parse(url).videoId

        companion object {
            /**
             * Estado que manda la página. Llega como texto JSON (canal de mensajes) o como el valor que devuelve
             * evaluateJavascript, que es ese mismo texto entre comillas.
             */
            fun fromJson(raw: String?): State? {
                if (raw.isNullOrBlank() || raw == "null") return null
                return runCatching {
                    val text = if (raw.startsWith("\"")) JSONObject("{\"v\":$raw}").getString("v") else raw
                    val json = JSONObject(text)
                    val seconds = json.optLong("c").coerceAtLeast(0)
                    State(
                        playing = json.optBoolean("p"),
                        positionSec = seconds,
                        durationSec = json.optLong("d").coerceAtLeast(0),
                        title = json.optString("t"),
                        artist = json.optString("a"),
                        url = json.optString("u"),
                        positionMs = if (json.has("ms")) json.optLong("ms").coerceAtLeast(0) else seconds * 1000,
                        buffering = json.optBoolean("w"),
                        rate = json.optDouble("r", 1.0).toFloat().takeIf { it > 0f && it.isFinite() } ?: 1f
                    )
                }.getOrNull()
            }
        }
    }

    /** Lo último que contó la página (null si no hay página abierta). */
    var state: State? = null
        private set

    /** Cuándo llegó [state] (reloj de SystemClock.elapsedRealtime). */
    private var stateTime = 0L

    /** Última vez que se actualizó la notificación. */
    private var lastRefresh = 0L

    /** Quien ejecuta las órdenes en la página: la pantalla de Explorar mientras existe. */
    private var commander: ((String) -> Unit)? = null

    @Volatile
    private var cachedScript: String? = null

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_ENABLED, true)

    /** Texto del script de página (se lee una sola vez de res/raw). */
    fun script(context: Context): String {
        cachedScript?.let { return it }
        val text = context.applicationContext.resources.openRawResource(R.raw.explore_background)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        cachedScript = text
        return text
    }

    fun attach(command: (String) -> Unit) {
        commander = command
    }

    fun detach(command: (String) -> Unit) {
        if (commander === command) {
            commander = null
            state = null
        }
    }

    /**
     * Orden para la página: "play", "pause", "toggle", "next", "previous", "seek:<milisegundo>", "report" (que cuente
     * ya lo que suena) o "suspend" (ya no sonar en segundo plano).
     */
    fun command(command: String) {
        commander?.invoke(command)
    }

    /**
     * La página contó algo nuevo: se actualiza la notificación si cambió lo que se ve en ella (título, pausa, un
     * salto en la canción) o cada pocos segundos para el segundo en el que va.
     */
    fun update(newState: State) {
        val old = state
        val expected = if (old != null) positionMs() else 0L
        val now = SystemClock.elapsedRealtime()
        state = newState
        stateTime = now
        val changed = old == null ||
                old.playing != newState.playing ||
                old.title != newState.title ||
                old.artist != newState.artist ||
                old.url != newState.url ||
                old.durationSec != newState.durationSec ||
                kotlin.math.abs(newState.positionMs - expected) > JUMP_MS
        if (changed || now - lastRefresh >= REFRESH_MS) {
            lastRefresh = now
            ExploreMediaService.refresh()
        }
    }

    /** En qué milisegundo va la página ahora (lo último que contó más lo que ha pasado desde entonces). */
    fun positionMs(): Long {
        val current = state ?: return 0L
        var position = current.positionMs
        if (current.playing && !current.buffering) {
            position += ((SystemClock.elapsedRealtime() - stateTime) * current.rate).toLong()
        }
        if (current.durationSec > 0) position = position.coerceAtMost(current.durationSec * 1000)
        return position.coerceAtLeast(0L)
    }

    /** El servicio (y su notificación) está en marcha o a punto de arrancar. */
    val serviceActive: Boolean
        get() = ExploreMediaService.active

    /** Arranca el servicio que mantiene viva la página. false si Android no lo permite ahora. */
    fun startService(context: Context): Boolean = ExploreMediaService.start(context)

    fun stopService() {
        ExploreMediaService.stop()
    }

    /**
     * Lo que sonaba en la página sigue en el reproductor de audio de la app, desde el mismo segundo. Solo para vídeos
     * de YouTube (el reproductor de audio pide su dirección a yt-dlp). Devuelve false si no se pudo.
     */
    fun handOverToMusic(context: Context, state: State): Boolean {
        val videoId = state.videoId ?: return false
        val app = context.applicationContext
        val item = ExplorePlayer.mediaItem(videoId, cleanTitle(state.title), state.artist)
        return runCatching {
            val token = SessionToken(app, ComponentName(app, MusicService::class.java))
            val future = MediaController.Builder(app, token).buildAsync()
            future.addListener({
                val controller = runCatching { future.get() }.getOrNull()
                if (controller != null) {
                    controller.setMediaItems(listOf(item), 0, state.positionSec * 1000)
                    controller.prepare()
                    controller.play()
                }
                // la orden ya se envió: se suelta el control y el servicio sigue sonando solo
                Handler(Looper.getMainLooper()).postDelayed(
                    { MediaController.releaseFuture(future) },
                    RELEASE_DELAY_MS
                )
            }, ContextCompat.getMainExecutor(app))
            true
        }.getOrDefault(false)
    }

    /** El título de la pestaña es "Título - YouTube", y con avisos pendientes "(3) Título - YouTube". */
    fun cleanTitle(raw: String): String {
        return raw.replace(Regex("^\\(\\d+\\)\\s*"), "").removeSuffix(" - YouTube").trim()
    }

    private const val RELEASE_DELAY_MS = 3000L

    /** Cada cuánto se actualiza como mucho la notificación si solo avanza el segundo. */
    private const val REFRESH_MS = 4000L

    /** Una diferencia mayor que esta entre lo esperado y lo contado es un salto (el usuario movió la canción). */
    private const val JUMP_MS = 2500L
}
