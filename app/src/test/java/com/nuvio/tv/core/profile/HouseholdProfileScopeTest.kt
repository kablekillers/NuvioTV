package com.nuvio.tv.core.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdProfileScopeTest {
    private val homeA = HouseholdProfileScope("shared-account", "home-a", "dad")

    @Test fun `same household and profile may apply personal data`() {
        assertTrue(HouseholdProfileScopePolicy.mayApplyPersonalData(homeA, homeA.copy()))
    }

    @Test fun `different households sharing login never share personal data`() {
        val homeB = homeA.copy(householdId = "home-b")
        assertFalse(HouseholdProfileScopePolicy.mayApplyPersonalData(homeA, homeB))
    }

    @Test fun `different profiles in same house remain separate`() {
        assertFalse(HouseholdProfileScopePolicy.mayApplyPersonalData(homeA, homeA.copy(profileId = "mom")))
    }

    @Test fun `account switch invalidates personal data scope`() {
        assertFalse(HouseholdProfileScopePolicy.mayApplyPersonalData(homeA, homeA.copy(authenticatedAccountId = "other")))
    }

    @Test fun `shared configuration is account scoped, not profile scoped`() {
        assertTrue(HouseholdProfileScopePolicy.mayShareMasterConfiguration("shared-account", "shared-account"))
        assertFalse(HouseholdProfileScopePolicy.mayShareMasterConfiguration("other", "shared-account"))
    }
}
