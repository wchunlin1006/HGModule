package com.hmodule

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import com.hmodule.hooks.Hooks
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

class MainHook : XposedModule() {

    companion object {
        private const val TAG = "ZongHe"
        @Volatile private var currentProcessName: String = ""
        private const val DEMO_PACKAGE = "com.hmodule"

        private val TARGET_PACKAGES = setOf(
            "com.phoenix.read",
            "com.phoenix.read.oversea.gp",
        )
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        currentProcessName = param.processName ?: ""
        LogUtil.init(param.processName)
        try { com.hmodule.adaptation.AdaptationStore.loadApk(moduleApplicationInfo.sourceDir) }
        catch (e: Exception) { LogUtil.warn("内置适配 JSON 读取失败: ${e.message}") }
        LogUtil.info("══════════ 模块加载 ══════════")
        LogUtil.info("进程=${param.processName} | systemServer=${param.isSystemServer}")
        LogUtil.info("框架=${frameworkName} v${frameworkVersion} | API=${apiVersion}")
        LogUtil.info("日志文件=${LogUtil.getFilePath()}")
        LogUtil.info("══════════════════════════════")
        if (currentProcessName.substringBefore(':') in TARGET_PACKAGES) {
            try { com.hmodule.adaptation.AdaptationStore.loadSnapshot(getRemotePreferences(com.hmodule.adaptation.AdaptationStore.REMOTE_GROUP)) }
            catch (e: Exception) { LogUtil.warn("框架适配缓存读取失败: ${e.message}") }
            try { com.hmodule.config.ModuleConfig.useRemoteSnapshot(getRemotePreferences(com.hmodule.config.ModuleConfig.REMOTE_GROUP)) }
            catch (e: Exception) { LogUtil.warn("果+框架配置备份读取失败: ${e.message}") }
        }
        log(Log.INFO, TAG, "模块已加载 | 进程=${param.processName} | API=${apiVersion}")

        try {
            val ver = BuildConfig.VERSION_NAME.substringBefore(' ').substringBefore('(')
            LogUtil.info("更新检查启动 | 当前版本=$ver")
            UpdateChecker.checkUpdate(ver) { latest ->
                if (latest != null) {
                    LogUtil.info("发现新版本 $latest（当前 $ver）")
                    Hooks.showUpdateDialogIfAvailable()
                } else if (UpdateChecker.lastError != null) {
                    LogUtil.warn("更新检查失败: ${UpdateChecker.lastError}")
                }
            }
        } catch (e: Exception) {
            LogUtil.error("更新检查启动失败", e)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        if (pkg !in TARGET_PACKAGES && pkg != DEMO_PACKAGE) return

        LogUtil.info("onPackageLoaded: $pkg | first=${param.isFirstPackage}")
        val cl = param.defaultClassLoader
        if (cl == null) { LogUtil.warn("defaultClassLoader=null"); return }

        if (pkg == DEMO_PACKAGE) {
            LogUtil.info("  → 安装演示 Hook")
            try { Hooks.installDemoHooks(this, cl); LogUtil.info("  ✓ 完成") }
            catch (e: Exception) { LogUtil.error("演示 Hook 失败", e) }
        }
        if (pkg in TARGET_PACKAGES) {
            LogUtil.info("  → 目标包，等待 onPackageReady")
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        if (pkg !in TARGET_PACKAGES && pkg != DEMO_PACKAGE) return

        LogUtil.info("onPackageReady: $pkg")
        LogUtil.info("  classLoader=${param.classLoader}")
        LogUtil.info("  appComponentFactory=${param.appComponentFactory}")

        if (pkg in TARGET_PACKAGES) {

            val proc = currentProcessName
            val isWebViewSandbox = proc.contains(":sandboxed_process") ||
                proc.contains(":privileged_process") ||
                proc.contains(":renderer")
            if (isWebViewSandbox) {
                LogUtil.info("  → WebView 沙箱/渲染进程，跳过 UI 业务 Hook | process=$proc")
                return
            }
            LogUtil.info("  → 安装业务 Hook | process=$proc | main=${proc == pkg}")
            try {
                Hooks.installBusinessHooks(this, param.classLoader, pkg, param.applicationInfo, checkOnline = proc == pkg)
                LogUtil.info("  ✓ 业务 Hook 安装完成")
            } catch (e: Exception) {
                LogUtil.error("业务 Hook 安装失败", e)
            }
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        LogUtil.info("system_server 启动")
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        LogUtil.info("热重载中...")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        LogUtil.info("热重载完成, 旧 hook=${param.oldHookHandles.size}")
        LogUtil.diagDump(true)
    }
}
