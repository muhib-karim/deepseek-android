package app.dsandroid.core.accounts

import app.dsandroid.core.deepseek.SessionExpired
import app.dsandroid.core.deepseek.WebRateLimited
import app.dsandroid.core.llm.ErrorExplainer
import app.dsandroid.core.llm.ProviderHttpException
import kotlinx.serialization.Serializable

enum class AccountType(val label: String) {
    DEEPSEEK_WEB("DeepSeek account · web sign-in"),
    API_KEY("API key"),
}

/** Metadata only. Session tokens and API keys stay in the encrypted credential stores, keyed by [id]. */
@Serializable
data class RegisteredAccount(val id: String, val label: String, val type: AccountType, val addedAt: Long = 0, val signedIn: Boolean = true)

/**
 * The accounts the user keeps and the one in use. The active account changes only through [switchTo] (a tap in
 * Settings > Accounts) or when a DeepSeek sign-in completes while no account is active. Adding, removing,
 * errors, quota and balances never select another account; see docs/ACCOUNTS.md.
 */
@Serializable
data class AccountRegistry(val accounts: List<RegisteredAccount> = emptyList(), val activeId: String? = null) {
    init {
        require(accounts.size <= MAX_ACCOUNTS) { "At most $MAX_ACCOUNTS accounts" }
        require(accounts.map { it.id }.distinct().size == accounts.size) { "Duplicate account" }
        require(activeId == null || accounts.any { it.id == activeId }) { "Unknown active account" }
        require(accounts.all { it.id.matches(ID) && it.label.isNotBlank() && it.label.length <= MAX_LABEL }) { "Invalid account" }
    }

    val active: RegisteredAccount? get() = accounts.firstOrNull { it.id == activeId }

    fun account(id: String): RegisteredAccount? = accounts.firstOrNull { it.id == id }

    /** Adds [account]; it becomes active only when it is a DeepSeek sign-in and no account is active. */
    fun add(account: RegisteredAccount): AccountRegistry {
        require(account(account.id) == null) { "Account already added" }
        val activate = activeId == null && account.type == AccountType.DEEPSEEK_WEB
        return AccountRegistry(accounts + account, if (activate) account.id else activeId)
    }

    /** The user's explicit choice; the only way another existing account becomes active. */
    fun switchTo(id: String): AccountRegistry {
        require(account(id) != null) { "Unknown account" }
        return if (id == activeId) this else copy(activeId = id)
    }

    /** Removing the active account leaves no account active; another one is never chosen. */
    fun remove(id: String): AccountRegistry {
        if (account(id) == null) return this
        return AccountRegistry(accounts.filterNot { it.id == id }, activeId.takeUnless { it == id })
    }

    fun rename(id: String, label: String): AccountRegistry {
        val name = label.trim()
        require(name.isNotEmpty() && name.length <= MAX_LABEL) { "Use a label of 1 to $MAX_LABEL characters" }
        require(account(id) != null) { "Unknown account" }
        return copy(accounts = accounts.map { if (it.id == id) it.copy(label = name) else it })
    }

    fun markSignedIn(id: String, signedIn: Boolean): AccountRegistry =
        copy(accounts = accounts.map { if (it.id == id) it.copy(signedIn = signedIn) else it })

    companion object {
        const val MAX_ACCOUNTS = 20
        const val MAX_LABEL = 80
        private val ID = Regex("[A-Za-z0-9_-]{1,100}")

        /** "DeepSeek account", then "DeepSeek account 2", ...; "API key 2", ... */
        fun nextLabel(type: AccountType, registry: AccountRegistry): String {
            val base = when (type) { AccountType.DEEPSEEK_WEB -> "DeepSeek account"; AccountType.API_KEY -> "API key" }
            val taken = registry.accounts.map { it.label }.toSet()
            val count = registry.accounts.count { it.type == type }
            return (count + 1..count + MAX_ACCOUNTS + 1).asSequence().map { if (it == 1) base else "$base $it" }.first { it !in taken }
        }
    }
}

enum class AccountFailure { QUOTA, RATE_LIMIT, SIGNED_OUT, OTHER }

data class FailureOutcome(val registry: AccountRegistry, val message: String, val offerSwitchAccount: Boolean)

/**
 * What a failed request does to the account state. Quota and rate errors keep the active account and offer a
 * "Switch account" button that only opens the list; a sign-in failure marks the active account signed out.
 * No failure ever selects, rotates to or falls back to another account.
 */
object AccountFailurePolicy {
    fun handle(registry: AccountRegistry, failure: AccountFailure, message: String): FailureOutcome {
        val next = if (failure == AccountFailure.SIGNED_OUT) registry.activeId?.let { registry.markSignedIn(it, false) } ?: registry else registry
        return FailureOutcome(next, message, failure == AccountFailure.QUOTA || failure == AccountFailure.RATE_LIMIT)
    }

    fun fromError(error: Throwable): AccountFailure = when (error) {
        is WebRateLimited -> AccountFailure.RATE_LIMIT
        is SessionExpired -> AccountFailure.SIGNED_OUT
        is ProviderHttpException -> fromHttpStatus(error.status, error.body)
        else -> AccountFailure.OTHER
    }

    fun fromHttpStatus(status: Int, body: String = ""): AccountFailure = fromProviderMessage(ErrorExplainer.http(status, body))

    /** The API-key client reports failures as ErrorExplainer messages. */
    fun fromProviderMessage(message: String): AccountFailure = when (message) {
        ErrorExplainer.http(402) -> AccountFailure.QUOTA
        ErrorExplainer.http(429) -> AccountFailure.RATE_LIMIT
        ErrorExplainer.http(401) -> AccountFailure.SIGNED_OUT
        else -> AccountFailure.OTHER
    }
}
