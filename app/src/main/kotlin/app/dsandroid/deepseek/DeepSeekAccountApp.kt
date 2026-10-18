package app.dsandroid.deepseek

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dsandroid.attachments.SharedInput
import app.dsandroid.core.launch.QuickAction
import app.dsandroid.data.Appearance
import app.dsandroid.launch.LaunchRequest
import app.dsandroid.ui.DsAndroidApp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeepSeekAccountApp(vm: AccountViewModel = viewModel(), sharedInput: SharedInput? = null, sharedConsumed: () -> Unit = {},
    launchRequest: LaunchRequest? = null, launchConsumed: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browser = remember(context) { DeepSeekWebView(context, vm::notice) }
    DisposableEffect(browser) { onDispose { browser.destroy() } }
    var apiFallback by rememberSaveable { mutableStateOf(false) }
    var cameraName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSend by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    val dark = state.appearance == Appearance.DARK || (state.appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    DisposableEffect(context, dark) {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
    fun cameraFile(name: String) = File(File(context.cacheDir, "camera"), name)
    fun cameraUri(name: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.artifacts", cameraFile(name))
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val name = cameraName; cameraName = null
        if (name != null) {
            if (success) vm.importAttachment(cameraUri(name)) else cameraFile(name).delete()
        }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::importAttachment) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importAttachment) }
    fun pick(kind: String) {
        try {
            when (kind) {
                "Camera" -> {
                    val name = "${UUID.randomUUID()}.jpg"
                    val file = cameraFile(name)
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    check(file.createNewFile()); cameraName = name
                    camera.launch(cameraUri(name))
                }
                "Photo" -> photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                "Document" -> document.launch(arrayOf("*/*"))
            }
        } catch (_: Exception) {
            cameraName?.let { cameraFile(it).delete() }; cameraName = null
            vm.notice("No system app is available for this attachment action.")
        }
    }
    LaunchedEffect(state.page, apiFallback) {
        if (state.page == AccountPage.LOGIN && !apiFallback) {
            browser.openLogin()
            while (true) {
                delay(1000)
                val token = browser.captureSession()
                if (token != null && vm.acceptSession(token)) break
            }
        }
    }
    LaunchedEffect(sharedInput, state.ready, state.busy, state.importing) {
        if (sharedInput != null && state.ready && !state.busy && !state.importing) {
            vm.newChat(); vm.draft(sharedInput.text)
            vm.importShared(sharedInput.uris)
            if (sharedInput.limited) vm.notice("Some shared content was too large or unsupported and was not imported.")
            sharedConsumed()
        }
    }
    LaunchedEffect(launchRequest, state.ready, state.busy, state.importing) {
        if (launchRequest != null && state.ready && !state.busy && !state.importing) {
            vm.newChat()
            when (launchRequest.action) {
                QuickAction.CAMERA -> pick("Camera")
                QuickAction.VOICE -> vm.notice("Hold the microphone button to dictate. Speech is added to your draft before you send it.")
                QuickAction.NEW_CHAT -> Unit
            }
            launchConsumed()
        }
    }
    AccountTheme(state.appearance) {
        if (apiFallback) {
            BackHandler { apiFallback = false }
            Column(Modifier.fillMaxSize()) {
                Surface {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("API-key fallback", Modifier.padding(vertical = 16.dp))
                        TextButton(onClick = { apiFallback = false }) { Text("Back to DeepSeek") }
                    }
                }
                Box(Modifier.weight(1f)) { DsAndroidApp(startDestination = "settings") }
            }
        } else DeepSeekShell(state,
            onLogin = { if (state.page == AccountPage.LOGIN) browser.openLogin() else vm.navigate(AccountPage.LOGIN) },
            onFinishLogin = { scope.launch {
                val token = browser.captureSession()
                if (token == null || !vm.acceptSession(token)) vm.notice("A supported DeepSeek session was not found. Complete login on chat.deepseek.com; if Google blocks this browser, choose another sign-in method.")
            } },
            onApiFallback = { apiFallback = true }, onNavigate = vm::navigate, onNew = vm::newChat, onOpen = vm::open,
            onDraft = vm::draft, onStop = vm::stop, onTheme = vm::appearance, onError = vm::notice,
            onLogout = { browser.stopForLogout(); vm.logout() }, onAttachment = ::pick, onRemoveAttachment = vm::removeAttachment,
            onSend = { think, search ->
                if (!state.signedIn) vm.navigate(AccountPage.LOGIN)
                else if (search || state.attachments.isNotEmpty()) pendingSend = think to search
                else vm.send(think, search, browser::solve)
            },
            loginContent = { AndroidView(factory = { browser.view }, modifier = Modifier.fillMaxSize()) },
        )
        pendingSend?.let { (think, search) ->
            AlertDialog(onDismissRequest = { pendingSend = null }, title = { Text("Send to DeepSeek?") },
                text = { Text(buildString {
                    append(state.draft.take(500))
                    if (search) append("\n\nDeepSeek Search will be enabled for this message.")
                    if (state.attachments.isNotEmpty()) append("\n\nExtracted text from: ${state.attachments.joinToString()}. Original files are not uploaded.")
                }) },
                confirmButton = { TextButton(onClick = { pendingSend = null; vm.send(think, search, browser::solve) }) { Text("Send") } },
                dismissButton = { TextButton(onClick = { pendingSend = null }) { Text("Cancel") } })
        }
    }
}
