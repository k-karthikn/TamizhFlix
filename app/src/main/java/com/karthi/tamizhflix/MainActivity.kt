package com.karthi.tamizhflix

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karthi.tamizhflix.data.*
import com.karthi.tamizhflix.ui.theme.TamizhFlixTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TamizhFlixTheme { TamizhFlixApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TamizhFlixApp(vm: MovieViewModel = viewModel()) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Resolution.File?>(null) }
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun download(file: Resolution.File) {
        try {
            MovieDownloads.enqueue(context, file, vm.selected?.title ?: file.link.title, vm.transport.cookieHeader(file.link.url))
            vm.close()
            notify("Download queued. View progress in Downloads.")
        } catch (e: Exception) { notify(e.message ?: "Could not start download. Try again.") }
    }
    fun openPlayer(file: Resolution.File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(file.link.url),
                file.mime.substringBefore(';').takeIf { it.startsWith("video/") } ?: "video/*")
            putExtra(Intent.EXTRA_TITLE, vm.selected?.title ?: file.link.title)
        }
        try {
            if (intent.resolveActivity(context.packageManager) == null) {
                notify("Install a video player such as VLC or MX Player, then try again.")
            } else {
                context.startActivity(Intent.createChooser(intent, "Open movie with"))
            }
        } catch (_: ActivityNotFoundException) {
            notify("No compatible video player found. Install VLC or MX Player.")
        } catch (_: SecurityException) {
            notify("Android could not open this link in another app.")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val file = pending
        pending = null
        if (granted && file != null) download(file) else notify("Storage permission is needed to save to Downloads on this Android version.")
    }
    val search: () -> Unit = { keyboard?.hide(); vm.search() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { Text("TamizhFlix", fontWeight = FontWeight.Bold) }, actions = {
                TextButton(onClick = {
                    try { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                    catch (_: Exception) { notify("Open your Files app to view Downloads/TamizhFlix.") }
                }) { Text("Downloads") }
            })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(32.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Find your next\nmovie.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("Search. Choose a quality. Watch or download.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Source.entries.forEach { source ->
                        FilterChip(selected = vm.source == source, onClick = { vm.changeSource(source) }, label = { Text(source.label) })
                    }
                }
            }
            item {
                OutlinedTextField(value = vm.query, onValueChange = { vm.query = it }, label = { Text("Movie title") },
                    placeholder = { Text("e.g. Vikram") }, singleLine = true, shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }))
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = vm.year, onValueChange = { vm.year = it.filter(Char::isDigit).take(4) },
                        label = { Text("Year (optional)") }, singleLine = true,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }))
                    Button(onClick = search, enabled = vm.query.isNotBlank() && !vm.loading,
                        modifier = Modifier.height(56.dp), shape = RoundedCornerShape(20.dp)) { Text("Search") }
                }
            }
            if (vm.loading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Searching catalogue pages…", Modifier.padding(top = 12.dp))
            }
            vm.error?.let { message -> item { MessageCard(message, "Retry", search) } }
            vm.warning?.let { message -> item { MessageCard(message, "Retry", search) } }
            if (!vm.loading && vm.error == null) {
                item {
                    Text(if (!vm.searched) "A little cinema, wherever you are."
                    else if (vm.results.isEmpty()) "No matches found"
                    else "${vm.results.size} movies found", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp))
                    if (!vm.searched || vm.results.isEmpty()) Text(
                        if (!vm.searched) "Search by the start of a movie’s title. Add a year for numbered or Tamil-script titles."
                        else "Try the start of the title, a different year, or the other source.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(vm.results, key = { it.url }) { movie ->
                Card(onClick = { vm.open(movie) }, shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(movie.title.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(movie.title, style = MaterialTheme.typography.titleMedium)
                            Text("Choose release & quality", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
    vm.selected?.let { movie ->
        ModalBottomSheet(onDismissRequest = { vm.close() }) {
            BackHandler { if (vm.canGoBack) vm.back() else vm.close() }
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(movie.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Choose a release, quality or server", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (vm.canGoBack) TextButton(onClick = { vm.back() }) { Text("‹ Previous choices") }
                }
                if (vm.resolving) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Finding download options…") }
                vm.resolverError?.let { item { MessageCard(it, "Retry", vm::retry) } }
                when (val result = vm.resolution) {
                    is Resolution.Choices -> items(result.links, key = { it.url }) { link ->
                        OutlinedButton(onClick = { vm.follow(link) }, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(18.dp)) { Text(link.title) }
                    }
                    is Resolution.File -> item {
                        Text("Your movie is ready", style = MaterialTheme.typography.titleLarge)
                        Text(result.link.title, modifier = Modifier.padding(vertical = 8.dp))
                        result.bytes?.let { Text("File size: %.1f MB".format(it / 1_000_000.0)) }
                        Text("Saves to Downloads/TamizhFlix", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                pending = result
                                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else download(result)
                        }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(56.dp)) { Text("Download movie") }
                        OutlinedButton(onClick = { openPlayer(result) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 56.dp)) {
                            Text("Open in another app")
                        }
                    }
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(message)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}
