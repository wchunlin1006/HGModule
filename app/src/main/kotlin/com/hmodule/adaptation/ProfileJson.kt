package com.hmodule.adaptation

import com.hmodule.controls.ControlTarget
import com.hmodule.hooks.TargetNames
import org.json.JSONObject
import org.json.JSONArray

data class AdaptationProfile(val raw: String, val revision: Int, val versionCode: Long, val names: TargetNames.Names,
    val schemaVersion: Int = 2, val features: List<FeatureProfile> = emptyList())

/** Data only. Explicit constructor binding keeps imported mappings stable under R8. */
object ProfileJson {
    const val MAX_BYTES = 256 * 1024
    /** Formatting and key order do not change an existing profile's revision. */
    fun equivalent(left: String, right: String): Boolean {
        fun value(node: Any?): Any? = when (node) {
            is JSONObject -> node.keys().asSequence().associateWith { value(node.get(it)) }
            is JSONArray -> (0 until node.length()).map { value(node.get(it)) }
            JSONObject.NULL -> null
            else -> node
        }
        return value(JSONObject(left)) == value(JSONObject(right))
    }
    fun read(stream: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "适配文件过大" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
    fun parse(raw: String): AdaptationProfile {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "适配文件过大" }
        val envelope = JSONObject(raw)
        val schema = envelope.getInt("schemaVersion")
        require(schema == 2) { "仅支持第二版适配格式，请使用新版 JSON" }
        require(envelope.getString("hookContract") == "guoplus-hooks-v1") { "适配文件需要不同的模块实现" }
        val revision = envelope.getInt("revision")
        val code = envelope.getLong("versionCode")
        require(revision > 0 && code > 0) { "适配版本无效" }
        val features = FeatureProfiles.parse(envelope)
        val json = FeatureProfiles.flatten(envelope, features)
        val names = decode(json)
        require(names.packageName in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE)) { "目标包不支持" }
        require(names.versionName.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))) { "红果版本格式错误" }
        require(names.profileId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "适配标识格式错误" }
        for (key in json.keys()) {
            val v = json.get(key)
            require(v !is String || v.length <= 300 && !v.contains('/') && !v.contains('\n') && v != "UNKNOWN") { "映射内容无效: $key" }
        }
        return AdaptationProfile(raw, revision, code, names, schema, features)
    }
    internal fun decodeMappings(json: JSONObject): TargetNames.Names = decode(json)
    private fun strings(json: JSONObject, key: String): List<String> = json.optJSONArray(key)?.let { values ->
        require(values.length() <= 256) { "映射列表过长" }
        (0 until values.length()).map { values.getString(it).also { value ->
            require(value.length <= 300 && !value.contains('/') && !value.contains('\n') && value != "UNKNOWN") { "映射列表无效" }
        } }
    } ?: emptyList()
    private fun ints(json: JSONObject, key: String): List<Int> = json.optJSONArray(key)?.let { values ->
        require(values.length() <= 256)
        (0 until values.length()).map { values.getInt(it) }
    } ?: emptyList()
    private fun targets(json: JSONObject, key: String): Map<String, String> = json.optJSONObject(key)?.let { values ->
        val allowed = ControlTarget.entries.map { it.key }.toSet()
        values.keys().asSequence().associateWith { name ->
            require(name.matches(Regex("[A-Za-z0-9_]+"))) { "控件资源名称无效" }
            values.getString(name).also { require(it in allowed) { "未知控件类型: $it" } }
        }
    } ?: emptyMap()
    private fun decode(json: JSONObject): TargetNames.Names = TargetNames.Names(
            profileId = json.getString("profileId"),
            packageName = json.getString("packageName"),
            versionName = json.getString("versionName"),
            useLegacySeedIds = json.optBoolean("useLegacySeedIds", false),
            structuralFullscreenWatch = json.optBoolean("structuralFullscreenWatch", false),
            shortHolder = json.getString("shortHolder"),
            holderBaseS1 = json.getString("holderBaseS1"),
            shortStateMethod = json.getString("shortStateMethod"),
            shortMaskMethod = json.getString("shortMaskMethod"),
            shortControlsMethod = json.getString("shortControlsMethod"),
            shortConfigMethod = json.getString("shortConfigMethod"),
            shortLayoutResetMethod = json.getString("shortLayoutResetMethod"),
            shortLandscapeMethod = json.getString("shortLandscapeMethod"),
            shortMaskField = json.getString("shortMaskField"),
            shortNativeClearField = json.getString("shortNativeClearField"),
            shortCleanManagerField = json.getString("shortCleanManagerField"),
            homeFragmentMaskMethod = json.getString("homeFragmentMaskMethod"),
            homeFragmentMaskField = json.getString("homeFragmentMaskField"),
            seriesFragmentRefreshMethod = json.getString("seriesFragmentRefreshMethod"),
            seriesPagerGetter = json.getString("seriesPagerGetter"),
            seriesHolderGetter = json.getString("seriesHolderGetter"),
            seriesLayoutFields = strings(json, "seriesLayoutFields"),
            fixedToolbarShowMethod = json.getString("fixedToolbarShowMethod"),
            customizeToolbarShowMethod = json.getString("customizeToolbarShowMethod"),
            customizeToolbarApplyMethod = json.getString("customizeToolbarApplyMethod"),
            toolbarBase = json.getString("toolbarBase"),
            progressBar = json.getString("progressBar"),
            hideView1 = json.getString("hideView1"),
            hideView2 = json.getString("hideView2"),
            oledBright = json.getString("oledBright"),
            oledBrightAction = json.getString("oledBrightAction"),
            playbackState = json.getString("playbackState"),
            adVideoEndShowMethod = json.getString("adVideoEndShowMethod"),
            pauseAdEntryClass = json.getString("pauseAdEntryClass"),
            pauseAdEntryMethod = json.getString("pauseAdEntryMethod"),
            resolutionController = json.getString("resolutionController"),
            resolutionModelMethods = strings(json, "resolutionModelMethods"),
            resolutionEngineField = json.getString("resolutionEngineField"),
            resolutionApplyMethod = json.optString("resolutionApplyMethod", ""),
            doubleTapHandlers = strings(json, "doubleTapHandlers"),
            rightViewAgency = json.getString("rightViewAgency"),
            rightViewAgencyEventMethod = json.getString("rightViewAgencyEventMethod"),
            kmpAcctService = strings(json, "kmpAcctService"),
            kmpVipModel = json.getString("kmpVipModel"),
            hideIdNames = strings(json, "hideIdNames"),
            progressIdNames = strings(json, "progressIdNames"),
            staticHideIds = ints(json, "staticHideIds"),
            staticProgressIds = ints(json, "staticProgressIds"),
            pauseRestoreIds = ints(json, "pauseRestoreIds"),
            doubleTapLikeView = json.optString("doubleTapLikeView", ""),
            doubleTapHolderLikeMethod = json.optString("doubleTapHolderLikeMethod", ""),
            controlTargets = targets(json, "controlTargets"),
            clearHostNames = strings(json, "clearHostNames"),
            bottomBackdropName = json.optString("bottomBackdropName", ""),
            mainNavNames = strings(json, "mainNavNames"),
            fullSeriesNames = strings(json, "fullSeriesNames"),
            pauseRestoreNames = strings(json, "pauseRestoreNames"),
            settingsListMethods = strings(json, "settingsListMethods"),
            settingsItemClass = json.optString("settingsItemClass", ""),
            settingsClickClass = json.optString("settingsClickClass", ""),
            settingsStyle = json.optString("settingsStyle", ""),
            nativeDrawer = json.optBoolean("nativeDrawer", false),
            drawerActionClass = json.optString("drawerActionClass", "com.dragon.read.component.shortvideo.impl.moredialog.action.a"),
            drawerTitleMethod = json.optString("drawerTitleMethod", "g"),
            drawerProviderField = json.optString("drawerProviderField", "k"),
            drawerClickMethod = json.optString("drawerClickMethod", "d"),
            drawerBindMethods = strings(json, "drawerBindMethods"),
            drawerTitleResource = json.optString("drawerTitleResource", "action_text"),
            speedControllerClass = json.optString("speedControllerClass", ""),
            speedSetMethod = json.optString("speedSetMethod", ""),
            speedCacheMethod = json.optString("speedCacheMethod", ""),
            doubleTapLikeMethod = json.optString("doubleTapLikeMethod", "a"),
            delayedPauseRestore = json.optBoolean("delayedPauseRestore", false),
            speedPlayerClass = json.optString("speedPlayerClass", ""),
    )
}
