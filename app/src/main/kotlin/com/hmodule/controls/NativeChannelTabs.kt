package com.hmodule.controls

import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Uses the host's own indicator and text renderers with visible, original-index endpoints. */
class NativeChannelTabs(
    tabsClass: Class<*>,
    pagerClass: Class<*>,
    private val isChannelPager: (View) -> Boolean,
) {
    private val pagerField = tabsClass.getField("H2")
    private val titlesField = tabsClass.getField("P0")
    private val positionField = tabsClass.getField("c")
    private val offsetField = tabsClass.getField("b")
    private val animationField = tabsClass.getField("M2")
    private val scrollField = tabsClass.getField("z")
    private val container = tabsClass.getMethod("getTabContainer")
    private val selectedPage = pagerClass.getMethod("getCurrentItem")
    private val indicator = tabsClass.getMethod("E", Float::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val text = tabsClass.getMethod("J", Float::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val refreshText = tabsClass.getMethod("H")
    private val verifiedPagers = WeakHashMap<View, WeakReference<View>>()

    private data class Frame(val progress: ChannelTabProgress, val children: ViewGroup)

    private fun pager(tabs: View, policy: ControlPolicy): View? {
        if (!policy.masterEnabled || !policy.hideEnabled || policy.allChannels ||
            policy.hiddenChannels.isEmpty() || ControlTarget.TOP_TABS !in policy.selected) return null
        val pager = pagerField.get(tabs) as? View ?: return null
        if (verifiedPagers[tabs]?.get() !== pager) {
            if (!isChannelPager(pager)) return null
            verifiedPagers[tabs] = WeakReference(pager)
        }
        return pager
    }

    private fun frame(tabs: View, policy: ControlPolicy): Frame? {
        pager(tabs, policy) ?: return null
        if (animationField.getBoolean(tabs)) return null // Keep the native click animator.
        val children = container.invoke(tabs) as? ViewGroup ?: return null
        val titles = titlesField.get(tabs) as? List<*> ?: return null
        if (titles.size != children.childCount || titles.size !in 1..64) return null
        val visible = titles.indices.filter { index ->
            children.getChildAt(index).visibility != View.GONE && ChannelTarget.entries.none {
                it.title == titles[index]?.toString()?.trim() && it in policy.hiddenChannels
            }
        }
        val progress = ChannelTabProgress.at(visible, positionField.getInt(tabs), offsetField.getFloat(tabs)) ?: return null
        return Frame(progress, children)
    }

    /** Replaces native r(): it otherwise interpolates toward a GONE child at index + 1. */
    fun scroll(tabs: View, policy: ControlPolicy): Boolean {
        val frame = frame(tabs, policy) ?: return false
        val (start, end, fraction) = frame.progress
        if (start != end) text.invoke(tabs, fraction, start, end)
        else {
            val original = positionField.getInt(tabs)
            try {
                positionField.setInt(tabs, start)
                refreshText.invoke(tabs)
            } finally { positionField.setInt(tabs, original) }
        }
        indicator.invoke(tabs, fraction, start, end)
        val first = frame.children.getChildAt(start)
        val last = frame.children.getChildAt(end)
        val left = first.left + (last.left - first.left) * fraction
        val target = if (start == 0 && fraction == 0f)
            0 else (left - tabs.width / 3f).toInt().coerceAtLeast(0)
        tabs.scrollTo(target, 0)
        scrollField.setInt(tabs, tabs.scrollX)
        tabs.invalidate()
        return true
    }

    /** Native onDraw calls d() again, so the same mapping must be used there too. */
    fun draw(tabs: View, policy: ControlPolicy): Boolean {
        val progress = frame(tabs, policy)?.progress ?: return false
        indicator.invoke(tabs, progress.fraction, progress.start, progress.end)
        return true
    }

    /** Update visual state only; never navigate or emit another page selection. */
    fun settle(tabs: View, policy: ControlPolicy) {
        val pager = pager(tabs, policy) ?: return
        positionField.setInt(tabs, (selectedPage.invoke(pager) as Number).toInt())
        offsetField.setFloat(tabs, 0f)
    }
}
