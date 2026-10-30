package com.tvhome.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Only exists so Android lets us read what's playing (Spotify, YouTube Music, VLC...).
 * Grant once from the PC:
 *   adb shell cmd notification allow_listener com.tvhome.launcher/com.tvhome.launcher.MediaListener
 */
class MediaListener : NotificationListenerService()

data class NowPlaying(
    val title: String,
    val artist: String,
    val app: String,
    val art: Bitmap?,
    val artUri: String?,
    val tint: Color?,
    val playing: Boolean,
    val controller: MediaController,
)

class NowPlayingTracker(private val context: Context) {
    var state by mutableStateOf<NowPlaying?>(null)
        private set
    var access by mutableStateOf(true)
        private set

    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackState?) = refresh()
        override fun onMetadataChanged(m: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }

    fun start() {
        if (started) return
        try {
            msm.addOnActiveSessionsChangedListener(sessions, component, handler)
            attach(msm.getActiveSessions(component))
            access = true
            started = true
        } catch (e: SecurityException) {
            access = false
        }
    }

    fun stop() {
        if (!started) return
        runCatching { msm.removeOnActiveSessionsChangedListener(sessions) }
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = emptyList()
        started = false
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list
        list.forEach { it.registerCallback(callback, handler) }
        refresh()
    }

    private fun refresh() {
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
        val md = c?.metadata
        if (c == null || md == null) { state = null; return }

        val raw = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = raw?.let { runCatching { Bitmap.createScaledBitmap(it, 192, 192, true) }.getOrNull() }
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        if (title.isBlank()) { state = null; return }

        state = NowPlaying(
            title = title,
            artist = artist,
            app = appLabel(c.packageName),
            art = art,
            artUri = uri,
            tint = art?.let(::averageColor),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            controller = c,
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("Music")

    /** Average colour of the album art, darkened so white text stays readable on the card. */
    private fun averageColor(b: Bitmap): Color? = runCatching {
        val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
        val r = ((px shr 16) and 0xFF) * 0.45f / 255f
        val g = ((px shr 8) and 0xFF) * 0.45f / 255f
        val bl = (px and 0xFF) * 0.45f / 255f
        Color(r, g, bl, 0.78f)
    }.getOrNull()
}

/** Listens only while Home is on screen. Retries on every return, so access granted later just works. */
@Composable
fun rememberNowPlaying(): NowPlayingTracker {
    val ctx = LocalContext.current.applicationContext
    val tracker = remember { NowPlayingTracker(ctx) }
    LifecycleResumeEffect(Unit) {
        tracker.start()
        onPauseOrDispose { tracker.stop() }
    }
    return tracker
}
