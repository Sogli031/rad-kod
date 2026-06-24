package com.rad.shifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OnboardingFlowTest {

    @Test
    fun `default onboarding sequence starts after permissions and ends by opening auto fill`() {
        assertEquals(
            listOf(
                OnboardingStep.TAX_UPDATE,
                OnboardingStep.PAY_SETTINGS,
                OnboardingStep.SENIORITY,
                OnboardingStep.AUTO_FILL
            ),
            defaultOnboardingSteps()
        )
        assertFalse(
            defaultOnboardingSteps()
                .map { onboardingStepTitle(it) }
                .any {
                    it.contains("dozvole", ignoreCase = true) ||
                        it.contains("cloud", ignoreCase = true) ||
                        it.contains("backup", ignoreCase = true)
                }
        )
    }
}
