package com.tvhome.launcher

import android.content.Context
import org.json.JSONObject
import java.io.File

data class YtVideo(
    val id: String,
    val title: String,
    val channel: String = "",
    val published: String = "",   // ISO date, sorts correctly as text
)
/** A row from a YouTube channel or playlist (auto-updating), or a fixed list of videos. */
data class YtRow(
    val title: String,
    val channelId: String? = null,
    val playlistId: String? = null,
    val videos: List<YtVideo> = emptyList(),
)

/** date is "MM-DD", e.g. "03-14". */
data class Birthday(val name: String, val date: String)

const val MY_HOLIDAYS_ICS =
    "https://calendar.google.com/calendar/ical/en.malaysia%23holiday%40group.v.calendar.google.com/public/basic.ics"

data class Config(
    val tmdbApiKey: String,
    val favoriteApps: List<String>,
    val youtubeRows: List<YtRow>,
    val name: String = "",
    val latitude: Double = 3.139,          // Kuala Lumpur, for weather
    val longitude: Double = 101.687,
    val prayerZone: String = "WLY01",      // JAKIM zone code
    val nbaTeam: String = "por",           // ESPN team code, "" = hide
    val calendarIcsUrls: List<String> = emptyList(),
    val holidayIcsUrl: String = MY_HOLIDAYS_ICS,
    val birthdays: List<Birthday> = emptyList(),
) {
    companion object {
        /**
         * Reads config.json. An override pushed with
         *   adb push config.json /sdcard/Android/data/com.tvhome.launcher/files/config.json
         * wins over the built-in default in assets/.
         * (The phone setup page in a later round will write this same file.)
         */
        fun load(context: Context): Config {
            val override = context.getExternalFilesDir(null)?.let { File(it, "config.json") }
            val text = if (override != null && override.exists()) override.readText()
            else context.assets.open("config.json").bufferedReader().use { it.readText() }
            return parse(text)
        }

        private fun parse(text: String): Config {
            val o = JSONObject(text)
            val fav = o.optJSONArray("favoriteApps")
            val rows = o.optJSONArray("youtubeRows")
            return Config(
                tmdbApiKey = o.optString("tmdbApiKey"),
                favoriteApps = fav?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                youtubeRows = rows?.let { a ->
                    (0 until a.length()).map { i ->
                        val r = a.getJSONObject(i)
                        val v = r.optJSONArray("videos")
                        YtRow(
                            title = r.getString("title"),
                            channelId = r.optString("channelId").ifBlank { null },
                            playlistId = r.optString("playlistId").ifBlank { null },
                            videos = v?.let { arr ->
                                (0 until arr.length()).map { j ->
                                    val x = arr.getJSONObject(j)
                                    YtVideo(x.getString("id"), x.optString("title", "YouTube video"))
                                }
                            } ?: emptyList(),
                        )
                    }
                } ?: emptyList(),
                name = o.optString("name"),
                latitude = o.optDouble("latitude", 3.139),
                longitude = o.optDouble("longitude", 101.687),
                prayerZone = o.optString("prayerZone", "WLY01").ifBlank { "WLY01" },
                nbaTeam = o.optString("nbaTeam", "por"),
                calendarIcsUrls = o.optJSONArray("calendarIcsUrls")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
                } ?: emptyList(),
                holidayIcsUrl = o.optString("holidayIcsUrl", MY_HOLIDAYS_ICS),
                birthdays = o.optJSONArray("birthdays")?.let { a ->
                    (0 until a.length()).map { i ->
                        val b = a.getJSONObject(i)
                        Birthday(b.getString("name"), b.getString("date"))
                    }
                } ?: emptyList(),
            )
        }
    }
}
