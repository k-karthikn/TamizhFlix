package com.karthi.tamizhflix

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import com.karthi.tamizhflix.data.HttpPageFetcher
import com.karthi.tamizhflix.data.Resolution
import java.util.UUID

object MovieDownloads {
    fun enqueue(context: Context, file: Resolution.File, title: String, cookies: String): Long {
        val guessed = URLUtil.guessFileName(file.link.url, file.disposition, file.mime)
        val extension = guessed.substringAfterLast('.', "mp4").lowercase()
            .takeIf { it in setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp") } ?: "mp4"
        val name = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().take(100).ifBlank { "Movie" }
        val filename = "$name-${UUID.randomUUID().toString().take(8)}.$extension"
        val request = DownloadManager.Request(Uri.parse(file.link.url))
            .setTitle(title).setDescription("TamizhFlix • $filename")
            .setMimeType(file.mime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "TamizhFlix/$filename")
            .addRequestHeader("User-Agent", HttpPageFetcher.USER_AGENT)
            .apply {
                if (file.link.referer.isNotBlank()) addRequestHeader("Referer", file.link.referer)
                if (cookies.isNotBlank()) addRequestHeader("Cookie", cookies)
            }
        return context.getSystemService(DownloadManager::class.java).enqueue(request)
    }
}
