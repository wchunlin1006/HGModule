package com.hmodule.ui

import android.app.Activity
import android.app.Dialog
import android.content.SharedPreferences
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.hmodule.controls.ControlSettings

/** Shared page: edits the same configuration from LSPosed and inside the host. */
object FeatureSettings {
    fun content(activity: Activity, prefs: SharedPreferences, changed: () -> Unit): ScrollView {
        val ui = MiuixUi
        val p = ui.palette(activity)
        val settings = ControlSettings(prefs)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(activity, 16), 0, ui.dp(activity, 16), ui.dp(activity, 28))
        }
        var group = ui.card(activity)
        fun section(title: String) {
            content.addView(ui.text(activity, title, 13f, p.accent, true).apply {
                setPadding(ui.dp(activity, 12), ui.dp(activity, 22), 0, ui.dp(activity, 10))
            })
            group = ui.card(activity); content.addView(group)
        }
        fun toggle(title: String, description: String, key: String, action: (() -> Unit)? = null): Pair<LinearLayout, TextView> {
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(activity, 72)
                setPadding(ui.dp(activity, 20), ui.dp(activity, 12), ui.dp(activity, 16), ui.dp(activity, 12))
                background = ui.ripple(activity)
            }
            val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(if (action == null) ui.text(activity, title, 16f, p.text)
                else ui.settingsTitle(activity, title))
            val summary = ui.text(activity, description, 12f, p.secondary).apply { setPadding(0, ui.dp(activity, 6), 0, 0) }
            labels.addView(summary)
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = Switch(activity).apply {
                contentDescription = title; ui.styleSwitch(this)
                isChecked = prefs.getBoolean(key, false)
                setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean(key, enabled).apply(); changed() }
            }
            row.addView(sw)
            row.setOnClickListener { if (action != null) action() else sw.isChecked = !sw.isChecked }
            ui.addRow(group, row)
            return row to summary
        }
        section("模块控制")
        toggle("总开关", "所用功能的总控制开关", "master_on")
        section("界面精简")
        toggle("隐藏状态栏", "视频页面沉浸显示", "status_bar")
        toggle("精简控件", "点击配置要隐藏的控件；开关控制整体启停", "control_hide") { settings.show(activity, changed) }
        toggle("清屏播放", "隐藏播放页所有控件；退出后保留自定义隐藏规则", "guoplus_clean_screen")
        val opacity = ui.settingsTitle(activity, "控件透明度").apply {
            setPadding(ui.dp(activity, 20), ui.dp(activity, 18), ui.dp(activity, 20), ui.dp(activity, 18))
            background = ui.ripple(activity)
        }
        opacity.addView(android.view.View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        val value = ui.text(activity, "${settings.opacityPercent()}%", 15f, p.secondary)
        opacity.addView(value)
        opacity.setOnClickListener { settings.showOpacity(activity) { value.text = "${settings.opacityPercent()}%"; changed() } }
        ui.addRow(group, opacity)
        toggle("隐藏小白条", "隐藏系统手势导航提示条", "nav_bar_off")
        toggle("暂停时退出清屏", "在清屏播放（果+）时，暂停播放临时退出清屏", "restore_controls_pause")
        section("播放与手势")
        lateinit var quality: Pair<LinearLayout, TextView>
        quality = toggle("默认画质", "当前 ${settings.defaultQuality().title}", "max_quality") {
            settings.showQuality(activity) {
                quality.second.text = "当前 ${settings.defaultQuality().title}"
                (quality.first.getChildAt(1) as Switch).isChecked = true
                changed()
            }
        }
        fun speedLabel() = "当前 ${prefs.getFloat("default_speed_value", 1f)}x"
        lateinit var speed: Pair<LinearLayout, TextView>
        speed = toggle("默认倍速", speedLabel(), "default_speed") {
            val options = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            lateinit var dialog: Dialog
            for (v in floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)) {
                options.addView(ui.text(activity, "${v}x", 16f, p.text).apply {
                    minimumHeight = ui.dp(activity, 50); gravity = Gravity.CENTER_VERTICAL
                    background = ui.ripple(activity)
                    setOnClickListener {
                        prefs.edit().putFloat("default_speed_value", v).putBoolean("default_speed", true).apply()
                        speed.second.text = speedLabel(); (speed.first.getChildAt(1) as Switch).isChecked = true
                        changed(); dialog.dismiss()
                    }
                })
            }
            dialog = ui.showPopup(activity, "默认倍速", options)
        }
        toggle("双击打开评论区", "替换原双击点赞动作，双击直接打开评论", "double_tap_comment")
        toggle("禁用下拉刷新", "禁用下拉手势，并折叠下拉刷新提示区域", "pull_refresh")
        toggle("顶部下滑拦截", "拦截从屏幕顶部（状态栏）区域向下滑的手势", "top_zone")
        section("内容与账号")
        toggle("拦截广告 / 挂件", "拦截已适配的广告层、金宝箱和悬浮挂件", "ad_block")
        toggle("解锁 VIP", "启用已适配的 VIP 状态 Hook", "vip_unlock")
        toggle("显示 VIP 图标", "控制 VIP 图标相关显示逻辑", "vip_icon")
        return ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(content) }
    }
}
