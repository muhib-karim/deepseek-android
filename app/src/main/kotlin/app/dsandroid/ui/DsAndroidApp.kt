package app.dsandroid.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.dsandroid.R
import app.dsandroid.core.llm.ModelInfo
import app.dsandroid.core.llm.ModelSelector
import app.dsandroid.core.tools.Artifact
import app.dsandroid.data.AppSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsAndroidApp(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navigation = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerPath by rememberSaveable { mutableStateOf("") }
    var viewerName by rememberSaveable { mutableStateOf("") }
    var viewerMime by rememberSaveable { mutableStateOf("") }
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
    fun share(artifact: Artifact) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.artifacts", vm.workspace.resolve(artifact.path))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = artifact.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(artifact.fileName, uri)
            }
            context.startActivity(Intent.createChooser(intent, "Share artifact"))
        } catch (_: Exception) { vm.showError("No compatible app could share this artifact. Try Save.") }
    }
    fun save(artifact: Artifact) {
        try {
            pendingSave = artifact.path
            saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = artifact.mimeType
                putExtra(Intent.EXTRA_TITLE, artifact.fileName)
            })
        } catch (_: Exception) { pendingSave = null; vm.showError("The document picker is unavailable.") }
    }
    ChatTheme {
        Surface(Modifier.fillMaxSize()) {
            Column {
                NavHost(navController = navigation, startDestination = "chat", modifier = Modifier.weight(1f)) {
                    composable("chat") {
                        val drawer = rememberDrawerState(DrawerValue.Closed)
                        BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
                        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                            ConversationDrawer(state, onNew = { vm.newConversation(); scope.launch { drawer.close() } },
                                onOpen = { vm.openConversation(it); scope.launch { drawer.close() } },
                                onSettings = { scope.launch { drawer.close() }; navigation.navigate("settings") },
                                onAbout = { scope.launch { drawer.close() }; navigation.navigate("about") })
                        }) {
                            Scaffold(topBar = {
                                CenterAlignedTopAppBar(title = { ModelTitle(state, settings, vm::selectModel, vm::refreshModels) }, navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawer.open() } }) { AppIcon(Glyph.MENU, "Open navigation") }
                                }, actions = {
                                    IconButton(onClick = vm::newConversation, enabled = !state.running && !state.importing,
                                        modifier = Modifier.padding(end = 8.dp).size(40.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
                                        AppIcon(Glyph.PLUS, "New chat")
                                    }
                                })
                            }) { padding ->
                                ChatScreen(state, settings, Modifier.padding(padding), vm) { artifact ->
                                    viewerPath = artifact.path; viewerName = artifact.fileName; viewerMime = artifact.mimeType
                                    navigation.navigate("viewer")
                                }
                            }
                        }
                    }
                    composable("settings") {
                        Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = { navigation.popBackStack() }) { Text("Back") } }) }) { padding ->
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
                    composable("viewer") {
                        ArtifactViewer(Artifact(viewerName, viewerMime, viewerPath), vm.workspace, { navigation.popBackStack() },
                            { share(Artifact(viewerName, viewerMime, viewerPath)) }, { save(Artifact(viewerName, viewerMime, viewerPath)) })
                    }
                    composable("about") {
                        Scaffold(topBar = { TopAppBar(title = { Text("About") }, navigationIcon = { TextButton(onClick = { navigation.popBackStack() }) { Text("Back") } }) }) { padding ->
                            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.size(80.dp))
                                Text("DeepSeek Android", style = MaterialTheme.typography.headlineMedium)
                                Text("Unofficial. Not affiliated with or endorsed by DeepSeek.")
                                Text("An open-source Android assistant for chat, files and useful artifacts. Original code and artwork. MIT licensed.")
                                Text("One active provider and one account at a time. Your password stays in your browser. Account access and quota depend on the provider.")
                                Text("Conversations stay in memory in v0.1. Share or save artifacts to keep them.")
                                Text("PDF text extraction uses pdfbox-android 2.0.27.0, Apache License 2.0.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                state.error?.let { message -> ErrorCard(message, vm::dismissError) { navigation.navigate("settings") { launchSingleTop = true }; vm.dismissError() } }
            }
        }
        state.confirmation?.let { confirmation ->
            AlertDialog(onDismissRequest = { vm.answerConfirmation(false) }, title = { Text("Allow this tool once?") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(confirmation.name, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer { Text(confirmation.arguments.toString(), fontFamily = FontFamily.Monospace) }
                }
            }, confirmButton = { TextButton(onClick = { vm.answerConfirmation(true) }) { Text("Allow once") } },
                dismissButton = { TextButton(onClick = { vm.answerConfirmation(false) }) { Text("Deny") } })
        }
    }
}

@Composable
private fun ModelTitle(state: ChatUiState, settings: AppSettings, select: (String) -> Unit, refresh: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val models = settings.availableModels
    val newest = ModelSelector.pickDefault(models)?.id
    Box {
        TextButton(onClick = { expanded = true }, enabled = !state.running && !state.saving && state.settingsReady) {
            Text(state.title.ifEmpty { settings.selectedModel + if (models.isEmpty() && settings.selectedModel == ModelSelector.OFFLINE_FALLBACK) " · fallback" else "" }, Modifier.widthIn(max = 200.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            AppIcon(Glyph.CHEVRON, "Choose model", Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 480.dp).widthIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text(if (newest == null) "Automatic · fallback" else "Automatic · newest") }, onClick = { select(""); expanded = false },
                trailingIcon = { if (settings.model.isEmpty()) AppIcon(Glyph.CHECK) })
            val entries = (ModelSelector.ranked(models) +
                if (models.isEmpty()) listOf(ModelInfo(ModelSelector.OFFLINE_FALLBACK)) else emptyList())
                .let { if (settings.model.isNotBlank() && it.none { model -> model.id == settings.model }) listOf(ModelInfo(settings.model)) + it else it }
            entries.forEach { model ->
                DropdownMenuItem(text = {
                    Column {
                        Text(model.id)
                        if (model.id == newest) Text("newest", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        else if (models.isEmpty() && model.id == ModelSelector.OFFLINE_FALLBACK) Text("fallback · offline", style = MaterialTheme.typography.labelSmall)
                    }
                }, onClick = { select(model.id); expanded = false }, trailingIcon = { if (settings.model == model.id) AppIcon(Glyph.CHECK) })
            }
            HorizontalDivider()
            state.modelsNotice?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            DropdownMenuItem(text = { Text(if (state.modelsLoading) "Refreshing models…" else "Refresh models") }, onClick = refresh, enabled = !state.modelsLoading)
        }
    }
}

@Composable
private fun ConversationDrawer(state: ChatUiState, onNew: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now()
    val grouped = state.conversations.filter { it.title.contains(query, ignoreCase = true) }.groupBy {
        val date = Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        when (date) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> "Earlier" }
    }
    ModalDrawerSheet {
        Column(Modifier.fillMaxHeight().widthIn(max = 320.dp).padding(horizontal = 12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp), placeholder = { Text("Search chats") }, singleLine = true)
            NavigationDrawerItem(label = { Text("New chat") }, selected = state.items.isEmpty(), onClick = { if (!state.running && !state.importing) onNew() }, icon = { AppIcon(Glyph.PLUS) })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                listOf("Today", "Yesterday", "Earlier").forEach { group ->
                    val entries = grouped[group].orEmpty()
                    if (entries.isNotEmpty()) {
                        item { Text(group, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
                        items(entries, key = { it.id }) { conversation ->
                            NavigationDrawerItem(label = { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                selected = state.conversationId == conversation.id, onClick = { if (!state.running && !state.importing) onOpen(conversation.id) })
                        }
                    }
                }
                if (state.running) item { Text("Stop the current reply to switch chats.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            TextButton(onClick = onSettings, enabled = !state.running && !state.signingIn, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
            TextButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("About") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ErrorCard(message: String, dismiss: () -> Unit, settings: () -> Unit) {
    Card(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = settings) { Text("Open Settings") }
                TextButton(onClick = dismiss) { Text("Dismiss") }
            }
        }
    }
}
