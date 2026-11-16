package com.deniscerri.ytdl.ui.more

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.BuildConfig
import com.deniscerri.ytdl.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * "Versión y novedades": qué compilación está instalada (número, fecha y cambio de GitHub), la lista de cambios y si
 * ya hay una compilación nueva publicada en GitHub.
 *
 * Al abrir la app después de instalar otra compilación aparece sola como "¡Actualizada!", con lo nuevo desde la
 * versión anterior marcado: así se ve de un vistazo que la actualización se instaló bien.
 *
 * El número de compilación, el commit y la fecha vienen de BuildConfig (app/build.gradle); el historial, de
 * assets/neon_changelog.json, que GitHub Actions genera desde git en cada compilación (scripts/ci/changelog.py).
 */
object NeonUpdates {

    private const val PREF_LAST_BUILD = "neon_last_build"
    private const val PREF_LAST_COMMIT = "neon_last_commit"
    private const val CHANGELOG_ASSET = "neon_changelog.json"
    private const val MAX_SHOWN = 60

    data class Change(val sha: String, val time: Long, val title: String, val body: String)

    /** La compilación publicada en GitHub Releases. */
    private class Published(val build: Int, val time: Long, val apkUrl: String?, val pageUrl: String?)

    /** Texto corto para la fila del menú: "Versión y novedades · #12". */
    fun menuLabel(context: Context): String {
        val title = context.getString(R.string.neon_updates_title)
        return if (BuildConfig.NEON_BUILD > 0) "$title · #${BuildConfig.NEON_BUILD}" else title
    }

    /**
     * Al abrir la app: si es una compilación distinta de la última que se abrió, se enseña el panel como
     * "¡Actualizada!". En una instalación nueva solo se apunta la compilación.
     */
    fun showIfUpdated(activity: FragmentActivity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val lastBuild = prefs.getInt(PREF_LAST_BUILD, -1)
        val lastCommit = prefs.getString(PREF_LAST_COMMIT, null)
        val build = BuildConfig.NEON_BUILD
        val commit = BuildConfig.NEON_COMMIT
        if (lastBuild == build && lastCommit == commit) return
        prefs.edit().putInt(PREF_LAST_BUILD, build).putString(PREF_LAST_COMMIT, commit).apply()
        if (lastBuild == -1 && isFreshInstall(activity)) return
        show(activity, previousCommit = lastCommit, justUpdated = true)
    }

    /** El panel, desde Más o desde el menú de Explorar (o solo tras una actualización). */
    fun show(activity: FragmentActivity, previousCommit: String? = null, justUpdated: Boolean = false): BottomSheetDialog {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_neon_updates, null)

        // cabecera
        view.findViewById<TextView>(R.id.updates_title).setText(
            if (justUpdated) R.string.neon_updates_updated else R.string.neon_updates_title
        )
        view.findViewById<TextView>(R.id.updates_subtitle).apply {
            isVisible = justUpdated
            text = if (BuildConfig.NEON_BUILD > 0) {
                activity.getString(R.string.neon_updates_installed, BuildConfig.NEON_BUILD)
            } else {
                activity.getString(R.string.neon_updates_installed_local)
            }
        }

        // lo que está instalado
        view.findViewById<TextView>(R.id.updates_version).text =
            activity.getString(R.string.neon_updates_version, baseVersion())
        view.findViewById<TextView>(R.id.updates_build).text = if (BuildConfig.NEON_BUILD > 0) {
            activity.getString(R.string.neon_updates_build, BuildConfig.NEON_BUILD, formatDate(BuildConfig.NEON_BUILD_TIME))
        } else {
            activity.getString(R.string.neon_updates_build_local, formatDate(BuildConfig.NEON_BUILD_TIME))
        }
        view.findViewById<TextView>(R.id.updates_commit).text =
            activity.getString(R.string.neon_updates_commit, BuildConfig.NEON_COMMIT, BuildConfig.NEON_BRANCH)

        setupCheck(activity, view)

        // historial de cambios
        val changes = changelog(activity)
        val newCount = newChangesCount(changes, previousCommit)
        view.findViewById<TextView>(R.id.updates_new_count).apply {
            isVisible = justUpdated && newCount > 0
            text = activity.getString(R.string.neon_updates_new_count, newCount)
        }
        view.findViewById<TextView>(R.id.updates_empty).isVisible = changes.isEmpty()
        val list = view.findViewById<LinearLayout>(R.id.updates_list)
        val inflater = LayoutInflater.from(activity)
        changes.take(MAX_SHOWN).forEachIndexed { index, change ->
            val row = inflater.inflate(R.layout.item_neon_change, list, false)
            row.findViewById<TextView>(R.id.change_date).text = formatDate(change.time * 1000)
            row.findViewById<TextView>(R.id.change_sha).text = change.sha.take(7)
            row.findViewById<View>(R.id.change_badge).isVisible = index < newCount
            row.findViewById<TextView>(R.id.change_title).text = change.title
            val body = row.findViewById<TextView>(R.id.change_body)
            body.text = change.body
            // los detalles se abren al tocar; los cambios nuevos se ven abiertos desde el principio
            body.isVisible = justUpdated && index < newCount && change.body.isNotBlank()
            row.setOnClickListener {
                if (change.body.isNotBlank()) body.isVisible = !body.isVisible
            }
            list.addView(row)
        }

        dialog.setContentView(view)
        dialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
        return dialog
    }

    fun changelog(context: Context): List<Change> {
        return runCatching {
            val text = context.assets.open(CHANGELOG_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                Change(
                    sha = item.optString("sha"),
                    time = item.optLong("time"),
                    title = item.optString("title"),
                    body = item.optString("body")
                ).takeIf { it.sha.isNotBlank() && it.title.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** Cambios que van antes del que tenía la versión anterior (la lista va del más nuevo al más viejo). */
    private fun newChangesCount(changes: List<Change>, previousCommit: String?): Int {
        if (previousCommit.isNullOrBlank() || previousCommit == "local") return 0
        val index = changes.indexOfFirst { it.sha.startsWith(previousCommit) }
        return if (index < 0) 0 else index
    }

    // ------------------------------------------------------------------------------------------
    // ¿Hay una compilación nueva?
    // ------------------------------------------------------------------------------------------

    private fun setupCheck(activity: FragmentActivity, view: View) {
        val check = view.findViewById<MaterialButton>(R.id.updates_check)
        val progress = view.findViewById<CircularProgressIndicator>(R.id.updates_progress)
        val status = view.findViewById<TextView>(R.id.updates_status)
        val download = view.findViewById<MaterialButton>(R.id.updates_download)

        check.setOnClickListener {
            check.isEnabled = false
            progress.isVisible = true
            status.isVisible = true
            status.setText(R.string.neon_updates_checking)
            download.isVisible = false
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { latestPublished() } }
                check.isEnabled = true
                progress.isVisible = false
                val published = result.getOrNull()
                when {
                    result.isFailure -> status.text = activity.getString(
                        R.string.neon_updates_failed,
                        result.exceptionOrNull()?.message ?: activity.getString(R.string.network_error)
                    )
                    published == null -> status.setText(R.string.neon_updates_unknown)
                    published.build > BuildConfig.NEON_BUILD -> {
                        status.text = activity.getString(
                            R.string.neon_updates_available,
                            published.build,
                            formatDate(published.time)
                        )
                        val target = published.apkUrl ?: published.pageUrl
                        download.isVisible = target != null
                        download.setOnClickListener {
                            runCatching {
                                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                            }
                        }
                    }
                    else -> status.text = activity.getString(R.string.neon_updates_latest, BuildConfig.NEON_BUILD)
                }
            }
        }
    }

    /**
     * La compilación más nueva publicada para esta rama o para main (las notas de cada Release llevan
     * "neon-build:N", ver scripts/ci/publish_release.sh). null si no hay ninguna con ese dato.
     */
    private fun latestPublished(): Published? {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val tags = linkedSetOf(tagFor(BuildConfig.NEON_BRANCH), "apk-main")
        var best: Published? = null
        var lastError: Exception? = null
        for (tag in tags) {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.NEON_REPO}/releases/tags/$tag")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NeonDownloader")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val published = parseRelease(JSONObject(response.body.string())) ?: return@use
                    if (best == null || published.build > best!!.build) best = published
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (best == null && lastError != null) throw lastError!!
        return best
    }

    private fun parseRelease(json: JSONObject): Published? {
        val body = json.optString("body")
        val build = Regex("neon-build:(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        var apkUrl: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    break
                }
            }
        }
        return Published(
            build = build,
            time = parseIsoDate(json.optString("published_at")),
            apkUrl = apkUrl,
            pageUrl = json.optString("html_url").takeIf { it.isNotBlank() }
        )
    }

    private fun tagFor(branch: String): String {
        if (branch.isBlank() || branch == "local") return "apk-main"
        return "apk-" + branch.replace('/', '-')
    }

    // ------------------------------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------------------------------

    /** "1.9.1.1.12" -> "1.9.1.1" (el último número es la compilación, que se enseña aparte). */
    private fun baseVersion(): String {
        val name = BuildConfig.VERSION_NAME
        if (BuildConfig.NEON_BUILD <= 0) return name
        return name.removeSuffix(".${BuildConfig.NEON_BUILD}")
    }

    private fun formatDate(millis: Long): String {
        if (millis <= 0) return "—"
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
            .format(Date(millis))
    }

    private fun parseIsoDate(raw: String): Long {
        if (raw.isBlank()) return 0
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(raw)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun isFreshInstall(context: Context): Boolean {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.firstInstallTime == info.lastUpdateTime
        }.getOrDefault(false)
    }
}
