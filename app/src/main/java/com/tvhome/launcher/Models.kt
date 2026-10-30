package com.tvhome.launcher

import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap

/** Anything shown as a poster card or hero banner. */
data class MediaItem(
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,        // card artwork (URL or content:// URI)
    val backdrop: String? = null,     // wide hero artwork, falls back to image
    val description: String? = null,
    val meta: List<String> = emptyList(),
    val source: String? = null,       // "YouTube", "Netflix", "Trending today"...
    val progress: Float? = null,      // 0..1, Play Next only
    val intent: Intent? = null,
)

data class AppItem(
    val pkg: String,
    val label: String,
    val banner: ImageBitmap?,         // 16:9 TV banner if the app has one
    val icon: ImageBitmap?,           // fallback square icon
    val intent: Intent,
)

/** One poster row. id is stable so order/hidden settings survive reloads. */
class MediaRow(val id: String, val title: String, val items: List<MediaItem>)

/** Whether we can read Play Next and app rows from the TV. */
enum class Access { OK, NOT_ALLOWED, BLOCKED }

data class HomeData(
    val rows: List<MediaRow> = emptyList(),          // Continue watching, app rows, Subscriptions, YouTube
    val apps: List<AppItem> = emptyList(),           // all installed apps, A-Z
    val defaultFavs: List<String> = emptyList(),     // from config.json, used until you edit favourites
    val heroPlayNext: List<MediaItem> = emptyList(),
    val heroYouTube: List<MediaItem> = emptyList(),
    val access: Access = Access.OK,
    val playNextCount: Int = 0,
    val loaded: Boolean = false,
    val cfg: Config? = null,
)
