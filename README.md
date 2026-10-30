# TV Home (round 1)

Google TV-style launcher for Android TV 9+. Round 1 has the base:

- Rotating hero banner with slow zoom (TMDB trending > Play Next posters > YouTube thumbnails)
- Continue watching row from the TV's Play Next database
- Your apps row (TV banners), favourites first
- YouTube rows from video IDs in `config.json`
- Clock, focus highlight and scaling for the remote
- Back on the Home screen scrolls to top instead of exiting

Round 2 adds:

- Wallpaper home (default): changes with time of day (morning / day / evening / night) and weather, a new picture each day, 1.5s crossfades, fades to black behind the rows, slightly dimmer at night
- Focusing a show or video swaps the wallpaper for its artwork after 1 second (slow zoom). Settings > Hero switches back to the round 1 rotating banner
- Weather + next prayer pill next to the clock. Click it for the full day's schedule (JAKIM e-Solat). It turns orange 15 minutes before, and a banner shows at prayer time
- Widgets on the right: next calendar event, Trail Blazers next game or live score, now playing with album art (only while music plays)
- Birthday and holiday greeting, full screen, once a day

Coming later: ambient screensaver, bedtime nudge, phone setup page.

## Round 2 setup

**Music widget** needs one permission (any app that plays music: Spotify, YouTube Music, VLC...):
```
adb shell cmd notification allow_listener com.tvhome.launcher/com.tvhome.launcher.MediaListener
```

**Your own wallpapers** (optional, the built-in drawn ones are used until you add some). Make folders by time of day, and optionally by weather inside them, then push JPGs (1920×1080, ~300 KB each keeps it fast):
```
wallpapers/morning/        wallpapers/night/        wallpapers/night/rain/
wallpapers/day/            wallpapers/evening/      (rain, cloudy, clear, storm)

adb push wallpapers /sdcard/Android/data/com.tvhome.launcher/files/
```
A weather folder wins when it matches the current weather; otherwise any picture in the time-of-day folder is used.

**Calendar and birthdays**: in Google Calendar on the web, open the calendar's Settings > Integrate calendar > *Secret address in iCal format*, and put that link in `calendarIcsUrls` in config.json. Events titled like "Ali's birthday" (or set to repeat yearly with "birthday" in the title) trigger the greeting. You can also list birthdays directly:
```json
"birthdays": [ { "name": "Ali", "date": "03-14" } ]
```
Malaysian public holidays come from Google's public holiday calendar (`holidayIcsUrl`, set to "" to turn off).

**Other config.json fields**: `name` (greeting), `latitude` / `longitude` (weather), `prayerZone` (JAKIM zone, WLY01 = KL), `nbaTeam` (ESPN code, "" hides the card).

## Build variants

| Variant | Use |
|---|---|
| `phoneDebug` | Test on your Android phone. Opens as a normal app in landscape, never replaces your phone launcher. Play Next shows sample items. |
| `tvDebug` | The real launcher for the TV / emulator. Registers as a Home screen. |

## Build

1. Install Android Studio (Ladybug 2024.2 or newer).
2. File > Open > this `tvhome` folder. Let Gradle sync. If Studio offers to upgrade AGP or Kotlin, accepting is fine.
3. Build > Select Build Variant > pick `phoneDebug` or `tvDebug`.
4. Build > Build App Bundle(s) / APK(s) > Build APK(s).
   Output: `app/build/outputs/apk/<variant>/debug/app-<variant>-debug.apk`

## Test on phone

```
adb install -r app-phone-debug.apk
```
Or copy the APK to the phone and open it. Uninstall like any app.

## Install on the TV

Turn on Developer options > USB debugging and Network debugging on the TV, then from the PC:
```
adb connect <tv-ip>:5555
adb install -r app-tv-debug.apk
```
Press Home. Android asks which home app to use. Pick **TV Home** > Always.

### Undo (always works)
```
adb uninstall com.tvhome.launcher
```
The TV goes straight back to its previous home screen. This app never disables or removes anything on the TV.

## Play Next permission test

Check whether your TV exposes Play Next to other apps:
```
adb shell content query --uri content://android.media.tv/watch_next_program --projection title:package_name
```
After installing, try granting read access:
```
adb shell pm grant com.tvhome.launcher com.android.providers.tv.permission.READ_EPG_DATA
```
If either fails, the Continue watching row is simply hidden and the hero falls back to TMDB or YouTube art. Nothing breaks.

## Config

Default lives in `app/src/main/assets/config.json`. To change it without rebuilding, edit a copy and push it:
```
adb push config.json /sdcard/Android/data/com.tvhome.launcher/files/config.json
```
(For the phone build the package is `com.tvhome.launcher.phone`.) Then press Home or reopen the app.

- `tmdbApiKey`: free key from themoviedb.org (Settings > API). Leave blank to skip.
- `favoriteApps`: package names, shown first in this order. List all with `adb shell pm list packages`.
- `youtubeRows`: each video `id` is the part after `watch?v=` in a YouTube link.
