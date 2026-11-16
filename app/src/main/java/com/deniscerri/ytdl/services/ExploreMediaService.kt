package com.deniscerri.ytdl.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.ui.explore.ExploreBackgroundPlayback
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Mantiene viva la página de Explorar mientras suena con la app minimizada o la pantalla apagada.
 *
 * Es un servicio en primer plano (tipo mediaPlayback): sin él, Android congela la app a los pocos segundos de
 * salir y el audio se corta. Enseña una notificación de reproductor con anterior, reproducir/pausar, siguiente
 * (el vídeo anterior o siguiente del Mix o la playlist) y cerrar, tiene una
 * MediaSession (controles en la pantalla de bloqueo, en los audífonos y en el reloj) y sujeta la CPU y el Wi-Fi
 * mientras suena. La página sigue en el navegador de la app: este servicio solo le manda órdenes a través de
 * [ExploreBackgroundPlayback].
 *
 * Se usa desde el hilo principal.
 */
class ExploreMediaService : Service() {

    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var loader: ExecutorService? = null
    private var isForeground = false

    private var artworkUrl: String? = null
    private var artwork: Bitmap? = null

    /** En pausa mucho rato con la app fuera: se libera todo (la notificación desaparece). */
    private val pausedTooLong = Runnable { closeFromUser() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        loader = Executors.newSingleThreadExecutor()
        createChannel()
        session = MediaSession(this, SESSION_TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    ExploreBackgroundPlayback.command("play")
                }

                override fun onPause() {
                    ExploreBackgroundPlayback.command("pause")
                }

                override fun onSkipToNext() {
                    ExploreBackgroundPlayback.command("next")
                }

                override fun onSkipToPrevious() {
                    ExploreBackgroundPlayback.command("previous")
                }

                override fun onStop() {
                    closeFromUser()
                }
            })
            // Android 7: sin estas marcas los botones de los audífonos no llegan
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            openAppIntent()?.let { setSessionActivity(it) }
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_TOGGLE -> ExploreBackgroundPlayback.command("toggle")
            ACTION_NEXT -> ExploreBackgroundPlayback.command("next")
            ACTION_PREVIOUS -> ExploreBackgroundPlayback.command("previous")
            ACTION_CLOSE -> {
                closeFromUser()
                return START_NOT_STICKY
            }
        }
        val shown = showNotification()
        if (action == ACTION_SHOW && (!shown || !wanted)) {
            // pedido con startForegroundService: Android exige pasar a primer plano aunque ya no haga falta
            if (!shown) promoteBriefly()
            stopEverything()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        wanted = false
        main.removeCallbacksAndMessages(null)
        releaseLocks()
        session?.release()
        session = null
        loader?.shutdownNow()
        loader = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------
    // Notificación y controles
    // ------------------------------------------------------------------------------------------

    /** Pinta (o actualiza) la notificación con lo que suena. false si no hay nada o Android no lo permite. */
    private fun showNotification(): Boolean {
        val state = ExploreBackgroundPlayback.state ?: return false
        val title = ExploreBackgroundPlayback.cleanTitle(state.title)
            .ifBlank { getString(R.string.explore_background_playing) }
        val artist = state.artist.ifBlank { hostOf(state.url) }

        loadArtwork(state)
        updateSession(state, title, artist)
        if (!post(buildNotification(state.playing, title, artist))) return false

        updateLocks(state.playing)
        main.removeCallbacks(pausedTooLong)
        if (!state.playing) main.postDelayed(pausedTooLong, PAUSED_TIMEOUT_MS)
        return true
    }

    private fun post(notification: Notification): Boolean {
        if (isForeground) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
            return true
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType())
            isForeground = true
            true
        } catch (e: Exception) {
            // Android no deja pasar a primer plano ahora (la app ya estaba en segundo plano)
            false
        }
    }

    /** Notificación mínima para cumplir con startForegroundService cuando ya no queda nada que enseñar. */
    private fun promoteBriefly() {
        val notification = notificationBuilder()
            .setContentTitle(getString(R.string.explore_background_playing))
            .build()
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType()) }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0

    private fun notificationBuilder(): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_rocket)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
    }

    private fun buildNotification(playing: Boolean, title: String, artist: String): Notification {
        val previous = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_player_prev),
            getString(R.string.explore_background_previous),
            servicePendingIntent(ACTION_PREVIOUS, REQUEST_PREVIOUS)
        ).build()
        val next = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_player_next),
            getString(R.string.explore_background_next),
            servicePendingIntent(ACTION_NEXT, REQUEST_NEXT)
        ).build()
        val toggle = Notification.Action.Builder(
            Icon.createWithResource(this, if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play),
            getString(if (playing) R.string.explore_background_pause else R.string.explore_background_resume),
            servicePendingIntent(ACTION_TOGGLE, REQUEST_TOGGLE)
        ).build()
        val close = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_player_stop),
            getString(R.string.explore_background_close),
            servicePendingIntent(ACTION_CLOSE, REQUEST_CLOSE)
        ).build()

        // compacta: anterior, reproducir/pausar y siguiente; expandida, además, cerrar
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }

        val builder = notificationBuilder()
            .setContentTitle(title)
            .setContentText(artist)
            .setOngoing(playing)
            .setDeleteIntent(servicePendingIntent(ACTION_CLOSE, REQUEST_CLOSE))
            .addAction(previous)
            .addAction(toggle)
            .addAction(next)
            .addAction(close)
            .setStyle(style)
        artwork?.let { builder.setLargeIcon(it) }
        openAppIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun updateSession(state: ExploreBackgroundPlayback.State, title: String, artist: String) {
        val current = session ?: return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        current.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    state.positionSec * 1000,
                    if (state.playing) 1f else 0f
                )
                .build()
        )
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        if (state.durationSec > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationSec * 1000)
        artwork?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        current.setMetadata(metadata.build())
    }

    /** Portada de la notificación: la miniatura del vídeo de YouTube (en otras webs, ninguna). */
    private fun loadArtwork(state: ExploreBackgroundPlayback.State) {
        val videoId = state.videoId
        val url = videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        if (url == artworkUrl) return
        artworkUrl = url
        artwork = null
        if (url == null) return
        loader?.execute {
            val bitmap = runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            main.post {
                if (bitmap != null && artworkUrl == url && instance?.get() === this) {
                    artwork = bitmap
                    showNotification()
                }
            }
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ExploreMediaService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.explore_background_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.removePrefix("m.").orEmpty()

    // ------------------------------------------------------------------------------------------
    // CPU y Wi-Fi: que la pantalla apagada no corte el audio
    // ------------------------------------------------------------------------------------------

    private fun updateLocks(playing: Boolean) {
        if (!playing) {
            releaseLocks()
            return
        }
        runCatching {
            val wake = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
                .also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            // se renueva con cada aviso de la página; si la app muriera sin soltarlo, caduca solo
            wake.acquire(LOCK_TIMEOUT_MS)
        }
        runCatching {
            @Suppress("DEPRECATION")
            val wifi = wifiLock ?: (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, LOCK_TAG)
                .also {
                    it.setReferenceCounted(false)
                    wifiLock = it
                }
            if (!wifi.isHeld) wifi.acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
    }

    // ------------------------------------------------------------------------------------------
    // Cerrar
    // ------------------------------------------------------------------------------------------

    /** El usuario cerró la notificación (o la pausa duró demasiado): se pausa la página y se libera todo. */
    private fun closeFromUser() {
        ExploreBackgroundPlayback.command("pause")
        ExploreBackgroundPlayback.command("suspend")
        stopEverything()
    }

    private fun stopEverything() {
        main.removeCallbacks(pausedTooLong)
        releaseLocks()
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "explore_background"
        private const val NOTIFICATION_ID = 0x4E44
        private const val SESSION_TAG = "NeonExplore"
        private const val LOCK_TAG = "NeonDownloader:explore"

        private const val ACTION_SHOW = "com.deniscerri.ytdl.explore.SHOW"
        private const val ACTION_TOGGLE = "com.deniscerri.ytdl.explore.TOGGLE"
        private const val ACTION_CLOSE = "com.deniscerri.ytdl.explore.CLOSE"
        private const val ACTION_NEXT = "com.deniscerri.ytdl.explore.NEXT"
        private const val ACTION_PREVIOUS = "com.deniscerri.ytdl.explore.PREVIOUS"

        private const val REQUEST_TOGGLE = 1
        private const val REQUEST_CLOSE = 2
        private const val REQUEST_OPEN = 3
        private const val REQUEST_NEXT = 4
        private const val REQUEST_PREVIOUS = 5

        private const val LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L
        private const val PAUSED_TIMEOUT_MS = 20 * 60 * 1000L

        private var instance: WeakReference<ExploreMediaService>? = null

        /** false entre [stop] y el siguiente [start]: un arranque que llegue tarde se cierra solo. */
        private var wanted = false

        /** El servicio está en marcha o a punto de arrancar. */
        val active: Boolean
            get() = wanted

        /** Empieza a mantener viva la página (lo normal: en cuanto algo suena, con la app a la vista). false si Android no lo deja. */
        fun start(context: Context): Boolean {
            wanted = true
            val app = context.applicationContext
            return try {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, ExploreMediaService::class.java).setAction(ACTION_SHOW)
                )
                true
            } catch (e: Exception) {
                wanted = false
                false
            }
        }

        /** La página cambió (título, pausa, segundo...): se actualiza la notificación si está a la vista. */
        fun refresh() {
            instance?.get()?.showNotification()
        }

        /** La página se cerró o se pasó a "Escuchar": ya no hace falta el servicio. */
        fun stop() {
            wanted = false
            instance?.get()?.stopEverything()
        }
    }
}
