package com.hmodule.controls

import org.junit.Assert.*
import org.junit.Test

class ControlPolicyTest {
    @Test fun nonPlaybackPageKeepsOnlyNavigationSelectionAndRespectsBothGates() {
        val configured = ControlPolicy(true, true, setOf(ControlTarget.MALL, ControlTarget.SERIES_INFO),
            true, true, 0, true)
        val page = configured.forPage(false)
        assertTrue(page.hides(ControlTarget.MALL))
        assertFalse(page.hides(ControlTarget.HOME))
        assertFalse(page.hides(ControlTarget.SERIES_INFO))
        assertFalse(page.clearsAll)
        assertFalse(page.temporarilyRestored)
        assertEquals(0.8f, page.alpha(0.8f), 0f)
        assertFalse(configured.copy(masterEnabled = false).forPage(false).hides(ControlTarget.MALL))
        assertFalse(configured.copy(hideEnabled = false).forPage(false).hides(ControlTarget.MALL))
        assertEquals(configured, configured.forPage(true))
        assertTrue(ControlTarget.SERIES_INFO in configured.selected)
    }

    @Test fun selectedControlsNeverHideTheirUnselectedSiblings() {
        val policy = ControlPolicy(true, true, setOf(ControlTarget.COMMENT, ControlTarget.MALL))
        assertTrue(policy.hides(ControlTarget.COMMENT))
        assertTrue(policy.hides(ControlTarget.MALL))
        assertFalse(policy.hides(ControlTarget.LIKE))
        assertFalse(policy.hides(ControlTarget.EARN))
        assertFalse(policy.hides(ControlTarget.SERIES_INFO))
    }

    @Test fun pauseDoesNotRestoreSelectiveControlsOutsideFullClear() {
        val playing = ControlPolicy(true, true, ControlTarget.entries.toSet(), true)
        assertTrue(ControlTarget.entries.all(playing::hides))
        val paused = playing.copy(paused = true)
        assertFalse(paused.temporarilyRestored)
        assertTrue(ControlTarget.entries.all(paused::hides))
        assertEquals("清屏播放(果+)", paused.drawerTitle())
        assertTrue(ControlTarget.entries.all(paused.copy(paused = false)::hides))
    }

    @Test fun disablingEitherGateRestoresControlsWithoutLosingSelection() {
        val enabled = ControlPolicy(true, true, setOf(ControlTarget.SERIES_INFO))
        assertFalse(enabled.copy(masterEnabled = false).hides(ControlTarget.SERIES_INFO))
        assertFalse(enabled.copy(hideEnabled = false).hides(ControlTarget.SERIES_INFO))
        assertEquals(enabled.selected, enabled.copy(hideEnabled = false).selected)
        assertEquals("清屏播放(果+)", enabled.copy(hideEnabled = false).drawerTitle())
    }

    @Test fun pauseRestoreIsOptInAndOpacityPreservesNativeAlpha() {
        val policy = ControlPolicy(true, true, setOf(ControlTarget.SERIES_INFO), paused = true, opacityPercent = 40)
        assertTrue(policy.hides(ControlTarget.SERIES_INFO))
        assertEquals(0.2f, policy.alpha(0.5f), 0.001f)
        assertEquals(0.5f, policy.copy(masterEnabled = false).alpha(0.5f), 0.001f)
        assertEquals(1f, policy.copy(opacityPercent = 120).alpha(1f), 0.001f)
        assertEquals(0f, policy.copy(opacityPercent = -10).alpha(1f), 0.001f)
    }

    @Test fun fullClearIsIndependentAndExitPreservesSelectiveRules() {
        val selective = ControlPolicy(true, true, setOf(ControlTarget.PLAYBACK_SPEED))
        assertFalse(selective.cleanScreenEnabled)
        val clear = selective.copy(fullClearEnabled = true)
        assertTrue(ControlTarget.entries.all(clear::hides))
        assertEquals("退出清屏(果+)", clear.drawerTitle())
        val exited = clear.copy(fullClearEnabled = false)
        assertTrue(exited.hides(ControlTarget.PLAYBACK_SPEED))
        assertFalse(exited.hides(ControlTarget.PLAYER_MORE))
        assertTrue(clear.copy(hideEnabled = false).clearsAll)
        assertTrue(ControlTarget.entries.all(clear.copy(hideEnabled = false)::hides))
        assertFalse(clear.copy(masterEnabled = false).clearsAll)
    }

    @Test fun fullClearPauseTemporarilyRestoresWithoutChangingTheDrawerState() {
        val playing = ControlPolicy(masterEnabled = true, fullClearEnabled = true, restoreOnPause = true)
        val paused = playing.copy(paused = true)
        assertTrue(paused.temporarilyRestored)
        assertFalse(paused.clearsAll)
        assertTrue(ControlTarget.entries.none(paused::hides))
        assertEquals("退出清屏(果+)", paused.drawerTitle())
        assertTrue(paused.copy(paused = false).clearsAll)
    }

    @Test fun fullClearPauseRestoresOnlyUnselectedControlsEvenAtZeroOpacity() {
        val paused = ControlPolicy(true, true, setOf(ControlTarget.COMMENT), true, true, 0, true)
        assertTrue(paused.hides(ControlTarget.COMMENT))
        assertFalse(paused.hides(ControlTarget.LIKE))
        assertEquals(0.8f, paused.alpha(0.8f), 0f)
        assertTrue(paused.copy(paused = false).hides(ControlTarget.LIKE))
        assertEquals(0f, paused.copy(paused = false).alpha(0.8f), 0f)
    }
}
