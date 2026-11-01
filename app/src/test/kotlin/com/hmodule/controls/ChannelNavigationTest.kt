package com.hmodule.controls

import org.junit.Assert.*
import org.junit.Test

class ChannelNavigationTest {
    private val titles = listOf("关注", "推荐", "真人剧", "漫剧")
    private val policy = ControlPolicy(true, true, setOf(ControlTarget.TOP_TABS),
        allChannels = false, hiddenChannels = setOf(ChannelTarget.LIVE))

    @Test fun swipingEitherWaySkipsTheHiddenChannelBeforeSelection() {
        assertEquals(3, ChannelNavigation.destination(titles, 1, 2, policy))
        assertEquals(1, ChannelNavigation.destination(titles, 3, 2, policy))
        assertEquals(3, ChannelNavigation.destination(titles, 1, 3, policy))
        assertEquals(3, ChannelNavigation.destination(titles, 1, 2, policy.forPage(false)))
    }

    @Test fun consecutiveHiddenChannelsAndEdgesNeverSelectAHiddenPage() {
        val hidden = policy.copy(hiddenChannels = setOf(ChannelTarget.LIVE, ChannelTarget.COMIC))
        assertEquals(0, ChannelNavigation.destination(titles, 0, 2, hidden))
        assertEquals(1, ChannelNavigation.destination(titles, 1, 2, hidden))
        assertEquals(1, ChannelNavigation.destination(titles, 3, 3, hidden))
        assertEquals(1, ChannelNavigation.destination(titles, 2, 2, hidden))
        assertEquals(1, ChannelNavigation.destination(titles, 1, 0,
            policy.copy(hiddenChannels = setOf(ChannelTarget.FOLLOW))))
    }

    @Test fun unselectedChannelsAndDisabledGatesKeepNativeNavigation() {
        for (off in listOf(policy.copy(masterEnabled = false), policy.copy(hideEnabled = false),
            policy.copy(allChannels = true), policy.copy(selected = emptySet()), policy.copy(hiddenChannels = emptySet()))) {
            assertEquals(2, ChannelNavigation.destination(titles, 1, 2, off))
        }
        assertEquals(2, ChannelNavigation.destination(emptyList(), 1, 2, policy))
        assertEquals(2, ChannelNavigation.destination(listOf("关注", "推荐", "未知频道"), 1, 2, policy))
    }

    @Test fun anyCombinationKeepsVisibleRequestsAndRedirectsToVisibleDestinations() {
        for (mask in 0..7) {
            val hidden = ChannelTarget.entries.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            val config = policy.copy(hiddenChannels = hidden)
            val hiddenTitles = hidden.map { it.title }.toSet()
            for (current in titles.indices) for (requested in titles.indices) {
                val result = ChannelNavigation.destination(titles, current, requested, config)
                assertTrue(titles[result] !in hiddenTitles)
                if (titles[requested] !in hiddenTitles) assertEquals(requested, result)
            }
        }
    }
}
