package com.hmodule.adaptation

import com.hmodule.LogUtil
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Background checks never block host startup or replace hooks in an already running player. */
object RemoteAdaptation {
    private var started = false
    @Volatile var checking = false
        private set
    @Volatile var lastMessage = "GitHub 在线适配 · 本地缓存兜底"
        private set
    fun refreshHost(cacheDir: File?, pkg: String, publish: (AdaptationProfile) -> Unit = {}) {
        if (cacheDir == null) return
        synchronized(this) {
            if (started) return
            started = true; checking = true
        }
        lastMessage = "正在检查 GitHub 适配配置…"
        Thread({
            try {
                val result = updateHost(cacheDir, pkg, publish, ::fetch)
                lastMessage = if (result.profiles.isEmpty()) "已检查 ${result.checked} 个版本，适配配置已是最新"
                    else "已更新 ${result.profiles.size} 个适配配置，请重新启动红果生效"
                LogUtil.info("在线适配: $lastMessage")
            } catch (e: Exception) {
                lastMessage = "在线检查失败，保留缓存及内置适配：${e.message ?: "网络错误"}"
                LogUtil.warn(lastMessage)
            } finally {
                checking = false
            }
        }, "guoplus-online-adaptation").start()
    }

    internal fun updateHost(cacheDir: File, pkg: String, publish: (AdaptationProfile) -> Unit,
        fetch: (String) -> String): RemoteProfiles.Result {
        try {
            val result = RemoteProfiles.download(AdaptationStore.profiles(), pkg, fetch)
            result.profiles.forEach { AdaptationStore.cacheProfile(cacheDir, it) }
            AdaptationStore.remember(result.profiles)
            return result
        } finally {
            // Also sync cached data offline, or after an earlier boot's provider access was blocked.
            AdaptationStore.profiles().filter { it.names.packageName == pkg }.forEach {
                try { publish(it) } catch (e: Exception) { LogUtil.warn("宿主适配同步暂不可用，保留本地缓存: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    private fun fetch(file: String): String {
        val connection = URL(RemoteProfiles.BASE_URL + file).openConnection() as HttpURLConnection
        return readConnection(connection)
    }

    internal fun readConnection(connection: HttpURLConnection): String {
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "GuoPlus-Adaptation/2")
            connection.setRequestProperty("Cache-Control", "no-cache")
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "GitHub HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong <= ProfileJson.MAX_BYTES) { "适配文件过大" }
            return connection.inputStream.use(ProfileJson::read)
        } finally { connection.disconnect() }
    }
}
