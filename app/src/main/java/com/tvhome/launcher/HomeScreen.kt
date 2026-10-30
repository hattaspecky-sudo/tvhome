@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.tvhome.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Date
import java.util.Locale

private val ScreenBg = Color(0xFF08090B)
private val Panel = Color(0xFF1B1D21)
private val Sheet = Color(0xFF16181C)
private val Dim = Color(0xFF9B9A96)
private val Soft = Color(0xFFCFCDC8)
private val Accent = Color(0xFFE8B04A)
private val Progress = Color(0xFFFF3B30)
private val SideGutter = 56.dp   // TV overscan-safe margin

private enum class Tab { HOME, APPS }

private sealed interface MenuFor {
    data class App(val app: AppItem) : MenuFor
    data class Row(val id: String, val title: String) : MenuFor
}

@Composable
fun HomeScreen(repo: Repository, store: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var data by remember { mutableStateOf(HomeData()) }
    var prefs by remember { mutableStateOf(store.load()) }
    fun update(p: UiPrefs) { prefs = p; store.save(p) }

    var tab by remember { mutableStateOf(Tab.HOME) }
    var menu by remember { mutableStateOf<MenuFor?>(null) }
    var settingsPage by remember { mutableStateOf<String?>(null) }

    // ----- round 2: wallpaper, weather, prayer, widgets -----
    val wallpaperMode = prefs.hero == UiPrefs.HERO_WALLPAPER
    val live = rememberLive(data.cfg)
    val music = rememberNowPlaying()
    val now by produceState(LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); delay(20_000) }
    }
    val today = now.toLocalDate()
    val scene = sceneFor(now.hour)
    val sky = live.weather?.sky ?: Sky.CLEAR
    val wallFile by produceState<File?>(null, scene, sky, today) {
        value = withContext(Dispatchers.IO) { pickWallpaper(context, scene, sky, today) }
    }
    val nextP = nextPrayer(live.prayers, now.toLocalTime())
    val minsToNext = nextP?.let { minutesUntil(now.toLocalTime(), it.time) }
    var prayerOpen by remember { mutableStateOf(false) }

    // What's focused drives the big title; shows/videos also swap the wallpaper for their artwork after 1s
    var focusedItem by remember { mutableStateOf<MediaItem?>(null) }
    var focusedApp by remember { mutableStateOf<AppItem?>(null) }
    var backdropItem by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(focusedItem) {
        backdropItem = null
        val f = focusedItem
        if (f != null && (f.backdrop ?: f.image) != null) { delay(1000); backdropItem = f }
    }

    // Music widget: shows while playing, hides a minute after pause/stop
    val np = music.state
    var shownNp by remember { mutableStateOf<NowPlaying?>(null) }
    var npVisible by remember { mutableStateOf(false) }
    LaunchedEffect(np) {
        if (np != null) shownNp = np
        when {
            np == null -> npVisible = false
            np.playing -> npVisible = true
            else -> { delay(60_000); npVisible = false }
        }
    }

    // Prayer time banner (30s, once per prayer)
    var banner by remember { mutableStateOf<Prayer?>(null) }
    LaunchedEffect(live.prayers) {
        var last: Prayer? = null
        while (true) {
            val t = LocalTime.now()
            val p = live.prayers.firstOrNull { it.isPrayer && !t.isBefore(it.time) && t.isBefore(it.time.plusSeconds(90)) }
            if (p != null && p != last) { last = p; banner = p; delay(30_000); banner = null }
            delay(15_000)
        }
    }

    // Birthday / holiday greeting, each once a day
    val greetStore = remember { context.getSharedPreferences("greet", Context.MODE_PRIVATE) }
    var greeting by remember { mutableStateOf<Greeting?>(null) }
    var greetTick by remember { mutableIntStateOf(0) }
    val todaysGreetings = live.greetings(today, data.cfg)
    LaunchedEffect(todaysGreetings, greetTick) {
        if (greeting != null) return@LaunchedEffect
        val g = todaysGreetings.firstOrNull { !greetStore.getBoolean("$today|${it.key}", false) } ?: return@LaunchedEffect
        delay(1500)
        greetStore.edit().putBoolean("$today|${g.key}", true).apply()
        greeting = g
    }

    // Reload whenever Home comes back to the front, so Play Next stays current
    LifecycleResumeEffect(Unit) {
        val job = scope.launch { data = withContext(Dispatchers.IO) { repo.load() } }
        onPauseOrDispose { job.cancel() }
    }

    // ----- derived state -----
    val appsByPkg = remember(data.apps) { data.apps.associateBy { it.pkg } }
    val favs = (prefs.favs ?: data.defaultFavs).filter { it in appsByPkg }
    val rowsById = remember(data.rows) { data.rows.associateBy { it.id } }
    val allRowIds = listOf("favs") + data.rows.map { it.id }
    val order = prefs.rowOrder.filter { it in allRowIds } + allRowIds.filter { it !in prefs.rowOrder }
    val visibleRows = order.filter { it !in prefs.hidden }
    fun rowName(id: String) = if (id == "favs") "Favourites" else rowsById[id]?.title ?: id

    val heroItems = when (prefs.hero) {
        UiPrefs.HERO_WALLPAPER, UiPrefs.HERO_OFF -> emptyList()
        UiPrefs.HERO_YOUTUBE -> data.heroYouTube.ifEmpty { data.heroPlayNext }
        else -> data.heroPlayNext.ifEmpty { data.heroYouTube }
    }
    var heroIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(heroItems, prefs.lite) {
        heroIndex = 0
        while (heroItems.size > 1 && !prefs.lite) {
            delay(8_000)
            heroIndex = (heroIndex + 1) % heroItems.size
        }
    }
    val hero = heroItems.getOrNull(heroIndex)

    // ----- actions -----
    fun setFavs(list: List<String>) = update(prefs.copy(favs = list))
    fun moveFav(pkg: String, d: Int) {
        val l = favs.toMutableList(); val i = l.indexOf(pkg); val j = i + d
        if (i < 0 || j !in l.indices) return toast(context, "Already at the end")
        l[i] = l[j].also { l[j] = l[i] }; setFavs(l)
    }
    fun moveRow(id: String, d: Int) {
        val v = visibleRows.toMutableList(); val i = v.indexOf(id); val j = i + d
        if (i < 0 || j !in v.indices) return toast(context, "Already at the end")
        v[i] = v[j].also { v[j] = v[i] }
        update(prefs.copy(rowOrder = v + order.filter { it !in v }))
    }

    // ----- focus -----
    val entry = remember { FocusRequester() }
    LaunchedEffect(data.loaded, hero == null) {
        if (data.loaded) { delay(100); runCatching { entry.requestFocus() } }
    }

    // Home screen: Back never exits. Apps tab -> Home tab, Home -> scroll to top.
    BackHandler(enabled = BuildConfig.IS_HOME || tab == Tab.APPS) {
        if (tab == Tab.APPS) {
            tab = Tab.HOME
            return@BackHandler
        }
        scope.launch { listState.animateScrollToItem(0); runCatching { entry.requestFocus() } }
    }
    // Declared later so it wins: Back closes the prayer list first
    BackHandler(enabled = prayerOpen) { prayerOpen = false }

    val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    Box(Modifier.fillMaxSize().background(ScreenBg)) {
        if (wallpaperMode) {
            AmbientBackdrop(
                scene = scene,
                sky = sky,
                file = wallFile,
                focusItem = if (tab == Tab.HOME && !prefs.lite) backdropItem else null,
                faded = scrolled || tab == Tab.APPS,
                animate = !prefs.lite,
            )
        } else if (tab == Tab.HOME && hero != null && !prefs.lite) {
            HeroBackdrop(hero, heroHeight + 24.dp, Modifier.alpha(if (scrolled) 0.28f else 1f))
        }
        Column(Modifier.fillMaxSize()) {
            TopBar(
                tab = tab,
                onTab = { tab = it },
                showClock = prefs.clock,
                onSettings = { settingsPage = "main" },
                pill = {
                    WeatherPrayerPill(live.weather, scene == Scene.NIGHT, nextP, minsToNext, prayerOpen) {
                        prayerOpen = !prayerOpen
                    }
                },
            )
            when (tab) {
                Tab.HOME -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    if (hero != null) item(key = "hero") {
                        HeroInfo(hero, heroHeight - 70.dp, prefs.lite, entry) { open(context, hero.intent) }
                    }
                    if (wallpaperMode) item(key = "wall") {
                        WallpaperInfo(
                            height = heroHeight - 70.dp,
                            sideGutter = SideGutter,
                            greeting = greetingFor(now.hour, data.cfg?.name),
                            title = focusedItem?.title ?: focusedApp?.label,
                            meta = focusedItem?.let { listOfNotNull(it.source, it.subtitle).distinct().joinToString("  ·  ") }
                                ?: focusedApp?.let { "Press OK to open" },
                        )
                    }
                    itemsIndexed(visibleRows, key = { _, id -> id }) { index, id ->
                        val req = if (hero == null && index == 0) entry else null
                        if (id == "favs") {
                            AppsRow(
                                title = "Favourites",
                                apps = favs.mapNotNull { appsByPkg[it] },
                                firstReq = req,
                                onOpen = { open(context, it.intent) },
                                onLong = { menu = MenuFor.App(it) },
                                onFocus = { focusedApp = it; focusedItem = null },
                            )
                        } else rowsById[id]?.let { row ->
                            MediaRowView(
                                row = row,
                                firstReq = req,
                                onOpen = { open(context, it.intent) },
                                onLong = { menu = MenuFor.Row(row.id, row.title) },
                                onFocus = { focusedItem = it; focusedApp = null },
                            )
                        }
                    }
                }
                Tab.APPS -> AppsGrid(
                    favApps = favs.mapNotNull { appsByPkg[it] },
                    others = data.apps.filter { it.pkg !in favs },
                    onOpen = { open(context, it.intent) },
                    onLong = { menu = MenuFor.App(it) },
                )
            }
        }

        // ----- overlays (drawn on top, in this order) -----
        WidgetColumn(
            visible = tab == Tab.HOME && !scrolled,
            nextEvent = live.nextEvent(now),
            game = live.game,
            music = shownNp,
            musicVisible = npVisible,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = SideGutter),
        )
        PrayerDropdown(
            open = prayerOpen,
            prayers = live.prayers,
            next = nextP,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 62.dp, end = SideGutter + 130.dp),
        )
        PrayerBanner(banner, Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
        NightDim(on = wallpaperMode && scene == Scene.NIGHT)
        GreetingOverlay(greeting) { greeting = null; greetTick++ }
    }

    // ----- long-press menus -----
    when (val m = menu) {
        is MenuFor.App -> {
            val pkg = m.app.pkg
            val isFav = pkg in favs
            OptionsDialog(
                title = m.app.label,
                options = if (isFav) listOf(
                    "Move left" to { moveFav(pkg, -1) },
                    "Move right" to { moveFav(pkg, 1) },
                    "Remove from favourites" to { setFavs(favs - pkg); toast(context, "${m.app.label} removed from favourites") },
                    "App info" to { appInfo(context, pkg) },
                ) else listOf(
                    "Add to favourites" to { setFavs(favs + pkg); toast(context, "${m.app.label} added to favourites") },
                    "App info" to { appInfo(context, pkg) },
                ),
                onDismiss = { menu = null },
            )
        }
        is MenuFor.Row -> OptionsDialog(
            title = m.title,
            options = listOf(
                "Move row up" to { moveRow(m.id, -1) },
                "Move row down" to { moveRow(m.id, 1) },
                "Hide this row" to {
                    update(prefs.copy(hidden = prefs.hidden + m.id))
                    toast(context, "Row hidden. Turn it back on in Settings → Rows")
                },
            ),
            onDismiss = { menu = null },
        )
        null -> {}
    }

    // ----- settings panel -----
    settingsPage?.let { page ->
        var asked by remember { mutableStateOf(false) }
        SettingsSheet(
            title = if (page == "rows") "Rows" else "Home settings",
            pageKey = page,
            onBack = { settingsPage = if (page == "main") null else "main" },
        ) { first ->
            if (page == "main") {
                SheetItem("Favourites", "${favs.size} apps ›", modifier = Modifier.focusRequester(first)) {
                    settingsPage = null; tab = Tab.APPS
                    toast(context, "Hold OK on any app to add, move or remove it")
                }
                SheetItem("Rows", "${visibleRows.size} of ${allRowIds.size} on ›") { settingsPage = "rows" }
                SheetItem(
                    "Hero",
                    when (prefs.hero) {
                        UiPrefs.HERO_WALLPAPER -> "Wallpaper"
                        UiPrefs.HERO_OFF -> "Off"
                        UiPrefs.HERO_YOUTUBE -> "YouTube rotation"
                        else -> "Play Next rotation"
                    } + " ⇄",
                ) {
                    val next = when (prefs.hero) {
                        UiPrefs.HERO_WALLPAPER -> UiPrefs.HERO_PLAY_NEXT
                        UiPrefs.HERO_PLAY_NEXT -> UiPrefs.HERO_YOUTUBE
                        UiPrefs.HERO_YOUTUBE -> UiPrefs.HERO_OFF
                        else -> UiPrefs.HERO_WALLPAPER
                    }
                    update(prefs.copy(hero = next))
                }
                SheetItem("Wallpapers", if (wallFile != null) "Your photos" else "Built-in") {
                    toast(context, "Add photos to Android/data/${context.packageName}/files/wallpapers/<morning|day|evening|night>/")
                }
                SheetItem("Music widget", if (music.access) "On" else "Needs access ›") {
                    if (music.access) toast(context, "Shows while music plays")
                    else toast(context, "Run on PC: adb shell cmd notification allow_listener ${context.packageName}/com.tvhome.launcher.MediaListener")
                }
                SheetItem("Clock", toggle = prefs.clock) { update(prefs.copy(clock = !prefs.clock)) }
                SheetItem("Lite mode", toggle = prefs.lite) {
                    val on = !prefs.lite
                    update(prefs.copy(lite = on))
                    if (on) toast(context, "Lite mode: no hero images or animations")
                }
                SheetItem(
                    "TV data access",
                    when (data.access) {
                        Access.OK -> "Allowed"
                        Access.NOT_ALLOWED -> "Not allowed ›"
                        Access.BLOCKED -> "Blocked by TV"
                    },
                ) {
                    when (data.access) {
                        Access.OK -> toast(context, "Working. Play Next items: ${data.playNextCount}")
                        Access.BLOCKED -> toast(context, "This TV blocks Play Next for other launchers")
                        Access.NOT_ALLOWED -> {
                            val act = context as? Activity
                            if (!asked && act != null) { asked = true; act.requestPermissions(arrayOf(EPG_PERMISSION), 1) }
                            else { appInfo(context, context.packageName); toast(context, "Permissions → allow TV data") }
                        }
                    }
                }
                SheetItem("Switch to stock home", "") { switchHome(context) }
            } else {
                order.forEachIndexed { i, id ->
                    SheetItem(
                        rowName(id),
                        toggle = id !in prefs.hidden,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) {
                        update(prefs.copy(hidden = if (id in prefs.hidden) prefs.hidden - id else prefs.hidden + id))
                    }
                }
                Text(
                    "To reorder, hold OK on a row on the Home screen.",
                    color = Dim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ---------- Top bar ----------

@Composable
private fun TopBar(tab: Tab, onTab: (Tab) -> Unit, showClock: Boolean, onSettings: () -> Unit, pill: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = SideGutter, end = SideGutter, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabChip("Home", tab == Tab.HOME) { onTab(Tab.HOME) }
        TabChip("Apps", tab == Tab.APPS) { onTab(Tab.APPS) }
        Spacer(Modifier.weight(1f))
        pill()
        if (showClock) Clock()
        Surface(
            onClick = onSettings,
            modifier = Modifier.padding(start = 12.dp).size(38.dp),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent, contentColor = Soft,
                focusedContainerColor = Color.White, focusedContentColor = ScreenBg,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Text("⚙", fontSize = 19.sp, modifier = Modifier.align(Alignment.Center))
        }
        Box(
            Modifier.padding(start = 10.dp).size(34.dp).clip(CircleShape).background(Accent),
            contentAlignment = Alignment.Center,
        ) { Text("H", color = Color(0xFF2A1D05), fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect,
        // Like Google TV: just moving onto a tab switches to it
        modifier = Modifier.onFocusChanged { if (it.isFocused) onSelect() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Color.White else Dim,
            focusedContainerColor = Color.White,
            focusedContentColor = ScreenBg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp))
    }
}

@Composable
private fun Clock() {
    val time by produceState(now()) {
        while (true) { value = now(); delay(15_000) }
    }
    Text(time, fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium)
}

private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

// ---------- Hero ----------

@Composable
private fun HeroBackdrop(hero: MediaItem, height: Dp, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(height)) {
        Crossfade(targetState = hero, animationSpec = tween(900), label = "hero") { item ->
            AsyncImage(
                model = item.backdrop ?: item.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to ScreenBg.copy(alpha = 0.92f),
                    0.42f to ScreenBg.copy(alpha = 0.55f),
                    0.7f to Color.Transparent,
                )
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to ScreenBg)))
    }
}

@Composable
private fun HeroInfo(hero: MediaItem, height: Dp, lite: Boolean, watchFocus: FocusRequester, onWatch: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(height).padding(horizontal = SideGutter),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Crossfade(targetState = hero, animationSpec = if (lite) snap() else tween(500), label = "heroText") { item ->
            Column(Modifier.widthIn(max = 600.dp)) {
                item.source?.let {
                    Text(it.uppercase(), fontSize = 13.sp, letterSpacing = 1.2.sp, color = Dim, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                Text(
                    item.title,
                    fontSize = 44.sp, lineHeight = 48.sp, fontFamily = FontFamily.Serif, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                val meta = (item.meta + listOfNotNull(item.subtitle)).joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, fontSize = 15.sp, color = Soft, maxLines = 1)
            }
        }
        // Button sits outside the crossfade so it keeps focus while banners rotate
        Button(onClick = onWatch, modifier = Modifier.padding(top = 16.dp).focusRequester(watchFocus)) { Text("▶  Watch") }
    }
}

// ---------- Rows ----------

@Composable
private fun RowLabel(text: String) {
    Text(
        text, fontSize = 17.sp, color = Color(0xFFE3E1DC), fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = SideGutter, bottom = 10.dp),
    )
}

@Composable
private fun MediaRowView(row: MediaRow, firstReq: FocusRequester?, onOpen: (MediaItem) -> Unit, onLong: () -> Unit, onFocus: (MediaItem) -> Unit = {}) {
    Column {
        RowLabel(row.title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = SideGutter, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.items) { i, item ->
                MediaCard(item, if (i == 0) firstReq else null, { onOpen(item) }, onLong, { onFocus(item) })
            }
        }
    }
}

@Composable
private fun AppsRow(title: String, apps: List<AppItem>, firstReq: FocusRequester?, onOpen: (AppItem) -> Unit, onLong: (AppItem) -> Unit, onFocus: (AppItem) -> Unit = {}) {
    Column {
        RowLabel(title)
        if (apps.isEmpty()) {
            Text(
                "No favourites yet. Go to the Apps tab and hold OK on an app.",
                color = Dim, fontSize = 14.sp, modifier = Modifier.padding(start = SideGutter),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = SideGutter, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(apps, key = { _, a -> a.pkg }) { i, app ->
                AppCard(
                    app,
                    Modifier.size(188.dp, 106.dp).then(if (i == 0 && firstReq != null) Modifier.focusRequester(firstReq) else Modifier),
                    star = false,
                    onClick = { onOpen(app) },
                    onLong = { onLong(app) },
                    onFocus = { onFocus(app) },
                )
            }
        }
    }
}

@Composable
private fun AppsGrid(favApps: List<AppItem>, others: List<AppItem>, onOpen: (AppItem) -> Unit, onLong: (AppItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        contentPadding = PaddingValues(start = SideGutter, end = SideGutter, top = 12.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { GridHeader("Favourites", "shown on Home, in this order") }
        items(favApps.size, key = { "f" + favApps[it].pkg }) { i ->
            val app = favApps[i]
            AppCard(app, Modifier.fillMaxWidth().aspectRatio(16f / 9f), star = true, onClick = { onOpen(app) }, onLong = { onLong(app) })
        }
        item(span = { GridItemSpan(maxLineSpan) }) { GridHeader("All apps", "${others.size} more, A–Z") }
        items(others.size, key = { "a" + others[it].pkg }) { i ->
            val app = others[i]
            AppCard(app, Modifier.fillMaxWidth().aspectRatio(16f / 9f), star = false, onClick = { onOpen(app) }, onLong = { onLong(app) })
        }
    }
}

@Composable
private fun GridHeader(title: String, note: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 17.sp, color = Color(0xFFE3E1DC), fontWeight = FontWeight.Medium)
        Text("  ·  $note", fontSize = 13.sp, color = Dim)
    }
}

private val focusBorder = Border(BorderStroke(3.dp, Color.White))

@Composable
private fun MediaCard(item: MediaItem, req: FocusRequester?, onClick: () -> Unit, onLong: () -> Unit, onFocus: () -> Unit = {}) {
    Card(
        onClick = onClick,
        onLongClick = onLong,
        modifier = Modifier.size(236.dp, 133.dp).then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = focusBorder),
        colors = CardDefaults.colors(containerColor = Panel),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.image, contentDescription = item.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                Text(item.title, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                item.subtitle?.let { Text(it, fontSize = 12.sp, color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            item.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x40FFFFFF))) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Progress))
                }
            }
        }
    }
}

@Composable
private fun AppCard(app: AppItem, modifier: Modifier, star: Boolean, onClick: () -> Unit, onLong: () -> Unit, onFocus: () -> Unit = {}) {
    Card(
        onClick = onClick,
        onLongClick = onLong,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = focusBorder),
        colors = CardDefaults.colors(containerColor = Panel),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (app.banner != null) {
                Image(app.banner, app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    app.icon?.let { Image(it, null, Modifier.size(40.dp)) }
                    Text(app.label, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (star) Text("★", color = Color(0xFFFFD77A), fontSize = 14.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}

// ---------- Dialogs ----------

@Composable
private fun OptionsDialog(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        Column(
            Modifier.width(380.dp).background(Panel, RoundedCornerShape(14.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title.uppercase(), color = Dim, fontSize = 13.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 8.dp),
            )
            options.forEachIndexed { i, (label, action) ->
                SheetItem(label, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                    onDismiss(); action()
                }
            }
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    }
}

@Composable
private fun SettingsSheet(title: String, pageKey: String, onBack: () -> Unit, content: @Composable ColumnScope.(FocusRequester) -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            key(pageKey) {
                val first = remember { FocusRequester() }
                Column(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(470.dp).background(Sheet)
                        .padding(horizontal = 30.dp, vertical = 38.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(title, fontSize = 32.sp, fontFamily = FontFamily.Serif, color = Color.White, modifier = Modifier.padding(start = 18.dp, bottom = 14.dp))
                    content(first)
                }
                LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
            }
        }
    }
}

@Composable
private fun SheetItem(
    label: String,
    value: String? = null,
    toggle: Boolean? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = ScreenBg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            value?.let { Text(it, fontSize = 16.sp, color = LocalContentColor.current.copy(alpha = 0.6f)) }
            toggle?.let { on ->
                Box(
                    Modifier.size(46.dp, 26.dp).background(if (on) Accent else Color(0xFF45474C), RoundedCornerShape(13.dp)),
                ) {
                    Box(
                        Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(3.dp)
                            .size(20.dp).background(Color.White, CircleShape)
                    )
                }
            }
        }
    }
}

// ---------- System actions ----------

private fun open(context: Context, intent: Intent?) {
    if (intent == null) return
    try {
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(context, "Couldn't open that")
    }
}

private fun appInfo(context: Context, pkg: String) =
    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

/** Lets you go back to the Panasonic home without uninstalling. */
private fun switchHome(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        appInfo(context, context.packageName)
        toast(context, "Choose \"Clear defaults\", then press Home")
    }
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
