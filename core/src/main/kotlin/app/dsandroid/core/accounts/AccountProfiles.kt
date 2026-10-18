package app.dsandroid.core.accounts

import kotlinx.serialization.Serializable

enum class AccountKind { API_KEY, ACCOUNT }

/** Credentials must only be serialized into the platform secure store. Never export this type. */
@Serializable data class AccountProfile(val id: String, val label: String, val kind: AccountKind, val credential: String)

class AccountProfiles(val entries: List<AccountProfile>, val activeId: String? = null) {
    init {
        require(entries.size <= 20 && entries.map { it.id }.distinct().size == entries.size)
        require(activeId == null || entries.any { it.id == activeId })
        require(entries.all { it.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && it.label.isNotBlank() && it.label.length <= 80 && it.credential.isNotBlank() && it.credential.length <= 16384 })
    }
    fun selected(): AccountProfile? = entries.find { it.id == activeId }
    fun select(id: String): AccountProfiles {
        require(entries.any { it.id == id })
        return AccountProfiles(entries, id)
    }
    fun remove(id: String) = AccountProfiles(entries.filterNot { it.id == id }, activeId.takeUnless { it == id })
}
