package app.dsandroid.ui

import androidx.compose.ui.res.stringResource
import app.dsandroid.R

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
    var runDetailsVisible by remember { mutableStateOf(false) }
    var audioInfoVisible by remember { mutableStateOf(false) }
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
                } catch (_: Exception) { vm.showError(context.getString(R.string.save_failed)) }
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
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_artifact)))
        } catch (_: Exception) { vm.showError(context.getString(R.string.share_failed)) }
    }
    fun save(artifact: Artifact) {
        try {
            pendingSave = artifact.path
            saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = artifact.mimeType
                putExtra(Intent.EXTRA_TITLE, artifact.fileName)
            })
        } catch (_: Exception) { pendingSave = null; vm.showError(context.getString(R.string.document_unavailable)) }
    }
    ChatTheme(settings.appearance, settings.fontScale) {
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
                                onAbout = { scope.launch { drawer.close() }; navigation.navigate("about") }, rename = vm::renameConversation, delete = vm::deleteConversation)
                        }) {
                            Scaffold(topBar = {
                                CenterAlignedTopAppBar(title = { ModelTitle(state, settings, vm::selectModel, vm::refreshModels) }, navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawer.open() } }) { AppIcon(Glyph.MENU, stringResource(R.string.open_navigation)) }
                                }, actions = {
                                    IconButton(onClick = { audioInfoVisible = true }) { AppIcon(Glyph.MUTE, stringResource(R.string.voice)) }
                                    IconButton(onClick = vm::newConversation, enabled = !state.running && !state.importing,
                                        modifier = Modifier.padding(end = 8.dp).size(40.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
                                        AppIcon(Glyph.PLUS, stringResource(R.string.new_chat))
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
                        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { TextButton(onClick = { navigation.popBackStack() }) { Text(stringResource(R.string.back)) } }) }) { padding ->
                            if (state.settingsReady) SettingsScreen(settings, state.saving, Modifier.padding(padding),
                                { value -> vm.saveSettings(value) { navigation.popBackStack() } },
                                { value -> vm.saveSettings(value) { navigation.navigate("sign-in") } }, vm::signOut, vm::clearConversations)
                            else Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.settings_loading)) }
                        }
                    }
                    composable("sign-in") {
                        BackHandler { vm.cancelSignIn(); navigation.popBackStack() }
                        Scaffold(topBar = {
                            TopAppBar(title = { Text(stringResource(R.string.account_sign_in)) }, navigationIcon = { TextButton(onClick = { vm.cancelSignIn(); navigation.popBackStack() }) { Text(stringResource(R.string.back)) } })
                        }) { padding ->
                            SignInScreen(settings, state, Modifier.padding(padding), {
                                vm.signIn { url -> CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
                            }, vm::cancelSignIn)
                        }
                    }
                    composable("viewer") {
                        ArtifactViewer(Artifact(viewerName, viewerMime, viewerPath), vm.workspace, { navigation.popBackStack() },
                            { share(Artifact(viewerName, viewerMime, viewerPath)) }, { save(Artifact(viewerName, viewerMime, viewerPath)) },
                            openExternal = {
                                try {
                                    val artifact = Artifact(viewerName, viewerMime, viewerPath)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.artifacts", vm.workspace.resolve(artifact.path))
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, artifact.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(artifact.fileName, uri) })
                                } catch (_: Exception) { vm.showError(context.getString(R.string.share_failed)) }
                            }, exportPng = { vm.exportPng(Artifact(viewerName, viewerMime, viewerPath)) { save(it) } })
                    }
                    composable("about") {
                        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.about)) }, navigationIcon = { TextButton(onClick = { navigation.popBackStack() }) { Text(stringResource(R.string.back)) } }) }) { padding ->
                            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.size(80.dp))
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                                Text(stringResource(R.string.unofficial))
                                Text(stringResource(R.string.about_description))
                                Text(stringResource(R.string.about_provider))
                                Text(stringResource(R.string.about_storage))
                                Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if(state.waitingForNetwork || state.retrySeconds != null || state.resumable || state.needsSignIn) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(if(state.needsSignIn) stringResource(R.string.sign_in_again) else if(state.retrySeconds != null) stringResource(R.string.retry_countdown, state.retrySeconds ?: 0) else if(state.waitingForNetwork) stringResource(R.string.waiting_connection) else stringResource(R.string.resume_task))
                            Row {
                                if(state.needsSignIn) TextButton(onClick = { navigation.navigate(if(settings.provider == app.dsandroid.data.Provider.ACCOUNT) "sign-in" else "settings") }) { Text(stringResource(R.string.sign_in_again)) }
                                else if(state.resumable && !state.running) TextButton(onClick = vm::resumeTask) { Text(stringResource(R.string.resume_task)) }
                                else TextButton(onClick = vm::retryNow) { Text(stringResource(R.string.retry_now)) }
                                if(state.runDetails != null) TextButton(onClick = { runDetailsVisible = true }) { Text(stringResource(R.string.details)) }
                            }
                        }
                    }
                }
                state.error?.let { message -> ErrorCard(message, vm::dismissError) { navigation.navigate("settings") { launchSingleTop = true }; vm.dismissError() } }
            }
        }
        if(audioInfoVisible) AlertDialog(onDismissRequest = { audioInfoVisible = false }, title = { Text(stringResource(R.string.voice)) },
            text = { Text(stringResource(R.string.voice_muted)) }, confirmButton = { TextButton(onClick = { audioInfoVisible = false }) { Text(stringResource(R.string.confirm)) } })
        if(runDetailsVisible) ModalBottomSheet(onDismissRequest = { runDetailsVisible = false }) {
            SelectionContainer { Text(state.runDetails.orEmpty(), Modifier.padding(24.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
        }
        state.confirmation?.let { confirmation ->
            ToolConfirmationSheet(confirmation, vm::answerConfirmation)
        }
    }
}

@Composable
private fun ModelTitle(state: ChatUiState, settings: AppSettings, select: (String) -> Unit, refresh: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val models = settings.availableModels
    val newest = ModelSelector.pickDefault(models)?.id
    val fallbackSuffix = stringResource(R.string.model_fallback_suffix)
    Box {
        TextButton(onClick = { expanded = true }, enabled = !state.running && !state.saving && state.settingsReady) {
            Text(state.title.ifEmpty { settings.selectedModel + if (models.isEmpty() && settings.selectedModel == ModelSelector.OFFLINE_FALLBACK) fallbackSuffix else "" }, Modifier.widthIn(max = 200.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            AppIcon(Glyph.CHEVRON, stringResource(R.string.choose_model), Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 480.dp).widthIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text(if (newest == null) stringResource(R.string.automatic_fallback) else stringResource(R.string.automatic_newest)) }, onClick = { select(""); expanded = false },
                trailingIcon = { if (settings.model.isEmpty()) AppIcon(Glyph.CHECK) })
            val entries = (ModelSelector.ranked(models) +
                if (models.isEmpty()) listOf(ModelInfo(ModelSelector.OFFLINE_FALLBACK)) else emptyList())
                .let { if (settings.model.isNotBlank() && it.none { model -> model.id == settings.model }) listOf(ModelInfo(settings.model)) + it else it }
            entries.forEach { model ->
                DropdownMenuItem(text = {
                    Column {
                        Text(model.id)
                        if (model.id == newest) Text(stringResource(R.string.newest), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        else if (models.isEmpty() && model.id == ModelSelector.OFFLINE_FALLBACK) Text(stringResource(R.string.offline_fallback), style = MaterialTheme.typography.labelSmall)
                    }
                }, onClick = { select(model.id); expanded = false }, trailingIcon = { if (settings.model == model.id) AppIcon(Glyph.CHECK) })
            }
            HorizontalDivider()
            state.modelsNotice?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            DropdownMenuItem(text = { Text(if (state.modelsLoading) stringResource(R.string.refreshing_models) else stringResource(R.string.refresh_models)) }, onClick = refresh, enabled = !state.modelsLoading)
        }
    }
}

@Composable
private fun ConversationDrawer(state: ChatUiState, onNew: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit, onAbout: () -> Unit,
    rename: (String, String) -> Unit, delete: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuId by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val todayLabel = stringResource(R.string.today)
    val recentLabel = stringResource(R.string.last_30_days)
    val grouped = state.conversations.filter { it.title.contains(query, ignoreCase = true) || it.searchText.contains(query, ignoreCase = true) }.groupBy {
        val date = Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        when { date == today -> todayLabel; date >= today.minusDays(30) -> recentLabel
            else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.getDefault())) }
    }
    ModalDrawerSheet {
        Column(Modifier.fillMaxHeight().widthIn(max = 320.dp).padding(horizontal = 12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp), placeholder = { Text(stringResource(R.string.search_chats)) }, singleLine = true)
            NavigationDrawerItem(label = { Text(stringResource(R.string.new_chat)) }, selected = state.items.isEmpty(), onClick = { if (!state.running && !state.importing) onNew() }, icon = { AppIcon(Glyph.PLUS) })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                grouped.forEach { (group, entries) ->
                    item { Text(group, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
                    items(entries, key = { it.id }) { conversation ->
                        NavigationDrawerItem(label = { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = state.conversationId == conversation.id, onClick = { if (!state.running && !state.importing) onOpen(conversation.id) },
                            badge = {
                                Box {
                                    IconButton(onClick = { menuId = conversation.id }, enabled = !state.running) { AppIcon(Glyph.MORE, stringResource(R.string.details)) }
                                    DropdownMenu(expanded = menuId == conversation.id, onDismissRequest = { menuId = null }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menuId = null; title = conversation.title; renameId = conversation.id })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menuId = null; deleteId = conversation.id })
                                    }
                                }
                            })
                    }
                }
                if (state.running) item { Text(stringResource(R.string.stop_to_switch), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            TextButton(onClick = onSettings, enabled = !state.running && !state.signingIn, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.profile_footer)) }
            TextButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.about)) }
            Spacer(Modifier.height(12.dp))
        }
    }
    if(renameId != null) AlertDialog(onDismissRequest = { renameId = null }, title = { Text(stringResource(R.string.rename)) }, text = { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.chat_title)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { renameId?.let { rename(it, title.trim()) }; renameId = null }, enabled = title.isNotBlank()) { Text(stringResource(R.string.rename)) } },
        dismissButton = { TextButton(onClick = { renameId = null }) { Text(stringResource(R.string.cancel)) } })
    if(deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.delete_chat_notice)) },
        confirmButton = { TextButton(onClick = { deleteId?.let(delete); deleteId = null }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ErrorCard(message: String, dismiss: () -> Unit, settings: () -> Unit) {
    Card(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = settings) { Text(stringResource(R.string.open_settings)) }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}
