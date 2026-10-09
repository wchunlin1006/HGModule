package com.hmodule.config

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdStartConfigurationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val host get() = object : ContextWrapper(app) {
        override fun getPackageName() = "com.phoenix.read"
        override fun getApplicationContext(): Context = this
    }
    @Before fun reset() {
        ShadowBinder.setCallingUid(android.os.Process.myUid())
        listOf("guoplus_configuration_cache", "guoplus_configuration_pending", "cold-legacy", "cold-remote")
            .forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        ModuleConfig.local(app).edit().clear().commit()
    }

    @Test fun blockedProviderKeepsSettingsAndDurableEditsAcrossHostRestart() {
        val cache = app.getSharedPreferences("guoplus_configuration_cache", 0)
        cache.edit().putBoolean("master_on", true).putInt("guoplus_control_opacity", 50).commit()
        val offline = ModuleConfig.openHost(host, null) { _, _ -> null }
        assertTrue(offline.getBoolean("master_on", false))
        assertEquals(50, offline.getInt("guoplus_control_opacity", 100))
        assertTrue(offline.edit().putInt("guoplus_control_opacity", 65).putBoolean("control_hide", true).commit())
        val restarted = ModuleConfig.openHost(host, null) { _, _ -> null }
        assertEquals(65, restarted.getInt("guoplus_control_opacity", 100))
        assertTrue(restarted.getBoolean("control_hide", false))
        val provider = Robolectric.buildContentProvider(ConfigurationProvider::class.java).create().get()
        val recovered = ModuleConfig.openHost(host, null) { method, data -> provider.call(method, null, data) }
        assertEquals(65, recovered.getInt("guoplus_control_opacity", 100))
        assertTrue(ModuleConfig.local(app).getBoolean("control_hide", false))
        assertTrue(app.getSharedPreferences("guoplus_configuration_pending", 0).all.isEmpty())
    }

    @Test fun frameworkSnapshotSupersedesCacheButNeverPendingHostEdits() {
        val remote = app.getSharedPreferences("cold-remote", 0)
        remote.edit().putBoolean("configuration_initialized", true).putBoolean("master_on", false)
            .putInt("guoplus_control_opacity", 40).commit()
        val cache = app.getSharedPreferences("guoplus_configuration_cache", 0)
        cache.edit().putBoolean("master_on", true).putInt("guoplus_control_opacity", 50).commit()
        val offline = ModuleConfig.openHost(host, null, remote) { _, _ -> throw IllegalArgumentException("provider rejected") }
        assertFalse(offline.getBoolean("master_on", true))
        assertEquals(40, offline.getInt("guoplus_control_opacity", 100))
        offline.edit().putInt("guoplus_control_opacity", 75).apply()
        remote.edit().putInt("guoplus_control_opacity", 20).commit()
        ShadowLooper.idleMainLooper()
        assertEquals(75, offline.getInt("guoplus_control_opacity", 100))
    }

    @Test fun offlineClearRemovesLegacySettingsAndRecoveryNotifiesRemovedKeys() {
        val legacy = app.getSharedPreferences("cold-legacy", 0)
        legacy.edit().putBoolean("control_hide", true).putString(ModuleConfig.THEME, "dark").commit()
        val provider = Robolectric.buildContentProvider(ConfigurationProvider::class.java).create().get()
        var available = false
        val offline = ModuleConfig.openHost(host, legacy) { method, data ->
            if (available) provider.call(method, null, data) else null
        }
        assertTrue(offline.getBoolean("control_hide", false))
        val changed = mutableSetOf<String?>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed.add(key) }
        offline.registerOnSharedPreferenceChangeListener(listener)
        assertTrue(ModuleConfig.clear(host, offline))
        assertFalse(offline.getBoolean("control_hide", false))
        assertTrue(changed.contains("control_hide"))
        available = true
        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS)
        val module = ModuleConfig.local(app)
        assertEquals(setOf(ModuleConfig.THEME, "configuration_initialized", ModuleConfig.RESET), module.all.keys)
        assertEquals("dark", module.getString(ModuleConfig.THEME, null))
        provider.call("migrate", null, ModuleConfig.bundle(legacy.all))
        assertFalse(module.contains("control_hide"))
    }

    @Test fun moduleClearDiscardsEditsQueuedBeforeClear() {
        val provider = Robolectric.buildContentProvider(ConfigurationProvider::class.java).create().get()
        var available = false
        val hostPrefs = ModuleConfig.openHost(host, null) { method, data ->
            if (available) provider.call(method, null, data) else null
        }
        hostPrefs.edit().putBoolean("control_hide", true).apply()
        ModuleConfig.clear(app, ModuleConfig.local(app))
        available = true
        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS)
        assertFalse(hostPrefs.contains("control_hide"))
        assertFalse(ModuleConfig.local(app).contains("control_hide"))
        assertTrue(app.getSharedPreferences("guoplus_configuration_pending", 0).all.isEmpty())
    }
}
