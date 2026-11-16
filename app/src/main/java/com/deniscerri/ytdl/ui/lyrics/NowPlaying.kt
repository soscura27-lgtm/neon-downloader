package com.deniscerri.ytdl.ui.lyrics

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.deniscerri.ytdl.services.MusicService
import com.deniscerri.ytdl.ui.explore.ExploreBackgroundPlayback
import com.deniscerri.ytdl.ui.explore.YoutubeUrls
import com.deniscerri.ytdl.util.Extensions.getIDFromYoutubeURL
import com.google.common.util.concurrent.ListenableFuture

/** Algo que suena y que la letra puede seguir. Todo desde el hilo principal. */
internal interface LyricsSource {
    /** La canción que suena (null si no hay ninguna). */
    fun track(): LyricsTrack?

    /** En qué milisegundo va. */
    fun positionMs(): Long

    fun isPlaying(): Boolean

    /** Ir a ese milisegundo (al tocar una línea de la letra). */
    fun seekTo(ms: Long)
}

/**
 * Lo que suena ahora, para la letra: la página de Explorar (un vídeo de YouTube) o "Escuchar" (el reproductor de
 * audio). Si suenan los dos, manda el que se pidió; si no suena ninguno, el último que sonó. Así la letra sigue a la
 * canción aunque se pase de la página al reproductor de audio (por ejemplo, al cambiar de pestaña).
 */
internal class NowPlaying(context: Context, preferMusic: Boolean) {

    private val page = PageSource()
    private val music = MusicSource(context)
    private val ordered: List<LyricsSource> = if (preferMusic) listOf(music, page) else listOf(page, music)
    private var last: LyricsSource? = null

    /** La conexión con "Escuchar" se está abriendo: todavía no se sabe si suena algo ahí. */
    val connecting: Boolean
        get() = music.connecting

    fun active(): LyricsSource? {
        music.update()
        ordered.firstOrNull { it.isPlaying() }?.let {
            last = it
            return it
        }
        last?.takeIf { it.track() != null }?.let { return it }
        ordered.firstOrNull { it.track() != null }?.let {
            last = it
            return it
        }
        return null
    }

    /** ¿Lo que suena está en la página de Explorar? (allí los títulos tardan un poco en cambiar). */
    fun isPage(source: LyricsSource?): Boolean = source === page

    fun release() {
        music.release()
        last = null
    }
}

/** La página de Explorar: lo que cuenta res/raw/explore_background.js a través de [ExploreBackgroundPlayback]. */
private class PageSource : LyricsSource {

    override fun track(): LyricsTrack? {
        val state = ExploreBackgroundPlayback.state ?: return null
        val videoId = state.videoId
        // una página sin vídeo (el inicio de YouTube, un buscador...) no es una canción
        if (videoId == null && state.durationSec <= 0) return null
        val title = ExploreBackgroundPlayback.cleanTitle(state.title)
        if (title.isBlank() && videoId == null) return null
        return LyricsTrack(videoId, title, state.artist, state.durationSec.toInt())
    }

    override fun positionMs(): Long = ExploreBackgroundPlayback.positionMs()

    override fun isPlaying(): Boolean = ExploreBackgroundPlayback.state?.playing == true

    override fun seekTo(ms: Long) {
        ExploreBackgroundPlayback.command("seek:${ms.coerceAtLeast(0)}")
    }
}

/** "Escuchar": el servicio de música, a través de un MediaController (solo mientras el servicio está en marcha). */
private class MusicSource(context: Context) : LyricsSource {

    private val app = context.applicationContext
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Si la conexión falló, no se vuelve a intentar hasta este momento. */
    private var retryAt = 0L

    val connecting: Boolean
        get() = future != null && controller == null

    /** Se conecta cuando el servicio de música está en marcha y se suelta cuando se detiene. */
    fun update() {
        if (MusicService.running) {
            if (future == null && SystemClock.elapsedRealtime() >= retryAt) connect()
        } else if (future != null) {
            release()
        }
    }

    private fun connect() {
        val token = SessionToken(app, ComponentName(app, MusicService::class.java))
        val pending = MediaController.Builder(app, token).buildAsync()
        future = pending
        pending.addListener({
            if (future === pending) {
                controller = runCatching { pending.get() }.getOrNull()
                // no se pudo: se reintenta dentro de un rato
                if (controller == null) {
                    release()
                    retryAt = SystemClock.elapsedRealtime() + RETRY_MS
                }
            }
        }, ContextCompat.getMainExecutor(app))
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    override fun track(): LyricsTrack? {
        val c = controller ?: return null
        if (c.mediaItemCount == 0) return null
        val item = c.currentMediaItem ?: return null
        val url = item.mediaId
        val videoId = url.getIDFromYoutubeURL() ?: YoutubeUrls.parse(url).videoId
        val metadata = item.mediaMetadata
        val title = metadata.title?.toString().orEmpty()
        val artist = metadata.artist?.toString().orEmpty()
        if (videoId == null && title.isBlank()) return null
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { (it / 1000).toInt() } ?: 0
        return LyricsTrack(videoId, title, artist, duration)
    }

    override fun positionMs(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: 0L

    override fun isPlaying(): Boolean {
        val c = controller ?: return false
        return c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE
    }

    override fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
    }

    private companion object {
        const val RETRY_MS = 3000L
    }
}
