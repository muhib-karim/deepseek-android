package app.dsandroid.deepseek

import android.app.Application
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dsandroid.attachments.AttachmentImporter
import app.dsandroid.attachments.Upload
import app.dsandroid.core.deepseek.*
import app.dsandroid.data.Appearance
import app.dsandroid.data.Provider
import app.dsandroid.data.SettingsStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                mutable.update { it.copy(appearance = appearance, hasApiKey = hasApiKey) }
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
        if (available()) mutable.update { it.copy(page = page, error = null) }
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
        withContext(Dispatchers.IO) { store?.clear(); persistence.withLock { history.clear() } }
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
                withContext(Dispatchers.IO) { persistence.withLock { history.save(next) } }
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
            prompt = prompt, thinkingEnabled = think, searchEnabled = search)
        runTurn(current.messages, user, files, solve)
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
        runTurn(current.messages.take(userIndex), user.copy(serverId = null), emptyList(), solve)
    }
    fun edit(id: String, text: String, solve: suspend (PowChallenge) -> Long) {
        if (!available()) return
        val index = current.messages.indexOfFirst { it.id == id && it.user }
        if (index < 0 || text.isBlank()) return
        val user = current.messages[index]
        if (user.prompt == null) { notice("This older message cannot be edited. Send its text again."); return }
        runTurn(current.messages.take(index), user.copy(id = UUID.randomUUID().toString(), text = text.trim().take(20_000),
            prompt = text.trim().take(20_000), serverId = null), emptyList(), solve)
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
    private fun displayMessages() = current.messages.map { AccountMessage(it.id, it.user, it.text, it.thinking, it.prompt) }
    private fun clearUploads() { uploads.forEach { it.file.delete() }; uploads.clear() }
    private fun emptyChat() = WebChat(UUID.randomUUID().toString(), "New Chat")
    override fun onCleared() { clearUploads(); super.onCleared() }
}
