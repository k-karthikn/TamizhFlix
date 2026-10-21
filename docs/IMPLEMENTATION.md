# TamizhFlix native implementation

## Scope

Kotlin and Jetpack Compose, Material 3 Expressive, Tamil/dubbed title search,
optional release year, release/quality/file/server selection and native Android
DownloadManager. Downloads go to Downloads/TamizhFlix and continue independently
of the activity. The Downloads action opens Android's download UI. No built-in player,
Python runtime, Termux, ffmpeg or yt-dlp is included. HLS is rejected with a message
rather than saving a playlist as a movie.

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
  Native code uses typed bundled Source defaults, not this remote configuration.
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
- MovieViewModel.kt: lifecycle-retained search/selection state and job cancellation.
- MovieDownloads.kt: DownloadManager handoff.
- MainActivity.kt: search UI, status and retry states, selection sheet.
- src/test/resources/fixtures: captured HTML for parser regressions.

The two homepage defaults are in Source in Catalogue.kt. Changing homepage domains
requires updating those defaults and rebuilding. This deliberately avoids allowing
an unrelated remote JSON refresh to replace working user-supplied domains.

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
transfer status and retry; the app does not maintain a separate download library.

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
