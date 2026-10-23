package com.karthi.tamizhflix

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AppVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

        fun parse(value: String): AppVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val parts = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class ReleaseInfo(val version: AppVersion, val name: String, val apkUrl: String)

object ReleaseParser {
    private const val REPO = "k-karthikn/TamizhFlix"

    fun parse(json: String, installedVersion: AppVersion): ReleaseInfo? {
        val release = JsonParser.parseString(json).asJsonObject
        if (release.get("draft")?.asBoolean != false || release.get("prerelease")?.asBoolean != false) return null
        val tag = release.get("tag_name")?.asString ?: return null
        if (!tag.startsWith('v')) return null
        val version = AppVersion.parse(tag.drop(1)) ?: return null
        if (version <= installedVersion) return null
        val assets = release.getAsJsonArray("assets") ?: return null
        val apkUrl = assets.firstNotNullOfOrNull { asset ->
            val url = runCatching { asset.asJsonObject.get("browser_download_url").asString }.getOrNull()
            if (url != null && isTrustedApkUrl(url, tag)) url else null
        } ?: return null
        val name = release.get("name")?.asString?.trim().orEmpty().take(80)
        return ReleaseInfo(version, name.ifBlank { "Version $version" }, apkUrl)
    }

    private fun isTrustedApkUrl(value: String, tag: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host == "github.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.encodedPath.startsWith("/$REPO/releases/download/$tag/") &&
            url.encodedPath.substringAfterLast('/').endsWith(".apk", ignoreCase = true)
    }
}

class ReleaseUpdater(private val context: Context) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val preferences = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val installedCode: Long get() = packageCode(installedPackage())
    private val installedVersion: AppVersion? get() = installedPackage().versionName?.let(AppVersion::parse)

    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder()
                .url("https://api.github.com/repos/k-karthikn/TamizhFlix/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TamizhFlix-Android")
                .build()).execute().use { response ->
                if (response.code == 404) return@withContext null // No published release yet.
                if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty release response")
                if (body.contentLength() > 256_000) throw IOException("Release response is too large")
                val source = body.source()
                source.request(256_001)
                val bytes = source.buffer.readByteArray(source.buffer.size.coerceAtMost(256_001))
                if (bytes.size > 256_000) throw IOException("Release response is too large")
                val current = installedVersion ?: return@withContext null
                ReleaseParser.parse(bytes.toString(Charsets.UTF_8), current)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null } // Update checks must never block opening the app.
    }

    fun pendingDownload(): Pair<Long, AppVersion>? {
        val id = preferences.getLong("download_id", -1)
        val version = preferences.getString("release_version", null)?.let(AppVersion::parse)
        return if (id > 0 && version != null) id to version else null
    }

    fun start(release: ReleaseInfo): Pair<Long, AppVersion> {
        require(installedVersion?.let { release.version > it } == true) {
            "This release is not newer than the installed app."
        }
        val fileName = "TamizhFlix-update-v${release.version}-${System.currentTimeMillis()}.apk"
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle(release.name)
            .setDescription("TamizhFlix app update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = manager.enqueue(request)
        preferences.edit().putLong("download_id", id)
            .putString("release_version", release.version.toString()).apply()
        return id to release.version
    }

    suspend fun awaitVerifiedApk(id: Long, expectedVersion: AppVersion): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(folder, "TamizhFlix-update-v$expectedVersion.apk")
        try {
            while (true) {
                val status = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (!cursor.moveToFirst()) throw IOException("Update download was removed.")
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> break
                    DownloadManager.STATUS_FAILED -> throw IOException("Update download failed. Please try again.")
                }
                delay(1000)
            }
            val uri = manager.getUriForDownloadedFile(id) ?: throw IOException("Downloaded APK is unavailable.")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Could not read the downloaded APK.")
            verifyApk(file, expectedVersion)
            clearPending()
            manager.remove(id)
            file
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            file.delete()
            clearPending()
            manager.remove(id)
            throw e
        }
    }

    fun installerIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun verifyApk(file: File, expectedVersion: AppVersion) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("The update is not a valid APK.")
        if (archive.packageName != context.packageName ||
            archive.versionName?.let(AppVersion::parse) != expectedVersion ||
            packageCode(archive) <= installedCode) {
            throw IOException("The update package or version does not match this app.")
        }
        val currentSigners = signers(installedPackage(flags))
        val newSigners = signers(archive)
        if (currentSigners.isEmpty() || newSigners.isEmpty() ||
            currentSigners.size != newSigners.size ||
            currentSigners.any { current -> newSigners.none { it.contentEquals(current) } }) {
            throw IOException("The update is not signed with the TamizhFlix key.")
        }
    }

    private fun installedPackage(flags: Int = 0): PackageInfo =
        @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, flags)

    private fun packageCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun signers(info: PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        else @Suppress("DEPRECATION") info.signatures?.map { it.toByteArray() }.orEmpty()

    private fun clearPending() = preferences.edit().remove("download_id").remove("release_version").apply()
}
