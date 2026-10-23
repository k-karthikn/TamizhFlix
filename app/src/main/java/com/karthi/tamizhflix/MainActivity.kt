package com.karthi.tamizhflix

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
import com.karthi.tamizhflix.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            var mode by remember {
                mutableStateOf(ThemeMode.entries.firstOrNull { it.name == preferences.getString("mode", null) }
                    ?: ThemeMode.SYSTEM)
            }
            TamizhFlixTheme(mode) {
                TamizhFlixApp(mode, onThemeChange = {
                    mode = it
                    preferences.edit().putString("mode", it.name).apply()
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TamizhFlixApp(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeChange: (ThemeMode) -> Unit = {},
    vm: MovieViewModel = viewModel()
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Resolution.File?>(null) }
    var showingDownloads by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    val updater = remember { ReleaseUpdater(context.applicationContext) }
    var availableUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
    var dismissedUpdate by rememberSaveable { mutableStateOf("") }
    var activeUpdate by remember { mutableStateOf<Pair<Long, AppVersion>?>(null) }
    var pendingInstaller by remember { mutableStateOf<File?>(null) }
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun openInstaller(file: File) {
        try { context.startActivity(updater.installerIntent(file)) }
        catch (e: Exception) { notify("Could not open Android installer: ${e.message}") }
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = pendingInstaller
        pendingInstaller = null
        if (file != null && (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()))
            openInstaller(file)
        else notify("Allow app installs for TamizhFlix to finish updating.")
    }
    fun requestInstall(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            pendingInstaller = file
            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
        } else openInstaller(file)
    }
    LaunchedEffect(Unit) {
        val pending = updater.pendingDownload()
        if (pending != null) activeUpdate = pending
        else updater.check()?.let { if (it.version.toString() != dismissedUpdate) availableUpdate = it }
    }
    LaunchedEffect(activeUpdate) {
        val task = activeUpdate ?: return@LaunchedEffect
        try {
            val file = updater.awaitVerifiedApk(task.first, task.second)
            activeUpdate = null
            requestInstall(file)
        } catch (e: Exception) {
            activeUpdate = null
            notify(e.message ?: "Could not install this update.")
        }
    }
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
                Box {
                    TextButton(onClick = { themeMenu = true }) { Text(themeMode.label) }
                    DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                        ThemeMode.entries.forEach { mode ->
                            DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                                onThemeChange(mode)
                                themeMenu = false
                            })
                        }
                    }
                }
                IconButton(onClick = { showingDownloads = true }) {
                    Icon(painterResource(R.drawable.ic_action_library), contentDescription = "Downloads")
                }
            })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Discover", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Find a movie", style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold)
                    Text("Search the catalogue, then choose how to watch.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Catalogue", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Source.entries.forEach { source ->
                                FilterChip(selected = vm.source == source,
                                    onClick = { vm.changeSource(source) }, label = { Text(source.label) })
                            }
                        }
                        OutlinedTextField(value = vm.query, onValueChange = { vm.query = it },
                            label = { Text("Movie title") }, placeholder = { Text("e.g. Vikram") },
                            singleLine = true, shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }))
                        OutlinedTextField(value = vm.year,
                            onValueChange = { vm.year = it.filter(Char::isDigit).take(4) },
                            label = { Text("Release year (optional)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }))
                        Button(onClick = search, enabled = vm.query.isNotBlank() && !vm.loading,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(18.dp)) { Text("Search movies") }
                    }
                }
            }
            if (vm.loading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Searching catalogue pages…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.error?.let { message -> item { MessageCard(message, "Retry", search) } }
            vm.warning?.let { message -> item { MessageCard(message, "Retry", search) } }
            if (!vm.loading && vm.error == null) {
                item {
                    Text(if (!vm.searched) "Ready when you are"
                    else if (vm.results.isEmpty()) "No matches found"
                    else "${vm.results.size} movies found", style = MaterialTheme.typography.titleLarge)
                    if (!vm.searched || vm.results.isEmpty()) Text(
                        if (!vm.searched) "Start with a title. Add a year for titles beginning with a number or Tamil letter."
                        else "Try the start of the title, a different year, or the other source.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(vm.results, key = { it.url }) { movie ->
                Card(onClick = { vm.open(movie) }, shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(movie.title, style = MaterialTheme.typography.titleMedium)
                        Text("Choose release and quality", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    if (vm.canGoBack) TextButton(onClick = { vm.back() }) { Text("Previous choices") }
                }
                if (vm.resolving) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Finding download options…") }
                vm.resolverError?.let { item { MessageCard(it, "Retry", vm::retry) } }
                when (val result = vm.resolution) {
                    is Resolution.Choices -> items(result.links, key = { it.url }) { link ->
                        Card(onClick = { vm.follow(link) },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Text(link.title, Modifier.fillMaxWidth().padding(20.dp),
                                style = MaterialTheme.typography.titleMedium)
                        }
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
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(context, MoviePlayerActivity::class.java).apply {
                                    putExtra("url", result.link.url)
                                    putExtra("title", movie.title)
                                    putExtra("referer", result.link.referer)
                                    putExtra("cookie", vm.transport.cookieHeader(result.link.url))
                                })
                            },
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Play here") }
                            OutlinedButton(onClick = { openPlayer(result) },
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Open in app") }
                        }
                    }
                    null -> Unit
                }
            }
        }
    }
    availableUpdate?.let { release ->
        AlertDialog(
            onDismissRequest = { dismissedUpdate = release.version.toString(); availableUpdate = null },
            title = { Text("Update available") },
            text = { Text("${release.name} is ready. Download the update and install it with Android?") },
            confirmButton = { TextButton(onClick = {
                availableUpdate = null
                try {
                    activeUpdate = updater.start(release)
                    notify("Downloading update in the background…")
                } catch (e: Exception) { notify(e.message ?: "Could not start the update.") }
            }) { Text("Update") } },
            dismissButton = { TextButton(onClick = {
                dismissedUpdate = release.version.toString()
                availableUpdate = null
            }) { Text("Later") } }
        )
    }
    if (showingDownloads) DownloadsScreen(onClose = { showingDownloads = false })
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
