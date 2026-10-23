package com.karthi.tamizhflix

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

private data class MovieDownload(val id: Long, val title: String, val status: Int, val bytes: Long, val total: Long)

private fun readDownloads(manager: DownloadManager): List<MovieDownload> = buildList {
    manager.query(DownloadManager.Query()).use { cursor ->
        val id = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
        val title = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
        val status = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
        val bytes = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val total = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val description = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)
        while (cursor.moveToNext()) {
            if (!cursor.getString(description).orEmpty().startsWith("TamizhFlix •")) continue
            add(MovieDownload(cursor.getLong(id), cursor.getString(title) ?: "Movie",
                cursor.getInt(status), cursor.getLong(bytes), cursor.getLong(total)))
        }
    }
}.sortedByDescending { it.id }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    val preferences = remember { context.getSharedPreferences("downloads", Context.MODE_PRIVATE) }
    val hidden = remember { mutableStateListOf<Long>().apply {
        addAll(preferences.getStringSet("hidden", emptySet()).orEmpty().mapNotNull(String::toLongOrNull))
    } }
    var downloads by remember { mutableStateOf(emptyList<MovieDownload>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<MovieDownload?>(null) }
    fun removeFromList(item: MovieDownload) {
        hidden.add(item.id)
        preferences.edit().putStringSet("hidden", hidden.map(Long::toString).toSet()).apply()
        downloads = downloads.filterNot { it.id == item.id }
    }
    fun play(item: MovieDownload) {
        val uri = manager.getUriForDownloadedFile(item.id)
        if (uri == null) { message = "File unavailable. It may have been moved or deleted."; return }
        context.startActivity(Intent(context, MoviePlayerActivity::class.java).apply {
            putExtra("url", uri.toString())
            putExtra("title", item.title)
        })
    }
    LaunchedEffect(Unit) {
        while (true) {
            try { downloads = readDownloads(manager).filterNot { it.id in hidden } }
            catch (e: Exception) { message = "Could not read downloads: ${e.message}" }
            delay(2000)
        }
    }
    deleteCandidate?.let { item ->
        AlertDialog(onDismissRequest = { deleteCandidate = null }, title = { Text("Delete download?") },
            text = { Text("This removes the download and its saved file.") },
            confirmButton = { TextButton(onClick = {
                manager.remove(item.id)
                downloads = downloads.filterNot { it.id == item.id }
                deleteCandidate = null
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } })
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(title = { Text("Downloads") }, actions = {
                    TextButton(onClick = onClose) { Text("Close") }
                })
                message?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text("Your library", style = MaterialTheme.typography.headlineMedium)
                        Text("Track transfers and play saved movies.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (downloads.isEmpty()) item {
                        Card(shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.fillMaxWidth().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Nothing here yet", style = MaterialTheme.typography.headlineSmall)
                                Text("Movies you save will appear here with their progress.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    val active = downloads.filter { it.status in listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED) }
                    val completed = downloads.filter { it.status == DownloadManager.STATUS_SUCCESSFUL }
                    val failed = downloads.filter { it.status == DownloadManager.STATUS_FAILED }
                    if (active.isNotEmpty()) item { SectionLabel("In progress", active.size) }
                    items(active, key = { it.id }) { item ->
                        DownloadCard(item, "Downloading", {}, { removeFromList(item) }, { deleteCandidate = item })
                    }
                    if (completed.isNotEmpty()) item { SectionLabel("Completed", completed.size) }
                    items(completed, key = { it.id }) { item ->
                        DownloadCard(item, "Tap Play to watch", { play(item) }, { removeFromList(item) }, { deleteCandidate = item })
                    }
                    if (failed.isNotEmpty()) item { SectionLabel("Failed", failed.size) }
                    items(failed, key = { it.id }) { item ->
                        DownloadCard(item, "Download failed", {}, { removeFromList(item) }, { deleteCandidate = item })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    Text("$title  ·  $count", style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun DownloadCard(item: MovieDownload, subtitle: String,
    onPlay: () -> Unit, onRemove: () -> Unit, onDelete: () -> Unit) {
    Card(shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.status == DownloadManager.STATUS_RUNNING && item.total > 0) {
                LinearProgressIndicator(progress = { (item.bytes.toFloat() / item.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
                Text("${item.bytes / 1_000_000} / ${item.total / 1_000_000} MB")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
                    FilledTonalButton(onClick = onPlay) { Text("Play") }
                }
                TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
