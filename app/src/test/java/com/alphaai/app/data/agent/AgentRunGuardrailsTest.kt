package com.alphaai.app.data.agent

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AgentRunGuardrailsTest {
    @Test fun enforcesThe25StepHardStop() {
        assertDoesNotThrow { AgentRunGuardrails.ensureStepAllowed(24) }
        assertThrows(IllegalArgumentException::class.java) { AgentRunGuardrails.ensureStepAllowed(25) }
    }

    @Test fun rejectsAtOrAboveConfiguredCostCap() {
        assertDoesNotThrow { AgentRunGuardrails.ensureCostAllowed(.49, .50) }
        assertThrows(IllegalArgumentException::class.java) { AgentRunGuardrails.ensureCostAllowed(.50, .50) }
    }

    @Test fun repeatedCanonicalCallsAreBlocked() {
        val seen = mutableSetOf<String>()
        assertDoesNotThrow { AgentRunGuardrails.ensureNotRepeated("shell:{}", seen) }
        assertThrows(IllegalArgumentException::class.java) { AgentRunGuardrails.ensureNotRepeated("shell:{}", seen) }
    }
}
