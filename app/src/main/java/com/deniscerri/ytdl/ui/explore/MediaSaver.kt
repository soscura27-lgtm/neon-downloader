package com.deniscerri.ytdl.ui.explore

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.WebSettings
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Guarda y comparte imágenes, GIF y stickers que se ven en Explorar (comentarios, publicaciones, miniaturas,
 * Tenor...). Se guardan en Imágenes/Neon Downloader (los vídeos cortos, en Películas/Neon Downloader) y se
 * comparten con cualquier app (WhatsApp, Telegram...) desde el menú de compartir de Android.
 *
 * Cada elemento se pide como una lista de direcciones candidatas, de la mejor a la peor: se usa la primera
 * que responda con una imagen o un vídeo.
 */
object MediaSaver {

    private const val FOLDER = "Neon Downloader"
    private const val SHARE_DIR = "shared_media"
    private const val MAX_BYTES = 150L * 1024 * 1024
    private const val SHARE_MAX_AGE_MS = 24L * 60 * 60 * 1000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Resultado de guardar varias imágenes: cuántas se guardaron y en qué carpeta. */
    class SaveResult(val saved: Int, val failed: Int, val folder: String?)

    /** Archivo temporal ya descargado, con su tipo. */
    private class Fetched(val file: File, val mime: String, val extension: String) {
        val isVideo: Boolean get() = mime.startsWith("video/")
    }

    // ------------------------------------------------------------------------------------------
    // Direcciones
    // ------------------------------------------------------------------------------------------

    /**
     * Direcciones candidatas para una imagen vista en la página, de mejor a peor calidad:
     *  - imágenes de Google (yt3.ggpht.com, *.googleusercontent.com): "=s0" pide el archivo original;
     *  - miniaturas de YouTube (i.ytimg.com): de maxresdefault a hqdefault;
     *  - y siempre, al final, la dirección tal cual.
     */
    fun candidatesFor(src: String): List<String> {
        val result = LinkedHashSet<String>()
        val uri = runCatching { Uri.parse(src) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT).orEmpty()
        if (host.endsWith("ggpht.com") || host.endsWith("googleusercontent.com")) {
            val slash = src.lastIndexOf('/')
            val equals = src.lastIndexOf('=')
            if (equals > slash && slash > 0) result.add(src.substring(0, equals) + "=s0")
        }
        if (host.endsWith("ytimg.com")) {
            Regex("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/").find(src)?.let { match ->
                result.addAll(YoutubeUrls.thumbnailUrls(match.groupValues[1]))
            }
        }
        result.add(src)
        return result.toList()
    }

    // ------------------------------------------------------------------------------------------
    // Guardar
    // ------------------------------------------------------------------------------------------

    /** Guarda cada elemento de [items] (cada uno con sus direcciones candidatas). */
    suspend fun save(
        context: Context,
        items: List<List<String>>,
        referer: String?,
        baseName: String?
    ): SaveResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        var saved = 0
        var failed = 0
        var folder: String? = null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val base = cleanName(baseName) ?: "NeonDownloader_$stamp"
        items.forEachIndexed { index, candidates ->
            val fetched = fetchFirst(app, candidates, referer)
            if (fetched == null) {
                failed++
                return@forEachIndexed
            }
            try {
                val name = if (items.size > 1) "${base}_${index + 1}" else base
                val where = store(app, fetched, name)
                if (where != null) {
                    saved++
                    folder = where
                } else {
                    failed++
                }
            } finally {
                fetched.file.delete()
            }
        }
        SaveResult(saved, failed, folder)
    }

    private fun store(context: Context, fetched: Fetched, name: String): String? {
        val dirType = if (fetched.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val displayName = "$name.${fetched.extension}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (fetched.isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            insertIntoMediaStore(context, collection, fetched, displayName, "$dirType/$FOLDER")
                ?.let { return it }
            // tipos que la galería no acepta (p. ej. AVIF en Android antiguos): a Descargas
            return insertIntoMediaStore(
                context,
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                fetched,
                displayName,
                "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
            )
        }

        // Android 9 o anterior: carpeta pública (con permiso de almacenamiento) o, si no hay permiso, la de la app
        @Suppress("DEPRECATION")
        val publicDir = File(Environment.getExternalStoragePublicDirectory(dirType), FOLDER)
        val target = copyInto(publicDir, fetched, name)
            ?: copyInto(context.getExternalFilesDir(dirType)?.let { File(it, FOLDER) }, fetched, name)
            ?: return null
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(fetched.mime), null)
        return target.parent
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoMediaStore(
        context: Context,
        collection: Uri,
        fetched: Fetched,
        displayName: String,
        relativePath: String
    ): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, fetched.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("sin salida")
            out.use { stream -> fetched.file.inputStream().use { it.copyTo(stream) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            relativePath
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun copyInto(dir: File?, fetched: Fetched, name: String): File? {
        if (dir == null) return null
        return try {
            if (!dir.exists() && !dir.mkdirs()) return null
            var target = File(dir, "$name.${fetched.extension}")
            var n = 1
            while (target.exists()) {
                target = File(dir, "$name ($n).${fetched.extension}")
                n++
            }
            fetched.file.copyTo(target)
            target
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------------------------------
    // Compartir
    // ------------------------------------------------------------------------------------------

    /** Descarga la imagen (o GIF, o sticker) y devuelve el menú de compartir de Android, o null si no se pudo. */
    suspend fun shareIntent(
        context: Context,
        candidates: List<String>,
        referer: String?,
        baseName: String?
    ): Intent? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val fetched = fetchFirst(app, candidates, referer) ?: return@withContext null
        try {
            val dir = File(app.cacheDir, SHARE_DIR)
            if (!dir.exists()) dir.mkdirs()
            // lo compartido antes ya no hace falta
            val now = System.currentTimeMillis()
            dir.listFiles()?.filter { now - it.lastModified() > SHARE_MAX_AGE_MS }?.forEach { it.delete() }

            val name = cleanName(baseName) ?: "NeonDownloader"
            val out = File(dir, "$name.${fetched.extension}")
            fetched.file.copyTo(out, overwrite = true)
            val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = fetched.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(send, null)
        } catch (e: Exception) {
            null
        } finally {
            fetched.file.delete()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Descarga
    // ------------------------------------------------------------------------------------------

    private fun fetchFirst(context: Context, candidates: List<String>, referer: String?): Fetched? {
        for (url in candidates) {
            val fetched = when {
                url.startsWith("data:", ignoreCase = true) -> decodeDataUrl(context, url)
                url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
                    download(context, url, referer)
                else -> null
            }
            if (fetched != null) return fetched
        }
        return null
    }

    private fun download(context: Context, url: String, referer: String?): Fetched? {
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("User-Agent", userAgent(context))
                .header("Accept", "image/avif,image/webp,image/apng,image/gif,image/*,video/mp4,video/webm;q=0.9,*/*;q=0.8")
                .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                .build()
        }.getOrNull() ?: return null

        val temp = File.createTempFile("neon_media", ".tmp", context.cacheDir)
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                if (body.contentLength() > MAX_BYTES) return@use null
                var total = 0L
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) throw IOException("demasiado grande")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val header = body.contentType()?.let { "${it.type}/${it.subtype}" }?.lowercase(Locale.ROOT)
                typed(temp, header)
            }
        } catch (e: Exception) {
            null
        } ?: run {
            temp.delete()
            null
        }
    }

    private fun decodeDataUrl(context: Context, url: String): Fetched? {
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        if (!meta.contains(";base64", ignoreCase = true)) return null
        val bytes = runCatching { Base64.decode(url.substring(comma + 1), Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val temp = File.createTempFile("neon_media", ".tmp", context.cacheDir)
        temp.writeBytes(bytes)
        return typed(temp, meta.substringBefore(';').lowercase(Locale.ROOT)) ?: run {
            temp.delete()
            null
        }
    }

    /** Decide el tipo por los primeros bytes (más fiable que la cabecera) y, si no se reconoce, por la cabecera. */
    private fun typed(file: File, header: String?): Fetched? {
        val sniffed = sniff(file)
        val mime = sniffed
            ?: header?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
            ?: return null
        val extension = when (mime) {
            "image/gif" -> "gif"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/avif" -> "avif"
            "image/heic" -> "heic"
            "image/svg+xml" -> "svg"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            else -> mime.substringAfter('/').substringBefore('+').ifBlank { "bin" }
        }
        return Fetched(file, mime, extension)
    }

    private fun sniff(file: File): String? {
        val head = ByteArray(16)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (read < 12) return null
        fun ascii(from: Int, to: Int) = String(head, from, to - from, Charsets.US_ASCII)
        return when {
            ascii(0, 4) == "GIF8" -> "image/gif"
            head[0] == 0x89.toByte() && ascii(1, 4) == "PNG" -> "image/png"
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
            ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP" -> "image/webp"
            ascii(4, 8) == "ftyp" -> when (ascii(8, 12)) {
                "avif", "avis" -> "image/avif"
                "heic", "heix", "mif1" -> "image/heic"
                else -> "video/mp4"
            }
            head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                    head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> "video/webm"
            else -> null
        }
    }

    private fun userAgent(context: Context): String {
        return runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
            ?.replace("; wv", "")
            ?.replace(" Version/4.0", "")
            ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
    }

    /** Nombre de archivo seguro a partir del título de la página (o null si no queda nada útil). */
    private fun cleanName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
            .trim()
        return cleaned.ifBlank { null }
    }
}
