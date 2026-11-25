package com.alphaai.app.data.router

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CostCalculatorTest {
    @Test fun estimatesInputAndOutputSeparately() {
        val cost = CostCalculator.estimate("llama-3.3-70b-versatile", 1_000_000, 1_000_000)
        assertEquals(1.38, cost, 0.000001)
    }

    @Test fun zeroTokensCostZeroAndCheapModelCostsLess() {
        assertEquals(0.0, CostCalculator.estimate("llama-3.3-70b-versatile", 0, 0), 0.000001)
        val normal = CostCalculator.estimate("llama-3.3-70b-versatile", 10_000, 10_000)
        val cheap = CostCalculator.estimate("llama-3.1-8b-instant", 10_000, 10_000)
        assertTrue(cheap < normal)
    }
}
