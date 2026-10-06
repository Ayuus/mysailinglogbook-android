# Notes for developers

What to know before changing this code. The README is the user manual; this is the part for whoever changes the app.

## How it fits together

```
MainActivity (manual download + auto-start on launch)
  -> HotspotDetector           network detection: finds the phone's own private-network subnet (NetworkInterface enumeration)
  -> android_entry.sync_from_w2k2()   [Chaquopy call into the real nmea2log package]
       -> w2k2_download.discover_w2k2()   scans that subnet for the W2K-2's HTTP API
       -> w2k2_download.download_file()   downloads new/changed .ebl files
       -> run_pipeline()                  decode -> build_trips -> write_html_logbook()
  -> WebView shows the resulting logbook.html
  -> RestUploader (optional)      publishes logbook.html to WordPress
```

The app calls these actions **download**, **assemble**, **publish** and **import**. Some names in the code still say
sync or build (`SyncController`, `SyncState`, `sync_from_w2k2()`, `build_from_local_files()`, `HotspotDetector`): they are
identifiers, not wording, and are not changed for the sake of it.

- **`android_entry.py`** (in the nmea2log repo, not here) is the Chaquopy entry point. It mirrors
  what the desktop CLI's `_run()` does -- minus argument parsing and minus any upload, both of
  which are Kotlin's job on this platform -- and returns a plain `dict` a background thread can
  read, instead of relying on stderr text or an exit code the way the desktop CLI does.
- **`SettingsStore`** wraps `EncryptedSharedPreferences` for everything `nmea2log.ini` holds on
  desktop (W2K-2 login, boat identity, WordPress publish settings) -- there is no config file on
  Android, values are entered once via `SettingsActivity`.
- **`SyncController`** is the interface Kotlin implements and hands to
  `android_entry.sync_from_w2k2()` via Chaquopy, so Python can call back into it like a normal
  Python object: `report()` for per-file progress, `isCancelled()` to stop a download cleanly when the
  app closes, `onLogLine()` to mirror the desktop CLI's own `[info]`/`[ok]`/`[skip]`/`[warning]`
  messages verbatim in the UI, and `onDownloadComplete()` (see "The download notification is stopped as soon as downloading finishes" below).

## Texts shared with the iOS app

The strings both apps show are not edited here: they live in the nmea2log repo's `src/nmea2log/app_texts.py`
(the iOS app reads that file directly) and are written into `values*/strings.xml` from there:

```bash
python -m nmea2log.export_android_strings app/src/main/res      # run from the nmea2log repo's src/
```

Edit such a string in `app_texts.py` and export, never by hand in `strings.xml` (the next export would
overwrite it). Strings that only exist on Android stay here. `--check` reports drift.

The defaults and constants both apps share (`app_settings.py`, `app_constants.py`) are written into
`SharedConstants.kt` the same way: `python -m nmea2log.export_android_constants <path to SharedConstants.kt>`.

## Design choices worth knowing before changing this code

**`autoStartSyncWithSettingsRetry()`'s own "vul W2K-2-gegevens in" line is not a startup nag --
it's gated behind an explicit opt-in.** `onCreate()` only ever calls this function at all when
`settingsStore.autoSyncOnLaunch` ("Automatisch downloaden bij starten") is already on (see its own
branching, right above); with that setting off, a fresh launch calls `viewLocalLogbook()` instead
and never reaches this function, message or not. Tempting to remove the message once
`importButton`'s SD/USB import (see the toolbar section of the README) meant W2K-2 credentials were no
longer strictly required to use the app at all -- tried exactly that, found in practice it was
wrong: someone who only ever imports from a card has no reason to turn *on* "automatically
download on launch" in the first place, so they'd never reach this branch either way, message or
not. The only person this line can ever actually reach already asked for auto-download
specifically, and incomplete settings blocking that is exactly the kind of thing they'd want to
know about, every time, until fixed -- not a nag, a status report on a feature they opted into.
Deliberately does *not* fall back to showing whatever logbook is already on the phone (asked for
explicitly, "logboek alleen tonen als auto download uit staat") -- same reasoning applies to its
own hotspot-not-found sibling branch just above: with auto-download on, the owner asked to see
fresh data, not whatever's cached; that fallback belongs solely to the
`settingsStore.autoSyncOnLaunch == false` branch in `onCreate()`, which calls `viewLocalLogbook()`
unconditionally.

**The network detection (`HotspotDetector`) has two different checks, deliberately not interchangeable.**
`detectSubnetPrefix()` -- used everywhere the app actually reaches the W2K-2 (manual download, boat
mode's own search/probe) -- takes *any* interface with a private IPv4 address up, preferring an
AP-named one if more than one is up at once but falling back to whatever matched otherwise. That's
what makes phone-and-W2K-2-on-the-same-external-WiFi work exactly as well as the phone's own
hotspot: neither this function nor the Python `discover_w2k2()` scan it feeds cares which one it
is, only that the phone is on *some* private subnet the W2K-2 might also be on.
`isHotspotUp()` is the stricter one, and has exactly one caller: `shouldAutoStartBootMode()`. It
only counts an AP-named interface (`ap_br_swlan0` on the test Samsung Galaxy S23; Android's docs
mention `ap0`/`wlan1` as other common OEM names), i.e. specifically whether *this phone's own*
tethering/hotspot feature is switched on -- deliberately narrower, since auto-starting boat mode
needs a reliable "I'm on the boat" signal, and "joined to some WiFi network" (which could just as
easily be a cafe or the owner's own home) isn't specific enough for that, the way "my own hotspot
is on" is. This also rules out `ConnectivityManager.NetworkCallback` (which observes networks
*this device* joins as a client, not its own AP state) as an event source for "is the hotspot
back".

**There is no automatic reconnect or polling after a failed download.** When a download cannot find the W2K-2 (or fails), a
dialog offers to assemble the logbook from what is already on the phone, or to close the app -- not an immediate retry,
which out of range of the boat would just fail again and show the same dialog again. Finding the W2K-2 means
scanning for the real device (`discover_w2k2()`), not just checking that the phone's own hotspot is on: the hotspot
commonly stays on while the W2K-2 itself drops off it (e.g. walking away from the boat). Boat mode does that scan on
its own timer (its searches and rounds); a manual download does it once per tap.

**The download notification is stopped as soon as downloading finishes, before decode/assemble runs.**
`SyncController.onDownloadComplete()` fires once, right after the last file's download attempt and
before Python's decode/assemble/write pipeline starts. Kotlin uses it to stop the foreground
notification service at that point rather than keeping it running for the whole call: decode/assemble
is pure CPU, no network I/O, so there's no reason to keep paying for a "dataSync" foreground
service during it. This matters because Android 15+ (this app targets SDK 37) caps a `dataSync`
foreground service at **6 cumulative hours per rolling 24h period**; a long day of intermittent
connectivity could otherwise burn through that budget on wait time and idle CPU work rather than
actual network activity, and Android then simply refuses to start the service again until the
budget resets or the user brings the app to the foreground. `startSyncNotification()` also catches
that refusal gracefully wherever it's used -- a download still completes without a visible notification
rather than crashing outright if the budget is ever actually exhausted.

**The generated `logbook.html` renders server-side in Dutch by default, unchanged** -- but every
translatable label also carries a `data-i18n`/`data-i18n-tpl` attribute, and all four supported
languages (NL/EN/FR/DE) are embedded in the page as one JS object. A flag button in the page's own
header lets a viewer switch languages client-side without needing the file regenerated, since the
page can be opened by people who don't read Dutch. This lives in the nmea2log repo
(`html_writer.py`/`translations.py`), not here, but the Android app benefits from it automatically
since it calls the same `write_html_logbook()`.

**The page also shows the boat's single most recent GPS fix ("Laatste positie") under "Laatst
bijgewerkt".** A trip that's still underway when the downloaded data runs out has no arrival place
of its own to show in the trips table (`"Unknown (end outside log file)"`), which used to leave no
indication anywhere on the page of where the boat actually last was. Built from the single latest
GPS fix across the *whole* dataset, not the last completed trip's own arrival -- those can disagree
by hours or days if the boat has been anchored/idle (still logging position) since the last trip
closed. Reverse-geocoded into a place name the same way every trip's own depart/arrive place
already is; falls back to plain coordinates when a lookup fails (see the geocoding paragraph below).

**Geocoding, weather, and marine (wave/current) lookups are enabled by default on Android too**,
same as the desktop CLI (see `android_entry.run_pipeline()`'s own doc comment) -- this changed
since an earlier version of this README said otherwise. All three ride the phone's own internet
connection, not the W2K-2's (which never needs internet at all); no settings toggle exists yet to
turn them off for someone who'd rather save cellular data than see place names/weather/sea state
in the logbook.

**The toolbar uses plain single-path vector drawables, not emoji-as-button-text** (an earlier
version of this app used 🔄/☁️/⚙️ etc.; replaced once icon *behaviour* -- a pulsing "busy" state,
matching on/off icon pairs for boat mode -- needed more control than a font glyph gives).
Borderless (no background box/shadow, `selectableItemBackgroundBorderless`, zero minimum touch
target) so they still read as plain icons rather than boxed buttons; tint applied at runtime by
`iconButton()`, so each drawable's own `fillColor` is just a placeholder.

There is a small Kotlin unit test suite (`app/src/test/`, run via `./gradlew test`), but it's
deliberately scoped to pure logic only (currently: `HotspotDetectorTest`, covering the private-IPv4
range checks) -- notification handling, settings storage, the WordPress client, and the reconnect flow
all need real Android framework classes or real network I/O to exercise meaningfully, and aren't
covered by anything automated yet. The Python side this app calls into is covered separately by
nmea2log's own extensive pytest suite.
