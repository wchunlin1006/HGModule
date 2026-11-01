package com.hmodule.controls

import org.junit.Assert.*
import org.junit.Test

class ChannelTabProgressTest {
    @Test fun hiddenMiddlePageIsCompressedIntoItsVisibleNeighboursInBothDirections() {
        val visible = listOf(0, 1, 3)
        val samples = listOf(1 to 0f, 1 to 0.5f, 2 to 0f, 2 to 0.5f, 3 to 0f)
        val expected = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        for (index in samples.indices) {
            val (position, offset) = samples[index]
            val progress = ChannelTabProgress.at(visible, position, offset)!!
            assertEquals(1, progress.start)
            assertEquals(3, progress.end)
            assertEquals(expected[index], progress.fraction, 0f)
        }
    }

    @Test fun hiddenEdgesClampToTheFirstAndLastVisibleTabs() {
        assertEquals(ChannelTabProgress(1, 2, 0f), ChannelTabProgress.at(listOf(1, 2), 0, 0.8f))
        assertEquals(ChannelTabProgress(1, 2, 1f), ChannelTabProgress.at(listOf(1, 2), 2, 0.8f))
        assertEquals(ChannelTabProgress(1, 1, 0f), ChannelTabProgress.at(listOf(1), 3, 0.5f))
        assertNull(ChannelTabProgress.at(emptyList(), 2, 0f))
    }

    @Test fun unfilteredPagesKeepTheirNativeProgressAndMultipleHiddenPagesStayContinuous() {
        val native = ChannelTabProgress.at(listOf(0, 1, 2, 3), 1, 0.4f)!!
        assertEquals(1, native.start)
        assertEquals(2, native.end)
        assertEquals(0.4f, native.fraction, 0.000001f)
        assertEquals(ChannelTabProgress(0, 3, 0.5f), ChannelTabProgress.at(listOf(0, 3), 1, 0.5f))
        assertEquals(ChannelTabProgress(0, 3, 0f), ChannelTabProgress.at(listOf(0, 3), 0, Float.NaN))
    }
}
