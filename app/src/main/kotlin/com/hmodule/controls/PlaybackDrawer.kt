package com.hmodule.controls

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hmodule.LogUtil

/** Adds an action only to an existing playback action drawer, never to a player root. */
class PlaybackDrawer(
    private val policy: () -> ControlPolicy,
    private val toggle: () -> Unit,
    private val openSettings: () -> Unit = {},
) {
    fun inject(root: View, ownerName: String, dismiss: () -> Unit): Boolean {
        root.findViewWithTag<TextView>(ENTRY_TAG)?.let {
            it.text = policy().drawerTitle()
            it.contentDescription = it.text
            return true
        }
        if (root.findViewWithTag<View>(ControlVisibilityManager.MODULE_UI_TAG) != null) return false
        val texts = texts(root)
        val classHint = ownerName.contains("long", true) || ownerName.contains("press", true)
        val actionCount = ACTION_LABELS.count { label -> texts.any { it == label || it.startsWith(label) } }
        if (actionCount < 2 || (!classHint && texts.none { it.contains("清屏") || it.contains("倍速") })) return false
        val container = findActionContainer(root) ?: return false
        val density = root.resources.displayMetrics.density
        val sample = firstActionText(container)
        val title = TextView(root.context).apply {
            tag = ENTRY_TAG
            text = policy().drawerTitle()
            textSize = sample?.textSize?.div(root.resources.displayMetrics.scaledDensity) ?: 16f
            if (sample != null) setTextColor(sample.currentTextColor)
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = text
        }
        val row = LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            minimumHeight = (52 * density).toInt()
            isClickable = true; isFocusable = true
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(root.context).apply {
                text = "长按进入设置"; textSize = 12f
                if (sample != null) setTextColor(sample.currentTextColor)
                alpha = 0.6f
            })
            setOnClickListener {
                toggle()
                title.text = policy().drawerTitle()
                title.contentDescription = title.text
                dismiss()
            }
            setOnLongClickListener { dismiss(); openSettings(); true }
        }
        container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * density).toInt()))
        LogUtil.info("果+ 播放抽屉入口已注入: $ownerName")
        return true
    }

    private fun findActionContainer(view: View): LinearLayout? {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findActionContainer(view.getChildAt(i))?.let { return it }
        }
        if (view !is LinearLayout || view.orientation != LinearLayout.VERTICAL) return null
        val actions = texts(view)
        if (ACTION_LABELS.count { label -> actions.any { it == label || it.startsWith(label) } } < 2) return null
        // Do not append to the entire screen or the dialog's title/video host.
        if (view.childCount !in 2..12 || view.height > view.resources.displayMetrics.heightPixels * 0.9f) return null
        return view
    }

    private fun texts(view: View): List<String> {
        val result = mutableListOf<String>()
        fun walk(node: View, depth: Int) {
            if (depth > 12 || node.tag == ControlVisibilityManager.MODULE_UI_TAG) return
            if (node is TextView) result.add(node.text?.toString()?.trim().orEmpty())
            if (node is ViewGroup) for (i in 0 until node.childCount) walk(node.getChildAt(i), depth + 1)
        }
        walk(view, 0)
        return result
    }

    private fun firstActionText(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) firstActionText(view.getChildAt(i))?.let { return it }
        return null
    }

    companion object {
        private const val ENTRY_TAG = "GUOPLUS_DRAWER_ACTION"
        private val ACTION_LABELS = setOf("倍速", "清屏", "下载", "不感兴趣", "举报", "定时关闭", "分享", "收藏")
    }
}
