package app.dsandroid.core.auth

sealed interface SessionAuthState {
    data object SignedOut : SessionAuthState
    data class Active(val accountId: String, val credentialRevision: String) : SessionAuthState
    data class NeedsSignIn(val accountId: String, val previousRevision: String, val status: Int) : SessionAuthState
}

/** Exactly one account. Reauthentication must renew the credentials of that same account. */
class SessionAuthMachine {
    var state: SessionAuthState = SessionAuthState.SignedOut
        private set
    val canResume: Boolean get() = state is SessionAuthState.Active
    fun signedIn(accountId: String, credentialRevision: String): Boolean {
        require(accountId.isNotBlank() && credentialRevision.isNotBlank())
        return when (val current = state) {
            SessionAuthState.SignedOut -> { state = SessionAuthState.Active(accountId, credentialRevision); true }
            is SessionAuthState.Active -> if (current.accountId == accountId) { state = SessionAuthState.Active(accountId, credentialRevision); true } else false
            is SessionAuthState.NeedsSignIn -> renew(accountId, credentialRevision)
        }
    }
    fun rejected(status: Int) {
        if (status != 401 && status != 403) return
        val current = state as? SessionAuthState.Active ?: return
        state = SessionAuthState.NeedsSignIn(current.accountId, current.credentialRevision, status)
    }
    fun renew(accountId: String, credentialRevision: String): Boolean {
        val current = state as? SessionAuthState.NeedsSignIn ?: return false
        if (accountId != current.accountId || credentialRevision.isBlank() || credentialRevision == current.previousRevision) return false
        state = SessionAuthState.Active(accountId, credentialRevision)
        return true
    }
    fun signOut() { state = SessionAuthState.SignedOut }
}
