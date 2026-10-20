package app.dsandroid.deepseek

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import app.dsandroid.ui.ChatViewModel
import app.dsandroid.core.llm.LlmClient
import app.dsandroid.attachments.SharedInput
import app.dsandroid.core.launch.QuickAction
import app.dsandroid.data.Appearance
import app.dsandroid.launch.LaunchRequest
import app.dsandroid.ui.ArtifactViewer
import app.dsandroid.ui.DsAndroidApp
import app.dsandroid.core.tools.Artifact
import app.dsandroid.core.accounts.AccountType
import app.dsandroid.core.deepseek.PowChallenge
import app.dsandroid.core.editor.EditorLanguage
import app.dsandroid.workbench.ArtifactPanel
import app.dsandroid.workbench.CreateTextDocument
import app.dsandroid.workbench.EditorScreen
import app.dsandroid.workbench.TextFiles
import app.dsandroid.workbench.WorkbenchPane
import app.dsandroid.workbench.runnable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BIRTHDAY_NOTICE = "Confirm your date of birth on the DeepSeek page to finish signing in."

@Composable
fun DeepSeekAccountApp(vm: AccountViewModel = viewModel(), sharedInput: SharedInput? = null, sharedConsumed: () -> Unit = {},
    launchRequest: LaunchRequest? = null, launchConsumed: () -> Unit = {},
    loginBrowser: DeepSeekWebView? = null, speechInput: AccountSpeechInput? = null, apiClient: LlmClient? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browser = remember(context, loginBrowser) { loginBrowser ?: DeepSeekWebView(context, notice = vm::notice) }
    DisposableEffect(browser) { onDispose { browser.destroy() } }
    var apiFallback by rememberSaveable { mutableStateOf(false) }
    var cameraName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSend by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var pendingResend by remember { mutableStateOf<(() -> Unit)?>(null) }
    var voiceTrigger by remember { mutableIntStateOf(0) }
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
    var viewing by remember { mutableStateOf<Artifact?>(null) }
    var pendingSave by remember { mutableStateOf<Artifact?>(null) }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val artifact = pendingSave
        pendingSave = null
        val file = artifact?.let(vm::artifactFile)
        if (target != null && artifact != null && file != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { ArtifactActions.copyTo(context, file, target) }
                Toast.makeText(context, "Saved ${artifact.fileName}", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { vm.notice("The file could not be saved.") }
        }
    }
    val skillFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::installSkillFromUri) }
    // Save as for the editor and the artifacts panel: the system file picker (ACTION_CREATE_DOCUMENT), no storage permission.
    var pendingText by remember { mutableStateOf<String?>(null) }
    val saveText = rememberLauncherForActivityResult(CreateTextDocument()) { target ->
        val text = pendingText
        pendingText = null
        if (target != null && text != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { TextFiles.writeTo(context, target, text) }
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { vm.notice("The file could not be saved.") }
        }
    }
    val saveAs: (String, String) -> Unit = { name, text ->
        pendingText = text
        try { saveText.launch(CreateTextDocument.Request(name, TextFiles.mimeType(name))) }
        catch (_: Exception) { pendingText = null; vm.notice("No file picker is available.") }
    }
    val shareText: (String, String) -> Unit = { name, text ->
        try { if (!TextFiles.share(context, name, text)) vm.notice("This text is too long to share directly. Use Save as, then share the file.") }
        catch (_: Exception) { vm.notice("No app is available to share this text.") }
    }
    val agentActions = remember(vm, context) {
        object : AgentActions {
            override fun setAgent(enabled: Boolean) = vm.setAgent(enabled)
            override fun runCode(messageId: String, code: String) = vm.runCode(messageId, code)
            override fun exportPdf(messageId: String) = vm.exportPdf(messageId)
            override fun artifactFile(artifact: Artifact) = vm.artifactFile(artifact)
            override fun openArtifact(artifact: Artifact) {
                when {
                    vm.artifactFile(artifact) == null -> vm.notice("This file is no longer available.")
                    vm.openArtifactFile(artifact) -> Unit // HTML, SVG and Markdown open in the artifacts panel.
                    else -> viewing = artifact
                }
            }
            override fun saveArtifact(artifact: Artifact) {
                val file = vm.artifactFile(artifact) ?: return vm.notice("This file is no longer available.")
                scope.launch {
                    try {
                        if (withContext(Dispatchers.IO) { ArtifactActions.saveToDownloads(context, artifact, file) }) {
                            Toast.makeText(context, "Saved to Downloads/DeepSeek", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingSave = artifact
                            saveDocument.launch(artifact.fileName)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { vm.notice("The file could not be saved.") }
                }
            }
            override fun shareArtifact(artifact: Artifact) {
                val file = vm.artifactFile(artifact) ?: return vm.notice("This file is no longer available.")
                try { ArtifactActions.share(context, artifact, file) } catch (_: Exception) { vm.notice("No app is available to share this file.") }
            }
            override fun answerTool(allow: Boolean) = vm.answerTool(allow)
            override fun installSkillFromUrl(url: String) = vm.installSkillFromUrl(url)
            override fun installSkillFromFile() {
                try { skillFile.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                catch (_: Exception) { vm.notice("No file picker is available.") }
            }
            override fun answerSkillInstall(allow: Boolean) = vm.answerSkillInstall(allow)
            override fun setSkillEnabled(name: String, enabled: Boolean) = vm.setSkillEnabled(name, enabled)
            override fun removeSkill(name: String) = vm.removeSkill(name)
            override fun installPackages(specs: String) = vm.installPackages(specs)
            override fun answerPackageInstall(allow: Boolean) = vm.answerPackageInstall(allow)
            override fun removePackage(name: String) = vm.removePackage(name)
            override fun previewCode(messageId: String, revisionOf: String?, language: String, code: String) = vm.previewCode(messageId, revisionOf, language, code)
            override fun editCode(language: String, code: String) = vm.editCode(language, code)
            override fun editArtifact(artifact: Artifact) = vm.editArtifactFile(artifact)
            override fun openChatFiles() = vm.showChatFiles()
            override fun hideChatFiles() = vm.hideChatFiles()
            override fun openChatFile(row: ChatFileRow) {
                vm.hideChatFiles()
                val artifact = vm.chatFileArtifact(row)
                when {
                    vm.openArtifactFile(artifact) -> Unit
                    EditorLanguage.forFileName(row.name) != null -> vm.editArtifactFile(artifact)
                    else -> openArtifact(artifact)
                }
            }
        }
    }
    val accountActions = remember(vm, browser) {
        object : AccountActions {
            override fun switchTo(id: String) {
                // The sign-in page must not stay open on the previous account.
                browser.stopForLogout()
                vm.switchAccount(id)
            }
            override fun addDeepSeekAccount() = vm.startAddAccount { browser.stopForLogout() }
            override fun addApiKeyProfile(label: String, key: String) = vm.addApiKeyProfile(label, key)
            override fun remove(id: String) {
                if (vm.state.value.accounts.any { it.id == id && it.active && it.type == AccountType.DEEPSEEK_WEB }) browser.stopForLogout()
                vm.removeAccount(id)
            }
            override fun rename(id: String, label: String) = vm.renameAccount(id, label)
            override fun refreshBalances(id: String?) = vm.refreshBalances(id)
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
    // The sign-in page always shows the account shell (also when opened from API-key mode), so it is polled whenever it is open.
    LaunchedEffect(state.page) {
        if (state.page == AccountPage.LOGIN) {
            browser.openLogin()
            var birthdayChecked = 0L
            while (true) {
                delay(1000)
                val token = browser.captureSession() ?: continue
                // Keep the sign-in page open until a new account has answered DeepSeek's date-of-birth prompt.
                if (System.currentTimeMillis() - birthdayChecked < 3000) continue
                birthdayChecked = System.currentTimeMillis()
                if (vm.needsBirthday(token)) { vm.notice(BIRTHDAY_NOTICE); continue }
                if (vm.acceptSession(token)) break
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
                QuickAction.VOICE -> voiceTrigger++
                QuickAction.NEW_CHAT -> Unit
            }
            launchConsumed()
        }
    }
    // The workbench pane keeps its editor state when it moves between the side pane and the full-screen overlay.
    val workbenchPane = remember {
        movableContentOf { pane: WorkbenchPane, ui: AccountUiState -> WorkbenchPaneHost(pane, ui, vm, browser::solve, saveAs, shareText) }
    }
    AccountTheme(state.appearance) {
        // An active API-key profile chats through the API-key client; Settings > Accounts and sign-in still use the account shell.
        val apiProfile = state.accounts.firstOrNull { it.active && it.type == AccountType.API_KEY }
        val shellPage = state.page == AccountPage.ACCOUNTS || state.page == AccountPage.LOGIN
        val apiMode = (apiFallback || apiProfile != null) && !shellPage
        val pane = state.pane.takeIf { !apiMode }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Side by side on wide windows (840 dp and up); full screen over the chat on phones.
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (apiMode) key(apiProfile?.id ?: "fallback") {
                        // A fresh API ViewModel reads the latest encrypted key on each explicit entry; chats are kept per API-key profile.
                        val apiOwner = remember { ViewModelStore() }
                        val historyDir = remember { apiHistoryDir(apiProfile?.id ?: state.apiKeyProfile) }
                        val apiVm = remember {
                            ViewModelProvider(apiOwner, viewModelFactory { initializer { ChatViewModel(context.applicationContext as Application, apiClient, historyDir) } })["api", ChatViewModel::class.java]
                        }
                        DisposableEffect(apiOwner) { onDispose { apiOwner.clear() } }
                        var returning by remember { mutableStateOf(false) }
                        fun leaveApi(then: () -> Unit) {
                            if (returning) return
                            returning = true
                            scope.launch {
                                // Complete any non-cancellable settings write before account mode can change the key.
                                apiVm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                                apiOwner.clear()
                                vm.refreshApiKey()
                                then()
                            }
                        }
                        BackHandler(enabled = apiProfile == null) { leaveApi { apiFallback = false } }
                        Column(Modifier.fillMaxSize()) {
                            Surface {
                                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (apiProfile == null) {
                                        Text("API-key fallback", Modifier.padding(vertical = 16.dp))
                                        TextButton(onClick = { leaveApi { apiFallback = false } }, enabled = !returning) { Text("Back to DeepSeek") }
                                    } else {
                                        Text("API key \u00B7 ${apiProfile.label}", Modifier.weight(1f).padding(vertical = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        TextButton(onClick = { leaveApi { vm.navigate(AccountPage.ACCOUNTS) } }, enabled = !returning,
                                            modifier = Modifier.testTag("api-switch-account")) { Text("Switch account") }
                                    }
                                }
                            }
                            Box(Modifier.weight(1f).testTag("api-fallback-ui")) {
                                DsAndroidApp(vm = apiVm, startDestination = if (state.hasApiKey) "chat" else "settings",
                                    onSwitchAccount = { leaveApi { vm.navigate(AccountPage.ACCOUNTS) } })
                            }
                        }
                    } else DeepSeekShell(state,
                        onLogin = { if (state.page == AccountPage.LOGIN) browser.openLogin() else vm.navigate(AccountPage.LOGIN) },
                        onFinishLogin = { scope.launch {
                            val token = browser.captureSession()
                            if (token == null || !vm.acceptSession(token)) vm.notice("A supported DeepSeek session was not found. Complete login on chat.deepseek.com; if Google blocks this browser, choose another sign-in method.")
                        } },
                        onApiFallback = { apiFallback = true }, onApiKey = vm::saveApiKey, onNavigate = vm::navigate, onNew = vm::newChat, onOpen = vm::open,
                        onDraft = vm::draft, onStop = vm::stop, onTheme = vm::appearance, onError = vm::notice,
                        onRename = vm::rename, onDelete = vm::delete, speechInput = speechInput, voiceTrigger = voiceTrigger, onVoiceHandled = { voiceTrigger = 0 },
                        onRegenerate = {
                            if (vm.messageNeedsConfirmation()) pendingResend = { vm.regenerate(browser::solve) }
                            else vm.regenerate(browser::solve)
                        },
                        onEdit = { id, text ->
                            if (vm.messageNeedsConfirmation(id)) pendingResend = { vm.edit(id, text, browser::solve) }
                            else vm.edit(id, text, browser::solve)
                        },
                        onLogout = { browser.stopForLogout(); vm.logout() }, onAttachment = ::pick, onRemoveAttachment = vm::removeAttachment,
                        onSend = { think, search ->
                            if (!state.signedIn) vm.navigate(AccountPage.LOGIN)
                            else if (search || state.attachments.isNotEmpty()) pendingSend = think to search
                            else vm.send(think, search, browser::solve)
                        },
                        loginContent = { AndroidView(factory = { browser.view }, modifier = Modifier.fillMaxSize().testTag("login-webview")) },
                        actions = agentActions, accountActions = accountActions,
                    )
                }
                if (wide && pane != null) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f).fillMaxHeight()) { workbenchPane(pane, state) }
                }
            }
            // Blocks touches to the chat underneath the full-screen pane.
            if (!wide && pane != null) Box(Modifier.fillMaxSize().pointerInput(Unit) {}) { workbenchPane(pane, state) }
        }
        viewing?.let { artifact ->
            Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize()) {
                    ArtifactViewer(artifact, vm.agentWorkspace, back = { viewing = null }, share = agentActions::shareArtifact, save = agentActions::saveArtifact,
                        openExternal = { item ->
                            val file = vm.artifactFile(item)
                            if (file != null) try { ArtifactActions.openExternal(context, item, file) } catch (_: Exception) { vm.notice("No app can open this file.") }
                        })
                }
            }
        }
        pendingResend?.let { resend ->
            AlertDialog(onDismissRequest = { pendingResend = null }, title = { Text("Resend to DeepSeek?") },
                text = { Text("This message reuses its Search setting and any previously uploaded files. Its reply and later turns will be replaced.") },
                confirmButton = { TextButton(onClick = { pendingResend = null; resend() }) { Text("Resend") } },
                dismissButton = { TextButton(onClick = { pendingResend = null }) { Text("Cancel") } })
        }
        pendingSend?.let { (think, search) ->
            AlertDialog(onDismissRequest = { pendingSend = null }, title = { Text("Send to DeepSeek?") },
                text = { Text(buildString {
                    append(state.draft.take(500))
                    if (search) append("\n\nDeepSeek Search will be enabled for this message.")
                    if (state.attachments.isNotEmpty()) append("\n\nUpload to DeepSeek: ${state.attachments.joinToString()}.")
                }) },
                confirmButton = { TextButton(onClick = { pendingSend = null; vm.send(think, search, browser::solve) }) { Text("Send") } },
                dismissButton = { TextButton(onClick = { pendingSend = null }) { Text("Cancel") } })
        }
    }
}

/** The API-key client keeps chats per API-key profile; the Settings key and older installs use the original folder. */
internal fun apiHistoryDir(profileId: String?): String =
    if (profileId == null || profileId == AccountViewModel.QUICK_KEY_ID) "conversations" else "conversations-$profileId"

/** The artifacts panel or the editor, with the latest notice shown inside the pane (the chat's banner may be covered). */
@Composable
private fun WorkbenchPaneHost(pane: WorkbenchPane, state: AccountUiState, vm: AccountViewModel, solve: suspend (PowChallenge) -> Long,
    saveAs: (String, String) -> Unit, share: (String, String) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        when (pane) {
            is WorkbenchPane.Panel -> ArtifactPanel(pane, state.textScale, chatBusy = state.busy, draft = vm::draftText, saveDraft = vm::saveDraft,
                onClose = vm::closePane, onRun = { source -> vm.runArtifact(pane.artifact.id, source) },
                onAsk = { base, source, instruction -> vm.askArtifactChange(pane.artifact.id, base, source, instruction, solve) },
                onSaveAs = saveAs, onShare = share)
            is WorkbenchPane.Editor -> EditorScreen(pane.file, state.textScale, state.editorOutput, state.editorBusy, draft = vm::draftText,
                saveDraft = vm::saveDraft, onClose = vm::closePane, onSave = vm::saveEditor, onSaveAs = saveAs, onShare = share,
                onRun = if (pane.file.runnable()) vm::runEditorPython else null)
        }
        state.error?.let { message ->
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp).testTag("pane-notice"),
                color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    // Like the chat banner: a quota or rate error only offers the account list, it never switches.
                    if (state.offerSwitchAccount) TextButton(onClick = { vm.closePane(); vm.navigate(AccountPage.ACCOUNTS) }) { Text("Switch account") }
                    TextButton(onClick = vm::dismissNotice) { Text("Dismiss") }
                }
            }
        }
    }
}
