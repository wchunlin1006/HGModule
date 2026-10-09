package com.hmodule.controls

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class ControlSettings(private val preferences: SharedPreferences) {
    init {
        val quality = preferences.getString(DefaultQuality.VALUE_KEY, null)
        if (quality in setOf("2160", "1440", "1080plus"))
            preferences.edit().putString(DefaultQuality.VALUE_KEY, DefaultQuality.FHD.key).apply()
        // Consolidate the old independent progress toggle into the selective policy.
        if (preferences.contains("progress_off")) {
            val editor = preferences.edit().remove("progress_off")
            if (preferences.getBoolean("progress_off", false) &&
                !preferences.contains(ControlTarget.PROGRESS.preferenceKey)) {
                editor.putBoolean(ControlTarget.PROGRESS.preferenceKey, true)
                    .putBoolean("control_hide", true)
            }
            editor.apply()
        }
    }
    fun selected(): Set<ControlTarget> = ControlTarget.entries.filterTo(linkedSetOf()) {
        preferences.getBoolean(it.preferenceKey, false)
    }

    fun opacityPercent(): Int = preferences.getInt(OPACITY_KEY, 100).coerceIn(0, 100)

    fun show(activity: Activity, changed: () -> Unit) {
        val p = com.hmodule.ui.MiuixUi.palette(activity)
        fun dp(value: Int) = com.hmodule.ui.MiuixUi.dp(activity, value)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        content.addView(com.hmodule.ui.MiuixUi.text(activity,
            "开启项目即可隐藏对应控件，退出清屏后继续应用这里的选择。", 13f, p.secondary).apply {
            setPadding(dp(12), 0, dp(12), dp(6))
        })
        val selected = selected()
        for ((section, targets) in ControlTarget.entries.groupBy { it.section }) {
            content.addView(com.hmodule.ui.MiuixUi.text(activity, section, 13f, p.accent, true).apply {
                setPadding(dp(12), dp(22), dp(12), dp(10))
            })
            val card = com.hmodule.ui.MiuixUi.card(activity)
            for (target in targets) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(62)
                    setPadding(dp(20), dp(4), dp(16), dp(4))
                    background = com.hmodule.ui.MiuixUi.ripple(activity)
                }
                row.addView(com.hmodule.ui.MiuixUi.text(activity, target.title, 16f, p.text),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val toggle = Switch(activity).apply {
                    contentDescription = target.title
                    com.hmodule.ui.MiuixUi.styleSwitch(this)
                    isChecked = target in selected
                    setOnCheckedChangeListener { _, checked ->
                        preferences.edit().putBoolean(target.preferenceKey, checked).apply()
                        changed()
                    }
                }
                row.addView(toggle)
                row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
                com.hmodule.ui.MiuixUi.addRow(card, row)
            }
            content.addView(card)
        }
        com.hmodule.ui.MiuixUi.showPage(activity, "精简控件", ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
        })
    }

    fun showOpacity(activity: Activity, changed: () -> Unit) {
        val content = LinearLayout(activity).apply {
            tag = ControlVisibilityManager.MODULE_UI_TAG
            orientation = LinearLayout.VERTICAL
            val pad = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val label = TextView(activity)
        label.text = "控件不透明度：${opacityPercent()}%"
        label.textSize = 16f
        label.setTextColor(com.hmodule.ui.MiuixUi.palette(activity).text)
        content.addView(label)
        content.addView(SeekBar(activity).apply {
            max = 100
            val accent = com.hmodule.ui.MiuixUi.palette(activity).accent
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            progress = opacityPercent()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    label.text = "控件不透明度：$value%"
                    if (fromUser) {
                        preferences.edit().putInt(OPACITY_KEY, value).apply()
                        changed()
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        content.addView(TextView(activity).apply {
            text = "100% 为原始显示，0% 为完全透明。仅作用于播放页控件，视频和果+设置面板不受影响。"
            textSize = 12f
            setTextColor(com.hmodule.ui.MiuixUi.palette(activity).secondary)
        })
        com.hmodule.ui.MiuixUi.showPopup(activity, "控件透明度", content)
    }

    fun defaultQuality(): DefaultQuality = DefaultQuality.fromKey(preferences.getString(DefaultQuality.VALUE_KEY, null))

    fun showQuality(activity: Activity, changed: () -> Unit) {
        val ui = com.hmodule.ui.MiuixUi
        val p = ui.palette(activity)
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ui.text(activity, "视频不支持所选画质时，自动回退到可用档位。", 12f, p.secondary).apply {
            setPadding(0, 0, 0, ui.dp(activity, 8))
        })
        lateinit var dialog: Dialog
        for (quality in DefaultQuality.entries) {
            val selected = quality == defaultQuality()
            content.addView(ui.text(activity, quality.title + if (selected) "  ✓" else "", 16f,
                if (selected) p.accent else p.text, selected).apply {
                minimumHeight = ui.dp(activity, 50)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(activity, 12), 0, ui.dp(activity, 12), 0)
                background = ui.ripple(activity)
                setOnClickListener {
                    preferences.edit().putString(DefaultQuality.VALUE_KEY, quality.key).apply()
                    changed()
                    dialog.dismiss()
                }
            })
        }
        dialog = ui.showPopup(activity, "默认画质", content)
    }

    companion object { const val OPACITY_KEY = "guoplus_control_opacity" }
}
