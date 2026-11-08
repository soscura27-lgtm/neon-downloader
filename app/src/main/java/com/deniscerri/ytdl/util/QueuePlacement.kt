package com.deniscerri.ytdl.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lugar de la cola que el usuario eligió para unas descargas ("que sea la siguiente", "la 3").
 *
 * La petición se guarda por dirección y no depende de ninguna pantalla: DownloadQueueUtil.enqueue la
 * lee cuando el elemento ya está en la cola, salga de la hoja de formato, de una descarga directa o
 * de la selección masiva. Cada dirección se usa una sola vez y la petición caduca a los 10 minutos
 * (por si la descarga se cancela antes de llegar a la cola).
 *
 * Posición: 1 = la siguiente en empezar, 2 = la segunda... Las descargas que ya están en marcha no
 * cuentan. Si se piden varias direcciones a la vez, cada una que llega a la cola toma el siguiente
 * lugar (P, P+1, P+2...), así que mantienen su orden entre ellas.
 */
object QueuePlacement {

    /** Sin petición: al final de la cola, como siempre. */
    const val END = 0

    /** La siguiente en empezar. */
    const val NEXT = 1

    private const val TTL_MS = 10 * 60 * 1000L

    private class Group(start: Int, val expiresAt: Long) {
        private val next = AtomicInteger(start)
        fun take(): Int = next.getAndIncrement()
    }

    private val groups = ConcurrentHashMap<String, Group>()

    // Un mismo vídeo de YouTube puede llegar con direcciones distintas (m.youtube.com, &list=...): se compara por su id
    private val YOUTUBE_ID = Regex("""(?:youtube\.com/(?:watch\?(?:[^#]*&)?v=|shorts/|live/|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})""")

    private fun keyOf(url: String): String {
        val id = YOUTUBE_ID.find(url)?.groupValues?.get(1)
        return if (id != null) "yt:$id" else url
    }

    /** Pide [position] para estas direcciones. END (o menos) borra una petición anterior. */
    fun request(urls: Collection<String>, position: Int) {
        purgeExpired()
        if (position < NEXT) {
            urls.forEach { groups.remove(keyOf(it)) }
            return
        }
        val group = Group(position, System.currentTimeMillis() + TTL_MS)
        urls.forEach { groups[keyOf(it)] = group }
    }

    /** Devuelve (y borra) el lugar pedido para esta dirección, o null si no hay una petición vigente. */
    fun consume(url: String): Int? {
        val group = groups.remove(keyOf(url)) ?: return null
        if (group.expiresAt < System.currentTimeMillis()) return null
        return group.take()
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        groups.entries.removeAll { it.value.expiresAt < now }
    }
}
