package com.hmodule.adaptation

import com.hmodule.hooks.TargetNames

data class MemberRequirement(val key: String, val kind: String, val owner: String, val name: String = "",
    val parameters: List<String>? = emptyList(), val type: String? = null) {
    fun matches(member: DexMember) = kind == member.kind && owner == member.owner && name == member.name &&
        (parameters == null || parameters == member.parameters) && (type == null || type == member.type)
}

/** Versioned module contracts. Unknown callback types are still checked exactly against the host. */
object FeatureContracts {
    fun expected(id: String, n: TargetNames.Names): List<MemberRequirement> {
        val list = mutableListOf<MemberRequirement>()
        fun cls(key: String, owner: String) {
            if (owner.isNotBlank()) list.add(MemberRequirement(key, "class", DexMember.objectType(owner)))
        }
        fun method(key: String, owner: String, name: String, params: List<String>? = emptyList(), result: String? = "V") {
            if (owner.isNotBlank() && name.isNotBlank()) list.add(MemberRequirement(key, "method", DexMember.objectType(owner), name, params, result))
        }
        fun field(key: String, owner: String, name: String, type: String? = null) {
            if (owner.isNotBlank() && name.isNotBlank()) list.add(MemberRequirement(key, "field", DexMember.objectType(owner), name, type = type))
        }
        fun resource(name: String) {
            if (name.isNotBlank()) list.add(MemberRequirement("resource.$name", "resource", "", name, type = ""))
        }
        when (id) {
            "playback" -> {
                require(n.shortHolder.isNotBlank() && n.holderBaseS1.isNotBlank() && n.shortControlsMethod.isNotBlank() && n.shortMaskField.isNotBlank()) { "播放核心映射为空" }
                cls("shortHolder", n.shortHolder); cls("holderBaseS1", n.holderBaseS1)
                method("shortStateMethod", n.holderBaseS1, n.shortStateMethod, null)
                method("shortControlsMethod", n.shortHolder, n.shortControlsMethod, listOf("Z", "Z"))
                method("shortMaskMethod", n.shortHolder, n.shortMaskMethod, listOf("Z"))
                method("shortConfigMethod", n.shortHolder, n.shortConfigMethod, listOf("Landroid/content/res/Configuration;"))
                method("shortLayoutResetMethod", n.shortHolder, n.shortLayoutResetMethod)
                method("shortLandscapeMethod", n.shortHolder, n.shortLandscapeMethod, listOf("Z"))
                field("shortMaskField", n.shortHolder, n.shortMaskField, "Landroid/view/View;")
                field("shortNativeClearField", n.shortHolder, n.shortNativeClearField, "Z")
                field("shortCleanManagerField", n.shortHolder, n.shortCleanManagerField)
                cls("playbackState", n.playbackState)
            }
            "playback_layout" -> {
                val home = "com.dragon.read.component.shortvideo.impl.v2.SeriesBookMallTabFragment"
                val series = "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment"
                method("homeFragmentMaskMethod", home, n.homeFragmentMaskMethod, listOf("Z"))
                field("homeFragmentMaskField", home, n.homeFragmentMaskField, "Landroid/view/View;")
                method("seriesFragmentRefreshMethod", series, n.seriesFragmentRefreshMethod, listOf("Z", "Z"))
                method("seriesPagerGetter", series, n.seriesPagerGetter, result = null)
                method("seriesHolderGetter", n.speedControllerClass, n.seriesHolderGetter, result = null)
                n.seriesLayoutFields.forEach { field("seriesLayout.$it", series, it) }
                method("fixedToolbarShowMethod", "com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed", n.fixedToolbarShowMethod, listOf("Z"))
                method("customizeToolbarShowMethod", "com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", n.customizeToolbarShowMethod, listOf("Z"))
                method("customizeToolbarApplyMethod", "com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", n.customizeToolbarApplyMethod, listOf("Z", "Z", "Z"))
                cls("toolbarBase", n.toolbarBase)
            }
            "controls" -> {
                cls("progressBar", n.progressBar); cls("hideView1", n.hideView1); cls("hideView2", n.hideView2)
                cls("authorDeclaration", "com.dragon.read.component.shortvideo.impl.infobottom.ShortSeriesInfoBottomView")
                (n.controlTargets.keys + n.clearHostNames + n.hideIdNames + n.progressIdNames + n.mainNavNames +
                    n.fullSeriesNames + n.pauseRestoreNames + listOf(n.bottomBackdropName)).toSet().forEach(::resource)
            }
            "quality" -> {
                require(n.resolutionController.isNotBlank() && n.resolutionEngineField.isNotBlank()) { "画质映射为空" }
                cls("resolutionController", n.resolutionController)
                field("resolutionEngineField", n.resolutionController, n.resolutionEngineField, "Lcom/ss/ttvideoengine/TTVideoEngine;")
                method("resolutionApplyMethod", n.resolutionController, n.resolutionApplyMethod, listOf("Lcom/ss/ttvideoengine/Resolution;"))
                n.resolutionModelMethods.forEach { method("resolutionModel.$it", n.resolutionController, it, null) }
            }
            "speed" -> {
                require(n.speedPlayerClass.isNotBlank() && n.speedControllerClass.isNotBlank()) { "倍速映射为空" }
                method("setPlaySpeed", n.speedPlayerClass, "setPlaySpeed", listOf("I"))
                method("speedSetMethod", n.speedControllerClass, n.speedSetMethod, listOf("Z", "F", "Z"))
                method("speedCacheMethod", n.speedControllerClass, n.speedCacheMethod, listOf("Ljava/lang/String;"), "F")
                method("getCurrentPlaySpeed", n.speedControllerClass, "getCurrentPlaySpeed", result = "I")
                method("autoPlaySpeed", "com.dragon.read.component.shortvideo.impl.autoplay.o", "setSpeed", listOf("F"))
            }
            "comments" -> {
                cls("rightViewAgency", n.rightViewAgency)
                field("rightView", n.rightViewAgency, "p", "Lcom/dragon/read/component/shortvideo/impl/rightview/ShortSeriesRightView;")
                method("rightViewAgencyEventMethod", n.rightViewAgency, n.rightViewAgencyEventMethod, listOf("Landroid/os/Bundle;", "Ljava/lang/String;"))
                n.doubleTapHandlers.forEachIndexed { i, owner -> cls("handler.$i", owner) }
                n.doubleTapHandlers.filter { it != n.doubleTapLikeView }.forEachIndexed { i, owner -> method("onDoubleTap.$i", owner, "onDoubleTap", listOf("Landroid/view/MotionEvent;"), "Z") }
                method("doubleTapLikeMethod", n.doubleTapLikeView, n.doubleTapLikeMethod, listOf("Lkotlin/Pair;"))
                method("doubleTapHolderLikeMethod", n.shortHolder, n.doubleTapHolderLikeMethod)
            }
            "settings" -> {
                require(n.settingsItemClass.isNotBlank() && n.settingsClickClass.isNotBlank() && n.settingsListMethods.isNotEmpty()) { "设置入口映射为空" }
                n.settingsListMethods.forEach { method("settingsList.$it", "com.dragon.read.component.biz.impl.mine.settings.SettingsActivity", it, listOf("Lcom/dragon/read/recyler/c;"), "Ljava/util/List;") }
                method("itemConstructor", n.settingsItemClass, "<init>")
                field("itemTitle", n.settingsItemClass, "e", "Ljava/lang/CharSequence;")
                method("itemClick", n.settingsClickClass, "onClick", listOf("Landroid/view/View;"))
            }
            "drawer" -> {
                require(n.nativeDrawer) { "未启用原生抽屉" }
                val holder = "com.dragon.read.component.shortvideo.impl.moredialog.ShortSeriesMorePanelDialogV2\$b"
                field("drawerProviderField", n.drawerActionClass, n.drawerProviderField)
                method("drawerConstructor", n.drawerActionClass, "<init>", null)
                method("drawerTitleMethod", n.drawerActionClass, n.drawerTitleMethod, listOf("Ljava/lang/String;"))
                method("drawerClickMethod", n.drawerActionClass, n.drawerClickMethod, listOf("Landroid/view/View;"))
                n.drawerBindMethods.forEach { method("drawerBind.$it", holder, it, listOf(if (it == "onBind") "Ljava/lang/Object;" else "Lcom/dragon/read/component/shortvideo/impl/moredialog/action/o;", "I")) }
                resource(n.drawerTitleResource)
            }
            "ads" -> {
                method("adVideoEndShowMethod", "com.dragon.read.pages.video.layers.advideoendlayer.AdVideoEndLayer", n.adVideoEndShowMethod)
                method("pauseAdEntryMethod", n.pauseAdEntryClass, n.pauseAdEntryMethod, listOf("Landroid/view/View;", "Z"))
            }
            "brightness" -> {
                cls("oledBrightAction", n.oledBrightAction)
                method("oledBright", n.oledBright, "a", listOf("Ljava/util/List;"))
            }
            "membership" -> {
                cls("kmpVipModel", n.kmpVipModel)
                n.kmpAcctService.forEachIndexed { i, owner -> method("getVipInfo.$i", owner, "getVipInfo", result = DexMember.objectType(n.kmpVipModel)) }
            }
        }
        return list
    }
}
