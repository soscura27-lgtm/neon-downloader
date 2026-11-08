package com.deniscerri.ytdl.ui.explore

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.deniscerri.ytdl.R
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bloqueador de anuncios del navegador de Explorar. Trabaja en dos capas, como uBlock Origin:
 *
 *  - Red: [intercept] responde en vacío a las peticiones de publicidad (dominios de anuncios de Google,
 *    /pagead/, estadísticas de anuncios de YouTube...) y entrega un "ad_status.js" falso para que YouTube
 *    no crea que falta el servicio de anuncios.
 *  - Página: [script] (res/raw/explore_adblock.js) se ejecuta al empezar cada documento de youtube.com,
 *    limpia los datos de anuncios de las respuestas del reproductor y de las listas, oculta lo que quede
 *    y silencia/acelera/omite los anuncios que lleguen a reproducirse.
 *
 * Es un bloqueo de mejor esfuerzo: YouTube cambia estas piezas a menudo y hay anuncios insertados en
 * el propio flujo de vídeo que no se pueden quitar de raíz (por eso se aceleran y se omiten).
 */
object AdBlocker {

    const val PREF_ENABLED = "explore_adblock"

    /** Orígenes donde se inyecta el script de página. */
    val ORIGIN_RULES: Set<String> = setOf("https://*.youtube.com", "https://youtube.com")

    private val BLOCKED_HOST_SUFFIXES = arrayOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "adtrafficquality.google",
        "2mdn.net",
        "imasdk.googleapis.com"
    )

    private val AD_SERVICE_HOST = Regex("adservice\\.google\\.[a-z.]+")

    private const val AD_STATUS_HOST = "static.doubleclick.net"
    private const val AD_STATUS_PATH = "/instream/ad_status.js"
    private const val AD_BREAK_PATH = "/youtubei/v1/player/ad_break"

    @Volatile
    var enabled: Boolean = true

    private val blocked = AtomicInteger(0)

    /** Peticiones de publicidad detenidas desde que se abrió la app. */
    val blockedCount: Int
        get() = blocked.get()

    @Volatile
    private var cachedScript: String? = null

    /** Texto del script de página (se lee una sola vez de res/raw). */
    fun script(context: Context): String {
        cachedScript?.let { return it }
        val text = context.applicationContext.resources.openRawResource(R.raw.explore_adblock)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        cachedScript = text
        return text
    }

    /**
     * Se llama desde WebViewClient.shouldInterceptRequest (hilo de red del WebView).
     * Devuelve la respuesta vacía que sustituye a la petición, o null si debe seguir su curso.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!enabled || request.isForMainFrame) return null
        val uri = request.url ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.path.orEmpty()

        if (host == AD_STATUS_HOST && path == AD_STATUS_PATH) {
            // YouTube comprueba esta variable para saber si el servicio de anuncios carga: se le dice que sí
            return response("application/javascript", "window.google_ad_status=1;")
        }
        if (isYoutube(host) && path == AD_BREAK_PATH) {
            blocked.incrementAndGet()
            return response("application/json", "{}")
        }
        if (!isAdRequest(host, path)) return null

        blocked.incrementAndGet()
        return response("text/plain", "")
    }

    private fun isYoutube(host: String) = host == "youtube.com" || host.endsWith(".youtube.com")

    private fun isGoogle(host: String) = host == "google.com" || host.endsWith(".google.com")

    private fun isAdRequest(host: String, path: String): Boolean {
        if (BLOCKED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) return true
        if (AD_SERVICE_HOST.matches(host)) return true
        if ((isYoutube(host) || isGoogle(host)) && path.startsWith("/pagead/")) return true
        if (isYoutube(host) && (path.startsWith("/api/stats/ads") || path.startsWith("/get_midroll_"))) return true
        return false
    }

    /**
     * Respuesta 200 vacía (y con CORS abierto): para la página es como si el anuncio no existiera,
     * y no como un error de red, que YouTube sí interpreta como "bloqueador detectado".
     */
    private fun response(mimeType: String, body: String): WebResourceResponse {
        return WebResourceResponse(
            mimeType,
            "UTF-8",
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store"
            ),
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        )
    }
}
