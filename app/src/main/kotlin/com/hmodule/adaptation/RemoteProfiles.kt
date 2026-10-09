package com.hmodule.adaptation

import com.hmodule.hooks.TargetNames
import org.json.JSONObject
import java.security.MessageDigest

/** Validates data before the caller commits anything. The manifest never supplies arbitrary URLs. */
object RemoteProfiles {
    const val BASE_URL = "https://raw.githubusercontent.com/wchunlin1006/HGModule/main/adaptation/"
    data class Entry(val file: String, val pkg: String, val version: String, val code: Long,
        val revision: Int, val sha256: String)
    data class Result(val checked: Int, val profiles: List<AdaptationProfile>)

    fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun manifest(raw: String): List<Entry> {
        require(raw.toByteArray(Charsets.UTF_8).size <= ProfileJson.MAX_BYTES) { "适配索引过大" }
        val root = JSONObject(raw)
        require(root.getInt("schemaVersion") == 1 && root.getString("hookContract") == "guoplus-hooks-v1") { "适配索引格式不支持" }
        val array = root.getJSONArray("profiles")
        require(array.length() in 1..64) { "适配索引数量无效" }
        val entries = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Entry(item.getString("file"), item.getString("packageName"), item.getString("versionName"),
                item.getLong("versionCode"), item.getInt("revision"), item.getString("sha256")).also {
                require(it.file.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.json")) && it.file !in setOf("index.json", "manifest.json")) { "适配文件名无效" }
                require(it.pkg in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE)) { "目标包不支持" }
                require(it.version.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) && it.code > 0 && it.revision > 0) { "适配版本无效" }
                require(it.sha256.matches(Regex("[a-f0-9]{64}"))) { "适配文件摘要无效" }
            }
        }
        require(entries.map { Triple(it.pkg, it.version, it.code) }.distinct().size == entries.size &&
            entries.map { it.file }.distinct().size == entries.size) { "重复适配版本或文件" }
        return entries
    }

    fun download(current: List<AdaptationProfile>, packageName: String? = null, fetch: (String) -> String): Result {
        val entries = manifest(fetch("manifest.json")).filter { packageName == null || it.pkg == packageName }
        val downloaded = entries.mapNotNull { entry ->
            val old = current.find { it.names.packageName == entry.pkg && it.names.versionName == entry.version && it.versionCode == entry.code }
            if (old != null && old.revision > entry.revision) return@mapNotNull null
            if (old != null && old.revision == entry.revision && sha256(old.raw) == entry.sha256) return@mapNotNull null
            val raw = fetch(entry.file)
            require(sha256(raw) == entry.sha256) { "适配文件摘要不一致: ${entry.file}" }
            ProfileJson.parse(raw).also {
                require(it.names.packageName == entry.pkg && it.names.versionName == entry.version &&
                    it.versionCode == entry.code && it.revision == entry.revision) { "适配文件与索引不一致: ${entry.file}" }
                require(old == null || old.revision != it.revision || ProfileJson.equivalent(old.raw, it.raw)) { "适配内容已改变，请提高 revision" }
            }
        }
        return Result(entries.size, downloaded)
    }
}
