package com.deniscerri.ytdl.ui.explore

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.services.MusicService
import com.deniscerri.ytdl.util.Extensions.getIDFromYoutubeURL
import com.google.common.util.concurrent.ListenableFuture

/**
 * Barra del reproductor de audio de Explorar y su conexión con el servicio de música [MusicService], que es
 * quien reproduce (y por eso sigue sonando con la pantalla apagada o fuera de la app). Esta clase solo manda
 * órdenes al servicio y enseña su estado: título de la pista, anterior / reproducir-pausar / siguiente / detener.
 *
 * La conexión vive mientras la pantalla está visible: se abre al empezar (si el servicio ya está sonando) o al
 * pedir una pista, y se cierra al salir de la pantalla. El audio no se interrumpe por eso.
 */
class ExplorePlayer(
    context: Context,
    private val bar: View,
    private val title: TextView,
    private val previous: ImageButton,
    private val playPause: ImageButton,
    private val next: ImageButton,
    private val stop: ImageButton,
    /** El usuario pulsó reproducir en la barra: el vídeo de la página tiene que callarse para que no suenen los dos. */
    private val onResumeRequested: () -> Unit,
    /** Una pista no se pudo reproducir (el servicio pasa solo a la siguiente). */
    private val onProblem: (String) -> Unit
) : DefaultLifecycleObserver, Player.Listener {

    /** Dónde poner lo nuevo cuando ya está sonando algo. */
    enum class Mode { NOW, NEXT, END }

    private val appContext: Context = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Órdenes que llegaron antes de que terminara la conexión con el servicio. */
    private val waiting = ArrayList<(MediaController) -> Unit>()

    init {
        previous.setOnClickListener { controller?.seekToPrevious() }
        next.setOnClickListener { controller?.seekToNextMediaItem() }
        playPause.setOnClickListener { togglePlayPause() }
        stop.setOnClickListener { stopPlayback() }
        refresh()
    }

    // ------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------------------------------

    override fun onStart(owner: LifecycleOwner) {
        // si el servicio sigue sonando, se vuelve a enseñar la barra; si no, no hace falta conectarse todavía
        if (MusicService.running) connect()
    }

    override fun onStop(owner: LifecycleOwner) {
        disconnect()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        disconnect()
    }

    // ------------------------------------------------------------------------------------------
    // Conexión con el servicio
    // ------------------------------------------------------------------------------------------

    private fun connect() {
        if (future != null) return
        val token = SessionToken(appContext, ComponentName(appContext, MusicService::class.java))
        val connecting = MediaController.Builder(appContext, token).buildAsync()
        future = connecting
        connecting.addListener({ onConnected(connecting) }, mainExecutor)
    }

    private fun onConnected(connecting: ListenableFuture<MediaController>) {
        // la pantalla se cerró mientras se conectaba
        if (future !== connecting) return

        val connected = try {
            connecting.get()
        } catch (e: Exception) {
            future = null
            waiting.clear()
            onProblem(e.message ?: e.toString())
            return
        }

        controller = connected
        connected.addListener(this)
        refresh()

        val actions = ArrayList(waiting)
        waiting.clear()
        actions.forEach { it(connected) }
    }

    private fun disconnect() {
        waiting.clear()
        controller?.removeListener(this)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    private fun withController(action: (MediaController) -> Unit) {
        val connected = controller
        if (connected != null) {
            action(connected)
            return
        }
        waiting.add(action)
        connect()
    }

    // ------------------------------------------------------------------------------------------
    // Órdenes
    // ------------------------------------------------------------------------------------------

    /** ¿Hay audio en cola (sonando o en pausa)? Entonces se puede elegir dónde poner lo nuevo. */
    fun hasQueue(): Boolean {
        val c = controller ?: return false
        return c.mediaItemCount > 0 &&
                c.playbackState != Player.STATE_ENDED &&
                c.playbackState != Player.STATE_IDLE
    }

    /**
     * Reproduce [items] empezando por [startIndex] (y por [positionMs] dentro de esa pista), o los añade a la cola
     * de audio que ya está sonando, según [mode].
     */
    fun play(items: List<MediaItem>, startIndex: Int, positionMs: Long, mode: Mode) {
        if (items.isEmpty()) return
        val start = startIndex.coerceIn(0, items.size - 1)
        withController { c ->
            val replace = mode == Mode.NOW ||
                    c.mediaItemCount == 0 ||
                    c.playbackState == Player.STATE_ENDED ||
                    c.playbackState == Player.STATE_IDLE
            when {
                replace -> {
                    c.setMediaItems(items, start, positionMs)
                    c.prepare()
                    c.play()
                }
                mode == Mode.NEXT -> c.addMediaItems(c.currentMediaItemIndex + 1, items.drop(start))
                else -> c.addMediaItems(items.drop(start))
            }
        }
    }

    private fun togglePlayPause() {
        val c = controller ?: return
        when {
            c.playbackState == Player.STATE_ENDED -> {
                // la cola terminó: empieza otra vez desde la primera pista
                c.seekToDefaultPosition(0)
                onResumeRequested()
                c.play()
            }
            c.playbackState == Player.STATE_IDLE -> {
                onResumeRequested()
                c.prepare()
                c.play()
            }
            c.playWhenReady -> c.pause()
            else -> {
                onResumeRequested()
                c.play()
            }
        }
    }

    private fun stopPlayback() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
    }

    // ------------------------------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------------------------------

    override fun onEvents(player: Player, events: Player.Events) {
        refresh()
    }

    override fun onPlayerError(error: PlaybackException) {
        // el servicio salta solo a la siguiente pista; aquí solo se avisa del motivo
        // (si el servicio reintenta en silencio con una dirección nueva, no hay motivo que avisar)
        MusicService.lastProblem?.let { onProblem(it) }
    }

    private fun refresh() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE

        val name = c.mediaMetadata.title?.toString().orEmpty()
        val loading = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING
        title.text = if (loading) appContext.getString(R.string.music_title_loading, name) else name

        val playing = c.playWhenReady &&
                c.playbackState != Player.STATE_ENDED &&
                c.playbackState != Player.STATE_IDLE
        playPause.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)

        val hasNext = c.hasNextMediaItem()
        next.isEnabled = hasNext
        next.alpha = if (hasNext) 1f else 0.4f
    }

    companion object {

        /** Pista de audio de un vídeo de YouTube. La dirección del vídeo es su identificador en la cola. */
        fun mediaItem(videoId: String, title: String?, artist: String?, artworkUrl: String? = null): MediaItem {
            val url = YoutubeUrls.videoUrl(videoId)
            val artwork = artworkUrl?.takeIf { it.isNotBlank() } ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            val metadata = MediaMetadata.Builder()
                .setTitle(title?.takeIf { it.isNotBlank() } ?: "YouTube")
                .setArtist(artist?.takeIf { it.isNotBlank() })
                .setArtworkUri(Uri.parse(artwork))
                .build()
            return MediaItem.Builder()
                .setMediaId(url)
                .setUri(url)
                .setMediaMetadata(metadata)
                .build()
        }

        /** Pista a partir de un vídeo leído de una playlist o un Mix; null si no es un vídeo de YouTube. */
        fun mediaItem(item: ResultItem): MediaItem? {
            val id = item.url.getIDFromYoutubeURL() ?: return null
            return mediaItem(id, item.title, item.author, item.thumb)
        }
    }
}
