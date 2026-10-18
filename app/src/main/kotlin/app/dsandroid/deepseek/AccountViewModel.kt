package app.dsandroid.deepseek

import android.app.Application
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dsandroid.attachments.AttachmentImporter
import app.dsandroid.attachments.Upload
import app.dsandroid.core.agent.AgentEvent
import app.dsandroid.core.agent.AgentLoop
import app.dsandroid.core.agent.Confirmer
import app.dsandroid.core.agent.ToolCallProtocol
import app.dsandroid.core.agent.ToolStatus
import app.dsandroid.core.agent.TurnStep
import app.dsandroid.core.agent.WebChatTransport
import app.dsandroid.core.agent.WebPromptKind
import app.dsandroid.core.agent.WebToolProtocolClient
import app.dsandroid.core.deepseek.*
import app.dsandroid.core.guard.GuardPolicy
import app.dsandroid.core.guard.GuardSettings
import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.llm.ChatMessage
import app.dsandroid.core.python.RunPythonTool
import app.dsandroid.core.skills.SkillDownloader
import app.dsandroid.core.skills.SkillPack
import app.dsandroid.core.skills.SkillPackReader
import app.dsandroid.core.skills.SkillPrompt
import app.dsandroid.core.tools.Artifact
import app.dsandroid.core.tools.ToolContext
import app.dsandroid.core.tools.ToolRegistry
import app.dsandroid.python.AgentToolkit
import app.dsandroid.tools.CreatePdfTool
import app.dsandroid.data.Appearance
import app.dsandroid.data.Provider
import app.dsandroid.data.SettingsStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl

class AccountViewModel @JvmOverloads constructor(
    application: Application,
    baseUrl: String = DeepSeekOrigin.URL,
    private val powSolver: (suspend (PowChallenge) -> Long)? = null,
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = mutable.asStateFlow()
    private val client = DeepSeekWebClient(origin = baseUrl.toHttpUrl())
    private var session: SessionToken? = null
    private var rejectedToken: String? = null
    private var store: DeepSeekSessionStore? = null
    private var settingsStore: SettingsStore? = null
    private val history = WebHistory(File(application.filesDir, "deepseek-web-chats.json"))
    private val appearancePrefs = application.getSharedPreferences("deepseek_web_appearance", 0)
    private val persistence = Mutex()
    private var chats = emptyList<WebChat>()
    private var current = emptyChat()
    private val uploads = mutableListOf<Upload>()
    private var sending: Job? = null
    private var importing: Job? = null
    private var sessionChanging = false
    /** Agent files live under workspace/account/<chat id>; the FileProvider exposes workspace/ for sharing. */
    val agentWorkspace = WorkspacePath(File(application.filesDir, "workspace"))
    private var toolReply: CompletableDeferred<Boolean>? = null
    private var pendingPack: SkillPack? = null

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    store = DeepSeekSessionStore(application)
                    settingsStore = SettingsStore(application)
                    store!!.load() to history.load()
                }
                session = saved.first
                chats = saved.second
                val expired = session?.isExpired(System.currentTimeMillis()) == true
                val appearance = runCatching { Appearance.valueOf(appearancePrefs.getString("theme", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM)
                val hasApiKey = withContext(Dispatchers.IO) { settingsStore!!.load().apiKey.isNotBlank() }
                val agent = appearancePrefs.getBoolean("agent_mode", false)
                mutable.update { it.copy(appearance = appearance, hasApiKey = hasApiKey, agent = agent) }
                if (expired) expireSession()
                else {
                    if (session == null) {
                        chats = emptyList()
                        withContext(Dispatchers.IO) { history.clear() }
                    }
                    mutable.update { it.copy(ready = true, signedIn = session != null, history = summaries()) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.update { it.copy(ready = true, error = "Secure session or local history could not be opened. Close and reopen the app; no unencrypted session will be saved.") }
            }
        }
    }

    private fun available() = state.value.ready && !state.value.busy && !state.value.importing && !sessionChanging
    fun navigate(page: AccountPage) {
        if (!available()) return
        mutable.update { it.copy(page = page, error = null) }
        if (page == AccountPage.SKILLS) refreshToolsets()
    }
    fun setAgent(enabled: Boolean) {
        appearancePrefs.edit().putBoolean("agent_mode", enabled).apply()
        mutable.update { it.copy(agent = enabled) }
    }
    fun draft(value: String) { mutable.update { it.copy(draft = value.take(20_000)) } }
    fun notice(message: String) { mutable.update { it.copy(error = message) } }
    fun appearance(value: Appearance) {
        appearancePrefs.edit().putString("theme", value.name).apply()
        mutable.update { it.copy(appearance = value) }
    }
    suspend fun refreshApiKey() {
        try {
            val saved = withContext(Dispatchers.IO) { settingsStore?.load()?.apiKey?.isNotBlank() == true }
            mutable.update { it.copy(hasApiKey = saved) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice("The API key settings could not be read securely. Try again.") }
    }
    fun saveApiKey(value: String) {
        if (!available()) return
        val key = value.trim()
        if (key.length > 16_384 || key.any { it.isWhitespace() }) { notice("Enter a valid API key without spaces."); return }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val settings = settingsStore ?: throw IllegalStateException()
                    // Use the existing encrypted API settings; no account token is ever an API key.
                    settings.save(settings.load().copy(apiKey = key, provider = Provider.API_KEY, baseUrl = "https://api.deepseek.com"))
                }
                mutable.update { it.copy(hasApiKey = key.isNotBlank(), error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("The API key could not be saved securely. Try again.") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    /** True only when the service says this freshly signed-in account still owes the age confirmation. */
    suspend fun needsBirthday(token: SessionToken): Boolean = try {
        client.needsBirthday(WebCredentials(token, DeepSeekCookies.read()))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    suspend fun acceptSession(token: SessionToken): Boolean {
        if (!available() || token.bearer() == rejectedToken) return false
        if (token.isExpired(System.currentTimeMillis())) { expireSession(); return false }
        sessionChanging = true
        return try {
            withContext(Dispatchers.IO) {
                val target = store ?: DeepSeekSessionStore(getApplication()).also { store = it }
                if (session?.bearer() != token.bearer()) persistence.withLock { history.clear() }
                target.save(token)
            }
            if (session?.bearer() != token.bearer()) { chats = emptyList(); current = emptyChat(); clearUploads() }
            session = token
            mutable.update { it.copy(signedIn = true, logoutPending = false, page = AccountPage.CHAT, error = null,
                messages = displayMessages(), history = summaries(), title = current.title, conversationId = current.id, requiresNewChat = current.blocked, draft = "", attachments = emptyList()) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice("The session could not be stored securely. Login was not completed."); false }
        finally { sessionChanging = false }
    }
    fun logout() {
        if (sessionChanging || !state.value.ready) return
        sessionChanging = true
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                sending?.cancelAndJoin(); importing?.cancelAndJoin()
                clearSessionData()
                rejectedToken = null
                mutable.update { AccountUiState(ready = true, appearance = it.appearance, hasApiKey = it.hasApiKey, page = AccountPage.LOGIN) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                session = null
                mutable.update { it.copy(signedIn = false, logoutPending = true, error = "Logout could not fully clear local data. Close and reopen the app, then retry logout.") }
            } finally { sessionChanging = false; mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun clearSessionData() {
        session = null
        chats = emptyList(); current = emptyChat(); clearUploads()
        withContext(Dispatchers.IO) {
            store?.clear(); persistence.withLock { history.clear() }
            File(agentWorkspace.root, "account").deleteRecursively()
        }
        DeepSeekCookies.clear()
    }
    private suspend fun expireSession() {
        rejectedToken = session?.bearer()
        sessionChanging = true
        var cleared = false
        try { clearSessionData(); cleared = true }
        finally {
            mutable.update { AccountUiState(ready = true, appearance = it.appearance, hasApiKey = it.hasApiKey,
                page = AccountPage.LOGIN, error = SessionExpired().message, logoutPending = !cleared) }
            sessionChanging = false
        }
    }
    fun newChat() {
        if (!available()) return
        clearUploads(); current = emptyChat()
        mutable.update { it.copy(page = AccountPage.CHAT, title = current.title, conversationId = current.id, messages = emptyList(), draft = "", attachments = emptyList(), requiresNewChat = false, error = null) }
    }
    fun open(id: String) {
        if (!available()) return
        current = chats.firstOrNull { it.id == id } ?: return
        clearUploads()
        mutable.update { it.copy(page = AccountPage.CHAT, title = current.title, conversationId = current.id, messages = displayMessages(), draft = "", attachments = emptyList(), requiresNewChat = current.blocked, error = null) }
    }
    fun rename(id: String, title: String) {
        val name = title.trim().take(100)
        if (name.isEmpty()) return
        changeHistory(id, delete = false, title = name)
    }
    fun delete(id: String) = changeHistory(id, delete = true)
    private fun changeHistory(id: String, delete: Boolean, title: String = "") {
        if (!available()) return
        val chat = chats.firstOrNull { it.id == id } ?: return
        mutable.update { it.copy(busy = true, error = null) }
        sending = viewModelScope.launch {
            try {
                chat.sessionId?.let { remote ->
                    if (delete) client.deleteSession(credentials(), remote) else client.renameSession(credentials(), remote, title)
                }
                // Server failures leave both memory and disk untouched.
                val next = if (delete) chats.filterNot { it.id == id } else chats.map { if (it.id == id) it.copy(title = title) else it }
                withContext(Dispatchers.IO) {
                    persistence.withLock { history.save(next) }
                    if (delete) File(agentWorkspace.root, chatDir(id)).deleteRecursively()
                }
                chats = next
                val wasCurrent = current.id == id
                if (wasCurrent) {
                    current = if (delete) emptyChat() else current.copy(title = title)
                    if (delete) clearUploads()
                }
                mutable.update { it.copy(history = summaries(), title = current.title, conversationId = current.id, messages = displayMessages(), requiresNewChat = current.blocked,
                    draft = if (delete && wasCurrent) "" else it.draft, attachments = uploads.map(Upload::name)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportFailure(error) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun importShared(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris.take(5)) { importAttachment(uri); importing?.join() }
            if (uris.size > 5) notice("Only the first five attachments were imported.")
        }
    }
    fun importAttachment(uri: Uri) {
        if (!available()) return
        if (uploads.size >= 5) { notice("Attach up to five files per message."); return }
        mutable.update { it.copy(importing = true) }
        importing = viewModelScope.launch {
            try {
                // Retain the imported file even when an image has no recognizable text.
                val upload = AttachmentImporter(getApplication()).import(uri)
                uploads += upload
                mutable.update { it.copy(attachments = uploads.map(Upload::name)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("This attachment could not be read. Choose a supported file smaller than 20 MB.") }
            finally { mutable.update { it.copy(importing = false) } }
        }
    }
    fun removeAttachment(index: Int) {
        if (!available()) return
        uploads.getOrNull(index)?.let { it.file.delete(); uploads.removeAt(index) }
        mutable.update { it.copy(attachments = uploads.map(Upload::name)) }
    }
    fun send(think: Boolean, search: Boolean, solve: suspend (PowChallenge) -> Long) {
        if (!available() || current.blocked) return
        val prompt = state.value.draft.trim()
        if (prompt.isEmpty() && uploads.isEmpty()) return
        val files = uploads.toList()
        val display = listOf(prompt, files.joinToString("\n") { "Attached: ${it.name}" }).filter(String::isNotBlank).joinToString("\n\n")
        val user = WebMessage(UUID.randomUUID().toString(), true, display, parentId = current.parentId,
            prompt = prompt, thinkingEnabled = think, searchEnabled = search, agent = state.value.agent)
        if (user.agent) runAgentTurn(current.messages, user, files, solve) else runTurn(current.messages, user, files, solve)
    }
    /** Reuse the selected prompt's predecessor, never the reply that is being replaced. */
    fun regenerate(solve: suspend (PowChallenge) -> Long) {
        if (!available()) return
        val last = current.messages.lastOrNull() ?: return
        if (last.user) return
        val userIndex = current.messages.indexOfLast { it.user }
        if (userIndex < 0) return
        val user = current.messages[userIndex]
        if (user.prompt == null) { notice("This older message cannot be regenerated. Send its text again."); return }
        if (user.agent) runAgentTurn(current.messages.take(userIndex), user.copy(serverId = null), emptyList(), solve)
        else runTurn(current.messages.take(userIndex), user.copy(serverId = null), emptyList(), solve)
    }
    fun edit(id: String, text: String, solve: suspend (PowChallenge) -> Long) {
        if (!available()) return
        val index = current.messages.indexOfFirst { it.id == id && it.user }
        if (index < 0 || text.isBlank()) return
        val user = current.messages[index]
        if (user.prompt == null) { notice("This older message cannot be edited. Send its text again."); return }
        val edited = user.copy(id = UUID.randomUUID().toString(), text = text.trim().take(20_000), prompt = text.trim().take(20_000), serverId = null)
        if (edited.agent) runAgentTurn(current.messages.take(index), edited, emptyList(), solve)
        else runTurn(current.messages.take(index), edited, emptyList(), solve)
    }
    fun messageNeedsConfirmation(id: String? = null): Boolean {
        val user = if (id == null) current.messages.lastOrNull { it.user } else current.messages.firstOrNull { it.id == id && it.user }
        return user?.let { it.searchEnabled || it.refFileIds.isNotEmpty() } == true
    }
    private fun runTurn(prefix: List<WebMessage>, user: WebMessage, files: List<Upload>, solve: suspend (PowChallenge) -> Long) {
        if (session == null) { mutable.update { it.copy(page = AccountPage.LOGIN, signedIn = false) }; return }
        val replyId = UUID.randomUUID().toString()
        val before = current
        val displayedTitle = if (before.messages.isEmpty()) user.text.replace('\n', ' ').take(60) else current.title
        current = current.copy(title = displayedTitle, messages = prefix + user + WebMessage(replyId, false, ""), parentId = user.parentId, blocked = true)
        // Ownership of pending files moves to this job until upload finishes or it is cancelled.
        uploads.clear()
        mutable.update { it.copy(busy = true, draft = "", attachments = emptyList(), title = current.title, conversationId = current.id, messages = displayMessages(), error = null) }
        sending = viewModelScope.launch {
            var receivedId: Long? = null
            try {
                persist()
                val credentials = credentials()
                val remoteId = current.sessionId ?: client.createSession(credentials).also { current = current.copy(sessionId = it) }
                val refs = user.refFileIds + files.map { file ->
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                    client.uploadFile(credentials, file.file, file.name, mime)
                }
                val sent = user.copy(refFileIds = refs)
                current = current.copy(messages = prefix + sent + WebMessage(replyId, false, ""))
                var final: WebReply? = null
                client.complete(credentials, CompletionInput(remoteId, user.parentId, user.prompt.orEmpty(), user.thinkingEnabled, user.searchEnabled, refs), powSolver ?: solve).collect { reply ->
                    final = reply
                    receivedId = reply.responseMessageId ?: receivedId
                    current = current.copy(messages = prefix + sent.copy(serverId = reply.requestMessageId) +
                        WebMessage(replyId, false, reply.response, reply.thinking, serverId = receivedId, parentId = reply.requestMessageId))
                    mutable.update { it.copy(messages = displayMessages()) }
                }
                val completed = final?.takeIf { it.finished && it.responseMessageId != null } ?: throw EndpointChanged()
                current = current.copy(parentId = completed.responseMessageId, blocked = false)
            } catch (cancelled: CancellationException) {
                // Keep the partial reply. A known reply id is a valid predecessor for the next turn.
                current = current.copy(parentId = receivedId ?: user.parentId, blocked = false)
                throw cancelled
            } catch (error: Exception) {
                if (receivedId == null && error !is SessionExpired) {
                    // Failed edits/regenerations do not destroy the old branch; ordinary sends can be retried.
                    current = before.copy(sessionId = current.sessionId)
                    mutable.update { it.copy(draft = if (prefix == before.messages) user.prompt.orEmpty() else it.draft) }
                } else current = current.copy(parentId = receivedId ?: user.parentId, blocked = false)
                reportFailure(error)
            } finally {
                files.forEach { it.file.delete() }
                withContext(NonCancellable) {
                    if (session != null) {
                        try { persist() } catch (_: Exception) { notice("Local history could not be saved. This conversation may be lost when the app closes.") }
                    }
                    mutable.update { it.copy(busy = false, requiresNewChat = current.blocked, title = current.title,
                        messages = displayMessages(), history = summaries()) }
                }
            }
        }
    }
    private fun credentials() = WebCredentials(session ?: throw SessionExpired(), DeepSeekCookies.read())
    private suspend fun reportFailure(error: Exception) {
        when (error) {
            is SessionExpired -> {
                try { expireSession() } catch (_: Exception) { /* expireSession already publishes the safe cleanup state. */ }
            }
            is EndpointChanged, is WebRateLimited, is WebConnectionFailed -> notice(error.message!!)
            else -> notice("The change could not be completed or saved. Please try again.")
        }
    }
    fun stop() { sending?.cancel() }
    private suspend fun persist() {
        val next = (listOf(current) + chats.filterNot { it.id == current.id }).take(100)
        withContext(Dispatchers.IO) { persistence.withLock { history.save(next) } }
        chats = next
    }
    private fun summaries() = chats.map { AccountHistory(it.id, it.title) }
    private fun displayMessages() = current.messages.map { AccountMessage(it.id, it.user, it.text, it.thinking, it.prompt, it.steps, it.artifacts, it.codeRuns) }
    private fun clearUploads() { uploads.forEach { it.file.delete() }; uploads.clear() }
    private fun emptyChat() = WebChat(UUID.randomUUID().toString(), "New Chat")
    private fun chatDir(id: String) = "account/$id"

    /** Workspace file behind an artifact card, or null when it no longer exists. */
    fun artifactFile(artifact: Artifact): File? = runCatching { agentWorkspace.resolve(artifact.path) }.getOrNull()?.takeIf { it.isFile }

    /**
     * Agent turn on account-mode web chat: the same AgentLoop as the API client, with tool calls carried as
     * fenced JSON in the chat (WebToolProtocolClient). Every round trip is a message in this chat's
     * parent chain; tool results go back as the next message. The partial timeline survives Stop and errors.
     */
    private fun runAgentTurn(prefix: List<WebMessage>, user: WebMessage, files: List<Upload>, solve: suspend (PowChallenge) -> Long) {
        if (session == null) { mutable.update { it.copy(page = AccountPage.LOGIN, signedIn = false) }; return }
        val replyId = UUID.randomUUID().toString()
        val before = current
        val displayedTitle = if (before.messages.isEmpty()) user.text.replace('\n', ' ').take(60) else current.title
        current = current.copy(title = displayedTitle, messages = prefix + user + WebMessage(replyId, false, ""), parentId = user.parentId, blocked = true)
        uploads.clear()
        mutable.update { it.copy(busy = true, draft = "", attachments = emptyList(), title = current.title, conversationId = current.id, messages = displayMessages(), error = null) }
        sending = viewModelScope.launch {
            var parent = user.parentId
            var lastSeen: Long? = null
            var answered = false
            var transportFailure: Exception? = null
            var agentError: String? = null
            var sent = user
            var steps = emptyList<TurnStep>()
            val artifacts = mutableListOf<Artifact>()
            fun publish() {
                val answer = steps.filterIsInstance<TurnStep.Answer>().joinToString("") { it.text }.trim()
                val thinking = steps.filterIsInstance<TurnStep.Reasoning>().joinToString("\n\n") { it.text }
                current = current.copy(messages = prefix + sent + WebMessage(replyId, false, answer, thinking, serverId = lastSeen,
                    parentId = sent.serverId, steps = steps, artifacts = artifacts.toList()))
                mutable.update { it.copy(messages = displayMessages()) }
            }
            try {
                persist()
                val credentials = credentials()
                val remoteId = current.sessionId ?: client.createSession(credentials).also { current = current.copy(sessionId = it) }
                val refs = user.refFileIds + files.map { file ->
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                    client.uploadFile(credentials, file.file, file.name, mime)
                }
                sent = user.copy(refFileIds = refs)
                publish()
                val transport = WebChatTransport { prompt, kind ->
                    flow {
                        val first = kind == WebPromptKind.USER
                        var final: WebReply? = null
                        try {
                            client.complete(credentials(), CompletionInput(remoteId, parent, prompt, user.thinkingEnabled, user.searchEnabled && first,
                                if (first) refs else emptyList()), powSolver ?: solve).collect { reply ->
                                final = reply
                                lastSeen = reply.responseMessageId ?: lastSeen
                                if (first && sent.serverId == null && reply.requestMessageId != null) sent = sent.copy(serverId = reply.requestMessageId)
                                emit(reply)
                            }
                        } catch (error: Exception) {
                            if (error !is CancellationException) transportFailure = error
                            throw error
                        }
                        val done = final?.takeIf { it.finished && it.responseMessageId != null }
                        if (done == null) {
                            val error = EndpointChanged()
                            transportFailure = error
                            throw error
                        }
                        parent = done.responseMessageId
                        answered = true
                        current = current.copy(parentId = parent)
                    }
                }
                val toolkit = AgentToolkit.get(getApplication())
                val (tools, skills) = withContext(Dispatchers.IO) { toolkit.tools() to toolkit.enabledSkills() }
                val registry = ToolRegistry(tools)
                val llm = WebToolProtocolClient(transport) { specs -> ToolCallProtocol.systemPrompt(specs, SkillPrompt.summaries(skills)) }
                val loop = AgentLoop(llm, registry, GuardPolicy(registry, agentWorkspace), ToolContext(agentWorkspace, chatDir = chatDir(current.id)),
                    Confirmer { name, arguments -> confirmTool(name, arguments) }, GuardSettings(), maxSteps = 8)
                loop.run(mutableListOf(ChatMessage("user", user.prompt.orEmpty()))).collect { event ->
                    if (event is AgentEvent.StepsUpdated) { steps = event.steps; publish() }
                    if (event is AgentEvent.ArtifactCreated && artifacts.none { it.path == event.artifact.path }) { artifacts += event.artifact; publish() }
                    if (event is AgentEvent.Error) agentError = event.message
                }
                transportFailure?.let { throw it }
                current = current.copy(parentId = parent, blocked = false)
                agentError?.let { notice(it) }
            } catch (cancelled: CancellationException) {
                current = current.copy(parentId = lastSeen ?: parent, blocked = false)
                throw cancelled
            } catch (error: Exception) {
                if (!answered && lastSeen == null && error !is SessionExpired) {
                    current = before.copy(sessionId = current.sessionId)
                    mutable.update { it.copy(draft = if (prefix == before.messages) user.prompt.orEmpty() else it.draft) }
                } else current = current.copy(parentId = lastSeen ?: parent, blocked = false)
                reportFailure(error)
            } finally {
                files.forEach { it.file.delete() }
                withContext(NonCancellable) {
                    if (session != null) {
                        try { persist() } catch (_: Exception) { notice("Local history could not be saved. This conversation may be lost when the app closes.") }
                    }
                    mutable.update { it.copy(busy = false, requiresNewChat = current.blocked, title = current.title,
                        messages = displayMessages(), history = summaries()) }
                }
            }
        }
    }

    /** Per-action approval for network tools and downloads; Stop cancels the wait. */
    private suspend fun confirmTool(name: String, arguments: JsonObject): Boolean = withContext(Dispatchers.Main.immediate) {
        val reply = CompletableDeferred<Boolean>()
        toolReply = reply
        mutable.update { it.copy(confirmation = AgentConfirmation(name, describeTool(name, arguments))) }
        try { reply.await() } finally {
            toolReply = null
            mutable.update { it.copy(confirmation = null) }
        }
    }

    fun answerTool(allow: Boolean) { toolReply?.complete(allow) }

    private fun describeTool(name: String, arguments: JsonObject): String {
        fun text(key: String) = (arguments[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (name) {
            "pip_install" -> "Download these Python packages from PyPI and install them in this app:\n" +
                (arguments["packages"] as? JsonArray).orEmpty().joinToString("\n") { "- " + ((it as? JsonPrimitive)?.contentOrNull ?: it.toString()) }
            "web_fetch" -> "Fetch this web page; its text is then sent to DeepSeek:\n${text("url")}"
            else -> "$name\n${arguments.toString().take(800)}"
        }
    }

    /** "Export as PDF" on an assistant message: the answer is laid out as Markdown and attached as a card. */
    fun exportPdf(id: String) {
        if (!available()) return
        val chatId = current.id
        val message = current.messages.firstOrNull { it.id == id && !it.user && it.text.isNotBlank() } ?: return
        val title = current.title.ifBlank { "DeepSeek answer" }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val artifact = withContext(Dispatchers.IO) {
                    CreatePdfTool().run(buildJsonObject {
                        put("title", title)
                        put("markdown", message.text)
                        put("file_path", "exports/" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(50).ifEmpty { "answer" } + ".pdf")
                    }, ToolContext(agentWorkspace, chatDir = chatDir(chatId))).artifacts.single()
                }
                if (current.id == chatId) {
                    current = current.copy(messages = current.messages.map { if (it.id == id) it.copy(artifacts = (it.artifacts + artifact).distinctBy { a -> a.path }) else it })
                    persist()
                    mutable.update { it.copy(messages = displayMessages()) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("The PDF could not be created.") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    /** Run button on a Python code block: executes on the device in this chat's folder; output and files attach to the message. */
    fun runCode(id: String, code: String) {
        if (!available() || code.isBlank()) return
        val chatId = current.id
        val callId = UUID.randomUUID().toString()
        val argsJson = buildJsonObject { put("code", code) }.toString()
        fun update(step: TurnStep.ToolCall, files: List<Artifact> = emptyList()) {
            if (current.id != chatId) return
            current = current.copy(messages = current.messages.map { message ->
                if (message.id != id) message
                else message.copy(codeRuns = message.codeRuns.filterNot { it.id == callId } + step, artifacts = (message.artifacts + files).distinctBy { it.path })
            })
            mutable.update { it.copy(messages = displayMessages()) }
        }
        update(TurnStep.ToolCall(callId, "run_python", argsJson, ToolStatus.RUNNING))
        mutable.update { it.copy(busy = true) }
        sending = viewModelScope.launch {
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            try {
                val result = RunPythonTool(AgentToolkit.get(getApplication()).python).run(buildJsonObject { put("code", code); put("timeout_s", 60) },
                    ToolContext(agentWorkspace, chatDir = chatDir(chatId)))
                update(TurnStep.ToolCall(callId, "run_python", argsJson, if (result.isError) ToolStatus.ERROR else ToolStatus.DONE,
                    result.content.take(8000), elapsed()), result.artifacts)
            } catch (cancelled: CancellationException) {
                update(TurnStep.ToolCall(callId, "run_python", argsJson, ToolStatus.STOPPED, "Stopped", elapsed()))
                throw cancelled
            } catch (error: Exception) {
                update(TurnStep.ToolCall(callId, "run_python", argsJson, ToolStatus.ERROR, "Python could not run: ${error.message.orEmpty().take(300)}", elapsed()))
            } finally {
                withContext(NonCancellable) {
                    if (session != null) runCatching { persist() }
                    mutable.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun refreshToolsets() {
        viewModelScope.launch {
            try {
                val toolkit = AgentToolkit.get(getApplication())
                val (skills, packages) = withContext(Dispatchers.IO) { toolkit.ensureBuiltIns(); toolkit.skills.list() to toolkit.installer.installed() }
                mutable.update { state -> state.copy(
                    skills = skills.map { SkillRow(it.manifest.name, it.manifest.description, it.manifest.version, it.builtIn, it.enabled, it.manifest.tools.size) },
                    packages = packages.map { PackageRow(it.name, it.version, it.bytes) },
                    bundledPackages = "Built in: Python 3.13 with numpy ${AgentToolkit.BUNDLED["numpy"]} and matplotlib ${AgentToolkit.BUNDLED["matplotlib"]} " +
                        "(plus Pillow, fontTools, contourpy, kiwisolver, cycler, pyparsing, python-dateutil, packaging, six).",
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("Skills and Python packages could not be read.") }
        }
    }

    fun installSkillFromUrl(url: String) = preparePack { SkillPackReader.fromZip(SkillDownloader().download(url.trim())) }

    fun installSkillFromUri(uri: Uri) = preparePack {
        withContext(Dispatchers.IO) {
            val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("The file could not be opened")
            val bytes = input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= SkillPackReader.MAX_ARCHIVE_BYTES) { "Skill archive exceeds 5 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            SkillPackReader.fromZip(bytes)
        }
    }

    private fun preparePack(load: suspend () -> SkillPack) {
        if (state.value.toolsetBusy) return
        mutable.update { it.copy(toolsetBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val pack = load()
                pendingPack = pack
                mutable.update { it.copy(pendingSkill = SkillPreview(pack.manifest.name, pack.manifest.version, pack.manifest.description,
                    pack.manifest.tools.map { tool -> tool.name }, pack.manifest.pip)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice("This skill could not be installed: ${error.message?.take(200) ?: "invalid skill package"}") }
            finally { mutable.update { it.copy(toolsetBusy = false) } }
        }
    }

    fun answerSkillInstall(allow: Boolean) {
        val pack = pendingPack
        pendingPack = null
        mutable.update { it.copy(pendingSkill = null) }
        if (!allow || pack == null) return
        toolsetTask("The skill could not be installed") { toolkit ->
            withContext(Dispatchers.IO) { toolkit.ensureBuiltIns(); toolkit.skills.install(pack) }
            if (pack.manifest.pip.isNotEmpty()) toolkit.installer.install(pack.manifest.pip)
        }
    }

    fun setSkillEnabled(name: String, enabled: Boolean) = toolsetTask("The skill could not be changed") { toolkit ->
        withContext(Dispatchers.IO) { toolkit.skills.setEnabled(name, enabled) }
    }

    fun removeSkill(name: String) = toolsetTask("The skill could not be removed") { toolkit ->
        withContext(Dispatchers.IO) { toolkit.skills.remove(name) }
    }

    fun installPackages(specs: String) {
        val requirements = specs.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        if (requirements.isNotEmpty()) mutable.update { it.copy(pendingPackages = requirements) }
    }

    fun answerPackageInstall(allow: Boolean) {
        val requirements = state.value.pendingPackages
        mutable.update { it.copy(pendingPackages = emptyList()) }
        if (!allow || requirements.isEmpty()) return
        toolsetTask("The packages could not be installed") { toolkit -> notice(toolkit.installer.install(requirements).summary()) }
    }

    fun removePackage(name: String) = toolsetTask("The package could not be removed") { toolkit ->
        withContext(Dispatchers.IO) { toolkit.installer.remove(name) }
    }

    private fun toolsetTask(failure: String, block: suspend (AgentToolkit) -> Unit) {
        if (state.value.toolsetBusy) return
        mutable.update { it.copy(toolsetBusy = true, error = null) }
        viewModelScope.launch {
            try { block(AgentToolkit.get(getApplication())) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice("$failure: ${error.message?.take(200) ?: "unknown error"}") }
            finally {
                mutable.update { it.copy(toolsetBusy = false) }
                refreshToolsets()
            }
        }
    }
    override fun onCleared() { clearUploads(); super.onCleared() }
}
