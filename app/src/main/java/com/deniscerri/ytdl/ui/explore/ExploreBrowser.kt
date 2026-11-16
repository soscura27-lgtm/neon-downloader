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

    const val DESKTOP_PLATFORM = "Linux"

    fun userAgent(context: Context, desktop: Boolean): String {
        val default = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
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
     * más claras: la cabecera X-Requested-With y la marca "Android WebView" de los client hints. En modo
     * escritorio los client hints dicen además que es un ordenador, igual que el user agent.
     */
    fun hideWebViewIdentity(settings: WebSettings, desktop: Boolean) {
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
                }
                WebSettingsCompat.setUserAgentMetadata(settings, builder.build())
            }
        }
    }
}
