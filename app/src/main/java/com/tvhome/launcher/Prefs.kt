package com.tvhome.launcher

import android.content.Context

/** Everything you change from the remote. Saved on the TV. */
data class UiPrefs(
    val favs: List<String>? = null,          // null = use defaults from config.json
    val rowOrder: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val hero: String = HERO_WALLPAPER,
    val lite: Boolean = false,
    val clock: Boolean = true,
) {
    companion object {
        const val HERO_WALLPAPER = "wallpaper"
        const val HERO_PLAY_NEXT = "playnext"
        const val HERO_YOUTUBE = "youtube"
        const val HERO_OFF = "off"
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun load() = UiPrefs(
        favs = sp.getString("favs", null)?.split(',')?.filter { it.isNotBlank() },
        rowOrder = sp.getString("rowOrder", "").orEmpty().split(',').filter { it.isNotBlank() },
        hidden = sp.getStringSet("hidden", emptySet()).orEmpty().toSet(),
        // New key in round 2 so everyone starts on the wallpaper look
        hero = sp.getString("hero2", UiPrefs.HERO_WALLPAPER) ?: UiPrefs.HERO_WALLPAPER,
        lite = sp.getBoolean("lite", false),
        clock = sp.getBoolean("clock", true),
    )

    fun save(p: UiPrefs) {
        sp.edit().apply {
            if (p.favs == null) remove("favs") else putString("favs", p.favs.joinToString(","))
            putString("rowOrder", p.rowOrder.joinToString(","))
            putStringSet("hidden", p.hidden)
            putString("hero2", p.hero)
            putBoolean("lite", p.lite)
            putBoolean("clock", p.clock)
        }.apply()
    }
}
