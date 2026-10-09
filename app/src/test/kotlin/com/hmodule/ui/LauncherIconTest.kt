package com.hmodule.ui

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherIconTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Suppress("DEPRECATION")
    private fun resolves(category: String): List<String> = app.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(app.packageName), 0
    ).map { it.activityInfo.name }

    @Test fun launcherIsEnabledByDefaultAndLspUsesMainActivity() {
        assertFalse(LauncherIcon.isHidden(app))
        assertTrue(resolves(Intent.CATEGORY_LAUNCHER).contains("com.hmodule.LauncherAlias"))
        assertEquals(listOf("com.hmodule.MainActivity"), resolves("de.robv.android.xposed.category.MODULE_SETTINGS"))
    }

    @Test fun hidingAndRestoringOnlyChangesLauncherResolution() {
        assertTrue(LauncherIcon.setHidden(app, true))
        assertTrue(LauncherIcon.isHidden(app))
        assertTrue(resolves(Intent.CATEGORY_LAUNCHER).isEmpty())
        assertEquals(listOf("com.hmodule.MainActivity"), resolves("de.robv.android.xposed.category.MODULE_SETTINGS"))
        assertTrue(LauncherIcon.setHidden(app, false))
        assertFalse(LauncherIcon.isHidden(app))
        assertTrue(resolves(Intent.CATEGORY_LAUNCHER).contains("com.hmodule.LauncherAlias"))
    }
}
