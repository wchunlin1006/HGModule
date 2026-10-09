package com.hmodule.controls

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import com.hmodule.config.ConfigurationProvider
import com.hmodule.config.ModuleConfig
import com.hmodule.ui.FeatureSettings
import com.hmodule.ui.MiuixUi
import com.hmodule.ui.SettingsAccess
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntegratedSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        ShadowBinder.setCallingUid(android.os.Process.myUid())
        ModuleConfig.local(app).edit().clear().commit()
        MiuixUi.preferences = null
    }
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun navigationIsLockedOnlyWhenActivationAndAdaptationAreBothMissing() {
        assertFalse(SettingsAccess.canNavigate(false,false))
        assertTrue(SettingsAccess.canNavigate(true,false))
        assertTrue(SettingsAccess.canNavigate(false,true))
        assertTrue(SettingsAccess.canNavigate(true,true))
    }
    @Test fun providerMigratesOnlyOnceAndDoesNotResurrectSettingsAfterClear() {
        val provider = Robolectric.buildContentProvider(ConfigurationProvider::class.java).create().get()
        val prefs = ModuleConfig.local(app)
        prefs.edit().putBoolean("master_on",false).commit()
        val legacy = ModuleConfig.bundle(mapOf("master_on" to true,"top_zone" to true,"player_bar" to true,"control_hide" to true))
        provider.call("migrate",null,legacy)
        assertFalse(prefs.getBoolean("master_on",true))
        assertTrue(prefs.getBoolean("control_hide",false))
        assertTrue(prefs.getBoolean("top_zone",false))
        assertFalse(prefs.contains("player_bar"))
        prefs.edit().putString(ModuleConfig.THEME,"dark").commit()
        assertTrue(ModuleConfig.clear(app,prefs))
        provider.call("migrate",null,legacy)
        assertFalse(prefs.getBoolean("control_hide",false))
        assertFalse(prefs.getBoolean("top_zone",false))
        assertEquals("dark",prefs.getString(ModuleConfig.THEME,null))
        assertEquals(setOf(ModuleConfig.THEME,"configuration_initialized",ModuleConfig.RESET),prefs.all.keys)
    }
    @Test fun hostAndModuleShareWritesAndClearNotifications() {
        Robolectric.buildContentProvider(ConfigurationProvider::class.java).create(ModuleConfig.AUTHORITY).get()
        val hostContext = object : ContextWrapper(app) {
            override fun getPackageName() = "com.phoenix.read"
            override fun getApplicationContext(): Context = this
        }
        val old = app.getSharedPreferences("migration-source",0)
        old.edit().clear().putBoolean("master_on",true).putInt(ControlSettings.OPACITY_KEY,70).commit()
        val host = ModuleConfig.open(hostContext,old)
        val module = ModuleConfig.local(app)
        assertTrue(host.getBoolean("master_on",false))
        var changed = 0
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed++ }
        host.registerOnSharedPreferenceChangeListener(listener)
        module.edit().putInt(ControlSettings.OPACITY_KEY,40).commit()
        ShadowLooper.idleMainLooper()
        assertEquals(40,host.getInt(ControlSettings.OPACITY_KEY,0))
        host.edit().putBoolean("guoplus_clean_screen",true).commit()
        assertTrue(module.getBoolean("guoplus_clean_screen",false))
        ModuleConfig.clear(app,module)
        ShadowLooper.idleMainLooper()
        assertFalse(host.getBoolean("master_on",false))
        assertFalse(host.getBoolean("guoplus_clean_screen",false))
        assertEquals(100,ControlSettings(host).opacityPercent())
        assertTrue(changed >= 3)
        host.unregisterOnSharedPreferenceChangeListener(listener)
    }
    @Test fun untrustedUidCannotReadOrWriteConfiguration() {
        val provider = Robolectric.buildContentProvider(ConfigurationProvider::class.java).create().get()
        ShadowBinder.setCallingUid(556677)
        try {
            assertThrows(SecurityException::class.java) { provider.call("read",null,null) }
            assertThrows(SecurityException::class.java) { provider.call("write",null,null) }
        } finally { ShadowBinder.setCallingUid(android.os.Process.myUid()) }
    }
    @Test fun explicitThemeOverridesSystemAndSystemModeFollowsConfiguration() {
        val prefs = ModuleConfig.local(app)
        MiuixUi.preferences = prefs
        prefs.edit().putString(ModuleConfig.THEME,"dark").commit()
        assertTrue(MiuixUi.isDark(app))
        val dark = MiuixUi.palette(app)
        prefs.edit().putString(ModuleConfig.THEME,"light").commit()
        assertFalse(MiuixUi.isDark(app))
        assertNotEquals(dark.page,MiuixUi.palette(app).page)
        prefs.edit().putString(ModuleConfig.THEME,"system").commit()
        assertEquals(app.resources.configuration.uiMode and 0x30 == 0x20,MiuixUi.isDark(app))
    }
    @Test fun sharedFeaturePageHasNewCopyCurrentOpacityAndNoRemovedGroups() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = ModuleConfig.local(app)
        prefs.edit().putInt(ControlSettings.OPACITY_KEY,45).commit()
        val root = FeatureSettings.content(activity,prefs) {}
        val texts = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.containsAll(listOf("总开关","所用功能的总控制开关","隐藏小白条",
            "暂停时退出清屏","在清屏播放（果+）时，暂停播放临时退出清屏","45%","顶部下滑拦截",
            "拦截从屏幕顶部（状态栏）区域向下滑的手势")))
        assertFalse(texts.any { it in setOf("选集相关功能","实验性功能","模块总开关") })
        val toggles = descendants(root).filterIsInstance<Switch>()
        toggles.single { it.contentDescription == "顶部下滑拦截" }.isChecked = true
        assertTrue(prefs.getBoolean("top_zone",false))
    }
    @Test fun declarationIsIndependentEvenInsideInformationContainer() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(activity).apply { text = "短剧标题" }
        val declaration = TextView(activity).apply { text = "作者声明：内容由AI生成" }
        info.addView(title); info.addView(declaration); root.addView(info)
        val manager = ControlVisibilityManager { it() }
        manager.bind(info,ControlTarget.SERIES_INFO)
        manager.policy = ControlPolicy(true,true,setOf(ControlTarget.SERIES_INFO))
        manager.sync(root)
        assertEquals(View.VISIBLE,info.visibility)
        assertEquals(View.GONE,title.visibility)
        assertEquals(View.VISIBLE,declaration.visibility)
        manager.policy = manager.policy.copy(selected = setOf(ControlTarget.AUTHOR_DECLARATION))
        manager.sync(root)
        assertEquals(View.VISIBLE,title.visibility)
        assertEquals(View.GONE,declaration.visibility)
        manager.policy = manager.policy.copy(masterEnabled = false)
        manager.sync(root)
        assertEquals(View.VISIBLE,declaration.visibility)
    }
}
