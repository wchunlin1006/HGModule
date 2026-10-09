package com.hmodule.controls

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlVisibilityManagerTest {
    @Test fun siblingAuthorFollowsPackedButtonsWhenRestoredAndAcrossNativeLayouts() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = com.dragon.read.component.shortvideo.impl.rightview.ShortSeriesRightView(activity)
        val panel = FrameLayout(activity)
        val host = com.dragon.read.widget.LimitHeightByChildrenConstraintLayout(activity)
        val avatar = com.dragon.read.compose.AvatarComposeComponent(activity)
        val follow = com.dragon.read.component.shortvideo.impl.rightview.PugcSubscribeView(activity)
        root.addView(panel); panel.addView(avatar); panel.addView(follow); panel.addView(host)
        activity.setContentView(root)
        val manager = ControlVisibilityManager { it() }
        val targets = listOf(ControlTarget.FAVORITE, ControlTarget.COMMENT, ControlTarget.LIKE, ControlTarget.SHARE)
        val rows = targets.map { target -> View(activity).also { host.addView(it); manager.bind(it, target) } }
        fun layout(base: Int = 0) {
            panel.layout(0, 0, 100, 500)
            avatar.layout(0, base, 50, base + 60)
            follow.layout(15, base + 50, 35, base + 70)
            host.layout(0, base + 80, 80, base + 280)
            rows.forEachIndexed { i, row -> row.layout(0, i * 50, 80, i * 50 + 50) }
        }
        layout()
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.AUTHOR_FOLLOW, ControlTarget.LIKE, ControlTarget.SHARE))
        manager.sync(root)
        manager.policy = manager.policy.copy(selected = setOf(ControlTarget.LIKE, ControlTarget.SHARE))
        manager.sync(root)
        assertEquals(View.VISIBLE, avatar.visibility)
        assertEquals(100f, avatar.translationY, 0f)
        assertEquals(avatar.translationY, follow.translationY, 0f)
        assertEquals(10f, host.top + rows[0].y - follow.bottom - follow.translationY, 0f)
        layout(20); manager.sync(root)
        assertEquals(100f, avatar.translationY, 0f)
        manager.policy = manager.policy.copy(selected = targets.toSet()); manager.sync(root)
        assertEquals(210f, avatar.translationY, 0f)
        manager.policy = manager.policy.copy(fullClearEnabled = true); manager.sync(root)
        assertEquals(0f, avatar.translationY, 0f)
        manager.policy = manager.policy.copy(fullClearEnabled = false, hideEnabled = false); manager.sync(root)
        assertEquals(0f, avatar.translationY, 0f); assertEquals(0f, follow.translationY, 0f)
        assertTrue(rows.all { it.translationY == 0f })
    }

    @Test fun authorAvatarHidesItsEntireCompositionOnlyInsideThePlayerAndRestoresWhenDisabled() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val right = com.dragon.read.component.shortvideo.impl.rightview.ShortSeriesRightView(activity)
        val avatar = com.dragon.read.compose.AvatarComposeComponent(activity)
        val picture = android.widget.ImageView(activity)
        val follow = TextView(activity).apply { text = "+" }
        avatar.addView(picture); avatar.addView(follow); right.addView(avatar)
        val separateFollow = com.dragon.read.component.shortvideo.impl.rightview.PugcSubscribeView(activity)
        right.addView(separateFollow)
        val otherAvatar = com.dragon.read.compose.AvatarComposeComponent(activity)
        root.addView(right); root.addView(otherAvatar); activity.setContentView(root)
        val manager = ControlVisibilityManager { it() }
        val policy = ControlPolicy(true,true,setOf(ControlTarget.AUTHOR_FOLLOW))
        manager.policy = policy; manager.sync(root)
        assertFalse(avatar.isShown)
        assertFalse(picture.isShown); assertFalse(follow.isShown)
        assertFalse(separateFollow.isShown)
        assertEquals(View.VISIBLE,otherAvatar.visibility)
        manager.policy = policy.copy(fullClearEnabled=true,restoreOnPause=true,paused=true)
        manager.sync(root); assertFalse(avatar.isShown)
        manager.policy = policy.copy(hideEnabled=false)
        manager.sync(root); assertEquals(View.VISIBLE,avatar.visibility)
        assertTrue(picture.isShown); assertTrue(follow.isShown)
        assertTrue(separateFollow.isShown)
        // The host may inflate a replacement composition while scrolling to another video.
        right.removeView(avatar)
        val replacement = com.dragon.read.compose.AvatarComposeComponent(activity)
        right.addView(replacement); manager.policy=policy; manager.sync(root)
        assertFalse(replacement.isShown)
    }

    @Test fun authorFollowParticipatesInMeasuredInteractionPackingAndRestoresNativeSlots() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val root=FrameLayout(activity)
        val host=com.dragon.read.widget.LimitHeightByChildrenConstraintLayout(activity)
        root.addView(host,FrameLayout.LayoutParams(80,300)); activity.setContentView(root)
        val manager=ControlVisibilityManager { it() }
        val targets=listOf(ControlTarget.AUTHOR_FOLLOW,ControlTarget.FAVORITE,ControlTarget.COMMENT,ControlTarget.LIKE,ControlTarget.SHARE)
        val rows=targets.map { target -> TextView(activity).also { host.addView(it); manager.bind(it,target) } }
        host.measurementGuard={ block -> manager.withInteractionMeasurement(host) { block() }; Unit }
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(80,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY))
            root.layout(0,0,80,300)
        }
        layout(); val oldBottom=rows.last().bottom.toFloat()
        manager.policy=ControlPolicy(true,true,setOf(ControlTarget.AUTHOR_FOLLOW,ControlTarget.SHARE))
        manager.sync(root); layout()
        assertEquals(View.INVISIBLE,rows.first().visibility)
        assertEquals(oldBottom,rows[3].bottom+rows[3].translationY,0f)
        manager.policy=manager.policy.copy(hideEnabled=false); manager.sync(root); layout()
        assertTrue(rows.all { it.visibility==View.VISIBLE && it.translationY==0f })
        manager.policy=manager.policy.copy(hideEnabled=true,selected=targets.toSet())
        manager.sync(root); layout()
        assertEquals(View.INVISIBLE,host.visibility)
        manager.restoreAll(); assertEquals(View.VISIBLE,host.visibility)
    }

    @Test fun declarationHidesIconAndTextAsOneRowAcrossVersionProfilesAndRestoresNativeState() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for ((profile, mapped) in listOf("CN-7.3.2.32" to false, "CN-7.3.9.32" to true, "COMPAT-CN-7.3.9.32" to false)) {
            val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(activity).apply { text = "短剧标题" }
            val declaration = com.dragon.read.component.shortvideo.impl.infobottom.ShortSeriesInfoBottomView(activity).apply { alpha = 0.8f }
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val icon = android.widget.ImageView(activity)
            val text = TextView(activity).apply { id = android.R.id.button1; this.text = "作者声明：内容由AI生成" }
            row.addView(icon, LinearLayout.LayoutParams(24, 24)); row.addView(text, LinearLayout.LayoutParams(200, 24))
            declaration.addView(row); root.addView(title, LinearLayout.LayoutParams(300, 40))
            root.addView(declaration, LinearLayout.LayoutParams(300, 30))
            activity.setContentView(root)
            val manager = ControlVisibilityManager { it() }
            manager.profileId = profile
            if (mapped) manager.resourceOverrides = mapOf("button1" to "author_declaration")
            val policy = ControlPolicy(masterEnabled = true, hideEnabled = true, selected = setOf(ControlTarget.AUTHOR_DECLARATION))
            manager.policy = policy
            manager.sync(root)
            assertEquals(profile, View.GONE, declaration.visibility)
            assertFalse(profile, icon.isShown)
            assertFalse(profile, text.isShown)
            assertEquals(View.VISIBLE, title.visibility)
            manager.policy = policy.copy(fullClearEnabled = true, restoreOnPause = true, paused = true)
            manager.sync(root)
            assertEquals(profile, View.GONE, declaration.visibility)
            manager.policy = policy.copy(hideEnabled = false)
            manager.sync(root)
            assertEquals(View.VISIBLE, declaration.visibility)
            assertEquals(0.8f, declaration.alpha, 0f)
            assertEquals(View.VISIBLE, icon.visibility)
            assertEquals(View.VISIBLE, text.visibility)
            val native = manager.requestedVisibility(declaration, View.GONE)
            declaration.visibility = native
            manager.sync(root)
            manager.restoreAll()
            assertEquals(View.GONE, declaration.visibility)
        }
    }

    @Test fun jsonResourceMappingControlsOneTargetAndRetainsPauseSimplification() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val selected = TextView(activity).apply { id = android.R.id.button1 }
        val other = TextView(activity).apply { id = android.R.id.button2 }
        root.addView(selected); root.addView(other)
        val manager = ControlVisibilityManager { it() }
        manager.profileId = "CN-7.3.9.32"
        manager.resourceOverrides = mapOf("button1" to "hot_comment", "button2" to "series_info")
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.HOT_COMMENT))
        manager.sync(root)
        assertEquals(View.GONE, selected.visibility)
        assertEquals(View.VISIBLE, other.visibility)
        manager.policy = manager.policy.copy(fullClearEnabled = true, restoreOnPause = true, paused = true)
        manager.sync(root)
        assertEquals(View.GONE, selected.visibility)
        assertEquals(View.VISIBLE, other.visibility)
        manager.restoreAll()
        assertEquals(View.VISIBLE, selected.visibility)
    }

    @Test fun jsonClearHostsRestoreAndConfiguredBackdropKeepsMatchingColour() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val host = FrameLayout(activity).apply { id = android.R.id.content }
        val backdrop = View(activity).apply { id = android.R.id.background }
        root.addView(host); root.addView(backdrop)
        val manager = ControlVisibilityManager { it() }
        manager.profileId = "CN-7.3.9.32"
        manager.clearHostNames = setOf("content", "background")
        manager.bottomBackdropName = "background"
        manager.policy = ControlPolicy(masterEnabled = true, fullClearEnabled = true)
        manager.sync(root)
        assertEquals(View.INVISIBLE, host.visibility)
        assertEquals(View.VISIBLE, backdrop.visibility)
        assertEquals(ControlVisibilityManager.CLEAR_BACKGROUND_COLOR, (backdrop.background as android.graphics.drawable.ColorDrawable).color)
        manager.policy = manager.policy.copy(fullClearEnabled = false)
        manager.sync(root)
        assertEquals(View.VISIBLE, host.visibility)
        assertNull(backdrop.background)
    }

    @Test fun remainingInteractionsPackUpFromTheOriginalBottomAndRestoreTheirPositions() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val host = com.dragon.read.widget.LimitHeightByChildrenConstraintLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(host)
        val manager = ControlVisibilityManager { it() }
        host.measurementGuard = { block -> manager.withInteractionMeasurement(host) { block() }; Unit }
        val targets = listOf(ControlTarget.FAVORITE, ControlTarget.COMMENT, ControlTarget.LIKE, ControlTarget.SHARE)
        val views = targets.map { target -> TextView(activity).also {
            host.addView(it, LinearLayout.LayoutParams(80, 50)); manager.bind(it, target)
        } }
        fun layout() {
            host.measure(View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
            host.layout(0, 0, 80, host.measuredHeight)
        }
        layout()
        val baseline = views.map { it.y }
        val bottom = views.last().y + views.last().height
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.SHARE))
        manager.sync(root); layout()
        assertEquals(bottom, views[2].y + views[2].height, 0f)
        assertEquals(views[2].y, views[1].y + views[1].height, 0f)
        manager.policy = manager.policy.copy(selected = setOf(ControlTarget.LIKE, ControlTarget.SHARE))
        manager.sync(root); layout()
        assertEquals(bottom, views[1].y + views[1].height, 0f)
        assertEquals(views[1].y, views[0].y + views[0].height, 0f)
        views[0].translationY = manager.requestedTranslationY(views[0], 3f)
        repeat(4) { manager.sync(root) }
        assertEquals(bottom, views[1].y + views[1].height, 0f)
        manager.policy = manager.policy.copy(fullClearEnabled = true, restoreOnPause = true, paused = true)
        manager.sync(root)
        assertEquals(bottom, views[1].y + views[1].height, 0f)
        manager.policy = manager.policy.copy(masterEnabled = false)
        manager.sync(root); layout()
        assertEquals(baseline.mapIndexed { index, y -> y + if (index == 0) 3f else 0f }, views.map { it.y })
        assertTrue(views.all { it.visibility == View.VISIBLE })
    }

    @Test fun hidingHotCommentCollapsesItsInvisibleAlternativeWrapperAndLetsInformationDropIntoPlace() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val stack = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(stack, FrameLayout.LayoutParams(200, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        val title = TextView(activity)
        stack.addView(title, LinearLayout.LayoutParams(200, 80))
        val hotWrapper = FrameLayout(activity)
        val invisibleAlternative = TextView(activity).apply { visibility = View.INVISIBLE }
        val hotComment = TextView(activity)
        hotWrapper.addView(invisibleAlternative, FrameLayout.LayoutParams(200, 60))
        hotWrapper.addView(hotComment, FrameLayout.LayoutParams(200, 60))
        stack.addView(hotWrapper, LinearLayout.LayoutParams(200, ViewGroup.LayoutParams.WRAP_CONTENT))
        val manager = ControlVisibilityManager { it() }
        manager.bind(title, ControlTarget.SERIES_INFO)
        manager.bind(invisibleAlternative, ControlTarget.SERIES_INFO)
        manager.bind(hotComment, ControlTarget.HOT_COMMENT)
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 200, 400)
        }
        layout()
        val nativeTitleTop = stack.y + title.y
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.HOT_COMMENT))
        manager.sync(root); layout()
        assertEquals(View.GONE, hotWrapper.visibility)
        assertEquals(nativeTitleTop + 60f, stack.y + title.y, 0f)
        // A newly visible native sibling must reopen the wrapper while hot comments stay hidden.
        invisibleAlternative.visibility = manager.requestedVisibility(invisibleAlternative, View.VISIBLE)
        manager.sync(root); layout()
        assertEquals(View.VISIBLE, hotWrapper.visibility)
        assertEquals(View.GONE, hotComment.visibility)
        assertEquals(nativeTitleTop, stack.y + title.y, 0f)
        invisibleAlternative.visibility = manager.requestedVisibility(invisibleAlternative, View.INVISIBLE)
        manager.sync(root); layout()
        assertEquals(View.GONE, hotWrapper.visibility)
        assertEquals(nativeTitleTop + 60f, stack.y + title.y, 0f)
        manager.policy = manager.policy.copy(fullClearEnabled = true, restoreOnPause = true)
        manager.sync(root)
        manager.policy = manager.policy.copy(paused = true)
        manager.sync(root); layout()
        assertEquals(View.GONE, hotWrapper.visibility)
        assertEquals(nativeTitleTop + 60f, stack.y + title.y, 0f)
        manager.policy = manager.policy.copy(fullClearEnabled = false)
        manager.policy = manager.policy.copy(hideEnabled = false)
        manager.sync(root); layout()
        assertEquals(View.VISIBLE, hotWrapper.visibility)
        assertEquals(View.INVISIBLE, invisibleAlternative.visibility)
        assertEquals(nativeTitleTop, stack.y + title.y, 0f)
    }

    @Test fun interactionPackingKeepsUnequalNativeGapsAndFillsMiddleSlots() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val host = com.dragon.read.widget.LimitHeightByChildrenConstraintLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(host)
        val manager = ControlVisibilityManager { it() }
        host.measurementGuard = { block -> manager.withInteractionMeasurement(host) { block() }; Unit }
        val targets = listOf(ControlTarget.FAVORITE, ControlTarget.COMMENT, ControlTarget.LIKE, ControlTarget.SHARE)
        val gaps = listOf(5, 7, 9, 0)
        val views = targets.mapIndexed { index, target -> TextView(activity).also {
            host.addView(it, LinearLayout.LayoutParams(80, 50).apply { bottomMargin = gaps[index] })
            manager.bind(it, target)
        } }
        host.measure(View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        host.layout(0, 0, 80, 221)
        val bottom = views[3].y + views[3].height
        val configured = ControlPolicy(true, true, setOf(ControlTarget.SHARE))
        manager.policy = configured
        manager.sync(root)
        assertEquals(bottom, views[2].y + views[2].height, 0f)
        assertEquals(7f, views[2].y - views[1].y - views[1].height, 0f)
        assertEquals(5f, views[1].y - views[0].y - views[0].height, 0f)
        manager.policy = configured.copy(selected = setOf(ControlTarget.COMMENT))
        manager.sync(root)
        assertEquals(bottom, views[3].y + views[3].height, 0f)
        assertEquals(9f, views[3].y - views[2].y - views[2].height, 0f)
        assertEquals(5f, views[2].y - views[0].y - views[0].height, 0f)
        manager.restoreAll()
        assertTrue(views.all { it.translationY == 0f })
    }

    @Test fun navigationSelectionSurvivesLeavingPlaybackRecreationAndReturningToHome() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val nav = com.dragon.read.widget.BottomTabFrameLayout(activity).apply { alpha = 0.8f }
        val slots = LinearLayout(activity)
        root.addView(nav); nav.addView(slots)
        fun createTabs(): List<TextView> = listOf("首页", "剧场", "商城", "赚钱", "我的").map { label ->
            TextView(activity).apply { text = label; slots.addView(this, LinearLayout.LayoutParams(80, 50)) }
        }
        var tabs = createTabs()
        val info = TextView(activity).apply { alpha = 0.7f }
        root.addView(info)
        val manager = ControlVisibilityManager { it() }
        manager.bind(info, ControlTarget.SERIES_INFO)
        val configured = ControlPolicy(true, true, setOf(ControlTarget.MALL, ControlTarget.EARN,
            ControlTarget.SERIES_INFO), restoreOnPause = true, opacityPercent = 40, fullClearEnabled = true)
        manager.policy = configured.forPage(true)
        manager.sync(root)
        assertEquals(View.INVISIBLE, nav.visibility)
        // The profile/theater pages have no active video; only bottom selections remain.
        repeat(2) {
            manager.policy = configured.forPage(false)
            manager.sync(root)
            assertEquals(View.VISIBLE, nav.visibility)
            assertEquals(0.8f, nav.alpha, 0f)
            assertEquals(View.GONE, tabs[2].visibility)
            assertEquals(View.GONE, tabs[3].visibility)
            assertTrue(listOf(0, 1, 4).all { tabs[it].visibility == View.VISIBLE })
            assertEquals(View.VISIBLE, info.visibility)
            assertEquals(0.7f, info.alpha, 0f)
            assertEquals(View.GONE, manager.requestedVisibility(tabs[2], View.VISIBLE))
            assertEquals(1f, (tabs[0].layoutParams as LinearLayout.LayoutParams).weight, 0f)
            if (it == 0) { slots.removeAllViews(); tabs = createTabs() }
        }
        manager.policy = configured.forPage(true)
        manager.sync(root)
        assertEquals(View.INVISIBLE, nav.visibility)
        manager.policy = configured.copy(fullClearEnabled = false).forPage(true)
        manager.sync(root)
        assertEquals(View.VISIBLE, nav.visibility)
        assertEquals(View.GONE, tabs[2].visibility)
        assertEquals(View.GONE, info.visibility)
        manager.policy = configured.copy(hideEnabled = false).forPage(false)
        manager.sync(root)
        assertTrue(tabs.all { it.visibility == View.VISIBLE && it.layoutParams.width == 80 })
    }

    @Test fun hidingOneInteractionPreservesTheNativeContainerHeight() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val host = com.dragon.read.widget.LimitHeightByChildrenConstraintLayout(activity)
        root.addView(host)
        val manager = ControlVisibilityManager { it() }
        host.measurementGuard = { block -> manager.withInteractionMeasurement(host) { block() }; Unit }
        val targets = listOf(ControlTarget.FAVORITE, ControlTarget.COMMENT, ControlTarget.LIKE, ControlTarget.SHARE)
        val views = targets.map { target -> TextView(activity).also {
            host.addView(it, LinearLayout.LayoutParams(80, 50)); manager.bind(it, target)
        } }
        fun measure() = host.measure(View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        measure()
        val baseline = host.measuredHeight
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.COMMENT))
        manager.sync(root)
        measure()
        assertEquals(baseline, host.measuredHeight)
        assertEquals(View.INVISIBLE, views[1].visibility)
        assertTrue(views.filterIndexed { index, _ -> index != 1 }.all { it.visibility == View.VISIBLE })
        for (target in targets) {
            manager.policy = manager.policy.copy(selected = setOf(target))
            manager.sync(root)
            measure()
            assertEquals(baseline, host.measuredHeight)
        }
        manager.restoreAll()
        measure()
        assertEquals(baseline, host.measuredHeight)
        assertTrue(views.all { it.visibility == View.VISIBLE })
    }

    @Test fun clearScreenMatchesBottomBackdropAndWindowColorThenRestoresTheirNativeState() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        @Suppress("DEPRECATION")
        val namedResources = object : android.content.res.Resources(activity.assets,
            activity.resources.displayMetrics, activity.resources.configuration) {
            override fun getResourceEntryName(id: Int): String = if (id == 100001) "ar8" else super.getResourceEntryName(id)
        }
        val context = object : android.content.ContextWrapper(activity) {
            override fun getResources() = namedResources
        }
        val root = FrameLayout(activity)
        val video = android.view.TextureView(activity)
        val backdrop = View(context).apply { id = 100001; setBackgroundColor(android.graphics.Color.DKGRAY) }
        val nav = com.dragon.read.widget.BottomTabFrameLayout(activity)
        root.addView(video); root.addView(backdrop); root.addView(nav)
        val manager = ControlVisibilityManager { it() }.apply { profileId = "CN-7.3.2.32" }
        val active = ControlPolicy(masterEnabled = true, fullClearEnabled = true, restoreOnPause = true)
        activity.window.navigationBarColor = android.graphics.Color.DKGRAY
        manager.policy = active
        manager.sync(root)
        manager.syncWindow(activity.window)
        assertEquals(View.VISIBLE, backdrop.visibility)
        assertEquals(ControlVisibilityManager.CLEAR_BACKGROUND_COLOR, (backdrop.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(ControlVisibilityManager.CLEAR_BACKGROUND_COLOR, activity.window.navigationBarColor)
        assertEquals(View.INVISIBLE, nav.visibility)
        assertEquals(View.VISIBLE, video.visibility)
        manager.policy = active.copy(paused = true)
        manager.sync(root)
        manager.syncWindow(activity.window)
        assertEquals(View.VISIBLE, backdrop.visibility)
        assertEquals(android.graphics.Color.DKGRAY, (backdrop.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(android.graphics.Color.DKGRAY, activity.window.navigationBarColor)
        manager.policy = active
        manager.sync(root)
        assertEquals(View.VISIBLE, backdrop.visibility)
        assertEquals(1f, manager.requestedAlpha(backdrop, 0.6f), 0f)
        assertEquals(View.VISIBLE, manager.requestedVisibility(backdrop, View.GONE))
        manager.policy = active.copy(fullClearEnabled = false)
        manager.sync(root)
        assertEquals(View.GONE, backdrop.visibility)
        assertEquals(0.6f, backdrop.alpha, 0f)
        assertEquals(View.VISIBLE, nav.visibility)
        manager.restoreAll()
        assertEquals(View.GONE, backdrop.visibility)
    }

    @Test fun pauseAtZeroOpacityShowsOnlyUnselectedControlsAndResumeReappliesClear() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val overlay = com.dragon.read.widget.BottomTabFrameLayout(activity).apply { alpha = 0.8f }
        val selected = TextView(activity)
        val unselected = TextView(activity)
        root.addView(overlay); overlay.addView(selected); overlay.addView(unselected)
        val manager = ControlVisibilityManager { it() }
        manager.bind(selected, ControlTarget.SERIES_INFO)
        manager.bind(unselected, ControlTarget.PLAYBACK_SPEED)
        val playing = ControlPolicy(true, true, setOf(ControlTarget.SERIES_INFO), true, false, 0, true)
        manager.policy = playing
        manager.sync(root)
        assertEquals(View.INVISIBLE, overlay.visibility)
        manager.policy = playing.copy(paused = true)
        manager.sync(root)
        assertEquals(View.VISIBLE, overlay.visibility)
        assertEquals(0.8f, overlay.alpha, 0f)
        assertEquals(View.GONE, selected.visibility)
        assertEquals(View.VISIBLE, unselected.visibility)
        assertEquals(1f, unselected.alpha, 0f)
        manager.policy = playing
        manager.sync(root)
        assertEquals(View.INVISIBLE, overlay.visibility)
        manager.policy = playing.copy(fullClearEnabled = false, paused = true)
        manager.sync(root)
        assertEquals(View.GONE, selected.visibility)
        assertEquals(0f, overlay.alpha, 0f)
    }

    @Test fun homeTheaterAndProfileAreDiscoveredOnlyInsideNavigationAndReflowIndependently() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val nav = com.dragon.read.widget.BottomTabFrameLayout(activity)
        val slots = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(nav); nav.addView(slots)
        val tabs = listOf("首页", "剧场", "商城", "赚钱", "我的").map { name ->
            TextView(activity).apply { text = name; slots.addView(this, LinearLayout.LayoutParams(80, 50)) }
        }
        val outside = TextView(activity).apply { text = "我的"; root.addView(this) }
        val manager = ControlVisibilityManager { it() }
        for ((index, target) in listOf(0 to ControlTarget.HOME, 1 to ControlTarget.THEATER, 4 to ControlTarget.PROFILE)) {
            manager.policy = ControlPolicy(true, true, setOf(target))
            manager.sync(root)
            assertEquals(View.GONE, tabs[index].visibility)
            assertTrue(tabs.filterIndexed { i, _ -> i != index }.all { it.visibility == View.VISIBLE })
            assertEquals(View.VISIBLE, outside.visibility)
        }
        manager.policy = manager.policy.copy(selected = ControlTarget.entries.filter { it.isBottomTab }.toSet())
        manager.sync(root)
        assertTrue(tabs.all { it.visibility == View.GONE })
        manager.policy = manager.policy.copy(hideEnabled = false)
        manager.sync(root)
        assertTrue(tabs.all { it.visibility == View.VISIBLE && it.layoutParams.width == 80 })
    }

    @Test fun opacityAlsoCoversUnselectedOverlayChildrenWithoutFadingVideoOrCompounding() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val host = com.dragon.read.widget.BottomTabFrameLayout(activity).apply { alpha = 0.8f }
        val homeTab = TextView(activity)
        val mallTab = TextView(activity)
        val video = android.view.TextureView(activity)
        root.addView(host); root.addView(video); host.addView(homeTab); host.addView(mallTab)
        val manager = ControlVisibilityManager { it() }
        manager.bind(mallTab, ControlTarget.MALL)
        manager.policy = ControlPolicy(masterEnabled = true, opacityPercent = 40)
        repeat(5) { manager.sync(root) }
        assertEquals(0.32f, host.alpha, 0.001f)
        assertEquals(1f, homeTab.alpha, 0f)
        assertEquals(1f, mallTab.alpha, 0f)
        assertEquals(1f, video.alpha, 0f)
        assertEquals(0.2f, manager.requestedAlpha(host, 0.5f), 0.001f)
        manager.policy = manager.policy.copy(opacityPercent = 100)
        manager.sync(root)
        assertEquals(0.5f, host.alpha, 0f)
    }

    @Test fun asynchronouslyFilledWatchingCountStaysIndependentFromTheHiddenInformationPanel() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val info = LinearLayout(activity)
        val title = TextView(activity).apply { text = "作品标题" }
        val count = TextView(activity)
        root.addView(info); info.addView(title); info.addView(count)
        val manager = ControlVisibilityManager { it() }
        manager.bind(info, ControlTarget.SERIES_INFO)
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.SERIES_INFO))
        manager.sync(root)
        assertEquals(View.GONE, info.visibility)
        count.text = "共 1.2 万人在追"
        manager.sync(root)
        assertEquals(View.VISIBLE, info.visibility)
        assertEquals(View.GONE, title.visibility)
        assertEquals(View.VISIBLE, count.visibility)
        manager.policy = manager.policy.copy(selected = setOf(ControlTarget.WATCHING_COUNT))
        manager.sync(root)
        assertEquals(View.VISIBLE, title.visibility)
        assertEquals(View.GONE, count.visibility)
    }

    @Test fun hidingAllInteractionControlsPreservesMeasuredSlotsForPauseRestoration() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val interactions = listOf(ControlTarget.FAVORITE, ControlTarget.COMMENT, ControlTarget.LIKE, ControlTarget.SHARE)
        val manager = ControlVisibilityManager { it() }
        val views = interactions.map { target ->
            TextView(activity).also {
                root.addView(it, LinearLayout.LayoutParams(80, 50))
                manager.bind(it, target)
            }
        }
        manager.policy = ControlPolicy(true, true, interactions.toSet(), restoreOnPause = true)
        manager.sync(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        assertTrue(views.all { it.visibility == View.INVISIBLE && it.measuredWidth == 80 && it.measuredHeight == 50 })
        manager.policy = manager.policy.copy(paused = true)
        manager.sync(root)
        assertTrue(views.all { it.visibility == View.INVISIBLE })
        manager.policy = manager.policy.copy(paused = false)
        manager.sync(root)
        assertTrue(views.all { it.visibility == View.INVISIBLE })
    }

    @Test fun allFourInteractionsSuppressTheNativeMeasuringHostAndRestoreWithoutZeroSizeChildren() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val host = com.dragon.read.widget.LimitHeightByChildrenConstraintLayout(activity)
        root.addView(host)
        val targets = setOf(ControlTarget.FAVORITE, ControlTarget.COMMENT, ControlTarget.LIKE, ControlTarget.SHARE)
        val manager = ControlVisibilityManager { it() }
        val views = targets.map { target -> TextView(activity).also { host.addView(it); manager.bind(it, target) } }
        manager.policy = ControlPolicy(true, true, targets, restoreOnPause = true)
        manager.sync(root)
        assertEquals(View.INVISIBLE, host.visibility)
        assertTrue(views.all { it.visibility == View.VISIBLE })
        assertEquals(View.VISIBLE, manager.requestedVisibility(views.first(), View.VISIBLE))
        manager.policy = manager.policy.copy(paused = true)
        manager.sync(root)
        assertEquals(View.INVISIBLE, host.visibility)
        assertTrue(views.all { it.visibility == View.VISIBLE })
        manager.policy = manager.policy.copy(paused = false, selected = setOf(ControlTarget.FAVORITE))
        manager.sync(root)
        assertEquals(View.VISIBLE, host.visibility)
        assertEquals(View.INVISIBLE, views.first().visibility)
        assertTrue(views.drop(1).all { it.visibility == View.VISIBLE })
    }

    @Test fun fullClearSuppressesUnknownOverlayChildrenAndExitKeepsSelectedControlsHidden() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val overlay = com.dragon.read.widget.BottomTabFrameLayout(activity)
        val selected = TextView(activity)
        val unknown = TextView(activity)
        val video = android.view.TextureView(activity)
        val gesture = View(activity)
        root.addView(video); root.addView(gesture); root.addView(overlay)
        overlay.addView(selected); overlay.addView(unknown)
        val manager = ControlVisibilityManager { it() }
        manager.bind(selected, ControlTarget.PLAYBACK_SPEED)
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.PLAYBACK_SPEED), fullClearEnabled = true)
        manager.sync(root)
        assertEquals(View.INVISIBLE, overlay.visibility)
        assertEquals(View.VISIBLE, video.visibility)
        assertEquals(View.VISIBLE, gesture.visibility)
        manager.policy = manager.policy.copy(fullClearEnabled = false)
        manager.sync(root)
        assertEquals(View.VISIBLE, overlay.visibility)
        assertEquals(View.GONE, selected.visibility)
        assertEquals(View.VISIBLE, unknown.visibility)
        manager.policy = manager.policy.copy(fullClearEnabled = true, restoreOnPause = true)
        manager.sync(root)
        manager.policy = manager.policy.copy(paused = true)
        manager.sync(root)
        assertEquals(View.VISIBLE, overlay.visibility)
        assertEquals(View.GONE, selected.visibility)
        manager.policy = manager.policy.copy(paused = false)
        manager.sync(root)
        assertEquals(View.INVISIBLE, overlay.visibility)
    }

    @Test fun fullClearNeverSuppressesAHostContainingTheVideoSurfaceAndRestoresNativeHiddenHosts() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val videoHost = com.dragon.read.widget.BottomTabFrameLayout(activity)
        videoHost.addView(android.view.SurfaceView(activity))
        val hiddenHost = com.dragon.read.widget.BottomTabFrameLayout(activity).apply { visibility = View.GONE }
        root.addView(videoHost); root.addView(hiddenHost)
        val manager = ControlVisibilityManager { it() }
        manager.policy = ControlPolicy(masterEnabled = true, fullClearEnabled = true)
        manager.sync(root)
        assertEquals(View.VISIBLE, videoHost.visibility)
        assertEquals(View.INVISIBLE, manager.requestedVisibility(hiddenHost, View.GONE))
        manager.restoreAll()
        assertEquals(View.GONE, hiddenHost.visibility)
    }

    @Test fun pauseRestoresSuppressedInformationParentWithoutTouchingTheVideoRoot() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val overlay = LinearLayout(activity).apply { visibility = View.INVISIBLE; alpha = 0f; translationY = 20f }
        val info = TextView(activity)
        root.addView(overlay); overlay.addView(info)
        val manager = ControlVisibilityManager { it() }
        manager.bind(info, ControlTarget.SERIES_INFO)
        manager.policy = ControlPolicy(true, true, emptySet(), true, true, fullClearEnabled = true)
        manager.sync(root)
        assertEquals(View.VISIBLE, overlay.visibility)
        assertEquals(1f, overlay.alpha, 0f)
        assertEquals(0f, overlay.translationY, 0f)
        assertEquals(View.VISIBLE, info.visibility)
        manager.policy = manager.policy.copy(paused = false)
        manager.sync(root)
        assertEquals(View.INVISIBLE, overlay.visibility)
        assertEquals(0f, overlay.alpha, 0f)
        assertEquals(20f, overlay.translationY, 0f)
        assertEquals(View.VISIBLE, root.visibility)
    }

    @Test fun nestedControlsDoNotMultiplyTheConfiguredOpacityTwice() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val info = LinearLayout(activity)
        val count = TextView(activity)
        root.addView(info); info.addView(count)
        val manager = ControlVisibilityManager { it() }
        manager.bind(info, ControlTarget.SERIES_INFO)
        manager.bind(count, ControlTarget.WATCHING_COUNT)
        manager.policy = ControlPolicy(masterEnabled = true, opacityPercent = 40)
        manager.sync(root)
        assertEquals(0.4f, info.alpha, 0.001f)
        assertEquals(1f, count.alpha, 0.001f)
    }
    @Test fun pauseKeepsSelectedInfoHiddenAndRestoresUnselectedCommentOnlyDuringClear() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LinearLayout(activity)
        val info = TextView(activity).apply { text = "作品信息" }
        val comment = TextView(activity).apply { text = "评论" }
        root.addView(info); root.addView(comment)
        activity.setContentView(root)
        val manager = ControlVisibilityManager { it() }
        manager.bind(info, ControlTarget.SERIES_INFO)
        manager.bind(comment, ControlTarget.COMMENT)
        val playing = ControlPolicy(true, true, setOf(ControlTarget.SERIES_INFO), true, fullClearEnabled = true)
        manager.policy = playing
        manager.sync(root)
        assertEquals(View.GONE, info.visibility)
        assertEquals(View.INVISIBLE, comment.visibility)
        manager.policy = playing.copy(paused = true)
        manager.sync(root)
        assertEquals(View.GONE, info.visibility)
        assertEquals(View.VISIBLE, comment.visibility)
        manager.policy = playing
        manager.sync(root)
        assertEquals(View.GONE, info.visibility)
        manager.policy = playing.copy(hideEnabled = false, fullClearEnabled = false)
        manager.sync(root)
        assertEquals(View.VISIBLE, info.visibility)
    }

    @Test fun repeatedOpacityApplicationDoesNotCompoundAndOffRestoresOriginalAlpha() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LinearLayout(activity)
        val info = TextView(activity).apply { alpha = 0.8f }
        root.addView(info)
        val manager = ControlVisibilityManager { it() }
        manager.bind(info, ControlTarget.SERIES_INFO)
        manager.policy = ControlPolicy(masterEnabled = true, opacityPercent = 50)
        repeat(10) { manager.sync(root) }
        assertEquals(0.4f, info.alpha, 0.001f)
        assertEquals(0.3f, manager.requestedAlpha(info, 0.6f), 0.001f)
        manager.policy = manager.policy.copy(masterEnabled = false)
        manager.sync(root)
        assertEquals(0.6f, info.alpha, 0.001f)
    }

    @Test fun hidingMallReweightsRemainingTabsAndRestoresLayoutAfterDisabling() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val tabs = listOf("首页", "剧场", "商城", "赚钱", "我的").map { label ->
            TextView(activity).apply {
                text = label
                root.addView(this, LinearLayout.LayoutParams(80, 50))
            }
        }
        val manager = ControlVisibilityManager { it() }
        manager.bind(tabs[2], ControlTarget.MALL)
        manager.bind(tabs[3], ControlTarget.EARN)
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.MALL))
        manager.sync(root)
        assertEquals(View.GONE, tabs[2].visibility)
        assertEquals(View.VISIBLE, tabs[3].visibility)
        for (tab in tabs.filterIndexed { index, _ -> index != 2 }) {
            assertEquals(1f, (tab.layoutParams as LinearLayout.LayoutParams).weight, 0f)
        }
        manager.policy = manager.policy.copy(hideEnabled = false)
        manager.sync(root)
        assertEquals(View.VISIBLE, tabs[2].visibility)
        assertTrue(tabs.all { it.layoutParams.width == 80 })
        assertTrue(tabs.all { (it.layoutParams as LinearLayout.LayoutParams).weight == 0f })
    }

    @Test fun targetAppVisibilityRequestSurvivesHidePauseResumeAndExit() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LinearLayout(activity)
        val info = TextView(activity)
        root.addView(info)
        val manager = ControlVisibilityManager { it() }
        manager.bind(info, ControlTarget.SERIES_INFO)
        manager.policy = ControlPolicy(true, true, setOf(ControlTarget.SERIES_INFO), true)
        manager.sync(root)
        assertEquals(View.GONE, manager.requestedVisibility(info, View.INVISIBLE))
        manager.policy = manager.policy.copy(paused = true)
        manager.sync(root)
        assertEquals(View.GONE, info.visibility)
        manager.policy = manager.policy.copy(hideEnabled = false)
        manager.sync(root)
        assertEquals(View.INVISIBLE, info.visibility)
    }

    @Test fun drawerAddsOneActionAndLabelFollowsIndependentFullClearSwitch() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(activity).apply { text = "倍速" })
        root.addView(TextView(activity).apply { text = "清屏" })
        var policy = ControlPolicy()
        var settingsOpened = false
        val drawer = PlaybackDrawer({ policy }, {
            policy = policy.copy(masterEnabled = true, fullClearEnabled = !policy.cleanScreenEnabled)
        }, { settingsOpened = true })
        assertTrue(drawer.inject(root, "PlaybackLongPressDialog") {})
        assertTrue(drawer.inject(root, "PlaybackLongPressDialog") {})
        assertEquals(3, root.childCount)
        val action = root.getChildAt(2) as LinearLayout
        val label = action.getChildAt(0) as TextView
        assertEquals("清屏播放(果+)", label.text)
        action.performClick()
        assertEquals("退出清屏(果+)", label.text)
        action.performClick()
        assertEquals("清屏播放(果+)", label.text)
        assertTrue(action.performLongClick())
        assertTrue(settingsOpened)
        assertFalse(policy.fullClearEnabled)
    }
}
