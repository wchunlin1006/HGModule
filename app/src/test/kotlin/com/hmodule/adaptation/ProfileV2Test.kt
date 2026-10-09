package com.hmodule.adaptation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileV2Test {
    private fun raw() = RuntimeEnvironment.getApplication().assets.open("adaptation/CN-7.3.9.32.json").bufferedReader().use { it.readText() }
    private fun feature(root: JSONObject, id: String) = root.getJSONArray("features").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }.single { it.getString("id") == id }
    }
    class Player {
        fun setPlaySpeed(speed: Int) {}
        fun setSpeed(clear: Boolean, speed: Float, animate: Boolean) {}
        fun cache(id: String): Float = 1f
        fun getCurrentPlaySpeed(): Int = 100
    }
    open class Parent {
        @JvmField var count: Int = 0
        fun echo(value: Array<String>): Array<String> = value
    }
    class Child : Parent()

    private fun withFakeSpeed(): JSONObject {
        val root = JSONObject(raw())
        val speed = feature(root,"speed")
        val mappings = speed.getJSONObject("mappings")
        val owner = Player::class.java.name
        mappings.put("speedPlayerClass",owner).put("speedControllerClass",owner).put("speedSetMethod","setSpeed").put("speedCacheMethod","cache")
        val type = DexMember.objectType(owner)
        speed.put("delegates",JSONArray().apply {
            for ((key, descriptor) in listOf(
                "setPlaySpeed" to "$type->setPlaySpeed(I)V",
                "speedSetMethod" to "$type->setSpeed(ZFZ)V",
                "speedCacheMethod" to "$type->cache(Ljava/lang/String;)F",
                "getCurrentPlaySpeed" to "$type->getCurrentPlaySpeed()I",
                "autoPlaySpeed" to "Lcom/dragon/read/component/shortvideo/impl/autoplay/o;->setSpeed(F)V",
            )) put(JSONObject().put("key",key).put("kind","method").put("descriptor",descriptor).put("status","SUCCESS"))
        })
        return root
    }

    @Test fun failingQualityDoesNotDisableIndependentSpeedAndResolutionNeverMutatesSavedData() {
        val root = withFakeSpeed()
        val profile = ProfileJson.parse(root.toString())
        val result = FeatureProfiles.resolve(profile,Player::class.java.classLoader) { true }
        assertTrue(result.results.single { it.id == "speed" }.enabled)
        assertFalse(result.results.single { it.id == "quality" }.enabled)
        assertEquals("",result.names.resolutionController)
        assertEquals(Player::class.java.name,result.names.speedPlayerClass)
        assertEquals("setSpeed",result.names.speedSetMethod)
        assertEquals(root.toString(),profile.raw)
        assertTrue(profile.names.resolutionController.isNotBlank())
    }

    @Test fun missingMemberWrongContractAndFalseSuccessAreRejectedPerFeature() {
        for (failure in listOf("missing", "contract", "return", "mapping", "status")) {
            val root=withFakeSpeed(); val speed=feature(root,"speed")
            when(failure) {
                "missing" -> speed.getJSONArray("delegates").remove(0)
                "contract" -> speed.put("contract","speed-v9")
                "return" -> speed.getJSONArray("delegates").getJSONObject(0).put("descriptor",DexMember.objectType(Player::class.java.name)+"->setPlaySpeed(I)I")
                "mapping" -> speed.getJSONObject("mappings").put("speedSetMethod","absent")
                "status" -> speed.getJSONArray("delegates").getJSONObject(0).put("status","FAILURE")
            }
            val result=FeatureProfiles.resolve(ProfileJson.parse(root.toString()),Player::class.java.classLoader) { true }
            assertFalse(failure,result.results.single { it.id=="speed" }.enabled)
            assertEquals(failure,"",result.names.speedPlayerClass)
            assertTrue(failure,"speed" in result.names.disabledFeatures)
        }
    }

    @Test fun allBuiltinSignaturesAgreeWithModuleBindingsAndCurrentContract() {
        for (file in listOf("CN-7.3.2.32.json","CN-7.3.9.32.json")) {
            val profile=ProfileJson.parse(RuntimeEnvironment.getApplication().assets.open("adaptation/$file").bufferedReader().use { it.readText() })
            for (f in profile.features.filter { it.outcome=="PASS" }) {
                val expected=FeatureContracts.expected(f.id,profile.names)
                assertEquals("$file/${f.id}",expected.map { it.key }.toSet(),f.delegates.map { it.key }.toSet())
                for(r in expected) assertTrue("$file/${f.id}/${r.key}",r.matches(f.delegates.single { it.key==r.key }.let { DexMember.parse(it.kind,it.descriptor) }))
            }
        }
        assertTrue(ProfileJson.parse(raw()).features.all { it.outcome=="PASS" })
    }

    @Test fun descriptorsVerifyExactReturnFieldTypeArraysAndInheritedMembersWithoutInitialization() {
        val type=DexMember.objectType(Child::class.java.name)
        val loader=Child::class.java.classLoader
        DexMember.parse("method","$type->echo([Ljava/lang/String;)[Ljava/lang/String;").verify(loader,null)
        DexMember.parse("field","$type->count:I").verify(loader,null)
        assertThrows(IllegalArgumentException::class.java) { DexMember.parse("field","$type->count:J").verify(loader,null) }
        assertThrows(IllegalArgumentException::class.java) { DexMember.parse("method","$type->echo([Ljava/lang/String;)Ljava/lang/String;").verify(loader,null) }
        assertThrows(IllegalArgumentException::class.java) { DexMember.parse("resource","id/missing").verify(loader) { false } }
    }

    @Test fun oldFormatMalformedSignatureDuplicateFeatureAndCrossFeatureMappingCannotBeImported() {
        assertThrows(IllegalArgumentException::class.java) { ProfileJson.parse(JSONObject(raw()).put("schemaVersion",1).toString()) }
        for (descriptor in listOf("Lexample/X;->method(V)V","Lexample/X;->field:V","Lexample/X;->method([V)V","Lexample/X;->method(I)","Lexample.X;")) {
            val root=JSONObject(raw()); feature(root,"speed").getJSONArray("delegates").getJSONObject(0).put("descriptor",descriptor)
            assertThrows(IllegalArgumentException::class.java) { ProfileJson.parse(root.toString()) }
        }
        val duplicate=JSONObject(raw()); duplicate.getJSONArray("features").put(feature(duplicate,"speed"))
        assertThrows(IllegalArgumentException::class.java) { ProfileJson.parse(duplicate.toString()) }
        val cross=JSONObject(raw()); feature(cross,"speed").getJSONObject("mappings").put("shortHolder","x.y")
        assertThrows(IllegalArgumentException::class.java) { ProfileJson.parse(cross.toString()) }
    }

    @Test fun anOldSnapshotAndHostCacheCannotOverrideNewBuiltinEvenWithHigherRevision() {
        val app=RuntimeEnvironment.getApplication()
        val old=JSONObject().put("schemaVersion",1).put("revision",999).toString()
        val prefs=app.getSharedPreferences(AdaptationStore.REMOTE_GROUP,0)
        prefs.edit().clear().putString("old",old).commit()
        AdaptationStore.loadAssets(app)
        val dir=java.io.File(app.cacheDir,"v2-migration").apply { mkdirs() }
        java.io.File(dir,"com.phoenix.read-73932.json").writeText(old)
        val selected=AdaptationStore.select("com.phoenix.read","7.3.9.32",73932,dir)!!
        assertEquals(2,selected.schemaVersion); assertEquals(2,selected.revision)
        assertEquals(2,ProfileJson.parse(java.io.File(dir,"com.phoenix.read-73932.json").readText()).schemaVersion)
    }

    @Test fun missingResourcesDisableControlsButKeepVerifiedSpeedAndWrongMappingTypesAreRejected() {
        val root=withFakeSpeed(); val controls=feature(root,"controls")
        val mapping=FeatureProfiles.emptyMappings()
        val minimal=JSONObject()
        FeatureProfiles.keys.getValue("controls").forEach { minimal.put(it,mapping.get(it)) }
        minimal.put("controlTargets",JSONObject().put("missing_resource","author_declaration"))
        controls.put("mappings",minimal).put("delegates",JSONArray().apply {
            put(JSONObject().put("key","authorDeclaration").put("kind","class").put("descriptor","Lcom/dragon/read/component/shortvideo/impl/infobottom/ShortSeriesInfoBottomView;").put("status","SUCCESS"))
            put(JSONObject().put("key","resource.missing_resource").put("kind","resource").put("descriptor","id/missing_resource").put("status","SUCCESS"))
        })
        val result=FeatureProfiles.resolve(ProfileJson.parse(root.toString()),Player::class.java.classLoader) { false }
        assertFalse(result.results.single { it.id=="controls" }.enabled)
        assertTrue(result.names.controlTargets.isEmpty())
        assertTrue(result.results.single { it.id=="speed" }.enabled)
        minimal.put("structuralFullscreenWatch","true")
        assertThrows(IllegalArgumentException::class.java) { ProfileJson.parse(root.toString()) }
    }
}
