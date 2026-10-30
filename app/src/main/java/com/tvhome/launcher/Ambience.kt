package com.tvhome.launcher

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.File
import java.time.LocalDate

/** Time of day decides how bright the wallpaper is. */
enum class Scene { MORNING, DAY, EVENING, NIGHT }

/** Weather decides which wallpaper set is used. */
enum class Sky { CLEAR, CLOUDY, RAIN, STORM }

fun sceneFor(hour: Int): Scene = when (hour) {
    in 5..10 -> Scene.MORNING
    in 11..16 -> Scene.DAY
    in 17..19 -> Scene.EVENING
    else -> Scene.NIGHT
}

/** WMO weather code (Open-Meteo) to our 4 groups. */
fun skyFor(code: Int): Sky = when (code) {
    0, 1 -> Sky.CLEAR
    in 51..67, in 80..82 -> Sky.RAIN
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUDY
}

private val BlackBg = Color(0xFF08090B)

/**
 * Your own wallpapers, pushed to the TV:
 *   /sdcard/Android/data/com.tvhome.launcher/files/wallpapers/<scene>/<sky>/ (e.g. night/rain/)
 *   /sdcard/Android/data/com.tvhome.launcher/files/wallpapers/<scene>/       (any weather)
 * JPG, PNG or WebP files.
 * A different picture is picked each day. Nothing found = built-in drawn wallpaper.
 */
fun pickWallpaper(context: Context, scene: Scene, sky: Sky, day: LocalDate): File? {
    val root = File(context.getExternalFilesDir(null) ?: return null, "wallpapers")
    val s = scene.name.lowercase()
    val dirs = listOf(File(root, "$s/${sky.name.lowercase()}"), File(root, s))
    for (d in dirs) {
        val imgs = d.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }
        if (!imgs.isNullOrEmpty()) return imgs[(day.dayOfYear + scene.ordinal * 7) % imgs.size]
    }
    return null
}

/**
 * Full-screen background: wallpaper (crossfades when the scene or weather changes),
 * the focused show's artwork on top after a short pause, and the fade to black behind the rows.
 */
@Composable
fun AmbientBackdrop(
    scene: Scene,
    sky: Sky,
    file: File?,
    focusItem: MediaItem?,
    faded: Boolean,
    animate: Boolean,
) {
    val alpha by animateFloatAsState(if (faded) 0.3f else 1f, tween(600), label = "wallAlpha")
    Box(Modifier.fillMaxSize().alpha(alpha)) {
        Crossfade(targetState = Triple(scene, sky, file), animationSpec = tween(1500), label = "wall") { (sc, sk, f) ->
            if (f != null) {
                AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                DrawnWallpaper(sc, sk, animate)
            }
        }
        // Focused show artwork, slow zoom. null = wallpaper shows through.
        Crossfade(targetState = focusItem, animationSpec = tween(900), label = "focusArt") { item ->
            if (item != null) {
                val zoom = remember(item) { Animatable(1f) }
                LaunchedEffect(item, animate) {
                    if (animate) zoom.animateTo(1.08f, tween(20_000, easing = LinearEasing))
                }
                AsyncImage(
                    model = item.backdrop ?: item.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                )
            }
        }
        // Darken the sides so text and widgets stay readable on bright wallpapers
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color(0x99000000), 0.5f to Color.Transparent, 0.72f to Color.Transparent, 1f to Color(0x73000000),
                )
            )
        )
        // Wallpaper stays clear on top, fades to black behind the rows
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x40000000), 0.14f to Color.Transparent, 0.44f to Color(0x1A000000), 0.62f to BlackBg, 1f to BlackBg,
                )
            )
        )
    }
}

/** Dims the whole screen a little at night. Drawn last, never takes focus. */
@Composable
fun NightDim(on: Boolean) {
    val a by animateFloatAsState(if (on) 0.18f else 0f, tween(1500), label = "nightDim")
    if (a > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = a)))
}

// ---------- Built-in wallpaper (used until you add your own photos) ----------

private class Palette(
    val sky: List<Color>, val sun: Color, val sunX: Float, val sunY: Float, val sunR: Float, val glow: Float,
    val m1: Color, val m2: Color, val m3: Color, val stars: Float, val moon: Boolean,
)

private fun paletteFor(scene: Scene, sky: Sky): Palette {
    val wet = sky == Sky.RAIN || sky == Sky.STORM
    if (wet) {
        val night = scene == Scene.NIGHT
        return Palette(
            sky = if (night) listOf(Color(0xFF0B0E14), Color(0xFF161B24), Color(0xFF232933), Color(0xFF2C323C))
            else listOf(Color(0xFF262C37), Color(0xFF363F4C), Color(0xFF4F5764), Color(0xFF626974)),
            sun = Color(0xFFFFD9A0), sunX = 0f, sunY = 0f, sunR = 0f, glow = 0f,
            m1 = Color(0xFF2A303B), m2 = Color(0xFF1E232C), m3 = Color(0xFF12161D), stars = 0f, moon = false,
        )
    }
    return when (scene) {
        Scene.MORNING -> Palette(
            listOf(Color(0xFF6FA8DC), Color(0xFFA9CDEC), Color(0xFFF3DCC0), Color(0xFFFFD3A0)),
            Color(0xFFFFF1CF), 520f, 400f, 60f, 0.8f, Color(0xFF7088A8), Color(0xFF4E6584), Color(0xFF2C3A52), 0f, false,
        )
        Scene.DAY -> Palette(
            listOf(Color(0xFF2F7FCF), Color(0xFF6FB2EA), Color(0xFFBFE0F6), Color(0xFFE3F1FB)),
            Color(0xFFFFFBE8), 1400f, 200f, 56f, 0.6f, Color(0xFF5F7D63), Color(0xFF405C46), Color(0xFF24372A), 0f, false,
        )
        Scene.EVENING -> Palette(
            listOf(Color(0xFF0A1630), Color(0xFF2B3F6B), Color(0xFFB9695A), Color(0xFFF0A868)),
            Color(0xFFFFD9A0), 1120f, 560f, 64f, 0.9f, Color(0xFF3A3550), Color(0xFF231F33), Color(0xFF120F1C), 0.75f, false,
        )
        Scene.NIGHT -> Palette(
            listOf(Color(0xFF01030A), Color(0xFF050C20), Color(0xFF0F1A3C), Color(0xFF18244A)),
            Color(0xFFE6EBF5), 1500f, 190f, 42f, 0.25f, Color(0xFF141A2E), Color(0xFF0C1021), Color(0xFF060810), 0.9f, true,
        )
    }
}

private val Stars = listOf(
    210f to 90f, 480f to 160f, 760f to 70f, 1020f to 140f, 1330f to 60f, 1680f to 260f,
    1860f to 80f, 640f to 230f, 1180f to 250f, 90f to 300f, 900f to 330f, 1250f to 380f,
)
private val Ridge1 = floatArrayOf(0f, 600f, 220f, 480f, 380f, 560f, 560f, 420f, 760f, 550f, 900f, 490f, 1080f, 600f, 1260f, 510f, 1440f, 580f, 1620f, 470f, 1920f, 570f)
private val Ridge2 = floatArrayOf(0f, 660f, 180f, 590f, 340f, 650f, 520f, 570f, 700f, 660f, 880f, 610f, 1060f, 680f, 1240f, 620f, 1420f, 680f, 1600f, 610f, 1780f, 660f, 1920f, 630f)
private val Ridge3 = floatArrayOf(0f, 740f, 240f, 690f, 460f, 730f, 720f, 680f, 980f, 740f, 1240f, 700f, 1500f, 750f, 1760f, 710f, 1920f, 730f)

@Composable
private fun DrawnWallpaper(scene: Scene, sky: Sky, animate: Boolean) {
    val p = remember(scene, sky) { paletteFor(scene, sky) }
    val wet = sky == Sky.RAIN || sky == Sky.STORM
    val rainShift = if (wet && animate) {
        val t = rememberInfiniteTransition(label = "rain")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "rainT")
        v
    } else 0f

    Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / 1920f
        val sy = size.height / 1080f
        drawRect(Brush.verticalGradient(0f to p.sky[0], 0.4f to p.sky[1], 0.6f to p.sky[2], 0.72f to p.sky[3]))
        if (p.stars > 0f) Stars.forEach { (x, y) -> drawCircle(Color.White.copy(alpha = p.stars), 1.6f * sx, Offset(x * sx, y * sy)) }
        if (p.sunR > 0f) {
            val c = Offset(p.sunX * sx, p.sunY * sy)
            drawCircle(Brush.radialGradient(listOf(p.sun.copy(alpha = 0.9f * p.glow), Color.Transparent), c, 250f * sx), 250f * sx, c)
            drawCircle(p.sun, p.sunR * sx, c)
        }
        if (sky == Sky.CLOUDY && !wet) drawClouds(sx, sy, if (scene == Scene.NIGHT) 0.12f else 0.75f)
        drawRidge(Ridge1, p.m1, sx, sy)
        drawRidge(Ridge2, p.m2, sx, sy)
        drawRidge(Ridge3, p.m3, sx, sy)
        if (wet) {
            val dy = rainShift * 60f * sy
            val dx = -rainShift * 20f * sx
            for (i in 0 until 90) {
                val x = ((i * 97) % 1980) * sx + dx
                val y = (((i * 53) % 820) - 60) * sy + dy
                drawLine(Color(0x8CCFD8E6), Offset(x, y), Offset(x - 10f * sx, y + 34f * sy), strokeWidth = 2f * sx, cap = StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.drawRidge(pts: FloatArray, color: Color, sx: Float, sy: Float) {
    val path = Path().apply {
        moveTo(pts[0] * sx, pts[1] * sy)
        var i = 2
        while (i < pts.size) { lineTo(pts[i] * sx, pts[i + 1] * sy); i += 2 }
        lineTo(size.width, size.height); lineTo(0f, size.height); close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawClouds(sx: Float, sy: Float, a: Float) {
    val c = Color.White.copy(alpha = a)
    fun cloud(x: Float, y: Float, w: Float) {
        drawOval(c, Offset((x - w) * sx, (y - 38f) * sy), androidx.compose.ui.geometry.Size(w * 2 * sx, 76f * sy))
        drawOval(c, Offset((x + w * 0.1f - w * 0.6f) * sx, (y - 70f) * sy), androidx.compose.ui.geometry.Size(w * 1.2f * sx, 88f * sy))
    }
    cloud(420f, 210f, 150f)
    cloud(1600f, 330f, 170f)
    cloud(980f, 120f, 120f)
}
