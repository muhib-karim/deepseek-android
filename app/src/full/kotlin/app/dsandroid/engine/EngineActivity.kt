package app.dsandroid.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.dsandroid.BuildConfig
import app.dsandroid.MainActivity
import app.dsandroid.data.Appearance
import app.dsandroid.deepseek.AccountTheme
import app.dsandroid.flavor.EngineKeyForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The official DeepSeek engine: DeepSeek's own harness UI (dsh web, with dsh-web-mobile's phone layout) full screen over
 * the local harness, under one slim opaque bar that holds the app's only menu. While the runtime is unpacked on first
 * launch it shows "Preparing the engine… n%". The harness UI itself is unchanged except for the opaque-surface and
 * plugin additions in assets/engine/android-ui.js.
 */
class EngineActivity : ComponentActivity(), NodeServer.Listener, EngineWebView.Host {
    private var engineState by mutableStateOf(NodeServer.state())
    private var progress by mutableIntStateOf(NodeServer.progress())
    private var detail by mutableStateOf(NodeServer.detail())
    private var pageShown by mutableStateOf(false)
    private var loadedUrl: String? = null
    private val snackbar = SnackbarHostState()
    private var pendingSnack by mutableStateOf<Pair<String, String?>?>(null)
    private lateinit var engineWeb: EngineWebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        engineWeb = EngineWebView(this, this)
        Engine.setRoute(this, Engine.ROUTE_ENGINE)
        setContent { AccountTheme(Appearance.SYSTEM) { EngineScreen() } }
        if (Engine.hasKey(this)) NodeServer.start(this)
        if (Engine.autoUpdate(this) && Engine.updateCheckDue(this)) {
            Thread({
                val result = Engine.updateHarness(this)
                if (result.status == "updated") runOnUiThread { pendingSnack = result.message to "Restart" }
            }, "dsh-update-check").start()
        }
    }

    override fun onStart() {
        super.onStart()
        NodeServer.addListener(this)
        onState(NodeServer.state(), NodeServer.progress(), NodeServer.detail())
        NodeServer.url()?.let { onReady(it) }
    }

    override fun onStop() {
        NodeServer.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        engineWeb.destroy()
        super.onDestroy()
    }

    @Deprecated("Activity results for the WebView file chooser and the plugin pickers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!engineWeb.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    // NodeServer.Listener (worker threads)
    override fun onState(state: NodeServer.State, progress: Int, detail: String) = runOnUiThread {
        engineState = state
        this.progress = progress
        this.detail = detail
        if (state != NodeServer.State.READY) pageShown = false
    }

    override fun onReady(url: String) = runOnUiThread {
        engineState = NodeServer.State.READY
        if (url != loadedUrl) {
            loadedUrl = url
            pageShown = false
            engineWeb.load(url)
        }
    }

    // EngineWebView.Host
    override fun onPageShown() {
        pageShown = true
    }

    override fun onFileSaved(file: File, mime: String, inDownloads: Boolean) {
        pendingSnack = (if (inDownloads) "Saved to Downloads: ${file.name}" else "Ready to share: ${file.name}") to "Share"
        lastFile = file to mime
    }

    override fun onMessage(text: String) = runOnUiThread { pendingSnack = text to null }

    override fun onRestartEngine(why: String?) = runOnUiThread {
        loadedUrl = null
        pageShown = false
        if (why != null) pendingSnack = "$why: restarting the engine…" to null
        NodeServer.restart(this)
    }

    private var lastFile: Pair<File, String>? = null

    private fun share(file: File, mime: String) {
        val uri = FileBridge.uriFor(this, file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, file.name))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun EngineScreen() {
        var menu by remember { mutableStateOf(false) }
        var dialog by remember { mutableStateOf<String?>(null) }
        var hasKey by remember { mutableStateOf(Engine.hasKey(this)) }
        var stats by remember { mutableStateOf(Engine.showStats(this)) }
        val scope = rememberCoroutineScope()
        val snack = pendingSnack
        LaunchedEffect(snack) {
            if (snack != null) {
                pendingSnack = null
                val result = snackbar.showSnackbar(snack.first, actionLabel = snack.second, withDismissAction = snack.second == null)
                if (result == SnackbarResult.ActionPerformed) {
                    when (snack.second) {
                        "Share" -> lastFile?.let { (f, m) -> share(f, m) }
                        "Restart" -> onRestartEngine(null)
                    }
                }
            }
        }
        BackHandler { if (!engineWeb.goBack()) moveTaskToBack(true) }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { testTagsAsResourceId = true }) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("DeepSeek", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("engine-menu").semantics { contentDescription = "More options" }) {
                                Text("⋮", fontSize = 22.sp)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.testTag("engine-menu-list")) {
                                DropdownMenuItem(text = { Text("API key") }, onClick = { menu = false; dialog = "key" }, modifier = Modifier.testTag("menu-api-key"))
                                DropdownMenuItem(text = { Text("Session stats") }, onClick = {
                                    stats = !stats
                                    Engine.setShowStats(this@EngineActivity, stats)
                                    engineWeb.setStats(stats)
                                }, trailingIcon = { Checkbox(checked = stats, onCheckedChange = null) }, modifier = Modifier.testTag("menu-stats"))
                                DropdownMenuItem(text = {
                                    Column {
                                        Text("Harness version")
                                        Text("${Engine.updater(this@EngineActivity).currentVersion()} · Check for updates",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }, onClick = { menu = false; dialog = "update" }, modifier = Modifier.testTag("menu-harness"))
                                DropdownMenuItem(text = { Text("Engine status and log") }, onClick = { menu = false; dialog = "status" }, modifier = Modifier.testTag("menu-status"))
                                DropdownMenuItem(text = { Text("Advanced") }, onClick = { menu = false; dialog = "advanced" }, modifier = Modifier.testTag("menu-advanced"))
                                DropdownMenuItem(text = { Text("About and licences") }, onClick = { menu = false; dialog = "about" }, modifier = Modifier.testTag("menu-about"))
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding()) {
                    AndroidView(factory = { engineWeb.view() }, modifier = Modifier.fillMaxSize().testTag("engine-webview"))
                    if (!hasKey) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                EngineKeyForm(onStarted = { hasKey = true; NodeServer.start(this@EngineActivity) })
                            }
                        }
                    } else if (engineState != NodeServer.State.READY || !pageShown) {
                        StatusCover(onRetry = { NodeServer.start(this@EngineActivity) }, onLog = { dialog = "status" })
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 72.dp))
        }
        when (dialog) {
            "key" -> KeyDialog(onDismiss = { dialog = null }, onChanged = { hasKey = Engine.hasKey(this); onRestartEngine("API key saved") })
            "update" -> UpdateDialog(onDismiss = { dialog = null })
            "status" -> StatusDialog(onDismiss = { dialog = null })
            "advanced" -> AdvancedDialog(onDismiss = { dialog = null })
            "about" -> AboutDialog(onDismiss = { dialog = null })
        }
    }

    /** Opaque cover while the engine prepares, starts or has stopped: never a half-drawn page behind it. */
    @Composable
    private fun StatusCover(onRetry: () -> Unit, onLog: () -> Unit) {
        Surface(Modifier.fillMaxSize().testTag("engine-preparing"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                when (engineState) {
                    NodeServer.State.PREPARING -> {
                        Text("Preparing the engine… $progress%", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("engine-progress"))
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text("One time only: unpacking DeepSeek's harness and its Node runtime.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    NodeServer.State.FAILED -> {
                        Text("The engine is not running", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("engine-error"))
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onRetry) { Text("Try again") }
                            OutlinedButton(onClick = onLog) { Text("View log") }
                        }
                    }
                    else -> {
                        CircularProgressIndicator(Modifier.size(32.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(if (engineState == NodeServer.State.READY) "Opening DeepSeek…" else "Starting DeepSeek's harness…",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    @Composable
    private fun KeyDialog(onDismiss: () -> Unit, onChanged: () -> Unit) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("DeepSeek API key") },
            text = { EngineKeyForm(compact = true, onStarted = { onDismiss(); onChanged() }) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = { runCatching { Engine.clearKey(this) }; onDismiss(); onChanged() }, modifier = Modifier.testTag("key-remove")) { Text("Remove key") }
            })
    }

    @Composable
    private fun UpdateDialog(onDismiss: () -> Unit) {
        var message by remember { mutableStateOf<String?>(null) }
        var updated by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val result = withContext(Dispatchers.IO) { Engine.updateHarness(this@EngineActivity) }
            message = result.message
            updated = result.status == "updated"
        }
        AlertDialog(onDismissRequest = onDismiss, title = { Text("DeepSeek Harness") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("In use: ${Engine.updater(this@EngineActivity).currentVersion()} (bundled ${BuildConfig.DSH_VERSION})")
                    if (message == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Checking npm for a newer @deepseek-ai/dsh…")
                    } else Text(message!!, modifier = Modifier.testTag("update-result"))
                }
            },
            confirmButton = {
                if (updated) TextButton(onClick = { onDismiss(); onRestartEngine("New harness") }) { Text("Restart engine") }
                else TextButton(onClick = onDismiss) { Text("Close") }
            })
    }

    @Composable
    private fun StatusDialog(onDismiss: () -> Unit) {
        val log = remember { EngineLog.tail(this, 12000) }
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Engine status") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("State: ${engineState.name.lowercase()} · page ${NodeServer.pageBytes()} bytes", modifier = Modifier.testTag("status-state"))
                    Text("Harness ${Engine.updater(this@EngineActivity).currentVersion()} · app ${BuildConfig.VERSION_NAME} · ${Engine.nodeArch()}")
                    HorizontalDivider()
                    Text(log.ifEmpty { "No log yet." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Engine log", log))
                        Toast.makeText(this@EngineActivity, "Log copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                    TextButton(onClick = { onDismiss(); onRestartEngine(null) }) { Text("Restart") }
                }
            })
    }

    @Composable
    private fun AdvancedDialog(onDismiss: () -> Unit) {
        var auto by remember { mutableStateOf(Engine.autoUpdate(this)) }
        var fallback by remember { mutableStateOf(app.dsandroid.flavor.EngineEntry.builtInAgentEnabled(this)) }
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Advanced") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow("Update the harness automatically", "Checks npm once a day and installs a newer @deepseek-ai/dsh after verifying it.", auto) {
                        auto = it; Engine.setAutoUpdate(this@EngineActivity, it)
                    }
                    SwitchRow("Built-in engine (fallback)", "This app's own API-key agent from earlier versions. Off by default; DeepSeek's harness is the engine.", fallback,
                        Modifier.testTag("advanced-fallback")) {
                        fallback = it; app.dsandroid.flavor.EngineEntry.setBuiltInAgentEnabled(this@EngineActivity, it)
                    }
                    if (fallback) OutlinedButton(onClick = {
                        onDismiss()
                        startActivity(Intent(this@EngineActivity, MainActivity::class.java).putExtra(app.dsandroid.flavor.EngineEntry.EXTRA_BUILT_IN, true))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open the built-in engine") }
                    OutlinedButton(onClick = {
                        onDismiss()
                        Engine.setRoute(this@EngineActivity, Engine.ROUTE_GOOGLE)
                        startActivity(Intent(this@EngineActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }, modifier = Modifier.fillMaxWidth().testTag("advanced-google")) { Text("Use Google sign-in instead") }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    }

    @Composable
    private fun SwitchRow(title: String, body: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun AboutDialog(onDismiss: () -> Unit) {
        var licences by remember { mutableStateOf(false) }
        val text = remember(licences) {
            if (!licences) null else runCatching { assets.open("engine/licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
        AlertDialog(onDismissRequest = onDismiss, title = { Text(if (licences) "Licences" else "About") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("about-text"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (text != null) Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    else {
                        Text("DeepSeek Android ${BuildConfig.VERSION_NAME} (full)", fontWeight = FontWeight.SemiBold)
                        Text("Unofficial app. The engine is DeepSeek's own agent harness, @deepseek-ai/dsh ${Engine.updater(this@EngineActivity).currentVersion()} " +
                            "(bundled ${BuildConfig.DSH_VERSION}, MIT), running on this phone. Your API key stays on the phone; the harness talks to api.deepseek.com.")
                        Text("Built on dsh-android by yhm080315-sudo (MIT, commit ${BuildConfig.DSH_ANDROID_COMMIT.take(7)}): runtime packaging, Android patches " +
                            "and the WebView host. Phone layout: dsh-web-mobile ${BuildConfig.DSH_WEB_MOBILE_VERSION} by mexiaosh (MIT).")
                        Text("Runtime: Node.js, bash, GNU coreutils and tools, ripgrep and git from Termux packages (MIT, GPL-3.0 and others; " +
                            "sources at github.com/termux/termux-packages).", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { licences = true }, modifier = Modifier.testTag("about-licences")) { Text("Licences") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    }

    companion object {
        fun open(context: android.content.Context) {
            context.startActivity(Intent(context, EngineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
