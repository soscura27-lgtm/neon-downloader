package com.deniscerri.ytdl.services

import android.app.PendingIntent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.util.extractors.ytdlp.YTDLPUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Reproductor de audio en segundo plano ("Escuchar" en Explorar).
 *
 * Reproduce solo el audio de los vídeos de YouTube en streaming, sin descargarlos. Es un servicio en primer plano
 * con notificación y controles (también en la pantalla de bloqueo), así que sigue sonando con la pantalla apagada
 * o con la app minimizada. La pantalla de Explorar solo le manda órdenes a través de un MediaController.
 *
 * Cada pista se identifica con la dirección de su vídeo (watch?v=...). La dirección directa del audio se pide a
 * yt-dlp justo antes de leerla, porque caduca, y se pide por adelantado la de la pista siguiente para que el paso
 * de una canción a otra no se note.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {

    private var session: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var streams: AudioStreams? = null

    /** Pistas a las que ya se les dio una segunda oportunidad con una dirección nueva. */
    private val retried = HashSet<String>()

    /** Pistas seguidas que fallaron: si fallan varias a la vez, lo más probable es que no haya conexión. */
    private var failuresInARow = 0

    override fun onCreate() {
        super.onCreate()
        running = true

        val resolver = AudioStreams(
            YTDLPUtil(applicationContext, DBManager.getInstance(applicationContext).commandTemplateDao)
        )
        streams = resolver

        val mediaSources = DefaultMediaSourceFactory(
            ResolvingDataSource.Factory(DefaultDataSource.Factory(this), resolver)
        ).setLoadErrorHandlingPolicy(NoRetryWhileResolving())

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(PlaybackListener())
        exoPlayer = exo

        val builder = MediaSession.Builder(this, exo)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        session = builder.build()

        val notifications = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.music_channel_name)
            .build()
        notifications.setSmallIcon(R.drawable.ic_stat_rocket)
        setMediaNotificationProvider(notifications)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        running = false
        streams?.close()
        streams = null
        session?.release()
        session = null
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------
    // Estado del reproductor
    // ------------------------------------------------------------------------------------------

    private inner class PlaybackListener : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            failuresInARow = 0
            exoPlayer?.currentMediaItem?.mediaId?.let { retried.remove(it) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // la pista actual ya suena: es buen momento para ir preparando la siguiente
            if (playbackState == Player.STATE_READY) prefetchNext()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (exoPlayer?.playbackState == Player.STATE_READY) prefetchNext()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (timeline.isEmpty) {
                // la cola se vació (el usuario pulsó "detener"): el servicio ya no hace falta
                Handler(Looper.getMainLooper()).post { stopWhenEmpty() }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }
    }

    private fun stopWhenEmpty() {
        val exo = exoPlayer ?: return
        if (exo.mediaItemCount == 0) pauseAllPlayersAndStopSelf()
    }

    private fun prefetchNext() {
        val exo = exoPlayer ?: return
        val index = exo.nextMediaItemIndex
        if (index == C.INDEX_UNSET) return
        streams?.prefetch(exo.getMediaItemAt(index).mediaId)
    }

    /**
     * Una pista falló:
     *  1) si YouTube rechazó la dirección (caducó o no vale para esta conexión), se pide otra y se reintenta una vez;
     *  2) si no, se pasa a la siguiente pista, salvo que ya hayan fallado varias seguidas.
     */
    private fun handleError(error: PlaybackException) {
        val exo = exoPlayer ?: return
        val id = exo.currentMediaItem?.mediaId

        if (id != null && isRejectedByServer(error) && retried.add(id)) {
            // en silencio: se pide una dirección nueva y se reintenta
            lastProblem = null
            streams?.forget(id)
            exo.prepare()
            return
        }

        lastProblem = reasonOf(error)
        failuresInARow++
        if (failuresInARow < MAX_FAILURES_IN_A_ROW && exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem()
            exo.prepare()
            exo.play()
        }
    }

    /** Motivo del fallo en pocas palabras, para el aviso de la pantalla. */
    private fun reasonOf(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is StreamUrlException) return cause.message.orEmpty()
            if (cause is HttpDataSource.InvalidResponseCodeException) return "HTTP ${cause.responseCode}"
            cause = cause.cause
        }
        return error.errorCodeName
    }

    private fun isRejectedByServer(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                val code = cause.responseCode
                return code == 403 || code == 404 || code == 410
            }
            cause = cause.cause
        }
        return false
    }

    // ------------------------------------------------------------------------------------------
    // Direcciones de audio
    // ------------------------------------------------------------------------------------------

    /** No se pudo obtener el audio de un vídeo: no tiene sentido reintentar al instante, yt-dlp ya dio su respuesta. */
    private class StreamUrlException(message: String, cause: Throwable?) : IOException(message, cause)

    private class NoRetryWhileResolving : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (loadErrorInfo.exception is StreamUrlException) return C.TIME_UNSET
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    /**
     * Cambia la dirección de un vídeo de YouTube por la dirección directa de su audio justo antes de leerlo.
     * Guarda las direcciones un rato (se vuelven a pedir en cada salto dentro de la pista) y deja pedir la de
     * la pista siguiente por adelantado; si dos peticiones piden lo mismo a la vez, yt-dlp solo se ejecuta una vez.
     */
    private class AudioStreams(private val ytdlp: YTDLPUtil) : ResolvingDataSource.Resolver {

        private class Entry(val url: String, val at: Long)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val cache = ConcurrentHashMap<String, Entry>()

        // peticiones en marcha; solo se toca dentro de synchronized(this)
        private val inFlight = HashMap<String, Deferred<Result<String>>>()

        /** Lo llama el reproductor desde su hilo de carga: puede tardar lo que tarde yt-dlp. */
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val uri = dataSpec.uri
            // las direcciones que no son de YouTube ya son directas
            if (!isYoutube(uri)) return dataSpec
            return dataSpec.withUri(Uri.parse(directUrl(uri.toString())))
        }

        fun prefetch(key: String) {
            if (!isYoutube(Uri.parse(key)) || freshUrl(key) != null) return
            start(key)
        }

        fun forget(key: String) {
            cache.remove(key)
        }

        fun close() {
            scope.cancel()
        }

        private fun isYoutube(uri: Uri): Boolean {
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        }

        private fun freshUrl(key: String): String? {
            val entry = cache[key] ?: return null
            if (SystemClock.elapsedRealtime() - entry.at > URL_TTL_MS) {
                cache.remove(key)
                return null
            }
            return entry.url
        }

        /** Empieza a pedir la dirección (o se une a la petición que ya está en marcha para ese vídeo). */
        private fun start(key: String): Deferred<Result<String>> = synchronized(this) {
            inFlight[key] ?: scope.async {
                try {
                    val result = ytdlp.getAudioStreamUrl(key)
                    val url = result.getOrNull()
                    if (url != null) cache[key] = Entry(url, SystemClock.elapsedRealtime())
                    result
                } finally {
                    synchronized(this@AudioStreams) { inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }

        private fun directUrl(key: String): String {
            freshUrl(key)?.let { return it }

            val job = start(key)
            val result: Result<String> = try {
                runBlocking { job.await() }
            } catch (e: InterruptedException) {
                // la carga se canceló (por ejemplo, el usuario saltó de pista); yt-dlp sigue y deja la dirección lista
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            } catch (e: CancellationException) {
                throw InterruptedIOException()
            }

            val url = result.getOrNull()
            if (url.isNullOrBlank()) {
                val failure = result.exceptionOrNull()
                throw StreamUrlException(shorten(failure?.message).ifBlank { "audio" }, failure)
            }
            return url
        }

        /** El mensaje de yt-dlp trae varias líneas; para el aviso basta con la del error. */
        private fun shorten(raw: String?): String {
            val lines = raw.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
            val line = lines.lastOrNull { it.startsWith("ERROR", ignoreCase = true) }
                ?: lines.lastOrNull()
                ?: return ""
            return line.removePrefix("ERROR:").trim().take(160)
        }
    }

    companion object {
        /** Las direcciones de YouTube duran horas; 25 minutos deja margen de sobra. */
        private const val URL_TTL_MS = 25 * 60 * 1000L
        private const val MAX_FAILURES_IN_A_ROW = 3

        /** true mientras el servicio existe (sirve para reconectar la barra del reproductor al volver a Explorar). */
        @Volatile
        var running = false

        /** Motivo del último fallo de una pista que no se reintenta en silencio; la pantalla lo enseña en un aviso. */
        @Volatile
        var lastProblem: String? = null
    }
}
