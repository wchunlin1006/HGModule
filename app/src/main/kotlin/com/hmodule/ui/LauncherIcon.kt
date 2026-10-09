package com.hmodule.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** PackageManager persists the alias state across restarts and updates, independently of feature settings. */
object LauncherIcon {
    private fun component(context: Context) = ComponentName(context.packageName, "com.hmodule.LauncherAlias")

    fun isHidden(context: Context): Boolean = context.packageManager.getComponentEnabledSetting(component(context)) in
        setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)

    fun setHidden(context: Context, hidden: Boolean): Boolean = try {
        context.packageManager.setComponentEnabledSetting(component(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP)
        true
    } catch (_: Exception) { false }
}
