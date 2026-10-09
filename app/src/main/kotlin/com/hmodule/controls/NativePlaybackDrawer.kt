package com.hmodule.controls

import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hmodule.LogUtil
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** CN 7.3.2.32: prepare the action list before the native dialog's first layout. */
class NativePlaybackDrawer(private val policy: () -> ControlPolicy, private val openSettings: (View) -> Unit) {
    private var mapping: com.hmodule.hooks.TargetNames.Names? = null
    fun configure(names: com.hmodule.hooks.TargetNames.Names) { mapping = names }
    private val actions = WeakHashMap<Any, WeakReference<Dialog>>()
    private val labelLayouts = WeakHashMap<TextView, ViewGroup.LayoutParams>()

    fun owns(action: Any) = actions.containsKey(action)
    fun isPrepared(dialog: Dialog) = actions.values.any { it.get() === dialog }

    fun prepare(dialog: Dialog) {
        for (field in dialog.javaClass.declaredFields) {
            if (!List::class.java.isAssignableFrom(field.type)) continue
            try {
                field.isAccessible = true
                val data = field.get(dialog) as? List<*> ?: continue
                val existing = data.firstOrNull { it != null && owns(it) }
                if (existing != null) { updateTitle(existing); return }
                val index = data.indexOfFirst { it?.javaClass?.name == (mapping?.drawerActionClass ?: ACTION_CLASS) }
                if (index < 0) continue
                val original = data[index]!!
                val provider = field(original, mapping?.drawerProviderField ?: "k") ?: continue
                val action = original.javaClass.declaredConstructors.single { it.parameterCount == 1 }.newInstance(provider)
                updateTitle(action)
                val prepared = ArrayList(data)
                prepared.add(index + 1, action)
                actions[action] = WeakReference(dialog)
                field.set(dialog, prepared)
                LogUtil.info("果+ 原生播放抽屉入口已在首次布局前准备")
                return
            } catch (e: Throwable) {
                LogUtil.warn("果+ 原生抽屉准备失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun bind(holder: Any, action: Any) {
        val row = field(holder, "itemView") as? ViewGroup ?: return
        val hint = row.findViewWithTag<TextView>(HINT_TAG)
        val titleId = row.resources.getIdentifier(mapping?.drawerTitleResource ?: "action_text", "id", row.context.packageName)
        val title = row.findViewById<TextView>(titleId) ?: return
        if (!owns(action)) {
            if (hint != null) {
                hint.visibility = View.GONE
                row.setOnLongClickListener(null)
                row.isLongClickable = false
                labelLayouts.remove(title)?.let { title.layoutParams = it }
            }
            return
        }
        title.text = policy().drawerTitle()
        val label = hint ?: TextView(row.context).apply {
            tag = HINT_TAG; id = View.generateViewId()
            text = "长按进入设置"; textSize = 12f
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { addHint(row, title, it) }
        label.setTextColor(title.currentTextColor)
        label.alpha = 0.6f
        label.visibility = View.VISIBLE
        constrainTitle(row, title, label)
        row.contentDescription = "${policy().drawerTitle()}，长按进入设置"
        row.setOnLongClickListener {
            actions[action]?.get()?.dismiss()
            openSettings(row)
            true
        }
    }

    private fun addHint(row: ViewGroup, title: TextView, hint: TextView) {
        val density = row.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        if (row.javaClass.name.endsWith("ConstraintLayout") && title.parent === row) {
            val paramsClass = title.layoutParams.javaClass
            val lp = paramsClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) as ViewGroup.MarginLayoutParams
            paramsClass.getField("endToEnd").setInt(lp, 0)
            paramsClass.getField("topToTop").setInt(lp, 0)
            paramsClass.getField("bottomToBottom").setInt(lp, 0)
            lp.marginEnd = dp(16)
            row.addView(hint, lp)
        } else if (row is LinearLayout) {
            row.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL; marginStart = dp(12)
            })
        } else if (row is FrameLayout) {
            row.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) })
        }
    }

    private fun constrainTitle(row: ViewGroup, title: TextView, hint: TextView) {
        if (!row.javaClass.name.endsWith("ConstraintLayout") || title.parent !== row) return
        val original = title.layoutParams
        val paramsClass = original.javaClass
        if (title !in labelLayouts) labelLayouts[title] = paramsClass.getConstructor(ViewGroup.LayoutParams::class.java)
            .newInstance(original) as ViewGroup.LayoutParams
        paramsClass.getField("endToStart").setInt(original, hint.id)
        paramsClass.getField("endToEnd").setInt(original, -1)
        original.width = 0
        title.layoutParams = original
    }

    private fun updateTitle(action: Any) {
        action.javaClass.getMethod(mapping?.drawerTitleMethod ?: "g", String::class.java).invoke(action, policy().drawerTitle())
    }

    private fun field(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(instance) }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        return null
    }

    companion object {
        const val ACTION_CLASS = "com.dragon.read.component.shortvideo.impl.moredialog.action.a"
        private const val HINT_TAG = "GUOPLUS_NATIVE_DRAWER_HINT"
    }
}
