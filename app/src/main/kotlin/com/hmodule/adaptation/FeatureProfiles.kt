package com.hmodule.adaptation

import com.hmodule.LogUtil
import com.hmodule.hooks.TargetNames
import org.json.JSONArray
import org.json.JSONObject

data class MemberProfile(val key: String, val kind: String, val descriptor: String, val status: String)
data class FeatureProfile(val id: String, val title: String, val contract: String, val outcome: String,
    val mappings: JSONObject, val delegates: List<MemberProfile>)
data class FeatureResult(val id: String, val title: String, val enabled: Boolean, val reason: String)
data class ProfileResolution(val names: TargetNames.Names, val results: List<FeatureResult>)

/** Each mapping belongs to exactly one capability. No executable code is imported. */
object FeatureProfiles {
    val keys = linkedMapOf(
        "playback" to "shortHolder holderBaseS1 shortStateMethod shortMaskMethod shortControlsMethod shortConfigMethod shortLayoutResetMethod shortLandscapeMethod shortMaskField shortNativeClearField shortCleanManagerField playbackState delayedPauseRestore",
        "playback_layout" to "homeFragmentMaskMethod homeFragmentMaskField seriesFragmentRefreshMethod seriesPagerGetter seriesHolderGetter seriesLayoutFields fixedToolbarShowMethod customizeToolbarShowMethod customizeToolbarApplyMethod toolbarBase",
        "controls" to "useLegacySeedIds structuralFullscreenWatch progressBar hideView1 hideView2 hideIdNames progressIdNames staticHideIds staticProgressIds pauseRestoreIds controlTargets clearHostNames bottomBackdropName mainNavNames fullSeriesNames pauseRestoreNames",
        "quality" to "resolutionController resolutionModelMethods resolutionEngineField resolutionApplyMethod",
        "speed" to "speedPlayerClass speedControllerClass speedSetMethod speedCacheMethod",
        "comments" to "doubleTapHandlers rightViewAgency rightViewAgencyEventMethod doubleTapLikeView doubleTapLikeMethod doubleTapHolderLikeMethod",
        "settings" to "settingsListMethods settingsItemClass settingsClickClass settingsStyle",
        "drawer" to "nativeDrawer drawerActionClass drawerTitleMethod drawerProviderField drawerClickMethod drawerBindMethods drawerTitleResource",
        "ads" to "adVideoEndShowMethod pauseAdEntryClass pauseAdEntryMethod",
        "brightness" to "oledBright oledBrightAction",
        "membership" to "kmpAcctService kmpVipModel",
    ).mapValues { it.value.split(' ').toSet() }
    private val arrays = setOf("seriesLayoutFields", "hideIdNames", "progressIdNames", "staticHideIds", "staticProgressIds",
        "pauseRestoreIds", "clearHostNames", "mainNavNames", "fullSeriesNames", "pauseRestoreNames", "resolutionModelMethods",
        "doubleTapHandlers", "settingsListMethods", "drawerBindMethods", "kmpAcctService")
    private val booleans = setOf("useLegacySeedIds", "structuralFullscreenWatch", "delayedPauseRestore", "nativeDrawer")

    fun emptyMappings(): JSONObject = JSONObject().apply {
        keys.values.flatten().forEach { key -> put(key, when (key) {
            in arrays -> JSONArray()
            in booleans -> false
            "controlTargets" -> JSONObject()
            else -> ""
        }) }
    }

    fun parse(root: JSONObject): List<FeatureProfile> {
        require(!root.has("names")) { "第二版使用 features，不接受旧 names" }
        val list = root.getJSONArray("features")
        require(list.length() in 1..keys.size) { "功能分组数量无效" }
        val seen = mutableSetOf<String>()
        return (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            val id = item.getString("id")
            require(id in keys && seen.add(id)) { "未知或重复功能: $id" }
            val mappings = item.getJSONObject("mappings")
            require(mappings.keys().asSequence().all { it in keys.getValue(id) }) { "功能映射越界: $id" }
            mappings.keys().forEach { key ->
                val value = mappings.get(key)
                require(when (key) {
                    in arrays -> value is JSONArray && value.length() <= 256
                    in booleans -> value is Boolean
                    "controlTargets" -> value is JSONObject && value.length() <= 256
                    else -> value is String
                }) { "映射类型无效: $id/$key" }
            }
            val title = item.getString("title").also { require(it.length in 1..80) }
            val contract = item.getString("contract").also { require(it.length <= 80) }
            val outcome = item.getString("outcome").also { require(it in setOf("PASS", "UNSUPPORTED", "UNVERIFIED")) }
            val delegates = item.getJSONArray("delegates")
            require(delegates.length() <= 256)
            val memberKeys = mutableSetOf<String>()
            FeatureProfile(id, title, contract, outcome, mappings, (0 until delegates.length()).map { j ->
                val d = delegates.getJSONObject(j)
                val key = d.getString("key").also { require(it.matches(Regex("[A-Za-z0-9._-]{1,100}")) && memberKeys.add(it)) }
                val kind = d.getString("kind").also { require(it in setOf("class", "method", "field", "resource")) }
                val descriptor = d.getString("descriptor").also { require(it.length in 1..1000) }
                val status = d.getString("status").also { require(it in setOf("SUCCESS", "UNVERIFIED", "FAILURE")) }
                // Parse signatures now, so malformed imports cannot replace a valid cache.
                DexMember.parse(kind, descriptor)
                MemberProfile(key, kind, descriptor, status)
            })
        }
    }

    fun flatten(root: JSONObject, features: List<FeatureProfile>, enabled: Set<String>? = null): JSONObject = emptyMappings().apply {
        val host = root.getJSONObject("host")
        put("packageName", host.getString("packageName")); put("versionName", host.getString("versionName"))
        put("profileId", root.getString("profileId"))
        features.filter { enabled == null || it.id in enabled }.forEach { feature ->
            feature.mappings.keys().forEach { key -> put(key, feature.mappings.get(key)) }
        }
    }

    fun resolve(profile: AdaptationProfile, loader: ClassLoader?, resourceExists: ((String) -> Boolean)?): ProfileResolution {
        val results = keys.keys.map { id ->
            val feature = profile.features.find { it.id == id }
            val error = if (feature == null) "配置未提供" else try {
                require(feature.contract == "$id-v1") { "功能契约不匹配" }
                require(feature.outcome == "PASS") { "配置状态 ${feature.outcome}" }
                require(feature.mappings.keys().asSequence().toSet() == keys.getValue(id)) { "映射项不完整" }
                val expected = FeatureContracts.expected(id, profile.names)
                require(feature.delegates.map { it.key }.toSet() == expected.map { it.key }.toSet()) { "成员清单不完整或包含多余项" }
                for (requirement in expected) {
                    val supplied = feature.delegates.single { it.key == requirement.key }
                    require(supplied.status == "SUCCESS") { "${supplied.key}: ${supplied.status}" }
                    val member = DexMember.parse(supplied.kind, supplied.descriptor)
                    require(requirement.matches(member)) { "${supplied.key}: 签名与映射或模块契约不一致" }
                    if (supplied.key == "shortStateMethod") require(member.parameters.lastOrNull() == "I") { "播放状态回调缺少状态参数" }
                    if (supplied.key.startsWith("resolutionModel.")) require(member.parameters.firstOrNull() == "Lcom/ss/ttvideoengine/model/VideoModel;") { "画质回调缺少 VideoModel 参数" }
                    if (supplied.key == "drawerConstructor") require(member.parameters.size == 1) { "抽屉构造参数不匹配" }
                    member.verify(loader, resourceExists)
                }
                null
            } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            catch (e: LinkageError) { e.javaClass.simpleName }
            FeatureResult(id, feature?.title ?: id, error == null, error.orEmpty())
        }
        val enabled = results.filter { it.enabled }.map { it.id }.toSet()
        val names = ProfileJson.decodeMappings(flatten(JSONObject(profile.raw), profile.features, enabled))
            .copy(disabledFeatures = keys.keys - enabled)
        results.forEach { LogUtil.info("适配功能 ${it.id}: ${if (it.enabled) "PASS" else "DISABLED ${it.reason}"}") }
        return ProfileResolution(names, results)
    }
}
