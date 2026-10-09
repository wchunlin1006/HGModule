package com.hmodule

import android.app.Application
import com.hmodule.config.ModuleConfig

/** Connect even when only the configuration provider starts this process. */
class ModuleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try { com.hmodule.adaptation.AdaptationStore.loadAssets(this) }
        catch (e: Exception) { LogUtil.warn("内置适配 JSON 加载失败: ${e.message}") }
        ModuleConfig.local(this)
        LauncherStatus.service
    }
}
