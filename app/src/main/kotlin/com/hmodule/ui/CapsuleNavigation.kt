package com.hmodule.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/** One moving selection capsule, driven by the pager rather than a second animator. */
class CapsuleNavigation(context: Context, select: (Int) -> Unit) : FrameLayout(context) {
    private val palette = MiuixUi.palette(context)
    private val selector = View(context)
    private val labels = LinearLayout(context)
    private var pagePosition = 0f

    init {
        val inset = MiuixUi.dp(context, 6)
        setPadding(inset, inset, inset, inset)
        elevation = MiuixUi.dp(context, 10).toFloat()
        background = capsule(palette.surface)
        clipToOutline = true
        selector.background = capsule(palette.soft)
        selector.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(selector, LayoutParams(0, 0))
        addView(labels, LayoutParams(-1, -2))
        listOf("首页", "功能", "设置").forEachIndexed { index, title ->
            labels.addView(MiuixUi.text(context, title, 16f, palette.secondary, true).apply {
                gravity = Gravity.CENTER
                minimumHeight = MiuixUi.dp(context, 48)
                background = MiuixUi.ripple(context)
                setOnClickListener { select(index) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = MiuixUi.dp(context, 2); marginEnd = marginStart
            })
        }
    }

    private fun capsule(color: Int) = GradientDrawable().apply {
        setColor(color)
        // Clamped to half the shortest edge at drawing time, including resized layouts.
        cornerRadius = MiuixUi.dp(context, 1000).toFloat()
    }

    fun setSelectedPage(index: Int) {
        for (i in 0 until labels.childCount) labels.getChildAt(i).isSelected = i == index
    }

    fun setPagePosition(position: Float) {
        pagePosition = position.coerceIn(0f, 2f)
        if (labels.width > 0) selector.translationX = pagePosition * labels.width / 3f
        for (index in 0 until labels.childCount) {
            val weight = (1f - abs(pagePosition - index)).coerceIn(0f, 1f)
            fun channel(from: Int, to: Int) = (from + (to - from) * weight).toInt()
            (labels.getChildAt(index) as android.widget.TextView).setTextColor(Color.rgb(
                channel(Color.red(palette.secondary), Color.red(palette.accent)),
                channel(Color.green(palette.secondary), Color.green(palette.accent)),
                channel(Color.blue(palette.secondary), Color.blue(palette.accent))))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val first = labels.getChildAt(0)
        selector.layout(labels.left + first.left, labels.top + first.top,
            labels.left + first.right, labels.top + first.bottom)
        setPagePosition(pagePosition)
    }
}
