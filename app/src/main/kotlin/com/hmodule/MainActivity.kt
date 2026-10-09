package com.hmodule

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hmodule.hooks.TargetNames
import com.hmodule.ui.MiuixUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity() {
    companion object {
        const val EXTRA_PAGE = "com.hmodule.extra.PAGE"
        const val PAGE_FEATURES = 1
    }
    private lateinit var updateStatus: TextView
    private var selectedPage = 0
    private val prefs by lazy { com.hmodule.config.ModuleConfig.local(this) }
    private val configHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val renderConfiguration = Runnable { if (!isFinishing && !isDestroyed) renderPage() }
    private val hostConfigurationChanged: () -> Unit = {
        configHandler.removeCallbacks(renderConfiguration)
        configHandler.post(renderConfiguration)
    }
    private val configChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == com.hmodule.config.ModuleConfig.THEME) runOnUiThread { renderPage() }
    }
    private val currentVersion = BuildConfig.VERSION_NAME.substringBefore(' ').substringBefore('(')
    private val frameworkChanged: () -> Unit = { runOnUiThread { if (!isFinishing && !isDestroyed) renderPage() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPage = (savedInstanceState?.getInt("selectedPage") ?: intent.getIntExtra(EXTRA_PAGE, 0)).coerceIn(0, 2)
        MiuixUi.preferences = prefs
        prefs.registerOnSharedPreferenceChangeListener(configChanged)
        renderPage()
        UpdateChecker.resetShown()
        silentCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedPage = intent.getIntExtra(EXTRA_PAGE, 0).coerceIn(0, 2)
        renderPage()
    }

    override fun onStart() {
        super.onStart()
        LauncherStatus.subscribe(frameworkChanged)
        com.hmodule.config.ModuleConfig.observeHostWrites(hostConfigurationChanged)
    }

    override fun onStop() {
        LauncherStatus.unsubscribe(frameworkChanged)
        com.hmodule.config.ModuleConfig.stopObservingHostWrites(hostConfigurationChanged)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        renderPage()
        UpdateChecker.showUpdateDialogIfNeeded(this)
    }

    @Suppress("DEPRECATION")
    private fun installed(pkg: String): PackageInfo? = try {
        packageManager.getPackageInfo(pkg, 0)
    } catch (_: Exception) { null }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selectedPage", selectedPage)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        configHandler.removeCallbacks(renderConfiguration)
        prefs.unregisterOnSharedPreferenceChangeListener(configChanged)
        super.onDestroy()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selectedPage != 0) { selectedPage = 0; renderPage() } else super.onBackPressed()
    }
    private fun renderPage() {
        val p = MiuixUi.palette(this)
        fun dp(value: Int) = MiuixUi.dp(this, value)
        val page = MiuixUi.page(this)
        val service = LauncherStatus.service
        val framework = try { service?.frameworkName } catch (_: Exception) { null }
        val api = try { service?.apiVersion } catch (_: Exception) { null }
        val cn = installed(TargetNames.CN_PACKAGE)
        val overseas = installed(TargetNames.OVERSEA_PACKAGE)
        val supported = TargetNames.isSupported(TargetNames.CN_PACKAGE, cn?.versionName.orEmpty(), cn?.longVersionCode ?: -1) ||
            TargetNames.isSupported(TargetNames.OVERSEA_PACKAGE, overseas?.versionName.orEmpty(), overseas?.longVersionCode ?: -1)
        val canNavigate = com.hmodule.ui.SettingsAccess.canNavigate(api != null, supported)
        if (!canNavigate) selectedPage = 0
        fun buildContent(index: Int): ScrollView {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(28))
            }
            if (index == 0) content.addView(MiuixUi.text(this, "果+", 26f, p.text, true).apply {
                setPadding(0, dp(4), 0, dp(28))
            }) else content.addView(MiuixUi.header(this, if (index == 1) "模块设置" else "设置") {
                selectedPage = 0; renderPage()
            })
            fun heading(value: String) {
                content.addView(MiuixUi.text(this, value, 13f, p.accent, true).apply {
                    setPadding(dp(12), dp(22), dp(12), dp(12))
                })
            }
            if (index == 0) {
                content.addView(MiuixUi.statusCard(this,
                    if (api != null) "模块已激活" else "模块未激活",
                    "模块版本  ${BuildConfig.VERSION_NAME}", api?.let { "libxposed API $it" }, healthy = api != null))
                fun information(title: String, value: String, badge: String? = null) = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = dp(72)
                    setPadding(dp(20), dp(17), dp(20), dp(17))
                    addView(LinearLayout(this@MainActivity).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(MiuixUi.text(this@MainActivity, title, 16f, p.text))
                        if (badge != null) addView(MiuixUi.text(this@MainActivity, badge, 11f,
                            if (MiuixUi.isDark(this@MainActivity)) 0xffffaaa4.toInt() else 0xffb3261e.toInt(), true).apply {
                            background = MiuixUi.rounded(this@MainActivity,
                                if (MiuixUi.isDark(this@MainActivity)) 0xff482222.toInt() else 0xfffbe1e0.toInt(), 6)
                            setPadding(dp(7), dp(4), dp(7), dp(4))
                        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                    })
                    addView(MiuixUi.text(this@MainActivity, value, 13f, p.secondary).apply {
                        setPadding(0, dp(6), 0, 0)
                    })
                }
                heading("设备信息")
                val info = MiuixUi.card(this)
                if (cn == null && overseas == null) MiuixUi.addRow(info, information("红果版本", "未安装"))
                for ((pkg, installed, label) in listOf(Triple(TargetNames.CN_PACKAGE, cn, "国版"),
                    Triple(TargetNames.OVERSEA_PACKAGE, overseas, "海外版"))) {
                    if (installed == null) continue
                    val title = if (cn != null && overseas != null) "红果版本 · $label" else "红果版本"
                    val version = installed.versionName ?: "未知"
                    val value = if (pkg == TargetNames.OVERSEA_PACKAGE) "$version · 海外版" else version
                    val exact = com.hmodule.adaptation.AdaptationStore.exact(pkg, version, installed.longVersionCode)
                    val neighbour = if (exact == null) com.hmodule.adaptation.AdaptationStore.nearest(pkg, version) else null
                    val partial = exact?.features?.any { it.outcome != "PASS" } == true
                    val status = if (partial) "部分适配" else if (TargetNames.isSupported(pkg, version, installed.longVersionCode)) null else if (neighbour != null) "部分适配" else "未适配"
                    MiuixUi.addRow(info, information(title, value, status))
                }
                val date = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.CHINA).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                }.format(Date(BuildConfig.BUILD_TIME_MS))
                MiuixUi.addRow(info, information("构建时间", date))
                MiuixUi.addRow(info, information("设备型号", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"))
                MiuixUi.addRow(info, information("Android 版本", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
                MiuixUi.addRow(info, information("加载环境", if (api != null)
                    "当前加载器：${framework ?: "未知"}\n当前 Hook 桥接：libxposed API $api"
                    else "尚未连接 Xposed 框架"))
                content.addView(info)
            }
            if (index == 2) {
                heading("基础设置")
                val basicCard = MiuixUi.card(this)
                fun preference(card: LinearLayout, title: String, value: String, settingsIcon: Boolean = true, clicked: () -> Unit) {
                    val row = (if (settingsIcon) MiuixUi.settingsTitle(this, title) else LinearLayout(this).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(MiuixUi.text(this@MainActivity, title, 16f, p.text))
                    }).apply {
                        minimumHeight = dp(62); setPadding(dp(20), dp(14), dp(20), dp(14))
                        background = MiuixUi.ripple(this@MainActivity)
                        setOnClickListener { clicked() }
                    }
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    row.addView(MiuixUi.text(this, value, 14f, p.secondary))
                    MiuixUi.addRow(card, row)
                }
                val themes = linkedMapOf("system" to "跟随系统", "light" to "亮色模式", "dark" to "暗色模式")
                preference(basicCard, "主题设置", themes[prefs.getString(com.hmodule.config.ModuleConfig.THEME,"system")] ?: "跟随系统") {
                    val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    lateinit var dialog: android.app.Dialog
                    themes.forEach { (key, title) -> options.addView(MiuixUi.text(this,
                        title + if (prefs.getString(com.hmodule.config.ModuleConfig.THEME,"system") == key) "  ✓" else "",
                        16f, if (prefs.getString(com.hmodule.config.ModuleConfig.THEME,"system") == key) p.accent else p.text).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL; minimumHeight = dp(52)
                        background = MiuixUi.ripple(this@MainActivity)
                        setOnClickListener { dialog.dismiss(); prefs.edit().putString(com.hmodule.config.ModuleConfig.THEME,key).apply() }
                    }) }
                    dialog = MiuixUi.showPopup(this,"主题设置",options)
                }
                val launcherRow = LinearLayout(this).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(72)
                    setPadding(dp(20), dp(12), dp(16), dp(12))
                    background = MiuixUi.ripple(this@MainActivity)
                }
                launcherRow.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(MiuixUi.text(this@MainActivity, "隐藏图标", 16f, p.text))
                    addView(MiuixUi.text(this@MainActivity, "隐藏后仍可从 LSPosed 打开", 13f, p.secondary).apply {
                        setPadding(0, dp(6), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val launcherToggle = android.widget.Switch(this).apply {
                    contentDescription = "隐藏图标"
                    isChecked = com.hmodule.ui.LauncherIcon.isHidden(this@MainActivity)
                    MiuixUi.styleSwitch(this)
                    setOnCheckedChangeListener { toggle, checked ->
                        if (!com.hmodule.ui.LauncherIcon.setHidden(this@MainActivity, checked)) {
                            toggle.setOnCheckedChangeListener(null)
                            toggle.isChecked = com.hmodule.ui.LauncherIcon.isHidden(this@MainActivity)
                            android.widget.Toast.makeText(this@MainActivity, "图标设置失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
                            renderPage()
                        }
                    }
                }
                launcherRow.addView(launcherToggle)
                launcherRow.setOnClickListener { launcherToggle.isChecked = !launcherToggle.isChecked }
                MiuixUi.addRow(basicCard, launcherRow)
                content.addView(basicCard)
                heading("配置")
                val configurationCard = MiuixUi.card(this)
                val profileNames = listOf(TargetNames.CN_PACKAGE to cn, TargetNames.OVERSEA_PACKAGE to overseas)
                    .mapNotNull { (pkg, target) -> target?.let {
                        val exact = com.hmodule.adaptation.AdaptationStore.exact(pkg, it.versionName.orEmpty(), it.longVersionCode)
                        val profile = exact ?: com.hmodule.adaptation.AdaptationStore.nearest(pkg, it.versionName.orEmpty())
                        profile?.let { config -> com.hmodule.adaptation.AdaptationStore.fileName(config) +
                            if (exact == null) "（相邻版本）" else "" }
                    } }.distinct()
                MiuixUi.addRow(configurationCard, LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                    addView(MiuixUi.text(this@MainActivity, "配置文件", 16f, p.text))
                    addView(MiuixUi.text(this@MainActivity, profileNames.joinToString("\n").ifEmpty { "未加载 JSON 配置" }, 13f, p.secondary).apply {
                        setPadding(0, dp(6), 0, 0)
                    })
                })
                preference(configurationCard, "清除配置", "", settingsIcon = false) {
                    val confirmation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    confirmation.addView(MiuixUi.text(this,"清除所有功能设置，恢复默认值。",15f,p.text))
                    lateinit var dialog: android.app.Dialog
                    val buttons = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(20), 0, 0)
                    }
                    buttons.addView(MiuixUi.text(this,"取消",16f,p.text,true).apply {
                        gravity = android.view.Gravity.CENTER
                        background = MiuixUi.rounded(this@MainActivity,p.soft,14)
                        setOnClickListener { dialog.dismiss() }
                    }, LinearLayout.LayoutParams(0,dp(48),1f).apply { marginEnd = dp(6) })
                    buttons.addView(MiuixUi.text(this,"清除",16f,
                        if (MiuixUi.isDark(this)) p.page else android.graphics.Color.WHITE,true).apply {
                        gravity = android.view.Gravity.CENTER
                        background = MiuixUi.rounded(this@MainActivity,p.accent,14)
                        setOnClickListener {
                            val ok = com.hmodule.config.ModuleConfig.clear(this@MainActivity,prefs)
                            dialog.dismiss(); renderPage()
                            android.widget.Toast.makeText(this@MainActivity,if(ok) "已清除功能配置" else "清除失败",android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }, LinearLayout.LayoutParams(0,dp(48),1f).apply { marginStart = dp(6) })
                    confirmation.addView(buttons)
                    dialog = MiuixUi.showPopup(this,"清除配置",confirmation,showCloseButton = false)
                }
                content.addView(configurationCard)
                heading("适配信息")
                val adaptation = MiuixUi.card(this)
                MiuixUi.addRow(adaptation, LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = dp(76)
                    setPadding(dp(20), dp(14), dp(20), dp(14))
                    addView(MiuixUi.text(this@MainActivity, "国内版", 16f, p.text))
                    addView(MiuixUi.text(this@MainActivity, "已适配：7.3.2.32、7.3.9.32，其他版本请自测", 13f, p.secondary).apply {
                        setPadding(0, dp(6), 0, 0)
                    })
                    addView(MiuixUi.text(this@MainActivity, "海外版", 16f, p.text).apply {
                        setPadding(0, dp(18), 0, 0)
                    })
                    addView(MiuixUi.text(this@MainActivity, "未专项适配，自测", 13f, p.secondary).apply {
                        setPadding(0, dp(6), 0, 0)
                    })
                })
                content.addView(adaptation)
                heading("项目与更新")
                val actions = MiuixUi.card(this)
                fun action(title: String, clicked: () -> Unit) {
                    MiuixUi.addRow(actions, MiuixUi.text(this, "$title  ›", 16f, p.text).apply {
                        minimumHeight = dp(58)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(20), dp(12), dp(20), dp(12))
                        background = MiuixUi.ripple(this@MainActivity)
                        setOnClickListener { clicked() }
                    })
                }
                action("检查更新", ::manualCheck)
                action("GitHub 仓库") { UpdateChecker.openUrl(this, UpdateChecker.REPO_URL) }
                action("打赏") { com.hmodule.ui.DonationPage.show(this) }
                content.addView(actions)
                updateStatus = MiuixUi.text(this, "", 13f, p.secondary).apply {
                    visibility = View.GONE
                    setPadding(dp(12), dp(12), dp(12), 0)
                }
                content.addView(updateStatus)
            }
            if (index == 1) {
                val features = com.hmodule.ui.FeatureSettings.content(this,prefs) {}
                val body = features.getChildAt(0)
                features.removeView(body)
                body.setPadding(0, 0, 0, dp(28))
                content.addView(body)
            }
            content.setPadding(content.paddingLeft,content.paddingTop,content.paddingRight,
                if (canNavigate) dp(108) else dp(28))
            return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }
        }
        val body = FrameLayout(this)
        val pager = com.hmodule.ui.SwipePageLayout(this).apply { navigationEnabled = canNavigate }
        for (index in if(canNavigate) 0..2 else 0..0) pager.addView(buildContent(index),FrameLayout.LayoutParams(-1,-1))
        pager.selectPage(selectedPage,false)
        body.addView(pager,FrameLayout.LayoutParams(-1,-1))
        if (canNavigate) {
            val nav = com.hmodule.ui.CapsuleNavigation(this) { pager.selectPage(it) }
            nav.setPagePosition(selectedPage.toFloat())
            pager.onPageSelected = { selectedPage = it; nav.setSelectedPage(it) }
            pager.onPagePositionChanged = nav::setPagePosition
            nav.setSelectedPage(selectedPage)
            body.addView(nav,FrameLayout.LayoutParams(minOf(dp(360),resources.displayMetrics.widthPixels-dp(32)),-2,
                android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        }
        page.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(page)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = p.page; window.navigationBarColor = p.page }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (MiuixUi.isDark(this)) 0 else light, light)
        }
        page.requestApplyInsets()
    }

    private fun silentCheck() {
        if (!UpdateChecker.checking && UpdateChecker.latestVersion != null) {
            UpdateChecker.showUpdateDialogIfNeeded(this)
            return
        }
        UpdateChecker.checkUpdate(currentVersion) { latest ->
            if (latest != null) UpdateChecker.showUpdateDialogIfNeeded(this)
        }
    }

    private fun manualCheck() {
        updateStatus.visibility = View.VISIBLE
        updateStatus.text = "正在检查更新…"
        UpdateChecker.checkUpdate(currentVersion) { latest ->
            updateStatus.text = when {
                latest != null -> { UpdateChecker.showUpdateDialogIfNeeded(this); "发现新版本：$latest" }
                UpdateChecker.lastError != null -> "检查更新失败：${UpdateChecker.lastError}"
                else -> "已是最新版本"
            }
        }
    }
}
