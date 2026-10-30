@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.tvhome.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Glass = Color(0x8C0A0C18)
private val Label = Color(0xB8FFFFFF)
private val Sub = Color(0xC7FFFFFF)
val WidgetAccent = Color(0xFFF2A65A)
private val OnAccent = Color(0xFF140D05)
private val LiveRed = Color(0xFFE03A3E)

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

fun greetingFor(hour: Int, name: String?): String {
    val g = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..21 -> "Good evening"; else -> "Good night" }
    return if (name.isNullOrBlank()) g else "$g, $name"
}

// ---------- Hero text over the wallpaper ----------

/** What the remote is on. Read only by the hero title and backdrop, so other parts don't redraw on every move. */
class FocusState {
    var item by mutableStateOf<MediaItem?>(null)
    var app by mutableStateOf<AppItem?>(null)
}

@Composable
fun WallpaperInfo(height: Dp, sideGutter: Dp, greeting: String, focus: FocusState) {
    val item = focus.item
    val app = focus.app
    val title = item?.title ?: app?.label
    val meta = item?.let { listOfNotNull(it.source, it.subtitle).distinct().joinToString("  ·  ") }
        ?: app?.let { "Press OK to open" }
    Column(
        Modifier.fillMaxWidth().height(height).padding(horizontal = sideGutter),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(greeting, fontSize = 16.sp, color = Sub)
        Crossfade(targetState = title to meta, animationSpec = tween(350), label = "wallTitle") { (t, m) ->
            Column(Modifier.widthIn(max = 560.dp)) {
                Text(
                    t ?: " ", fontSize = 40.sp, lineHeight = 44.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp),
                )
                Text(m ?: " ", fontSize = 15.sp, color = Sub, maxLines = 1)
            }
        }
    }
}

// ---------- Weather + prayer pill (top bar) ----------

@Composable
fun WeatherPrayerPill(weather: Weather?, night: Boolean, next: Prayer?, minsToNext: Long?, open: Boolean, onClick: () -> Unit) {
    if (weather == null && next == null) return
    val soon = minsToNext != null && minsToNext in 1..15
    val bg by animateColorAsState(if (soon) WidgetAccent else Glass, tween(600), label = "pillBg")
    val fg = if (soon) OnAccent else Color.White
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(end = 12.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = bg, contentColor = fg,
            focusedContainerColor = Color.White, focusedContentColor = Color(0xFF0B0B0F),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            weather?.let {
                WeatherIcon(it.sky, night, Modifier.size(18.dp))
                Text("${it.tempC}°", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
            }
            if (weather != null && next != null) {
                Box(Modifier.padding(horizontal = 10.dp).size(1.dp, 16.dp).background(Color(0x4DFFFFFF)))
            }
            next?.let {
                val txt = if (soon) "${it.name} in ${minsToNext}m" else "${it.name} ${it.time.format(hm)}"
                Text(txt, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Chevron(open, Modifier.padding(start = 8.dp).size(10.dp))
        }
    }
}

@Composable
fun PrayerDropdown(open: Boolean, prayers: List<Prayer>, next: Prayer?, modifier: Modifier) {
    AnimatedVisibility(
        visible = open && prayers.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { -it / 10 } + scaleIn(tween(240), initialScale = 0.96f),
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 10 },
    ) {
        Column(
            Modifier.width(230.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xEB0E101C)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "WAKTU SOLAT", fontSize = 11.sp, letterSpacing = 1.sp, color = Label,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 6.dp),
            )
            prayers.forEach { p ->
                val isNext = p == next
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (isNext) WidgetAccent else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    val c = if (isNext) OnAccent else if (p.isPrayer) Color.White else Sub
                    Text(p.name, fontSize = 16.sp, color = c, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(p.time.format(hm), fontSize = 16.sp, color = c, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Slides in for 30 seconds when it's prayer time. */
@Composable
fun PrayerBanner(prayer: Prayer?, modifier: Modifier) {
    var last by remember { mutableStateOf<Prayer?>(null) }
    if (prayer != null) last = prayer
    AnimatedVisibility(
        visible = prayer != null,
        modifier = modifier,
        enter = fadeIn(tween(300)) + slideInVertically(tween(400)) { -it },
        exit = fadeOut(tween(300)) + slideOutVertically(tween(400)) { -it },
    ) {
        last?.let { p ->
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(WidgetAccent).padding(horizontal = 22.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Waktu ${p.name}", fontSize = 18.sp, color = OnAccent, fontWeight = FontWeight.SemiBold)
                Text("  ·  ${p.time.format(hm)}", fontSize = 18.sp, color = OnAccent)
            }
        }
    }
}

// ---------- Widgets column ----------

@Composable
fun WidgetColumn(
    visible: Boolean,
    nextEvent: Pair<CalEvent, LocalDate>?,
    game: Game?,
    music: NowPlaying?,
    musicVisible: Boolean,
    modifier: Modifier,
) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(tween(400)), exit = fadeOut(tween(250))) {
        Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            nextEvent?.let { (e, d) -> CalendarCard(e, d) }
            game?.let { GameCard(it) }
            AnimatedVisibility(
                visible = musicVisible && music != null,
                enter = expandVertically(tween(450)) + fadeIn(tween(350)),
                exit = shrinkVertically(tween(450)) + fadeOut(tween(250)),
            ) {
                music?.let { MusicCard(it) }
            }
        }
    }
}

@Composable
private fun WidgetCard(bg: Color = Glass, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun CardText(label: String, title: String, sub: String?, modifier: Modifier = Modifier, badge: (@Composable () -> Unit)? = null) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), fontSize = 10.sp, letterSpacing = 0.8.sp, color = Label, maxLines = 1, modifier = Modifier.weight(1f))
            badge?.invoke()
        }
        Text(title, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, fontSize = 12.sp, color = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun IconBox(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.padding(end = 10.dp).size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x1AFFFFFF)),
        contentAlignment = Alignment.Center, content = content,
    )
}

@Composable
private fun CalendarCard(e: CalEvent, d: LocalDate) {
    val today = LocalDate.now()
    val day = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.format(dayFmt) }
    val sub = e.time?.let { "$day · ${it.format(hm)}" } ?: day
    WidgetCard {
        IconBox { CalendarIcon(Modifier.size(16.dp)) }
        CardText("Next up", e.title, sub, Modifier.weight(1f))
    }
}

@Composable
private fun GameCard(g: Game) {
    val live = g.state == "in"
    val title = if (live && g.us != null && g.them != null) "${g.team} ${g.us} – ${g.them} ${g.opp}"
    else "${g.team} ${if (g.home) "vs" else "@"} ${g.opp}"
    val sub = if (live) g.detail else {
        val t = g.start.atZone(ZoneId.systemDefault())
        val d = t.toLocalDate()
        val today = LocalDate.now()
        val day = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.format(dayFmt) }
        "$day · ${t.toLocalTime().format(hm)}"
    }
    WidgetCard {
        IconBox { BallIcon(Modifier.size(16.dp)) }
        CardText("Trail Blazers", title, sub, Modifier.weight(1f)) {
            Text(
                if (live) "LIVE" else "NEXT", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = if (live) Color.White else OnAccent,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (live) LiveRed else WidgetAccent)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun MusicCard(m: NowPlaying) {
    val bg by animateColorAsState(m.tint ?: Glass, tween(800), label = "musicTint")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crossfade(targetState = m.art ?: m.artUri, animationSpec = tween(600), label = "art") { art ->
                Box(Modifier.padding(end = 10.dp).size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x26FFFFFF))) {
                    when (art) {
                        is android.graphics.Bitmap -> Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        is String -> AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else -> NoteIcon(Modifier.align(Alignment.Center).size(20.dp))
                    }
                }
            }
            CardText("Now playing · ${m.app}", m.title, m.artist.ifBlank { null }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val tc = m.controller.transportControls
            MediaButton(onClick = { tc.skipToPrevious() }) { PrevIcon(Modifier.size(12.dp)) }
            MediaButton(primary = true, onClick = { if (m.playing) tc.pause() else tc.play() }) {
                if (m.playing) PauseIcon(Modifier.size(12.dp)) else PlayIcon(Modifier.size(12.dp))
            }
            MediaButton(onClick = { tc.skipToNext() }) { NextIcon(Modifier.size(12.dp)) }
        }
    }
}

@Composable
private fun MediaButton(primary: Boolean = false, onClick: () -> Unit, icon: @Composable BoxScope.() -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color.White else Color(0x26FFFFFF),
            contentColor = if (primary) Color(0xFF0B0B0F) else Color.White,
            focusedContainerColor = WidgetAccent, focusedContentColor = OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = icon)
    }
}

// ---------- Birthday / holiday greeting ----------

@Composable
fun GreetingOverlay(greeting: Greeting?, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<Greeting?>(null) }
    if (greeting != null) last = greeting
    AnimatedVisibility(
        visible = greeting != null,
        enter = fadeIn(tween(700)) + scaleIn(tween(700), initialScale = 0.97f),
        exit = fadeOut(tween(500)),
    ) {
        val ok = remember { FocusRequester() }
        LaunchedEffect(greeting) {
            delay(100); runCatching { ok.requestFocus() }
            delay(30_000); onDismiss()
        }
        Box(Modifier.fillMaxSize().background(Color(0xD9000000)), contentAlignment = Alignment.Center) {
            last?.let { g ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "TODAY · " + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())).uppercase(),
                        fontSize = 13.sp, letterSpacing = 1.2.sp, color = OnAccent, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(WidgetAccent).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Text(g.headline, fontSize = 52.sp, lineHeight = 58.sp, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(g.sub, fontSize = 18.sp, color = Sub, textAlign = TextAlign.Center)
                    Button(onClick = onDismiss, modifier = Modifier.padding(top = 10.dp).focusRequester(ok)) { Text("OK") }
                }
            }
        }
    }
}

// ---------- Small drawn icons (no emoji, crisp at any size) ----------

@Composable
fun WeatherIcon(sky: Sky, night: Boolean, modifier: Modifier) = Canvas(modifier) {
    val w = size.width
    val c = Offset(w / 2, w / 2)
    val sun = Color(0xFFFFD27A)
    val moon = Color(0xFFDFE6F5)
    fun cloud(color: Color, dy: Float) {
        drawCircle(color, w * 0.2f, Offset(w * 0.35f, w * 0.55f + dy))
        drawCircle(color, w * 0.26f, Offset(w * 0.55f, w * 0.45f + dy))
        drawCircle(color, w * 0.18f, Offset(w * 0.75f, w * 0.58f + dy))
        drawRect(color, Offset(w * 0.35f, w * 0.55f + dy), androidx.compose.ui.geometry.Size(w * 0.4f, w * 0.21f))
    }
    when (sky) {
        Sky.CLEAR -> if (night) {
            val a = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, w * 0.38f)) }
            val b = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.68f, w * 0.34f), w * 0.32f)) }
            drawPath(Path().apply { op(a, b, PathOperation.Difference) }, moon)
        } else {
            drawCircle(sun, w * 0.22f, c)
            repeat(8) {
                val ang = it * PI / 4
                drawLine(
                    sun, c + Offset((cos(ang) * w * 0.32f).toFloat(), (sin(ang) * w * 0.32f).toFloat()),
                    c + Offset((cos(ang) * w * 0.46f).toFloat(), (sin(ang) * w * 0.46f).toFloat()),
                    strokeWidth = w * 0.08f, cap = StrokeCap.Round,
                )
            }
        }
        Sky.CLOUDY -> cloud(Color.White, 0f)
        Sky.RAIN, Sky.STORM -> {
            cloud(Color.White, -w * 0.12f)
            val rain = if (sky == Sky.STORM) Color(0xFFFFD27A) else Color(0xFF9FC3FF)
            for (i in 0..2) {
                val x = w * (0.35f + i * 0.2f)
                drawLine(rain, Offset(x, w * 0.75f), Offset(x - w * 0.08f, w * 0.98f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Chevron(open: Boolean, modifier: Modifier) {
    val rot by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, tween(220), label = "chev")
    val color = androidx.tv.material3.LocalContentColor.current
    Canvas(modifier.graphicsLayer { rotationZ = rot }) {
        val w = size.width
        drawLine(color, Offset(0f, w * 0.3f), Offset(w / 2, w * 0.8f), strokeWidth = w * 0.18f, cap = StrokeCap.Round)
        drawLine(color, Offset(w / 2, w * 0.8f), Offset(w, w * 0.3f), strokeWidth = w * 0.18f, cap = StrokeCap.Round)
    }
}

@Composable
private fun CalendarIcon(modifier: Modifier) = Canvas(modifier) {
    val w = size.width
    val s = w * 0.1f
    drawRoundRect(Color.White, Offset(w * 0.08f, w * 0.18f), androidx.compose.ui.geometry.Size(w * 0.84f, w * 0.76f),
        androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = androidx.compose.ui.graphics.drawscope.Stroke(s))
    drawLine(Color.White, Offset(w * 0.08f, w * 0.42f), Offset(w * 0.92f, w * 0.42f), strokeWidth = s)
    drawLine(Color.White, Offset(w * 0.32f, w * 0.06f), Offset(w * 0.32f, w * 0.26f), strokeWidth = s, cap = StrokeCap.Round)
    drawLine(Color.White, Offset(w * 0.68f, w * 0.06f), Offset(w * 0.68f, w * 0.26f), strokeWidth = s, cap = StrokeCap.Round)
}

@Composable
private fun BallIcon(modifier: Modifier) = Canvas(modifier) {
    val w = size.width
    val s = w * 0.09f
    val st = androidx.compose.ui.graphics.drawscope.Stroke(s)
    drawCircle(Color.White, w * 0.44f, Offset(w / 2, w / 2), style = st)
    drawLine(Color.White, Offset(w * 0.06f, w / 2), Offset(w * 0.94f, w / 2), strokeWidth = s)
    drawLine(Color.White, Offset(w / 2, w * 0.06f), Offset(w / 2, w * 0.94f), strokeWidth = s)
}

@Composable
private fun NoteIcon(modifier: Modifier) = Canvas(modifier) {
    val w = size.width
    drawCircle(Color.White, w * 0.16f, Offset(w * 0.3f, w * 0.78f))
    drawCircle(Color.White, w * 0.16f, Offset(w * 0.78f, w * 0.68f))
    drawLine(Color.White, Offset(w * 0.44f, w * 0.78f), Offset(w * 0.44f, w * 0.14f), strokeWidth = w * 0.09f)
    drawLine(Color.White, Offset(w * 0.92f, w * 0.68f), Offset(w * 0.92f, w * 0.06f), strokeWidth = w * 0.09f)
    drawLine(Color.White, Offset(w * 0.44f, w * 0.14f), Offset(w * 0.92f, w * 0.06f), strokeWidth = w * 0.12f)
}

@Composable
private fun PlayIcon(modifier: Modifier) {
    val color = androidx.tv.material3.LocalContentColor.current
    Canvas(modifier) {
        val w = size.width
        drawPath(Path().apply { moveTo(w * 0.15f, 0f); lineTo(w, w / 2); lineTo(w * 0.15f, w); close() }, color)
    }
}

@Composable
private fun PauseIcon(modifier: Modifier) {
    val color = androidx.tv.material3.LocalContentColor.current
    Canvas(modifier) {
        val w = size.width
        drawRect(color, Offset(w * 0.1f, 0f), androidx.compose.ui.geometry.Size(w * 0.3f, w))
        drawRect(color, Offset(w * 0.6f, 0f), androidx.compose.ui.geometry.Size(w * 0.3f, w))
    }
}

@Composable
private fun NextIcon(modifier: Modifier) {
    val color = androidx.tv.material3.LocalContentColor.current
    Canvas(modifier) {
        val w = size.width
        drawPath(Path().apply { moveTo(0f, 0f); lineTo(w * 0.75f, w / 2); lineTo(0f, w); close() }, color)
        drawRect(color, Offset(w * 0.78f, 0f), androidx.compose.ui.geometry.Size(w * 0.18f, w))
    }
}

@Composable
private fun PrevIcon(modifier: Modifier) {
    val color = androidx.tv.material3.LocalContentColor.current
    Canvas(modifier) {
        val w = size.width
        drawRect(color, Offset(w * 0.04f, 0f), androidx.compose.ui.geometry.Size(w * 0.18f, w))
        drawPath(Path().apply { moveTo(w, 0f); lineTo(w * 0.25f, w / 2); lineTo(w, w); close() }, color)
    }
}
