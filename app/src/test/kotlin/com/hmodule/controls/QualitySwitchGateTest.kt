package com.hmodule.controls

import org.junit.Assert.*
import org.junit.Test

class QualitySwitchGateTest {

    private enum class Resolution { P360, P720, P1080 }

    private val gate = QualitySwitchGate()

    @Test fun firstVideoSwitchesAndLaterEpisodesReuseTheHostState() {
        assertTrue(gate.shouldApply(Resolution.P1080))
        gate.markApplied(Resolution.P1080)
        assertFalse(gate.shouldApply(Resolution.P1080))
        assertFalse(gate.shouldApply(Resolution.P1080))
    }

    @Test fun aDifferentPreferredQualityStillSwitchesOnceMore() {
        gate.markApplied(Resolution.P1080)
        assertTrue(gate.shouldApply(Resolution.P720))
        gate.markApplied(Resolution.P720)
        assertFalse(gate.shouldApply(Resolution.P720))
    }

    @Test fun changingTheSettingAllowsExactlyOneMoreSwitch() {
        gate.markApplied(Resolution.P1080)
        gate.reset()
        assertTrue(gate.shouldApply(Resolution.P1080))
        gate.markApplied(Resolution.P1080)
        assertFalse(gate.shouldApply(Resolution.P1080))
    }

    @Test fun failedSwitchIsNotRecordedSoTheNextVideoRetries() {
        assertTrue(gate.shouldApply(Resolution.P720))
        assertNull(gate.appliedTarget())
        assertTrue(gate.shouldApply(Resolution.P720))
    }

    @Test fun missingTargetNeverSwitchesAndNeverRecords() {
        assertFalse(gate.shouldApply(null))
        gate.markApplied(null)
        assertNull(gate.appliedTarget())
        assertTrue(gate.shouldApply(Resolution.P1080))
    }

    @Test fun equalButDistinctResolutionValuesCountAsTheSameQuality() {
        gate.markApplied(listOf(Resolution.P1080, Resolution.P720))
        assertFalse(gate.shouldApply(listOf(Resolution.P1080, Resolution.P720)))
        assertTrue(gate.shouldApply(listOf(Resolution.P720)))
    }
}
