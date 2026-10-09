package com.hmodule.adaptation

import com.hmodule.hooks.TargetNames
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteProfilesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun raw() = app.assets.open("adaptation/CN-7.3.9.32.json").bufferedReader().use { it.readText() }
    private fun newer() = JSONObject(raw()).put("revision", 3).toString()
    private fun manifest(raw: String): JSONObject {
        val p = ProfileJson.parse(raw)
        return JSONObject().put("schemaVersion", 1).put("hookContract", "guoplus-hooks-v1").put("profiles", JSONArray().put(
            JSONObject().put("file", "CN-7.3.9.32.json").put("packageName", p.names.packageName)
                .put("versionName", p.names.versionName).put("versionCode", p.versionCode)
                .put("revision", p.revision).put("sha256", RemoteProfiles.sha256(raw))))
    }
    private fun download(json: JSONObject, content: String, current: List<AdaptationProfile> = AdaptationStore.profiles()) =
        RemoteProfiles.download(current) { file -> if (file == "manifest.json") json.toString() else content }

    @Before fun reset() {
        AdaptationStore.publishTo(null)
        app.getSharedPreferences(AdaptationStore.REMOTE_GROUP, 0).edit().clear().commit()
        AdaptationStore.loadAssets(app)
    }

    @Test fun newerRemoteProfilePersistsAndPublishesWithoutChangingUserConfiguration() {
        val mirror = app.getSharedPreferences("online-mirror", 0).apply { edit().clear().commit() }
        val config = app.getSharedPreferences("guoplus_configuration", 0)
        config.edit().putInt("guoplus_control_opacity", 50).commit()
        AdaptationStore.publishTo(mirror)
        val result = download(manifest(newer()), newer())
        assertEquals(1, result.checked); assertEquals(1, result.profiles.size)
        AdaptationStore.importProfiles(result.profiles)
        assertEquals(3, ProfileJson.parse(mirror.all.values.single() as String).revision)
        AdaptationStore.loadAssets(app)
        assertEquals(3, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!.revision)
        assertEquals(50, config.getInt("guoplus_control_opacity", 0))
    }

    @Test fun unchangedOrOlderRemoteDoesNotDownloadOrDowngrade() {
        for (revision in listOf(2, 1)) {
            val content = if (revision == 2) raw() else JSONObject(raw()).put("revision", revision).toString()
            val calls = mutableListOf<String>()
            val result = RemoteProfiles.download(AdaptationStore.profiles()) {
                calls.add(it); assertEquals("manifest.json", it); manifest(content).toString()
            }
            assertTrue(result.profiles.isEmpty()); assertEquals(listOf("manifest.json"), calls)
        }
    }

    @Test fun replacingSameRevisionRequiresRevisionIncrease() {
        val changed = JSONObject(raw()).put("profileId", "CN-edited").toString()
        assertThrows(IllegalArgumentException::class.java) { download(manifest(changed), changed) }
    }

    @Test fun legacyWhitespaceCacheAcceptsVerifiedCanonicalFileWithoutAFalseRevisionConflict() {
        val server = raw()
        val legacy = server.replace("\n", "\r\n")
        val cached = ProfileJson.parse(legacy)
        AdaptationStore.importJson(legacy)
        val result = download(manifest(server), server, listOf(cached))
        assertEquals(1, result.profiles.size)
        AdaptationStore.importProfiles(result.profiles)
        AdaptationStore.loadAssets(app)
        assertEquals(server, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!.raw)
        assertTrue(download(manifest(server), server).profiles.isEmpty())
    }

    @Test fun failedNetworkAndTamperingKeepExistingCache() {
        AdaptationStore.importJson(newer())
        assertThrows(java.io.IOException::class.java) {
            RemoteProfiles.download(AdaptationStore.profiles()) { throw java.io.IOException("offline") }
        }
        val incoming = JSONObject(raw()).put("revision", 4).toString()
        assertThrows(IllegalArgumentException::class.java) { download(manifest(incoming), incoming + " ") }
        assertEquals(3, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!.revision)
    }

    @Test fun identityContractAndMalformedFileAreRejectedAfterHashValidation() {
        val incoming = newer()
        val mismatched = manifest(incoming)
        mismatched.getJSONArray("profiles").getJSONObject(0).put("versionCode", 73933)
        assertThrows(IllegalArgumentException::class.java) { download(mismatched, incoming) }
        val broken = JSONObject(incoming).put("schemaVersion", 1).toString()
        val index = manifest(incoming)
        index.getJSONArray("profiles").getJSONObject(0).put("sha256", RemoteProfiles.sha256(broken))
        assertThrows(IllegalArgumentException::class.java) { download(index, broken) }
        assertEquals(2, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!.revision)
    }

    @Test fun maliciousManifestCannotEscapeRepositoryOrSupplyUnboundedFiles() {
        for (file in listOf("../profile.json", "https://other.example/a.json", "a/b.json", "manifest.json", "index.json")) {
            val index = manifest(newer())
            index.getJSONArray("profiles").getJSONObject(0).put("file", file)
            assertThrows(IllegalArgumentException::class.java) { download(index, newer()) }
        }
        val duplicate = manifest(newer())
        duplicate.getJSONArray("profiles").put(duplicate.getJSONArray("profiles").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { download(duplicate, newer()) }
        assertThrows(IllegalArgumentException::class.java) { RemoteProfiles.manifest(" ".repeat(ProfileJson.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { download(manifest(newer()).put("schemaVersion", 99), newer()) }
    }

    @Test fun aBadSecondFilePreventsAnyProfileFromBeingAccepted() {
        val content = newer()
        val index = manifest(content)
        val second = JSONObject(index.getJSONArray("profiles").getJSONObject(0).toString())
            .put("file", "CN-other.json").put("versionCode", 73933)
        index.getJSONArray("profiles").put(second)
        assertThrows(IllegalArgumentException::class.java) {
            val result = download(index, content)
            AdaptationStore.importProfiles(result.profiles)
        }
        assertEquals(2, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!.revision)
    }

    @Test fun hostDownloadSurvivesColdStartupWithoutOpeningTheModuleAndRemainsANeighbour() {
        val dir = File(app.cacheDir, "online-host-test").apply { deleteRecursively(); mkdirs() }
        val json = JSONObject(newer()).put("profileId", "CN-7.3.10.32").put("versionCode", 731032)
        json.getJSONObject("host").put("versionName", "7.3.10.32")
        val p = ProfileJson.parse(json.toString())
        AdaptationStore.cacheProfile(dir, p)
        AdaptationStore.loadAssets(app); AdaptationStore.loadDirectory(dir)
        assertEquals(3, AdaptationStore.select(TargetNames.CN_PACKAGE, "7.3.10.32", 731032, dir)!!.revision)
        assertEquals("7.3.10.32", AdaptationStore.nearest(TargetNames.CN_PACKAGE, "7.3.11.32")!!.names.versionName)
        assertNull(AdaptationStore.select(TargetNames.CN_PACKAGE, "7.3.11.32", 731132, dir))
        assertFalse(File(dir, "${TargetNames.CN_PACKAGE}-731132.json").exists())
        AdaptationStore.loadAssets(app)
        val file = File(dir, "${TargetNames.CN_PACKAGE}-731032.json")
        File(file.path + ".bak").writeText(p.raw); file.writeText("interrupted")
        AdaptationStore.loadDirectory(dir)
        assertEquals(3, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.10.32", 731032)!!.revision)
    }

    @Test fun hostFetchesOnlyItsOwnPackageAndRepositoryManifestMatchesBundledFiles() {
        val index = app.assets.open("adaptation/manifest.json").bufferedReader().use { it.readText() }
        for (entry in RemoteProfiles.manifest(index)) {
            val bundled = app.assets.open("adaptation/${entry.file}").bufferedReader().use { it.readText() }
            assertEquals(entry.sha256, RemoteProfiles.sha256(bundled))
        }
        val result = RemoteProfiles.download(emptyList(), TargetNames.OVERSEA_PACKAGE) {
            assertEquals("manifest.json", it); index
        }
        assertEquals(0, result.checked); assertTrue(result.profiles.isEmpty())
    }

    @Test fun everyColdStartChecksEvenWhenAnOldIntervalFileExistsAndSyncsToTheModule() {
        val dir = File(app.cacheDir, "cold-online-check").apply { deleteRecursively(); mkdirs() }
        File(dir, "online-check").writeText(Long.MAX_VALUE.toString())
        val provider = org.robolectric.Robolectric.buildContentProvider(com.hmodule.config.ConfigurationProvider::class.java).create().get()
        val user = com.hmodule.config.ModuleConfig.local(app)
        user.edit().putInt("guoplus_control_opacity", 50).commit()
        val incoming = newer()
        val index = manifest(incoming).toString()
        var checks = 0
        repeat(2) {
            RemoteAdaptation.updateHost(dir, TargetNames.CN_PACKAGE, { profile ->
                assertTrue(provider.call("cache_adaptation", null, android.os.Bundle().apply { putString("json", profile.raw) }).getBoolean("ok"))
            }) { name ->
                if (name == "manifest.json") { checks++; index } else incoming
            }
        }
        assertEquals(2, checks)
        assertEquals(3, ProfileJson.parse(File(dir, "${TargetNames.CN_PACKAGE}-73932.json").readText()).revision)
        AdaptationStore.loadAssets(app)
        assertEquals(3, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!.revision)
        assertEquals(50, user.getInt("guoplus_control_opacity", 0))
    }

    @Test fun aBlockedModuleProviderDoesNotPreventHostDownloadOrNextStartup() {
        val dir = File(app.cacheDir, "blocked-online-sync").apply { deleteRecursively(); mkdirs() }
        val incoming = newer()
        val result = RemoteAdaptation.updateHost(dir, TargetNames.CN_PACKAGE, { throw SecurityException("blocked") }) {
            if (it == "manifest.json") manifest(incoming).toString() else incoming
        }
        assertEquals(1, result.profiles.size)
        AdaptationStore.loadAssets(app); AdaptationStore.loadDirectory(dir)
        assertEquals(3, AdaptationStore.select(TargetNames.CN_PACKAGE, "7.3.9.32", 73932, dir)!!.revision)
        val synced = mutableListOf<AdaptationProfile>()
        assertThrows(java.io.IOException::class.java) {
            RemoteAdaptation.updateHost(dir, TargetNames.CN_PACKAGE, { synced.add(it) }) { throw java.io.IOException("offline") }
        }
        assertTrue(synced.any { it.names.versionName == "7.3.9.32" && it.revision == 3 })
    }

    private class Connection(private val status: Int, private val body: ByteArray, private val length: Long = body.size.toLong()) :
        HttpURLConnection(URL("https://raw.githubusercontent.com/test")) {
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream() = ByteArrayInputStream(body)
    }

    @Test fun transportRejectsRedirectsErrorsAndOversizedResponsesAndAlwaysDisconnects() {
        val valid = Connection(200, newer().toByteArray())
        assertEquals(newer(), RemoteAdaptation.readConnection(valid)); assertTrue(valid.disconnected)
        for (connection in listOf(Connection(302, byteArrayOf()), Connection(404, byteArrayOf()),
            Connection(200, byteArrayOf(), ProfileJson.MAX_BYTES + 1L),
            Connection(200, ByteArray(ProfileJson.MAX_BYTES + 1), -1))) {
            assertThrows(IllegalArgumentException::class.java) { RemoteAdaptation.readConnection(connection) }
            assertTrue(connection.disconnected); assertFalse(connection.instanceFollowRedirects)
        }
    }
}
