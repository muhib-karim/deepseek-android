package app.dsandroid.core.accounts

import app.dsandroid.core.deepseek.EndpointChanged
import app.dsandroid.core.deepseek.SessionExpired
import app.dsandroid.core.deepseek.WebConnectionFailed
import app.dsandroid.core.deepseek.WebRateLimited
import app.dsandroid.core.llm.ErrorExplainer
import app.dsandroid.core.llm.ProviderHttpException
import kotlin.random.Random
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AccountRegistryTest {
    private val web = RegisteredAccount("default", "Personal", AccountType.DEEPSEEK_WEB, addedAt = 1)
    private val work = RegisteredAccount("key-work", "Work key", AccountType.API_KEY, addedAt = 2)
    private val spare = RegisteredAccount("key-spare", "Spare key", AccountType.API_KEY, addedAt = 3)
    private val registry = AccountRegistry(listOf(web, work, spare), activeId = web.id)

    /** The rule in docs/ACCOUNTS.md: quota and rate errors never move the user to another account. */
    @Test fun aQuotaErrorNeverChangesTheActiveAccount() {
        for (start in listOf(registry, registry.switchTo(work.id), registry.switchTo(spare.id))) {
            for (failure in AccountFailure.entries) {
                val outcome = AccountFailurePolicy.handle(start, failure, "message")
                assertEquals("$failure must keep ${start.activeId}", start.activeId, outcome.registry.activeId)
                assertEquals(start.accounts.map { it.id }, outcome.registry.accounts.map { it.id })
                assertEquals(failure == AccountFailure.QUOTA || failure == AccountFailure.RATE_LIMIT, outcome.offerSwitchAccount)
                assertEquals("message", outcome.message)
            }
        }
        val quota = AccountFailurePolicy.handle(registry.switchTo(spare.id), AccountFailurePolicy.fromError(ProviderHttpException(402, "")), "No balance")
        assertEquals(spare.id, quota.registry.activeId)
        assertTrue(quota.offerSwitchAccount)
    }

    @Test fun randomFailureSequencesNeverSelectAnotherAccount() {
        val random = Random(22)
        var current = registry.switchTo(work.id)
        repeat(1000) {
            current = AccountFailurePolicy.handle(current, AccountFailure.entries[random.nextInt(AccountFailure.entries.size)], "x").registry
            assertEquals(work.id, current.activeId)
        }
        assertEquals(registry.accounts.map { it.id }, current.accounts.map { it.id })
    }

    @Test fun failuresAreClassifiedFromBothBackends() {
        assertEquals(AccountFailure.RATE_LIMIT, AccountFailurePolicy.fromError(WebRateLimited()))
        assertEquals(AccountFailure.SIGNED_OUT, AccountFailurePolicy.fromError(SessionExpired()))
        assertEquals(AccountFailure.OTHER, AccountFailurePolicy.fromError(EndpointChanged()))
        assertEquals(AccountFailure.OTHER, AccountFailurePolicy.fromError(WebConnectionFailed()))
        assertEquals(AccountFailure.QUOTA, AccountFailurePolicy.fromError(ProviderHttpException(402, "")))
        assertEquals(AccountFailure.QUOTA, AccountFailurePolicy.fromError(ProviderHttpException(400, "{\"error\":{\"message\":\"Insufficient Balance\"}}")))
        assertEquals(AccountFailure.RATE_LIMIT, AccountFailurePolicy.fromError(ProviderHttpException(429, "")))
        assertEquals(AccountFailure.SIGNED_OUT, AccountFailurePolicy.fromError(ProviderHttpException(401, "")))
        assertEquals(AccountFailure.OTHER, AccountFailurePolicy.fromError(ProviderHttpException(500, "")))
        assertEquals(AccountFailure.QUOTA, AccountFailurePolicy.fromProviderMessage(ErrorExplainer.http(402)))
        assertEquals(AccountFailure.RATE_LIMIT, AccountFailurePolicy.fromProviderMessage(ErrorExplainer.http(429)))
        assertEquals(AccountFailure.OTHER, AccountFailurePolicy.fromProviderMessage("Stopped after 8 assistant steps"))
    }

    @Test fun aSignInFailureMarksTheActiveAccountSignedOutWithoutSwitching() {
        val outcome = AccountFailurePolicy.handle(registry, AccountFailure.SIGNED_OUT, "expired")
        assertEquals(web.id, outcome.registry.activeId)
        assertFalse(outcome.registry.active!!.signedIn)
        assertTrue(outcome.registry.account(work.id)!!.signedIn)
        assertFalse(outcome.offerSwitchAccount)
    }

    @Test fun onlyAnExplicitChoiceOfAKnownAccountSwitches() {
        assertEquals(work.id, registry.switchTo(work.id).activeId)
        assertTrue(runCatching { registry.switchTo("missing") }.isFailure)
        assertEquals(web.id, registry.activeId)
        assertSame(registry, registry.switchTo(web.id))
    }

    @Test fun addingNeverSelectsUnlessAWebSignInHasNoActiveAccount() {
        val third = RegisteredAccount("web-2", "Second", AccountType.DEEPSEEK_WEB)
        assertEquals(web.id, registry.add(third).activeId)
        assertEquals(web.id, registry.add(RegisteredAccount("key-3", "Third key", AccountType.API_KEY)).activeId)
        val empty = AccountRegistry()
        assertNull(empty.add(work).activeId)
        assertEquals(third.id, empty.add(work).add(third).activeId)
        assertTrue(runCatching { registry.add(work) }.isFailure)
        val full = (1..AccountRegistry.MAX_ACCOUNTS).fold(AccountRegistry()) { acc, index -> acc.add(RegisteredAccount("k$index", "Key $index", AccountType.API_KEY)) }
        assertTrue(runCatching { full.add(RegisteredAccount("extra", "Extra", AccountType.API_KEY)) }.isFailure)
    }

    @Test fun removingTheActiveAccountLeavesNoneActive() {
        val removed = registry.remove(web.id)
        assertNull(removed.activeId)
        assertEquals(listOf(work, spare), removed.accounts)
        assertEquals(work.id, registry.switchTo(work.id).remove(spare.id).activeId)
        assertSame(registry, registry.remove("missing"))
    }

    @Test fun labelsAreValidatedRenamedAndNumbered() {
        assertEquals("Day job", registry.rename(work.id, "  Day job ").account(work.id)!!.label)
        assertTrue(runCatching { registry.rename(work.id, " ") }.isFailure)
        assertTrue(runCatching { AccountRegistry(listOf(web.copy(label = "x".repeat(81)))) }.isFailure)
        assertTrue(runCatching { AccountRegistry(listOf(web.copy(id = "bad id"))) }.isFailure)
        assertTrue(runCatching { AccountRegistry(listOf(web), activeId = "missing") }.isFailure)
        assertEquals("DeepSeek account 2", AccountRegistry.nextLabel(AccountType.DEEPSEEK_WEB, registry))
        assertEquals("API key 3", AccountRegistry.nextLabel(AccountType.API_KEY, registry))
        assertEquals("DeepSeek account", AccountRegistry.nextLabel(AccountType.DEEPSEEK_WEB, AccountRegistry()))
    }

    @Test fun registryRoundTripsWithoutCredentials() {
        val json = Json.encodeToString(registry)
        assertEquals(registry, Json.decodeFromString<AccountRegistry>(json))
        assertFalse(json.contains("credential", ignoreCase = true))
    }
}
