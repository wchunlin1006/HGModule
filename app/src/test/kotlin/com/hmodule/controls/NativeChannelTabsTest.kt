package com.hmodule.controls

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Replays the native original-index layout after GONE tabs retain their old bounds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeChannelTabsTest {
    class Pager(context: Context) : FrameLayout(context) {
        var page = 3
        fun getCurrentItem() = page
    }
    class Tabs(context: Context, pager: Pager) : HorizontalScrollView(context) {
        @JvmField var H2: Pager = pager
        @JvmField var P0 = listOf("关注", "推荐", "真人剧", "漫剧")
        @JvmField var c = 2
        @JvmField var b = 0.5f
        @JvmField var M2 = false
        @JvmField var z = 0
        private val rows = LinearLayout(context).also { addView(it) }
        var indicatorCenter = 0f
        var weights = FloatArray(4)
        fun getTabContainer() = rows
        fun E(progress: Float, start: Int, end: Int) {
            fun center(index: Int) = rows.getChildAt(index).let { it.left + it.width / 2f }
            indicatorCenter = center(start) + (center(end) - center(start)) * progress
        }
        fun J(progress: Float, start: Int, end: Int) {
            weights.fill(0f)
            weights[start] = 1f - progress
            weights[end] = progress
        }
        fun H() { weights.fill(0f); weights[c] = 1f }
        fun nativeDraw() = E(b, c, (c + 1).coerceAtMost(P0.lastIndex))
    }

    private val policy = ControlPolicy(true, true, setOf(ControlTarget.TOP_TABS),
        allChannels = false, hiddenChannels = setOf(ChannelTarget.LIVE))

    private fun fixture(): Tabs {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val tabs = Tabs(activity, Pager(activity))
        activity.setContentView(tabs)
        repeat(4) { tabs.getTabContainer().addView(View(activity)) }
        tabs.layout(0, 0, 300, 80)
        tabs.getTabContainer().layout(0, 0, 300, 80)
        repeat(4) { tabs.getTabContainer().getChildAt(it).layout(it * 100, 0, (it + 1) * 100, 80) }
        tabs.getTabContainer().getChildAt(2).visibility = View.GONE
        // The visible comic tab moves left, while the GONE live tab keeps its old bounds.
        tabs.getTabContainer().getChildAt(3).layout(200, 0, 300, 80)
        return tabs
    }

    @Test fun scrollAndDrawUseTheSameVisibleEndpointsInsteadOfTheHiddenAdjacentSlot() {
        val tabs = fixture()
        val renderer = NativeChannelTabs(Tabs::class.java, Pager::class.java) { it === tabs.H2 }
        tabs.nativeDraw()
        assertEquals(250f, tabs.indicatorCenter, 0f) // Existing native path: wrong position.
        assertTrue(renderer.scroll(tabs, policy))
        assertEquals(225f, tabs.indicatorCenter, 0f)
        assertEquals(0.25f, tabs.weights[1], 0f)
        assertEquals(0.75f, tabs.weights[3], 0f)
        assertEquals(0f, tabs.weights[2], 0f)
        tabs.nativeDraw()
        assertTrue(renderer.draw(tabs, policy))
        assertEquals(225f, tabs.indicatorCenter, 0f)
        assertEquals(2, tabs.c) // Visual interpolation does not alter the pager's indices.
    }

    @Test fun idleSnapsSelectionToTheActualPageAndNeverNavigatesThePager() {
        val tabs = fixture()
        var identifications = 0
        val renderer = NativeChannelTabs(Tabs::class.java, Pager::class.java) { identifications++; true }
        renderer.settle(tabs, policy)
        assertEquals(3, tabs.c)
        assertEquals(0f, tabs.b, 0f)
        renderer.scroll(tabs, policy)
        renderer.draw(tabs, policy)
        assertEquals(250f, tabs.indicatorCenter, 0f)
        assertEquals(1f, tabs.weights[3], 0f)
        assertEquals(3, tabs.H2.page)
        assertEquals(1, identifications) // No repeated tree identification during animation.
    }

    @Test fun singleVisibleTabStaysSelectedEvenWhileNativeScrollCrossesHiddenPages() {
        val tabs = fixture()
        tabs.getTabContainer().getChildAt(0).visibility = View.GONE
        tabs.getTabContainer().getChildAt(3).visibility = View.GONE
        tabs.getTabContainer().getChildAt(1).layout(0, 0, 100, 80)
        val renderer = NativeChannelTabs(Tabs::class.java, Pager::class.java) { true }
        assertTrue(renderer.scroll(tabs, policy.copy(hiddenChannels = ChannelTarget.entries.toSet())))
        assertEquals(50f, tabs.indicatorCenter, 0f)
        assertEquals(1f, tabs.weights[1], 0f)
        assertEquals(2, tabs.c)
    }

    @Test fun disabledGatesUnrelatedPagersAndNativeClickAnimationKeepTheOriginalPath() {
        val tabs = fixture()
        val renderer = NativeChannelTabs(Tabs::class.java, Pager::class.java) { true }
        for (off in listOf(policy.copy(masterEnabled = false), policy.copy(hideEnabled = false),
            policy.copy(allChannels = true), policy.copy(selected = emptySet()), policy.copy(hiddenChannels = emptySet()))) {
            assertFalse(renderer.scroll(tabs, off))
            assertFalse(renderer.draw(tabs, off))
            renderer.settle(tabs, off)
            assertEquals(2, tabs.c)
        }
        val unrelated = NativeChannelTabs(Tabs::class.java, Pager::class.java) { false }
        assertFalse(unrelated.draw(tabs, policy))
        tabs.M2 = true
        assertFalse(renderer.scroll(tabs, policy))
        assertFalse(renderer.draw(tabs, policy))
    }
}
