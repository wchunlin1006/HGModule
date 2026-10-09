package com.hmodule

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    const val REPO_URL = "https://github.com/wchunlin1006/HGModule"
    const val RELEASES_URL = "$REPO_URL/releases/latest"

    @Volatile var currentVersion: String = ""
        private set
    @Volatile var latestVersion: String? = null
        private set
    @Volatile var hasUpdate: Boolean = false
        private set
    @Volatile var checking: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    @Volatile private var shownOnce = false

    private val callbacks = mutableListOf<(String?) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkUpdate(version: String, onResult: ((String?) -> Unit)? = null) {
        if (currentVersion.isEmpty()) currentVersion = version
        if (onResult != null) synchronized(callbacks) { callbacks.add(onResult) }
        if (checking) return
        checking = true
        Thread {
            var newer: String? = null
            try {
                val tag = fetchLatestTag()
                latestVersion = tag
                lastError = null
                if (tag != null && compareVersions(tag, version) > 0) {
                    hasUpdate = true
                    newer = tag
                } else {
                    hasUpdate = false
                }
            } catch (e: Exception) {
                lastError = e.message ?: "网络错误"
                hasUpdate = false
            } finally {
                checking = false
            }
            mainHandler.post {
                val list = synchronized(callbacks) { callbacks.toList().also { callbacks.clear() } }
                list.forEach { it(newer) }
            }
        }.start()
    }

    fun showUpdateDialogIfNeeded(ctx: Context) {
        if (!hasUpdate) return
        val latest = latestVersion ?: return
        if (ctx !is Activity || ctx.isFinishing) return
        if (shownOnce) return
        shownOnce = true
        showUpdateDialog(ctx, currentVersion.ifEmpty { "未知" }, latest)
    }

    fun resetShown() { shownOnce = false }

    fun showUpdateDialog(ctx: Context, current: String, latest: String) {
        try {
            AlertDialog.Builder(ctx)
                .setTitle("发现新版本")
                .setMessage("当前版本：$current\n最新版本：$latest\n\n是否前往 GitHub 下载更新？")
                .setPositiveButton("去更新") { _, _ -> openUrl(ctx, RELEASES_URL) }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            LogUtil.error("更新弹窗失败", e)
        }
    }

    private fun fetchLatestTag(): String? {
        val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            conn.requestMethod = "GET"
            val status = conn.responseCode
            val loc = conn.getHeaderField("Location")?.trim()?.trimEnd('/')
            if (status in 300..399 && !loc.isNullOrEmpty()) {

                return loc.substringAfterLast('/').removePrefix("v").trim()
            }

            if (status == 404) return null
            require(status == 200) { "GitHub HTTP $status" }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return Regex("""/wchunlin1006/HGModule/releases/tag/([^"'\s<>?]+)""").find(html)
                ?.groupValues?.get(1)?.removePrefix("v")?.trim()
        } finally {
            conn.disconnect()
        }
    }

    fun compareVersions(a: String, b: String): Int {
        fun parse(s: String): List<Int> = s.trim().removePrefix("v")
            .split('.')
            .map { seg -> seg.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: 0
            val y = pb.getOrNull(i) ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun openUrl(ctx: Context, url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.applicationContext.startActivity(i)
        } catch (e: Exception) {
            LogUtil.error("打开链接失败 $url", e)
        }
    }
}
