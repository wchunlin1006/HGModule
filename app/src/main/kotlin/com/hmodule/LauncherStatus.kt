package com.hmodule

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** A live framework connection establishes activation; a version match alone cannot. */
object LauncherStatus {
    @Volatile var service: XposedService? = null
        private set
    private val listeners = linkedSetOf<() -> Unit>()
    init {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
                try { com.hmodule.adaptation.AdaptationStore.publishTo(bound.getRemotePreferences(com.hmodule.adaptation.AdaptationStore.REMOTE_GROUP)) }
                catch (e: Exception) { LogUtil.warn("适配缓存连接失败: ${e.message}") }
                try { com.hmodule.config.ModuleConfig.publishTo(bound.getRemotePreferences(com.hmodule.config.ModuleConfig.REMOTE_GROUP)) }
                catch (e: Exception) { LogUtil.warn("果+框架配置备份连接失败: ${e.message}") }
                notifyListeners()
            }
            override fun onServiceDied(bound: XposedService) {
                service = null
                com.hmodule.adaptation.AdaptationStore.publishTo(null)
                com.hmodule.config.ModuleConfig.publishTo(null)
                notifyListeners()
            }
        })
    }
    fun subscribe(listener: () -> Unit) { synchronized(listeners) { listeners.add(listener) }; listener() }
    fun unsubscribe(listener: () -> Unit) { synchronized(listeners) { listeners.remove(listener) } }
    private fun notifyListeners() { synchronized(listeners) { listeners.toList() }.forEach { it() } }
}
