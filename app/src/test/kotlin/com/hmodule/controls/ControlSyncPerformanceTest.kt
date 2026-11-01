package com.hmodule.controls

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlSyncPerformanceTest {
    private class LayoutCounter(activity: Activity) : FrameLayout(activity) {
        var requests = 0
        override fun requestLayout() { requests++; super.requestLayout() }
    }

    private class TabCounter(activity: Activity) : TextView(activity) {
        var parameterWrites = 0
        override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
            parameterWrites++
            super.setLayoutParams(params)
        }
    }

    @Test fun repeatedPartialChannelSyncDoesNotToggleVisibilityOrRequestAnotherLayout() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val tabs = LayoutCounter(activity).apply { id = android.R.id.tabs }
        root.addView(tabs); activity.setContentView(root)
        val follow = TextView(activity).apply { text = "关注" }
        val comic = TextView(activity).apply { text = "漫剧" }
        tabs.addView(follow); tabs.addView(comic)
        val manager = ControlVisibilityManager { it() }
        manager.resourceOverrides = mapOf("tabs" to "top_tabs")
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.TOP_TABS),
            allChannels = false, hiddenChannels = setOf(ChannelTarget.FOLLOW))
        manager.sync(root)
        tabs.requests = 0
        repeat(30) { manager.sync(root) }
        assertEquals(View.GONE, follow.visibility)
        assertEquals(View.VISIBLE, comic.visibility)
        assertEquals(0, tabs.requests)
    }

    @Test fun repeatedSyncDoesNotRequestLayoutForAlreadyReflowedInformation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val host = LayoutCounter(activity)
        val title = View(activity)
        val declaration = View(activity)
        root.addView(host); host.addView(title); host.addView(declaration)
        root.layout(0, 0, 300, 400); host.layout(0, 0, 300, 300)
        title.layout(0, 180, 300, 220); declaration.layout(0, 220, 300, 250)
        val manager = ControlVisibilityManager { it() }
        manager.bind(title, ControlTarget.SERIES_INFO)
        manager.bind(declaration, ControlTarget.AUTHOR_DECLARATION)
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.AUTHOR_DECLARATION))
        manager.sync(root)
        host.requests = 0

        repeat(30) { manager.sync(root) }

        assertEquals(30f, title.translationY, 0f)
        assertEquals("Stable information must not trigger another measure/layout", 0, host.requests)
    }

    @Test fun repeatedSyncDoesNotRewriteUnchangedBottomTabParameters() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val host = LinearLayout(activity)
        val targets = listOf(ControlTarget.HOME, ControlTarget.MALL, ControlTarget.PROFILE)
        val tabs = targets.map { target -> TabCounter(activity).apply {
            text = target.title.removeSuffix("标签")
            host.addView(this, LinearLayout.LayoutParams(100, 50))
        } }
        root.addView(host)
        root.layout(0, 0, 300, 400); host.layout(0, 350, 300, 400)
        val manager = ControlVisibilityManager { it() }
        tabs.zip(targets).forEach { (tab, target) -> manager.bind(tab, target) }
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.MALL))
        manager.sync(root)
        tabs.forEach { it.parameterWrites = 0 }

        repeat(30) { manager.sync(root) }

        assertEquals("Stable tabs must not invalidate layout on every sync", 0, tabs.sumOf { it.parameterWrites })
    }

    @Test fun hidingWatchingCountInHorizontalBadgeDoesNotMoveItsOtherContentOutsideBackground() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val badge = FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.DKGRAY) }
        val icon = View(activity)
        val title = TextView(activity).apply { text = "系列剧·寒山枭雄" }
        val count = TextView(activity).apply { text = "共32万人在追" }
        root.addView(badge); badge.addView(icon); badge.addView(title); badge.addView(count)
        root.layout(0, 0, 600, 500); badge.layout(0, 200, 550, 278)
        icon.layout(13, 13, 65, 65); title.layout(72, 13, 365, 65); count.layout(394, 13, 550, 65)
        val manager = ControlVisibilityManager { it() }
        manager.bind(title, ControlTarget.SERIES_INFO)
        manager.bind(count, ControlTarget.WATCHING_COUNT)
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.WATCHING_COUNT))

        repeat(3) { manager.sync(root) }

        assertEquals(View.GONE, count.visibility)
        assertEquals("Horizontal siblings must stay inside their badge", 0f, title.translationY, 0f)
        assertEquals(0f, icon.translationY, 0f)
    }

    @Test fun visibleSeriesBannerClampsStaleChildTranslation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val banner = FrameLayout(activity)
        val wrapped = FrameLayout(activity)
        val title = TextView(activity)
        root.addView(banner); banner.addView(wrapped); wrapped.addView(title)
        root.layout(0, 0, 400, 500); banner.layout(0, 100, 400, 180)
        wrapped.layout(0, 0, 400, 80); wrapped.translationY = 40f
        title.layout(0, 0, 200, 40); title.translationY = 50f

        val manager = ControlVisibilityManager { it() }
        manager.bind(banner, ControlTarget.SERIES_BANNER)
        manager.policy = ControlPolicy(masterEnabled = true, hideEnabled = true)
        manager.sync(root)

        assertEquals("The banner host must stay in its measured slot", 0f, wrapped.translationY, 0f)
        assertEquals("Children must not render below the banner background", 0f, title.translationY, 0f)
    }
}
