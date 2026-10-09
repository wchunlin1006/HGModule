package com.hmodule.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process

/** One authoritative store for the module and both supported host applications. */
object ModuleConfig {
    const val AUTHORITY = "com.hmodule.configuration"
    val URI: Uri = Uri.parse("content://$AUTHORITY/config")
    const val THEME = "guoplus_theme"
    const val REMOTE_GROUP = "guoplus_configuration"
    internal const val RESET = "configuration_reset"
    private const val INITIALIZED = "configuration_initialized"
    private var localStore: SharedPreferences? = null
    private var notifier: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var remoteSnapshot: SharedPreferences? = null
    private var publisher: SharedPreferences? = null
    private val hostWriteListeners = linkedSetOf<() -> Unit>()
    fun observeHostWrites(listener: () -> Unit) { synchronized(hostWriteListeners) { hostWriteListeners.add(listener) } }
    fun stopObservingHostWrites(listener: () -> Unit) { synchronized(hostWriteListeners) { hostWriteListeners.remove(listener) } }
    internal fun notifyHostWrite() { synchronized(hostWriteListeners) { hostWriteListeners.toList() }.forEach { it() } }
    fun useRemoteSnapshot(prefs: SharedPreferences) { remoteSnapshot = prefs }
    @Synchronized fun publishTo(prefs: SharedPreferences?) {
        publisher = prefs
        localStore?.let(::publish)
    }
    private fun publish(prefs: SharedPreferences) {
        try {
            publisher?.edit()?.clear()?.also { put(it, bundle(prefs.all)) }?.commit()
        } catch (e: Exception) { com.hmodule.LogUtil.warn("果+框架配置备份失败: ${e.message}") }
    }
    @Synchronized fun local(context: Context): SharedPreferences {
        localStore?.let { return it }
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("guoplus_configuration", 0)
        notifier = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            publish(prefs)
            app.contentResolver.notifyChange(URI, null)
        }
        prefs.registerOnSharedPreferenceChangeListener(notifier!!)
        localStore = prefs
        publish(prefs)
        return prefs
    }
    fun open(context: Context, legacy: SharedPreferences? = null): SharedPreferences {
        if (context.packageName == "com.hmodule") return local(context)
        return openHost(context.applicationContext, legacy, remoteSnapshot) { method, data ->
            context.contentResolver.call(URI, method, null, data)
        }
    }
    internal fun openHost(context: Context, legacy: SharedPreferences?, remote: SharedPreferences? = null,
        call: (String, Bundle?) -> Bundle?): SharedPreferences = HostPreferences(context, legacy, remote, call)
    fun clear(context: Context, prefs: SharedPreferences): Boolean {
        val theme = prefs.getString(THEME, "system")
        val ok = prefs.edit().clear().putBoolean(INITIALIZED, true).putString(THEME, theme)
            .putString(RESET, java.util.UUID.randomUUID().toString()).commit()
        if (ok) context.contentResolver.notifyChange(URI, null)
        return ok
    }
    fun bundle(values: Map<String, *>): Bundle = Bundle().apply {
        values.forEach { (key, value) -> when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
        } }
    }
    fun put(editor: SharedPreferences.Editor, values: Bundle) {
        for (key in values.keySet()) when (val value = values.get(key)) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is ArrayList<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private class HostPreferences(private val context: Context, private val legacy: SharedPreferences?,
        private val remote: SharedPreferences?, private val call: (String, Bundle?) -> Bundle?) : SharedPreferences {
        private val cache = context.getSharedPreferences("guoplus_configuration_cache", 0)
        // Persist changes before exposing them, so a blocked provider or process death cannot lose edits.
        private val pending = context.getSharedPreferences("guoplus_configuration_pending", 0)
        private val removedKey = "__removed"
        private val clearKey = "__clear"
        private val resetKey = "__reset"
        private fun pendingValues() = pending.all.filterKeys { it !in setOf(removedKey, clearKey, resetKey) }
        private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        private val handler = Handler(Looper.getMainLooper())
        private var unavailableLogged = false
        private val remoteChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            handler.removeCallbacks(refresh); handler.post(refresh)
        }
        private val refresh = Runnable {
            reconnect()
        }
        private val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(refresh); handler.post(refresh) }
        }
        init {
            if (cache.all.isEmpty()) {
                cache.edit().also { put(it, bundle(legacy?.all.orEmpty())) }.remove("player_bar").commit()
            }
            try { context.contentResolver.registerContentObserver(URI, false, observer) }
            catch (e: Exception) { com.hmodule.LogUtil.warn("果+配置通知注册失败: ${e.message}") }
            remote?.registerOnSharedPreferenceChangeListener(remoteChanged)
            reconnect()
        }
        @Synchronized private fun reconnect() {
            handler.removeCallbacks(refresh)
            try {
                call("migrate", bundle(legacy?.all.orEmpty())) ?: error("配置服务不可用")
                val data = call("read", null) ?: error("配置读取失败")
                discardObsoleteEdits(data)
                if (pending.all.isNotEmpty()) {
                    val result = call("write", Bundle().apply {
                        putBundle("values", bundle(pendingValues()))
                        putStringArrayList("remove", ArrayList(pending.getStringSet(removedKey, emptySet())!!))
                        putBoolean("clear", pending.getBoolean(clearKey, false))
                    })
                    check(result?.getBoolean("ok") == true) { "配置同步失败" }
                    val updated = call("read", null) ?: error("配置读取失败")
                    check(pending.edit().clear().commit())
                    replace(updated)
                } else replace(data)
                if (unavailableLogged) com.hmodule.LogUtil.info("果+配置服务已恢复，待同步设置已保存")
                unavailableLogged = false
            } catch (e: Exception) {
                // A framework snapshot does not require Android to start the module application.
                val snapshot = try { remote?.all } catch (_: Exception) { null }
                val authoritative = snapshot?.get(INITIALIZED) == true
                replace(bundle(if (authoritative) snapshot!! else cache.all), authoritative)
                if (!unavailableLogged) {
                    com.hmodule.LogUtil.warn("果+配置服务暂不可用，使用已保存配置并保留修改: ${e.message}")
                    unavailableLogged = true
                }
                handler.postDelayed(refresh, 30_000)
            }
        }
        private fun discardObsoleteEdits(data: Bundle) {
            if (pending.all.isNotEmpty() && pending.getString(resetKey, "") != data.getString(RESET, "")) {
                // An explicit module-side clear wins over edits waiting since before that clear.
                check(pending.edit().clear().commit())
            }
        }
        private fun replace(data: Bundle, authoritative: Boolean = true) {
            if (authoritative) discardObsoleteEdits(data)
            val old = cache.all
            val editor = cache.edit().clear()
            if (!pending.getBoolean(clearKey, false)) put(editor, data)
            pending.getStringSet(removedKey, emptySet())!!.forEach(editor::remove)
            put(editor, bundle(pendingValues()))
            check(editor.commit()) { "配置缓存保存失败" }
            val now = cache.all
            val changed = (old.keys + now.keys).filter { old[it] != now[it] }
            val snapshot = synchronized(listeners) { listeners.toList() }
            changed.forEach { key -> snapshot.forEach { it.onSharedPreferenceChanged(this, key) } }
        }
        override fun getAll(): Map<String, *> = cache.all
        override fun getString(k: String?, d: String?): String? = cache.getString(k, d)
        override fun getStringSet(k: String?, d: MutableSet<String>?): MutableSet<String>? = cache.getStringSet(k, d)
        override fun getInt(k: String?, d: Int) = cache.getInt(k, d)
        override fun getLong(k: String?, d: Long) = cache.getLong(k, d)
        override fun getFloat(k: String?, d: Float) = cache.getFloat(k, d)
        override fun getBoolean(k: String?, d: Boolean) = cache.getBoolean(k, d)
        override fun contains(k: String?) = cache.contains(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { synchronized(listeners) { listeners.add(l) } }
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { synchronized(listeners) { listeners.remove(l) } }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val updates = Bundle()
            private val removed = arrayListOf<String>()
            private var clear = false
            private fun value(k: String, v: Any?): SharedPreferences.Editor {
                if (v == null) return remove(k)
                removed.remove(k); updates.putAll(bundle(mapOf(k to v))); return this
            }
            override fun putString(k: String, v: String?) = value(k, v)
            override fun putStringSet(k: String, v: MutableSet<String>?) = value(k, v)
            override fun putInt(k: String, v: Int) = value(k, v)
            override fun putLong(k: String, v: Long) = value(k, v)
            override fun putFloat(k: String, v: Float) = value(k, v)
            override fun putBoolean(k: String, v: Boolean) = value(k, v)
            override fun remove(k: String): SharedPreferences.Editor { updates.remove(k); removed.add(k); return this }
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean {
                synchronized(this@HostPreferences) {
                    val journal = pending.edit()
                    val baseReset = pending.getString(resetKey, cache.getString(RESET, ""))
                    val deleted = if (clear) mutableSetOf() else pending.getStringSet(removedKey, emptySet())!!.toMutableSet()
                    if (clear) journal.clear().putBoolean(clearKey, true)
                    removed.forEach { journal.remove(it); deleted.add(it) }
                    updates.keySet().forEach(deleted::remove)
                    put(journal, updates)
                    journal.putStringSet(removedKey, deleted).putString(resetKey, baseReset)
                    if (!journal.commit()) return false
                    reconnect()
                    return true
                }
            }
            override fun apply() { check(commit()) { "果+配置保存失败" } }
        }
    }
}

class ConfigurationProvider : ContentProvider() {
    private val lock = Any()
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = synchronized(lock) {
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (!(uid == Process.myUid() || ctx.packageManager.getPackagesForUid(uid)?.any {
            it == "com.phoenix.read" || it == "com.phoenix.read.oversea.gp"
        } == true)) throw SecurityException("配置访问未授权")
        val prefs = ModuleConfig.local(ctx)
        when (method) {
            "cache_adaptation" -> {
                val profile = com.hmodule.adaptation.ProfileJson.parse(requireNotNull(extras?.getString("json")))
                if (uid != Process.myUid()) require(ctx.packageManager.getPackagesForUid(uid)?.contains(profile.names.packageName) == true) {
                    "适配目标与调用应用不一致"
                }
                com.hmodule.adaptation.AdaptationStore.importProfiles(listOf(profile))
                ModuleConfig.notifyHostWrite()
                Bundle().apply { putBoolean("ok", true) }
            }
            "read" -> ModuleConfig.bundle(prefs.all)
            "migrate" -> {
                if (!prefs.getBoolean("configuration_initialized", false)) {
                    val editor = prefs.edit()
                    // Explicit settings made in LSPosed take precedence over old host settings.
                    val missing = Bundle(extras ?: Bundle())
                    prefs.all.keys.forEach(missing::remove)
                    ModuleConfig.put(editor, missing)
                    check(editor.remove("player_bar").putBoolean("configuration_initialized", true).commit())
                    ctx.contentResolver.notifyChange(ModuleConfig.URI, null)
                }
                Bundle().apply { putBoolean("ok", true) }
            }
            "write" -> {
                val editor = prefs.edit()
                if (extras?.getBoolean("clear") == true) editor.clear()
                extras?.getStringArrayList("remove")?.forEach(editor::remove)
                ModuleConfig.put(editor, extras?.getBundle("values") ?: Bundle())
                val ok = editor.commit()
                if (ok) {
                    ctx.contentResolver.notifyChange(ModuleConfig.URI, null)
                    ModuleConfig.notifyHostWrite()
                }
                Bundle().apply { putBoolean("ok", ok) }
            }
            else -> throw IllegalArgumentException(method)
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
