# TamizhFlix release versions and updates

The app checks the latest published, non-prerelease GitHub release from
`k-karthikn/TamizhFlix` when it opens. Use a three-part release tag such as
`v1.4.0`. The app compares that tag with its installed `versionName` and prompts
only for a newer version. Android still requires a separate increasing integer
`versionCode`; the downloaded APK must have a larger code before installation.

## Publish the next release

1. In `app/build.gradle.kts`, set a three-part `versionName` and increment
   `versionCode` for every release. The current source uses `versionName = "1.4.0"`
   and `versionCode = 7`. Never reuse or decrease the code. For example, a later
   release could use `versionName = "1.4.1"` and `versionCode = 8`.
2. Build a **signed release APK with the same signing key** as the installed app.
   Android will reject an update signed with a different key. Verify the package
   name stays `com.karthi.tamizhflix`.
3. Create a GitHub release in `k-karthikn/TamizhFlix` with tag `v1.4.0`, matching
   the APK's `versionName` exactly with a `v` prefix. The next could be `v1.4.1`.
   Use a clear release name, such as `TamizhFlix 1.4.0`, and attach the signed
   `.apk` file as an asset.
   Publish the release; draft and prerelease entries are ignored.

The update checker ignores missing releases, tags outside the `vMAJOR.MINOR.PATCH`
format (such as `v7`), releases without a GitHub-hosted APK asset, and tags equal
to or below the installed version. After the user taps **Update**, Android
DownloadManager downloads the APK. Before opening Android's installer,
TamizhFlix verifies the downloaded APK's package name, `versionName` matching
the tag, `versionCode` greater than the installed code, and signing certificate.
On Android 8+
the user may need to allow TamizhFlix to install apps from this source; Android
always asks the user to confirm installation.

The currently distributed 1.3.2 APK (`versionCode = 6`) does not contain this
updater. Users need to install version 7 manually once; releases after that can
be offered inside the app.
