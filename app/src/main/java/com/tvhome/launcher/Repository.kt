package com.tvhome.launcher

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val EPG_PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA"

class Repository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    // Internet data (YouTube, TMDB) and app banners are cached, so pressing Home is instant.
    // Play Next and app rows are local and cheap, so they refresh every time.
    private class Web(val yt: List<MediaRow>, val subs: MediaRow?, val tmdb: List<MediaItem>)
    private var web: Web? = null
    private var webAt = 0L
    private var appsCache: List<AppItem>? = null
    private var appsAt = 0L

    /** Builds the whole home screen. Runs on a background thread. */
    fun load(): HomeData {
        val cfg = runCatching { Config.load(context) }.getOrElse { Config("", emptyList(), emptyList()) }
        val now = System.currentTimeMillis()

        val granted = context.checkSelfPermission(EPG_PERMISSION) == PackageManager.PERMISSION_GRANTED
        var blocked = false
        var playNext = try {
            loadPlayNext()
        } catch (e: SecurityException) {
            blocked = true
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val channels = runCatching { loadAppChannels() }.getOrDefault(emptyList())

        if (web == null || now - webAt > 30 * 60_000) {
            val yt = cfg.youtubeRows.mapIndexedNotNull { i, r ->
                val vids = runCatching { youtubeVideos(r) }.getOrDefault(emptyList())
                if (vids.isEmpty()) null else MediaRow("yt$i", r.title, vids.map(::youtubeItem))
            }
            val subs = runCatching { loadSubscriptions() }.getOrNull()
            val tm = if (cfg.tmdbApiKey.isNotBlank()) runCatching { fetchTmdb(cfg.tmdbApiKey) }.getOrDefault(emptyList())
            else emptyList()
            web = Web(yt, subs, tm)
            webAt = now
        }
        val w = web!!

        val realPlayNextCount = playNext.size
        if (playNext.isEmpty() && BuildConfig.IS_PHONE) playNext = samplePlayNext(w)

        if (appsCache == null || now - appsAt > 10 * 60_000) {
            appsCache = loadApps()
            appsAt = now
        }

        val rows = buildList {
            if (playNext.isNotEmpty()) add(MediaRow("cw", "Continue watching", playNext))
            addAll(channels)
            w.subs?.let(::add)
            addAll(w.yt)
        }
        val heroPlayNext = playNext.filter { it.image != null }.take(6)
            .ifEmpty { channels.flatMap { it.items }.filter { it.image != null }.take(6) }
            .ifEmpty { w.tmdb }
        val heroYouTube = (w.subs?.items.orEmpty() + w.yt.flatMap { it.items }).take(6)

        return HomeData(
            rows = rows,
            apps = appsCache!!,
            defaultFavs = cfg.favoriteApps,
            heroPlayNext = heroPlayNext,
            heroYouTube = heroYouTube,
            access = when {
                BuildConfig.IS_PHONE -> Access.OK
                !granted -> Access.NOT_ALLOWED
                blocked -> Access.BLOCKED
                else -> Access.OK
            },
            playNextCount = realPlayNextCount,
            loaded = true,
            cfg = cfg,
        )
    }

    // ---------- TvProvider: Play Next + app rows ----------

    /** Same row the stock Android TV home shows as "Play Next". Updates as you watch. */
    private fun loadPlayNext(): List<MediaItem> =
        context.contentResolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI, null, null, null,
            "last_engagement_time_utc_millis DESC"
        )?.use { readPrograms(it, 20) } ?: emptyList()

    /**
     * Rows apps publish to the home screen themselves (YouTube recommendations,
     * Kodi "Recently added"...). Fully dynamic, no links needed.
     */
    private fun loadAppChannels(): List<MediaRow> {
        val rows = mutableListOf<MediaRow>()
        context.contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            val iId = c.getColumnIndex("_id")
            val iName = c.getColumnIndex("display_name")
            val iPkg = c.getColumnIndex("package_name")
            val iType = c.getColumnIndex("type")
            val iBrowsable = c.getColumnIndex("browsable")
            while (c.moveToNext() && rows.size < 8) {
                if (iType >= 0 && c.getString(iType) != TvContract.Channels.TYPE_PREVIEW) continue
                if (iBrowsable >= 0 && c.getInt(iBrowsable) == 0) continue
                val pkg = if (iPkg >= 0) c.getString(iPkg) else null
                if (pkg == context.packageName) continue
                val id = c.getLong(iId)
                val items = runCatching {
                    context.contentResolver.query(
                        TvContract.buildPreviewProgramsUriForChannel(id), null, null, null, null
                    )?.use { readPrograms(it, 20) }
                }.getOrNull().orEmpty()
                if (items.isEmpty()) continue
                val name = (if (iName >= 0) c.getString(iName) else null) ?: "Recommended"
                val app = pkg?.let(::appLabel)
                val title = if (app != null && !name.contains(app, ignoreCase = true)) "$name · $app" else name
                rows += MediaRow("ch:$id", title, items)
            }
        }
        return rows
    }

    /** Shared reader for Watch Next and Preview programs (same column names). */
    private fun readPrograms(c: Cursor, limit: Int): List<MediaItem> {
        fun str(col: String): String? = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
        fun num(col: String): Long = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L

        val out = mutableListOf<MediaItem>()
        while (c.moveToNext() && out.size < limit) {
            val title = str("title") ?: continue
            val pos = num("last_playback_position_millis")
            val dur = num("duration_millis")
            val season = str("season_display_number")?.let { "S$it" }
            val episode = str("episode_display_number")?.let { "E$it" }
            val ep = listOfNotNull(season, episode).joinToString(" ").ifBlank { null }
            val poster = str("poster_art_uri") ?: str("thumbnail_uri")
            out += MediaItem(
                title = title,
                subtitle = ep ?: str("episode_title"),
                image = poster,
                backdrop = poster,
                description = str("short_description"),
                source = str("package_name")?.let(::appLabel),
                progress = if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null,
                intent = str("intent_uri")?.let {
                    runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull()
                },
            )
        }
        return out
    }

    private fun appLabel(pkg: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    // ---------- Installed apps ----------

    private fun loadApps(): List<AppItem> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<AppItem>()
        val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)

        for (cat in categories) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || !seen.add(pkg)) continue
                val launch = (if (cat == Intent.CATEGORY_LEANBACK_LAUNCHER) pm.getLeanbackLaunchIntentForPackage(pkg) else null)
                    ?: pm.getLaunchIntentForPackage(pkg) ?: continue
                val banner = ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
                out += AppItem(
                    pkg = pkg,
                    label = ri.loadLabel(pm).toString(),
                    banner = banner?.let { runCatching { it.toBitmap(320, 180).asImageBitmap() }.getOrNull() },
                    icon = if (banner == null) runCatching { ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap() }.getOrNull() else null,
                    intent = launch,
                )
            }
            // On a TV, TV apps are enough. Phone-only apps would just clutter the list.
            if (cat == Intent.CATEGORY_LEANBACK_LAUNCHER && out.isNotEmpty() && !BuildConfig.IS_PHONE) break
        }
        return out.sortedBy { it.label.lowercase() }
    }

    // ---------- YouTube (channel/playlist feeds: always the latest uploads, no API key) ----------

    private fun youtubeVideos(r: YtRow): List<YtVideo> {
        val q = r.channelId?.let { "channel_id=$it" } ?: r.playlistId?.let { "playlist_id=$it" } ?: return r.videos
        return feed(q)
    }

    private fun feed(query: String): List<YtVideo> {
        val xml = httpGet("https://www.youtube.com/feeds/videos.xml?$query")
        val idRe = Regex("<yt:videoId>([^<]+)</yt:videoId>")
        val titleRe = Regex("<title>([^<]*)</title>")
        val nameRe = Regex("<name>([^<]*)</name>")
        val pubRe = Regex("<published>([^<]+)</published>")
        return xml.split("<entry>").drop(1).mapNotNull { e ->
            val id = idRe.find(e)?.groupValues?.get(1) ?: return@mapNotNull null
            YtVideo(
                id = id,
                title = titleRe.find(e)?.groupValues?.get(1)?.let(::unescape) ?: "YouTube video",
                channel = nameRe.find(e)?.groupValues?.get(1)?.let(::unescape).orEmpty(),
                published = pubRe.find(e)?.groupValues?.get(1).orEmpty(),
            )
        }.take(15)
    }

    /**
     * "Subscriptions" row: latest uploads from your channels, newest first.
     * Reads subscriptions.csv from Google Takeout (YouTube > subscriptions), either built into
     * the app (assets/) or pushed to /sdcard/Android/data/<package>/files/subscriptions.csv.
     */
    private fun loadSubscriptions(): MediaRow? {
        val ext = context.getExternalFilesDir(null)?.let { File(it, "subscriptions.csv") }
        val text = if (ext != null && ext.exists()) ext.readText()
        else runCatching { context.assets.open("subscriptions.csv").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return null
        val ids = text.lineSequence()
            .map { it.substringBefore(',').trim().trim('"') }
            .filter { it.startsWith("UC") }   // skips the header line
            .distinct().take(40).toList()
        if (ids.isEmpty()) return null

        val pool = Executors.newFixedThreadPool(6)
        try {
            val jobs = ids.map { id -> pool.submit(Callable { runCatching { feed("channel_id=$id").take(5) }.getOrDefault(emptyList()) }) }
            val vids = jobs.flatMap { runCatching { it.get(20, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
            val items = vids.sortedByDescending { it.published }.take(24).map(::youtubeItem)
            return if (items.isEmpty()) null else MediaRow("subs", "Subscriptions", items)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun unescape(s: String) = s
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun youtubeItem(v: YtVideo) = MediaItem(
        title = v.title,
        subtitle = v.channel.ifBlank { "YouTube" },
        image = "https://i.ytimg.com/vi/${v.id}/hqdefault.jpg",
        backdrop = "https://i.ytimg.com/vi/${v.id}/maxresdefault.jpg",
        source = if (v.channel.isBlank()) "YouTube" else "YouTube · ${v.channel}",
        intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${v.id}")),
    )

    /** Phones have no Play Next database, so the test build fills the row with sample items. */
    private fun samplePlayNext(w: Web): List<MediaItem> {
        val progress = listOf(0.62f, 0.3f, 0.68f, 0.15f, 0.8f)
        return (w.subs?.items.orEmpty() + w.yt.flatMap { it.items }).take(5).mapIndexed { i, item ->
            item.copy(subtitle = "Sample", progress = progress[i % progress.size])
        }
    }

    // ---------- TMDB (optional, needs a free API key in config.json) ----------

    private fun fetchTmdb(key: String): List<MediaItem> {
        val text = httpGet("https://api.themoviedb.org/3/trending/all/day?api_key=" + URLEncoder.encode(key, "UTF-8"))
        val results = JSONObject(text).getJSONArray("results")
        return (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val title = o.optString("title").ifBlank { o.optString("name") }
            val year = o.optString("release_date").ifBlank { o.optString("first_air_date") }.take(4)
            val kind = if (o.optString("media_type") == "tv") "Series" else "Movie"
            MediaItem(
                title = title,
                image = "https://image.tmdb.org/t/p/w780$backdrop",
                backdrop = "https://image.tmdb.org/t/p/w1280$backdrop",
                description = o.optString("overview"),
                meta = listOf(year, kind).filter { it.isNotBlank() },
                source = "Trending today",
                intent = Intent("android.search.action.GLOBAL_SEARCH").putExtra(SearchManager.QUERY, title),
            )
        }.take(8)
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }
}
