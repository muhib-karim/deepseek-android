package app.dsandroid.core.accounts

import org.junit.Assert.*
import org.junit.Test

class AccountProfilesTest {
    private val a = AccountProfile("one", "First", AccountKind.API_KEY, "fixture-one")
    private val b = AccountProfile("two", "Second", AccountKind.ACCOUNT, "fixture-two")
    @Test fun selectionRequiresAnExplicitKnownId() {
        val profiles = AccountProfiles(listOf(a, b), a.id)
        assertEquals(a, profiles.selected())
        assertEquals(b, profiles.select(b.id).selected())
        assertTrue(runCatching { profiles.select("missing") }.isFailure)
        assertEquals(a, profiles.selected())
    }
    @Test fun deletingActiveAccountDoesNotChooseAnother() {
        val profiles = AccountProfiles(listOf(a, b), a.id).remove(a.id)
        assertNull(profiles.selected())
        assertEquals(listOf(b), profiles.entries)
    }
    @Test fun rejectsEmptyAndDuplicateProfiles() {
        assertTrue(runCatching { AccountProfiles(listOf(a, a)) }.isFailure)
        assertTrue(runCatching { AccountProfiles(listOf(a.copy(credential = ""))) }.isFailure)
        assertTrue(runCatching { AccountProfiles(listOf(a), "missing") }.isFailure)
    }
}
