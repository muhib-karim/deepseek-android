package app.dsandroid.deepseek

import android.app.Application
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dsandroid.BuildConfig
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
import app.dsandroid.core.accounts.AccountFailurePolicy
import app.dsandroid.core.accounts.AccountKind
import app.dsandroid.core.accounts.AccountProfile
import app.dsandroid.core.accounts.AccountRegistry
import app.dsandroid.core.accounts.AccountFailure
import app.dsandroid.core.accounts.AccountType
import app.dsandroid.core.accounts.BalanceReading
import app.dsandroid.core.accounts.BalanceText
import app.dsandroid.core.accounts.DeepSeekBalanceClient
import app.dsandroid.core.accounts.RegisteredAccount
import app.dsandroid.core.deepseek.*
import app.dsandroid.core.guard.GuardPolicy
import app.dsandroid.core.guard.GuardSettings
import app.dsandroid.core.guard.WorkspacePath
import app.dsandroid.core.editor.EditorLanguage
import app.dsandroid.core.llm.ChatMessage
import app.dsandroid.core.llm.ClientIdentity
import app.dsandroid.core.python.PythonOutputText
import app.dsandroid.core.python.RunPythonTool
import app.dsandroid.core.skills.SkillDownloader
import app.dsandroid.core.skills.SkillPack
import app.dsandroid.core.skills.SkillPackReader
import app.dsandroid.core.skills.SkillPrompt
import app.dsandroid.core.tools.Artifact
import app.dsandroid.core.tools.ToolContext
import app.dsandroid.core.tools.ToolRegistry
import app.dsandroid.core.tools.MimeTypes
import app.dsandroid.core.tools.chatRoot
import app.dsandroid.core.tools.uniqueFile
import app.dsandroid.core.workbench.ArtifactKind
import app.dsandroid.core.workbench.ArtifactRevision
import app.dsandroid.core.workbench.VersionOrigin
import app.dsandroid.core.workbench.WorkbenchArtifact
import app.dsandroid.core.workbench.WorkbenchIds
import app.dsandroid.core.workbench.WorkbenchStore
import app.dsandroid.python.AgentToolkit
import app.dsandroid.tools.CreatePdfTool
import app.dsandroid.data.AppSettings
import app.dsandroid.data.Appearance
import app.dsandroid.data.Provider
import app.dsandroid.data.SettingsStore
import app.dsandroid.workbench.EditorFile
import app.dsandroid.workbench.WorkbenchPane
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
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
    apiBaseUrl: String = DeepSeekBalanceClient.DEFAULT_BASE_URL,
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = mutable.asStateFlow()
    private val client = DeepSeekWebClient(origin = baseUrl.toHttpUrl())
    private val balanceClient = DeepSeekBalanceClient(apiBaseUrl, ClientIdentity(BuildConfig.VERSION_NAME, Locale.getDefault().toLanguageTag(),
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000))
    private var session: SessionToken? = null
    private var rejectedToken: String? = null
    private var store: DeepSeekSessionStore? = null
    private var settingsStore: SettingsStore? = null
    /** The DeepSeek account whose session, chats and files are loaded; null while an API-key profile or no account is active. */
    private var webAccount: String? = null
    private var registry = AccountRegistry()
    private var balances = emptyMap<String, BalanceReading>()
    private var addingAccount = false
    private var history = historyFor(DeepSeekSessionStore.DEFAULT_SLOT)
    private var workbench = workbenchFor(DeepSeekSessionStore.DEFAULT_SLOT)
    /** Unsaved editor and panel text by buffer key: survives rotation, not process death. */
    private val drafts = LinkedHashMap<String, String>()
    private val appearancePrefs = application.getSharedPreferences("deepseek_web_appearance", 0)
    private val persistence = Mutex()
    private var chats = emptyList<WebChat>()
    private var current = emptyChat()
    private val uploads = mutableListOf<Upload>()
    private var sending: Job? = null
    private var importing: Job? = null
    private var sessionChanging = false
    /** Agent files live under workspace/account/<chat id> (accounts/<id>/<chat id> for added accounts); the FileProvider exposes workspace/. */
    val agentWorkspace = WorkspacePath(File(application.filesDir, "workspace"))
    private var toolReply: CompletableDeferred<Boolean>? = null
    private var pendingPack: SkillPack? = null

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val sessions = DeepSeekSessionStore(application).also { store = it }
                    val settings = SettingsStore(application).also { settingsStore = it }.load()
                    val accounts = reconcile(sessions.registry() ?: migrateRegistry(sessions), settings)
                    sessions.saveRegistry(accounts)
                    val web = accounts.active?.takeIf { it.type == AccountType.DEEPSEEK_WEB }?.id
                    Loaded(accounts, sessions.balances(), web, web?.let { sessions.load(it) }, web?.let { historyFor(it).load() }.orEmpty(), settings)
                }
                registry = loaded.registry
                balances = loaded.balances
                selectWebAccount(loaded.web)
                session = loaded.session
                chats = loaded.chats
                val expired = session?.isExpired(System.currentTimeMillis()) == true
                val appearance = runCatching { Appearance.valueOf(appearancePrefs.getString("theme", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM)
                val agent = appearancePrefs.getBoolean("agent_mode", false)
                mutable.update { it.copy(appearance = appearance, hasApiKey = loaded.settings.apiKey.isNotBlank(), agent = agent, textScale = loaded.settings.fontScale,
                    apiKeyProfile = apiProfileOf(loaded.settings)) }
                if (expired) expireSession()
                else {
                    if (session == null && webAccount != null) {
                        chats = emptyList()
                        withContext(Dispatchers.IO) { history.clear() }
                    }
                    publishAccounts()
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
        if (page != AccountPage.LOGIN) addingAccount = false
        mutable.update { it.copy(page = page, error = null, offerSwitchAccount = false) }
        if (page == AccountPage.SKILLS) refreshToolsets()
        if (page == AccountPage.ACCOUNTS) viewModelScope.launch { refreshApiKey() }
    }
    fun setAgent(enabled: Boolean) {
        appearancePrefs.edit().putBoolean("agent_mode", enabled).apply()
        mutable.update { it.copy(agent = enabled) }
    }
    fun draft(value: String) { mutable.update { it.copy(draft = value.take(20_000)) } }
    fun notice(message: String) = notice(message, offerSwitchAccount = false)
    fun dismissNotice() { mutable.update { it.copy(error = null, offerSwitchAccount = false) } }
    private fun notice(message: String, offerSwitchAccount: Boolean) { mutable.update { it.copy(error = message, offerSwitchAccount = offerSwitchAccount) } }
    fun appearance(value: Appearance) {
        appearancePrefs.edit().putString("theme", value.name).apply()
        mutable.update { it.copy(appearance = value) }
    }
    /** Re-reads the API settings (they change in the API-key client) and the API-key profiles in the account list. */
    suspend fun refreshApiKey() {
        try {
            val config = withContext(Dispatchers.IO) { settingsStore?.load() }
            if (config == null) { mutable.update { it.copy(hasApiKey = false) }; return }
            val next = reconcile(registry, config)
            if (next != registry) {
                withContext(Dispatchers.IO) { store?.saveRegistry(next) }
                registry = next
            }
            mutable.update { it.copy(hasApiKey = config.apiKey.isNotBlank(), apiKeyProfile = apiProfileOf(config), textScale = config.fontScale) }
            publishAccounts()
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
                val next = withContext(Dispatchers.IO) {
                    val settings = settingsStore ?: throw IllegalStateException()
                    val config = settings.load()
                    // The Settings key is also the "API key" profile in Settings > Accounts; saving it never changes the active account.
                    val others = config.accounts.filterNot { it.id == QUICK_KEY_ID }
                    val label = config.accounts.firstOrNull { it.id == QUICK_KEY_ID }?.label ?: "API key"
                    val accounts = if (key.isBlank()) others else others + AccountProfile(QUICK_KEY_ID, label, AccountKind.API_KEY, key)
                    val activeProfile = when {
                        key.isNotBlank() -> QUICK_KEY_ID
                        config.activeAccountId == QUICK_KEY_ID -> null
                        else -> config.activeAccountId
                    }
                    // Use the existing encrypted API settings; no account token is ever an API key.
                    val saved = config.copy(apiKey = key, provider = Provider.API_KEY, baseUrl = "https://api.deepseek.com", accounts = accounts, activeAccountId = activeProfile)
                    settings.save(saved)
                    reconcile(registry, saved).also { store?.saveRegistry(it) } to apiProfileOf(saved)
                }
                registry = next.first
                publishAccounts()
                mutable.update { it.copy(hasApiKey = key.isNotBlank(), apiKeyProfile = next.second, error = null) }
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
            val sessions = store ?: withContext(Dispatchers.IO) { DeepSeekSessionStore(getApplication()) }.also { store = it }
            val target = registry.active?.takeIf { !addingAccount && it.type == AccountType.DEEPSEEK_WEB }
            if (target != null) {
                // Signing in again keeps the active account's slot. A different token may be another person, so its chats start empty.
                val next = registry.markSignedIn(target.id, true)
                withContext(Dispatchers.IO) {
                    if (session?.bearer() != token.bearer()) persistence.withLock { history.clear() }
                    sessions.save(target.id, token)
                    sessions.saveRegistry(next)
                }
                registry = next
                if (session?.bearer() != token.bearer()) { chats = emptyList(); current = emptyChat(); clearUploads() }
                session = token
            } else {
                val id = if (registry.account(DeepSeekSessionStore.DEFAULT_SLOT) == null) DeepSeekSessionStore.DEFAULT_SLOT else "web-" + UUID.randomUUID()
                val account = RegisteredAccount(id, AccountRegistry.nextLabel(AccountType.DEEPSEEK_WEB, registry), AccountType.DEEPSEEK_WEB, System.currentTimeMillis())
                // A new account is active only when none was; adding never switches away from the account in use.
                val next = registry.add(account)
                withContext(Dispatchers.IO) {
                    persistence.withLock { historyFor(id).clear() }
                    workbenchFor(id).clear()
                    sessions.save(id, token)
                    sessions.saveRegistry(next)
                }
                registry = next
                addingAccount = false
                if (next.activeId != id) {
                    // The account in use stays; the new sign-in's cookies must not travel with its token.
                    DeepSeekCookies.clear()
                    publishAccounts()
                    mutable.update { it.copy(page = AccountPage.ACCOUNTS, error = "Added ${account.label}. Tap it to switch.", offerSwitchAccount = false) }
                    return true
                }
                selectWebAccount(id)
                session = token
                chats = emptyList(); current = emptyChat(); clearUploads()
            }
            publishAccounts()
            mutable.update { it.copy(signedIn = true, logoutPending = false, page = AccountPage.CHAT, error = null, offerSwitchAccount = false,
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
                val id = webAccount
                clearSessionData()
                // Logging out removes this account from the list; no other account is selected in its place.
                if (id != null) {
                    val next = registry.remove(id)
                    withContext(Dispatchers.IO) { store?.saveRegistry(next) }
                    registry = next
                    selectWebAccount(null)
                }
                rejectedToken = null
                mutable.update { AccountUiState(ready = true, appearance = it.appearance, hasApiKey = it.hasApiKey, textScale = it.textScale, page = AccountPage.LOGIN) }
                publishAccounts()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                session = null
                mutable.update { it.copy(signedIn = false, logoutPending = true, error = "Logout could not fully clear local data. Close and reopen the app, then retry logout.") }
            } finally { sessionChanging = false; mutable.update { it.copy(busy = false) } }
        }
    }
    /** Clears the active DeepSeek account's session, chats, agent files and artifact versions, then the browser's cookies. */
    private suspend fun clearSessionData() {
        val id = webAccount
        session = null
        chats = emptyList(); current = emptyChat(); clearUploads()
        drafts.clear()
        if (id != null) withContext(Dispatchers.IO) { wipeWebAccount(id) }
        DeepSeekCookies.clear()
    }
    private suspend fun wipeWebAccount(id: String) {
        store?.remove(id)
        persistence.withLock { historyFor(id).clear() }
        File(agentWorkspace.root, workspaceRoot(id)).deleteRecursively()
        workbenchFor(id).clear()
    }
    private suspend fun expireSession() {
        rejectedToken = session?.bearer()
        sessionChanging = true
        var cleared = false
        try {
            clearSessionData(); cleared = true
            // The expired account stays selected and is marked signed out; signing in again restores it.
            val outcome = AccountFailurePolicy.handle(registry, AccountFailure.SIGNED_OUT, SessionExpired().message.orEmpty())
            if (outcome.registry != registry) {
                withContext(Dispatchers.IO) { store?.saveRegistry(outcome.registry) }
                registry = outcome.registry
            }
        } finally {
            mutable.update { AccountUiState(ready = true, appearance = it.appearance, hasApiKey = it.hasApiKey, textScale = it.textScale,
                page = AccountPage.LOGIN, error = SessionExpired().message, logoutPending = !cleared) }
            publishAccounts()
            sessionChanging = false
        }
    }
    fun newChat() {
        if (!available()) return
        clearUploads(); current = emptyChat()
        mutable.update { it.copy(page = AccountPage.CHAT, title = current.title, conversationId = current.id, messages = emptyList(), draft = "", attachments = emptyList(),
            requiresNewChat = false, error = null, offerSwitchAccount = false, pane = null, chatFiles = null, editorOutput = null) }
    }
    fun open(id: String) {
        if (!available()) return
        current = chats.firstOrNull { it.id == id } ?: return
        clearUploads()
        mutable.update { it.copy(page = AccountPage.CHAT, title = current.title, conversationId = current.id, messages = displayMessages(), draft = "", attachments = emptyList(),
            requiresNewChat = current.blocked, error = null, offerSwitchAccount = false, pane = null, chatFiles = null, editorOutput = null) }
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
                    if (delete) { File(agentWorkspace.root, chatDir(id)).deleteRecursively(); workbench.deleteChat(id) }
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
    /** [keepDraft] leaves the composer alone (artifact revisions are not typed there). */
    private fun runTurn(prefix: List<WebMessage>, user: WebMessage, files: List<Upload>, solve: suspend (PowChallenge) -> Long, keepDraft: Boolean = false) {
        if (session == null) { mutable.update { it.copy(page = AccountPage.LOGIN, signedIn = false) }; return }
        val replyId = UUID.randomUUID().toString()
        val before = current
        val displayedTitle = if (before.messages.isEmpty()) user.text.replace('\n', ' ').take(60) else current.title
        current = current.copy(title = displayedTitle, messages = prefix + user + WebMessage(replyId, false, "", revisionOf = user.revisionOf), parentId = user.parentId, blocked = true)
        // Ownership of pending files moves to this job until upload finishes or it is cancelled.
        uploads.clear()
        mutable.update { it.copy(busy = true, draft = if (keepDraft) it.draft else "", attachments = emptyList(), title = current.title, conversationId = current.id,
            messages = displayMessages(), error = null, offerSwitchAccount = false) }
        sending = viewModelScope.launch {
            var receivedId: Long? = null
            var revisionReply: String? = null
            try {
                persist()
                val credentials = credentials()
                val remoteId = current.sessionId ?: client.createSession(credentials).also { current = current.copy(sessionId = it) }
                val refs = user.refFileIds + files.map { file ->
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                    client.uploadFile(credentials, file.file, file.name, mime)
                }
                val sent = user.copy(refFileIds = refs)
                current = current.copy(messages = prefix + sent + WebMessage(replyId, false, "", revisionOf = user.revisionOf))
                var final: WebReply? = null
                client.complete(credentials, CompletionInput(remoteId, user.parentId, user.prompt.orEmpty(), user.thinkingEnabled, user.searchEnabled, refs), powSolver ?: solve).collect { reply ->
                    final = reply
                    receivedId = reply.responseMessageId ?: receivedId
                    current = current.copy(messages = prefix + sent.copy(serverId = reply.requestMessageId) +
                        WebMessage(replyId, false, reply.response, reply.thinking, serverId = receivedId, parentId = reply.requestMessageId, revisionOf = user.revisionOf))
                    mutable.update { it.copy(messages = displayMessages()) }
                }
                val completed = final?.takeIf { it.finished && it.responseMessageId != null } ?: throw EndpointChanged()
                current = current.copy(parentId = completed.responseMessageId, blocked = false)
                revisionReply = completed.response
            } catch (cancelled: CancellationException) {
                // Keep the partial reply. A known reply id is a valid predecessor for the next turn.
                current = current.copy(parentId = receivedId ?: user.parentId, blocked = false)
                throw cancelled
            } catch (error: Exception) {
                if (receivedId == null && error !is SessionExpired) {
                    // Failed edits/regenerations do not destroy the old branch; ordinary sends can be retried.
                    current = before.copy(sessionId = current.sessionId)
                    mutable.update { it.copy(draft = if (prefix == before.messages && !keepDraft) user.prompt.orEmpty() else it.draft) }
                } else current = current.copy(parentId = receivedId ?: user.parentId, blocked = false)
                reportFailure(error)
            } finally {
                files.forEach { it.file.delete() }
                withContext(NonCancellable) {
                    if (session != null) {
                        try { persist() } catch (_: Exception) { notice("Local history could not be saved. This conversation may be lost when the app closes.") }
                    }
                    user.revisionOf?.let { finishRevision(it, revisionReply) }
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
            is EndpointChanged, is WebRateLimited, is WebConnectionFailed -> {
                // Quota and rate errors keep the account in use; the banner can only open the account list (docs/ACCOUNTS.md).
                val outcome = AccountFailurePolicy.handle(registry, AccountFailurePolicy.fromError(error), error.message.orEmpty())
                registry = outcome.registry
                notice(outcome.message, outcome.offerSwitchAccount)
            }
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
    private fun displayMessages() = current.messages.map { AccountMessage(it.id, it.user, it.text, it.thinking, it.prompt, it.steps, it.artifacts, it.codeRuns, it.revisionOf) }
    private fun clearUploads() { uploads.forEach { it.file.delete() }; uploads.clear() }
    private fun emptyChat() = WebChat(UUID.randomUUID().toString(), "New Chat")
    /** Each DeepSeek account keeps its own chats, agent files and artifact versions; the first one uses the original paths. */
    private fun historyFor(id: String) = WebHistory(File(getApplication<Application>().filesDir,
        if (id == DeepSeekSessionStore.DEFAULT_SLOT) "deepseek-web-chats.json" else "deepseek-web-chats-$id.json"))
    private fun workbenchFor(id: String) = WorkbenchStore(File(getApplication<Application>().filesDir, "workbench/$id"))
    private fun workspaceRoot(id: String) = if (id == DeepSeekSessionStore.DEFAULT_SLOT) "account" else "accounts/$id"
    private fun chatDir(id: String) = "${workspaceRoot(webAccount ?: DeepSeekSessionStore.DEFAULT_SLOT)}/$id"
    private fun selectWebAccount(id: String?) {
        webAccount = id
        history = historyFor(id ?: DeepSeekSessionStore.DEFAULT_SLOT)
        workbench = workbenchFor(id ?: DeepSeekSessionStore.DEFAULT_SLOT)
    }

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
    // ---- Account manager: Settings > Accounts (docs/ACCOUNTS.md). Only switchAccount changes the account in use. ----

    private class Loaded(val registry: AccountRegistry, val balances: Map<String, BalanceReading>, val web: String?, val session: SessionToken?,
        val chats: List<WebChat>, val settings: AppSettings)

    /** Installs from before v0.7.0 have one session in the original slot; it becomes the first, active account. */
    private fun migrateRegistry(sessions: DeepSeekSessionStore): AccountRegistry =
        if (sessions.load(DeepSeekSessionStore.DEFAULT_SLOT) == null) AccountRegistry()
        else AccountRegistry().add(RegisteredAccount(DeepSeekSessionStore.DEFAULT_SLOT, "DeepSeek account", AccountType.DEEPSEEK_WEB, System.currentTimeMillis()))

    /** API-key profiles live in the encrypted API settings (shared with the API-key client); keep the list in step with them. */
    private fun reconcile(saved: AccountRegistry, settings: AppSettings): AccountRegistry {
        val web = saved.accounts.filter { it.type == AccountType.DEEPSEEK_WEB }
        val keys = settings.accounts.filter { it.kind == AccountKind.API_KEY }.map { profile ->
            saved.account(profile.id)?.takeIf { it.type == AccountType.API_KEY }?.copy(label = profile.label)
                ?: RegisteredAccount(profile.id, profile.label, AccountType.API_KEY, System.currentTimeMillis())
        }
        val all = (web + keys).distinctBy { it.id }.take(AccountRegistry.MAX_ACCOUNTS)
        return AccountRegistry(all, saved.activeId?.takeIf { id -> all.any { it.id == id } })
    }

    private fun apiProfileOf(config: AppSettings): String? =
        config.activeAccountId?.takeIf { id -> config.accounts.any { it.id == id && it.kind == AccountKind.API_KEY } }

    private fun publishAccounts() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val rows = registry.accounts.map { account ->
            val reading = balances[account.id]?.takeIf { account.type == AccountType.API_KEY }
            AccountRow(account.id, account.label, account.type, account.id == registry.activeId, account.signedIn,
                BalanceText.summary(account.type, reading), BalanceText.readAt(reading, now, zone))
        }
        mutable.update { it.copy(accounts = rows) }
    }

    private suspend fun settings(): SettingsStore = settingsStore ?: withContext(Dispatchers.IO) { SettingsStore(getApplication()) }.also { settingsStore = it }

    /** One tap in Settings > Accounts. The previous account keeps its credentials and chats; its cookies are cleared. */
    fun switchAccount(id: String) {
        if (!available()) return
        val target = registry.account(id) ?: return
        if (id == registry.activeId) {
            val page = if (target.type == AccountType.DEEPSEEK_WEB && session == null) AccountPage.LOGIN else AccountPage.CHAT
            mutable.update { it.copy(page = page, error = null, offerSwitchAccount = false) }
            return
        }
        sessionChanging = true
        mutable.update { it.copy(busy = true, error = null, offerSwitchAccount = false) }
        viewModelScope.launch {
            try {
                val next = registry.switchTo(id)
                if (target.type == AccountType.DEEPSEEK_WEB) {
                    val sessions = store ?: throw IllegalStateException("Session store unavailable")
                    val (token, loaded) = withContext(Dispatchers.IO) { sessions.load(id) to historyFor(id).load() }
                    // Cookies belong to the previous sign-in; requests now carry only this account's token.
                    DeepSeekCookies.clear()
                    withContext(Dispatchers.IO) { sessions.saveRegistry(next) }
                    registry = next
                    clearUploads()
                    selectWebAccount(id)
                    session = token?.takeUnless { it.isExpired(System.currentTimeMillis()) }
                    chats = if (session != null) loaded else emptyList()
                    current = emptyChat()
                    rejectedToken = null
                    mutable.update { it.copy(signedIn = session != null, page = if (session != null) AccountPage.CHAT else AccountPage.LOGIN,
                        title = current.title, conversationId = current.id, messages = emptyList(), history = summaries(), draft = "",
                        attachments = emptyList(), requiresNewChat = false, pane = null, chatFiles = null) }
                } else {
                    val settings = settings()
                    val hasKey = withContext(Dispatchers.IO) {
                        val config = settings.load()
                        val profile = config.accounts.firstOrNull { it.id == id && it.kind == AccountKind.API_KEY } ?: throw IllegalStateException("Profile missing")
                        // The same fields the API-key client's own account picker sets; its chats are scoped to this profile.
                        settings.save(config.copy(provider = Provider.API_KEY, baseUrl = "https://api.deepseek.com", apiKey = profile.credential,
                            activeAccountId = id, accountBinding = id, cachedModels = emptyList(), modelCacheKey = "", model = ""))
                        store?.saveRegistry(next)
                        profile.credential.isNotBlank()
                    }
                    registry = next
                    clearUploads()
                    selectWebAccount(null)
                    session = null; chats = emptyList(); current = emptyChat()
                    mutable.update { it.copy(hasApiKey = hasKey, apiKeyProfile = id, signedIn = false, page = AccountPage.CHAT, title = current.title, conversationId = current.id,
                        messages = emptyList(), history = emptyList(), draft = "", attachments = emptyList(), requiresNewChat = false, pane = null, chatFiles = null) }
                }
                publishAccounts()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("The account could not be switched. Try again.") }
            finally {
                sessionChanging = false
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    /** "Add DeepSeek account": the sign-in page opens signed out so the account in use is not captured again. */
    fun startAddAccount(prepareBrowser: () -> Unit) {
        if (!available()) return
        if (registry.accounts.size >= AccountRegistry.MAX_ACCOUNTS) { notice("You can keep up to ${AccountRegistry.MAX_ACCOUNTS} accounts. Remove one first."); return }
        sessionChanging = true
        viewModelScope.launch {
            try {
                DeepSeekCookies.clear()
                prepareBrowser()
                addingAccount = true
                mutable.update { it.copy(page = AccountPage.LOGIN, error = null, offerSwitchAccount = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("The sign-in page could not be prepared. Try again.") }
            finally { sessionChanging = false }
        }
    }

    /** A new API-key profile is stored encrypted and listed; it is not selected until the user taps it. */
    fun addApiKeyProfile(label: String, key: String) {
        if (!available()) return
        val credential = key.trim()
        if (credential.isEmpty() || credential.length > 16_384 || credential.any { it.code !in 33..126 }) { notice("Enter a valid API key without spaces."); return }
        if (registry.accounts.size >= AccountRegistry.MAX_ACCOUNTS) { notice("You can keep up to ${AccountRegistry.MAX_ACCOUNTS} accounts. Remove one first."); return }
        val name = label.trim().ifEmpty { AccountRegistry.nextLabel(AccountType.API_KEY, registry) }.take(AccountRegistry.MAX_LABEL)
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val next = registry.add(RegisteredAccount(id, name, AccountType.API_KEY, System.currentTimeMillis()))
                val settings = settings()
                withContext(Dispatchers.IO) {
                    val config = settings.load()
                    settings.save(config.copy(accounts = config.accounts + AccountProfile(id, name, AccountKind.API_KEY, credential)))
                    store?.saveRegistry(next)
                }
                registry = next
                publishAccounts()
                mutable.update { it.copy(error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("The API-key profile could not be saved securely. Try again.") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    /** Removes an account and its credentials. Removing the account in use leaves no account selected. */
    fun removeAccount(id: String) {
        if (!available()) return
        val account = registry.account(id) ?: return
        if (account.type == AccountType.DEEPSEEK_WEB && id == webAccount) { logout(); return }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val next = registry.remove(id)
                val remaining = balances - id
                val settings = settings()
                val apiState = withContext(Dispatchers.IO) {
                    if (account.type == AccountType.DEEPSEEK_WEB) wipeWebAccount(id)
                    val config = settings.load()
                    val kept = config.accounts.filterNot { it.id == id }
                    val saved = if (config.activeAccountId == id) config.copy(accounts = kept, activeAccountId = null, apiKey = "",
                        accountBinding = UUID.randomUUID().toString(), cachedModels = emptyList(), modelCacheKey = "") else config.copy(accounts = kept)
                    if (account.type == AccountType.API_KEY) settings.save(saved)
                    store?.saveRegistry(next)
                    store?.saveBalances(remaining)
                    saved.apiKey.isNotBlank() to apiProfileOf(saved)
                }
                registry = next
                balances = remaining
                mutable.update { it.copy(hasApiKey = apiState.first, apiKeyProfile = apiState.second) }
                publishAccounts()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("The account could not be removed. Try again.") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun renameAccount(id: String, label: String) {
        if (!available()) return
        val next = try { registry.rename(id, label) } catch (_: IllegalArgumentException) { notice("Use a label of 1 to ${AccountRegistry.MAX_LABEL} characters."); return }
        val renamed = next.account(id) ?: return
        viewModelScope.launch {
            try {
                val settings = settings()
                withContext(Dispatchers.IO) {
                    if (renamed.type == AccountType.API_KEY) {
                        val config = settings.load()
                        settings.save(config.copy(accounts = config.accounts.map { if (it.id == id) it.copy(label = renamed.label) else it }))
                    }
                    store?.saveRegistry(next)
                }
                registry = next
                publishAccounts()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("The account could not be renamed. Try again.") }
        }
    }

    /** Reads GET /user/balance for one API-key profile, or all of them, only when the user asks. */
    fun refreshBalances(id: String? = null) {
        if (state.value.balanceBusy) return
        mutable.update { it.copy(balanceBusy = true) }
        viewModelScope.launch {
            try {
                val settings = settings()
                val profiles = withContext(Dispatchers.IO) { settings.load().accounts }
                    .filter { it.kind == AccountKind.API_KEY && (id == null || it.id == id) && registry.account(it.id) != null }
                for (profile in profiles) {
                    val reading = try { balanceClient.read(profile.credential) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { BalanceReading(System.currentTimeMillis(), error = "This key could not be checked.") }
                    balances = balances + (profile.id to reading)
                    publishAccounts()
                }
                withContext(Dispatchers.IO) { store?.saveBalances(balances) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("Balances could not be read or saved. Try again.") }
            finally { mutable.update { it.copy(balanceBusy = false) } }
        }
    }

    // ---- Workbench: artifacts panel and editor ----

    fun draftText(key: String): String? = drafts[key]
    fun saveDraft(key: String, text: String?) {
        if (text == null) { drafts.remove(key); return }
        drafts.remove(key)
        drafts[key] = text
        while (drafts.size > 20) drafts.remove(drafts.keys.first())
    }
    fun closePane() { mutable.update { it.copy(pane = null, editorOutput = null) } }

    /** "Preview" on an HTML, SVG or Markdown code block. A revision reply reopens the artifact it revised. */
    fun previewCode(messageId: String, revisionOf: String?, language: String, code: String) {
        val kind = ArtifactKind.fromFence(language, code) ?: return
        val chatId = current.id
        val bench = workbench
        viewModelScope.launch {
            try {
                val artifact = withContext(Dispatchers.IO) {
                    revisionOf?.let { bench.load(it) }?.takeIf { it.chatId == chatId } ?: run {
                        val id = WorkbenchIds.forFence(chatId, messageId, language, code)
                        bench.load(id) ?: WorkbenchArtifact.create(id, chatId, "", kind, code, VersionOrigin.ANSWER, System.currentTimeMillis()).also(bench::save)
                    }
                }
                mutable.update { it.copy(pane = WorkbenchPane.Panel(artifact), editorOutput = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("This artifact could not be opened.") }
        }
    }

    /** "Edit" on any other code block: a buffer in the editor, saved into this chat's folder on Save. */
    fun editCode(language: String, code: String) {
        val editorLanguage = EditorLanguage.forFence(language) ?: return
        val key = "code-" + WorkbenchIds.forFence(current.id, "editor", language, code)
        mutable.update { it.copy(pane = WorkbenchPane.Editor(EditorFile(key, editorLanguage.fileName("snippet"), code, editorLanguage, null, current.id)), editorOutput = null) }
    }

    /** Agent files: HTML, SVG and Markdown open in the panel (a rewritten file becomes a new version); returns false for others. */
    fun openArtifactFile(artifact: Artifact): Boolean {
        val kind = ArtifactKind.fromFileName(artifact.fileName) ?: return false
        val chatId = current.id
        val bench = workbench
        viewModelScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    val file = artifactFile(artifact) ?: throw IllegalStateException("Missing file")
                    require(file.length() <= WorkbenchArtifact.MAX_SOURCE_BYTES)
                    val text = file.readText(Charsets.UTF_8)
                    val id = WorkbenchIds.forFile(chatId, artifact.path)
                    val stored = bench.load(id)
                    when {
                        stored == null -> WorkbenchArtifact.create(id, chatId, artifact.fileName, kind, text, VersionOrigin.AGENT_FILE, System.currentTimeMillis(),
                            filePath = artifact.path).also(bench::save)
                        stored.versions.none { it.source == text } -> stored.withVersion(text, VersionOrigin.AGENT_FILE, System.currentTimeMillis()).also(bench::save)
                        else -> stored
                    }
                }
                mutable.update { it.copy(pane = WorkbenchPane.Panel(opened), editorOutput = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("This file could not be opened in the artifacts panel.") }
        }
        return true
    }

    /** "Edit" on a text file card or in Files in this chat: the workspace file itself, saved in place. */
    fun editArtifactFile(artifact: Artifact) {
        val language = EditorLanguage.forFileName(artifact.fileName) ?: return notice("This file type cannot be edited here.")
        val chatId = current.id
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val file = artifactFile(artifact) ?: throw IllegalStateException("Missing file")
                    require(file.length() <= MAX_EDITOR_BYTES)
                    file.readText(Charsets.UTF_8)
                }
                mutable.update { it.copy(pane = WorkbenchPane.Editor(EditorFile("file-" + artifact.path, artifact.fileName, text, language, artifact.path, chatId)),
                    editorOutput = null, chatFiles = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("This file could not be opened in the editor (text files up to 1 MB).") }
        }
    }

    /** Run in the panel: edited source becomes the next version. */
    fun runArtifact(id: String, source: String) {
        val bench = workbench
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    val artifact = bench.load(id) ?: throw IllegalStateException("Missing artifact")
                    artifact.withVersion(source, VersionOrigin.EDIT, System.currentTimeMillis()).also { if (it != artifact) bench.save(it) }
                }
                drafts.remove(id)
                mutable.update { state ->
                    val pane = state.pane as? WorkbenchPane.Panel
                    state.copy(pane = if (pane?.artifact?.id == id) pane.copy(artifact = updated) else state.pane)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) { notice(error.message ?: "This version could not be saved.") }
            catch (_: Exception) { notice("This version could not be saved.") }
        }
    }

    /**
     * "Ask DeepSeek to change this": the edited source (saved first as a version) and the instruction go to DeepSeek as
     * the next message in this chat; the code block in its reply becomes the next version (finishRevision).
     */
    fun askArtifactChange(id: String, baseVersion: Int, source: String, instruction: String, solve: suspend (PowChallenge) -> Long) {
        if (!available() || current.blocked) { notice("Wait for the current reply to finish, then try again."); return }
        if (uploads.isNotEmpty()) { notice("Send or remove the attached files first."); return }
        if (session == null) { mutable.update { it.copy(page = AccountPage.LOGIN, signedIn = false) }; return }
        val chatId = current.id
        val bench = workbench
        viewModelScope.launch {
            try {
                val (artifact, base) = withContext(Dispatchers.IO) {
                    val stored = bench.load(id)?.takeIf { it.chatId == chatId } ?: throw IllegalStateException("Missing artifact")
                    val edited = stored.withVersion(source, VersionOrigin.EDIT, System.currentTimeMillis())
                    if (edited != stored) bench.save(edited)
                    edited to (if (edited != stored) edited.latest.number else baseVersion)
                }
                val prompt = ArtifactRevision.prompt(artifact, base, source, instruction)
                val display = ArtifactRevision.display(artifact, base, instruction)
                if (!available() || current.id != chatId || current.blocked) { notice("Wait for the current reply to finish, then try again."); return@launch }
                drafts.remove(id)
                mutable.update { it.copy(pane = WorkbenchPane.Panel(artifact, revising = true)) }
                val user = WebMessage(UUID.randomUUID().toString(), true, display, parentId = current.parentId, prompt = prompt, revisionOf = id)
                runTurn(current.messages, user, emptyList(), solve, keepDraft = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) { notice(error.message ?: "Describe the change, then try again.") }
            catch (_: Exception) { notice("This artifact is no longer available.") }
        }
    }

    private suspend fun finishRevision(id: String, reply: String?) {
        var problem: String? = null
        val bench = workbench
        val updated = try {
            withContext(Dispatchers.IO) {
                val artifact = bench.load(id) ?: return@withContext null
                val source = reply?.let { ArtifactRevision.extract(it, artifact.kind) }
                if (reply != null && source == null) problem = "DeepSeek's reply has no complete ${artifact.kind.label} code block, so no new version was made. The reply is in the chat."
                if (source == null) artifact
                else artifact.withVersion(source, VersionOrigin.DEEPSEEK, System.currentTimeMillis(), note = "Asked DeepSeek").also { if (it != artifact) bench.save(it) }
            }
        } catch (_: Exception) { problem = "DeepSeek's new version could not be saved."; null }
        mutable.update { state ->
            val pane = state.pane as? WorkbenchPane.Panel
            if (pane?.artifact?.id != id) state
            else state.copy(pane = WorkbenchPane.Panel(updated ?: pane.artifact, revising = false))
        }
        problem?.let { notice(it) }
    }

    /** Editor Save: back into its workspace file, or a new file in this chat's folder for a code block. */
    fun saveEditor(text: String) {
        val file = (state.value.pane as? WorkbenchPane.Editor)?.file ?: return
        if (text.toByteArray(Charsets.UTF_8).size > MAX_EDITOR_BYTES) { notice("This file is larger than 1 MB; use Save as instead."); return }
        val chatDirectory = chatDir(file.chatId)
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    val target = if (file.path != null) agentWorkspace.resolve(file.path)
                    else uniqueFile(File(ToolContext(agentWorkspace, chatDir = chatDirectory).chatRoot(), file.name))
                    require(agentWorkspace.contains(target) && !target.isDirectory)
                    val temporary = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.tmp")
                    try {
                        temporary.writeText(text, Charsets.UTF_8)
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } finally { temporary.delete() }
                    agentWorkspace.relative(target)
                }
                drafts.remove(file.key)
                mutable.update { state ->
                    val pane = state.pane as? WorkbenchPane.Editor
                    if (pane?.file?.key != file.key) state
                    else state.copy(pane = WorkbenchPane.Editor(file.copy(text = text, path = path, name = File(path).name)))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("The file could not be saved.") }
        }
    }

    /** Run on a Python file: the chat's on-device run_python worker; output appears under the editor. */
    fun runEditorPython(code: String) {
        val file = (state.value.pane as? WorkbenchPane.Editor)?.file ?: return
        if (state.value.editorBusy || code.isBlank()) return
        val chatDirectory = chatDir(file.chatId)
        mutable.update { it.copy(editorBusy = true, editorOutput = null) }
        viewModelScope.launch {
            try {
                val result = RunPythonTool(AgentToolkit.get(getApplication()).python).run(buildJsonObject { put("code", code); put("timeout_s", 60) },
                    ToolContext(agentWorkspace, chatDir = chatDirectory))
                mutable.update { it.copy(editorOutput = PythonOutputText.format(result.content)) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(editorOutput = "Stopped.") }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(editorOutput = "Python could not run: ${error.message.orEmpty().take(300)}") }
            } finally { mutable.update { it.copy(editorBusy = false) } }
        }
    }

    /** Files in this chat's folder (agent files, saved editor files), for the editor and the panel. */
    fun showChatFiles() {
        if (!available()) return
        val root = File(agentWorkspace.root, chatDir(current.id))
        viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    if (!root.isDirectory) emptyList()
                    else root.walkTopDown().maxDepth(4).onEnter { it == root || !it.name.startsWith(".") }
                        .filter { it.isFile && !it.name.startsWith(".") && !Files.isSymbolicLink(it.toPath()) && agentWorkspace.contains(it) }
                        .take(200).map { ChatFileRow(agentWorkspace.relative(it), it.relativeTo(root).invariantSeparatorsPath, it.length()) }.toList()
                }
                mutable.update { it.copy(chatFiles = files) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("This chat's files could not be listed.") }
        }
    }
    fun hideChatFiles() { mutable.update { it.copy(chatFiles = null) } }
    fun chatFileArtifact(row: ChatFileRow): Artifact = Artifact(File(row.path).name, MimeTypes.forName(row.path), row.path)

    override fun onCleared() { clearUploads(); super.onCleared() }

    companion object {
        /** The API key saved through Settings > API-key mode is also listed as this profile. */
        const val QUICK_KEY_ID = "api-key"
        private const val MAX_EDITOR_BYTES = 1024 * 1024
    }
}
