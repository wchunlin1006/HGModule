package com.hmodule.adaptation

import android.content.Context
import android.content.res.Configuration
import android.view.View
import com.hmodule.config.ModuleConfig
import com.hmodule.hooks.TargetNames
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun raw() = app.assets.open("adaptation/CN-7.3.9.32.json").bufferedReader().use { it.readText() }
    private fun revised(revision: Int) = JSONObject(raw()).put("revision", revision).toString()
    private fun mapping(root: JSONObject, key: String): JSONObject = root.getJSONArray("features").let { features ->
        (0 until features.length()).map { features.getJSONObject(it).getJSONObject("mappings") }.single { it.has(key) }
    }
    private fun cache() = File(app.cacheDir, "adaptation-test").also { it.mkdirs(); it.listFiles()?.forEach(File::delete) }

    @Before fun reset() {
        AdaptationStore.publishTo(null)
        app.getSharedPreferences(AdaptationStore.REMOTE_GROUP, 0).edit().clear().commit()
        AdaptationStore.loadAssets(app)
    }

    @Test fun exactIncludesPackageVersionAndBuildCode() {
        val profile = AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!
        assertEquals("t65.v", profile.names.shortHolder)
        assertEquals("action_text", profile.names.drawerTitleResource)
        assertEquals("E", profile.names.adVideoEndShowMethod)
        assertNull(AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73933))
        assertNull(AdaptationStore.exact(TargetNames.OVERSEA_PACKAGE, "7.3.9.32", 73932))
        assertFalse(TargetNames.isSupported(TargetNames.CN_PACKAGE, "7.3.9.32", 73933))
    }

    @Test fun malformedImportKeepsPreviousValidCacheAndUserSettings() {
        val imported = AdaptationStore.importJson(revised(3))
        val user = ModuleConfig.local(app)
        user.edit().putInt("guoplus_control_opacity", 50).commit()
        for (json in listOf(
            JSONObject(raw()).put("schemaVersion", 99),
            JSONObject(raw()).put("hookContract", "future-code"),
            JSONObject(raw()).put("revision", 0),
            JSONObject(raw()).put("versionCode", -1),
        )) assertThrows(IllegalArgumentException::class.java) { AdaptationStore.importJson(json.toString()) }
        for ((key, value) in listOf("packageName" to "other.app", "versionName" to "7.3.9", "shortHolder" to "UNKNOWN")) {
            val json = JSONObject(raw())
            (if (key == "shortHolder") mapping(json, key) else json.getJSONObject("host")).put(key, value)
            assertThrows(IllegalArgumentException::class.java) { AdaptationStore.importJson(json.toString()) }
        }
        val target = JSONObject(raw())
        mapping(target,"controlTargets").getJSONObject("controlTargets").put("i3t", "invented")
        assertThrows(IllegalArgumentException::class.java) { AdaptationStore.importJson(target.toString()) }
        assertThrows(IllegalArgumentException::class.java) { AdaptationStore.importJson(" ".repeat(ProfileJson.MAX_BYTES + 1)) }
        assertEquals(imported.raw, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!.raw)
        assertEquals(50, user.getInt("guoplus_control_opacity", 0))
    }

    @Test fun importSurvivesRestartAndClearOfUserSettings() {
        AdaptationStore.importJson(revised(3))
        AdaptationStore.loadAssets(app)
        ModuleConfig.clear(app, ModuleConfig.local(app))
        assertEquals(3, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!.revision)
        assertThrows(IllegalArgumentException::class.java) { AdaptationStore.importJson(raw()) }
    }

    @Test fun frameworkSnapshotIsIndependentAndDoesNotNeedLauncherToResolve() {
        val framework = app.getSharedPreferences("adaptation-framework-test", 0)
        framework.edit().clear().commit()
        AdaptationStore.publishTo(framework)
        AdaptationStore.importJson(revised(3))
        assertTrue(framework.all.values.any { it is String && JSONObject(it).getInt("revision") == 3 })
        app.getSharedPreferences(AdaptationStore.REMOTE_GROUP, 0).edit().clear().commit()
        AdaptationStore.loadAssets(app)
        assertEquals(2, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!.revision)
        AdaptationStore.loadSnapshot(framework)
        assertEquals(3, AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!.revision)
    }

    @Test fun badHostCacheRecoversAndRevisionRefreshesWithoutCrossVersionReuse() {
        val dir = cache()
        val file = File(dir, "${TargetNames.CN_PACKAGE}-73932.json")
        file.writeText("broken")
        assertEquals(2, AdaptationStore.select(TargetNames.CN_PACKAGE, "7.3.9.32", 73932, dir)!!.revision)
        assertEquals(2, ProfileJson.parse(file.readText()).revision)
        AdaptationStore.importJson(revised(4))
        assertEquals(4, AdaptationStore.select(TargetNames.CN_PACKAGE, "7.3.9.32", 73932, dir)!!.revision)
        assertEquals(4, ProfileJson.parse(file.readText()).revision)
        assertNull(AdaptationStore.select(TargetNames.CN_PACKAGE, "7.3.10.32", 73932, dir))
    }

    @Test fun cachedProfileWorksOfflineAndTakesPriorityOverOlderBuiltin() {
        val dir = cache()
        File(dir, "${TargetNames.CN_PACKAGE}-73932.json").writeText(revised(8))
        assertEquals(8, AdaptationStore.select(TargetNames.CN_PACKAGE, "7.3.9.32", 73932, dir)!!.revision)
        assertNull(AdaptationStore.select(TargetNames.CN_PACKAGE, "7.3.9.32", 73933, dir))
    }

    @Test fun interruptedAtomicWriteRecoversLastValidProfileAndBoundsReads() {
        val dir = cache()
        val file = File(dir, "${TargetNames.CN_PACKAGE}-73932.json")
        File(file.path + ".bak").writeText(revised(7))
        file.writeText("interrupted")
        assertEquals(7, AdaptationStore.select(TargetNames.CN_PACKAGE, "7.3.9.32", 73932, dir)!!.revision)
        assertEquals(7, ProfileJson.parse(file.readText()).revision)
        assertThrows(IllegalArgumentException::class.java) {
            ProfileJson.read(java.io.ByteArrayInputStream(ByteArray(ProfileJson.MAX_BYTES + 1)))
        }
    }

    @Test fun neighbourIsPartialAndNeverCachedAsExact() {
        val neighbour = AdaptationStore.nearest(TargetNames.CN_PACKAGE, "7.3.10.32")!!
        assertEquals("7.3.9.32", neighbour.names.versionName)
        assertNull(AdaptationStore.nearest(TargetNames.CN_PACKAGE, "7.4.0.32"))
        assertNull(AdaptationStore.nearest(TargetNames.CN_PACKAGE, "unknown"))
        val n = TargetNames.namesFor(TargetNames.CN_PACKAGE, "7.3.10.32", null, 731032)
        assertTrue(n.profileId.startsWith("COMPAT-"))
        assertTrue(n.controlTargets.isEmpty())
        assertTrue(n.staticHideIds.isEmpty())
        assertFalse(n.nativeDrawer)
        assertEquals("", n.shortHolder)
        val dir = cache()
        assertNull(AdaptationStore.select(TargetNames.CN_PACKAGE, "7.3.10.32", 731032, dir))
        assertTrue(dir.listFiles()!!.isEmpty())
        assertEquals("UNSUPPORTED", TargetNames.namesFor(TargetNames.CN_PACKAGE, "8.0.0.32").profileId)
    }

    class Holder {
        @JvmField var mask: View? = null
        @JvmField var clear: Boolean = false
        fun mask(visible: Boolean) {}
        fun controls(clear: Boolean, animate: Boolean) {}
        fun configure(config: Configuration) {}
        fun reset() {}
        fun landscape(enabled: Boolean) {}
    }
    @Test fun neighbourRetainsMatchingPlaybackContractButDropsMismatchedMembers() {
        val profile = AdaptationStore.exact(TargetNames.CN_PACKAGE, "7.3.9.32", 73932)!!
        val names = profile.names.copy(shortHolder = Holder::class.java.name, shortMaskField = "mask", shortNativeClearField = "clear",
            shortMaskMethod = "mask", shortControlsMethod = "controls", shortConfigMethod = "configure", shortLayoutResetMethod = "reset", shortLandscapeMethod = "landscape")
        val valid = AdaptationStore.compatible(profile.copy(names = names), Holder::class.java.classLoader)
        assertEquals(Holder::class.java.name, valid.shortHolder)
        assertEquals("configure", valid.shortConfigMethod)
        assertEquals("", valid.shortStateMethod)
        assertTrue(valid.controlTargets.isEmpty())
        val invalid = AdaptationStore.compatible(profile.copy(names = names.copy(shortControlsMethod = "reset")), Holder::class.java.classLoader)
        assertEquals("", invalid.shortHolder)
        assertEquals("", invalid.shortConfigMethod)
    }
}
