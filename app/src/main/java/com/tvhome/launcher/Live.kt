package com.tvhome.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ---------- Models ----------

data class Weather(val tempC: Int, val sky: Sky)

/** isPrayer = false for Syuruk (shown in the list, never "next"). */
data class Prayer(val name: String, val time: LocalTime, val isPrayer: Boolean)

/** One NBA game from our team's point of view. state: "pre", "in" or "post". */
data class Game(
    val team: String, val opp: String, val home: Boolean, val start: Instant,
    val state: String, val detail: String, val us: String?, val them: String?,
)

data class CalEvent(val title: String, val date: LocalDate, val time: LocalTime?, val yearly: Boolean)

/** Full-screen greeting. key makes sure each one shows once a day. */
data class Greeting(val key: String, val headline: String, val sub: String)

// ---------- Live state for the home screen ----------

class Live {
    var weather by mutableStateOf<Weather?>(null)
    var prayers by mutableStateOf<List<Prayer>>(emptyList())
    var game by mutableStateOf<Game?>(null)
    var events by mutableStateOf<List<CalEvent>>(emptyList())
    var holidays by mutableStateOf<List<CalEvent>>(emptyList())

    /** Next calendar event in the coming 2 weeks. */
    fun nextEvent(now: LocalDateTime): Pair<CalEvent, LocalDate>? =
        events.mapNotNull { e ->
            val d = Ics.occurrence(e, now.toLocalDate())
            when {
                d.isAfter(now.toLocalDate().plusDays(14)) -> null
                d == now.toLocalDate() && e.time != null && e.time.isBefore(now.toLocalTime()) -> null
                else -> e to d
            }
        }.minWithOrNull(compareBy({ it.second }, { it.first.time ?: LocalTime.MIN }))

    /** Birthdays and holidays for today. */
    fun greetings(today: LocalDate, cfg: Config?): List<Greeting> = buildList {
        cfg?.birthdays?.forEach { b ->
            if (b.date == "%02d-%02d".format(today.monthValue, today.dayOfMonth)) {
                add(Greeting("bday:${b.name}", "Happy birthday, ${b.name}!", "Wishing you a great one, from everyone at home"))
            }
        }
        events.filter { Ics.occurrence(it, today) == today && isBirthday(it.title) }.forEach { e ->
            val name = birthdayName(e.title)
            add(Greeting("cal:${e.title}", if (name != null) "Happy birthday, $name!" else e.title, "Wishing you a great one, from everyone at home"))
        }
        holidays.filter { Ics.occurrence(it, today) == today }.forEach { e ->
            add(Greeting("hol:${e.title}", e.title, "Have a wonderful day"))
        }
    }.distinctBy { it.headline }

    private fun isBirthday(t: String) = t.contains("birthday", true) || t.contains("hari jadi", true)

    private fun birthdayName(t: String): String? =
        Regex("^(.+?)['’]s birthday", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.trim()
            ?: Regex("^(?:birthday|hari jadi)\\s*[:\\-–]?\\s*(.+)$", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.trim()
            ?: Regex("^(.+?)\\s*[:\\-–]\\s*(?:birthday|hari jadi)$", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.trim()
}

/** Keeps weather, prayer times, the Blazers game and calendar fresh while Home is open. */
@Composable
fun rememberLive(cfg: Config?): Live {
    val live = remember { Live() }
    LaunchedEffect(cfg) {
        if (cfg == null) return@LaunchedEffect
        launch {
            while (true) {
                io { WeatherApi.fetch(cfg.latitude, cfg.longitude) }?.let { live.weather = it }
                delay(30 * 60_000L)
            }
        }
        launch {
            while (true) {
                val p = io { PrayerApi.today(cfg.prayerZone) }
                if (p != null) {
                    live.prayers = p
                    val tomorrow = LocalDate.now().plusDays(1).atStartOfDay().plusMinutes(5)
                    delay(Duration.between(LocalDateTime.now(), tomorrow).toMillis().coerceAtLeast(60_000L))
                } else delay(10 * 60_000L)
            }
        }
        if (cfg.nbaTeam.isNotBlank()) launch {
            while (true) {
                val g = io { NbaApi.current(cfg.nbaTeam) }
                if (g != null) live.game = g
                val soon = g != null && (g.state == "in" || Duration.between(Instant.now(), g.start).toMinutes() < 20)
                delay(if (soon) 60_000L else 30 * 60_000L)
            }
        }
        launch {
            while (true) {
                if (cfg.calendarIcsUrls.isNotEmpty()) {
                    val all = cfg.calendarIcsUrls.mapNotNull { url -> io { Ics.parse(Net.get(url)) } }
                    if (all.isNotEmpty()) live.events = all.flatten()
                }
                if (cfg.holidayIcsUrl.isNotBlank()) io { Ics.parse(Net.get(cfg.holidayIcsUrl)) }?.let { live.holidays = it }
                delay(30 * 60_000L)
            }
        }
    }
    return live
}

private suspend fun <T> io(block: () -> T): T? =
    withContext(Dispatchers.IO) { runCatching(block).getOrNull() }

// ---------- Network ----------

object Net {
    fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "TVHome/0.2 (Android TV launcher)")
        }
        return try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
}

/** Open-Meteo: free, no key. */
object WeatherApi {
    fun fetch(lat: Double, lon: Double): Weather {
        val o = JSONObject(Net.get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto"
        )).getJSONObject("current")
        return Weather(o.getDouble("temperature_2m").roundToInt(), skyFor(o.getInt("weather_code")))
    }
}

/** JAKIM e-Solat, official times for a zone (WLY01 = Kuala Lumpur, Putrajaya). */
object PrayerApi {
    fun today(zone: String): List<Prayer> {
        val o = JSONObject(Net.get(
            "https://www.e-solat.gov.my/index.php?r=esolatApi/takwimsolat&period=today&zone=$zone"
        )).getJSONArray("prayerTime").getJSONObject(0)
        fun t(k: String) = LocalTime.parse(o.getString(k))
        return listOf(
            Prayer("Subuh", t("fajr"), true),
            Prayer("Syuruk", t("syuruk"), false),
            Prayer("Zohor", t("dhuhr"), true),
            Prayer("Asar", t("asr"), true),
            Prayer("Maghrib", t("maghrib"), true),
            Prayer("Isyak", t("isha"), true),
        )
    }
}

/** Next prayer today, or tomorrow's Subuh (today's time is close enough) after Isyak. */
fun nextPrayer(list: List<Prayer>, now: LocalTime): Prayer? {
    val p = list.filter { it.isPrayer }
    return p.firstOrNull { it.time.isAfter(now) } ?: p.firstOrNull()
}

fun minutesUntil(now: LocalTime, t: LocalTime): Long {
    val m = Duration.between(now, t).toMinutes()
    return if (m < 0) m + 24 * 60 else m
}

/** ESPN's public scoreboard feed (no key). */
object NbaApi {
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba"

    fun current(team: String): Game? {
        val now = Instant.now()
        val sched = parse(JSONObject(Net.get("$BASE/teams/$team/schedule")).optJSONArray("events"), team)
        val g = sched.filter { it.state == "in" || (it.state == "pre" && it.start.isAfter(now.minus(Duration.ofHours(4)))) }
            .minByOrNull { it.start } ?: return null
        // Live or about to start: the scoreboard has the up-to-date score and clock
        if (g.state == "in" || g.start.isBefore(now.plus(Duration.ofMinutes(20)))) {
            runCatching { parse(JSONObject(Net.get("$BASE/scoreboard")).optJSONArray("events"), team).firstOrNull() }
                .getOrNull()?.let { return it }
        }
        return g
    }

    private fun parse(arr: JSONArray?, team: String): List<Game> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Game>()
        for (i in 0 until arr.length()) runCatching {
            val e = arr.getJSONObject(i)
            val comp = e.getJSONArray("competitions").getJSONObject(0)
            val type = (comp.optJSONObject("status") ?: e.getJSONObject("status")).getJSONObject("type")
            val cs = comp.getJSONArray("competitors")
            var us: JSONObject? = null
            var them: JSONObject? = null
            for (j in 0 until cs.length()) {
                val c = cs.getJSONObject(j)
                if (c.getJSONObject("team").optString("abbreviation").equals(team, true)) us = c else them = c
            }
            val u = us ?: return@runCatching
            val t = them ?: return@runCatching
            out += Game(
                team = u.getJSONObject("team").optString("abbreviation").uppercase(),
                opp = t.getJSONObject("team").optString("abbreviation").uppercase(),
                home = u.optString("homeAway") == "home",
                start = instant(e.getString("date")),
                state = type.optString("state"),
                detail = type.optString("shortDetail"),
                us = score(u), them = score(t),
            )
        }
        return out.sortedBy { it.start }
    }

    private fun score(c: JSONObject): String? =
        c.optJSONObject("score")?.optString("displayValue")?.ifBlank { null }
            ?: c.optString("score").ifBlank { null }

    private fun instant(s: String): Instant =
        runCatching { Instant.parse(s) }.getOrElse {
            OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")).toInstant()
        }
}

/** Minimal iCal reader: SUMMARY, DTSTART and yearly repeats (enough for events and birthdays). */
object Ics {
    fun parse(text: String): List<CalEvent> {
        val lines = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").split("\n")
        val out = mutableListOf<CalEvent>()
        var inEvent = false
        var title: String? = null
        var start: String? = null
        var params = ""
        var yearly = false
        for (l in lines) {
            when {
                l == "BEGIN:VEVENT" -> { inEvent = true; title = null; start = null; params = ""; yearly = false }
                l == "END:VEVENT" -> {
                    val t = title; val s = start
                    if (t != null && s != null) event(t, params, s, yearly)?.let(out::add)
                    inEvent = false
                }
                !inEvent -> {}
                l.startsWith("SUMMARY") -> title = l.substringAfter(':')
                    .replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").trim()
                l.startsWith("DTSTART") -> { params = l.substringBefore(':'); start = l.substringAfter(':').trim() }
                l.startsWith("RRULE") -> yearly = l.contains("FREQ=YEARLY")
            }
        }
        return out
    }

    private fun event(title: String, params: String, v: String, yearly: Boolean): CalEvent? = runCatching {
        if (v.length == 8) {
            CalEvent(title, LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE), null, yearly)
        } else {
            val ldt = LocalDateTime.parse(v.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            val zone: ZoneId = when {
                v.endsWith("Z") -> ZoneOffset.UTC
                params.contains("TZID=") -> ZoneId.of(params.substringAfter("TZID=").substringBefore(';'))
                else -> ZoneId.systemDefault()
            }
            val local = ldt.atZone(zone).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            CalEvent(title, local.toLocalDate(), local.toLocalTime(), yearly)
        }
    }.getOrNull()

    /** Date this event happens on, from today onwards (yearly events move to this or next year). */
    fun occurrence(e: CalEvent, today: LocalDate): LocalDate {
        if (!e.yearly) return e.date
        val d = e.date.withYear(today.year)
        return if (d.isBefore(today)) e.date.withYear(today.year + 1) else d
    }
}
