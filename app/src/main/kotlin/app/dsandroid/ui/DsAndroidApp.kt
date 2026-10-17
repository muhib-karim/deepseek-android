package app.dsandroid.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.dsandroid.core.tools.Artifact
import app.dsandroid.data.AppSettings
import app.dsandroid.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsAndroidApp(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navigation = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = pendingSave
        pendingSave = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && path != null && uri?.scheme == "content") {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val file = vm.workspace.resolve(path)
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                            ?: error("No output stream")
                    }
                } catch (_: Exception) { vm.showError("Could not save the artifact to the chosen document.") }
            }
        }
    }
    fun openOrShare(artifact: Artifact, share: Boolean) {
        try {
            val file = vm.workspace.resolve(artifact.path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.artifacts", file)
            val intent = if (share) Intent(Intent.ACTION_SEND).apply { type = artifact.mimeType; putExtra(Intent.EXTRA_STREAM, uri) }
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, artifact.mimeType)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(artifact.fileName, uri)
            context.startActivity(Intent.createChooser(intent, if (share) "Share artifact" else "Open artifact"))
        } catch (_: Exception) { vm.showError("No compatible app could open this artifact. Try Save as…") }
    }
    fun saveAs(artifact: Artifact) {
        try {
            pendingSave = artifact.path
            saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = artifact.mimeType
                putExtra(Intent.EXTRA_TITLE, artifact.fileName)
            })
        } catch (_: Exception) { pendingSave = null; vm.showError("The document picker is unavailable.") }
    }

    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF9CD4BD), secondary = Color(0xFFADCBD0))
    else lightColorScheme(primary = Color(0xFF356859), secondary = Color(0xFF46666D))
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navigation, startDestination = "chat") {
                composable("chat") {
                    Scaffold(topBar = {
                        TopAppBar(title = { Text("DeepSeek Android") }, actions = {
                            TextButton(onClick = vm::clearConversation, enabled = !state.running) { Text("New chat") }
                            TextButton(onClick = { navigation.navigate("settings") }, enabled = !state.running && !state.signingIn) { Text("Settings") }
                        })
                    }) { padding ->
                        ChatScreen(state, settings.model, Modifier.padding(padding), vm::send, vm::stop,
                            { openOrShare(it, false) }, { openOrShare(it, true) }, ::saveAs)
                    }
                }
                composable("settings") {
                    Scaffold(topBar = {
                        TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = { navigation.popBackStack() }) { Text("Back") } })
                    }) { padding ->
                        if (state.settingsReady) SettingsScreen(settings, state.saving, Modifier.padding(padding),
                            { value -> vm.saveSettings(value) { navigation.popBackStack() } },
                            { value -> vm.saveSettings(value) { navigation.navigate("sign-in") } }, vm::signOut)
                        else Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Encrypted settings are loading…") }
                    }
                }
                composable("sign-in") {
                    BackHandler { vm.cancelSignIn(); navigation.popBackStack() }
                    Scaffold(topBar = {
                        TopAppBar(title = { Text("Account sign-in") }, navigationIcon = { TextButton(onClick = { vm.cancelSignIn(); navigation.popBackStack() }) { Text("Back") } })
                    }) { padding ->
                        SignInScreen(settings, state, Modifier.padding(padding), {
                            vm.signIn { url -> CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
                        }, vm::cancelSignIn)
                    }
                }
            }
        }
        state.confirmation?.let { confirmation ->
            AlertDialog(onDismissRequest = { vm.answerConfirmation(false) }, title = { Text("Allow this tool once?") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(confirmation.name, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        SelectionContainer { Text(confirmation.arguments.toString(), fontFamily = FontFamily.Monospace) }
                    }
                }, confirmButton = { TextButton(onClick = { vm.answerConfirmation(true) }) { Text("Allow once") } },
                dismissButton = { TextButton(onClick = { vm.answerConfirmation(false) }) { Text("Deny") } })
        }
        state.error?.let { message ->
            AlertDialog(onDismissRequest = vm::dismissError, title = { Text("DeepSeek Android") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } })
        }
    }
}

@Composable
private fun ChatScreen(
    state: ChatUiState, model: String, modifier: Modifier, send: (String) -> Boolean, stop: () -> Unit,
    open: (Artifact) -> Unit, share: (Artifact) -> Unit, save: (Artifact) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lastMessage = state.items.lastOrNull() as? ChatItem.Message
    LaunchedEffect(state.items.size, lastMessage?.text?.length, lastMessage?.reasoning?.length) {
        if (state.items.isNotEmpty() && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.items.lastIndex - 2) {
            listState.scrollToItem(state.items.lastIndex)
        }
    }
    Column(modifier.fillMaxSize().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(model, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        LazyColumn(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.items.isEmpty()) item {
                Column(Modifier.padding(vertical = 32.dp)) {
                    Text("What would you like to make?", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text("Ask for a PDF, a CSV table, an SVG drawing, or a Markdown note. Configure your provider in Settings to begin.")
                    Spacer(Modifier.height(12.dp))
                    Text("Conversations stay in memory for now. Save or share artifacts to keep a copy.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.items, key = { it.id }) { item ->
                when (item) {
                    is ChatItem.Message -> Card(colors = CardDefaults.cardColors(containerColor = if (item.user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(if (item.user) "You" else "DeepSeek Android", style = MaterialTheme.typography.labelMedium)
                            if (item.reasoning.isNotEmpty()) {
                                var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
                                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide reasoning" else "Show reasoning") }
                                if (expanded) SelectionContainer { Text(item.reasoning, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (item.text.isNotEmpty()) MarkdownText(item.text)
                        }
                    }
                    is ChatItem.ToolStatus -> SuggestionChip(onClick = {}, enabled = false,
                        label = { Text("${item.name}: ${item.status}") })
                    is ChatItem.ArtifactItem -> ArtifactCard(item.artifact, open, share, save)
                }
            }
        }
        if (state.running) LinearProgressIndicator(Modifier.widthIn(max = 840.dp).fillMaxWidth())
        Row(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                label = { Text("Message") }, maxLines = 5, enabled = state.settingsReady && !state.running)
            if (state.running) Button(onClick = stop) { Text("Stop") }
            else Button(onClick = { if (send(draft)) draft = "" }, enabled = draft.isNotBlank() && state.settingsReady) { Text("Send") }
        }
    }
}

@Composable
private fun MarkdownText(text: String) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkdownBlocks.parse(text).forEach { block ->
                when (block) {
                    is MarkdownBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            if (block.language.isNotBlank()) Text(block.language, style = MaterialTheme.typography.labelSmall)
                            Text(block.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is MarkdownBlock.Paragraph -> Text(buildAnnotatedString {
                        MarkdownBlocks.inline(block.text).forEach { part ->
                            withStyle(SpanStyle(fontWeight = if (part.bold) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = if (part.code) FontFamily.Monospace else FontFamily.Default)) { append(part.text) }
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun ArtifactCard(artifact: Artifact, open: (Artifact) -> Unit, share: (Artifact) -> Unit, save: (Artifact) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(artifact.fileName, style = MaterialTheme.typography.titleMedium)
            Text(artifact.mimeType, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { open(artifact) }) { Text("Open") }
                TextButton(onClick = { share(artifact) }) { Text("Share") }
                TextButton(onClick = { save(artifact) }) { Text("Save as…") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: AppSettings, saving: Boolean, modifier: Modifier,
    save: (AppSettings) -> Unit, signIn: (AppSettings) -> Unit, signOut: () -> Unit) {
    // Deliberately not rememberSaveable: secrets must never enter saved-instance-state bundles.
    var draft by remember(settings) { mutableStateOf(settings) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Provider", style = MaterialTheme.typography.titleMedium)
        Provider.entries.forEach { provider ->
            FilterChip(selected = draft.provider == provider, onClick = { draft = draft.copy(provider = provider) }, label = { Text(provider.label) })
        }
        SettingField("Base URL", draft.baseUrl) { draft = draft.copy(baseUrl = it) }
        Text("Custom providers may use HTTP on a trusted local network. HTTP exposes prompts and credentials to that network.", style = MaterialTheme.typography.bodySmall)
        SettingField("Model", draft.model) { draft = draft.copy(model = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { draft = draft.copy(model = "deepseek-chat") }) { Text("deepseek-chat") }
            TextButton(onClick = { draft = draft.copy(model = "deepseek-reasoner") }) { Text("deepseek-reasoner") }
        }
        when (draft.provider) {
            Provider.API_KEY -> SettingField("API key", draft.apiKey, secret = true) { draft = draft.copy(apiKey = it) }
            Provider.CUSTOM -> SettingField("Custom endpoint key (optional)", draft.customKey, secret = true) { draft = draft.copy(customKey = it) }
            Provider.ACCOUNT -> {
                Text(if (settings.accountToken.isNotEmpty()) "An account grant is saved on this device." else "No account grant is saved.")
                if (!draft.accountConfigured) Text("Account sign-in endpoint not configured; use an API key")
                Button(onClick = { signIn(draft) }, enabled = !saving) { Text("Save and open sign-in") }
                if (settings.accountToken.isNotEmpty()) TextButton(onClick = signOut, enabled = !saving) { Text("Remove local account grant") }
            }
        }
        HorizontalDivider()
        Text("Advanced", style = MaterialTheme.typography.titleLarge)
        Text("Account integration must be supported by the provider. No authorize URL, exchange URL or client ID is assumed.", style = MaterialTheme.typography.bodySmall)
        SettingField("Authorize URL (HTTPS)", draft.authorizeUrl) { draft = draft.copy(authorizeUrl = it) }
        SettingField("Token exchange URL (HTTPS)", draft.exchangeUrl) { draft = draft.copy(exchangeUrl = it) }
        SettingField("OAuth client ID", draft.clientId) { draft = draft.copy(clientId = it) }
        SettingToggle("Enable shell tools", draft.shellEnabled) { draft = draft.copy(shellEnabled = it) }
        SettingToggle("Enable install tools", draft.installEnabled) { draft = draft.copy(installEnabled = it) }
        Text("Warning: shell and install actions can change your device. Each action always requires confirmation. These tools are not registered in v0.1.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { save(draft) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Save settings") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingField(label: String, value: String, secret: Boolean = false, update: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = update, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = update)
    }
}

@Composable
private fun SignInScreen(settings: AppSettings, state: ChatUiState, modifier: Modifier, signIn: () -> Unit, cancel: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Sign in in your browser", style = MaterialTheme.typography.headlineSmall)
        Text("Your password stays on the provider’s page. DeepSeek Android receives an account grant through a local callback and stores it with Android Keystore-backed encryption.")
        if (!settings.accountConfigured) Text("Account sign-in endpoint not configured; use an API key", color = MaterialTheme.colorScheme.error)
        Text("Third-party sign-in may be unavailable or refused. Account inference may use your Platform balance or bonus; it is not a promise of unlimited free chat.")
        state.signInMessage?.let { Text(it) }
        if (state.signingIn) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = cancel) { Text("Cancel sign-in") }
        } else Button(onClick = signIn, enabled = settings.accountConfigured) { Text("Continue in browser") }
    }
}
