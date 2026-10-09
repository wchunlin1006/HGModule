package com.hmodule.controls

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Switch
import com.hmodule.MainActivity
import com.hmodule.UpdateChecker
import com.hmodule.config.ModuleConfig
import com.hmodule.hooks.TargetNames
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainNavigationTest {
    @Before fun offline() {
        UpdateChecker::class.java.getDeclaredField("checking").apply { isAccessible = true }.setBoolean(null,true)
        ModuleConfig.local(RuntimeEnvironment.getApplication()).edit().clear().commit()
    }
    @After fun finish() {
        UpdateChecker::class.java.getDeclaredField("checking").apply { isAccessible = true }.setBoolean(null,false)
    }
    private fun views(v: View): List<View> = listOf(v) + if(v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun labels(activity: MainActivity) = views(activity.window.decorView).filter { it.isShown }.filterIsInstance<TextView>()
    private fun installSupportedHost() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(PackageInfo().apply {
            packageName = TargetNames.CN_PACKAGE; versionName = "7.3.2.32"; longVersionCode = 73232
            applicationInfo = ApplicationInfo().apply { packageName = TargetNames.CN_PACKAGE }
        })
    }
    @Test fun unsupportedInactiveLaunchHasNoNavigationOrFeatureSettings() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val labels = labels(controller.get())
        assertTrue(labels.any { it.text.toString() == "模块未激活" })
        assertFalse(labels.any { it.text.toString() in setOf("首页","功能","设置","总开关") })
        controller.pause().stop().destroy()
    }
    @Test fun supportedLaunchNavigatesSharedFeaturesAndMovesProjectActionsToSettings() {
        installSupportedHost()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        fun click(text: String) {
            labels(activity).single { it.text.toString() == text }.performClick()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350))
        }
        assertFalse(labels(activity).any { it.text.toString() == "项目与更新" })
        click("功能")
        assertTrue(labels(activity).any { it.text.toString() == "模块设置" })
        val toggle = views(activity.window.decorView).filter { it.isShown }.filterIsInstance<Switch>().single { it.contentDescription == "总开关" }
        toggle.isChecked = true
        assertTrue(ModuleConfig.local(activity).getBoolean("master_on",false))
        click("设置")
        assertTrue(labels(activity).any { it.text.toString() == "项目与更新" })
        assertTrue(labels(activity).any { it.text.toString() == "主题设置" })
        assertTrue(labels(activity).any { it.text.toString() == "清除配置" })
        activity.onBackPressed()
        assertFalse(labels(activity).any { it.text.toString() == "项目与更新" })
        controller.pause().stop().destroy()
    }
    @Test fun manifestExposesLsposedSettingsAndNoLauncherIcon() {
        val manager = RuntimeEnvironment.getApplication().packageManager
        val packageName = RuntimeEnvironment.getApplication().packageName
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val module = Intent(Intent.ACTION_MAIN).addCategory("de.robv.android.xposed.category.MODULE_SETTINGS").setPackage(packageName)
        assertTrue(manager.queryIntentActivities(launcher,0).isEmpty())
        assertEquals(MainActivity::class.java.name,manager.queryIntentActivities(module,0).single().activityInfo.name)
    }
    @Test fun hostEntryOpensFunctionsWithoutCardAndReusedActivityCanRetarget() {
        installSupportedHost()
        val entry=Intent().putExtra(MainActivity.EXTRA_PAGE,MainActivity.PAGE_FEATURES)
        val controller=Robolectric.buildActivity(MainActivity::class.java,entry).setup()
        val activity=controller.get()
        assertTrue(labels(activity).any { it.text.toString()=="总开关" })
        assertTrue(labels(activity).any { it.text.toString()=="首页" })
        assertFalse(labels(activity).any { it.text.toString() in setOf("已适配","待适配","模块未激活") })
        controller.newIntent(Intent())
        assertTrue(labels(activity).any { it.text.toString()=="模块未激活" })
        controller.newIntent(entry)
        assertTrue(labels(activity).any { it.text.toString()=="总开关" })
        controller.pause().stop().destroy()
    }
    @Test fun hostEntryCannotBypassUnsupportedInactiveLock() {
        val controller=Robolectric.buildActivity(MainActivity::class.java,
            Intent().putExtra(MainActivity.EXTRA_PAGE,MainActivity.PAGE_FEATURES)).setup()
        assertFalse(labels(controller.get()).any { it.text.toString() in setOf("总开关","功能","设置") })
        controller.pause().stop().destroy()
    }

    @Test fun neighbourShowsPartialAdaptationButDoesNotBypassInactiveLock() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(PackageInfo().apply {
            packageName = TargetNames.CN_PACKAGE; versionName = "7.3.10.32"; longVersionCode = 731032
            applicationInfo = ApplicationInfo().apply { packageName = TargetNames.CN_PACKAGE }
        })
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val shown = labels(controller.get())
        assertTrue(shown.any { it.text.toString() == "部分适配" })
        assertTrue(shown.any { it.text.toString().contains("参考 7.3.9.32") })
        assertFalse(shown.any { it.text.toString() in setOf("总开关", "功能", "设置") })
        controller.pause().stop().destroy()
    }
}
