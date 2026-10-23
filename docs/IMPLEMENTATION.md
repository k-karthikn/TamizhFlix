# TamizhFlix native implementation

## Scope

Kotlin and Jetpack Compose, Material 3 Expressive, Tamil/dubbed title search,
optional release year, release/quality/file/server selection and native Android
DownloadManager. Downloads go to Downloads/TamizhFlix and continue independently
of the activity. The app includes a simple Media3 player and a Downloads screen.
No Python runtime, Termux, ffmpeg or yt-dlp is included. HLS is rejected with a
message rather than saving a playlist as a movie.

## padam-cli review

Reviewed main.py, all seven src modules, config/config.json, README, packaging,
installation scripts and dependency declarations. Its movie data comes from HTML,
not JSON. JSON only holds domains, endpoints, headers and excluded navigation titles.

Original flow: choose an alphabet/year listing → collect pagination → filter title
links → traverse releases and qualities → coral file link → dlink server pages →
media URL. CLI-specific playback and subprocess download features are not ported.

Issues addressed by the native rewrite:

- Config objects are loaded during Python module import, before main refreshes the
  remote JSON. Thus refreshed domains are not applied to those objects. The CLI
  also overwrites its cache before validating JSON and does not catch missing keys.
  Native code validates the remote domain configuration before caching it and
  applies refreshed domains during search.
- Current supplied domains differ from the CLI's saved and fallback domains.
  Isaidub's supplied homepage links to a separate catalogue host; discover that
  category link on each search before constructing the alphabet/year URL.
- Fetch failures in the CLI return None and then callers dereference it. Native
  errors are shown with retry; network failures cannot masquerade as an empty result.
- CLI resolves relative links against a fixed domain. Native parsing uses the
  response's final URL, including redirects, for every link.
- CLI follows only pagination linked on the first page. Native traversal discovers
  additional pages, deduplicates URLs and caps search at 80 pages. Partial network
  failures and reaching the cap produce explicit warnings.
- CLI indexes the first file/server blindly and uses URL substrings such as 'cdn'
  and 'dub' to guess media. Native choices preserve alternatives, remove streaming
  links and duplicate servers, and verify the HTTP content type before download.
- Resolver has a 15-hop bound and visited-set protection. Cancellation closes the
  active HTTP call; replaced searches do not overwrite newer loading state.
- Header/cookie context is retained for downloads. Filenames are sanitized and
  uniquely suffixed. Android 7–9 request storage permission only when downloading;
  newer Android versions do not request broad storage access.

## Structure

- data/Catalogue.kt: source definitions, pure HTML parsing, search and resolver.
- data/HttpPageFetcher.kt: cancellable OkHttp requests, timeouts, cookies, bounded
  HTML bodies, HTTPS-only links, no JavaScript execution.
- data/DomainConfig.kt: validated remote domain config, cached last-known-good
  values, and built-in offline defaults.
- MovieViewModel.kt: lifecycle-retained search/selection state and job cancellation.
- MovieDownloads.kt: DownloadManager handoff.
- MainActivity.kt: search UI, status and retry states, selection sheet.
- src/test/resources/fixtures: captured HTML for parser regressions.

The app checks `https://raw.githubusercontent.com/k-karthikn/padam-cli/refs/heads/master/config/config.json`
for `domains.moviesda` and `domains.isaidub`. It checks at most once per hour after
a successful update, or every five minutes after a failed update. If a homepage
request fails, it forces a config refresh and retries when the URL changes. The
last validated config is cached; `Source` in `Catalogue.kt` provides offline defaults.
The JSON `config_url` field is ignored so the update authority stays fixed.

## Validation

Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
On this workstation prefix with `JAVA_HOME=/opt/android-studio/jbr`.
Parser/repository tests run without network and include both real catalogue layouts,
relative URLs, pagination, partial failures, numeric queries, duplicate servers,
HTML pretending to be media, HLS rejection and resolver cycles.

Opt-in live checks: `TAMIZHFLIX_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest --tests '*LiveCatalogueTest' --rerun-tasks`.
They search both catalogues and traverse to media response headers without saving
movie files. Third-party sources can change or be unavailable independently of the app.

Device validation still required: layout at large font sizes, rotation while
loading, storage permission flow on Android 7–9, enqueue/completion notification,
background download persistence and opening Downloads. No device/emulator was
available during implementation.

## Limitations

Alphabet search requires the title's starting letter, as in padam-cli. Numeric or
Tamil-script titles need a year. Search does not crawl the entire catalogue for
arbitrary middle-of-title queries. HTTPS-only sources are supported. HLS and sites
requiring JavaScript/challenge completion are not supported. DownloadManager handles
transfer status and retry; the app reads its records for the Downloads screen.

### Verified in this workspace

Final build: successful. Thirteen scraper regression tests and both opt-in live
source tests passed (plus the template unit test). Android lint: 0 errors,
24 warnings, chiefly dependency/version-catalog suggestions and unused template
resources. Debug APK: app/build/outputs/apk/debug/app-debug.apk. Live checks verified
search and resolution to media response headers for both sources; they did not
validate a completed DownloadManager transfer on Android.

### Release startup crash fixed (20 October 2026)

Phone logs showed `IllegalAccessError: Class kotlin.sequences.d extended by class
v91 is inaccessible` while Compose initialized its font resolver. The matching R8
mapping identifies these as SequencesKt__SequenceBuilderKt and
SequencesKt___SequencesJvmKt. The original scoped release optimization produced
invalid access between those classes. Release optimization is disabled as a
workaround; signing and non-debuggable release behavior remain enabled. This
increases APK size. Re-enable optimization only after verifying cold startup of
the optimized release on a device.

The corrected signed APK was installed over the previous release with `adb
install -r`. Cold launch returned `Status: ok`; a subsequent process/activity
check confirmed the app remained alive and resumed, and its PID had no entries
in the crash log buffer. Validation used ADB status/logs only, with no screen capture.


### External player option (version 1.1)

Verified movie files now offer Download movie and Open in another app. The latter
uses Android ACTION_VIEW with the HTTPS media URL and a video MIME type, and
shows the system chooser for installed compatible players (such as VLC or MX
Player). It does not start a download or request storage permission. A scoped
package-visibility query supports checking for a compatible player on Android 11+.
When none is installed the app displays a useful message.

Playback happens in the selected app. Standard ACTION_VIEW does not guarantee that
a player forwards the scraper's cookies or Referer headers; servers requiring
those headers may still need the existing Download option. HLS resolution remains
outside this change.

### Version 1.2 playback and downloads

The resolved-file actions now have Download on the first row, with Play and Open
beneath it. Play uses Media3 ExoPlayer with its built-in play/pause, seek and
full-screen controls. The player forwards the scraper's User-Agent, Referer and
Cookie headers. Open sends the URL to an external player. The Downloads screen
reads this app's Android DownloadManager records, groups active/completed/failed
items, shows transfer progress, and offers Open file or Remove.

The signed release uses the existing local key and versionCode 3. The previous
R8 workaround remains in place. Search/parser regression tests and release
build passed. A connected device was unavailable for playback verification.

### Version 1.3 player and library controls

The player offers Auto (full sensor rotation), Lock (current orientation), and
Landscape rotation modes. Full screen hides system bars and makes video fill the
window; exiting the player restores system bars. Orientation/screen-size changes
are handled by the player activity so playback remains active on rotation.

The Downloads view now plays completed files inside the app. Remove hides an item
from this app's list without deleting the file; Delete asks for confirmation and
removes the DownloadManager record and saved file. Material icons and a TamizhFlix
red color scheme distinguish search, playback, download, and library actions.

### Version 1.3.2 domain updates

Search reads the two domains from the user's GitHub config without an APK rebuild.
Both URLs must be HTTPS domain roots, and both keys must be present before the
config replaces the cached version. Unit tests cover valid/invalid config values
and retrying a failed homepage after a domain change. The release APK was built
and signed with the existing local key.

### Version 1.4.0 update flow and interface

On app open, a background request checks the latest published GitHub release.
Release tags use `vMAJOR.MINOR.PATCH`; see [RELEASING.md](RELEASING.md). An update
prompt appears only for a newer version with a GitHub-hosted APK. DownloadManager
fetches the APK after consent, and the app checks package name, version code and
signing certificate before opening Android's installer. An unfinished download
is resumed when the app opens again.

The main screen has a focused search form and simpler result cards. The Downloads
action is icon-only, and the Downloads screen shows only movie transfers. Theme
choices are System, Light and Dark. Android 12+ uses wallpaper-based dynamic
colors; older Android versions use matching fallback palettes. No APK was
packaged for this change at the user's request.
