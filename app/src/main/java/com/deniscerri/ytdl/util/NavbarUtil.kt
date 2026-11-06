package com.deniscerri.ytdl.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.PopupMenu
import androidx.core.view.forEach
import androidx.core.view.get
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.google.android.material.navigation.NavigationBarView


object NavbarUtil {

    /** Máximo de pestañas visibles a la vez: es lo que admite BottomNavigationView. */
    const val MAX_VISIBLE_ITEMS = 5

    /**
     * Orden por defecto de la barra, con los índices de R.menu.navbar_items_menu
     * (0 Inicio, 1 Descargas, 2 Más, 3 Cola, 4 Terminal, 5 Explorar); el "-" marca una pestaña oculta.
     */
    private const val DEFAULT_NAV_BAR = "5,0,1,3,2,-4"

    lateinit var settings : SharedPreferences

    fun init(context: Context){
        settings = PreferenceManager.getDefaultSharedPreferences(context)
    }

    private var navItems = mapOf(
        "Explore" to R.id.exploreFragment,
        "Home" to R.id.homeFragment,
        "History" to R.id.historyFragment,
        "Queue" to R.id.downloadQueueMainFragment,
        "Terminal" to R.id.terminalActivity,
        "More" to R.id.moreFragment
    )

    fun getStartFragmentId(context: Context) : Int {
        val pref = settings.getString("start_destination", "")!!
        if (pref == "") {
            return R.id.homeFragment
        }

        val startId = navItems[pref] ?: return R.id.homeFragment
        val items = getDefaultNavBarItems(context)
        val index = items.indexOfFirst { it.itemId == startId }
        val navBarHasPref = index >= 0 && getNavBarPrefs(items.size).contains(index.toString())
        return if (navBarHasPref) startId else R.id.homeFragment
    }

    fun setNavBarItems(items: List<MenuItem>, context: Context) {
        val prefString = mutableListOf<String>()
        val defaultNavBarItems = getDefaultNavBarItems(context)
        items.forEach { newItem ->
            val index = defaultNavBarItems.indexOfFirst { newItem.itemId == it.itemId }
            prefString.add(if (newItem.isVisible) index.toString() else "-$index")
        }
        settings.edit().putString("navigation_bar", prefString.joinToString(",")).apply()
    }

    fun setStartFragment(itemId: Int) {
        settings.edit().putString("start_destination", navItems.filter { it.value == itemId }.keys.first()).apply()
    }

    fun getNavBarItems(context: Context): List<MenuItem> {
        val p = PopupMenu(context, null)
        MenuInflater(context).inflate(R.menu.navbar_items_menu, p.menu)

        return try {
            getNavBarPrefs(p.menu.size()).map {
                p.menu[it.replace("-", "").toInt()].apply {
                    this.isVisible = !it.contains("-")
                }
            }
        } catch (e: Exception) {
            Log.e("fail to parse nav items", e.toString())
            getDefaultNavBarItems(context)
        }
    }

    /**
     * Lee la preferencia de la barra y la ajusta a la lista actual de pestañas ([total]):
     * descarta índices que no existen o están repetidos y añade al principio las pestañas que no
     * estaban cuando se guardó (por ejemplo Explorar). Una pestaña nueva solo queda visible si
     * todavía hay hueco en la barra. El resultado trae cada índice exactamente una vez.
     */
    private fun getNavBarPrefs(total: Int): List<String> {
        val stored = parseNavBarPrefs(settings.getString("navigation_bar", DEFAULT_NAV_BAR)!!, total)
            .ifEmpty { parseNavBarPrefs(DEFAULT_NAV_BAR, total) }

        val known = stored.map { it.removePrefix("-").toInt() }.toSet()
        var visible = stored.count { !it.startsWith("-") }
        val added = mutableListOf<String>()
        for (index in 0 until total) {
            if (index in known) continue
            if (visible < MAX_VISIBLE_ITEMS) {
                added.add(index.toString())
                visible++
            } else {
                added.add("-$index")
            }
        }
        return added + stored
    }

    private fun parseNavBarPrefs(value: String, total: Int): List<String> {
        val seen = mutableSetOf<Int>()
        val tokens = mutableListOf<String>()
        value.split(",").forEach { raw ->
            val token = raw.trim()
            val index = token.removePrefix("-").toIntOrNull() ?: return@forEach
            if (index in 0 until total && seen.add(index)) {
                tokens.add(if (token.startsWith("-")) "-$index" else index.toString())
            }
        }
        return tokens
    }

    private fun getDefaultNavBarItems(context: Context): List<MenuItem> {
        val p = PopupMenu(context, null)
        MenuInflater(context).inflate(R.menu.navbar_items_menu, p.menu)
        val navBarItems = mutableListOf<MenuItem>()
        p.menu.forEach {
            navBarItems.add(it)
        }
        return navBarItems
    }

    fun NavigationBarView.setLabelVisibility() {
        val labelVisibilityMode = when (
            settings.getString("label_visibility", "always")
        ) {
            "always" -> NavigationBarView.LABEL_VISIBILITY_LABELED
            "selected" -> NavigationBarView.LABEL_VISIBILITY_SELECTED
            "never" -> NavigationBarView.LABEL_VISIBILITY_UNLABELED
            else -> NavigationBarView.LABEL_VISIBILITY_AUTO
        }
        this.labelVisibilityMode = labelVisibilityMode
    }

    fun NavigationBarView.applyNavBarStyle(): Int {
        setLabelVisibility()

        val navBarItems = getNavBarItems(this.context)

        // el menú que trae el layout solo es la base: se vacía y se rehace con las pestañas elegidas
        this.menu.clear()

        // NavigationBarView lanza una excepción si se pasa de su máximo, así que nunca se supera
        var shown = 0
        navBarItems.forEach { navBarItem ->
            if (navBarItem.isVisible && shown < this.maxItemCount) {
                this.menu.add(
                    navBarItem.groupId,
                    navBarItem.itemId,
                    Menu.NONE,
                    navBarItem.title
                ).icon = navBarItem.icon
                shown++
            }
        }
        return getStartFragmentId(this.context)
    }
}
