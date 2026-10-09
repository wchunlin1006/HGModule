package com.hmodule.hooks

object TargetNames {

    const val CN_PACKAGE = "com.phoenix.read"
    const val OVERSEA_PACKAGE = "com.phoenix.read.oversea.gp"
    val SUPPORTED_CN_VERSIONS get() = com.hmodule.adaptation.AdaptationStore.versions(CN_PACKAGE, listOf("7.3.1.32", "7.3.2.32", "7.3.3.18"))
    val SUPPORTED_OVERSEA_VERSIONS get() = com.hmodule.adaptation.AdaptationStore.versions(OVERSEA_PACKAGE, listOf("7.3.1.32"))

    data class Names(
        val profileId: String,
        val packageName: String,
        val versionName: String,

        val useLegacySeedIds: Boolean = false,

        val structuralFullscreenWatch: Boolean = false,


        val shortHolder: String,
        val holderBaseS1: String,
        val shortStateMethod: String,
        val shortMaskMethod: String,
        val shortControlsMethod: String,
        val shortConfigMethod: String,
        val shortLayoutResetMethod: String,
        val shortLandscapeMethod: String,
        val shortMaskField: String,
        val shortNativeClearField: String,
        val shortCleanManagerField: String,

        val homeFragmentMaskMethod: String,
        val homeFragmentMaskField: String,
        val seriesFragmentRefreshMethod: String,
        val seriesPagerGetter: String,
        val seriesHolderGetter: String,
        val seriesLayoutFields: List<String>,
        val fixedToolbarShowMethod: String,
        val customizeToolbarShowMethod: String,
        val customizeToolbarApplyMethod: String,
        val toolbarBase: String,

        val progressBar: String,
        val hideView1: String,
        val hideView2: String,

        val oledBright: String,
        val oledBrightAction: String,

        val playbackState: String,
        val adVideoEndShowMethod: String,

        val pauseAdEntryClass: String,
        val pauseAdEntryMethod: String,

        val resolutionController: String,
        val resolutionModelMethods: List<String>,
        val resolutionEngineField: String,

        val resolutionApplyMethod: String = "",

        val doubleTapHandlers: List<String>,
        val rightViewAgency: String,
        val rightViewAgencyEventMethod: String,

        val kmpAcctService: List<String>,
        val kmpVipModel: String,

        val hideIdNames: List<String>,
        val progressIdNames: List<String>,

        val staticHideIds: List<Int> = emptyList(),
        val staticProgressIds: List<Int> = emptyList(),

        val pauseRestoreIds: List<Int> = emptyList(),

        val doubleTapLikeView: String = "",

        val doubleTapHolderLikeMethod: String = "",

        val controlTargets: Map<String, String> = emptyMap(),
        val clearHostNames: List<String> = emptyList(),
        val bottomBackdropName: String = "",
        val mainNavNames: List<String> = emptyList(),
        val fullSeriesNames: List<String> = emptyList(),
        val pauseRestoreNames: List<String> = emptyList(),
        val settingsListMethods: List<String> = emptyList(),
        val settingsItemClass: String = "",
        val settingsClickClass: String = "",
        val settingsStyle: String = "",
        val nativeDrawer: Boolean = false,
        val drawerActionClass: String = "com.dragon.read.component.shortvideo.impl.moredialog.action.a",
        val drawerTitleMethod: String = "g",
        val drawerProviderField: String = "k",
        val drawerClickMethod: String = "d",
        val drawerBindMethods: List<String> = listOf("onBind", "f2"),
        val drawerTitleResource: String = "action_text",
        val speedControllerClass: String = "",
        val speedSetMethod: String = "",
        val speedCacheMethod: String = "",
        val doubleTapLikeMethod: String = "a",
        val delayedPauseRestore: Boolean = false,
        val speedPlayerClass: String = "",
        val disabledFeatures: Set<String> = emptySet(),

    )

    internal val CN_73132 = Names(
        profileId = "CN-7.3.1.32",
        packageName = CN_PACKAGE,
        versionName = "7.3.1.32",
        useLegacySeedIds = true,
        structuralFullscreenWatch = false,
        shortHolder = "dw4.t",
        holderBaseS1 = "dw4.i0",
        shortStateMethod = "S1",
        shortMaskMethod = "z3",
        shortControlsMethod = "za",
        shortConfigMethod = "j4",
        shortLayoutResetMethod = "l4",
        shortLandscapeMethod = "p4",
        shortMaskField = "c3",
        shortNativeClearField = "w3",
        shortCleanManagerField = "v3",
        homeFragmentMaskMethod = "o0",
        homeFragmentMaskField = "e",
        seriesFragmentRefreshMethod = "za",
        seriesPagerGetter = "Mg",
        seriesHolderGetter = "p2",
        seriesLayoutFields = listOf("A3", "i", "j", "q", "l", "m", "r", "z3"),
        fixedToolbarShowMethod = "T",
        customizeToolbarShowMethod = "Q",
        customizeToolbarApplyMethod = "S",
        toolbarBase = "gt7.c",
        progressBar = "qg4.t0",
        hideView1 = "ry1.e",
        hideView2 = "fw4.e",
        oledBright = "l83.h",
        oledBrightAction = "n83.a",
        playbackState = "ns7.b",
        adVideoEndShowMethod = "I",
        pauseAdEntryClass = "com.dragon.read.component.shortvideo.impl.inject.view.j4",
        pauseAdEntryMethod = "b",
        resolutionController = "ov4.x",
        resolutionModelMethods = listOf("I", "L"),
        resolutionEngineField = "h",
        resolutionApplyMethod = "e0",

        doubleTapHandlers = listOf("bw4.j0", "yg4.e", "com.dragon.read.component.shortvideo.impl.fullscreen.d\$d"),
        doubleTapLikeView = "yg4.e",
        doubleTapHolderLikeMethod = "g4",
        rightViewAgency = "com.dragon.read.component.shortvideo.impl.inject.view.t6",
        rightViewAgencyEventMethod = "r",
        kmpAcctService = listOf("ec3.h"),
        kmpVipModel = "nn5.e",
        hideIdNames = listOf("hh", "book_container", "h8s", "inx", "ac5", "is7"),
        progressIdNames = emptyList(),

    )

    internal val CN_73232 = Names(
        profileId = "CN-7.3.2.32",
        packageName = CN_PACKAGE,
        versionName = "7.3.2.32",

        useLegacySeedIds = false,
        structuralFullscreenWatch = true,
        shortHolder = "dw4.t",
        holderBaseS1 = "dw4.i0",
        shortStateMethod = "S1",

        shortMaskMethod = "y3",
        shortControlsMethod = "Ea",
        shortConfigMethod = "j4",
        shortLayoutResetMethod = "l4",
        shortLandscapeMethod = "p4",
        shortMaskField = "c3",
        shortNativeClearField = "w3",
        shortCleanManagerField = "v3",
        homeFragmentMaskMethod = "o0",
        homeFragmentMaskField = "e",
        seriesFragmentRefreshMethod = "za",
        seriesPagerGetter = "Mg",
        seriesHolderGetter = "p2",
        seriesLayoutFields = listOf("A3", "i", "j", "q", "l", "m", "r", "z3"),
        fixedToolbarShowMethod = "T",
        customizeToolbarShowMethod = "Q",
        customizeToolbarApplyMethod = "S",
        toolbarBase = "gt7.c",
        progressBar = "qg4.t0",
        hideView1 = "ry1.e",
        hideView2 = "fw4.e",
        oledBright = "l83.h",
        oledBrightAction = "n83.a",
        playbackState = "ns7.b",
        adVideoEndShowMethod = "I",
        pauseAdEntryClass = "com.dragon.read.component.shortvideo.impl.inject.view.j4",
        pauseAdEntryMethod = "b",
        resolutionController = "ov4.x",
        resolutionModelMethods = listOf("I", "L"),
        resolutionEngineField = "h",
        resolutionApplyMethod = "e0",
        doubleTapHandlers = listOf("bw4.j0", "yg4.e", "com.dragon.read.component.shortvideo.impl.fullscreen.d\$d"),
        doubleTapLikeView = "yg4.e",
        doubleTapHolderLikeMethod = "g4",

        rightViewAgency = "com.dragon.read.component.shortvideo.impl.inject.view.t6",
        rightViewAgencyEventMethod = "q",
        kmpAcctService = listOf("ec3.h"),
        kmpVipModel = "nn5.e",
        hideIdNames = listOf("hh", "book_container", "h8s", "inx", "ac5", "is7"),
        progressIdNames = emptyList(),
    )

    internal val CN_73318 = Names(
        profileId = "CN-7.3.3.18",
        packageName = CN_PACKAGE,
        versionName = "7.3.3.18",
        useLegacySeedIds = false,
        structuralFullscreenWatch = true,
        shortHolder = "cy4.t",
        holderBaseS1 = "cy4.i0",
        shortStateMethod = "T1",
        shortMaskMethod = "N3",
        shortControlsMethod = "Qa",
        shortConfigMethod = "x4",
        shortLayoutResetMethod = "z4",
        shortLandscapeMethod = "B4",
        shortMaskField = "S2",
        shortNativeClearField = "n3",
        shortCleanManagerField = "m3",
        homeFragmentMaskMethod = "p0",
        homeFragmentMaskField = "e",
        seriesFragmentRefreshMethod = "Qa",

        seriesPagerGetter = "Tg",

        seriesHolderGetter = "t2",
        seriesLayoutFields = listOf("s3", "i", "j", "q", "l", "m", "r", "r3"),
        fixedToolbarShowMethod = "T",
        customizeToolbarShowMethod = "P",
        customizeToolbarApplyMethod = "R",
        toolbarBase = "hw7.c",
        progressBar = "bi4.h1",
        hideView1 = "",
        hideView2 = "fy4.e",
        oledBright = "x83.h",
        oledBrightAction = "z83.a",
        playbackState = "ov7.b",
        adVideoEndShowMethod = "q",
        pauseAdEntryClass = "com.dragon.read.component.shortvideo.impl.inject.view.k4",
        pauseAdEntryMethod = "b",
        resolutionController = "nx4.w",
        resolutionModelMethods = listOf("f", "i"),
        resolutionEngineField = "h",
        resolutionApplyMethod = "q",

        doubleTapHandlers = listOf("ay4.g0", "ji4.e", "com.dragon.read.component.shortvideo.impl.fullscreen.f\$d"),
        doubleTapLikeView = "ji4.e",
        doubleTapHolderLikeMethod = "u4",
        rightViewAgency = "com.dragon.read.component.shortvideo.impl.inject.view.u6",
        rightViewAgencyEventMethod = "q",
        kmpAcctService = listOf(
            "rc3.h",
            "com.dragon.read.kmp.service.w",
            "com.dragon.read.kmp.service.n0",
        ),
        kmpVipModel = "wq5.e",

        hideIdNames = listOf("iu1", "fxu", "hs9"),

        progressIdNames = listOf("hox"),

        staticHideIds = listOf(0x7F1133AE, 0x7F112411, 0x7F112E0D),
        staticProgressIds = listOf(0x7F112D92),
        pauseRestoreIds = listOf(0x7F112411),
    )

    internal val OVERSEA_73132 = Names(
        profileId = "OVERSEA-7.3.1.32",
        packageName = OVERSEA_PACKAGE,
        versionName = "7.3.1.32",
        useLegacySeedIds = false,
        structuralFullscreenWatch = true,
        shortHolder = "nt4.t",
        holderBaseS1 = "nt4.i0",
        shortStateMethod = "d2",
        shortMaskMethod = "B3",
        shortControlsMethod = "Y9",
        shortConfigMethod = "k4",
        shortLayoutResetMethod = "m4",
        shortLandscapeMethod = "p4",
        shortMaskField = "g3",
        shortNativeClearField = "A3",
        shortCleanManagerField = "z3",
        homeFragmentMaskMethod = "",
        homeFragmentMaskField = "",
        seriesFragmentRefreshMethod = "Y9",
        seriesPagerGetter = "Tf",
        seriesHolderGetter = "r2",
        seriesLayoutFields = listOf("E3", "i", "j", "q", "l", "m", "r", "D3"),
        fixedToolbarShowMethod = "P",
        customizeToolbarShowMethod = "L",
        customizeToolbarApplyMethod = "N",
        toolbarBase = "ep7.c",
        progressBar = "ae4.t0",
        hideView1 = "",
        hideView2 = "pt4.e",
        oledBright = "x63.h",
        oledBrightAction = "z63.a",
        playbackState = "lo7.b",
        adVideoEndShowMethod = "F",
        pauseAdEntryClass = "com.dragon.read.component.shortvideo.impl.inject.view.j4",
        pauseAdEntryMethod = "b",
        resolutionController = "ys4.x",
        resolutionModelMethods = listOf("K", "N"),
        resolutionEngineField = "h",

        resolutionApplyMethod = "",

        doubleTapHandlers = listOf("lt4.j0", "ie4.e", "com.dragon.read.component.shortvideo.impl.fullscreen.d\$d"),
        doubleTapLikeView = "ie4.e",
        doubleTapHolderLikeMethod = "h4",
        rightViewAgency = "com.dragon.read.component.shortvideo.impl.inject.view.u6",
        rightViewAgencyEventMethod = "o",
        kmpAcctService = listOf("com.dragon.read.kmp.service.l0", "s93.h"),
        kmpVipModel = "vk5.e",
        hideIdNames = listOf(
            "right_interact_container",
            "ly_tools_bar_icon",
            "series_info_panel_container",
            "top_header_constraint_layout",
        ),
        progressIdNames = listOf("seek_bar_root"),

        staticHideIds = listOf(0x7F0B2615, 0x7F0B1E9C, 0x7F0B28FA, 0x7F0B2F07),
        staticProgressIds = listOf(0x7F0B2877),

        pauseRestoreIds = listOf(0x7F0B2615, 0x7F0B1E9C),
    )

    internal val CN: Names get() = CN_73132
    internal val OVERSEA: Names get() = OVERSEA_73132

    fun namesFor(pkg: String, versionName: String?, classLoader: ClassLoader? = null, versionCode: Long = -1): Names {
        val version = versionName?.trim()?.substringBefore(' ').orEmpty()
        com.hmodule.adaptation.AdaptationStore.exact(pkg, version, versionCode)?.let { return it.names }
        com.hmodule.adaptation.AdaptationStore.nearest(pkg, version)?.let {
            return com.hmodule.adaptation.AdaptationStore.compatible(it, classLoader)
        }
        // Hardcoded profiles remain a recovery path only for their own exact versions.
        if (versionCode > 0 && com.hmodule.adaptation.AdaptationStore.exact(pkg, version) != null)
            return com.hmodule.adaptation.AdaptationStore.compatible(com.hmodule.adaptation.AdaptationStore.exact(pkg, version)!!, classLoader)
        if (pkg == OVERSEA_PACKAGE && version == "7.3.1.32") return OVERSEA_73132

        val normalized = versionName?.trim()?.substringBefore(' ') ?: ""
        val byVersion = if (pkg != CN_PACKAGE) null else when (normalized) {
            "7.3.3.18" -> CN_73318
            "7.3.2.32" -> CN_73232
            "7.3.1.32" -> CN_73132
            else -> null
        }

        if (byVersion != null) return byVersion

        val empty = com.hmodule.adaptation.AdaptationStore.compatible(
            com.hmodule.adaptation.AdaptationProfile("", 1, 1, CN_73318), null)
        return empty.copy(profileId = "UNSUPPORTED", packageName = pkg, versionName = version)
    }

    fun isSupported(pkg: String, versionName: String?, versionCode: Long = -1): Boolean {
        val v = versionName?.trim()?.substringBefore(' ') ?: return false
        if (com.hmodule.adaptation.AdaptationStore.exact(pkg, v) != null)
            return com.hmodule.adaptation.AdaptationStore.exact(pkg, v, versionCode) != null
        return when (pkg) {
            CN_PACKAGE -> v in SUPPORTED_CN_VERSIONS
            OVERSEA_PACKAGE -> v in SUPPORTED_OVERSEA_VERSIONS
            else -> false
        }
    }
}
