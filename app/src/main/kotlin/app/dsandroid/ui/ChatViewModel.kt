package app.dsandroid.ui

import android.app.Application
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dsandroid.BuildConfig
import app.dsandroid.R
import app.dsandroid.attachments.AttachmentImporter
import app.dsandroid.attachments.Upload
import app.dsandroid.auth.AccountSignIn
import app.dsandroid.core.agent.*
import app.dsandroid.core.auth.SessionAuthMachine
import app.dsandroid.core.attachments.AttachmentContextBuilder
import app.dsandroid.core.artifacts.ArtifactStore
import app.dsandroid.core.artifacts.FencedArtifact
import app.dsandroid.core.guard.*
import app.dsandroid.core.llm.*
import app.dsandroid.core.tools.*
import app.dsandroid.core.persistence.*
import app.dsandroid.core.personalization.*
import app.dsandroid.core.mcp.*
import app.dsandroid.core.export.*
import app.dsandroid.data.*
import app.dsandroid.tools.CreatePdfTool
import app.dsandroid.tools.CreateImageTool
import app.dsandroid.sandbox.WebViewCodeExecutor
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

sealed interface ChatItem {
    val id: String
    data class User(override val id: String, val text: String, val attachments: List<String>) : ChatItem
    data class Assistant(override val id: String, val steps: List<TurnStep> = emptyList(), val artifacts: List<Artifact> = emptyList(), val sources: List<WebSource> = emptyList()) : ChatItem
}

data class ConversationSummary(val id: String, val title: String, val createdAt: Long, val searchText: String = title, val pinned: Boolean = false, val projectId: String? = null)
data class Confirmation(val name: String, val arguments: JsonObject, val destination: String? = null, val remoteName: String? = null)
data class ChatUiState(
    val items: List<ChatItem> = emptyList(),
    val conversationId: String = "",
    val title: String = "",
    val conversations: List<ConversationSummary> = emptyList(),
    val attachments: List<Upload> = emptyList(),
    val importing: Boolean = false,
    val running: Boolean = false,
    val confirmation: Confirmation? = null,
    val error: String? = null,
    val signingIn: Boolean = false,
    val signInMessage: String? = null,
    val saving: Boolean = false,
    val settingsReady: Boolean = false,
    val modelsLoading: Boolean = false,
    val modelsNotice: String? = null,
    val retrySeconds: Int? = null,
    val waitingForNetwork: Boolean = false,
    val needsSignIn: Boolean = false,
    val resumable: Boolean = false,
    val runDetails: String? = null,
    val temporary: Boolean = false,
    val projectId: String? = null,
    val systemPrompt: String = "",
    val facts: List<SavedFact> = emptyList(),
    val projects: List<ProjectRecord> = emptyList(),
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    private val mutableSettings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()
    private var store: SettingsStore? = null
    private val settingsLock = Mutex()
    val workspace = WorkspacePath(File(application.filesDir, "workspace"))
    private val persistentCheckpoints = FileCheckpointStore(File(application.filesDir, "runs"))
    private val checkpoints = SessionCheckpointStore(persistentCheckpoints)
    private val memoryStore = MemoryStore(File(application.filesDir, "memory"))
    private val projectStore = ProjectStore(File(application.filesDir, "projects"))
    private val historyStore = ConversationStore(File(application.filesDir, "conversations"))
    private val diskWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val session = SessionAuthMachine()
    private var credentialRevision = 0L
    private var restarting = false
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) viewModelScope.launch {
                if (state.value.waitingForNetwork && !state.value.needsSignIn) retryNow()
            }
        }
    }
    private val importer = AttachmentImporter(application)
    private var agentJob: Job? = null
    private var stoppedRunId: String? = null
    private var activeRunId: String? = null
    private val sharedHttp = okhttp3.OkHttpClient()
    private var signInJob: Job? = null
    private var modelsJob: Job? = null
    private var confirmationReply: CompletableDeferred<Boolean>? = null
    private val codeExecutor = WebViewCodeExecutor(application) {
        requestConfirmation("python_runtime_download", buildJsonObject {
            put("runtime", "Pyodide")
            put("url", app.dsandroid.core.sandbox.PythonRuntime.BASE_URL)
            put("maximumBytes", app.dsandroid.core.sandbox.PythonRuntime.MAX_BYTES)
        })
    }
    private data class Conversation(
        val id: String = UUID.randomUUID().toString(), var title: String = "", val createdAt: Long = System.currentTimeMillis(),
        val history: MutableList<ChatMessage>, var items: List<ChatItem> = emptyList(),
        var attachmentCount: Int = 0, var pendingRunId: String? = null, var supersededRunIds: Set<String> = emptySet(),
        var pinned: Boolean = false, var projectId: String? = null, var systemPrompt: String = "", val temporary: Boolean = false,
    )
    private val conversations = mutableListOf<Conversation>()
    private var current = Conversation(history = mutableListOf(systemMessage()))

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (write in diskWrites) try { write() }
            catch (_: Exception) { showError(text(R.string.history_save_error)) }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        mutableState.update { it.copy(conversationId = current.id) }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    workspace.root.mkdirs()
                    val settingsStore = SettingsStore(application)
                    settingsStore to settingsStore.load()
                }
                store = loaded.first
                mutableSettings.value = loaded.second
                session.signedIn(loaded.second.runIdentity, credentialRevision.toString())
                val records = withContext(Dispatchers.IO) { historyStore.loadAll() }
                val recovered = ConversationRecovery.reconcile(records, persistentCheckpoints.all(), System.currentTimeMillis())
                conversations.addAll(recovered.map { it.toConversation() })
                withContext(Dispatchers.IO) { recovered.forEach(historyStore::save) }
                val facts = withContext(Dispatchers.IO) { memoryStore.all() }
                val projects = withContext(Dispatchers.IO) { projectStore.all() }
                mutableState.update { it.copy(facts = facts, projects = projects) }
                current = conversations.firstOrNull() ?: current
                mutableState.update { it.copy(conversationId = current.id, title = current.title, items = current.items,
                    resumable = current.pendingRunId != null, projectId = current.projectId, systemPrompt = current.systemPrompt, temporary = current.temporary) }
                mutableState.update { it.copy(settingsReady = true) }
                syncConversation()
                refreshModels()
            } catch (_: Exception) {
                showError(text(R.string.settings_unavailable))
            }
        }
    }

    private fun text(id: Int, vararg arguments: Any) = getApplication<Application>().getString(id, *arguments)

    private fun ConversationRecord.toConversation() = Conversation(id, title, createdAt, history.toMutableList(), items.map {
        if (it.role == "user") ChatItem.User(it.id, it.text, it.attachments)
        else ChatItem.Assistant(it.id, it.steps, it.artifacts, it.sources)
    }, attachmentCount, pendingRunId, supersededRunIds, pinned, projectId, systemPrompt, temporary)

    private fun Conversation.record() = ConversationRecord(id, title, createdAt, history.toList(), items.map {
        when (it) {
            is ChatItem.User -> StoredChatItem(it.id, "user", it.text, it.attachments)
            is ChatItem.Assistant -> StoredChatItem(it.id, "assistant", steps = it.steps, artifacts = it.artifacts, sources = it.sources)
        }
    }, attachmentCount, pendingRunId, supersededRunIds, pinned, projectId, systemPrompt, temporary)

    private fun persistConversation(conversation: Conversation) {
        if (conversation.items.isEmpty() || conversation.temporary) return
        val snapshot = conversation.record()
        diskWrites.trySend { historyStore.save(snapshot) }
    }

    private fun restoreCheckpoint(conversation: Conversation, checkpoint: RunCheckpoint) {
        val restored = ConversationRecovery.apply(conversation.record(), checkpoint).toConversation()
        conversation.history.clear(); conversation.history.addAll(restored.history)
        conversation.items = restored.items
        conversation.pendingRunId = restored.pendingRunId
        conversation.supersededRunIds = restored.supersededRunIds
    }

    private fun identity() = ClientIdentity(BuildConfig.VERSION_NAME, Locale.getDefault().toLanguageTag(), TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000)
    private fun client(config: AppSettings, model: String = config.selectedModel, thinking: Boolean = false): LlmClient {
        require(config.baseUrl.toHttpUrl().isHttps) { "Provider endpoints require HTTPS" }
        val auth = when (config.provider) {
            Provider.API_KEY -> ApiKeyAuth(config.apiKey)
            Provider.CUSTOM -> CustomEndpointAuth(config.customKey)
            Provider.ACCOUNT -> {
                require(config.accountOrigin == origin(config.baseUrl))
                DeepSeekAccountAuth(config.accountToken)
            }
        }
        return OpenAiCompatibleClient(config.baseUrl, model, auth, identity(), client = sharedHttp, thinking = thinking,
            vision = (config.capabilities[model] ?: ModelCapabilities()).isVision(model))
    }

    private suspend fun persist(transform: (AppSettings) -> AppSettings) = settingsLock.withLock {
        // Keep disk and StateFlow consistent even when a refresh is cancelled during a write.
        withContext(NonCancellable) {
            val value = transform(settings.value)
            withContext(Dispatchers.IO) { requireNotNull(store).save(value) }
            mutableSettings.value = value
        }
    }

    fun refreshModels() {
        if (!state.value.settingsReady) return
        modelsJob?.cancel()
        val config = settings.value
        modelsJob = viewModelScope.launch {
            mutableState.update { it.copy(modelsLoading = true, modelsNotice = null) }
            try {
                val fetched = client(config).listModels()
                require(fetched.isNotEmpty()) { "No models returned" }
                persist { latest -> if (latest.cacheKey == config.cacheKey) latest.copy(cachedModels = fetched, modelCacheKey = config.cacheKey) else latest }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val notice = text(if (settings.value.availableModels.isEmpty()) R.string.models_fallback_notice else R.string.models_cache_notice)
                mutableState.update { it.copy(modelsNotice = notice) }
                if (error is ProviderHttpException) {
                    if (error.status == 401 || error.status == 403) {
                        session.rejected(error.status)
                        mutableState.update { it.copy(needsSignIn = true) }
                    } else showError(localizeError(ErrorExplainer.explain(error)))
                }
            } finally { mutableState.update { it.copy(modelsLoading = false) } }
        }
    }

    fun selectModel(id: String) {
        if (state.value.running || state.value.saving || !state.value.settingsReady) return
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try { persist { it.copy(model = id) } }
            catch (_: Exception) { showError(text(R.string.model_remember_error)) }
            finally { mutableState.update { it.copy(saving = false) } }
        }
    }

    fun addAttachments(uris: List<Uri>) {
        if (state.value.running || state.value.importing || uris.isEmpty()) return
        val remaining = 50 - current.attachmentCount - state.value.attachments.size
        if (remaining <= 0) { showError(text(R.string.upload_limit)); return }
        mutableState.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                for (uri in uris.take(remaining)) {
                    try {
                        val upload = importer.import(uri)
                        mutableState.update { it.copy(attachments = it.attachments + upload) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { showError(text(R.string.upload_read_error)) }
                }
                if (uris.size > remaining) showError(text(R.string.upload_partial))
            } finally { mutableState.update { it.copy(importing = false) } }
        }
    }

    fun removeAttachment(id: String) {
        if (state.value.running) return
        val upload = state.value.attachments.find { it.id == id } ?: return
        mutableState.update { it.copy(attachments = it.attachments.filterNot { file -> file.id == id }) }
        viewModelScope.launch(Dispatchers.IO) { upload.file.delete() }
    }

    fun send(text: String, think: Boolean, search: Boolean, agentEnabled: Boolean = false, deepResearch: Boolean = false): Boolean {
        val ui = state.value
        if ((text.isBlank() && ui.attachments.isEmpty()) || ui.running || ui.importing || ui.saving || ui.signingIn || !ui.settingsReady) return false
        if (current.pendingRunId != null) { showError(this.text(R.string.resume_before_send)); return false }
        val config = settings.value
        val model = config.turnModel(think)
        try { client(config, model, think && !(config.capabilities[model] ?: ModelCapabilities()).isReasoning(model)) }
        catch (_: Exception) { showError(this.text(R.string.credentials_required)); return false }
        if (deepResearch && (!agentEnabled || !search || "web_search" in config.disabledTools || "web_fetch" in config.disabledTools)) {
            showError(this.text(R.string.research_tools_required)); return false
        }
        val uploads = ui.attachments
        val runId = UUID.randomUUID().toString()
        val conversation = current
        val userText = text.trim()
        if (conversation.title.isEmpty()) conversation.title = userText.ifBlank { uploads.firstOrNull()?.name ?: this.text(R.string.chat_new_title) }.replace('\n', ' ').take(60)
        if (!conversation.temporary && conversations.none { it.id == conversation.id }) conversations.add(conversation)
        conversation.pendingRunId = runId
        activeRunId = runId
        mutableState.update { it.copy(running = true, error = null, title = conversation.title, resumable = false,
            retrySeconds = null, waitingForNetwork = false, needsSignIn = false, runDetails = null,
            items = it.items + ChatItem.User("$runId-user", userText, uploads.map { upload -> upload.name }) + ChatItem.Assistant(runId)) }
        syncConversation()
        agentJob = viewModelScope.launch {
            var appended = false
            try {
                // Keep a stopped attachment turn editable: preparation is bounded local work,
                // and its user context must be durable before cancellation leaves this block.
                withContext(NonCancellable) {
                    val message = withContext(Dispatchers.IO) {
                        AttachmentContextBuilder.message(userText, uploads.map { it.content(config.supportsVision(think)) }, config.supportsVision(think))
                    }
                    refreshPrompt(conversation)
                    conversation.history.add(message)
                    appended = true
                    conversation.attachmentCount += uploads.size
                    mutableState.update { it.copy(attachments = emptyList()) }
                    withContext(Dispatchers.IO) { uploads.forEach { it.file.delete() } }
                    syncConversation()
                    checkpoints.save(RunCheckpoint(runId, conversation.id, model, think, search && agentEnabled,
                        config.runIdentity, messages = conversation.history.toList(), agentEnabled = agentEnabled, deepResearch = deepResearch,
                        researchPhase = if (deepResearch) ResearchPhase.PLAN else ResearchPhase.NONE))
                }
                ensureActive()
                executeRun(conversation, RunConfig(runId, conversation.id, model, think, search && agentEnabled, config.runIdentity, agentEnabled, deepResearch))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!appended) {
                    mutableState.update { state -> state.copy(items = state.items.filterNot { it.id == runId || it.id == "$runId-user" }) }
                    conversation.items = state.value.items
                }
                showError(this@ChatViewModel.text(if (appended) R.string.history_save_error else R.string.attachment_prepare_error))
            }
            finally { finishRun(conversation, runId) }
        }
        return true
    }

    private suspend fun executeRun(conversation: Conversation, run: RunConfig, checkpoint: RunCheckpoint? = null) {
        val config = settings.value
        require(config.runIdentity == run.providerKey)
        val llm = client(config, run.model, run.thinking && !(config.capabilities[run.model] ?: ModelCapabilities()).isReasoning(run.model))
        val tools = mutableListOf<Tool>(CreateMarkdownTool(), CreateCsvTool(), CreateSvgTool(), CreatePdfTool(), CreateImageTool(),
            CreateFileTool(), ListDirTool(), ListFilesTool(), ReadFileTool(), ZipFilesTool(), ListZipTool(),
            CreateDocxTool(), CreatePptxTool(), CreateXlsxTool(), CreateChartTool(),
            RenderChartTool(), CreateArtifactTool(), CodeInterpreterTool(codeExecutor))
        if (run.agentEnabled && run.searchEnabled) {
            tools.add(VisitWebTool()); tools.add(WebFetchTool())
            tools.add(WebSearchTool(if (config.searchBackend == "searxng") config.searxngUrl else null))
        }
        if (config.memoryEnabled && !conversation.temporary) tools.add(RememberTool(memoryStore) {
            val facts = memoryStore.all()
            mutableState.update { it.copy(facts = facts) }
        })
        config.remoteServers.forEach { server ->
            val remoteClient = McpClient(server.url)
            server.tools.forEach { tools.add(RemoteTool(server, it, remoteClient)) }
        }
        val registry = ToolRegistry(if (run.agentEnabled) tools else emptyList(), config.disabledTools)
        val confirmer = Confirmer { name, args ->
            val remote = registry[name] as? RemoteTool
            requestConfirmation(name, args, remote?.server?.url, remote?.definition?.name)
        }
        val loop = AgentLoop(llm, registry, GuardPolicy(registry, workspace), ToolContext(workspace), confirmer,
            config.guardSettings(), maxSteps = if (run.deepResearch) ResearchPolicy.MAX_STEPS else 8, checkpointStore = checkpoints, runConfig = run)
        refreshPrompt(conversation)
        val events = if (checkpoint == null) loop.run(conversation.history) else loop.resume(
            checkpoint.copy(messages = checkpoint.messages.map { if (it.role == "system") systemMessage(conversation) else it }), conversation.history)
        events.collect { event ->
            when (event) {
                is AgentEvent.StepsUpdated -> updateAssistant(run.runId) { it.copy(steps = event.steps) }
                is AgentEvent.SourcesUpdated -> updateAssistant(run.runId) { it.copy(sources = event.sources) }
                is AgentEvent.ArtifactCreated -> updateAssistant(run.runId) { it.copy(artifacts = (it.artifacts + event.artifact).distinctBy { artifact -> artifact.path }) }
                is AgentEvent.ToolFinished -> syncConversation()
                is AgentEvent.RetryWaiting -> mutableState.update { it.copy(retrySeconds = ((event.remainingMs + 999) / 1000).toInt(),
                    waitingForNetwork = true, runDetails = event.details, error = null) }
                is AgentEvent.RecoverableWaiting -> mutableState.update { it.copy(retrySeconds = null, waitingForNetwork = true,
                    resumable = true, runDetails = event.details, error = null) }
                is AgentEvent.AuthenticationRequired -> {
                    session.rejected(event.status)
                    mutableState.update { it.copy(needsSignIn = true, resumable = true, waitingForNetwork = false,
                        retrySeconds = null, error = null) }
                }
                is AgentEvent.Error -> showError(localizeError(event.message))
                AgentEvent.Done -> {
                    conversation.pendingRunId = null
                    mutableState.update { it.copy(waitingForNetwork = false, retrySeconds = null, resumable = false, runDetails = null) }
                }
                is AgentEvent.TextDelta, is AgentEvent.ReasoningDelta, is AgentEvent.ToolStarted ->
                    mutableState.update { it.copy(retrySeconds = null, waitingForNetwork = false) }
                else -> Unit
            }
        }
    }

    private suspend fun requestConfirmation(name: String, arguments: JsonObject, destination: String? = null, remoteName: String? = null): Boolean = withContext(Dispatchers.Main.immediate) {
        val reply = CompletableDeferred<Boolean>()
        confirmationReply = reply
        mutableState.update { it.copy(confirmation = Confirmation(name, arguments, destination, remoteName)) }
        try { reply.await() }
        finally { reply.cancel(); confirmationReply = null; mutableState.update { it.copy(confirmation = null) } }
    }

    fun openFencedArtifact(turnId: String, index: Int, fence: FencedArtifact, open: (Artifact) -> Unit) {
        viewModelScope.launch {
            try {
                val artifact = withContext(Dispatchers.IO) {
                    ArtifactStore(workspace).importFence(turnId, index, fence.format, fence.content)
                }
                open(artifact)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError(text(R.string.canvas_open_failed)) }
        }
    }

    private suspend fun finishRun(conversation: Conversation, runId: String) = withContext(NonCancellable) {
        try {
            var checkpoint = checkpoints.load(runId)
            if (stoppedRunId == runId) {
                stoppedRunId = null
                checkpoint = checkpoint?.let { saved ->
                    val visible = state.value.items.filterIsInstance<ChatItem.Assistant>().find { it.id == runId }?.steps.orEmpty()
                    val stopped = StoppedRun.finish(saved, visible)
                    try { checkpoints.save(stopped); stopped }
                    catch (_: Exception) { showError(text(R.string.history_save_error)); saved }
                }
            }
            conversation.items = state.value.items
            if (checkpoint != null) restoreCheckpoint(conversation, checkpoint)
            else conversation.pendingRunId = null
        } catch (_: Exception) {
            showError(text(R.string.history_save_error))
        } finally {
            activeRunId = null
            mutableState.update { it.copy(items = conversation.items, running = false, confirmation = null,
                retrySeconds = null, resumable = conversation.pendingRunId != null) }
            syncConversation()
        }
    }

    fun resumeTask() {
        if (state.value.running || state.value.signingIn || state.value.saving || !state.value.settingsReady || state.value.needsSignIn) return
        val conversation = current
        val id = conversation.pendingRunId ?: return
        activeRunId = id
        mutableState.update { it.copy(running = true, error = null, retrySeconds = null) }
        agentJob = viewModelScope.launch {
            try {
                val checkpoint = requireNotNull(checkpoints.load(id))
                if (checkpoint.providerKey != settings.value.runIdentity) {
                    showError(text(R.string.resume_provider_changed)); return@launch
                }
                executeRun(conversation, RunConfig(checkpoint.runId, checkpoint.conversationId, checkpoint.model,
                    checkpoint.thinking, checkpoint.searchEnabled, checkpoint.providerKey, checkpoint.agentEnabled, checkpoint.deepResearch), checkpoint)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError(text(R.string.resume_unavailable)) }
            finally { finishRun(conversation, id) }
        }
    }

    fun retryNow() {
        if (restarting || state.value.needsSignIn || (!state.value.waitingForNetwork && !state.value.resumable)) return
        val conversationId = current.id
        val runId = current.pendingRunId
        restarting = true
        viewModelScope.launch {
            try {
                agentJob?.cancelAndJoin()
                if (current.id == conversationId && current.pendingRunId == runId) resumeTask()
            }
            finally { restarting = false }
        }
    }

    fun retryTurn(userId: String, think: Boolean, search: Boolean, agentEnabled: Boolean, deepResearch: Boolean = false) {
        if (current.pendingRunId != null && state.value.items.filterIsInstance<ChatItem.User>().lastOrNull()?.id == userId) {
            retryNow()
        } else restartTurn(userId, null, think, search, agentEnabled, deepResearch)
    }

    /** Replace the entire suffix, including old tool results, using the stored attachment context. */
    fun restartTurn(userId: String, replacement: String?, think: Boolean, search: Boolean, agentEnabled: Boolean, deepResearch: Boolean = false): Boolean {
        val ui = state.value
        if (ui.running || ui.importing || ui.saving || ui.signingIn || !ui.settingsReady || restarting) return false
        if (ui.attachments.isNotEmpty()) { showError(text(R.string.turn_pending_attachments)); return false }
        val old = current.record()
        val revised = try { ConversationTurns.rewind(old, userId, replacement) }
        catch (_: IllegalArgumentException) { showError(text(R.string.turn_edit_unavailable)); return false }
        if (revised.items.last().text.isBlank() && revised.items.last().attachments.isEmpty()) return false
        val config = settings.value
        val model = config.turnModel(think)
        try { client(config, model) }
        catch (_: Exception) { showError(text(R.string.credentials_required)); return false }
        val runId = UUID.randomUUID().toString()
        val run = RunConfig(runId, current.id, model, think, search && agentEnabled, config.runIdentity, agentEnabled, deepResearch)
        val conversation = current
        val removedRuns = old.items.drop(revised.items.size).filter { it.role == "assistant" }.map { it.id }
        activeRunId = runId
        mutableState.update { it.copy(running = true, error = null, resumable = false, waitingForNetwork = false,
            retrySeconds = null, runDetails = null) }
        agentJob = viewModelScope.launch {
            var prepared = false
            try {
                // Save the revised transcript and tombstones before its checkpoint. If the
                // process stops between them, recovery retains a valid editable prompt.
                val persisted = CompletableDeferred<Boolean>(parent = viewModelScope.coroutineContext[Job])
                val nextItems = revised.items + StoredChatItem(runId, "assistant")
                val nextRecord = revised.copy(items = nextItems, pendingRunId = runId)
                diskWrites.send {
                    try {
                        historyStore.save(nextRecord)
                        val checkpointReady = runCatching {
                            checkpoints.save(RunCheckpoint(runId, conversation.id, model, think, run.searchEnabled,
                                config.runIdentity, messages = revised.history, agentEnabled = agentEnabled, deepResearch = deepResearch,
                                researchPhase = if (deepResearch) ResearchPhase.PLAN else ResearchPhase.NONE))
                        }.isSuccess
                        // Tombstones in the saved conversation make cleanup failure harmless.
                        removedRuns.forEach { runCatching { checkpoints.delete(it) } }
                        persisted.complete(checkpointReady)
                    } catch (error: Exception) { persisted.completeExceptionally(error) }
                }
                // Finish the local rewrite even if Stop arrives while it is being saved.
                val checkpointReady = withContext(NonCancellable) {
                    val saved = persisted.await()
                    conversation.history.clear(); conversation.history.addAll(revised.history)
                    conversation.items = nextRecord.toConversation().items
                    conversation.attachmentCount = revised.attachmentCount
                    conversation.supersededRunIds = revised.supersededRunIds
                    conversation.pendingRunId = runId
                    mutableState.update { it.copy(items = conversation.items) }
                    prepared = true
                    saved
                }
                ensureActive()
                check(checkpointReady) { "Could not save run" }
                executeRun(conversation, run)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError(text(R.string.turn_edit_unavailable)) }
            finally {
                if (prepared) finishRun(conversation, runId)
                else { activeRunId = null; mutableState.update { it.copy(running = false, resumable = conversation.pendingRunId != null) } }
            }
        }
        return true
    }

    fun exportPng(artifact: Artifact, onCreated: (Artifact) -> Unit) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val file = workspace.resolve(artifact.path)
                    require(file.length() <= 2 * 1024 * 1024)
                    CreateImageTool().run(buildJsonObject { put("svg", file.readText()) }, ToolContext(workspace))
                }
                onCreated(result.artifacts.single())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError(text(R.string.viewer_error)) }
        }
    }

    private fun updateAssistant(id: String, update: (ChatItem.Assistant) -> ChatItem.Assistant) {
        mutableState.update { state -> state.copy(items = state.items.map { if (it is ChatItem.Assistant && it.id == id) update(it) else it }) }
    }
    private fun syncConversation() {
        current.items = state.value.items
        mutableState.update { it.copy(conversations = conversations.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.createdAt }).map { c ->
            ConversationSummary(c.id, c.title, c.createdAt, c.title + "\n" + c.items.joinToString("\n") { item ->
                when (item) { is ChatItem.User -> item.text; is ChatItem.Assistant -> item.steps.filterIsInstance<TurnStep.Answer>().joinToString("\n") { answer -> answer.text } }
            }, c.pinned, c.projectId)
        }) }
        persistConversation(current)
    }
    fun newConversation(temporary: Boolean = false, projectId: String? = null) {
        if (state.value.running || state.value.importing || restarting) return
        discardPendingUploads()
        syncConversation()
        discardTemporarySession()
        current = Conversation(history = mutableListOf(systemMessage()), temporary = temporary, projectId = projectId)
        if (temporary) checkpoints.markTemporary(current.id)
        mutableState.update { it.copy(conversationId = current.id, title = "", items = emptyList(), error = null, attachments = emptyList(),
            temporary = temporary, projectId = projectId, systemPrompt = "",
            resumable = false, waitingForNetwork = false, retrySeconds = null, runDetails = null) }
    }
    fun openConversation(id: String) {
        if (state.value.running || state.value.importing || restarting) return
        val target = conversations.find { it.id == id } ?: return
        discardPendingUploads()
        syncConversation(); discardTemporarySession(); current = target
        mutableState.update { it.copy(conversationId = target.id, title = target.title, items = target.items, error = null, attachments = emptyList(),
            temporary = target.temporary, projectId = target.projectId, systemPrompt = target.systemPrompt,
            resumable = target.pendingRunId != null, waitingForNetwork = false, retrySeconds = null, runDetails = null) }
    }
    fun renameConversation(id: String, title: String) {
        if (state.value.running || title.isBlank()) return
        val conversation = conversations.find { it.id == id } ?: return
        conversation.title = title.replace('\n', ' ').trim().take(100)
        if (current.id == id) mutableState.update { it.copy(title = conversation.title) }
        persistConversation(conversation)
        syncConversation()
    }
    fun deleteConversation(id: String) {
        if (state.value.running || state.value.importing) return
        val conversation = conversations.find { it.id == id } ?: return
        conversations.remove(conversation)
        diskWrites.trySend {
            persistentCheckpoints.deleteConversation(id)
            historyStore.delete(id)
        }
        if (current.id == id) {
            discardPendingUploads()
            current = Conversation(history = mutableListOf(systemMessage()))
            mutableState.update { it.copy(conversationId = current.id, title = "", items = emptyList(), attachments = emptyList(),
                resumable = false, waitingForNetwork = false, retrySeconds = null, runDetails = null, error = null,
                temporary = false, projectId = null, systemPrompt = "") }
        }
        syncConversation()
    }
    fun clearConversations() {
        if (state.value.running || state.value.importing) return
        conversations.map { it.id }.forEach(::deleteConversation)
    }
    fun answerConfirmation(allow: Boolean) { confirmationReply?.complete(allow) }
    fun stop() {
        stoppedRunId = activeRunId
        confirmationReply?.cancel(); agentJob?.cancel()
        mutableState.update { it.copy(waitingForNetwork = false, retrySeconds = null) }
    }
    fun showError(message: String) { mutableState.update { it.copy(error = message) } }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    private fun localizeError(message: String): String {
        val resource = when (message) {
            "Tools are unavailable in Chat mode" -> R.string.error_chat_tools
            ErrorExplainer.http(401) -> R.string.error_signin
            ErrorExplainer.http(402) -> R.string.error_balance
            ErrorExplainer.http(403) -> R.string.error_refused
            ErrorExplainer.http(429) -> R.string.error_rate
            ErrorExplainer.http(500) -> R.string.error_service
            ErrorExplainer.explain(java.net.SocketTimeoutException()) -> R.string.error_timeout
            ErrorExplainer.explain(java.io.IOException()) -> R.string.error_connection
            else -> if (message.startsWith("Stopped after ")) R.string.error_step_limit else R.string.error_reply
        }
        val status = Regex("request \\((\\d{3})\\)").find(message)?.groupValues?.get(1)?.toIntOrNull()
        return if (status != null) text(R.string.error_request, status) else text(resource)
    }
    private fun discardPendingUploads() {
        val uploads = state.value.attachments
        viewModelScope.launch(Dispatchers.IO) { uploads.forEach { it.file.delete() } }
    }

    fun saveSettings(value: AppSettings, onSaved: () -> Unit) {
        if (state.value.running || state.value.signingIn || state.value.saving) return
        try {
            require(value.customInstructions.length <= 8000 && value.remoteServers.size <= 8)
            require(value.remoteServers.map { it.id }.distinct().size == value.remoteServers.size)
            value.remoteServers.forEach { McpEndpoint.validate(it.url); require(it.tools.size <= 64) }
            val url = value.baseUrl.toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            require(url.isHttps)
            listOf(value.authorizeUrl, value.exchangeUrl).filter { it.isNotBlank() }.forEach {
                val endpoint = it.toHttpUrl()
                require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.fragment == null)
            }
            if (value.searchBackend == "searxng") {
                val searchUrl = value.searxngUrl.toHttpUrl()
                require(searchUrl.isHttps && searchUrl.username.isEmpty() && searchUrl.password.isEmpty() && searchUrl.fragment == null)
            }
        } catch (_: Exception) { showError(text(R.string.settings_urls_error)); return }
        val old = settings.value
        val changedAccount = value.authorizeUrl != old.authorizeUrl || value.exchangeUrl != old.exchangeUrl || value.clientId != old.clientId ||
            (value.accountOrigin.isNotEmpty() && value.accountOrigin != origin(value.baseUrl))
        var safeValue = if (changedAccount) value.copy(accountToken = "", accountOrigin = "") else value
        val credentialsChanged = old.apiKey != safeValue.apiKey || old.customKey != safeValue.customKey || old.accountToken != safeValue.accountToken
        val renewSameActiveAccount = state.value.needsSignIn && old.cacheKey == safeValue.cacheKey && !changedAccount
        safeValue = safeValue.copy(accountBinding = if ((credentialsChanged && !renewSameActiveAccount) || old.cacheKey != safeValue.cacheKey || changedAccount)
            UUID.randomUUID().toString() else old.accountBinding)
        if (value.cacheKey != old.cacheKey) safeValue = safeValue.copy(cachedModels = emptyList(), modelCacheKey = "",
            model = if (value.model == old.model) "" else value.model)
        modelsJob?.cancel()
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            var resume = false
            try {
                persist { safeValue }
                val changedCredentials = old.apiKey != safeValue.apiKey || old.customKey != safeValue.customKey || old.accountToken != safeValue.accountToken
                if (old.runIdentity != safeValue.runIdentity) session.signOut()
                if (changedCredentials || old.runIdentity != safeValue.runIdentity) {
                    credentialRevision++
                    val renewed = session.signedIn(safeValue.runIdentity, credentialRevision.toString())
                    resume = renewed && state.value.needsSignIn && current.pendingRunId != null
                    if (renewed) mutableState.update { it.copy(needsSignIn = false) }
                }
                onSaved()
                refreshModels()
            } catch (_: Exception) { showError(text(R.string.settings_save_error)) }
            finally { mutableState.update { it.copy(saving = false) }; if (resume) resumeTask() }
        }
    }

    fun signIn(openBrowser: (String) -> Unit) {
        if (state.value.signingIn || state.value.running || state.value.saving || !state.value.settingsReady) return
        val config = settings.value
        if (!config.accountConfigured) {
            mutableState.update { it.copy(signInMessage = text(R.string.signin_not_configured)) }; return
        }
        modelsJob?.cancel()
        mutableState.update { it.copy(signingIn = true, signInMessage = text(R.string.signin_browser_prompt)) }
        signInJob = viewModelScope.launch {
            var resume = false
            try {
                val token = AccountSignIn(identity()).signIn(config, openBrowser)
                val binding = if (config.provider == Provider.ACCOUNT && state.value.needsSignIn) config.accountBinding else UUID.randomUUID().toString()
                persist { it.copy(provider = Provider.ACCOUNT, accountToken = token, accountOrigin = origin(config.baseUrl),
                    accountBinding = binding, cachedModels = emptyList(), modelCacheKey = "") }
                if (config.runIdentity != settings.value.runIdentity) session.signOut()
                credentialRevision++
                val renewed = session.signedIn(settings.value.runIdentity, credentialRevision.toString())
                resume = renewed && current.pendingRunId != null
                mutableState.update { it.copy(signInMessage = text(R.string.signin_success), needsSignIn = !renewed) }
                refreshModels()
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(signInMessage = text(R.string.signin_cancelled)) }; throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(signInMessage = text(R.string.signin_failed)) }
            } finally { mutableState.update { it.copy(signingIn = false) }; if (resume) resumeTask() }
        }
    }
    fun cancelSignIn() { signInJob?.cancel() }
    fun signOut() {
        saveSettings(settings.value.copy(apiKey = "", customKey = "", accountToken = "", accountOrigin = "", cachedModels = emptyList(), modelCacheKey = "")) {
            session.signOut(); mutableState.update { it.copy(needsSignIn = true) }
        }
    }
    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        diskWrites.close()
        super.onCleared()
    }
    private fun origin(url: String): String = url.toHttpUrl().let { "${it.scheme}://${it.host}:${it.port}" }
    private fun systemMessage(conversation: Conversation? = null) = ChatMessage("system", PromptComposer.compose(
        settings.value.customInstructions, conversation?.systemPrompt.orEmpty(),
        state.value.projects.find { it.id == conversation?.projectId }, state.value.facts,
        settings.value.memoryEnabled && conversation?.temporary != true))

    private fun refreshPrompt(conversation: Conversation) {
        conversation.history.removeAll { it.role == "system" }
        conversation.history.add(0, systemMessage(conversation))
    }

    private fun discardTemporarySession() {
        if (current.temporary) checkpoints.discardTemporary(current.id)
    }
}
