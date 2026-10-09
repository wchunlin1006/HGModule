package com.hmodule.adaptation

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.hmodule.LogUtil
import com.hmodule.hooks.TargetNames
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipFile

/** Adaptation data is independent of user settings and available before the module UI starts. */
object AdaptationStore {
    const val REMOTE_GROUP = "guoplus_adaptation"
    private val builtins = linkedMapOf<String, AdaptationProfile>()
    private val imported = linkedMapOf<String, AdaptationProfile>()
    private var publisher: SharedPreferences? = null
    private var local: SharedPreferences? = null
    private fun key(p: AdaptationProfile) = "${p.names.packageName}@${p.names.versionName}@${p.versionCode}"

    @Synchronized fun loadAssets(context: Context) {
        local = context.getSharedPreferences(REMOTE_GROUP, 0)
        loadSnapshot(local!!)
        load { context.assets.open("adaptation/$it").use { stream -> stream.readBytes().toString(Charsets.UTF_8) } }
    }
    @Synchronized fun loadApk(path: String) {
        ZipFile(path).use { zip ->
            load { name -> zip.getInputStream(requireNotNull(zip.getEntry("assets/adaptation/$name")))
                .use { it.readBytes().toString(Charsets.UTF_8) } }
        }
    }
    private fun load(read: (String) -> String) {
        val files = JSONArray(read("index.json"))
        val next = linkedMapOf<String, AdaptationProfile>()
        for (i in 0 until files.length()) {
            val filename = files.getString(i)
            require(filename.matches(Regex("[A-Za-z0-9._-]+\\.json")))
            val profile = ProfileJson.parse(read(filename))
            require(next.put(key(profile), profile) == null) { "重复适配版本" }
        }
        builtins.clear(); builtins.putAll(next)
    }
    @Synchronized fun loadSnapshot(prefs: SharedPreferences) {
        imported.clear()
        for (value in prefs.all.values) if (value is String) try {
            val profile = ProfileJson.parse(value)
            if ((imported[key(profile)]?.revision ?: 0) <= profile.revision) imported[key(profile)] = profile
        } catch (e: Exception) { LogUtil.warn("忽略无效适配缓存: ${e.message}") }
    }
    @Synchronized fun publishTo(prefs: SharedPreferences?) {
        publisher = prefs
        if (prefs != null) {
            val editor = prefs.edit().clear()
            imported.forEach { (key, profile) -> editor.putString(key, profile.raw) }
            check(editor.commit()) { "框架适配镜像保存失败" }
        }
    }
    @Synchronized fun importJson(raw: String): AdaptationProfile {
        val profile = ProfileJson.parse(raw)
        importProfiles(listOf(profile))
        return profile
    }
    @Synchronized fun importProfiles(profiles: List<AdaptationProfile>) {
        if (profiles.isEmpty()) return
        for (profile in profiles) {
            val current = profiles().find { key(it) == key(profile) }
            require(current == null || profile.revision > current.revision ||
                profile.revision == current.revision && ProfileJson.equivalent(profile.raw, current.raw)) { "配置 revision 必须高于已有版本" }
        }
        val store = requireNotNull(local) { "适配存储未初始化" }
        val editor = store.edit()
        profiles.forEach { editor.putString(key(it), it.raw) }
        check(editor.commit()) { "适配缓存保存失败" }
        remember(profiles)
        try { publishTo(publisher) } catch (e: Exception) { LogUtil.warn("适配框架镜像同步失败: ${e.message}") }
    }
    @Synchronized fun remember(profiles: List<AdaptationProfile>) {
        profiles.forEach { p ->
            val old = imported[key(p)]
            if (old == null || old.revision < p.revision || old.revision == p.revision && ProfileJson.equivalent(old.raw, p.raw)) imported[key(p)] = p
        }
    }
    @Synchronized fun profiles(): List<AdaptationProfile> {
        val result = builtins.toMutableMap()
        imported.forEach { (key, p) -> if ((result[key]?.revision ?: 0) <= p.revision) result[key] = p }
        return result.values.toList()
    }
    fun versions(pkg: String, fallback: List<String>) = (profiles().filter { it.names.packageName == pkg }
        .map { it.names.versionName } + fallback).distinct().sortedWith(compareBy { versionNumber(it) })
    fun exact(pkg: String, version: String, code: Long = -1) = profiles().find {
        it.names.packageName == pkg && it.names.versionName == version && (code < 0 || code == it.versionCode)
    }
    /** Repository profiles use their target version and independently incremented h revision. */
    fun fileName(profile: AdaptationProfile): String =
        "gp-${profile.names.versionName}-h${profile.revision.toString().padStart(3, '0')}.json"
    fun nearest(pkg: String, version: String): AdaptationProfile? {
        if (!version.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))) return null
        val prefix = version.split('.').take(2)
        if (prefix.size != 2) return null
        return profiles().filter { it.names.packageName == pkg && it.names.versionName.split('.').take(2) == prefix }
            .minWithOrNull(compareBy<AdaptationProfile> { kotlin.math.abs(versionNumber(it.names.versionName) - versionNumber(version)) }
                .thenBy { versionNumber(it.names.versionName) > versionNumber(version) })
    }
    private fun versionNumber(version: String): Long = version.split('.').fold(0L) { total, part -> total * 1000 + (part.toLongOrNull() ?: 0) }

    fun loadDirectory(cacheDir: File?) {
        val files = cacheDir?.listFiles().orEmpty().map { if (it.name.endsWith(".json.bak")) File(it.path.removeSuffix(".bak")) else it }
            .filter { it.name.endsWith(".json") }.distinctBy { it.path }
        for (file in files) try {
            val profile = AtomicFile(file).openRead().use { ProfileJson.parse(ProfileJson.read(it)) }
            require(file.name == "${profile.names.packageName}-${profile.versionCode}.json") { "缓存目标不一致" }
            remember(listOf(profile))
        } catch (e: Exception) { LogUtil.warn("忽略无效宿主适配缓存: ${e.message}") }
    }

    fun cacheProfile(cacheDir: File, profile: AdaptationProfile) {
        cacheDir.mkdirs()
        val atomic = AtomicFile(File(cacheDir, "${profile.names.packageName}-${profile.versionCode}.json"))
        val output = atomic.startWrite()
        try { output.write(profile.raw.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    /** Only an exact profile can be cached. A neighbour never becomes a false exact match. */
    fun select(pkg: String, version: String, code: Long, cacheDir: File?): AdaptationProfile? {
        val file = cacheDir?.let { File(it, "$pkg-$code.json") }
        val cached = try { file?.takeIf { it.exists() || File(it.path + ".bak").exists() }?.let { target ->
            AtomicFile(target).openRead().use { stream ->
                ProfileJson.parse(ProfileJson.read(stream))
            }
        }
            ?.takeIf { it.names.packageName == pkg && it.names.versionName == version && it.versionCode == code } }
        catch (e: Exception) { LogUtil.warn("适配缓存无效，使用内置配置: ${e.message}"); null }
        val builtin = exact(pkg, version, code)
        val profile = listOfNotNull(builtin, cached).maxByOrNull { it.revision } ?: return null
        if (file != null && (cached == null || cached.raw != profile.raw)) try {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(profile.raw.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
        } catch (e: Exception) { LogUtil.warn("适配缓存写入失败，继续使用内置 JSON: ${e.message}") }
        LogUtil.info("适配 JSON: ${profile.names.profileId} revision=${profile.revision} 来源=${if (cached?.raw == profile.raw) "本地缓存" else if (imported[key(profile)]?.raw == profile.raw) "持久配置" else "内置配置"}")
        return profile
    }

    /** Reject unsafe ids and version-specific layouts, then keep only matching reflection contracts. */
    fun compatible(profile: AdaptationProfile, loader: ClassLoader?): TargetNames.Names {
        var n = profile.names.copy(profileId = "COMPAT-${profile.names.profileId}", useLegacySeedIds = false,
            controlTargets = emptyMap(), clearHostNames = emptyList(), hideIdNames = emptyList(), progressIdNames = emptyList(),
            staticHideIds = emptyList(), staticProgressIds = emptyList(), pauseRestoreIds = emptyList(),
            bottomBackdropName = "", mainNavNames = emptyList(), fullSeriesNames = emptyList(), pauseRestoreNames = emptyList(),
            hideView1 = "", hideView2 = "", settingsListMethods = emptyList(), settingsItemClass = "", settingsClickClass = "",
            nativeDrawer = false, structuralFullscreenWatch = false, seriesLayoutFields = emptyList(),
            doubleTapHolderLikeMethod = "", doubleTapLikeView = "", kmpAcctService = emptyList(), kmpVipModel = "",
            oledBright = "", oledBrightAction = "", homeFragmentMaskMethod = "", homeFragmentMaskField = "",
            seriesFragmentRefreshMethod = "", seriesPagerGetter = "", seriesHolderGetter = "", toolbarBase = "",
            playbackState = "")
        fun method(clazz: String, name: String, vararg params: Class<*>): Boolean = try {
            if (loader == null) false else { Class.forName(clazz, false, loader).getDeclaredMethod(name, *params); true }
        } catch (_: Throwable) { false }
        fun field(clazz: String, name: String, type: Class<*>): Boolean = try {
            loader != null && type.isAssignableFrom(Class.forName(clazz, false, loader).getDeclaredField(name).type)
        } catch (_: Throwable) { false }
        val boolean = Boolean::class.javaPrimitiveType!!
        val mask = field(n.shortHolder, n.shortMaskField, android.view.View::class.java)
        val holderValid = mask && method(n.shortHolder, n.shortMaskMethod, boolean) &&
            method(n.shortHolder, n.shortControlsMethod, boolean, boolean)
        if (!holderValid) n = n.copy(shortHolder = "", holderBaseS1 = "", shortMaskField = "", shortStateMethod = "",
            shortControlsMethod = "", shortCleanManagerField = "", shortConfigMethod = "", shortLayoutResetMethod = "",
            shortLandscapeMethod = "", doubleTapHolderLikeMethod = "")
        if (n.resolutionApplyMethod.isNotBlank() && !method(n.resolutionController, n.resolutionApplyMethod, try { Class.forName("com.ss.ttvideoengine.Resolution", false, loader) } catch (_: Throwable) { Any::class.java }) ||
            !field(n.resolutionController, n.resolutionEngineField, try { Class.forName("com.ss.ttvideoengine.TTVideoEngine", false, loader) } catch (_: Throwable) { Any::class.java }))
            n = n.copy(resolutionController = "", resolutionModelMethods = emptyList(), resolutionEngineField = "")
        n = n.copy(holderBaseS1 = "", shortStateMethod = "", progressBar = "")
        if (!method(n.shortHolder, n.shortConfigMethod, android.content.res.Configuration::class.java)) n = n.copy(shortConfigMethod = "")
        if (!method(n.shortHolder, n.shortLayoutResetMethod)) n = n.copy(shortLayoutResetMethod = "")
        if (!method(n.shortHolder, n.shortLandscapeMethod, boolean)) n = n.copy(shortLandscapeMethod = "")
        if (!field(n.shortHolder, n.shortNativeClearField, boolean)) n = n.copy(shortNativeClearField = "")
        // Clean-manager internals and business models do not have a stable contract across versions.
        n = n.copy(shortCleanManagerField = "",
            doubleTapHandlers = n.doubleTapHandlers.filter { method(it, "onDoubleTap", android.view.MotionEvent::class.java) })
        if (!method(n.rightViewAgency, n.rightViewAgencyEventMethod, android.os.Bundle::class.java, String::class.java))
            n = n.copy(rightViewAgency = "", rightViewAgencyEventMethod = "")
        if (!method("com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed", n.fixedToolbarShowMethod, boolean)) n = n.copy(fixedToolbarShowMethod = "")
        if (!method("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", n.customizeToolbarShowMethod, boolean)) n = n.copy(customizeToolbarShowMethod = "")
        if (!method("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", n.customizeToolbarApplyMethod, boolean, boolean, boolean)) n = n.copy(customizeToolbarApplyMethod = "")
        if (!method(n.pauseAdEntryClass, n.pauseAdEntryMethod, android.view.View::class.java, boolean)) n = n.copy(pauseAdEntryClass = "", pauseAdEntryMethod = "")
        if (!method("com.dragon.read.pages.video.layers.advideoendlayer.AdVideoEndLayer", n.adVideoEndShowMethod)) n = n.copy(adVideoEndShowMethod = "")
        if (!method(n.speedControllerClass, n.speedSetMethod, boolean, Float::class.javaPrimitiveType!!, boolean) ||
            !method(n.speedControllerClass, n.speedCacheMethod, String::class.java) ||
            !method(n.resolutionController, "setPlaySpeed", Int::class.javaPrimitiveType!!))
            n = n.copy(speedControllerClass = "", speedSetMethod = "", speedCacheMethod = "")
        // A model callback with the same name but different parameters must not be hooked.
        n = n.copy(resolutionModelMethods = n.resolutionModelMethods.filter { name -> try {
            loader != null && Class.forName(n.resolutionController, false, loader).declaredMethods.any {
                it.name == name && it.parameterTypes.firstOrNull()?.name == "com.ss.ttvideoengine.model.VideoModel"
            }
        } catch (_: Throwable) { false } })
        LogUtil.info("相邻版本兼容模式: ${profile.names.versionName}，播放映射=${n.shortHolder.ifBlank { "已跳过" }}；未验证资源/业务模型已跳过")
        return n
    }
}
