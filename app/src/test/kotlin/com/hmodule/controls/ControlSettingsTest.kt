package com.hmodule.controls

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlSettingsTest {
    @Test fun qualityTipOptionExpandsWithDefaultQualityAndKeepsItsChoice() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = activity.getSharedPreferences("quality-tip-option", Context.MODE_PRIVATE)
        var changes = 0
        val page = com.hmodule.ui.FeatureSettings.content(activity, prefs) { changes++ }
        activity.setContentView(page)
        fun toggle(title: String) = descendants(page).filterIsInstance<Switch>()
            .single { it.contentDescription == title }
        val quality = toggle("默认画质")
        val tip = toggle("隐藏提示")
        assertFalse(tip.isShown)
        assertFalse(tip.isChecked)
        quality.isChecked = true
        assertTrue(tip.isShown)
        (tip.parent as View).performClick()
        assertTrue(prefs.getBoolean(DefaultQuality.HIDE_TIP_KEY, false))
        quality.isChecked = false
        assertFalse(tip.isShown)
        assertTrue(prefs.getBoolean(DefaultQuality.HIDE_TIP_KEY, false))
        quality.isChecked = true
        assertTrue(tip.isShown)
        assertTrue(tip.isChecked)
        assertEquals(4, changes)
        val recreated = com.hmodule.ui.FeatureSettings.content(activity, prefs) {}
        activity.setContentView(recreated)
        assertTrue(descendants(recreated).filterIsInstance<Switch>()
            .single { it.contentDescription == "隐藏提示" }.isShown)
    }

    @Test fun channelOptionsExpandPersistAndKeepTheirIndividualChoices() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = activity.getSharedPreferences("channel-options", Context.MODE_PRIVATE)
        val settings = ControlSettings(prefs)
        // Existing whole-channel configurations continue to hide all channels by default.
        assertTrue(settings.allChannels())
        settings.show(activity) {}
        val dialog = ShadowDialog.getLatestDialog()
        fun toggle(title: String) = descendants(dialog.window!!.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == title }
        val channel = toggle("频道标签")
        val all = toggle("全部")
        val follow = toggle("关注")
        assertFalse(all.isShown)
        channel.isChecked = true
        assertTrue(all.isShown)
        assertFalse(follow.isShown)
        all.isChecked = false
        assertTrue(follow.isShown)
        follow.isChecked = true
        assertEquals(setOf(ChannelTarget.FOLLOW), settings.hiddenChannels())
        all.isChecked = true
        assertFalse(follow.isShown)
        channel.isChecked = false
        assertFalse(all.isShown)
        channel.isChecked = true
        all.isChecked = false
        assertTrue(follow.isShown)
        assertTrue(follow.isChecked)
        assertTrue(toggle("游戏中心").isShown)
        dialog.dismiss()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun qualityPopupSavesASelectionAndClosesWithoutChangingOtherSettings() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = activity.getSharedPreferences("quality-popup", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("max_quality", true).putInt(ControlSettings.OPACITY_KEY, 35).commit()
        val settings = ControlSettings(prefs)
        assertEquals(DefaultQuality.HIGHEST, settings.defaultQuality())
        var changes = 0
        settings.showQuality(activity) { changes++ }
        val dialog = ShadowDialog.getLatestDialog()
        assertNotEquals(ViewGroup.LayoutParams.MATCH_PARENT, dialog.window!!.attributes.height)
        descendants(dialog.window!!.decorView).filterIsInstance<android.widget.TextView>()
            .single { it.text.toString() == DefaultQuality.HD.title }.performClick()
        assertEquals(DefaultQuality.HD, ControlSettings(prefs).defaultQuality())
        assertEquals(35, prefs.getInt(ControlSettings.OPACITY_KEY, 0))
        assertTrue(prefs.getBoolean("max_quality", false))
        assertEquals(1, changes)
        assertFalse(dialog.isShowing)
    }

    @Test fun opacityPopupKeepsLivePersistenceAndClosesOverTheSettingsPage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = activity.getSharedPreferences("opacity-popup", Context.MODE_PRIVATE)
        prefs.edit().putInt(ControlSettings.OPACITY_KEY, 35).commit()
        var changes = 0
        ControlSettings(prefs).showOpacity(activity) { changes++ }
        val dialog = ShadowDialog.getLatestDialog()
        assertNotEquals(ViewGroup.LayoutParams.MATCH_PARENT, dialog.window!!.attributes.height)
        val views = descendants(dialog.window!!.decorView)
        val seek = views.filterIsInstance<android.widget.SeekBar>().single()
        assertEquals(35, seek.progress)
        seek.keyProgressIncrement = 1
        seek.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(36, prefs.getInt(ControlSettings.OPACITY_KEY, 0))
        assertEquals(1, changes)
        views.filterIsInstance<android.widget.TextView>().single { it.text.toString() == "关闭" }.performClick()
        assertFalse(dialog.isShowing)
    }

    @Test fun legacyProgressSettingMovesToSelectiveConfigurationOnce() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = activity.getSharedPreferences("legacy-progress", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("progress_off", true).commit()
        assertTrue(ControlTarget.PROGRESS in ControlSettings(prefs).selected())
        assertTrue(prefs.getBoolean("control_hide", false))
        assertFalse(prefs.contains("progress_off"))
        prefs.edit().putBoolean(ControlTarget.PROGRESS.preferenceKey, false).commit()
        assertFalse(ControlTarget.PROGRESS in ControlSettings(prefs).selected())
    }

    @Test fun explicitSelectiveProgressChoiceWinsOverTheOldToggle() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = activity.getSharedPreferences("existing-progress", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("progress_off", true)
            .putBoolean(ControlTarget.PROGRESS.preferenceKey, false).commit()
        assertFalse(ControlTarget.PROGRESS in ControlSettings(prefs).selected())
        assertFalse(prefs.contains("progress_off"))
        assertFalse(prefs.getBoolean("control_hide", false))
    }

    @Test fun newPlaybackControlsCanBeConfiguredByRowWithoutChangingOtherPreferences() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val preferences = activity.getSharedPreferences("controls", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("control_hide", true).putBoolean("guoplus_clean_screen", true).commit()
        var changed = 0
        ControlSettings(preferences).show(activity) { changed++ }
        val dialog = ShadowDialog.getLatestDialog()
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val switches = descendants(dialog.window!!.decorView).filterIsInstance<Switch>()
        val speed = switches.single { it.contentDescription == ControlTarget.PLAYBACK_SPEED.title }
        assertFalse(speed.isChecked)
        (speed.parent as View).performClick()
        assertTrue(speed.isChecked)
        assertTrue(preferences.getBoolean(ControlTarget.PLAYBACK_SPEED.preferenceKey, false))
        assertFalse(preferences.getBoolean(ControlTarget.PLAYER_MORE.preferenceKey, false))
        assertTrue(preferences.getBoolean("control_hide", false))
        assertTrue(preferences.getBoolean("guoplus_clean_screen", false))
        assertEquals(1, changed)
        val required = setOf(ControlTarget.BACK_EPISODE, ControlTarget.PLAYER_MORE, ControlTarget.DANMAKU_ENTRY,
            ControlTarget.EPISODE_SELECTOR, ControlTarget.FULLSCREEN_SWITCH, ControlTarget.PROGRESS)
        assertTrue(required.all { target -> switches.any { it.contentDescription == target.title } })
        dialog.dismiss()
    }
}
