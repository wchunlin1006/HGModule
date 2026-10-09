package com.hmodule.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.ColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import com.hmodule.controls.ControlVisibilityManager

/** Shared native settings styling, usable from both the module and the host process. */
object MiuixUi {
    var preferences: android.content.SharedPreferences? = null
    fun isDark(context: Context): Boolean = when (preferences?.getString(com.hmodule.config.ModuleConfig.THEME, "system")) {
        "dark" -> true
        "light" -> false
        else -> context.resources.configuration.uiMode and 0x30 == 0x20
    }
    data class Palette(val page: Int, val surface: Int, val text: Int, val secondary: Int,
        val accent: Int, val soft: Int, val divider: Int, val off: Int)

    fun palette(context: Context): Palette = if (isDark(context))
        Palette(0xff141914.toInt(), 0xff222922.toInt(), 0xfff0f4ef.toInt(), 0xffaab6a9.toInt(),
            0xff91c49b.toInt(), 0xff2b402e.toInt(), 0x18ffffff, 0xff667066.toInt())
    else Palette(0xffeef3ec.toInt(), 0xfffcfdf9.toInt(), 0xff202820.toInt(), 0xff626e61.toInt(),
        0xff356b43.toInt(), 0xffd8e8d5.toInt(), 0x10000000, 0xffb5bfb3.toInt())

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun rounded(context: Context, color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
    }

    fun text(context: Context, value: String, size: Float, color: Int, medium: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun styleSwitch(toggle: Switch) {
        val context = toggle.context
        val p = palette(context)
        toggle.trackTintList = null
        toggle.thumbTintList = null
        // Draw at the device's actual resolution, with explicit inset and antialiasing.
        toggle.trackDrawable = SwitchShape(context, false, p.off, p.accent)
        toggle.thumbDrawable = SwitchShape(context, true, p.off, p.accent)
        toggle.splitTrack = false
        toggle.showText = false
        toggle.switchMinWidth = dp(context, 48)
        toggle.minimumHeight = dp(context, 48)
        toggle.setPadding(dp(context, 4), 0, dp(context, 4), 0)
    }

    private class SwitchShape(context: Context, private val thumb: Boolean, private val off: Int, private val accent: Int) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth() = ((if (thumb) 24 else 48) * density + 0.5f).toInt()
        override fun getIntrinsicHeight() = (28 * density + 0.5f).toInt()
        override fun isStateful() = !thumb
        override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
        override fun draw(canvas: Canvas) {
            paint.color = if (thumb) Color.WHITE else if (android.R.attr.state_checked in state) accent else off
            val rect = RectF(bounds)
            if (thumb) canvas.drawCircle(rect.centerX(), rect.centerY(), (rect.height() - 6 * density) / 2f, paint)
            else canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(context, palette(context).surface)
        clipToOutline = true
    }

    fun addRow(card: LinearLayout, row: View) {
        val context = card.context
        if (card.childCount > 0) card.addView(View(context).apply { setBackgroundColor(palette(context).divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
                marginStart = dp(context, 20); marginEnd = dp(context, 20)
            })
        card.addView(row)
    }

    fun ripple(context: Context) = RippleDrawable(ColorStateList.valueOf(palette(context).divider), null, null)

    fun statusCard(context: Context, title: String, detail: String, badge: String? = null,
        healthy: Boolean = true): FrameLayout {
        val p = palette(context)
        val dark = isDark(context)
        val ink = if (healthy) p.accent else if (dark) 0xffffaaa4.toInt() else 0xffb3261e.toInt()
        return FrameLayout(context).apply {
            background = rounded(context, if (healthy) p.soft else if (dark) 0xff482222.toInt() else 0xfffbe1e0.toInt())
            clipToOutline = true
            minimumHeight = dp(context, if (badge.isNullOrBlank()) 79 else 95)
            addView(object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ink; alpha = 165; style = Paint.Style.STROKE; strokeWidth = dp(context, 6).toFloat()
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                }
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f; val cy = height / 2f
                    val radius = width * 0.40f
                    canvas.drawCircle(cx, cy, radius, paint)
                    if (healthy) canvas.drawPath(Path().apply {
                        moveTo(cx - radius * 0.45f, cy)
                        lineTo(cx - radius * 0.1f, cy + radius * 0.35f)
                        lineTo(cx + radius * 0.5f, cy - radius * 0.35f)
                    }, paint)
                    else {
                        val d = radius * 0.38f
                        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint)
                        canvas.drawLine(cx + d, cy - d, cx - d, cy + d, paint)
                    }
                }
            }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(dp(context, 71), dp(context, 71), Gravity.END or Gravity.BOTTOM).apply {
                    marginEnd = -dp(context, 12); bottomMargin = -dp(context, 14)
                })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(dp(context, 20), dp(context, 10), dp(context, 68), dp(context, 10))
                addView(text(context, title, 22f, ink, true).apply {
                    gravity = Gravity.START; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                }, LinearLayout.LayoutParams(-1,-2))
                if (!badge.isNullOrBlank()) addView(text(context, badge, 13f, if (healthy) p.secondary else ink).apply {
                    gravity = Gravity.START; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setPadding(0, dp(context, 5), 0, 0)
                }, LinearLayout.LayoutParams(-1,-2))
                addView(text(context, detail, 13f, if (healthy) p.secondary else ink).apply {
                    gravity = Gravity.START; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setPadding(0, dp(context, 4), 0, 0)
                }, LinearLayout.LayoutParams(-1,-2))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    fun settingsTitle(context: Context, title: String, size: Float = 16f, medium: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(text(context, title, size, palette(context).text, medium),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette(context).secondary; style = Paint.Style.STROKE
                strokeWidth = resources.displayMetrics.density * 1.5f; strokeJoin = Paint.Join.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val radius = width * 0.38f
                canvas.drawPath(Path().apply {
                    for (i in 0 until 32) {
                        val angle = Math.PI * 2 * i / 32
                        val r = radius * if (i % 4 in 1..2) 1f else 0.78f
                        val x = cx + kotlin.math.cos(angle).toFloat() * r
                        val y = cy + kotlin.math.sin(angle).toFloat() * r
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }, paint)
                canvas.drawCircle(cx, cy, radius * 0.35f, paint)
            }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)).apply { marginStart = dp(context, 7) })
    }

    fun page(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        tag = ControlVisibilityManager.MODULE_UI_TAG
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette(activity).page)
        setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
    }

    fun showPopup(activity: Activity, title: String, content: View, showCloseButton: Boolean = true): Dialog {
        val theme = if (isDark(activity))
            android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar
        val dialog = Dialog(activity, theme).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val panel = LinearLayout(activity).apply {
            tag = com.hmodule.controls.ControlVisibilityManager.MODULE_UI_TAG
            orientation = LinearLayout.VERTICAL
            background = rounded(activity, palette(activity).surface, 24)
            setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 16))
        }
        panel.addView(text(activity, title, 20f, palette(activity).text, true).apply {
            setPadding(0, 0, 0, dp(activity, 14))
        })
        panel.addView(object : android.widget.ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; addView(content) })
        if (showCloseButton) panel.addView(text(activity, "关闭", 16f, palette(activity).accent, true).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(activity, 48)
            setPadding(0, dp(activity, 8), 0, 0)
            background = ripple(activity)
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            val width = minOf(dp(activity, 380), activity.resources.displayMetrics.widthPixels - dp(activity, 40))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.35f }
        }
        return dialog
    }

    fun header(activity: Activity, title: String, onBack: () -> Unit): LinearLayout {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 20), dp(activity, 16))
        }
        header.addView(object : View(activity) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette(activity).text; style = Paint.Style.STROKE
                strokeWidth = resources.displayMetrics.density * 2.2f
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f; val cy = height / 2f
                val dx = resources.displayMetrics.density * 4f
                val dy = resources.displayMetrics.density * 7f
                canvas.drawPath(Path().apply {
                    moveTo(cx + dx, cy - dy); lineTo(cx - dx, cy); lineTo(cx + dx, cy + dy)
                }, paint)
            }
        }.apply {
            contentDescription = "返回"
            background = ripple(activity)
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
        header.addView(text(activity, title, 22f, palette(activity).text, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return header
    }

    fun showPage(activity: Activity, title: String, content: View): Dialog {
        val theme = if (isDark(activity))
            android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar
        val dialog = Dialog(activity, theme).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val page = page(activity)
        page.addView(header(activity, title) { dialog.dismiss() })
        page.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(page)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setBackgroundColor(palette(activity).page)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= 30) {
                setDecorFitsSystemWindows(false)
                insetsController?.show(WindowInsets.Type.systemBars())
                val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                insetsController?.setSystemBarsAppearance(if (isDark(activity)) 0 else light, light)
            }
            @Suppress("DEPRECATION")
            run { statusBarColor = palette(activity).page; navigationBarColor = palette(activity).page }
        }
        page.requestApplyInsets()
        return dialog
    }
}
