package com.hmodule.controls

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomNavigationControlsTest {
    private class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val nav = com.dragon.read.widget.BottomTabFrameLayout(activity)
        val slots = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val manager = ControlVisibilityManager { it() }

        init { root.addView(nav); nav.addView(slots) }

        fun tab(label: String, iconOnly: Boolean = false): FrameLayout = FrameLayout(activity).apply {
            val icon = View(activity)
            if (iconOnly) icon.contentDescription = label
            addView(icon)
            if (!iconOnly) addView(TextView(activity).apply { text = label })
            slots.addView(this, LinearLayout.LayoutParams(90, 50).apply {
                leftMargin = 3; rightMargin = 5
            })
        }

        fun hide(vararg targets: ControlTarget) {
            manager.policy = ControlPolicy(true, true, targets.toSet())
            manager.sync(root)
        }
    }

    @Test fun welfareAliasesHideTheWholeSlotAndRestoreAllOriginalLayoutParameters() {
        for (label in listOf("福利", "福利中心", "领福利")) {
            val f = Fixture()
            val home = f.tab("首页")
            val welfare = f.tab(label)
            val profile = f.tab("我的")
            val outside = TextView(f.activity).apply { text = label }
            f.root.addView(outside)
            f.hide(ControlTarget.WELFARE)
            assertEquals(label, View.GONE, welfare.visibility)
            assertEquals(View.VISIBLE, outside.visibility)
            for (tab in listOf(home, profile)) {
                val lp = tab.layoutParams as LinearLayout.LayoutParams
                assertEquals(0, lp.width)
                assertEquals(1f, lp.weight, 0f)
                assertEquals(0, lp.leftMargin)
                assertEquals(0, lp.rightMargin)
            }
            f.manager.policy = f.manager.policy.copy(hideEnabled = false)
            f.manager.sync(f.root)
            for (tab in listOf(home, welfare, profile)) {
                val lp = tab.layoutParams as LinearLayout.LayoutParams
                assertEquals(View.VISIBLE, tab.visibility)
                assertEquals(90, lp.width)
                assertEquals(0f, lp.weight, 0f)
                assertEquals(3, lp.leftMargin)
                assertEquals(5, lp.rightMargin)
            }
        }
    }

    @Test fun iconOnlyTabsAndEmptyTextWithAccessibilityLabelsParticipateInReflow() {
        val f = Fixture()
        val home = f.tab("首页", iconOnly = true)
        val welfare = f.tab("", iconOnly = true)
        welfare.addView(TextView(f.activity).apply { contentDescription = "领福利" })
        val profile = f.tab("我的", iconOnly = true)
        f.hide(ControlTarget.WELFARE)
        assertEquals(View.GONE, welfare.visibility)
        for (tab in listOf(home, profile)) {
            assertEquals(View.VISIBLE, tab.visibility)
            assertEquals(1f, (tab.layoutParams as LinearLayout.LayoutParams).weight, 0f)
        }
        f.manager.restoreAll()
        assertTrue(listOf(home, welfare, profile).all { it.visibility == View.VISIBLE && it.layoutParams.width == 90 })
    }

    @Test fun resourceMappedIconOnlyTabsUseTheSameSlotAndReflowRules() {
        val f = Fixture()
        val home = f.tab("首页")
        val welfare = f.tab("", iconOnly = true)
        welfare.getChildAt(0).id = android.R.id.icon
        val profile = f.tab("我的")
        f.manager.resourceOverrides = mapOf("icon" to "welfare")
        f.hide(ControlTarget.WELFARE)
        assertEquals(View.GONE, welfare.visibility)
        assertTrue(listOf(home, profile).all { (it.layoutParams as LinearLayout.LayoutParams).weight == 1f })
        f.manager.resourceOverrides = mapOf("icon" to "mall")
        f.manager.sync(f.root)
        assertEquals("An adaptation change must replace the old welfare binding", View.VISIBLE, welfare.visibility)
        assertTrue(listOf(home, welfare, profile).all { it.layoutParams.width == 90 })
        f.hide(ControlTarget.MALL)
        assertEquals(View.GONE, welfare.visibility)
    }

    @Test fun reusedHiddenSlotKeepsNativeVisibilityAndUsesItsNewLabel() {
        val f = Fixture()
        val home = f.tab("首页")
        val reused = f.tab("福利")
        val profile = f.tab("我的")
        val label = reused.getChildAt(1) as TextView
        f.hide(ControlTarget.WELFARE)
        assertEquals(View.GONE, reused.visibility)
        label.text = "商城"
        f.manager.sync(f.root)
        assertEquals("Do not preserve the module's GONE state as a native state", View.VISIBLE, reused.visibility)
        assertTrue(listOf(home, reused, profile).all { it.layoutParams.width == 90 })
        f.hide(ControlTarget.MALL)
        assertEquals(View.GONE, reused.visibility)
        label.text = "福利中心"
        reused.visibility = f.manager.requestedVisibility(reused, View.INVISIBLE)
        f.manager.sync(f.root)
        assertEquals("The latest native visibility must survive a target change", View.INVISIBLE, reused.visibility)
        f.manager.restoreAll()
        assertEquals(View.INVISIBLE, reused.visibility)
    }

    @Test fun removingALabelOrMovingTheSlotOutsideNavigationDropsItsOldBinding() {
        for (moveOutside in listOf(false, true)) {
            val f = Fixture()
            val home = f.tab("首页")
            val welfare = f.tab("福利")
            val profile = f.tab("我的")
            f.hide(ControlTarget.WELFARE)
            if (moveOutside) {
                f.slots.removeView(welfare)
                f.root.addView(welfare)
            } else (welfare.getChildAt(1) as TextView).text = "活动"
            f.manager.sync(f.root)
            assertEquals(View.VISIBLE, welfare.visibility)
            assertEquals(View.VISIBLE, f.manager.requestedVisibility(welfare, View.VISIBLE))
            assertTrue(listOf(home, profile).all { it.layoutParams.width == 90 })
        }
    }

    @Test fun twoSlotsWithIconAndTextNeverHideTheirSharedContainer() {
        val f = Fixture()
        val home = f.tab("首页")
        val welfare = f.tab("福利")
        home.getChildAt(0).contentDescription = "首页"
        welfare.getChildAt(0).contentDescription = "福利"
        f.hide(ControlTarget.WELFARE)
        assertEquals(View.VISIBLE, f.slots.visibility)
        assertEquals(View.VISIBLE, home.visibility)
        assertEquals(View.GONE, welfare.visibility)
        assertEquals(1f, (home.layoutParams as LinearLayout.LayoutParams).weight, 0f)
    }

    @Test fun unknownReplacementStillSharesTheRowWhileAnotherTabRemainsHidden() {
        val f = Fixture()
        val home = f.tab("首页")
        val welfare = f.tab("福利")
        val replaced = f.tab("商城")
        f.hide(ControlTarget.WELFARE, ControlTarget.MALL)
        (replaced.getChildAt(1) as TextView).text = "活动"
        f.manager.sync(f.root)
        assertEquals(View.GONE, welfare.visibility)
        assertEquals(View.VISIBLE, replaced.visibility)
        for (tab in listOf(home, replaced)) {
            val lp = tab.layoutParams as LinearLayout.LayoutParams
            assertEquals(0, lp.width)
            assertEquals(1f, lp.weight, 0f)
        }
        f.manager.policy = f.manager.policy.copy(hideEnabled = false)
        f.manager.sync(f.root)
        assertTrue(listOf(home, welfare, replaced).all { it.layoutParams.width == 90 })
        (replaced.getChildAt(1) as TextView).text = "领福利"
        f.hide(ControlTarget.WELFARE)
        assertEquals(View.GONE, replaced.visibility)
    }

    @Test fun stableAutomaticBindingsDoNotRewriteLayoutParametersDuringRepeatedSync() {
        val f = Fixture()
        var parameterWrites = 0
        val tabs = listOf("首页", "领福利", "我的").map { label ->
            object : FrameLayout(f.activity) {
                override fun setLayoutParams(params: android.view.ViewGroup.LayoutParams?) {
                    parameterWrites++
                    super.setLayoutParams(params)
                }
            }.apply {
                addView(View(f.activity).apply { contentDescription = label })
                f.slots.addView(this, LinearLayout.LayoutParams(90, 50))
            }
        }
        f.hide(ControlTarget.WELFARE)
        parameterWrites = 0
        repeat(30) { f.manager.sync(f.root) }
        assertEquals(View.GONE, tabs[1].visibility)
        assertEquals(0, parameterWrites)
    }

    @Test fun frameSlotsReflowAroundMultipleHiddenTabsAndRestoreTheirMarginsAndGravity() {
        val f = Fixture()
        f.nav.removeView(f.slots)
        val slots = FrameLayout(f.activity)
        f.nav.addView(slots)
        val labels = listOf("首页", "商城", "领福利", "我的")
        val tabs = labels.mapIndexed { index, label -> FrameLayout(f.activity).apply {
            addView(View(f.activity))
            addView(TextView(f.activity).apply { text = label })
            slots.addView(this, FrameLayout.LayoutParams(80, 50, Gravity.BOTTOM or Gravity.START).apply {
                marginStart = index * 80 + 4; marginEnd = 6
            })
        } }
        slots.layout(0, 0, 400, 50)
        f.hide(ControlTarget.MALL, ControlTarget.WELFARE)
        assertEquals(View.GONE, tabs[1].visibility)
        assertEquals(View.GONE, tabs[2].visibility)
        for ((index, tab) in listOf(tabs[0], tabs[3]).withIndex()) {
            val lp = tab.layoutParams as FrameLayout.LayoutParams
            assertEquals(200, lp.width)
            assertEquals(index * 200, lp.marginStart)
            assertEquals(0, lp.marginEnd)
        }
        f.manager.policy = f.manager.policy.copy(masterEnabled = false)
        f.manager.sync(f.root)
        for ((index, tab) in tabs.withIndex()) {
            val lp = tab.layoutParams as FrameLayout.LayoutParams
            assertEquals(View.VISIBLE, tab.visibility)
            assertEquals(80, lp.width)
            assertEquals(index * 80 + 4, lp.marginStart)
            assertEquals(6, lp.marginEnd)
            assertEquals(Gravity.BOTTOM or Gravity.START, lp.gravity)
        }
    }
}
