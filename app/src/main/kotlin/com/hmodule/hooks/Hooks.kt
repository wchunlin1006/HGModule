package com.hmodule.hooks

import android.app.Activity
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.graphics.Color
import android.view.WindowManager
import android.view.WindowInsetsController
import android.widget.*
import com.hmodule.LogUtil
import com.hmodule.MainHook
import com.hmodule.BuildConfig
import com.hmodule.UpdateChecker
import com.hmodule.controls.DefaultQuality
import com.hmodule.controls.ControlPolicy
import com.hmodule.controls.ControlTarget
import com.hmodule.controls.ControlSettings
import com.hmodule.controls.ControlVisibilityManager
import com.hmodule.controls.PlaybackDrawer
import com.hmodule.controls.NativePlaybackDrawer
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker

object Hooks {
    private fun featureEnabled(id: String) = id !in gNames.disabledFeatures
    private var validatedMembers: List<com.hmodule.adaptation.DexMember> = emptyList()

    private var gPrefs: SharedPreferences? = null

    private var gMasterOn = false
    private var gStatusOn = false
    private var gControlOn = false
    private var gCleanScreenOn = false
    private var controlSettings: ControlSettings? = null
    private val controlVisibility = ControlVisibilityManager { block -> internalViewMutation { block() } }
    private val playbackDrawer = PlaybackDrawer(::controlPolicy, ::toggleCleanScreen) { showPanel(null) }
    private val nativePlaybackDrawer = NativePlaybackDrawer(::controlPolicy) { showPanel(null) }

    private fun controlPolicy(): ControlPolicy = ControlPolicy(
        gMasterOn, gControlOn && featureEnabled("controls"), controlSettings?.selected() ?: emptySet(),
        gRestoreControlsOnPause && featureEnabled("playback"), gVideoPaused,
        if (featureEnabled("controls")) controlSettings?.opacityPercent() ?: 100 else 100,
        gCleanScreenOn && featureEnabled("playback") && featureEnabled("controls"),
    )

    private fun pauseRestoresControls(): Boolean =
        gMasterOn && gCleanScreenOn && gRestoreControlsOnPause && gVideoPaused

    private fun toggleCleanScreen() {
        if (!featureEnabled("playback") || !featureEnabled("controls")) {
            gCurrentActivity?.let { Toast.makeText(it, "当前版本的清屏适配未通过校验", Toast.LENGTH_SHORT).show() }
            return
        }
        val enabled = !(gMasterOn && gCleanScreenOn)
        if (enabled) { gMasterOn = true; savePref("master_on", true) }
        gCleanScreenOn = enabled
        savePref("guoplus_clean_screen", enabled)
        applyToCurrent()
        appCtx?.let(::createNotification)
    }

    private fun syncCustomControls(root: View?) {
        val decor = gCurrentActivity?.window?.decorView ?: return
        if (root == null || root !== decor && !isInsideCurrentActivityDecor(root)) return
        controlVisibility.policy = controlPolicy()
        controlVisibility.profileId = gNames.profileId
        controlVisibility.resourceOverrides = gNames.controlTargets
        controlVisibility.clearHostNames = gNames.clearHostNames.toSet()
        controlVisibility.bottomBackdropName = gNames.bottomBackdropName
        if (!gMasterOn || !gControlOn && !gCleanScreenOn && controlVisibility.policy.opacityPercent == 100) {
            restorePauseForcedViews()
            controlVisibility.restoreAll()
            return
        }
        val activityName = gCurrentActivity?.javaClass?.name.orEmpty()
        if (activityName !in setOf(
                "com.dragon.read.pages.main.MainFragmentActivity",
                "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity",
                "com.dragon.read.component.shortvideo.impl.seriesdetail.ShortSeriesDetailActivity",
                "com.dragon.read.component.shortvideo.impl.albumdetail.VideoAlbumDetailActivity",
            )) { controlVisibility.policy = controlPolicy().copy(masterEnabled = false); restorePauseForcedViews(); controlVisibility.restoreAll(); return }
        val hasPlayer = shortVideoHolderSnapshot().any { isShortVideoHolderVisible(it.first) } ||
            activityName != "com.dragon.read.pages.main.MainFragmentActivity" && videoToolbarLayerSnapshot().isNotEmpty()
        controlVisibility.policy = controlPolicy().forPage(hasPlayer)
        if (!controlVisibility.policy.temporarilyRestored) restorePauseForcedViews()
        fun bindProgress(view: View) {
            if (view.tag == ControlVisibilityManager.MODULE_UI_TAG) return
            if (isProgressBar(view)) controlVisibility.bind(view, ControlTarget.PROGRESS)
            if (view is ViewGroup) for (i in 0 until view.childCount) bindProgress(view.getChildAt(i))
        }
        bindProgress(root)
        controlVisibility.sync(root)
        gCurrentActivity?.window?.let(controlVisibility::syncWindow)
    }

    private val topSwipe = com.hmodule.controls.TopSwipeInterceptor()
    private var gTopZoneOn = false
    private var gAdOn = false
    private var gRefreshOff = false
    private var gNavBarOff = false
    private var gRestoreControlsOnPause = false
    private var gVipOn = false
    private var gVipIconOn = false
    private var gDefaultQualityOn = false

    private var gDefaultSpeedOn = false
    private var gDefaultSpeed = 1.0f
    private const val DEFAULT_SPEED_PREF = "default_speed"
    private const val DEFAULT_SPEED_VALUE_PREF = "default_speed_value"
    private val DEFAULT_SPEED_OPTIONS = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
    private var gDoubleTapCommentOn = false

    private var gOledProtectOn = false

    private var gNotificationMenuOn = false

    private var gDownloadLimitUnlimitOn = false
    private const val DOWNLOAD_LIMIT_PREF = "download_limit_unlimit"
    private const val DOWNLOAD_LIMIT_EPISODE_PREF = "download_limit_one_day_episode"
    private const val DOWNLOAD_LIMIT_SERIES_PREF = "download_limit_one_day_series"
    private const val DOWNLOAD_LIMIT_TOTAL_PREF = "download_limit_total_series"
    private const val DOWNLOAD_LIMIT_AB_KEY = "video_download_limit_expansion_v711"
    private const val DOWNLOAD_LIMIT_DEFAULT_VALUE = 99999
    private var gDownloadOneDayEpisode = DOWNLOAD_LIMIT_DEFAULT_VALUE
    private var gDownloadOneDaySeries = DOWNLOAD_LIMIT_DEFAULT_VALUE
    private var gDownloadTotalSeries = DOWNLOAD_LIMIT_DEFAULT_VALUE
    @Volatile private var gVideoPaused = false

    private val gEngineResolutions = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, List<Any>>()
    )

    private val gControllerResolutions = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, List<Any>>()
    )

    private val gKnownPercentSpeedPlayers = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, Boolean>()
    )
    private val gKnownFloatSpeedPlayers = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, Boolean>()
    )

    private val gRightViewAgencies = mutableListOf<java.lang.ref.WeakReference<Any>>()
    @Volatile private var gLastDoubleTapCommentAt = 0L

    @Volatile private var gSuppressDoubleTapLikeUntil = 0L

    private val gForcedShortMaskVisibility = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Int>()
    )

    private data class SavedViewState(var visibility: Int, val alpha: Float)
    private val gSavedViewStates = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, SavedViewState>()
    )

    private data class PauseForcedState(
        val visibility: Int,
        val alpha: Float,
        val translationX: Float,
        val translationY: Float,
    )
    private val gPauseForcedStates = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, PauseForcedState>()
    )

    private val gCleanMaskViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )
    private val gInternalViewMutation = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private val gInsideFeedBottomMarginWrite = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private val gSavedTopMargins = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Int>()
    )

    private data class SavedBottomLayoutState(
        val width: Int,
        val height: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val marginLeft: Int?,
        val marginTop: Int?,
        val marginRight: Int?,
        val marginBottom: Int?,
        val bottomToTop: Int?,
        val bottomToBottom: Int?,
        val goneBottomMargin: Int?,
    )
    private val gSavedBottomLayoutStates = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, SavedBottomLayoutState>()
    )

    private val gKnownRefreshAccessoryViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gKnownMainBottomNavViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gKnownFullSeriesEntryViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gKnownBottomBackdropViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gSavedNativeNavBarColors = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Activity, Int>()
    )

    private val gFeedViewportNativeBottomMargins = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Int>()
    )
    private val gKnownFeedViewportViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gVideoToolbarLayers = mutableListOf<java.lang.ref.WeakReference<Any>>()
    private val gToolbarBaseLayers = mutableListOf<java.lang.ref.WeakReference<Any>>()
    private data class ShortVideoHolderState(
        val ref: java.lang.ref.WeakReference<Any>,
        var playbackState: Int,
        var updatedAt: Long,
    )
    private val gShortVideoHolders = mutableListOf<ShortVideoHolderState>()
    private val gShortSeriesFragments =
        mutableListOf<java.lang.ref.WeakReference<Any>>()
    private val gCnHomeFragments =
        mutableListOf<java.lang.ref.WeakReference<Any>>()
    private var gLastVideoStateAt = 0L
    private var gLastVideoStateReason = "init"
    private var gLastWindowedMode: Boolean? = null

    private var gReceiverRegistered = false
    private var gCurrentActivity: Activity? = null

    private val gTargetIdSet = mutableSetOf<Int>()
    private val gProgressIdSet = mutableSetOf<Int>()
    private val gPauseRestoreIdSet = mutableSetOf<Int>()
    private val hideClasses: List<String> get() = listOf(gNames.hideView1, gNames.hideView2).filter { it.isNotBlank() }
    @Volatile private var gNames: TargetNames.Names = TargetNames.CN
    @Volatile private var gPkg = TargetNames.CN_PACKAGE
    @Volatile private var gTargetVersionName = "未知"
    @Volatile private var gTargetVersionCode = -1L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var gIsNight = false
    private var gSettingsActivity: Activity? = null
    private val gGuoPlusBtnTag = "GUOPLUS_BTN_TAG"
    private val gGuoPlusSettingsWrapperTag = "GUOPLUS_SETTINGS_WRAPPER_TAG"


    private val seedIds = intArrayOf(
        0x7F0B03E3, 0x7F0B071E, 0x7F0B0412,
        0x7F0B0BCB, 0x7F0A00D2, 0x7F0B0C6A,
    )

    private fun initPrefs(ctx: Context) {
        if (gPrefs == null) {
            gPrefs = com.hmodule.config.ModuleConfig.open(ctx)
            com.hmodule.ui.MiuixUi.preferences = gPrefs
            controlSettings = ControlSettings(gPrefs!!)
            gPrefs!!.edit().remove("player_bar").apply()

            reloadPreferences()
            gPrefs!!.registerOnSharedPreferenceChangeListener(configChanged)
        }
    }
    private val changedConfigKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val configChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) changedConfigKeys.add(key)
        mainHandler.removeCallbacks(refreshConfiguration)
        mainHandler.post(refreshConfiguration)
    }
    private val refreshConfiguration = Runnable {
        val changed = synchronized(changedConfigKeys) { changedConfigKeys.toSet().also { changedConfigKeys.clear() } }
        val wasMaster = gMasterOn
        reloadPreferences()
        if (wasMaster && !gMasterOn) {
            restoreAllSavedViews()
            restoreShortVideoNativeControls()
            restoreNativeBottomWindowColor(gCurrentActivity)
            setVideoToolbarsVisible(true)
        }
        if (!gRefreshOff || !gMasterOn) restoreRefreshAccessories()
        gCurrentActivity?.let {
            if (gMasterOn && gStatusOn) applyCleanTop(it) else showStatusBar(it)
            if (gMasterOn && gNavBarOff) applyNavBar(it) else showNavBar(it)
        }
        createNotification(appCtx ?: gCurrentActivity ?: return@Runnable)
        if (gDefaultQualityOn && changed.any { it in setOf("master_on", "max_quality", DefaultQuality.VALUE_KEY) })
            applyDefaultQualityToKnownPlayers()
        if (gDefaultSpeedOn && changed.any { it in setOf("master_on", DEFAULT_SPEED_PREF, DEFAULT_SPEED_VALUE_PREF) })
            scheduleDefaultSpeedApply("configuration")
        applyToCurrent()
    }
    private fun reloadPreferences() {
        gMasterOn = gPrefs!!.getBoolean("master_on", false)
        gStatusOn = gPrefs!!.getBoolean("status_bar", false)
        gControlOn = gPrefs!!.getBoolean("control_hide", false) && featureEnabled("controls")
        gCleanScreenOn = gPrefs!!.getBoolean("guoplus_clean_screen", false) && featureEnabled("playback") && featureEnabled("controls")
        gTopZoneOn = gPrefs!!.getBoolean("top_zone", false)
        gAdOn = gPrefs!!.getBoolean("ad_block", false)
        gRefreshOff = gPrefs!!.getBoolean("pull_refresh", false)
        gNavBarOff = gPrefs!!.getBoolean("nav_bar_off", false)
        gRestoreControlsOnPause = gPrefs!!.getBoolean("restore_controls_pause", false) && featureEnabled("playback")
        gVipOn = gPrefs!!.getBoolean("vip_unlock", false)
        gVipIconOn = gPrefs!!.getBoolean("vip_icon", false)
        gDefaultQualityOn = gPrefs!!.getBoolean("max_quality", false) && featureEnabled("quality")
        gDefaultSpeedOn = gPrefs!!.getBoolean(DEFAULT_SPEED_PREF, false) && featureEnabled("speed")
        gDefaultSpeed = normalizeDefaultSpeed(gPrefs!!.getFloat(DEFAULT_SPEED_VALUE_PREF, 1.0f))
        gDoubleTapCommentOn = gPrefs!!.getBoolean("double_tap_comment", false) && featureEnabled("comments")
        gOledProtectOn = gPrefs!!.getBoolean("oled_protect", false) && featureEnabled("brightness")
        gNotificationMenuOn = gPrefs!!.getBoolean("notification_menu", false)
        gDownloadLimitUnlimitOn = gPrefs!!.getBoolean(DOWNLOAD_LIMIT_PREF, false)
        gDownloadOneDayEpisode = gPrefs!!.getInt(DOWNLOAD_LIMIT_EPISODE_PREF, DOWNLOAD_LIMIT_DEFAULT_VALUE)
        gDownloadOneDaySeries = gPrefs!!.getInt(DOWNLOAD_LIMIT_SERIES_PREF, DOWNLOAD_LIMIT_DEFAULT_VALUE)
        gDownloadTotalSeries = gPrefs!!.getInt(DOWNLOAD_LIMIT_TOTAL_PREF, DOWNLOAD_LIMIT_DEFAULT_VALUE)
    }

    private fun savePref(key: String, value: Boolean) {
        controlVisibility.policy = controlPolicy()
        val editor = gPrefs?.edit()?.putBoolean(key, value) ?: return

        if (key == DOWNLOAD_LIMIT_PREF || key == "master_on") editor.commit()
        else editor.apply()
    }

    private fun normalizeDefaultSpeed(value: Float): Float {
        if (!value.isFinite()) return 1.0f
        return DEFAULT_SPEED_OPTIONS.minByOrNull { kotlin.math.abs(it - value) } ?: 1.0f
    }

    private fun defaultSpeedPercent(): Int = (normalizeDefaultSpeed(gDefaultSpeed) * 100f + 0.5f).toInt()

    private fun formatSpeed(value: Float): String {
        val v = normalizeDefaultSpeed(value)
        return if (kotlin.math.abs(v - v.toInt().toFloat()) < 0.001f) "${v.toInt()}.0x"
        else ("%.2fx".format(java.util.Locale.US, v)).replace("0x", "x")
    }

    private fun defaultSpeedSummary(): String = "当前 ${formatSpeed(gDefaultSpeed)}"

    private fun saveDefaultSpeedValue(value: Float) {
        gDefaultSpeed = normalizeDefaultSpeed(value)
        gPrefs?.edit()?.putFloat(DEFAULT_SPEED_VALUE_PREF, gDefaultSpeed)?.apply()
    }

    private fun defaultSpeedEnabledNow(): Boolean = gMasterOn && gDefaultSpeedOn

    private fun weakPlayerSnapshot(map: MutableMap<Any, Boolean>): List<Any> {
        return try { synchronized(map) { map.keys.toList() } } catch (_: Throwable) { emptyList() }
    }

    private fun applySpeedToPercentPlayer(player: Any, reason: String): Boolean {
        if (!defaultSpeedEnabledNow()) return false
        return try {
            val method = player.javaClass.getMethod("setPlaySpeed", Integer.TYPE)
            method.invoke(player, defaultSpeedPercent())
            LogUtil.incr("defaultSpeedImmediatePercent")
            true
        } catch (_: Throwable) {
            try {
                val method = player.javaClass.getDeclaredMethod("setPlaySpeed", Integer.TYPE).apply { isAccessible = true }
                method.invoke(player, defaultSpeedPercent())
                LogUtil.incr("defaultSpeedImmediatePercent")
                true
            } catch (e: Throwable) {
                LogUtil.debug("默认倍速 percent 应用失败[$reason]: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    private fun applySpeedToFloatPlayer(player: Any, reason: String): Boolean {
        if (!defaultSpeedEnabledNow()) return false
        return try {
            val method = player.javaClass.getMethod("setSpeed", java.lang.Float.TYPE)
            method.invoke(player, gDefaultSpeed)
            LogUtil.incr("defaultSpeedImmediateFloat")
            true
        } catch (_: Throwable) {
            try {
                val method = player.javaClass.getDeclaredMethod("setSpeed", java.lang.Float.TYPE).apply { isAccessible = true }
                method.invoke(player, gDefaultSpeed)
                LogUtil.incr("defaultSpeedImmediateFloat")
                true
            } catch (e: Throwable) {
                LogUtil.debug("默认倍速 float 应用失败[$reason]: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    private fun applyDefaultSpeedNow(reason: String) {
        if (!defaultSpeedEnabledNow()) return
        val run = Runnable {
            var applied = 0
            weakPlayerSnapshot(gKnownPercentSpeedPlayers).forEach { if (applySpeedToPercentPlayer(it, reason)) applied++ }
            weakPlayerSnapshot(gKnownFloatSpeedPlayers).forEach { if (applySpeedToFloatPlayer(it, reason)) applied++ }
            LogUtil.info("默认倍速立即应用 | speed=${formatSpeed(gDefaultSpeed)} | players=$applied | reason=$reason")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    private fun scheduleDefaultSpeedApply(reason: String) {
        if (!defaultSpeedEnabledNow()) return
        mainHandler.post { applyDefaultSpeedNow("$reason/0") }
        mainHandler.postDelayed({ applyDefaultSpeedNow("$reason/120") }, 120L)
        mainHandler.postDelayed({ applyDefaultSpeedNow("$reason/420") }, 420L)
    }

    private fun saveDownloadLimitValues(episode: Int, series: Int, total: Int) {
        gDownloadOneDayEpisode = episode
        gDownloadOneDaySeries = series
        gDownloadTotalSeries = total
        gPrefs?.edit()
            ?.putInt(DOWNLOAD_LIMIT_EPISODE_PREF, episode)
            ?.putInt(DOWNLOAD_LIMIT_SERIES_PREF, series)
            ?.putInt(DOWNLOAD_LIMIT_TOTAL_PREF, total)
            ?.commit()
    }

    private fun downloadUnlimitEnabledNow(): Boolean {
        val master = gPrefs?.getBoolean("master_on", false) ?: false
        val feature = gPrefs?.getBoolean(DOWNLOAD_LIMIT_PREF, false) ?: false
        return master && feature
    }

    private fun currentDownloadLimitValues(): Triple<Int, Int, Int> {
        fun clean(v: Int): Int = v.coerceIn(1, 999_999_999)
        val episode = clean(gPrefs?.getInt(DOWNLOAD_LIMIT_EPISODE_PREF, gDownloadOneDayEpisode) ?: gDownloadOneDayEpisode)
        val series = clean(gPrefs?.getInt(DOWNLOAD_LIMIT_SERIES_PREF, gDownloadOneDaySeries) ?: gDownloadOneDaySeries)
        val total = clean(gPrefs?.getInt(DOWNLOAD_LIMIT_TOTAL_PREF, gDownloadTotalSeries) ?: gDownloadTotalSeries)
        return Triple(episode, series, total)
    }

    private fun customDownloadLimitJson(): String {
        val (episode, series, total) = currentDownloadLimitValues()
        return "{\"one_day_max_episode_limit\":" + episode +
            ",\"one_day_max_series_limit\":" + series +
            ",\"total_max_series_limit\":" + total + "}"
    }

    private fun overwriteDownloadLimitObject(obj: Any?): Boolean {
        if (obj == null) return false
        val (episode, series, total) = currentDownloadLimitValues()
        val values = mapOf("a" to episode, "b" to series, "c" to total)
        return try {
            var changed = false
            for ((name, value) in values) {
                try {
                    val f = obj.javaClass.getDeclaredField(name)
                    if (f.type == Int::class.javaPrimitiveType) {
                        f.isAccessible = true
                        f.setInt(obj, value)
                        changed = true
                    }
                } catch (_: Throwable) {}
            }
            changed
        } catch (_: Throwable) { false }
    }
    private fun resolveEntryId(
        entry: String,
        root: View?,
        targetSet: MutableSet<Int> = gTargetIdSet,
        logPrefix: String = "资源",
    ) {
        if (root == null) return
        try {
            val id = root.resources.getIdentifier(entry, "id", gPkg)
            if (id > 0 && targetSet.add(id)) LogUtil.info("$logPrefix $entry ID: $id")
        } catch (_: Exception) {}
    }
    private fun resolvePauseRestoreIds(root: View?) {
        if (root == null) return
        gNames.pauseRestoreNames.forEach { resolveEntryId(it, root, gPauseRestoreIdSet, "暂停恢复资源") }
        when (gNames.profileId) {
            "OVERSEA-7.3.1.32" -> {
                resolveEntryId("right_interact_container", root, gPauseRestoreIdSet, "暂停恢复资源")
                resolveEntryId("ly_tools_bar_icon", root, gPauseRestoreIdSet, "暂停恢复资源")
            }
            "CN-7.3.3.18" -> resolveEntryId("fxu", root, gPauseRestoreIdSet, "暂停恢复资源")
        }
    }
    private fun quickMatch(v: View?): Boolean {
        if (v == null) return false
        LogUtil.incr("matchCall")
        try { val id = v.id; if (id > 0 && gTargetIdSet.contains(id)) { LogUtil.incr("matchHit"); return true } } catch (_: Exception) {}
        try { if (v.javaClass.name in hideClasses) { LogUtil.incr("matchHit"); return true } } catch (_: Exception) {}
        if (isFullscreenWatchControl(v)) { LogUtil.incr("matchHit"); return true }
        if (isKnownFullSeriesEntry(v) || isHomeFullSeriesEntry(v)) { LogUtil.incr("matchHit"); return true }
        if (isKnownBottomBackdrop(v) || isHomeBottomBackdropMarker(v)) { LogUtil.incr("matchHit"); return true }
        return false
    }

    private fun isFullscreenWatchControl(v: View?): Boolean {
        if (!gNames.structuralFullscreenWatch || v !is LinearLayout) return false
        try { if (v.id > 0) return false } catch (_: Throwable) {}
        val density = try { v.resources.displayMetrics.density.coerceAtLeast(0.1f) } catch (_: Throwable) { 1f }
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return false
        val widthDp = width / density
        val heightDp = height / density
        val leftDp = v.paddingLeft / density
        val topDp = v.paddingTop / density
        val rightDp = v.paddingRight / density
        val bottomDp = v.paddingBottom / density
        val bgOk = try { v.background?.javaClass?.name == "android.graphics.drawable.GradientDrawable" } catch (_: Throwable) { false }
        return bgOk && widthDp in 84f..94f && heightDp in 25f..32f &&
            leftDp in 8f..12f && rightDp in 8f..12f &&
            topDp in 4f..8f && bottomDp in 4f..8f
    }

    private fun isInsideCurrentActivityDecor(v: View?): Boolean {
        if (v == null) return false
        val decor = try { gCurrentActivity?.window?.decorView } catch (_: Throwable) { null } ?: return false
        var node: View? = v
        var guard = 0
        while (node != null && guard++ < 80) {
            if (node === decor) return true
            node = node.parent as? View
        }
        return false
    }

    private fun viewEntryName(v: View?): String {
        if (v == null) return ""
        return try {
            if (v.id > 0) v.resources.getResourceEntryName(v.id) else ""
        } catch (_: Throwable) { "" }
    }

    private fun collectUiTexts(root: View?, maxDepth: Int = 4): List<String> {
        if (root == null) return emptyList()
        val out = ArrayList<String>(8)
        fun walk(v: View, depth: Int) {
            if (depth > maxDepth || out.size >= 24) return
            if (v is TextView) {
                val text = try { v.text?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
                if (text.isNotEmpty()) out.add(text)
            }
            val desc = try { v.contentDescription?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            if (desc.isNotEmpty()) out.add(desc)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    private fun isRefreshText(text: String): Boolean {
        val t = text.lowercase()
        return text.contains("下拉刷新") || text.contains("刷新内容") || text.contains("松开刷新") ||
            text.contains("正在刷新") || t.contains("pull to refresh") || t.contains("release to refresh") ||
            t.contains("refreshing")
    }

    private fun isRefreshAccessoryContainer(v: View?): Boolean {
        if (v !is ViewGroup || !isInsideCurrentActivityDecor(v)) return false
        val density = try { v.resources.displayMetrics.density.coerceAtLeast(0.1f) } catch (_: Throwable) { 1f }
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return false
        val hDp = height / density
        if (hDp !in 39f..56f) return false
        val screenW = try { v.resources.displayMetrics.widthPixels } catch (_: Throwable) { width }
        if (screenW > 0 && width.toFloat() / screenW.toFloat() < 0.93f) return false

        if (collectUiTexts(v, 4).any(::isRefreshText)) return true
        val idName = viewEntryName(v)
        if (idName == "root_layout") {
            val parent = v.parent as? View
            if (collectUiTexts(parent, 3).any(::isRefreshText)) return true
        }
        return false
    }

    private fun hideRefreshAccessory(v: View?) {
        if (v == null || !gMasterOn || !gRefreshOff) return
        try {
            synchronized(gKnownRefreshAccessoryViews) { gKnownRefreshAccessoryViews[v] = true }
            if (v.visibility != View.GONE) {
                rememberViewState(v)

                setModuleVisibility(v, View.GONE)
                v.requestLayout()
                (v.parent as? View)?.requestLayout()
                LogUtil.incr("refreshAccessoryHide")
            }
        } catch (_: Throwable) {}
    }

    private fun scanTreeRefreshAccessory(v: View?) {
        if (v == null || !gMasterOn || !gRefreshOff) return
        if (isRefreshAccessoryContainer(v)) {
            hideRefreshAccessory(v)
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreeRefreshAccessory(v.getChildAt(i))
    }

    private fun restoreRefreshAccessories() {
        val views = synchronized(gKnownRefreshAccessoryViews) {
            gKnownRefreshAccessoryViews.keys.toList().also { gKnownRefreshAccessoryViews.clear() }
        }
        for (v in views) restoreView(v)
        if (views.isNotEmpty()) LogUtil.info("restore refresh accessories: ${views.size}")
    }

    private fun isHomeFullSeriesEntry(v: View?): Boolean {
        if (gPkg !in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE) ||
            v !is ViewGroup || !isInsideCurrentActivityDecor(v)) return false
        synchronized(gKnownFullSeriesEntryViews) {
            if (gKnownFullSeriesEntryViews.containsKey(v)) return true
        }
        if (v.javaClass.name != "android.widget.FrameLayout") return false
        if (viewEntryName(v) != "root_layout") return false

        val dm = try { v.resources.displayMetrics } catch (_: Throwable) { return false }
        val density = dm.density.coerceAtLeast(0.1f)
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return false
        val hDp = height / density
        if (hDp !in 41f..47.5f) return false
        if (width.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() < 0.94f) return false

        val loc = IntArray(2)
        try { v.getLocationOnScreen(loc) } catch (_: Throwable) { return false }

        if (loc[1] < dm.heightPixels * 0.68f) return false

        val texts = collectUiTexts(v, 5)
        val matchedByText = texts.any { raw ->
            val text = raw.replace(" ", "")
            val lower = text.lowercase()
            text.contains("观看完整漫剧") || text.contains("观看完整短剧") ||
                text.contains("观看全集") || text.contains("完整剧集") ||
                lower.contains("watchall") || lower.contains("allepisodes") || lower.contains("fullseries") ||
                (text.contains("观看") && Regex("全\\d+集").containsMatchIn(text))
        }
        val matched = matchedByText || gPkg == TargetNames.OVERSEA_PACKAGE
        if (!matched) return false

        synchronized(gKnownFullSeriesEntryViews) { gKnownFullSeriesEntryViews[v] = true }
        LogUtil.info("home full-series entry recognized: root_layout, h=${"%.1f".format(hDp)}dp")
        return true
    }

    private fun isKnownFullSeriesEntry(v: View?): Boolean = if (v == null) false else
        synchronized(gKnownFullSeriesEntryViews) { gKnownFullSeriesEntryViews.containsKey(v) }

    private fun isHomeBottomBackdropMarker(v: View?): Boolean {
        if (gPkg !in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE) ||
            v == null || !isInsideCurrentActivityDecor(v)) return false
        val markerName = viewEntryName(v)
        val markerMatched = when {
            gNames.bottomBackdropName.isNotBlank() -> markerName == gNames.bottomBackdropName
            gPkg == TargetNames.OVERSEA_PACKAGE -> markerName == "bottom_tab_mask"
            gNames.profileId == "CN-7.3.3.18" -> markerName == "ar9"
            gNames.profileId == "CN-7.3.2.32" || gNames.profileId == "CN-7.3.1.32" -> markerName == "ar8"
            else -> false
        }
        if (!markerMatched) return false
        val dm = try { v.resources.displayMetrics } catch (_: Throwable) { return false }
        val density = dm.density.coerceAtLeast(0.1f)
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return false
        val hDp = height / density
        if (hDp !in 50f..61f) return false
        if (width.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() < 0.94f) return false
        val loc = IntArray(2)
        try { v.getLocationOnScreen(loc) } catch (_: Throwable) { return false }
        return loc[1] + height >= dm.heightPixels * 0.94f
    }

    private fun isKnownBottomBackdrop(v: View?): Boolean = if (v == null) false else
        synchronized(gKnownBottomBackdropViews) { gKnownBottomBackdropViews.containsKey(v) }

    private fun isNativeMainBottomFrame(v: View?): Boolean {
        if (gPkg !in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE) || v == null) return false
        return try {
            val idName = viewEntryName(v)
            v.javaClass.name == "com.dragon.read.widget.BottomTabFrameLayout" ||
                (v is ViewGroup && (idName in gNames.mainNavNames || idName == "bottom_bar_layout"))
        } catch (_: Throwable) { false }
    }

    private fun isNativeVideoFeedBottomMask(v: View?): Boolean {
        if (gPkg !in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE) || v == null) return false
        return try {
            val idName = viewEntryName(v)
            when {
                gNames.bottomBackdropName.isNotBlank() -> idName == gNames.bottomBackdropName
                gPkg == TargetNames.OVERSEA_PACKAGE -> idName == "bottom_tab_mask"
                gNames.profileId == "CN-7.3.3.18" -> idName == "ar9"
                gNames.profileId == "CN-7.3.2.32" || gNames.profileId == "CN-7.3.1.32" -> idName == "ar8"
                gPkg == TargetNames.CN_PACKAGE -> false
                else -> false
            }
        } catch (_: Throwable) { false }
    }

    private fun rememberNativeNavBarColor(act: Activity?) {
        if (act == null) return
        try {
            synchronized(gSavedNativeNavBarColors) {
                if (!gSavedNativeNavBarColors.containsKey(act)) {
                    gSavedNativeNavBarColors[act] = act.window.navigationBarColor
                }
            }
        } catch (_: Throwable) {}
    }

    private fun updateNativeNavBarRestoreColor(act: Activity?) {
        if (act == null) return
        try { synchronized(gSavedNativeNavBarColors) { gSavedNativeNavBarColors[act] = act.window.navigationBarColor } }
        catch (_: Throwable) {}
    }

    private fun restoreNativeBottomWindowColor(act: Activity?) {
        if (act == null || gNavBarOff) return
        val color = synchronized(gSavedNativeNavBarColors) { gSavedNativeNavBarColors.remove(act) } ?: return
        try {
            act.window.navigationBarColor = color
            if (Build.VERSION.SDK_INT >= 30) {
                act.window.setDecorFitsSystemWindows(false)
                act.window.decorView.windowInsetsController?.show(WindowInsets.Type.navigationBars())
            }
            act.window.decorView.requestApplyInsets()
        } catch (_: Throwable) {}
    }

    private fun collapseNativeMainBottomFrame(v: View?) {
        // Keep the native navigation host visible; only selected tabs are hidden.
    }

    private fun collapseNativeVideoFeedBottomMask(v: View?) {
        // Keep the native navigation host visible; only selected tabs are hidden.
    }

    private fun findNativeMainBottomFrame(act: Activity?): View? {
        if (act == null || gPkg !in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE)) return null
        val fieldCandidates = when (gPkg) {
            TargetNames.CN_PACKAGE -> listOf("H", "G")
            TargetNames.OVERSEA_PACKAGE -> listOf("G", "H")
            else -> emptyList()
        }
        for (fieldName in fieldCandidates) {
            try {
                val byField = findFieldValue(act, fieldName) as? View
                if (isNativeMainBottomFrame(byField)) return byField
            } catch (_: Throwable) {}
        }
        val idCandidates = when (gPkg) {
            TargetNames.CN_PACKAGE -> gNames.mainNavNames + "bottom_bar_layout"
            TargetNames.OVERSEA_PACKAGE -> listOf("bottom_bar_layout", "aot")
            else -> emptyList()
        }
        for (entry in idCandidates) {
            try {
                val id = act.resources.getIdentifier(entry, "id", gPkg)
                if (id != 0) {
                    val byId = act.findViewById<View>(id)
                    if (isNativeMainBottomFrame(byId)) return byId
                }
            } catch (_: Throwable) {}
        }
        fun walk(v: View?): View? {
            if (v == null) return null
            if (isNativeMainBottomFrame(v)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) {
                val hit = walk(v.getChildAt(i))
                if (hit != null) return hit
            }
            return null
        }
        return try { walk(act.window?.decorView) } catch (_: Throwable) { null }
    }

    private fun enforceNativeMainBottomHidden(act: Activity?) {
        // Keep the native navigation host visible; only selected tabs are hidden.
    }

    private fun findFeedViewport(root: View?): View? {
        if (root == null) return null
        try {
            if (root.javaClass.name == "androidx.viewpager2.widget.ViewPager2") return root
            val idName = viewEntryName(root)

            if (idName == "kkk" || idName == "kij" || idName == "view_pager_container") return root
        } catch (_: Throwable) {}
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val hit = findFeedViewport(root.getChildAt(i))
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun reclaimFeedViewportBottomMargin(root: View?) {
        // Keep the native navigation host visible; only selected tabs are hidden.
    }

    private fun enforceKnownFeedViewportBottomMargin() {
        // Keep the native navigation host visible; only selected tabs are hidden.
    }

    private fun restoreFeedViewportBottomMargins(clear: Boolean = true) {
        val entries = synchronized(gFeedViewportNativeBottomMargins) {
            gFeedViewportNativeBottomMargins.entries.map { it.key to it.value }.also {
                if (clear) gFeedViewportNativeBottomMargins.clear()
            }
        }
        for ((pager, bottom) in entries) {
            try {
                val lp = pager.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                if (lp.bottomMargin != bottom) {
                    lp.bottomMargin = bottom
                    pager.layoutParams = lp
                    pager.requestLayout()
                    (pager.parent as? View)?.requestLayout()
                }
            } catch (_: Throwable) {}
        }
        if (clear) synchronized(gKnownFeedViewportViews) { gKnownFeedViewportViews.clear() }
        if (entries.isNotEmpty()) LogUtil.info("首页视频底部占位已恢复 | count=${entries.size}")
    }

    private fun reflectIntField(obj: Any?, name: String): Int? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.getInt(obj)
            } catch (_: Throwable) {}
            c = c.superclass
        }
        return null
    }

    private fun setReflectIntField(obj: Any?, name: String, value: Int): Boolean {
        if (obj == null) return false
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                f.setInt(obj, value)
                return true
            } catch (_: Throwable) {}
            c = c.superclass
        }
        return false
    }

    private fun rememberBottomLayoutState(v: View) {
        synchronized(gSavedBottomLayoutStates) {
            if (gSavedBottomLayoutStates.containsKey(v)) return
            val lp = v.layoutParams
            val mlp = lp as? ViewGroup.MarginLayoutParams
            gSavedBottomLayoutStates[v] = SavedBottomLayoutState(
                width = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                height = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                paddingLeft = v.paddingLeft,
                paddingTop = v.paddingTop,
                paddingRight = v.paddingRight,
                paddingBottom = v.paddingBottom,
                marginLeft = mlp?.leftMargin,
                marginTop = mlp?.topMargin,
                marginRight = mlp?.rightMargin,
                marginBottom = mlp?.bottomMargin,
                bottomToTop = reflectIntField(lp, "bottomToTop"),
                bottomToBottom = reflectIntField(lp, "bottomToBottom"),
                goneBottomMargin = reflectIntField(lp, "goneBottomMargin"),
            )
        }
    }

    private fun restoreBottomLayoutReclaim() {
        val entries = synchronized(gSavedBottomLayoutStates) {
            val copy = gSavedBottomLayoutStates.entries.map { it.key to it.value }
            gSavedBottomLayoutStates.clear()
            copy
        }
        for ((v, st) in entries) {
            try {
                val lp = v.layoutParams
                if (lp != null) {
                    lp.width = st.width
                    lp.height = st.height
                    val mlp = lp as? ViewGroup.MarginLayoutParams
                    if (mlp != null && st.marginLeft != null && st.marginTop != null &&
                        st.marginRight != null && st.marginBottom != null) {
                        mlp.setMargins(st.marginLeft, st.marginTop, st.marginRight, st.marginBottom)
                    }
                    st.bottomToTop?.let { setReflectIntField(lp, "bottomToTop", it) }
                    st.bottomToBottom?.let { setReflectIntField(lp, "bottomToBottom", it) }
                    st.goneBottomMargin?.let { setReflectIntField(lp, "goneBottomMargin", it) }
                    v.layoutParams = lp
                }
                v.setPadding(st.paddingLeft, st.paddingTop, st.paddingRight, st.paddingBottom)
                v.requestLayout()
                (v.parent as? View)?.requestLayout()
            } catch (_: Throwable) {}
        }
        if (entries.isNotEmpty()) LogUtil.info("restore bottom layout reclaim: ${entries.size}")
    }

    private fun isNear(value: Int, target: Int, tolerance: Int): Boolean =
        kotlin.math.abs(value - target) <= tolerance

    private fun reclaimBottomSpaceFromAr9(marker: View) {
        if (!gMasterOn || !gControlOn || pauseRestoresControls()) return
        try {
            val dm = marker.resources.displayMetrics
            val density = dm.density.coerceAtLeast(0.1f)
            val markerLoc = IntArray(2)
            marker.getLocationOnScreen(markerLoc)
            val markerTop = markerLoc[1]
            val markerHeight = (if (marker.height > 0) marker.height else marker.measuredHeight).coerceAtLeast(1)
            val tol = (12f * density).toInt().coerceAtLeast(8)
            val minLargeHeight = (dm.heightPixels * 0.28f).toInt()

            val anchorIds = linkedSetOf<Int>()
            var idNode: View? = marker
            var idDepth = 0
            while (idNode != null && idDepth++ < 8) {
                if (idNode.id > 0) anchorIds.add(idNode.id)
                idNode = idNode.parent as? View
            }

            fun reclaimLargeBranch(root: View?, depth: Int = 0) {
                if (root == null || depth > 4) return
                try {
                    val w = if (root.width > 0) root.width else root.measuredWidth
                    val h = if (root.height > 0) root.height else root.measuredHeight
                    if (w > 0 && h > minLargeHeight &&
                        w.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() >= 0.82f) {
                        val lp = root.layoutParams
                        val mlp = lp as? ViewGroup.MarginLayoutParams
                        val bottomMargin = mlp?.bottomMargin ?: 0
                        val padBottom = root.paddingBottom
                        val barLikeMin = (markerHeight * 0.55f).toInt()
                        val barLikeMax = (markerHeight * 1.75f).toInt()
                        var changed = false
                        if (padBottom in barLikeMin..barLikeMax && padBottom > 0) {
                            rememberBottomLayoutState(root)
                            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
                            changed = true
                        }
                        if (mlp != null && bottomMargin in barLikeMin..barLikeMax && bottomMargin > 0) {
                            rememberBottomLayoutState(root)
                            mlp.bottomMargin = 0
                            root.layoutParams = mlp
                            changed = true
                        }
                        if (changed) {
                            root.requestLayout()
                            LogUtil.incr("bottomInsetReclaim")
                        }
                    }
                } catch (_: Throwable) {}
                if (root is ViewGroup) {
                    for (i in 0 until root.childCount) reclaimLargeBranch(root.getChildAt(i), depth + 1)
                }
            }

            var branch: View = marker
            var parent = marker.parent as? ViewGroup
            var depth = 0
            while (parent != null && depth++ < 7) {
                val parentChildren = parent.childCount
                for (i in 0 until parentChildren) {
                    val child = parent.getChildAt(i)
                    if (child === branch) continue
                    try {
                        val lp = child.layoutParams
                        var changed = false

                        val btt = reflectIntField(lp, "bottomToTop")
                        if (btt != null && btt >= 0 && anchorIds.contains(btt)) {
                            rememberBottomLayoutState(child)
                            setReflectIntField(lp, "bottomToTop", -1)
                            setReflectIntField(lp, "bottomToBottom", 0)
                            setReflectIntField(lp, "goneBottomMargin", 0)
                            child.layoutParams = lp
                            changed = true
                            LogUtil.info("bottom constraint reclaimed: ${child.javaClass.name}, anchor=$btt")
                            LogUtil.incr("bottomConstraintReclaim")
                        }

                        val loc = IntArray(2)
                        child.getLocationOnScreen(loc)
                        val w = if (child.width > 0) child.width else child.measuredWidth
                        val h = if (child.height > 0) child.height else child.measuredHeight
                        val childBottom = loc[1] + h
                        val looksLikeMainContent = w > 0 && h > minLargeHeight &&
                            w.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() >= 0.82f &&
                            loc[1] < markerTop - markerHeight
                        if (looksLikeMainContent && isNear(childBottom, markerTop, tol) && lp != null) {
                            val parentLoc = IntArray(2)
                            parent.getLocationOnScreen(parentLoc)
                            val parentH = if (parent.height > 0) parent.height else parent.measuredHeight
                            val desiredHeight = parentLoc[1] + parentH - loc[1]
                            val growBy = desiredHeight - h
                            val maxGrow = (markerHeight * 1.8f).toInt().coerceAtLeast(markerHeight + tol)
                            if (growBy > tol && growBy <= maxGrow) {
                                rememberBottomLayoutState(child)
                                lp.height = desiredHeight
                                child.layoutParams = lp
                                changed = true
                                LogUtil.info("bottom content extended: ${child.javaClass.name}, ${h}px -> ${desiredHeight}px")
                                LogUtil.incr("bottomHeightReclaim")
                            }
                        }

                        if (looksLikeMainContent) reclaimLargeBranch(child)

                        if (changed) {
                            child.requestLayout()
                            parent.requestLayout()
                        }
                    } catch (_: Throwable) {}
                }
                branch = parent
                parent = parent.parent as? ViewGroup
            }

            val decor = gCurrentActivity?.window?.decorView
            reclaimLargeBranch(decor)
        } catch (e: Throwable) {
            LogUtil.warn("reclaim bottom space from ar9 failed: $e")
        }
    }

    private fun bottomBackdropCollapseTarget(marker: View): View {
        val dm = try { marker.resources.displayMetrics } catch (_: Throwable) { return marker }
        val density = dm.density.coerceAtLeast(0.1f)
        var target: View = marker
        var cur: View = marker
        repeat(4) {
            val parent = cur.parent as? View ?: return@repeat
            val w = if (parent.width > 0) parent.width else parent.measuredWidth
            val h = if (parent.height > 0) parent.height else parent.measuredHeight
            if (w <= 0 || h <= 0) return@repeat
            val hDp = h / density
            val loc = IntArray(2)
            try { parent.getLocationOnScreen(loc) } catch (_: Throwable) { return@repeat }
            val fullWidth = w.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() >= 0.92f
            val compact = hDp in 48f..128f
            val nearBottom = loc[1] + h >= dm.heightPixels * 0.92f
            if (!fullWidth || !compact || !nearBottom) return@repeat
            target = parent
            cur = parent
        }
        return target
    }

    private fun collapseHomeBottomBackdrop(marker: View?) {
        if (marker == null || !gMasterOn || !gControlOn || pauseRestoresControls()) return

        if (isNativeVideoFeedBottomMask(marker) || isHomeBottomBackdropMarker(marker) || isKnownBottomBackdrop(marker)) {
            collapseNativeVideoFeedBottomMask(marker)
            enforceNativeMainBottomHidden(gCurrentActivity)
        }
    }

    private val mainBottomNavLabels = setOf("首页", "剧场", "商城", "赚钱", "我的")

    private fun isMainBottomNavContainer(v: View?): Boolean {
        if (gPkg != TargetNames.CN_PACKAGE || v !is ViewGroup || !isInsideCurrentActivityDecor(v)) return false
        synchronized(gKnownMainBottomNavViews) { if (gKnownMainBottomNavViews.containsKey(v)) return true }
        val density = try { v.resources.displayMetrics.density.coerceAtLeast(0.1f) } catch (_: Throwable) { 1f }
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return false
        val hDp = height / density
        if (hDp !in 38f..105f) return false
        val dm = try { v.resources.displayMetrics } catch (_: Throwable) { null } ?: return false
        if (width.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() < 0.90f) return false
        val loc = IntArray(2)
        try { v.getLocationOnScreen(loc) } catch (_: Throwable) { return false }
        if (loc[1] < dm.heightPixels * 0.55f) return false
        val labels = collectUiTexts(v, 5).map { it.trim() }.toSet()
        val hitCount = mainBottomNavLabels.count { target -> labels.any { it == target } }

        var structuralSlots = 0
        if (v.childCount in 4..7) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i) ?: continue
                val cw = if (child.width > 0) child.width else child.measuredWidth
                val ch = if (child.height > 0) child.height else child.measuredHeight
                val cwRatio = cw.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat()
                val chDp = ch / density
                if (cwRatio in 0.12f..0.30f && chDp >= 24f) structuralSlots++
            }
        }
        val nearBottom = loc[1] + height >= dm.heightPixels * 0.90f
        val structuralMatch = nearBottom && (
            structuralSlots >= 4 ||

                (v.childCount == 5 && hDp in 48f..88f)
            )
        if (hitCount < 4 && !structuralMatch) return false
        synchronized(gKnownMainBottomNavViews) { gKnownMainBottomNavViews[v] = true }
        LogUtil.info("main bottom nav recognized: ${v.javaClass.name}, h=${"%.1f".format(hDp)}dp, labels=$hitCount, slots=$structuralSlots")
        return true
    }

    private fun isKnownMainBottomNav(v: View?): Boolean = if (v == null) false else
        synchronized(gKnownMainBottomNavViews) { gKnownMainBottomNavViews.containsKey(v) }

    private fun shouldCollapseControl(v: View?): Boolean {
        if (v == null) return false
        val idName = viewEntryName(v)

        if (idName == "iu1" || idName == "is7") return true
        if (isKnownFullSeriesEntry(v) || isHomeFullSeriesEntry(v)) return true
        if (isNativeMainBottomFrame(v) || isNativeVideoFeedBottomMask(v)) return true
        if (isKnownBottomBackdrop(v) || isHomeBottomBackdropMarker(v)) return true
        return isMainBottomNavContainer(v)
    }

    private inline fun <T> internalViewMutation(block: () -> T): T {
        val old = gInternalViewMutation.get() == true
        gInternalViewMutation.set(true)
        return try { block() } finally { gInternalViewMutation.set(old) }
    }

    private fun rememberViewState(v: View, desiredVisibility: Int? = null) {
        synchronized(gSavedViewStates) {
            val old = gSavedViewStates[v]
            if (old == null) {
                gSavedViewStates[v] = SavedViewState(desiredVisibility ?: v.visibility, v.alpha)
            } else if (desiredVisibility != null) {

                old.visibility = desiredVisibility
            }
        }
    }

    private fun setModuleVisibility(v: View, visibility: Int) {
        internalViewMutation { v.visibility = visibility }
    }

    private fun blindView(v: View?) {
        if (v == null || !gMasterOn || !gControlOn || pauseRestoresControls()) return
        try {

            if (v.visibility != View.VISIBLE) return
            rememberViewState(v)
            val collapse = shouldCollapseControl(v)
            setModuleVisibility(v, if (collapse) View.GONE else View.INVISIBLE)
            if (collapse) {
                v.requestLayout()
                (v.parent as? View)?.requestLayout()
                LogUtil.incr("collapseControl")
            }
            LogUtil.incr("blindOK")
        } catch (_: Exception) {}
    }

    private fun restoreView(v: View?) {
        if (v == null) return
        val state = synchronized(gSavedViewStates) { gSavedViewStates.remove(v) } ?: return
        try {
            internalViewMutation {
                v.alpha = state.alpha
                v.visibility = state.visibility
            }
        } catch (_: Exception) {}
        try { v.requestLayout() } catch (_: Exception) {}
        try { (v.parent as? View)?.requestLayout() } catch (_: Exception) {}
    }

    private fun restoreAllSavedViews() {
        controlVisibility.policy = controlPolicy()
        controlVisibility.restoreAll()
        restoreFeedViewportBottomMargins(clear = true)
        restoreBottomLayoutReclaim()
        val entries = synchronized(gSavedViewStates) {
            val copy = gSavedViewStates.entries.map { Pair(it.key, SavedViewState(it.value.visibility, it.value.alpha)) }
            gSavedViewStates.clear()
            copy
        }
        for ((v, state) in entries) {
            try {
                internalViewMutation {
                    v.alpha = state.alpha
                    v.visibility = state.visibility
                }
                v.requestLayout()
                (v.parent as? View)?.requestLayout()
            } catch (_: Throwable) {}
        }
        synchronized(gForcedShortMaskVisibility) { gForcedShortMaskVisibility.clear() }
        synchronized(gCleanMaskViews) { gCleanMaskViews.clear() }
        if (entries.isNotEmpty()) LogUtil.info("restore saved views: ${entries.size}")
    }
    private fun forceOnePauseRestoreView(v: View?) {
        if (v == null) return
        try {
            synchronized(gPauseForcedStates) {
                if (!gPauseForcedStates.containsKey(v)) {
                    gPauseForcedStates[v] = PauseForcedState(
                        v.visibility, v.alpha, v.translationX, v.translationY,
                    )
                }
            }
            internalViewMutation {
                v.visibility = View.VISIBLE
                v.alpha = 1f
                v.translationX = 0f
                v.translationY = 0f
            }
            v.requestLayout()
        } catch (_: Throwable) {}
    }

    private fun scanTreePauseRestore(v: View?) {
        if (v == null) return
        try {
            if (v.id > 0 && gPauseRestoreIdSet.contains(v.id)) {
                forceOnePauseRestoreView(v)
            }
        } catch (_: Throwable) {}
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreePauseRestore(v.getChildAt(i))
    }

    private fun forcePauseRightAgencyTree(agency: Any) {
        val rightRoot = findFieldValue(agency, "p") as? View ?: return
        forceOnePauseRestoreView(rightRoot)
        scanTreePauseRestore(rightRoot)

        val decor = try { gCurrentActivity?.window?.decorView } catch (_: Throwable) { null }
        var node = rightRoot.parent as? View
        var depth = 0
        while (node != null && depth++ < 3) {
            if (node === decor) break
            val suppressed = try {
                node.visibility != View.VISIBLE ||
                    node.alpha < 0.99f ||
                    java.lang.Math.abs(node.translationX) > 1f ||
                    java.lang.Math.abs(node.translationY) > 1f
            } catch (_: Throwable) { false }
            if (suppressed) forceOnePauseRestoreView(node)
            node = node.parent as? View
        }
    }

    private fun forcePauseRestoreControls() {
        if (!pauseRestoresControls()) return

        syncCustomControls(gCurrentActivity?.window?.decorView)
    }

    private fun restorePauseForcedViews() {
        val entries = synchronized(gPauseForcedStates) {
            val copy = gPauseForcedStates.entries.map { Pair(it.key, it.value) }
            gPauseForcedStates.clear()
            copy
        }
        for ((v, state) in entries) {
            try {
                internalViewMutation {
                    v.visibility = state.visibility
                    v.alpha = state.alpha
                    v.translationX = state.translationX
                    v.translationY = state.translationY
                }
                v.requestLayout()
            } catch (_: Throwable) {}
        }
        if (entries.isNotEmpty()) LogUtil.info("pause restore temporary states restored: ${entries.size}")
    }

    private fun isRedGuoAd(v: View?): Boolean {
        if (v == null) return false
        try { if (v is TextView && (v.text?.toString() ?: "").contains("红果")) return true } catch (_: Exception) {}
        if (v is ViewGroup) for (i in 0 until v.childCount) if (isRedGuoAd(v.getChildAt(i))) return true
        return false
    }
    private fun scanTreeQuick(v: View?) {
        syncCustomControls(v)
    }

    private fun registerShortVideoHolder(holder: Any?, playbackState: Int? = null) {
        if (holder == null) return
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(gShortVideoHolders) {
            gShortVideoHolders.removeAll { it.ref.get() == null }
            val old = gShortVideoHolders.firstOrNull { it.ref.get() === holder }
            if (old != null) {
                gShortVideoHolders.remove(old)
                if (playbackState != null) old.playbackState = playbackState
                old.updatedAt = now
                gShortVideoHolders.add(old)
            } else {
                gShortVideoHolders.add(
                    ShortVideoHolderState(
                        java.lang.ref.WeakReference(holder),
                        playbackState ?: 0,
                        now,
                    )
                )
            }
        }
    }

    private fun shortVideoHolderSnapshot(): List<Pair<Any, Int>> = synchronized(gShortVideoHolders) {
        val result = mutableListOf<Pair<Any, Int>>()
        val iterator = gShortVideoHolders.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val holder = item.ref.get()
            if (holder == null) iterator.remove() else result.add(Pair(holder, item.playbackState))
        }
        result
    }

    private fun findFieldValue(instance: Any, fieldName: String): Any? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
            } catch (_: Throwable) {}
            clazz = clazz.superclass
        }
        return null
    }

    private fun registerRightViewAgency(agency: Any?) {
        if (agency == null) return
        synchronized(gRightViewAgencies) {
            val it = gRightViewAgencies.iterator()
            while (it.hasNext()) {
                val current = it.next().get()
                if (current == null || current === agency) it.remove()
            }
            gRightViewAgencies.add(java.lang.ref.WeakReference(agency))
        }
    }

    private fun rightViewAgencySnapshot(): List<Any> = synchronized(gRightViewAgencies) {
        val result = mutableListOf<Any>()
        val it = gRightViewAgencies.iterator()
        while (it.hasNext()) {
            val agency = it.next().get()
            if (agency == null) it.remove() else result.add(agency)
        }
        result
    }

    private fun rightViewAgencyRoot(agency: Any): View? = findFieldValue(agency, "p") as? View

    private fun isRightViewAgencyVisible(agency: Any): Boolean {
        val rightView = rightViewAgencyRoot(agency) ?: return false
        return try {
            rightView.windowToken != null && rightView.width > 0 && rightView.height > 0
        } catch (_: Throwable) { false }
    }

    private fun isAgencyInCurrentActivityWindow(agency: Any): Boolean {
        val rightView = rightViewAgencyRoot(agency) ?: return false
        val currentToken = try { gCurrentActivity?.window?.decorView?.windowToken } catch (_: Throwable) { null }

        return try {
            if (currentToken == null) {
                rightView.windowToken != null && rightView.isAttachedToWindow
            } else {
                rightView.windowToken === currentToken && rightView.isAttachedToWindow
            }
        } catch (_: Throwable) { false }
    }

    private fun openCurrentCommentFromDoubleTap(): Boolean {
        if (!gMasterOn || !gDoubleTapCommentOn) return false
        val snapshot = rightViewAgencySnapshot()
        if (snapshot.isEmpty()) return false

        val currentWindow = snapshot.filter { isAgencyInCurrentActivityWindow(it) }.asReversed()
        val visibleFallback = snapshot.filter { isRightViewAgencyVisible(it) }.asReversed()
        val candidates = if (currentWindow.isNotEmpty()) currentWindow else visibleFallback

        for (agency in candidates) {
            try {
                var clazz: Class<*>? = agency.javaClass
                var method: java.lang.reflect.Method? = null
                while (clazz != null && method == null) {
                    method = try {
                        clazz.getDeclaredMethod(
                            gNames.rightViewAgencyEventMethod,
                            android.os.Bundle::class.java,
                            String::class.java,
                        ).apply { isAccessible = true }
                    } catch (_: Throwable) { null }
                    clazz = clazz.superclass
                }
                if (method == null) continue
                method.invoke(agency, android.os.Bundle(), "show_comment_dialog")
                LogUtil.incr("doubleTapOpenComment")
                LogUtil.info("双击评论事件已发送 | profile=${gNames.profileId} | agency=${agency.javaClass.name}.${gNames.rightViewAgencyEventMethod}")
                return true
            } catch (e: Throwable) {
                LogUtil.warn("双击打开评论区失败(${agency.javaClass.name}): $e")
            }
        }
        return false
    }

    private fun registerShortSeriesFragment(fragment: Any?) {
        if (fragment == null) return
        synchronized(gShortSeriesFragments) {
            gShortSeriesFragments.removeAll { it.get() == null }
            val old = gShortSeriesFragments.firstOrNull { it.get() === fragment }
            if (old != null) gShortSeriesFragments.remove(old)
            gShortSeriesFragments.add(java.lang.ref.WeakReference(fragment))
        }
    }

    private fun shortSeriesFragmentSnapshot(): List<Any> = synchronized(gShortSeriesFragments) {
        val result = mutableListOf<Any>()
        val iterator = gShortSeriesFragments.iterator()
        while (iterator.hasNext()) {
            val fragment = iterator.next().get()
            if (fragment == null) iterator.remove() else result.add(fragment)
        }
        result
    }

    private fun activityFromFragment(fragment: Any?): Activity? {
        if (fragment == null) return null
        return try {
            fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
        } catch (_: Throwable) { null }
    }

    private fun shortVideoHolderRoot(holder: Any): View? {
        try {
            val root = holder.javaClass.getMethod("getRootView").invoke(holder) as? View
            if (root != null) return root
        } catch (_: Throwable) {}
        return findFieldValue(holder, "itemView") as? View
    }

    private fun isShortVideoHolderVisible(holder: Any): Boolean {
        val root = shortVideoHolderRoot(holder) ?: return false
        return try {
            if (!isInsideCurrentActivityDecor(root)) return false
            if (!root.isShown || root.windowToken == null || root.width <= 0 || root.height <= 0) return false
            val visible = Rect()
            if (!root.getGlobalVisibleRect(visible)) return false
            val visibleArea = visible.width().toLong() * visible.height().toLong()
            val totalArea = root.width.toLong() * root.height.toLong()
            visibleArea * 4L >= totalArea
        } catch (_: Throwable) { false }
    }

    private fun detectPausedFromShortVideoHolders(): Boolean? {
        for ((holder, state) in shortVideoHolderSnapshot().asReversed()) {
            if (!isShortVideoHolderVisible(holder)) continue
            when (state) {
                1 -> return false
                2 -> return true
            }
            try {
                val playing = holder.javaClass.getMethod("isVideoPlaying").invoke(holder) as? Boolean
                if (playing == true) return false
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun shouldForceShortVideoCleanMask(): Boolean {
        // Native clean masks hide unselected siblings too.
        return false
    }

    private fun rememberAndForceShortVideoMaskInvisible(holder: Any) {
        try {
            val mask = findFieldValue(holder, gNames.shortMaskField) as? View ?: return
            if (mask.visibility != View.VISIBLE) return
            synchronized(gForcedShortMaskVisibility) {
                if (!gForcedShortMaskVisibility.containsKey(mask)) {
                    gForcedShortMaskVisibility[mask] = mask.visibility
                }
            }
            rememberViewState(mask)
            synchronized(gCleanMaskViews) { gCleanMaskViews[mask] = true }
            setModuleVisibility(mask, View.INVISIBLE)
            LogUtil.incr("shortMaskDirectFallback")
        } catch (e: Throwable) {
            LogUtil.warn("force short-video mask invisible failed: ${holder.javaClass.name}: $e")
        }
    }

    private fun restoreForcedShortVideoMask(holder: Any) {
        try {
            val mask = findFieldValue(holder, gNames.shortMaskField) as? View ?: return
            val original = synchronized(gForcedShortMaskVisibility) {
                gForcedShortMaskVisibility.remove(mask)
            } ?: return

            synchronized(gCleanMaskViews) { gCleanMaskViews.remove(mask) }
            val saved = synchronized(gSavedViewStates) { gSavedViewStates[mask] }
            if (saved != null) restoreView(mask) else internalViewMutation { mask.visibility = original }
            LogUtil.incr("shortMaskDirectRestore")
        } catch (e: Throwable) {
            LogUtil.warn("restore short-video mask failed: ${holder.javaClass.name}: $e")
        }
    }

    private fun setOneShortVideoMaskClear(holder: Any, clear: Boolean) {
        try {

            if (clear) rememberAndForceShortVideoMaskInvisible(holder)
            else restoreForcedShortVideoMask(holder)
        } catch (e: Throwable) {
            LogUtil.warn("set short-video mask clear=$clear failed: ${holder.javaClass.name}: $e")
        }
    }

    private fun syncShortVideoMasks() {
        val holders = shortVideoHolderSnapshot().asReversed()
        if (shouldForceShortVideoCleanMask()) {
            var handledVisibleHolder = false
            for ((holder, _) in holders) {
                if (!isShortVideoHolderVisible(holder)) continue
                setOneShortVideoMaskClear(holder, true)
                handledVisibleHolder = true
            }

            if (!handledVisibleHolder) holders.firstOrNull()?.first?.let {
                setOneShortVideoMaskClear(it, true)
            }
            return
        }

        if (!gMasterOn || !gControlOn || pauseRestoresControls()) {
            for ((holder, _) in holders) restoreForcedShortVideoMask(holder)
        }
    }

    private fun registerCnHomeFragment(fragment: Any?) {
        if (fragment == null || gPkg != "com.phoenix.read") return
        synchronized(gCnHomeFragments) {
            gCnHomeFragments.removeAll { it.get() == null }
            val old = gCnHomeFragments.firstOrNull { it.get() === fragment }
            if (old != null) gCnHomeFragments.remove(old)
            gCnHomeFragments.add(java.lang.ref.WeakReference(fragment))
        }
    }

    private fun cnHomeFragmentSnapshot(): List<Any> = synchronized(gCnHomeFragments) {
        val result = mutableListOf<Any>()
        val it = gCnHomeFragments.iterator()
        while (it.hasNext()) {
            val item = it.next().get()
            if (item == null) it.remove() else result.add(item)
        }
        result
    }

    private fun forceCnHomeFragmentMaskInvisible(fragment: Any) {
        if (gPkg != "com.phoenix.read" || !shouldForceShortVideoCleanMask()) return
        try {
            val fieldName = gNames.homeFragmentMaskField
            if (fieldName.isBlank()) return
            val mask = findFieldValue(fragment, fieldName) as? View ?: return
            if (mask.visibility != View.VISIBLE) return
            rememberViewState(mask)
            synchronized(gCleanMaskViews) { gCleanMaskViews[mask] = true }
            setModuleVisibility(mask, View.INVISIBLE)
            LogUtil.incr("homeFragmentMaskHide")
        } catch (e: Throwable) {
            LogUtil.warn("home fragment mask hide failed: $e")
        }
    }

    private fun syncCnHomeFragmentMasks() {
        if (gPkg != "com.phoenix.read" || !shouldForceShortVideoCleanMask()) return
        for (fragment in cnHomeFragmentSnapshot().asReversed()) {
            forceCnHomeFragmentMaskInvisible(fragment)
        }
    }

    private fun setOneShortVideoControlsVisible(holder: Any, visible: Boolean) {
        if (!visible) return
        var managerHandled = false
        try {
            val cleanScreenManager = findFieldValue(holder, gNames.shortCleanManagerField)
            if (cleanScreenManager != null) {
                cleanScreenManager.javaClass.getDeclaredMethod("b", Boolean::class.java).apply {
                    isAccessible = true
                }.invoke(cleanScreenManager, false)
                managerHandled = true
            }
        } catch (e: Throwable) {
            LogUtil.warn("exit short-video clean screen failed: $e")
        }

        try {
            holder.javaClass.getMethod(gNames.shortControlsMethod, Boolean::class.java, Boolean::class.java)
                .invoke(holder, false, true)
        } catch (e: Throwable) {
            if (!managerHandled) {
                LogUtil.warn("show short-video controls failed: ${holder.javaClass.name}: $e")
            }
        }
    }

    private fun restoreShortVideoNativeControls() {
        val holders = shortVideoHolderSnapshot().asReversed()
        var restoredVisibleHolder = false
        for ((holder, _) in holders) {
            if (!isShortVideoHolderVisible(holder)) continue
            setOneShortVideoControlsVisible(holder, true)
            restoreForcedShortVideoMask(holder)
            shortVideoHolderRoot(holder)?.requestLayout()
            restoredVisibleHolder = true
        }

        if (!restoredVisibleHolder) holders.firstOrNull()?.first?.let {
            setOneShortVideoControlsVisible(it, true)
            restoreForcedShortVideoMask(it)
        }
    }

    private fun registerVideoToolbarLayer(layer: Any?) {
        if (layer == null) return
        var added = false
        synchronized(gVideoToolbarLayers) {
            gVideoToolbarLayers.removeAll { it.get() == null }
            val old = gVideoToolbarLayers.firstOrNull { it.get() === layer }
            if (old != null) {

                gVideoToolbarLayers.remove(old)
            } else {
                added = true
            }
            gVideoToolbarLayers.add(java.lang.ref.WeakReference(layer))
        }
        if (added) LogUtil.info("video layer registered: ${layer.javaClass.name}")
        if (added && pauseRestoresControls()) {
            mainHandler.post { setOneVideoToolbarVisible(layer, true); forcePauseRestoreControls() }
        }
    }
    private fun videoToolbarLayerSnapshot(): List<Any> = synchronized(gVideoToolbarLayers) {
        val result = mutableListOf<Any>()
        val iterator = gVideoToolbarLayers.iterator()
        while (iterator.hasNext()) {
            val layer = iterator.next().get()
            if (layer == null) iterator.remove() else result.add(layer)
        }
        result
    }

    private fun registerToolbarBaseLayer(layer: Any?) {
        if (layer == null) return
        synchronized(gToolbarBaseLayers) {
            gToolbarBaseLayers.removeAll { it.get() == null }
            val old = gToolbarBaseLayers.firstOrNull { it.get() === layer }
            if (old != null) gToolbarBaseLayers.remove(old)
            gToolbarBaseLayers.add(java.lang.ref.WeakReference(layer))
        }
    }

    private fun toolbarBaseLayerSnapshot(): List<Any> = synchronized(gToolbarBaseLayers) {
        val out = mutableListOf<Any>()
        val it = gToolbarBaseLayers.iterator()
        while (it.hasNext()) {
            val v = it.next().get()
            if (v == null) it.remove() else out.add(v)
        }
        out
    }

    private fun setToolbarBaseVisible(visible: Boolean) {
        for (layer in toolbarBaseLayerSnapshot()) {
            try {
                var type: Class<*>? = layer.javaClass
                var method: java.lang.reflect.Method? = null
                while (type != null && method == null) {
                    method = try { type.getDeclaredMethod("a", Boolean::class.java) } catch (_: Throwable) { null }
                    type = type.superclass
                }
                method?.apply { isAccessible = true }?.invoke(layer, visible)
            } catch (_: Throwable) {}
        }
    }

    private fun setOneVideoToolbarVisible(layer: Any, visible: Boolean) {
        try {
            when (layer.javaClass.name) {
                "com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed" ->
                    layer.javaClass.getDeclaredMethod(gNames.fixedToolbarShowMethod, Boolean::class.java).apply { isAccessible = true }.invoke(layer, visible)
                "com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer" ->
                    layer.javaClass.getDeclaredMethod(gNames.customizeToolbarShowMethod, Boolean::class.java).apply { isAccessible = true }.invoke(layer, visible)
            }
        } catch (e: Throwable) {
            LogUtil.warn("set video toolbar visible=$visible failed: ${layer.javaClass.name}: $e")
        }
    }

    private fun setVideoToolbarsVisible(visible: Boolean) {
        mainHandler.post {
            for (layer in videoToolbarLayerSnapshot()) {
                setOneVideoToolbarVisible(layer, visible)

                if (visible && layer.javaClass.name == "com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer") {
                    try {
                        layer.javaClass.getDeclaredMethod(
                            gNames.customizeToolbarApplyMethod,
                            Boolean::class.java,
                            Boolean::class.java,
                            Boolean::class.java,
                        ).apply { isAccessible = true }.invoke(layer, true, false, false)
                    } catch (_: Throwable) {}
                }
            }
            setToolbarBaseVisible(visible)
        }
    }

    private fun detectPausedFromVideoLayers(): Boolean? {

        for (layer in videoToolbarLayerSnapshot().asReversed()) {
            try {
                val inquirer = layer.javaClass.getMethod("getVideoStateInquirer").invoke(layer) ?: continue
                val paused = inquirer.javaClass.getMethod("isPaused").invoke(inquirer) as? Boolean ?: false
                if (paused) return true
                val playing = inquirer.javaClass.getMethod("isPlaying").invoke(inquirer) as? Boolean ?: false
                if (playing) return false
            } catch (_: Throwable) {}
        }
        return null
    }
    private fun refreshVideoPauseState(reason: String, fallback: Boolean? = null) {
        mainHandler.postDelayed({
            val shortVideoState = detectPausedFromShortVideoHolders()
            val detected = shortVideoState ?: detectPausedFromVideoLayers() ?: fallback
            if (detected != null) {
                val source = if (shortVideoState != null) "short-holder" else "layer"
                setVideoPaused(detected, "$reason/$source")
            }
            else LogUtil.warn("video state undetermined: $reason")
        }, 60L)
    }

    private fun setVideoPaused(paused: Boolean, reason: String = "callback") {
        val changed = gVideoPaused != paused
        gVideoPaused = paused
        syncCustomControls(gCurrentActivity?.window?.decorView)
        gLastVideoStateAt = android.os.SystemClock.uptimeMillis()
        gLastVideoStateReason = reason

        if (!gMasterOn || !gCleanScreenOn || !gRestoreControlsOnPause) return
        if (paused) {
            setVideoToolbarsVisible(true)
            forcePauseRestoreControls()

            val pauseRestoreDelays = if (gNames.delayedPauseRestore || gNames.profileId == "CN-7.3.2.32") {
                longArrayOf(120L, 360L, 720L)
            } else {
                longArrayOf(120L, 360L)
            }
            for (delay in pauseRestoreDelays) mainHandler.postDelayed({
                if (pauseRestoresControls()) {
                    setVideoToolbarsVisible(true)
                    forcePauseRestoreControls()
                }
            }, delay)
            if (changed) LogUtil.info("video paused: restore controls, reason=$reason")
        } else {
            restorePauseForcedViews()
            mainHandler.post { scanAllWindows() }
            if (changed) LogUtil.info("video resumed: hide controls, reason=$reason")
        }
    }
    private fun scanTreeRestore(v: View?) {
        syncCustomControls(v)
    }

    private fun isProgressBar(v: View?): Boolean {
        if (v == null) return false
        try { val id = v.id; if (id > 0 && gProgressIdSet.contains(id)) return true } catch (_: Exception) {}
        try { if (v.javaClass.name == gNames.progressBar) return true } catch (_: Exception) {}
        return false
    }
    private fun collectAllWindows(): MutableList<View> {
        val roots = mutableListOf<View>()
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmgClass.getMethod("getInstance").invoke(null)
            val mViewsField = wmgClass.getDeclaredField("mViews")
            mViewsField.isAccessible = true
            val mViews = mViewsField.get(instance) as? ArrayList<View>
            if (mViews != null) roots.addAll(mViews)
        } catch (_: Throwable) {

        }
        try {
            val decor = gCurrentActivity?.window?.decorView
            if (decor != null && roots.none { it === decor }) roots.add(decor)
        } catch (_: Throwable) {}
        return roots
    }
    private fun scanAllWindows() {
        try {
            if (gCurrentActivity == null) return
            val decor = gCurrentActivity!!.window.decorView
            gNames.hideIdNames.forEach { resolveEntryId(it, decor) }
            gNames.progressIdNames.forEach { resolveEntryId(it, decor, gProgressIdSet, "进度条资源") }
            resolvePauseRestoreIds(decor)
            syncCustomControls(decor)
            val allRoots = collectAllWindows()
            if (allRoots.isEmpty() && decor != null) allRoots.add(decor)
            for (root in allRoots) {
                if (gMasterOn && gControlOn && !pauseRestoresControls()) scanTreeQuick(root)
                else scanTreeRestore(root)

            }
            if (gMasterOn && gRefreshOff) for (root in allRoots) scanTreeRefreshAccessory(root)
            else if (!gRefreshOff) restoreRefreshAccessories()
            syncShortVideoMasks()
            syncCnHomeFragmentMasks()
            // Apply the selective policy after native mask callbacks have updated their parents.
            syncCustomControls(decor)
        } catch (_: Exception) {}
    }

    private var gScanRunnable: Runnable? = null
    private fun startPeriodicScan() {
        stopPeriodicScan()
        gScanRunnable = object : Runnable {
            override fun run() {
                try {
                    syncCustomControls(gCurrentActivity?.window?.decorView)
                    if (pauseRestoresControls()) {
                        forcePauseRestoreControls()
                    }
                    if (gMasterOn && gRefreshOff) {
                        val allRoots3 = collectAllWindows()
                        for (root in allRoots3) scanTreeRefreshAccessory(root)
                    }
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 400)
            }
        }
        mainHandler.postDelayed(gScanRunnable!!, 400)
    }
    private fun stopPeriodicScan() {
        gScanRunnable?.let { mainHandler.removeCallbacks(it) }
        gScanRunnable = null
    }
    private fun applyToCurrent() { mainHandler.post { scanAllWindows() } }

    private fun isWindowedMode(act: Activity?): Boolean {
        if (act == null) return false
        try {
            if (Build.VERSION.SDK_INT >= 24 && act.isInMultiWindowMode) return true
            if (Build.VERSION.SDK_INT >= 26 && act.isInPictureInPictureMode) return true

            if (Build.VERSION.SDK_INT >= 30) {
                val current = act.windowManager.currentWindowMetrics.bounds
                val maximum = act.windowManager.maximumWindowMetrics.bounds
                if (maximum.width() > 0 && maximum.height() > 0) {
                    val widthReduced = current.width().toLong() * 100L < maximum.width().toLong() * 88L
                    val heightReduced = current.height().toLong() * 100L < maximum.height().toLong() * 88L
                    if (widthReduced || heightReduced) return true
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun shouldHideStatusBar(): Boolean {
        return gMasterOn && gStatusOn
    }

    private fun updateSeriesMallTopMargin(act: Activity, statusHidden: Boolean) {
        try {

            val topBarId = act.resources.getIdentifier("is7", "id", gPkg)
            if (topBarId == 0) return
            val topBar = act.findViewById<View>(topBarId) ?: return
            val params = topBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (statusHidden) {
                synchronized(gSavedTopMargins) {
                    if (!gSavedTopMargins.containsKey(topBar)) gSavedTopMargins[topBar] = params.topMargin
                }
                if (params.topMargin != 0) {
                    params.topMargin = 0
                    topBar.layoutParams = params
                    topBar.requestLayout()
                }
            } else {
                val original = synchronized(gSavedTopMargins) { gSavedTopMargins.remove(topBar) } ?: return
                if (params.topMargin != original) {
                    params.topMargin = original
                    topBar.layoutParams = params
                    topBar.requestLayout()
                }
            }
        } catch (_: Exception) {}
    }

    private fun refreshOneShortVideoHolder(
        holder: Any,
        configuration: android.content.res.Configuration,
    ) {
        try {
            holder.javaClass.getMethod(gNames.shortConfigMethod, android.content.res.Configuration::class.java)
                .invoke(holder, configuration)

            holder.javaClass.getMethod(gNames.shortLayoutResetMethod).invoke(holder)
        } catch (_: Throwable) {}
        shortVideoHolderRoot(holder)?.requestLayout()
    }

    private fun refreshShortVideoWindowLayout(act: Activity) {
        val configuration = act.resources.configuration

        for (fragment in shortSeriesFragmentSnapshot().asReversed()) {
            if (activityFromFragment(fragment) !== act) continue
            for (fieldName in gNames.seriesLayoutFields) {
                val view = findFieldValue(fragment, fieldName) as? View ?: continue
                try { view.requestLayout() } catch (_: Throwable) {}
            }
            try {
                val pager = fragment.javaClass.getMethod(gNames.seriesPagerGetter).invoke(fragment) ?: continue
                val holder = pager.javaClass.getMethod(gNames.seriesHolderGetter).invoke(pager) ?: continue
                registerShortVideoHolder(holder)
                refreshOneShortVideoHolder(holder, configuration)
            } catch (_: Throwable) {}
        }

        for ((holder, _) in shortVideoHolderSnapshot().asReversed()) {
            if (!isShortVideoHolderVisible(holder)) continue
            refreshOneShortVideoHolder(holder, configuration)
        }
    }

    private fun applyWindowedTop(act: Activity) {
        try {
            val window = act.window ?: return
            val decor = window.decorView

            fun applyOnce() {

                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                )
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                if (Build.VERSION.SDK_INT >= 28) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    window.attributes = attrs
                }

                if (Build.VERSION.SDK_INT >= 30) {

                    @Suppress("DEPRECATION")
                    decor.systemUiVisibility = decor.systemUiVisibility and
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv() and
                        View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
                    window.setDecorFitsSystemWindows(false)
                    window.statusBarColor = Color.TRANSPARENT
                    decor.windowInsetsController?.apply {
                        setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                        if (Build.VERSION.SDK_INT >= 35) {
                            setSystemBarsAppearance(
                                WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                                WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                            )
                        }
                        var topTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.captionBar()
                        if (Build.VERSION.SDK_INT >= 34) {
                            topTypes = topTypes or WindowInsets.Type.systemOverlays()
                        }
                        hide(topTypes)
                        systemBarsBehavior =
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {

                    @Suppress("DEPRECATION")
                    decor.systemUiVisibility = decor.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    window.statusBarColor = Color.BLACK
                }
                decor.requestApplyInsets()
                updateSeriesMallTopMargin(act, true)
            }

            applyOnce()

            for (delay in longArrayOf(120L, 360L)) mainHandler.postDelayed({
                if (!act.isFinishing && isWindowedMode(act) && gMasterOn && gStatusOn) {
                    applyOnce()
                    refreshShortVideoWindowLayout(act)
                }
            }, delay)
        } catch (e: Throwable) {
            LogUtil.warn("apply windowed mode failed: $e")
        }
    }

    private fun reapplyAfterWindowModeChange(
        act: Activity?,
        reason: String,
        refreshLayout: Boolean = true,
    ) {
        if (act == null || !gMasterOn) return
        for (delay in longArrayOf(0L, 180L)) mainHandler.postDelayed({
            if (act.isFinishing) return@postDelayed
            val windowed = isWindowedMode(act)
            if (gLastWindowedMode != windowed) {
                gLastWindowedMode = windowed
                LogUtil.info("window mode -> ${if (windowed) "windowed" else "fullscreen"}, reason=$reason")
            }
            if (gStatusOn) applyCleanTop(act) else showStatusBar(act)
            if (gNavBarOff) applyNavBar(act) else showNavBar(act)
            if (refreshLayout) refreshShortVideoWindowLayout(act)
        }, delay)
    }

    private fun reapplySeriesPageState(
        owner: Any?,
        reason: String,
        refreshLayout: Boolean = true,
    ) {
        val act = owner as? Activity ?: activityFromFragment(owner) ?: return
        reapplyAfterWindowModeChange(act, reason, refreshLayout)
        mainHandler.postDelayed({
            if (act.isFinishing) return@postDelayed
            if (refreshLayout) refreshShortVideoWindowLayout(act)
            if (gRestoreControlsOnPause) {
                refreshVideoPauseState("series-window:$reason")
            }
            scanAllWindows()
        }, 80L)
    }

    private fun applyCleanTop(act: Activity?) {
        if (act == null || !gMasterOn || !gStatusOn) return
        if (isWindowedMode(act)) {
            applyWindowedTop(act)
            return
        }
        try {
            val window = act.window ?: return
            val decor = window.decorView

            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                window.attributes = attrs
            }

            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            )
            window.statusBarColor = Color.TRANSPARENT

            if (Build.VERSION.SDK_INT >= 30) {
                decor.windowInsetsController?.apply {
                    hide(WindowInsets.Type.statusBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            decor.requestApplyInsets()
            updateSeriesMallTopMargin(act, true)
        } catch (_: Exception) {}
    }
    private fun showStatusBar(act: Activity?) {
        if (act == null) return
        try {
            val window = act.window ?: return
            val decor = window.decorView
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            if (Build.VERSION.SDK_INT >= 30) {

                window.setDecorFitsSystemWindows(false)
                var topTypes = WindowInsets.Type.statusBars()
                if (isWindowedMode(act)) topTypes = topTypes or WindowInsets.Type.captionBar()
                decor.windowInsetsController?.show(topTypes)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                window.attributes = attrs
            }
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility and
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv() and
                View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
            decor.requestApplyInsets()
            updateSeriesMallTopMargin(act, false)
        } catch (_: Exception) {}
    }

    private fun applyBottomEdgeToEdge(act: Activity?, hideNavigationBar: Boolean) {
        if (act == null) return
        try {
            val window = act.window ?: return
            val decor = window.decorView
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                try { window.isNavigationBarContrastEnforced = false } catch (_: Throwable) {}
            }
            if (Build.VERSION.SDK_INT >= 30) {
                decor.windowInsetsController?.apply {
                    if (hideNavigationBar) hide(WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                (if (hideNavigationBar) View.SYSTEM_UI_FLAG_HIDE_NAVIGATION else 0)
            decor.requestApplyInsets()
        } catch (e: Throwable) {
            LogUtil.warn("apply bottom edge-to-edge failed: $e")
        }
    }

    private fun extendKnownBottomContentRoot(act: Activity?, expectedGapPx: Int) {
        if (act == null || expectedGapPx <= 0) return
        try {
            val id = act.resources.getIdentifier("hsw", "id", gPkg)
            if (id == 0) return
            val content = act.findViewById<View>(id) ?: return
            val parent = content.parent as? View ?: return
            val lp = content.layoutParams ?: return
            val contentLoc = IntArray(2)
            val parentLoc = IntArray(2)
            content.getLocationOnScreen(contentLoc)
            parent.getLocationOnScreen(parentLoc)
            val contentH = if (content.height > 0) content.height else content.measuredHeight
            val parentH = if (parent.height > 0) parent.height else parent.measuredHeight
            if (contentH <= 0 || parentH <= 0) return
            val desired = parentLoc[1] + parentH - contentLoc[1]
            val gap = desired - contentH
            val minGap = (expectedGapPx * 0.55f).toInt().coerceAtLeast(1)
            val maxGap = (expectedGapPx * 1.8f).toInt().coerceAtLeast(expectedGapPx)
            if (gap in minGap..maxGap && lp.height != desired) {
                rememberBottomLayoutState(content)
                lp.height = desired
                content.layoutParams = lp
                content.requestLayout()
                (content.parent as? View)?.requestLayout()
                LogUtil.info("hsw bottom extended: ${contentH}px -> ${desired}px, gap=${gap}px")
                LogUtil.incr("bottomHswExtend")
            }
        } catch (e: Throwable) {
            LogUtil.warn("extend hsw bottom failed: $e")
        }
    }

    private fun applyNavBar(act: Activity?) {
        if (act == null || !gMasterOn || !gNavBarOff) return
        applyBottomEdgeToEdge(act, true)
    }
    private fun showNavBar(act: Activity?) {
        if (act == null) return
        try {
            val decor = act.window.decorView
            if (Build.VERSION.SDK_INT >= 30) decor.windowInsetsController?.show(WindowInsets.Type.navigationBars())
            else @Suppress("DEPRECATION") decor.systemUiVisibility = decor.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
        } catch (_: Exception) {}
    }

    private fun resolutionRank(resolution: Any?): Int {
        if (resolution == null) return Int.MIN_VALUE
        val enumName = try { (resolution as? Enum<*>)?.name ?: "" } catch (_: Throwable) { "" }
        val text = try { (enumName + " " + resolution.toString()).lowercase() } catch (_: Throwable) { enumName.lowercase() }
        if ("auto" in text || "undefine" in text) return Int.MIN_VALUE

        return when {
            "eightk" in text || "8k" in text -> 8000
            "fourk" in text || "4k" in text || "2160" in text -> 4000
            "twok" in text || "2k" in text || "1440" in text -> 2000
            "extremelyhighplus" in text || "1080p+" in text -> 1081
            "extremelyhigh" in text || "1080" in text -> 1080
            "superhigh" in text || "720" in text -> 720
            "h_high" in text || "540" in text -> 540

            enumName.equals("High", true) || " 480p" in " $text" -> 480
            "l_standard" in text || "240" in text -> 240
            "standard" in text || "360" in text -> 360
            else -> {

                try {
                    val m = resolution.javaClass.methods.firstOrNull { it.name == "getIndex" && it.parameterCount == 0 }
                    ((m?.invoke(resolution) as? Number)?.toInt() ?: 0) - 10000
                } catch (_: Throwable) { -10000 }
            }
        }
    }

    private fun supportedResolutions(model: Any?): List<Any> {
        if (model == null) return emptyList()
        return try {
            val getter = model.javaClass.methods.firstOrNull {
                it.name == "getSupportResolutions" && it.parameterCount == 0
            } ?: return emptyList()
            val array = getter.invoke(model) ?: return emptyList()
            (0 until java.lang.reflect.Array.getLength(array)).mapNotNull {
                java.lang.reflect.Array.get(array, it)
            }
        } catch (e: Throwable) {
            LogUtil.warn("默认画质：读取支持清晰度失败: $e")
            emptyList()
        }
    }

    private fun selectedResolution(supported: List<Any>): Any? =
        (controlSettings?.defaultQuality() ?: DefaultQuality.HIGHEST).select(supported, ::resolutionRank)

    private fun applyResolutionToEngine(engine: Any, resolution: Any) {
        try {
            val method = engine.javaClass.methods.firstOrNull {
                it.name == "configResolution" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isInstance(resolution)
            } ?: return
            method.invoke(engine, resolution)
            LogUtil.incr("defaultQualityApply")
        } catch (e: Throwable) { LogUtil.warn("默认画质：应用失败: $e") }
    }

    private fun applyResolutionViaController(controller: Any?, resolution: Any?): Boolean {
        if (controller == null || resolution == null || gNames.resolutionApplyMethod.isBlank()) return false
        return try {
            var clazz: Class<*>? = controller.javaClass
            var target: java.lang.reflect.Method? = null
            while (clazz != null && target == null) {
                target = clazz.declaredMethods.firstOrNull {
                    it.name == gNames.resolutionApplyMethod && it.parameterCount == 1 &&
                        it.parameterTypes[0].isInstance(resolution)
                }?.apply { isAccessible = true }
                clazz = clazz.superclass
            }
            if (target == null) return false
            target.invoke(controller, resolution)
            LogUtil.info("默认画质：原生控制器 ${gNames.resolutionApplyMethod}($resolution)")
            LogUtil.incr("defaultQualityNativeApply")
            true
        } catch (e: Throwable) {
            LogUtil.warn("默认画质：原生控制器应用失败: $e")
            false
        }
    }

    private fun applyDefaultQualityToKnownPlayers() {
        if (!gMasterOn || !gDefaultQualityOn) return
        val controllers = synchronized(gControllerResolutions) { gControllerResolutions.entries.map { it.key to it.value } }
        for ((controller, supported) in controllers) applyResolutionViaController(controller, selectedResolution(supported))
        val engines = synchronized(gEngineResolutions) { gEngineResolutions.entries.map { it.key to it.value } }
        for ((engine, supported) in engines) selectedResolution(supported)?.let { applyResolutionToEngine(engine, it) }
    }

    private fun createNotification(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!gNotificationMenuOn) {
                nm.cancel(9999)
                return
            }
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("guoplus_toggle", "果+", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
            val flag = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            fun pi(code: Int, action: String) = PendingIntent.getBroadcast(ctx, code, Intent(action).apply { setPackage(gPkg); addFlags(Intent.FLAG_RECEIVER_FOREGROUND) }, flag)
            val n = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, "guoplus_toggle") else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            n.setContentTitle("果+"); n.setContentText(if (gMasterOn) "已启用" else "已停用"); n.setSmallIcon(android.R.drawable.ic_menu_view); n.setOngoing(true)
            n.setContentIntent(pi(9999, "$gPkg.GuoPlus.TOGGLE"))
            n.addAction(android.R.drawable.ic_menu_view, if (gMasterOn) "隐藏" else "显示", pi(10001, "$gPkg.GuoPlus.TOGGLE"))
            n.addAction(android.R.drawable.ic_menu_edit, "面板", pi(10002, "$gPkg.GuoPlus.OPEN_PANEL"))
            n.addAction(android.R.drawable.ic_menu_close_clear_cancel, "重启", pi(10003, "$gPkg.GuoPlus.RESTART"))
            nm.notify(9999, n.build())
        } catch (e: Exception) { LogUtil.error("通知", e) }
    }
    private fun ensureReceiver(ctx: Context) {
        if (gReceiverRegistered) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try { when (intent.action) { "$gPkg.GuoPlus.TOGGLE" -> doToggle(ctx); "$gPkg.GuoPlus.OPEN_PANEL" -> openPanel(); "$gPkg.GuoPlus.RESTART" -> restartApp(ctx) } } catch (e: Exception) { LogUtil.error("receiver", e) }
                }
            }
            val filter = IntentFilter("$gPkg.GuoPlus.TOGGLE").apply { addAction("$gPkg.GuoPlus.OPEN_PANEL"); addAction("$gPkg.GuoPlus.RESTART") }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
            gReceiverRegistered = true
        } catch (e: Exception) { LogUtil.error("receiver", e) }
    }
    private fun doToggle(ctx: Context) {
        gMasterOn = !gMasterOn
        savePref("master_on", gMasterOn)
        if (!gMasterOn) {
            restoreAllSavedViews()
            restoreShortVideoNativeControls()
            setVideoToolbarsVisible(true)
        }
        createNotification(ctx)
        applyToCurrent()
        LogUtil.info("总开关 → $gMasterOn")
    }
    private fun restartApp(ctx: Context) { try { val i = ctx.packageManager.getLaunchIntentForPackage(gPkg); if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK); ctx.startActivity(i) }; mainHandler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1000) } catch (_: Exception) {} }

    private data class PanelPalette(
        val page: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val text: Int,
        val textSecondary: Int,
        val accent: Int,
        val accentSoft: Int,
        val divider: Int,
        val switchOffThumb: Int,
        val switchOffTrack: Int,
        val switchOnTrack: Int,
    )

    private fun safeRgb(r: Int, g: Int, b: Int): Int = Color.rgb(r, g, b)
    private fun safeArgb(a: Int, r: Int, g: Int, b: Int): Int = Color.argb(a, r, g, b)
    private fun dp(ctx: Context, value: Float): Int = (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun panelPalette(ctx: Context): PanelPalette {
        val palette = com.hmodule.ui.MiuixUi.palette(ctx)
        gIsNight = com.hmodule.ui.MiuixUi.isDark(ctx)
        return PanelPalette(palette.page, palette.surface, palette.surface, palette.text,
            palette.secondary, palette.accent, palette.soft, palette.divider,
            Color.WHITE, palette.off, 0xff347445.toInt())
    }

    private fun roundedBg(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 0f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0f) setStroke(dp(ctx, strokeDp), strokeColor)
        }
    }

    private fun styleSwitch(sw: Switch, p: PanelPalette) {
        com.hmodule.ui.MiuixUi.styleSwitch(sw)
    }

    private fun sectionTitle(ctx: Context, title: String, p: PanelPalette): TextView = TextView(ctx).apply {
        text = title
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(p.accent)
        isAllCaps = false
        setPadding(dp(ctx, 12f), dp(ctx, 24f), dp(ctx, 12f), dp(ctx, 10f))
    }

    private fun makePanelRow(
        ctx: Context,
        title: String,
        description: String,
        checked: Boolean,
        p: PanelPalette,
        emphasis: Boolean = false,
        hasSubmenu: Boolean = false,
    ): Pair<LinearLayout, Switch> {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 17f), dp(ctx, 16f), dp(ctx, 17f))
            background = com.hmodule.ui.MiuixUi.ripple(ctx)
            minimumHeight = dp(ctx, 64f)
        }
        val labels = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.addView(if (hasSubmenu) com.hmodule.ui.MiuixUi.settingsTitle(ctx, title, medium = emphasis) else TextView(ctx).apply {
            text = title
            textSize = 16f
            setTypeface(Typeface.DEFAULT, if (emphasis) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(p.text)
            includeFontPadding = false
        })
        labels.addView(TextView(ctx).apply {
            text = description
            textSize = 12f
            setTextColor(p.textSecondary)
            includeFontPadding = false
            setPadding(0, dp(ctx, 4f), dp(ctx, 6f), 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply {
            isChecked = checked
            setShowText(false)
            setPadding(dp(ctx, 8f), 0, 0, 0)
        }
        styleSwitch(sw, p)
        row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row to sw
    }

    private fun showDefaultSpeedEditor(act: Activity, p: PanelPalette, onSelected: (Float) -> Unit) {
        try {
            lateinit var dialog: Dialog
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 18f), dp(act, 18f), dp(act, 18f), dp(act, 16f))
                background = roundedBg(act, p.page, 26f, p.divider, 1f)
            }
            root.addView(TextView(act).apply {
                text = "选择后立即生效，并记住为以后播放的默认速度"
                textSize = 11.5f
                setTextColor(p.textSecondary)
                includeFontPadding = false
                setPadding(0, dp(act, 6f), 0, dp(act, 10f))
            })

            DEFAULT_SPEED_OPTIONS.forEach { speed ->
                val selected = kotlin.math.abs(speed - gDefaultSpeed) < 0.001f
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(act, 15f), dp(act, 12f), dp(act, 15f), dp(act, 12f))
                    background = roundedBg(act, if (selected) p.accentSoft else p.surfaceAlt, 16f)
                    minimumHeight = dp(act, 50f)
                }
                row.addView(TextView(act).apply {
                    text = formatSpeed(speed)
                    textSize = 15f
                    setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (selected) p.accent else p.text)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(act).apply {
                    text = if (selected) "✓" else ""
                    textSize = 18f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(p.accent)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(dp(act, 30f), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.setOnClickListener {
                    saveDefaultSpeedValue(speed)

                    if (!gDefaultSpeedOn) {
                        gDefaultSpeedOn = true
                        savePref(DEFAULT_SPEED_PREF, true)
                    }
                    onSelected(speed)
                    dialog.dismiss()
                    scheduleDefaultSpeedApply("menu-select")
                    Toast.makeText(act, "默认倍速已设为 ${formatSpeed(speed)}", Toast.LENGTH_SHORT).show()
                }
                root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(act, 7f)
                })
            }

            dialog = com.hmodule.ui.MiuixUi.showPage(act, "默认倍速", ScrollView(act).apply { addView(root) })
        } catch (e: Throwable) {
            LogUtil.error("默认倍速编辑", e)
        }
    }

    private fun downloadLimitSummary(): String {
        val (episode, series, total) = currentDownloadLimitValues()
        return "单日集数 $episode  ·  单日剧数 $series  ·  总缓存 $total"
    }

    private fun showDownloadLimitEditor(act: Activity, p: PanelPalette, onSaved: () -> Unit) {
        try {
            val (episodeNow, seriesNow, totalNow) = currentDownloadLimitValues()
            lateinit var dialog: Dialog

            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 18f), dp(act, 18f), dp(act, 18f), dp(act, 16f))
                background = roundedBg(act, p.page, 26f, p.divider, 1f)
            }
            root.addView(TextView(act).apply {
                text = "默认 99999。测试时可以改成 2、3 等小数值。"
                textSize = 11.5f
                setTextColor(p.textSecondary)
                includeFontPadding = false
                setPadding(0, dp(act, 6f), 0, dp(act, 12f))
            })

            fun field(label: String, value: Int): EditText {
                root.addView(TextView(act).apply {
                    text = label
                    textSize = 12f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(p.textSecondary)
                    includeFontPadding = false
                    setPadding(dp(act, 2f), dp(act, 7f), 0, dp(act, 5f))
                })
                return EditText(act).apply {
                    setText(value.toString())
                    textSize = 15f
                    setTextColor(p.text)
                    setHintTextColor(p.textSecondary)
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    setSelectAllOnFocus(true)
                    setPadding(dp(act, 13f), 0, dp(act, 13f), 0)
                    background = roundedBg(act, p.surfaceAlt, 12f, p.divider, 1f)
                    root.addView(this, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(act, 48f),
                    ))
                }
            }

            val episodeField = field("单日最大下载集数", episodeNow)
            val seriesField = field("单日最大下载剧数", seriesNow)
            val totalField = field("总缓存剧数上限", totalNow)

            root.addView(TextView(act).apply {
                text = "修改数值后需要强停红果并重新打开，确保主进程和 :downloader 的缓存配置全部刷新。"
                textSize = 10.8f
                setTextColor(p.accent)
                includeFontPadding = false
                setPadding(dp(act, 2f), dp(act, 12f), dp(act, 2f), dp(act, 4f))
            })

            val buttons = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(act, 12f), 0, 0)
            }
            val cancel = TextView(act).apply {
                text = "取消"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(p.text)
                background = roundedBg(act, p.surfaceAlt, 12f)
                setOnClickListener { dialog.dismiss() }
            }
            val save = TextView(act).apply {
                text = "保存"
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = roundedBg(act, p.accent, 12f)
                setOnClickListener {
                    val episode = episodeField.text?.toString()?.trim()?.toIntOrNull()
                    val series = seriesField.text?.toString()?.trim()?.toIntOrNull()
                    val total = totalField.text?.toString()?.trim()?.toIntOrNull()
                    if (episode == null || series == null || total == null ||
                        episode !in 1..999_999_999 || series !in 1..999_999_999 || total !in 1..999_999_999
                    ) {
                        Toast.makeText(act, "请输入 1 ~ 999999999 的整数", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    saveDownloadLimitValues(episode, series, total)
                    LogUtil.info("download limit custom values saved: episode=$episode series=$series total=$total")
                    onSaved()
                    dialog.dismiss()
                    Toast.makeText(act, "已保存，需要强停红果后重新打开生效", Toast.LENGTH_LONG).show()
                }
            }
            buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(act, 44f), 1f).apply { rightMargin = dp(act, 6f) })
            buttons.addView(save, LinearLayout.LayoutParams(0, dp(act, 44f), 1f).apply { leftMargin = dp(act, 6f) })
            root.addView(buttons)

            dialog = com.hmodule.ui.MiuixUi.showPage(act, "自定义下载限制", ScrollView(act).apply { addView(root) })
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        } catch (e: Throwable) {
            LogUtil.error("下载限制编辑", e)
        }
    }

    private fun installPanelWatermark(target: View, ctx: Context, color: Int) {
        try {
            val text = "免费模块倒卖死全家"
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {

                this.color = Color.argb(24, Color.red(color), Color.green(color), Color.blue(color))
                textSize = 12.5f * ctx.resources.displayMetrics.scaledDensity
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val gapX = dp(ctx, 54f).toFloat()
            val gapY = dp(ctx, 72f).toFloat()
            val drawable = object : android.graphics.drawable.Drawable() {
                override fun draw(canvas: android.graphics.Canvas) {
                    val b = bounds
                    if (b.width() <= 0 || b.height() <= 0) return
                    val save = canvas.save()
                    canvas.rotate(-24f, b.exactCenterX(), b.exactCenterY())
                    val textW = paint.measureText(text)
                    val stepX = textW + gapX
                    var row = 0
                    var y = -b.height().toFloat()
                    val maxY = b.height() * 2f
                    while (y < maxY) {
                        var x = -b.width().toFloat() - if (row % 2 == 0) 0f else stepX / 2f
                        val maxX = b.width() * 2f
                        while (x < maxX) {
                            canvas.drawText(text, x, y, paint)
                            x += stepX
                        }
                        y += gapY
                        row++
                    }
                    canvas.restoreToCount(save)
                }

                override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255) }
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
                @Suppress("DEPRECATION")
                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            }
            target.overlay.add(drawable)
            target.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                drawable.setBounds(0, 0, v.width, v.height)
                drawable.invalidateSelf()
            }
            target.post {
                drawable.setBounds(0, 0, target.width, target.height)
                drawable.invalidateSelf()
            }
        } catch (e: Throwable) {
            LogUtil.error("面板水印", e)
        }
    }

    private fun showPanel(ctx: Context?) {
        val act = (ctx as? Activity) ?: gCurrentActivity ?: return
        try {
            act.startActivity(android.content.Intent().apply {
                setClassName("com.hmodule", "com.hmodule.MainActivity")
                putExtra(com.hmodule.MainActivity.EXTRA_PAGE, com.hmodule.MainActivity.PAGE_FEATURES)
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (e: Exception) { LogUtil.error("面板", e) }
    }
    private fun openPanel() { mainHandler.post { showPanel(gCurrentActivity) } }

    private fun findNativeSettingsContainer(root: View?): ViewGroup? {
        if (root !is ViewGroup) return null
        var best: ViewGroup? = null
        var bestScore = Int.MIN_VALUE
        fun walk(group: ViewGroup, depth: Int) {
            if (depth > 9) return
            val name = group.javaClass.name
            val isRecycler = name.contains("RecyclerView") || name.contains("ListView")
            val isVerticalLinear = group is LinearLayout && group.orientation == LinearLayout.VERTICAL
            if (!isRecycler && isVerticalLinear && group.childCount in 2..40) {
                var textCount = 0
                var clickableCount = 0
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child is TextView) textCount++
                    if (child.isClickable) clickableCount++
                    if (child is ViewGroup) {
                        for (j in 0 until child.childCount) if (child.getChildAt(j) is TextView) { textCount++; break }
                    }
                }
                val widthOk = group.width <= 0 || group.width >= actWidthPx(group.context) * 0.72f
                val score = textCount * 3 + clickableCount * 2 + group.childCount - depth * 2 + if (widthOk) 8 else 0
                if (textCount >= 2 && score > bestScore) {
                    best = group
                    bestScore = score
                }
            }
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is ViewGroup && !child.javaClass.name.contains("RecyclerView")) walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return best
    }

    private fun actWidthPx(ctx: Context): Int = try {
        ctx.resources.displayMetrics.widthPixels
    } catch (_: Throwable) { 1080 }

    private fun setFieldValue(instance: Any, fieldName: String, value: Any?): Boolean {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            try {
                clazz.getDeclaredField(fieldName).apply { isAccessible = true }.set(instance, value)
                return true
            } catch (_: Throwable) {}
            clazz = clazz.superclass
        }
        return false
    }

    private fun callNoArgMethod(instance: Any, methodName: String): Any? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            try {
                val method = clazz.declaredMethods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                if (method != null) {
                    method.isAccessible = true
                    return method.invoke(instance)
                }
            } catch (_: Throwable) {}
            clazz = clazz.superclass
        }
        return null
    }

    private data class NativeSettingsSpec(
        val listMethods: Set<String>,
        val itemClass: String,
        val clickClass: String,
        val checkedClass: String?,
        val style: String,
    )

    private fun nativeSettingsSpec(): NativeSettingsSpec = when {
        !featureEnabled("settings") || gNames.profileId.startsWith("COMPAT-") || gNames.profileId == "UNSUPPORTED" -> NativeSettingsSpec(emptySet(), "", "", null, "")
        gPkg == TargetNames.OVERSEA_PACKAGE -> NativeSettingsSpec(
            listMethods = setOf("e1", "f1"),
            itemClass = "vr5.e",
            clickClass = "vr5.b",
            checkedClass = null,
            style = "oversea-arrow",
        )
        gNames.settingsItemClass.isNotBlank() -> NativeSettingsSpec(
            listMethods = gNames.settingsListMethods.toSet(), itemClass = gNames.settingsItemClass,
            clickClass = gNames.settingsClickClass, checkedClass = null, style = gNames.settingsStyle,
        )
        gNames.profileId == "CN-7.3.3.18" -> NativeSettingsSpec(
            listMethods = setOf("X0", "Y0"),
            itemClass = "mz5.e",
            clickClass = "mz5.b",
            checkedClass = "mz5.c",
            style = "atomic-arrow",
        )
        gNames.profileId == "CN-7.3.2.32" -> NativeSettingsSpec(
            listMethods = setOf("c1", "d1"),
            itemClass = "qv5.e",
            clickClass = "qv5.b",
            checkedClass = "qv5.c",
            style = "grouped-atomic-arrow",
        )
        else -> NativeSettingsSpec(

            listMethods = setOf("c1", "d1"),
            itemClass = "pv5.e",
            clickClass = "pv5.b",
            checkedClass = "pv5.c",
            style = "grouped-atomic-arrow",
        )
    }

    private fun injectNativeSettingsList(
        listObj: Any?,
        classLoader: ClassLoader,
        spec: NativeSettingsSpec = nativeSettingsSpec(),
    ): Boolean {
        val list = listObj as? MutableList<Any?> ?: return false
        try {
            for (item in list) {
                if (item == null) continue
                val title = findFieldValue(item, "e")?.toString()
                if (title == "模块设置") return true
            }

            val itemClass = Class.forName(spec.itemClass, false, classLoader)
            val ctor = itemClass.declaredConstructors.firstOrNull { it.parameterCount == 0 } ?: return false
            ctor.isAccessible = true
            val item = ctor.newInstance()
            if (!setFieldValue(item, "e", "模块设置")) return false
            setFieldValue(item, "f", null)

            when (spec.style) {
                "oversea-arrow" -> {

                    setFieldValue(item, "i", true)
                    setFieldValue(item, "k", true)
                }
                "grouped-atomic-arrow" -> {

                    setFieldValue(item, "i", true)
                    setFieldValue(item, "a", true)
                    setFieldValue(item, "b", true)
                    setFieldValue(item, "c", true)
                    setFieldValue(item, "o", null)
                }
                else -> {

                    setFieldValue(item, "i", true)
                    setFieldValue(item, "o", null)
                }
            }

            list.add(0, item)
            LogUtil.info("原生设置数据项已注入: ${spec.itemClass} / index=0 / profile=${gNames.profileId}")
            return true
        } catch (e: Throwable) {
            LogUtil.warn("原生设置数据项注入失败(${spec.itemClass}): $e")
            return false
        }
    }

    private fun boundSettingsTitle(clickHost: Any): String? {
        return try {
            val holder = findFieldValue(clickHost, "a") ?: return null
            val data = callNoArgMethod(holder, "getBoundData") ?: return null
            findFieldValue(data, "e")?.toString()
        } catch (_: Throwable) { null }
    }

    private fun nearestSettingsTextColor(root: View?, fallback: Int): Int {
        if (root == null) return fallback
        if (root is TextView) {
            val text = try { root.text?.toString().orEmpty() } catch (_: Throwable) { "" }
            if (text.isNotBlank() && root.textSize > 12f) return try { root.currentTextColor } catch (_: Throwable) { fallback }
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            val c = nearestSettingsTextColor(root.getChildAt(i), fallback)
            if (c != fallback) return c
        }
        return fallback
    }

    private fun makeNativeSettingsEntry(act: Activity, parent: ViewGroup): View {
        val p = panelPalette(act)
        val textColor = nearestSettingsTextColor(parent, p.text)
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(act, 18f), 0, dp(act, 16f), 0)
            minimumHeight = dp(act, 56f)
            tag = gGuoPlusBtnTag
            isClickable = true
            isFocusable = true
            try {
                val tv = android.util.TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0) {
                    setBackgroundResource(tv.resourceId)
                } else {
                    background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                }
            } catch (_: Throwable) {
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            }
            addView(TextView(act).apply {
                text = "模块设置"
                textSize = 15f
                setTextColor(textColor)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(TextView(act).apply {
                text = "›"
                textSize = 25f
                setTextColor(p.textSecondary)
                includeFontPadding = false
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(act, 30f), ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { showPanel(gSettingsActivity ?: gCurrentActivity) }
        }
    }

    private fun containsGuoPlusEntry(root: View?): Boolean {
        if (root == null) return false
        if (root.tag == gGuoPlusBtnTag) return true
        if (root is TextView) {
            val text = try { root.text?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            if (text == "模块设置") return true
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) if (containsGuoPlusEntry(root.getChildAt(i))) return true
        return false
    }

    private fun isLikelySettingsActivity(act: Activity?): Boolean {
        if (act == null) return false
        val name = try { act.javaClass.name } catch (_: Throwable) { return false }
        if (name == "com.dragon.read.component.biz.impl.mine.settings.SettingsActivity" ||
            name == "com.dragon.read.component.biz.impl.mine.settings.KmpSettingsActivity") return true
        val lower = name.lowercase()
        return lower.startsWith("com.dragon.read") && lower.contains(".mine.") &&
            (lower.endsWith("settingsactivity") || lower.endsWith("settingactivity"))
    }

    private fun installSettingsFooterFallback(act: Activity, content: ViewGroup): Boolean {
        if (containsGuoPlusEntry(content)) return true
        if (content.childCount <= 0) return false
        try {

            val oldChildren = ArrayList<Pair<View, ViewGroup.LayoutParams?>>(content.childCount)
            for (i in 0 until content.childCount) {
                val child = content.getChildAt(i)
                oldChildren.add(child to child.layoutParams)
            }
            content.removeAllViews()

            val wrapper = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                tag = gGuoPlusSettingsWrapperTag
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val stage = FrameLayout(act)
            wrapper.addView(stage, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            for ((child, oldLp) in oldChildren) {
                val lp = if (oldLp is FrameLayout.LayoutParams) oldLp else FrameLayout.LayoutParams(
                    oldLp?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
                    oldLp?.height ?: ViewGroup.LayoutParams.MATCH_PARENT,
                )
                stage.addView(child, lp)
            }

            val entry = makeNativeSettingsEntry(act, wrapper)

            val divider = View(act).apply {
                background = android.graphics.drawable.ColorDrawable(panelPalette(act).divider)
            }
            wrapper.addView(divider, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1f)
            ))
            wrapper.addView(entry, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 56f)
            ))
            content.addView(wrapper, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            LogUtil.info("设置页入口已使用固定布局 footer 兜底: children=${oldChildren.size}")
            return true
        } catch (e: Throwable) {
            LogUtil.error("设置页 footer 兜底", e)
            return false
        }
    }

    private fun injectSettingsButton(act: Activity, allowFallback: Boolean = true) {
        try {
            if (act.isFinishing || !isLikelySettingsActivity(act)) return
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (containsGuoPlusEntry(content)) return

            val nativeContainer = findNativeSettingsContainer(content)
            if (nativeContainer != null) {
                val entry = makeNativeSettingsEntry(act, nativeContainer)
                nativeContainer.addView(entry, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 56f),
                ))
                LogUtil.info("设置页入口已注入原生容器: ${nativeContainer.javaClass.name}")
                return
            }

            if (allowFallback) installSettingsFooterFallback(act, content)
        } catch (e: Exception) { LogUtil.error("设置页入口", e) }
    }

    private fun scheduleSettingsEntryInjection(act: Activity?) {
        if (!isLikelySettingsActivity(act)) return
        val a = act ?: return
        gSettingsActivity = a

        val attempts = arrayOf(
            150L to false,
            550L to false,
            1400L to false,
            2800L to true,
            4600L to true,
        )
        for ((delay, allowFallback) in attempts) {
            mainHandler.postDelayed({
                if (!a.isFinishing && isLikelySettingsActivity(a)) injectSettingsButton(a, allowFallback)
            }, delay)
        }
    }

    private fun pollUpdatePrompt(a: Activity, attempt: Int) {
        if (a.isFinishing || attempt > 10) return
        mainHandler.postDelayed({
            try {
                if (UpdateChecker.hasUpdate || UpdateChecker.lastError != null) {
                    UpdateChecker.showUpdateDialogIfNeeded(a)
                } else if (!a.isFinishing) {
                    pollUpdatePrompt(a, attempt + 1)
                }
            } catch (_: Exception) {}
        }, 1000)
    }

    fun showUpdateDialogIfAvailable() {
        val a = gCurrentActivity ?: return
        if (a.isFinishing) return
        mainHandler.post { UpdateChecker.showUpdateDialogIfNeeded(a) }
    }

    private fun detectTargetPackageVersion(pkg: String): Pair<String?, Long> {
        try {
            val app = try {
                Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null) as? Context
            } catch (_: Throwable) {
                try { Class.forName("android.app.AppGlobals").getDeclaredMethod("getInitialApplication").invoke(null) as? Context } catch (_: Throwable) { null }
            }
            if (app != null) {
                @Suppress("DEPRECATION")
                val pi = app.packageManager.getPackageInfo(pkg, 0)
                @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                return pi.versionName to code
            }
        } catch (e: Throwable) {
            LogUtil.warn("读取目标版本失败，改用类指纹: $e")
        }
        return null to -1L
    }

    fun installBusinessHooks(module: MainHook, classLoader: ClassLoader, pkg: String, targetInfo: android.content.pm.ApplicationInfo? = null, checkOnline: Boolean = false) {
        LogUtil.info("── installBusinessHooks ── pkg=$pkg")
        gTargetIdSet.clear()
        gProgressIdSet.clear()
        gPauseRestoreIdSet.clear()
        gPkg = pkg

        try {
            val processCtx = try {
                Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null) as? Context
            } catch (_: Throwable) {
                try { Class.forName("android.app.AppGlobals").getDeclaredMethod("getInitialApplication").invoke(null) as? Context } catch (_: Throwable) { null }
            }
            if (processCtx != null) {
                appCtx = processCtx.applicationContext
                initPrefs(processCtx.applicationContext)
            }
        } catch (e: Throwable) {
            LogUtil.warn("共享配置初始化失败，将在 Activity 创建时重试: ${e.javaClass.simpleName}: ${e.message}")
        }
        val earlyContext = try {
            val thread = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentActivityThread").invoke(null)
            thread.javaClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
        } catch (_: Throwable) { null }
        @Suppress("DEPRECATION")
        val archive = targetInfo?.sourceDir?.let { earlyContext?.packageManager?.getPackageArchiveInfo(it, 0) }
        val detected = if (archive != null) archive.versionName to archive.longVersionCode else detectTargetPackageVersion(pkg)
        gTargetVersionName = detected.first ?: "未知"
        gTargetVersionCode = detected.second

        val adaptationContext = try { earlyContext?.createPackageContext(pkg, 0) } catch (_: Throwable) { null }
        val cacheDir = try { adaptationContext?.filesDir?.let { java.io.File(it, "guoplus-adaptation") } } catch (_: Throwable) { null }
        com.hmodule.adaptation.AdaptationStore.loadDirectory(cacheDir)
        val selectedProfile = com.hmodule.adaptation.AdaptationStore.select(pkg, gTargetVersionName, gTargetVersionCode, cacheDir)
        val hostResources = try { targetInfo?.let { earlyContext?.packageManager?.getResourcesForApplication(it) } } catch (_: Throwable) { null }
        gNames = selectedProfile?.let {
            com.hmodule.adaptation.FeatureProfiles.resolve(it, classLoader) { name ->
                hostResources?.getIdentifier(name, "id", pkg)?.let { id -> id != 0 } == true
            }.names
        }
            ?: TargetNames.namesFor(pkg, detected.first, classLoader, gTargetVersionCode)
        if (gPrefs != null) reloadPreferences()
        validatedMembers = selectedProfile?.features?.filter { featureEnabled(it.id) }?.flatMap { feature ->
            feature.delegates.filter { it.kind == "method" }.map { com.hmodule.adaptation.DexMember.parse(it.kind, it.descriptor) }
        }.orEmpty()
        nativePlaybackDrawer.configure(gNames)
        if (checkOnline) com.hmodule.adaptation.RemoteAdaptation.refreshHost(cacheDir, pkg) { profile ->
            val response = adaptationContext?.contentResolver?.call(com.hmodule.config.ModuleConfig.URI, "cache_adaptation", null,
                android.os.Bundle().apply { putString("json", profile.raw) })
            check(response?.getBoolean("ok") == true) { "模块适配缓存服务不可用" }
        }

        if (gNames.useLegacySeedIds) seedIds.forEach { gTargetIdSet.add(it) }
        gNames.staticHideIds.forEach { gTargetIdSet.add(it) }
        gNames.staticProgressIds.forEach { gProgressIdSet.add(it) }
        gNames.pauseRestoreIds.forEach { gPauseRestoreIdSet.add(it) }
        LogUtil.info("目标兼容配置=${gNames.profileId} | detectedVersion=${gTargetVersionName}(${gTargetVersionCode}) | shortHolder=${gNames.shortHolder} | holderBaseS1=${gNames.holderBaseS1} | toolbarBase=${gNames.toolbarBase} | kmpVipModel=${gNames.kmpVipModel}")
        LogUtil.info("资源兼容：hideIds=${gTargetIdSet.joinToString { "0x%08X".format(it) }} | progressIds=${gProgressIdSet.joinToString { "0x%08X".format(it) }} | pauseIds=${gPauseRestoreIdSet.joinToString { "0x%08X".format(it) }}")

        try {
            if (!UpdateChecker.checking && UpdateChecker.latestVersion == null) {
                LogUtil.info("作用域启动：自动触发更新检测")
                val ver = BuildConfig.VERSION_NAME.substringBefore(' ').substringBefore('(')
                UpdateChecker.checkUpdate(ver) { latest ->
                    if (latest != null) {
                        LogUtil.info("作用域启动发现新版本 $latest")
                        showUpdateDialogIfAvailable()
                    }
                }
            }
        } catch (e: Exception) { LogUtil.warn("作用域更新检测启动失败: $e") }

        fun ham(clazz: Class<*>, methodName: String, hookId: String, block: (XposedInterface.Chain) -> Any?) {
            val signatures = validatedMembers.filter { it.owner == com.hmodule.adaptation.DexMember.objectType(clazz.name) && it.name == methodName }
            clazz.declaredMethods.filter { method -> method.name == methodName && (signatures.isEmpty() || signatures.any {
                it.parameters == method.parameterTypes.map(com.hmodule.adaptation.DexMember::typeOf) && it.type == com.hmodule.adaptation.DexMember.typeOf(method.returnType)
            }) }.forEachIndexed { i, m -> try { module.hook(m).setId("${hookId}_$i").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> block(chain) }) } catch (_: Exception) {} }
        }
        fun hac(clazz: Class<*>, hookId: String, block: (XposedInterface.Chain) -> Any?) {
            clazz.declaredConstructors.forEachIndexed { i, c -> try { module.hook(c).setId("${hookId}_$i").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> block(chain) }) } catch (_: Exception) {} }
        }

        try {
            val agencyClass = Class.forName(gNames.rightViewAgency, false, classLoader)
            hac(agencyClass, "rightViewAgencyCtor") { chain ->
                val result = chain.proceed()
                registerRightViewAgency(chain.thisObject)
                result
            }

            ham(agencyClass, gNames.rightViewAgencyEventMethod, "rightViewAgencyEvent") { chain ->
                registerRightViewAgency(chain.thisObject)
                chain.proceed()
            }
            LogUtil.info("  ✓ 评论区 Agency ${gNames.rightViewAgency}.${gNames.rightViewAgencyEventMethod}")
        } catch (e: Throwable) {
            LogUtil.warn("  评论区 Agency Hook 失败: $e")
        }

        var doubleTapHookCount = 0
        for ((handlerIndex, handlerName) in gNames.doubleTapHandlers.withIndex()) {
            try {
                val doubleTapClass = Class.forName(handlerName, false, classLoader)
                val methods = doubleTapClass.declaredMethods.filter {
                    it.name == "onDoubleTap" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == android.view.MotionEvent::class.java
                }
                for ((methodIndex, method) in methods.withIndex()) {
                    module.hook(method).setId("doubleTapComment_${handlerIndex}_${methodIndex}")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            if (!gMasterOn || !gDoubleTapCommentOn) return@Hooker chain.proceed()
                            val now = android.os.SystemClock.uptimeMillis()

                            gSuppressDoubleTapLikeUntil = maxOf(gSuppressDoubleTapLikeUntil, now + 1500L)

                            if (now - gLastDoubleTapCommentAt > 350L) {
                                gLastDoubleTapCommentAt = now
                                val opened = openCurrentCommentFromDoubleTap()
                                LogUtil.incr(if (opened) "doubleTapOpenCommentOK" else "doubleTapOpenCommentNoAgency")
                            } else {
                                LogUtil.incr("doubleTapDuplicateConsumed")
                            }

                            LogUtil.incr("doubleTapLikeBlocked")
                            true
                        })
                    doubleTapHookCount++
                    LogUtil.info("  ✓ 双击评论 $handlerName.onDoubleTap")
                }
            } catch (e: Throwable) {
                LogUtil.warn("  双击处理器 $handlerName Hook 失败: $e")
            }
        }
        if (doubleTapHookCount == 0) LogUtil.warn("  双击评论：当前版本没有安装到任何 onDoubleTap Hook")

        if (gNames.doubleTapLikeView.isNotBlank()) {
            try {
                val likeViewClass = Class.forName(gNames.doubleTapLikeView, false, classLoader)
                likeViewClass.declaredMethods.filter {
                    it.name == gNames.doubleTapLikeMethod && it.parameterCount == 1 &&
                        it.parameterTypes[0].name == "kotlin.Pair" && it.returnType == Void.TYPE
                }.forEachIndexed { index, method ->
                    module.hook(method).setId("doubleTapDiggBlock_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val now = android.os.SystemClock.uptimeMillis()
                            if (gMasterOn && gDoubleTapCommentOn && now <= gSuppressDoubleTapLikeUntil) {
                                LogUtil.incr("doubleTapDiggActionBlocked")
                                null
                            } else chain.proceed()
                        })
                }
                LogUtil.info("  ✓ 双击点赞动作兜底 ${gNames.doubleTapLikeView}.${gNames.doubleTapLikeMethod}(Pair)")
            } catch (e: Throwable) {
                LogUtil.warn("  双击点赞动作兜底安装失败: $e")
            }
        }

        if (gNames.doubleTapHolderLikeMethod.isNotBlank()) {
            try {
                val holderClass = Class.forName(gNames.shortHolder, false, classLoader)
                holderClass.declaredMethods.filter {
                    it.name == gNames.doubleTapHolderLikeMethod &&
                        it.parameterCount == 0 && it.returnType == Void.TYPE
                }.forEachIndexed { index, method ->
                    module.hook(method).setId("doubleTapHolderLikeBlock_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val now = android.os.SystemClock.uptimeMillis()
                            if (gMasterOn && gDoubleTapCommentOn && now <= gSuppressDoubleTapLikeUntil) {
                                LogUtil.incr("doubleTapHolderLikeBlocked")
                                null
                            } else chain.proceed()
                        })
                }
                LogUtil.info("  ✓ 双击最终点赞兜底 ${gNames.shortHolder}.${gNames.doubleTapHolderLikeMethod}()")
            } catch (e: Throwable) {
                LogUtil.warn("  双击最终点赞兜底安装失败: $e")
            }
        }

        if (gNames.resolutionController.isNotBlank()) {
            try {
                val controllerClass = Class.forName(gNames.resolutionController, false, classLoader)
                val engineField = controllerClass.getDeclaredField(gNames.resolutionEngineField).apply { isAccessible = true }
                for ((methodIndex, methodName) in gNames.resolutionModelMethods.withIndex()) {
                    ham(controllerClass, methodName, "defaultQualityModel_${methodIndex}") { chain ->
                        val controller = chain.thisObject
                        val model = try { chain.getArg(0) } catch (_: Throwable) { null }
                        val supported = supportedResolutions(model)
                        // Replace the entire list on every video, including empty models, to avoid stale choices.
                        if (controller != null) {
                            if (gNames.resolutionApplyMethod.isBlank()) {
                                try { engineField.get(controller)?.let { gEngineResolutions[it] = supported } } catch (_: Throwable) {}
                            } else gControllerResolutions[controller] = supported
                        }
                        val result = chain.proceed()
                        try {
                            if (gNames.resolutionApplyMethod.isBlank()) {
                                val engine = if (controller != null) engineField.get(controller) else null
                                if (engine != null) {
                                    gEngineResolutions[engine] = supported
                                    if (gMasterOn && gDefaultQualityOn) selectedResolution(supported)?.let { applyResolutionToEngine(engine, it) }
                                }
                            } else if (gMasterOn && gDefaultQualityOn) {
                                applyResolutionViaController(controller, selectedResolution(supported))
                            }
                        } catch (e: Throwable) { LogUtil.warn("默认画质：应用失败: $e") }
                        result
                    }
                }
                if (gNames.resolutionApplyMethod.isBlank()) {
                    val engineClass = Class.forName("com.ss.ttvideoengine.TTVideoEngine", false, classLoader)
                    ham(engineClass, "configResolution", "defaultQualityConfig") { chain ->
                        if (!gMasterOn || !gDefaultQualityOn) return@ham chain.proceed()
                        val engine = chain.thisObject
                        val supported = gEngineResolutions[engine] ?: return@ham chain.proceed()
                        val resolution = selectedResolution(supported) ?: return@ham chain.proceed()
                        val requested = chain.getArg(0)
                        if (requested !== resolution) {
                            val args = chain.args.toTypedArray()
                            args[0] = resolution
                            LogUtil.incr("defaultQualityOverride")
                            return@ham chain.proceed(args)
                        }
                        chain.proceed()
                    }
                }
                LogUtil.info("  ✓ 默认画质 ${gNames.resolutionController}")
            } catch (e: Throwable) { LogUtil.warn("  默认画质 Hook 失败: $e") }
        }

        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            module.hook(c.getDeclaredMethod("onWindowFocusChanged", Boolean::class.java))
                .setId("wf")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val focused = chain.getArg(0) as? Boolean == true
                    val result = chain.proceed()
                    if (focused) {
                        val a = chain.thisObject as? Activity
                        try {
                            if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                            if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                        } catch (_: Exception) {}
                    }
                    result
                })
            LogUtil.info("  ✓ winFocus")
        } catch (e: Exception) { LogUtil.error("wf", e) }

        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            ham(c, "onMultiWindowModeChanged", "multiWindow") { chain ->
                val result = chain.proceed()
                reapplyAfterWindowModeChange(chain.thisObject as? Activity, "multi-window")
                result
            }
            ham(c, "onPictureInPictureModeChanged", "pictureInPicture") { chain ->
                val result = chain.proceed()
                reapplyAfterWindowModeChange(chain.thisObject as? Activity, "picture-in-picture")
                result
            }
            ham(c, "onConfigurationChanged", "windowConfiguration") { chain ->
                val result = chain.proceed()
                reapplyAfterWindowModeChange(chain.thisObject as? Activity, "configuration")
                result
            }
            LogUtil.info("  ✓ window mode callbacks")
        } catch (e: Throwable) { LogUtil.warn("  window mode callbacks missing: $e") }

        val seriesActivityClasses = listOf(
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity",
            "com.dragon.read.component.shortvideo.impl.seriesdetail.ShortSeriesDetailActivity",
            "com.dragon.read.component.shortvideo.impl.albumdetail.VideoAlbumDetailActivity",
        )
        for ((classIndex, className) in seriesActivityClasses.withIndex()) {
            try {
                val c = Class.forName(className, false, classLoader)
                for (methodName in listOf(
                    "onCreate",
                    "onResume",
                    "onConfigurationChanged",
                    "onMultiWindowModeChanged",
                    "onPictureInPictureModeChanged",
                    "onWindowFocusChanged",
                )) {
                    ham(c, methodName, "seriesWindow_${classIndex}_$methodName") { chain ->
                        val result = chain.proceed()

                        val shouldRefreshLayout = methodName == "onConfigurationChanged" ||
                            methodName == "onMultiWindowModeChanged" ||
                            methodName == "onPictureInPictureModeChanged"
                        reapplySeriesPageState(
                            chain.thisObject,
                            "$className#$methodName",
                            refreshLayout = shouldRefreshLayout,
                        )
                        result
                    }
                }
                LogUtil.info("  ✓ series window tracker: $className")
            } catch (e: Throwable) {
                LogUtil.warn("  series window tracker missing: $className: $e")
            }
        }

        try {
            val className =
                "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment"
            val c = Class.forName(className, false, classLoader)
            hac(c, "seriesFragmentCtor") { chain ->
                val result = chain.proceed()
                registerShortSeriesFragment(chain.thisObject)
                result
            }
            for (methodName in listOf(
                "onCreateContent",
                "onResume",
                "onConfigurationChanged",
                gNames.seriesFragmentRefreshMethod,
            )) {
                ham(c, methodName, "seriesFragment_$methodName") { chain ->
                    val result = chain.proceed()
                    registerShortSeriesFragment(chain.thisObject)
                    reapplySeriesPageState(
                        chain.thisObject,
                        "$className#$methodName",
                        refreshLayout = methodName != gNames.seriesFragmentRefreshMethod,
                    )
                    result
                }
            }
            LogUtil.info("  ✓ series fragment window tracker")
        } catch (e: Throwable) {
            LogUtil.warn("  series fragment window tracker missing: $e")
        }

        try {
            val c = Class.forName("com.dragon.read.base.ui.util.StatusBarUtil", false, classLoader)
            ham(c, "clearFullScreenFlag", "sbC") { chain ->
                val result = chain.proceed()
                if (shouldHideStatusBar()) {
                    val a = chain.getArg(0) as? Activity
                    mainHandler.post { applyCleanTop(a) }
                }
                result
            }
            ham(c, "hideStatusBar", "sbH") { chain ->
                if (!shouldHideStatusBar()) {
                    chain.proceed()
                } else try {
                    if (chain.args.size >= 2 && chain.getArg(1) is Boolean && !(chain.getArg(1) as Boolean)) {
                        val args = chain.args.toTypedArray()
                        args[1] = true
                        chain.proceed(args)
                    } else chain.proceed()
                } catch (_: Exception) { chain.proceed() }
            }

            ham(c, "getStatusHeight", "sbHeight") { chain ->
                if (shouldHideStatusBar()) 0 else chain.proceed()
            }
            LogUtil.info("  ✓ StatusBarUtil")
        } catch (e: Exception) { LogUtil.warn("  StatusBarUtil 未找到") }

        try {
            val c = Class.forName("com.bytedance.ies.uikit.statusbar.StatusBarUtils", false, classLoader)
            module.hook(c.getDeclaredMethod("getStatusBarHeight", Context::class.java))
                .setId("uikitStatusHeight")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    if (shouldHideStatusBar()) 0 else chain.proceed()
                })
            LogUtil.info("  ✓ UIKit StatusBarUtils")
        } catch (e: Exception) { LogUtil.warn("  UIKit StatusBarUtils 未找到: $e") }

        try {
            val isNewCn = gNames.profileId == "CN-7.3.3.18"
            if (!featureEnabled("speed") || (gNames.profileId.startsWith("COMPAT-") || gNames.profileId == "UNSUPPORTED") && gNames.speedControllerClass.isBlank()) throw ClassNotFoundException("倍速映射未通过校验")
            val isOversea = gNames.profileId == "OVERSEA-7.3.1.32"

            val percentPlayerClassName = if (gNames.speedPlayerClass.isNotBlank()) gNames.speedPlayerClass else if (gNames.speedControllerClass.isNotBlank()) gNames.resolutionController else when {
                isNewCn -> "nx4.w"
                isOversea -> "ys4.x"
                else -> "ov4.x"
            }
            val controllerClassName = if (gNames.speedControllerClass.isNotBlank()) gNames.speedControllerClass else when {
                isNewCn -> "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a"
                isOversea -> "lt4.v"
                else -> "bw4.v"
            }
            val controllerSetMethod = if (gNames.speedSetMethod.isNotBlank()) gNames.speedSetMethod else when {
                isNewCn -> "v2"
                isOversea -> "u2"
                else -> "r2"
            }
            val controllerCacheMethod = gNames.speedCacheMethod.ifBlank { if (isNewCn) "w" else "getCacheVideoSpeed" }

            val playerClass = Class.forName(percentPlayerClassName, false, classLoader)
            hac(playerClass, "defaultSpeedPlayerCtor") { chain ->
                val result = chain.proceed()
                try {
                    gKnownPercentSpeedPlayers[chain.thisObject] = true
                    if (defaultSpeedEnabledNow()) {
                        val player = chain.thisObject
                        mainHandler.postDelayed({ applySpeedToPercentPlayer(player, "player-ctor") }, 120L)
                    }
                } catch (_: Throwable) {}
                result
            }
            val setPlaySpeed = playerClass.getDeclaredMethod("setPlaySpeed", Integer.TYPE)
            module.hook(setPlaySpeed).setId("defaultSpeedPercentPlayer")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    try { gKnownPercentSpeedPlayers[chain.thisObject] = true } catch (_: Throwable) {}
                    if (!defaultSpeedEnabledNow()) return@Hooker chain.proceed()
                    val wanted = defaultSpeedPercent()
                    val requested = try { chain.getArg(0) as? Int } catch (_: Throwable) { null }
                    if (requested == wanted) return@Hooker chain.proceed()
                    val args = chain.args.toTypedArray()
                    args[0] = wanted
                    LogUtil.incr("defaultSpeedPercentForce")
                    chain.proceed(args)
                })

            val controllerClass = Class.forName(controllerClassName, false, classLoader)
            val controllerSetter = controllerClass.getDeclaredMethod(
                controllerSetMethod,
                Boolean::class.javaPrimitiveType,
                java.lang.Float.TYPE,
                Boolean::class.javaPrimitiveType,
            )
            module.hook(controllerSetter).setId("defaultSpeedControllerSet")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    if (!defaultSpeedEnabledNow()) return@Hooker chain.proceed()
                    val args = chain.args.toTypedArray()
                    args[1] = gDefaultSpeed
                    LogUtil.incr("defaultSpeedControllerForce")
                    chain.proceed(args)
                })
            val cacheGetter = controllerClass.getDeclaredMethod(controllerCacheMethod, String::class.java)
            module.hook(cacheGetter).setId("defaultSpeedCacheGetter")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    if (defaultSpeedEnabledNow()) gDefaultSpeed else chain.proceed()
                })
            val currentGetter = controllerClass.getDeclaredMethod("getCurrentPlaySpeed")
            module.hook(currentGetter).setId("defaultSpeedCurrentGetter")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    if (defaultSpeedEnabledNow()) defaultSpeedPercent() else chain.proceed()
                })

            val autoplayClass = Class.forName("com.dragon.read.component.shortvideo.impl.autoplay.o", false, classLoader)
            hac(autoplayClass, "defaultSpeedAutoplayCtor") { chain ->
                val result = chain.proceed()
                try {
                    gKnownFloatSpeedPlayers[chain.thisObject] = true
                    if (defaultSpeedEnabledNow()) {
                        val player = chain.thisObject
                        mainHandler.postDelayed({ applySpeedToFloatPlayer(player, "autoplay-ctor") }, 120L)
                    }
                } catch (_: Throwable) {}
                result
            }
            val autoSetSpeed = autoplayClass.getDeclaredMethod("setSpeed", java.lang.Float.TYPE)
            module.hook(autoSetSpeed).setId("defaultSpeedFloatPlayer")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    try { gKnownFloatSpeedPlayers[chain.thisObject] = true } catch (_: Throwable) {}
                    if (!defaultSpeedEnabledNow()) return@Hooker chain.proceed()
                    val requested = try { chain.getArg(0) as? Float } catch (_: Throwable) { null }
                    if (requested != null && kotlin.math.abs(requested - gDefaultSpeed) < 0.001f) return@Hooker chain.proceed()
                    val args = chain.args.toTypedArray()
                    args[0] = gDefaultSpeed
                    LogUtil.incr("defaultSpeedFloatForce")
                    chain.proceed(args)
                })

            LogUtil.info("  ✓ 默认倍速 | profile=${gNames.profileId} | player=$percentPlayerClassName.setPlaySpeed | controller=$controllerClassName.$controllerSetMethod/$controllerCacheMethod | autoplay=setSpeed")
        } catch (e: Throwable) {
            LogUtil.warn("  默认倍速 Hook 失败 | profile=${gNames.profileId}: $e")
        }

        fun hookPlaybackState(className: String, hookId: String) {
            try {
                val c = Class.forName(className, false, classLoader)
                ham(c, "onPlaybackStateChanged", hookId) { chain ->
                    val result = chain.proceed()
                    try {
                        val state = chain.args.lastOrNull { it is Int } as? Int
                        when (state) {
                            1 -> {
                                setVideoPaused(false, "$className#state=1")
                                scheduleDefaultSpeedApply("$className#state=1")
                            }
                            2 -> setVideoPaused(true, "$className#state=2")
                            0, 3 -> if (gVideoPaused) refreshVideoPauseState("$className#state=$state", false)
                        }
                    } catch (e: Throwable) { LogUtil.warn("playback state parse failed: $className: $e") }
                    result
                }
                LogUtil.info("  ✓ playback detector: $className")
            } catch (e: Throwable) { LogUtil.warn("  playback detector missing: $className: $e") }
        }

        hookPlaybackState("com.ss.android.videoshop.controller.VideoController", "vcState")
        hookPlaybackState(gNames.playbackState, "nsState")

        val shortVideoHolderClasses = listOf(gNames.holderBaseS1)
        var shortPlaybackHookCount = 0
        shortVideoHolderClasses.forEachIndexed { classIndex, className ->
            try {
                val c = Class.forName(className, false, classLoader)
                ham(c, gNames.shortStateMethod, "shortState_${classIndex}") { chain ->
                    val result = chain.proceed()
                    try {
                        val holder = chain.thisObject
                        val state = chain.args.lastOrNull { it is Int } as? Int
                        registerShortVideoHolder(holder, state)
                        if (state == 1 || state == 2) {
                            mainHandler.post {
                                if (isShortVideoHolderVisible(holder)) {
                                    setVideoPaused(state == 2, "$className#${gNames.shortStateMethod}=$state")
                                    if (state == 1) scheduleDefaultSpeedApply("$className#${gNames.shortStateMethod}=1")
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        LogUtil.warn("short-video playback parse failed: $className: $e")
                    }
                    result
                }
                shortPlaybackHookCount++
            } catch (_: Throwable) {}
        }
        try {
            val c = Class.forName(gNames.shortHolder, false, classLoader)
            hac(c, "shortHolderCtor") { chain ->
                val result = chain.proceed()
                registerShortVideoHolder(chain.thisObject)
                result
            }
            ham(c, "onBind", "shortHolderBind") { chain ->
                val result = chain.proceed()

                registerShortVideoHolder(chain.thisObject, 0)
                if (gRestoreControlsOnPause) refreshVideoPauseState("short-holder-bind")
                result
            }

            try {
                val nativeClean = c.getDeclaredMethod(
                    gNames.shortControlsMethod,
                    Boolean::class.java,
                    Boolean::class.java,
                )
                module.hook(nativeClean)
                    .setId("shortNativeClean")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        if (!shouldForceShortVideoCleanMask()) return@Hooker chain.proceed()
                        try {
                            val requested = chain.getArg(0) as? Boolean
                            if (requested == false) {
                                val args = chain.args.toTypedArray()
                                args[0] = true
                                LogUtil.incr("shortNativeCleanForce")
                                return@Hooker chain.proceed(args)
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    })
                LogUtil.info("  ✓ native clean-screen ${gNames.shortHolder}.${gNames.shortControlsMethod}")
            } catch (e: Throwable) {
                LogUtil.warn("  native clean-screen hook missing: $e")
            }

            module.hook(c.getDeclaredMethod(gNames.shortMaskMethod, Boolean::class.java))
                .setId("shortMask")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val result = chain.proceed()
                    if (shouldForceShortVideoCleanMask()) rememberAndForceShortVideoMaskInvisible(chain.thisObject)
                    result
                })
        } catch (_: Throwable) {}

        if (gPkg == "com.phoenix.read") {
            try {
                val homeFragment = Class.forName(
                    "com.dragon.read.component.shortvideo.impl.v2.SeriesBookMallTabFragment",
                    false,
                    classLoader,
                )
                hac(homeFragment, "cnHomeFragmentCtor") { chain ->
                    val result = chain.proceed()
                    registerCnHomeFragment(chain.thisObject)
                    result
                }
                val homeMaskMethod = gNames.homeFragmentMaskMethod
                if (homeMaskMethod.isBlank()) throw NoSuchMethodException("homeFragmentMaskMethod blank")
                module.hook(homeFragment.getDeclaredMethod(homeMaskMethod, Boolean::class.java))
                    .setId("cnHomeMask")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        registerCnHomeFragment(chain.thisObject)

                        val result = if (shouldForceShortVideoCleanMask()) {
                            try {
                                val requested = chain.getArg(0) as? Boolean
                                if (requested == false) {
                                    val args = chain.args.toTypedArray()
                                    args[0] = true
                                    LogUtil.incr("homeNativeCleanForce")
                                    chain.proceed(args)
                                } else chain.proceed()
                            } catch (_: Throwable) { chain.proceed() }
                        } else chain.proceed()
                        forceCnHomeFragmentMaskInvisible(chain.thisObject)
                        result
                    })
                LogUtil.info("  ✓ CN home fragment mask ${gNames.homeFragmentMaskMethod}/${gNames.homeFragmentMaskField}")
            } catch (e: Throwable) {
                LogUtil.warn("  CN home fragment mask missing: $e")
            }
        }

        LogUtil.info("  ✓ short-video playback detectors: $shortPlaybackHookCount")

        try {
            val c = Class.forName("com.ss.android.videoshop.mediaview.LayerHostMediaLayout", false, classLoader)
            ham(c, "execCommand", "videoCmd") { chain ->
                var command: Int? = null
                try {
                    val cmd = chain.getArg(0)
                    if (cmd != null) command = cmd.javaClass.getMethod("getCommand").invoke(cmd) as? Int
                } catch (_: Throwable) {}
                val result = chain.proceed()
                when (command) {
                    208 -> refreshVideoPauseState("command=208", true)
                    207, 214 -> refreshVideoPauseState("command=$command", false)
                }
                result
            }
            ham(c, "onVideoPause", "videoPauseDirect") { chain ->
                val result = chain.proceed()
                setVideoPaused(true, "LayerHostMediaLayout#onVideoPause")
                result
            }
            ham(c, "onVideoPlay", "videoPlayDirect") { chain ->
                val result = chain.proceed()
                setVideoPaused(false, "LayerHostMediaLayout#onVideoPlay")
                scheduleDefaultSpeedApply("LayerHostMediaLayout#onVideoPlay")
                result
            }
            LogUtil.info("  ✓ video command detector")
        } catch (e: Throwable) { LogUtil.warn("  video command detector missing: $e") }

        fun hookToolbarLayer(className: String, hookId: String) {
            try {
                val c = Class.forName(className, false, classLoader)
                hac(c, hookId) { chain ->
                    val result = chain.proceed()
                    registerVideoToolbarLayer(chain.thisObject)
                    refreshVideoPauseState("layer-created:$className")
                    result
                }
                LogUtil.info("  ✓ toolbar layer tracker: $className")
            } catch (e: Throwable) { LogUtil.warn("  toolbar layer tracker missing: $className: $e") }
        }
        hookToolbarLayer("com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed", "fixedLayer")
        hookToolbarLayer("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", "customLayer")

        fun trackLayerFromCall(chain: XposedInterface.Chain) {
            try { registerVideoToolbarLayer(chain.thisObject) } catch (_: Throwable) {}
        }

        try { val c = Class.forName("com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed", false, classLoader)
            ham(c, gNames.fixedToolbarShowMethod, "pb") { chain -> trackLayerFromCall(chain); chain.proceed() }
            LogUtil.info("  ✓ playerBtm") } catch (e: Exception) { LogUtil.warn("  ToolbarLayerFixed 未找到") }

        try { val c = Class.forName("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", false, classLoader)
            ham(c, gNames.customizeToolbarShowMethod, "pt") { chain -> trackLayerFromCall(chain); chain.proceed() }
            LogUtil.info("  ✓ playerTop") } catch (e: Exception) { LogUtil.warn("  CustomizeToolbarLayer 未找到") }

        try { val c = Class.forName("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", false, classLoader)
            module.hook(c.getDeclaredMethod(gNames.customizeToolbarApplyMethod, Boolean::class.java, Boolean::class.java, Boolean::class.java)).setId("ctS").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> trackLayerFromCall(chain); chain.proceed() })
            LogUtil.info("  ✓ CustomizeToolbarLayer.${gNames.customizeToolbarApplyMethod}") } catch (e: Exception) { LogUtil.warn("  CustomizeToolbarLayer.${gNames.customizeToolbarApplyMethod} 未找到") }

        try { val c = Class.forName(gNames.toolbarBase, false, classLoader)
            hac(c, "toolbarBaseCtor") { chain -> val r = chain.proceed(); registerToolbarBaseLayer(chain.thisObject); r }
            module.hook(c.getDeclaredMethod("a", Boolean::class.java)).setId("gt7c").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                registerToolbarBaseLayer(chain.thisObject)
                chain.proceed()
            })
            LogUtil.info("  ✓ ${gNames.toolbarBase}.a") } catch (e: Exception) { LogUtil.warn("  ${gNames.toolbarBase} 未找到") }

        try {
            val mgrClass = Class.forName("com.dragon.read.base.ssconfig.SsConfigMgr", false, classLoader)
            ham(mgrClass, "getABValueJson", "downloadLimitAB") { chain ->
                try {
                    val key = chain.getArg(0)?.toString()
                    if (key == DOWNLOAD_LIMIT_AB_KEY && downloadUnlimitEnabledNow()) {
                        val json = customDownloadLimitJson()
                        LogUtil.incr("downloadLimitABOverride")
                        LogUtil.info("download limit override: SsConfigMgr.getABValueJson -> $json profile=${gNames.profileId}")
                        return@ham json
                    }
                } catch (e: Throwable) {
                    LogUtil.warn("download limit AB override failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                chain.proceed()
            }
            LogUtil.info("  ✓ 下载数量限制 AB Hook SsConfigMgr.getABValueJson")
        } catch (e: Throwable) {
            LogUtil.warn("  下载数量限制 AB Hook 失败: $e")
        }

        try {
            if (gNames.profileId !in setOf("CN-7.3.1.32", "CN-7.3.2.32", "CN-7.3.3.18", "OVERSEA-7.3.1.32")) throw ClassNotFoundException("未验证旧版 KMP 下载映射，跳过")
            val svcClass = Class.forName("s93.q", false, classLoader)
            val a7Methods = svcClass.declaredMethods.filter { it.name == "A7" && it.parameterCount >= 1 }
            a7Methods.forEachIndexed { index, method ->
                module.hook(method).setId("downloadLimitKmpA7_$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val key = try { chain.getArg(0)?.toString() } catch (_: Throwable) { null }
                        if (key == DOWNLOAD_LIMIT_AB_KEY && downloadUnlimitEnabledNow()) {
                            val json = customDownloadLimitJson()
                            LogUtil.incr("downloadLimitKmpOverride")
                            LogUtil.info("download limit override: s93.q.A7 -> $json profile=${gNames.profileId}")
                            json
                        } else chain.proceed()
                    })
            }
            if (a7Methods.isNotEmpty()) LogUtil.info("  ✓ 下载数量限制 KMP Hook s93.q.A7")
        } catch (_: Throwable) {}

        try {
            if (gNames.profileId !in setOf("CN-7.3.1.32", "CN-7.3.2.32", "CN-7.3.3.18", "OVERSEA-7.3.1.32")) throw ClassNotFoundException("未验证旧版下载模型，跳过")
            val configClass = Class.forName("xh5.f", false, classLoader)
            hac(configClass, "downloadLimitConfigCtor") { chain ->
                val result = chain.proceed()
                if (downloadUnlimitEnabledNow() && overwriteDownloadLimitObject(chain.thisObject)) {
                    LogUtil.incr("downloadLimitObjectOverride")
                    LogUtil.info("download limit override: xh5.f ctor a/b/c=${currentDownloadLimitValues()}")
                }
                result
            }
            try {
                val serializerClass = Class.forName("xh5.f\$a", false, classLoader)
                ham(serializerClass, "deserialize", "downloadLimitDeserialize") { chain ->
                    val result = chain.proceed()
                    if (downloadUnlimitEnabledNow() && overwriteDownloadLimitObject(result)) {
                        LogUtil.incr("downloadLimitDeserializeOverride")
                        LogUtil.info("download limit override: xh5.f\$a.deserialize a/b/c=${currentDownloadLimitValues()}")
                    }
                    result
                }
            } catch (_: Throwable) {}
            LogUtil.info("  ✓ 下载数量限制对象 Hook xh5.f")
        } catch (_: Throwable) {}

        try { val c = Class.forName("com.dragon.read.component.biz.impl.BsGoldBoxServiceImpl", false, classLoader); ham(c, "tryAttach", "gb") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }; LogUtil.info("  ✓ goldBox") } catch (e: Exception) { LogUtil.warn("  BsGoldBoxServiceImpl 未找到") }
        try { val c = Class.forName("com.bytedance.ug.sdk.novel.pendant.PlanPendantServiceImpl", false, classLoader); ham(c, "triggerEvent", "pd") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }; LogUtil.info("  ✓ pendant") } catch (e: Exception) { LogUtil.warn("  PlanPendantServiceImpl 未找到") }

        try { var rc: Class<*>? = null; try { rc = Class.forName("com.dragon.read.component.biz.impl.bookmall.holder.video.VideoRedPacketHolder", false, classLoader) } catch (_: Exception) {}; if (rc != null) { hac(rc, "rp") { chain -> if (!gMasterOn || !gAdOn) chain.proceed() else { val r = chain.proceed(); try { val iv = chain.thisObject.javaClass.getField("itemView").get(chain.thisObject) as? View; iv?.visibility = View.GONE; iv?.layoutParams = ViewGroup.LayoutParams(0, 0) } catch (_: Exception) {}; r } }; LogUtil.info("  ✓ redPack") } } catch (e: Exception) { LogUtil.warn("redPack: $e") }


        try {
            val c = Class.forName("com.dragon.read.component.shortvideo.impl.rightview.LimitHeightByChildrenConstraintLayout", false, classLoader)
            module.hook(c.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
                .setId("guoplusInteractionMeasure").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val host = chain.thisObject as? ViewGroup
                    if (host == null) chain.proceed()
                    else controlVisibility.withInteractionMeasurement(host) { chain.proceed() }
                })
        } catch (e: Throwable) { LogUtil.warn("guoplus interaction measure hook: $e") }

        try {
            val c = Class.forName("android.view.LayoutInflater", false, classLoader)
            ham(c, "inflate", "inf") { chain ->
                val result = chain.proceed()
                if (result is ViewGroup) {
                    LogUtil.incr("inflate")
                    gNames.hideIdNames.forEach { resolveEntryId(it, result) }
                    gNames.progressIdNames.forEach { resolveEntryId(it, result, gProgressIdSet, "进度条资源") }
                    resolvePauseRestoreIds(result)
                    if (gMasterOn && gControlOn && !pauseRestoresControls()) scanTreeQuick(result)
                    if (gMasterOn && gRefreshOff) scanTreeRefreshAccessory(result)
                }
                result
            }
            LogUtil.info("  ✓ inflate")
        } catch (e: Exception) { LogUtil.error("inflate", e) }

        try {
            val c = Class.forName("android.view.View", false, classLoader)
            ham(c, "setVisibility", "sv") { chain ->
                if (gInternalViewMutation.get() == true) return@ham chain.proceed()
                val view = chain.thisObject as? View
                val targetVisibility = chain.getArg(0) as? Int

                if (view != null && targetVisibility != null) {
                    synchronized(gSavedViewStates) {
                        gSavedViewStates[view]?.visibility = targetVisibility
                    }
                }

                if (view != null && targetVisibility != null) {
                    val desired = controlVisibility.requestedVisibility(view, targetVisibility)
                    if (desired != targetVisibility) {
                        val args = chain.args.toTypedArray()
                        args[0] = desired
                        return@ham chain.proceed(args)
                    }
                }
                if (!gMasterOn || pauseRestoresControls()) return@ham chain.proceed()
                try {
                    LogUtil.incr("setVis")
                    if (targetVisibility == View.VISIBLE && view != null) {
                        val replacement = when {
                            gRefreshOff && (synchronized(gKnownRefreshAccessoryViews) { gKnownRefreshAccessoryViews.containsKey(view) } || isRefreshAccessoryContainer(view)) -> {
                                synchronized(gKnownRefreshAccessoryViews) { gKnownRefreshAccessoryViews[view] = true }
                                View.GONE
                            }
                            else -> null
                        }
                        if (replacement != null) {
                            rememberViewState(view, View.VISIBLE)
                            val args = chain.args.toTypedArray()
                            args[0] = replacement
                            return@ham chain.proceed(args)
                        }
                    }
                    chain.proceed()
                } catch (_: Exception) { chain.proceed() }
            }
            LogUtil.info("  ✓ setVis")
        } catch (e: Exception) { LogUtil.error("setVis", e) }
        try {
            val c = Class.forName("android.view.ViewGroup", false, classLoader)
            ham(c, "addView", "av") { chain ->
                val v = try { chain.getArg(0) as? View } catch (_: Throwable) { null }

                val result = chain.proceed()
                try {
                    if (!gMasterOn) return@ham result
                    LogUtil.incr("addView")
                    if (v != null) {
                        syncCustomControls(v)
                        if (gRefreshOff && isRefreshAccessoryContainer(v)) hideRefreshAccessory(v)
                    }
                } catch (_: Exception) {}
                result
            }
            LogUtil.info("  ✓ addView")
        } catch (e: Exception) { LogUtil.error("addView", e) }

        try { val c = Class.forName("com.dragon.read.recyler.AbsRecyclerViewHolder", false, classLoader); hac(c, "cc") { chain -> if (gMasterOn) try { val v = chain.getArg(0) as? View; if (v != null) { if (gControlOn) scanTreeQuick(v) else scanTreeRestore(v) } } catch (_: Exception) {}; chain.proceed() }; try { module.hook(c.getDeclaredMethod("onBind", Object::class.java, Int::class.java)).setId("cb").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn) try { val iv = chain.thisObject.javaClass.getField("itemView").get(chain.thisObject) as? View; if (iv != null) { if (gControlOn) scanTreeQuick(iv) else scanTreeRestore(iv) } } catch (_: Exception) {}; chain.proceed() }) } catch (_: Exception) {}; LogUtil.info("  ✓ card") } catch (e: Exception) { LogUtil.warn("  AbsRecyclerViewHolder 未找到") }

        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            module.hook(c.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent::class.java))
                .setId("topSwipe").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                    val act = chain.thisObject as? Activity
                    val ev = chain.getArg(0) as? android.view.MotionEvent
                    if (act != null && ev != null && gMasterOn && gTopZoneOn && act === gCurrentActivity &&
                        act.javaClass.name in setOf("com.dragon.read.pages.main.MainFragmentActivity",
                            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity",
                            "com.dragon.read.component.shortvideo.impl.seriesdetail.ShortSeriesDetailActivity",
                            "com.dragon.read.component.shortvideo.impl.albumdetail.VideoAlbumDetailActivity")) {
                        val decision = topSwipe.onEvent(ev.actionMasked, ev.rawX, ev.rawY,
                            dp(act, 80f).toFloat(), android.view.ViewConfiguration.get(act).scaledTouchSlop.toFloat())
                        if (decision) {
                            if (ev.actionMasked == android.view.MotionEvent.ACTION_MOVE && topSwipe.justIntercepted) {
                                val cancel = android.view.MotionEvent.obtain(ev)
                                cancel.action = android.view.MotionEvent.ACTION_CANCEL
                                try { chain.proceed(arrayOf(cancel)) } finally { cancel.recycle() }
                            }
                            true
                        } else chain.proceed()
                    } else { topSwipe.reset(); chain.proceed() }
                })
        } catch (e: Exception) { LogUtil.error("顶部下滑拦截", e) }

        try { val c = Class.forName("androidx.swiperefreshlayout.widget.SwipeRefreshLayout", false, classLoader); module.hook(c.getDeclaredMethod("onInterceptTouchEvent", android.view.MotionEvent::class.java)).setId("sw").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gRefreshOff) false else chain.proceed() }); LogUtil.info("  ✓ swipe") } catch (e: Exception) { LogUtil.warn("  SwipeRefreshLayout 未找到") }


        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            module.hook(c.getDeclaredMethod("onCreate", android.os.Bundle::class.java)).setId("oc").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                LogUtil.incr("onCreate")
                val a = chain.thisObject as? Activity

                try {
                    if (a != null) {

                        gCurrentActivity = a
                        initPrefs(a.applicationContext)
                        appCtx = a.applicationContext
                        ensureReceiver(a.applicationContext)
                    }
                } catch (e: Exception) { LogUtil.error("Activity 创建时配置初始化失败", e) }
                val result = chain.proceed()
                try {
                    if (a != null) {
                        if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                        if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                        createNotification(a)
                    }
                } catch (_: Exception) {}
                result
            })
            LogUtil.info("  ✓ onCreate")
            module.hook(c.getDeclaredMethod("onResume")).setId("or").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                LogUtil.incr("onResume")
                val a = chain.thisObject as? Activity
                gCurrentActivity = a

                scheduleSettingsEntryInjection(a)

                try {
                    if (a != null) {
                        UpdateChecker.showUpdateDialogIfNeeded(a)
                        pollUpdatePrompt(a, 0)
                    }
                } catch (_: Exception) {}
                try {
                    if (a != null) {
                        initPrefs(a.applicationContext)
                        appCtx = a.applicationContext
                        ensureReceiver(a.applicationContext)
                    }
                } catch (e: Exception) { LogUtil.error("Activity 恢复时配置初始化失败", e) }
                val result = chain.proceed()
                try {
                    if (a != null) {
                        if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                        if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                        createNotification(a)
                    }
                } catch (_: Exception) {}
                mainHandler.postDelayed({
                    scanAllWindows()
                    if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                    if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                }, 400)
                startPeriodicScan()
                LogUtil.diagDump(true)
                result
            })
            LogUtil.info("  ✓ onResume")
            module.hook(c.getDeclaredMethod("onPause")).setId("op").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                val a = chain.thisObject as? Activity
                stopPeriodicScan()
                val result = chain.proceed()
                mainHandler.postDelayed({
                    try {

                        if (a != null && a.hasWindowFocus() && !a.isFinishing) refreshVideoPauseState("activity-onPause")
                    } catch (_: Throwable) {}
                }, 120L)
                result
            })
            LogUtil.info("  ✓ onPause")
        } catch (e: Exception) { LogUtil.error("lifecycle", e) }

        val settingsSpec = nativeSettingsSpec()
        var nativeSettingsListHookCount = 0
        for ((classIndex, settingsClassName) in listOf(
            "com.dragon.read.component.biz.impl.mine.settings.SettingsActivity",
            "com.dragon.read.component.biz.impl.mine.settings.KmpSettingsActivity",
        ).withIndex()) {
            try {
                val settingsClass = Class.forName(settingsClassName, false, classLoader)
                settingsClass.declaredMethods
                    .filter { it.name in settingsSpec.listMethods && java.util.List::class.java.isAssignableFrom(it.returnType) }
                    .forEachIndexed { methodIndex, method ->
                        module.hook(method)
                            .setId("nativeSettingsList_${classIndex}_$methodIndex")
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(Hooker { chain ->
                                val result = chain.proceed()
                                injectNativeSettingsList(result, classLoader, settingsSpec)
                                result
                            })
                        nativeSettingsListHookCount++
                        LogUtil.info("  ✓ native settings list $settingsClassName.${method.name} -> ${settingsSpec.itemClass}")
                    }
            } catch (_: Throwable) {}
        }

        try {
            val clickClass = Class.forName(settingsSpec.clickClass, false, classLoader)
            clickClass.declaredMethods.filter { it.name == "onClick" }.forEachIndexed { index, method ->
                module.hook(method)
                    .setId("nativeSettingsClick_$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val host = chain.thisObject
                        if (host != null && boundSettingsTitle(host) == "模块设置") {
                            LogUtil.info("点击原生模块设置项 | ${settingsSpec.clickClass}")
                            mainHandler.post { showPanel(gSettingsActivity ?: gCurrentActivity) }
                            null
                        } else chain.proceed()
                    })
            }
            LogUtil.info("  ✓ native settings click ${settingsSpec.clickClass}")
        } catch (e: Throwable) {
            LogUtil.warn("  ${settingsSpec.clickClass} 设置点击 Hook 失败: $e")
        }


        if (nativeSettingsListHookCount == 0) {
            LogUtil.warn("  原生设置列表 Hook 未找到 | profile=${gNames.profileId} | methods=${settingsSpec.listMethods}")
        }

        var settingsHookCount = 0
        for ((index, settingsClassName) in listOf(
            "com.dragon.read.component.biz.impl.mine.settings.SettingsActivity",
            "com.dragon.read.component.biz.impl.mine.settings.KmpSettingsActivity",
        ).withIndex()) {
            try {
                val c = Class.forName(settingsClassName, false, classLoader)
                module.hook(c.getDeclaredMethod("onCreate", android.os.Bundle::class.java))
                    .setId("st$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val a = chain.thisObject as? Activity
                        gSettingsActivity = a
                        val r = chain.proceed()
                        if (a != null) scheduleSettingsEntryInjection(a)
                        r
                    })
                settingsHookCount++
                LogUtil.info("  ✓ settings $settingsClassName")
            } catch (_: Throwable) {}
        }
        if (settingsHookCount == 0) LogUtil.warn("  设置页 Activity 未找到")

        try {
            val hClass = Class.forName(gNames.oledBright, false, classLoader)
            val brightActionClass = Class.forName(gNames.oledBrightAction, false, classLoader)
            module.hook(hClass.getDeclaredMethod("a", java.util.List::class.java)).setId("oled").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                if (!gMasterOn || !gOledProtectOn) return@Hooker chain.proceed()
                try {
                    val list = chain.getArg(0) as? java.util.List<*>
                    if (list != null && list.isNotEmpty()) {
                        val filtered = list.filter { it == null || !brightActionClass.isInstance(it) }
                        if (filtered.size < list.size) {
                            LogUtil.incr("oledBrightBlock")
                            val na = chain.args.toTypedArray()
                            na[0] = java.util.ArrayList(filtered)
                            return@Hooker chain.proceed(na)
                        }
                    }
                } catch (_: Exception) {}
                chain.proceed()
            })
            LogUtil.info("  ✓ OLED 亮度拦截（默认关闭）")
        } catch (e: Exception) { LogUtil.warn("  ${gNames.oledBright} OLED 未找到: $e") }

        try { val c = Class.forName(gNames.pauseAdEntryClass, false, classLoader)
            ham(c, gNames.pauseAdEntryMethod, "pauseAdEntry") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            LogUtil.info("  ✓ 暂停广告入口 ${gNames.pauseAdEntryClass}.${gNames.pauseAdEntryMethod}")
        } catch (e: Exception) {
            LogUtil.warn("  暂停广告入口 ${gNames.pauseAdEntryClass}.${gNames.pauseAdEntryMethod} 未找到: $e")
        }

        try { val c = Class.forName("com.dragon.read.ad.onestop.seriespause.impl.SeriesPauseAdImpl", false, classLoader)
            ham(c, "canShowPauseAd", "spCan") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            ham(c, "enablePauseAd", "spEnable") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            ham(c, "requestAd", "spReq") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            ham(c, "onPauseAdShow", "spShow") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            ham(c, "enableCoinBox", "spCoin") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            LogUtil.info("  ✓ SeriesPauseAdImpl 拦截") } catch (e: Exception) { LogUtil.warn("  SeriesPauseAdImpl 未找到: $e") }

        try { val c = Class.forName("com.dragon.read.pages.video.layers.advideoendlayer.AdVideoEndLayer", false, classLoader)
            ham(c, "handleVideoEvent", "veAd") { chain -> if (gMasterOn && gAdOn) { try { val ev = chain.getArg(0); val m = ev?.javaClass?.getMethod("getType"); if (m != null && (m.invoke(ev) as? Int) == 102) return@ham false } catch (_: Exception) {} }; chain.proceed() }
            ham(c, gNames.adVideoEndShowMethod, "veAdI") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            LogUtil.info("  ✓ 片尾广告层") } catch (e: Exception) { LogUtil.warn("  AdVideoEndLayer 未找到: $e") }

        try { val c = Class.forName("com.dragon.read.pages.video.layers.adiconlayer.AdIconLayer", false, classLoader)
            ham(c, "handleVideoEvent", "aiAd") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            LogUtil.info("  ✓ 广告图标层") } catch (e: Exception) { LogUtil.warn("  AdIconLayer 未找到: $e") }

        try { val c = Class.forName("com.dragon.read.component.biz.impl.privilege.PrivilegeManager", false, classLoader)
            ham(c, "isVip", "vipIsVip") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "isAnyVip", "vipAny") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "canReadShortStory", "vipRead") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "hasVipShortSeriesPrivilege", "vipSeries") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "b", "vipSubType") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "hasNoAdFollAllScene", "vipNoAd") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "hasNoAdForShortSeries", "vipNoAdS") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }

            try {
                val vim = Class.forName("com.dragon.read.user.model.VipInfoModel", false, classLoader)
                val vct = Class.forName("com.dragon.read.rpc.model.VipCommonSubType", false, classLoader)
                val vc = vim.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, vct)
                vc.isAccessible = true
                val de = vct.getEnumConstants()[0]
                val fakeModel = { vc.newInstance("2099-12-31 23:59:59", "1", "99999999", true, false, 0, true, de) }
                c.declaredMethods.filter { it.name == "getVipInfo" }.forEachIndexed { i, m -> try { module.hook(m).setId("vipGetInfo_$i").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gVipOn) fakeModel() else chain.proceed() }) } catch (_: Exception) {} }
                module.hook(c.getDeclaredMethod("getAllVipInfo")).setId("vipGetAll").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gVipOn) java.util.Collections.singletonList(fakeModel()) else chain.proceed() })
                LogUtil.info("  ✓ getVipInfo 模型")
            } catch (e2: Exception) { LogUtil.warn("  getVipInfo 模型 hook 失败: $e2") }
            LogUtil.info("  ✓ VIP 解锁 (PrivilegeManager)") } catch (e: Exception) { LogUtil.warn("  PrivilegeManager 未找到: $e") }
        try { val c = Class.forName("com.dragon.read.component.NsUserInfoDependImpl", false, classLoader)
            ham(c, "isVip", "nsVip") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            try {
                val vim = Class.forName("com.dragon.read.user.model.VipInfoModel", false, classLoader)
                val vct = Class.forName("com.dragon.read.rpc.model.VipCommonSubType", false, classLoader)
                val vc = vim.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, vct)
                vc.isAccessible = true
                val de = vct.getEnumConstants()[0]
                module.hook(c.getDeclaredMethod("getVipInfoModel")).setId("vipGetModel").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gVipOn) vc.newInstance("2099-12-31 23:59:59", "1", "99999999", true, false, 0, true, de) else chain.proceed() })
                LogUtil.info("  ✓ getVipInfoModel")
            } catch (e2: Exception) { LogUtil.warn("  getVipInfoModel hook 失败: $e2") }
            LogUtil.info("  ✓ NsUserInfoDependImpl.isVip") } catch (e: Exception) { LogUtil.warn("  NsUserInfoDependImpl 未找到: $e") }
        try { val c = Class.forName("com.dragon.read.component.NsComicAdDependImpl", false, classLoader)
            ham(c, "isVipUser", "comicVip") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            LogUtil.info("  ✓ NsComicAdDependImpl.isVipUser") } catch (e: Exception) { LogUtil.warn("  NsComicAdDependImpl 未找到: $e") }

        try {
            val eCls = Class.forName(gNames.kmpVipModel, false, classLoader)
            val eCtor = eCls.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, java.lang.Boolean::class.java, java.lang.Boolean::class.java, Integer::class.java, java.lang.Boolean::class.java, Integer::class.java, java.lang.Boolean::class.java, Integer::class.java, java.lang.Boolean::class.java)
            eCtor.isAccessible = true
            var kmpHooked = 0
            for (svc in gNames.kmpAcctService) {
                try {
                    val c = Class.forName(svc, false, classLoader)
                    module.hook(c.getDeclaredMethod("getVipInfo")).setId("kmpVipInfo_$svc").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                        if (gMasterOn && gVipOn) { try { eCtor.newInstance("1", "2099-12-31 23:59:59", "99999999", true, true, 0, true, 0, true, 0, true) } catch (_: Exception) { chain.proceed() } } else chain.proceed()
                    })
                    kmpHooked++
                } catch (_: Exception) {}
            }
            LogUtil.info("  ✓ KMP getVipInfo hooked=$kmpHooked ($gNames.kmpVipModel)") } catch (e: Exception) { LogUtil.warn("  KMP vip 模型 ${gNames.kmpVipModel} 未找到: $e") }
        try { val c = Class.forName("com.dragon.read.component.biz.impl.NsVipImpl", false, classLoader)
            ham(c, "isVip", "nvVip") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "isSpecificVipOrHigher", "nvSpec") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "canShowVipCenter", "nvCenter") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            LogUtil.info("  ✓ NsVipImpl") } catch (e: Exception) { LogUtil.warn("  NsVipImpl 未找到: $e") }

        try {
            val viewClass = Class.forName("android.view.View", false, classLoader)
            try {
            if (featureEnabled("drawer") && (gNames.nativeDrawer || gNames.profileId == "CN-7.3.2.32")) {
                val actionClass = Class.forName(gNames.drawerActionClass, false, classLoader)
                ham(actionClass, gNames.drawerClickMethod, "guoplusNativeDrawerClick") { chain ->
                    if (nativePlaybackDrawer.owns(chain.thisObject)) { toggleCleanScreen(); null }
                    else chain.proceed()
                }
                for (name in listOf("f", "ShortSeriesMorePanelDialogV2", "ShortSeriesMorePanelDialogImmersive")) {
                    try {
                        val clazz = Class.forName("com.dragon.read.component.shortvideo.impl.moredialog.$name", false, classLoader)
                        ham(clazz, "onCreate", "guoplusDrawerPrepare_$name") { chain ->
                            (chain.thisObject as? Dialog)?.let(nativePlaybackDrawer::prepare)
                            chain.proceed()
                        }
                    } catch (e: Throwable) { LogUtil.warn("果+ 抽屉初始化 Hook 失败: $name $e") }
                }
                val holderClass = Class.forName("com.dragon.read.component.shortvideo.impl.moredialog.ShortSeriesMorePanelDialogV2\$b", false, classLoader)
                for (method in gNames.drawerBindMethods) ham(holderClass, method, "guoplusDrawerBind_$method") { chain ->
                    val result = chain.proceed()
                    chain.args.firstOrNull()?.let { nativePlaybackDrawer.bind(chain.thisObject, it) }
                    result
                }
            }
            } catch (e: Throwable) { LogUtil.warn("果+ 原生抽屉映射不可用，使用菜单兜底: $e") }
            ham(viewClass, "setAlpha", "guoplusAlpha") { chain ->
                val view = chain.thisObject as? View
                val requested = chain.getArg(0) as? Float
                if (view == null || requested == null || gInternalViewMutation.get() == true) chain.proceed()
                else {
                    val args = chain.args.toTypedArray()
                    args[0] = controlVisibility.requestedAlpha(view, requested)
                    chain.proceed(args)
                }
            }
            ham(viewClass, "setTranslationY", "guoplusRowTranslation") { chain ->
                val view = chain.thisObject as? View
                val requested = chain.getArg(0) as? Float
                if (view == null || requested == null || gInternalViewMutation.get() == true) chain.proceed()
                else {
                    val args = chain.args.toTypedArray()
                    args[0] = controlVisibility.requestedTranslationY(view, requested)
                    chain.proceed(args)
                }
            }
            val dialogClass = Class.forName("android.app.Dialog", false, classLoader)
            ham(dialogClass, "show", "guoplusDrawer") { chain ->
                val result = chain.proceed()
                val dialog = chain.thisObject as? Dialog
                if (dialog != null && !nativePlaybackDrawer.isPrepared(dialog)) {
                    if (dialog.isShowing && shortVideoHolderSnapshot().any { isShortVideoHolderVisible(it.first) }) {
                        dialog.window?.decorView?.let {
                            playbackDrawer.inject(it, dialog.javaClass.name) { dialog.dismiss() }
                        }
                    }
                }
                result
            }
            val popupClass = Class.forName("android.widget.PopupWindow", false, classLoader)
            for (method in listOf("showAtLocation", "showAsDropDown")) {
                ham(popupClass, method, "guoplusPopup_$method") { chain ->
                    val result = chain.proceed()
                    val popup = chain.thisObject as? PopupWindow
                    if (popup != null) {
                        if (popup.isShowing && shortVideoHolderSnapshot().any { isShortVideoHolderVisible(it.first) }) {
                            popup.contentView?.let { playbackDrawer.inject(it, popup.javaClass.name) { popup.dismiss() } }
                        }
                    }
                    result
                }
            }
            ham(viewClass, "onDetachedFromWindow", "guoplusDetach") { chain ->
                val result = chain.proceed()
                if (chain.thisObject === gCurrentActivity?.window?.decorView) controlVisibility.clear()
                result
            }
        } catch (e: Throwable) { LogUtil.warn("果+ 控件/抽屉 Hook 安装失败: $e") }

        LogUtil.info("installBusinessHooks done"); LogUtil.diagDump(true)
    }

    fun installDemoHooks(module: MainHook, classLoader: ClassLoader) {
        LogUtil.info("demo")
        try { val c = Class.forName("com.hmodule.MainActivity", false, classLoader); module.hook(c.getMethod("onCreate", android.os.Bundle::class.java)).setPriority(XposedInterface.PRIORITY_DEFAULT).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("dm").intercept(Hooker { chain -> chain.proceed() }); LogUtil.info("demo ok") } catch (e: Exception) { LogUtil.error("demo", e) }
    }
}
