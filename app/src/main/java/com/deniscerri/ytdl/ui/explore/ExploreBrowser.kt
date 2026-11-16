package com.deniscerri.ytdl.ui.explore

import android.content.Context
import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Identidad de los navegadores de Explorar (la pestaña y el visor de comentarios): se presentan como Chrome
 * para que YouTube y Google no los traten como un WebView incrustado.
 */
object ExploreBrowser {

    private const val DESKTOP_PLATFORM = "Linux"
    private const val ANDROID_PLATFORM = "Android"

    private fun defaultUserAgent(context: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")

    fun userAgent(context: Context, desktop: Boolean): String {
        val default = defaultUserAgent(context)
        val chrome = Regex("Chrome/([0-9.]+)").find(default)?.groupValues?.get(1) ?: "138.0.0.0"
        return if (desktop || default.isBlank()) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        } else {
            // sin las marcas de WebView ("; wv" y "Version/4.0") para que parezca Chrome
            default.replace("; wv", "").replace(" Version/4.0", "")
        }
    }

    /**
     * Google rechaza el inicio de sesión cuando detecta un WebView incrustado. Se quitan las dos pistas
     * más claras: la cabecera X-Requested-With y la marca "Android WebView" de los client hints. Además los
     * client hints dicen lo mismo que el user agent: ordenador en modo escritorio, Android en modo normal.
     */
    fun hideWebViewIdentity(context: Context, settings: WebSettings, desktop: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching {
                val current = WebSettingsCompat.getUserAgentMetadata(settings)
                val brands = current.brandVersionList.map { brand ->
                    if (brand.brand.contains("WebView", ignoreCase = true)) {
                        UserAgentMetadata.BrandVersion.Builder(brand).setBrand("Google Chrome").build()
                    } else {
                        brand
                    }
                }
                val builder = UserAgentMetadata.Builder(current).setBrandVersionList(brands)
                if (desktop) {
                    builder.setMobile(false).setPlatform(DESKTOP_PLATFORM)
                } else {
                    // teléfono: "Mobile" en el user agent; tableta: sin él (igual que Chrome)
                    builder.setMobile(defaultUserAgent(context).contains(" Mobile"))
                        .setPlatform(ANDROID_PLATFORM)
                }
                WebSettingsCompat.setUserAgentMetadata(settings, builder.build())
            }
        }
    }
}
