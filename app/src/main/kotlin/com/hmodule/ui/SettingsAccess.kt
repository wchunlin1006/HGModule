package com.hmodule.ui

object SettingsAccess {
    /** Both activation and adaptation missing: home is the only available page. */
    fun canNavigate(activated: Boolean, supported: Boolean) = activated || supported
}
