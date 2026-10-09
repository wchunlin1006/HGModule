package com.hmodule

import android.util.Log
import android.os.Process
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogUtil {

    private const val LOG_TAG = "ZongHe"
    private const val LOG_ROOT = "/storage/emulated/0/Android/media"
    private const val LOG_DIR_NAME = "K_红果logs"
    private const val LOG_RETENTION_MS = 24L * 60L * 60L * 1000L

    private var logFile: File? = null
    private var currentLogDir: String = "$LOG_ROOT/com.hmodule/$LOG_DIR_NAME"
    private var writer: FileWriter? = null
    private var initialized = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private val counters = mutableMapOf<String, Int>()
    private var lastDiagDump = 0L

    @Synchronized
    fun init(processName: String? = null) {
        if (initialized) return
        try {
            val processPackage = processName?.substringBefore(':')?.takeIf { it.isNotBlank() }
            val ownerPackage = when (processPackage) {
                "com.phoenix.read", "com.phoenix.read.oversea.gp", "com.hmodule" -> processPackage
                else -> "com.hmodule"
            }
            currentLogDir = "$LOG_ROOT/$ownerPackage/$LOG_DIR_NAME"
            val dir = File(currentLogDir)
            if (!dir.exists()) dir.mkdirs()
            val deletedOldLogs = cleanupExpiredLogs(dir)
            val safeProcess = (processName ?: "unknown")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeLast(56)
            logFile = File(dir, "${fileFormat.format(Date())}_${safeProcess}_${Process.myPid()}.log")
            writer = FileWriter(logFile, true)
            initialized = true
            info("═══════════════════════════════════")
            info("日志系统初始化 | 文件=${logFile?.absolutePath}")
            info("日志保留=最近24小时 | 已清理过期日志=$deletedOldLogs")
            info("═══════════════════════════════════")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "日志文件初始化失败", e)
        }
    }

    @Synchronized
    fun info(msg: String) {
        write("INFO", msg)
        Log.i(LOG_TAG, msg)
    }

    @Synchronized
    fun warn(msg: String) {
        write("WARN", msg)
        Log.w(LOG_TAG, msg)
    }

    @Synchronized
    fun error(msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg
        write("ERROR", full)
        Log.e(LOG_TAG, full, t)
        if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            writeRaw(sw.toString())
        }
    }

    @Synchronized
    fun debug(msg: String) {
        write("DEBUG", msg)
        Log.d(LOG_TAG, msg)
    }

    @Synchronized
    fun incr(key: String) {
        counters[key] = (counters[key] ?: 0) + 1
    }

    @Synchronized
    fun diagDump(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastDiagDump) < 10000) return
        lastDiagDump = now
        val sb = StringBuilder()
        sb.appendLine("DIAG ──────────────────────────────")
        sb.appendLine("DIAG " + counters.entries.joinToString(" | ") { "${it.key}=${it.value}" })
        sb.appendLine("DIAG ──────────────────────────────")
        writeRaw(sb.toString())
    }

    @Synchronized
    fun flush() {
        try { writer?.flush() } catch (_: Exception) {}
    }

    @Synchronized
    fun getFilePath(): String = logFile?.absolutePath ?: "(未初始化)"

    private fun cleanupExpiredLogs(dir: File): Int {
        val now = System.currentTimeMillis()
        var deleted = 0
        try {
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".log", ignoreCase = true)) return@forEach
                val modified = file.lastModified()
                if (modified > 0L && now - modified > LOG_RETENTION_MS) {
                    if (file.delete()) deleted++
                }
            }
        } catch (_: Throwable) {}
        return deleted
    }

    private fun write(level: String, msg: String) {
        if (!initialized) {

            Log.i(LOG_TAG, "[文件日志未就绪] $level: $msg")
            return
        }
        val ts = timeFormat.format(Date())
        writeRaw("[$ts] [$level] $msg")
    }

    private fun writeRaw(text: String) {
        try {
            writer?.apply {
                write(text)
                write("\n")
                flush()
            }
        } catch (_: Exception) {}
    }
}
