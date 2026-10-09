package com.hmodule.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

enum class DonationCode(val title: String, val fileName: String, val mimeType: String) {
    ALIPAY("支付宝", "alipay.png", "image/png"),
    WECHAT("微信支付", "wechat.png", "image/png");

    val assetPath get() = "donation/$fileName"
}

object DonationImages {
    /** Publish the original image only after the write completes; no storage permission is needed. */
    fun save(context: Context, code: DonationCode): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "GuoPlus-${UUID.randomUUID()}-${code.fileName}")
            put(MediaStore.Images.Media.MIME_TYPE, code.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/GuoPlus")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建相册图片")
        try {
            context.assets.open(code.assetPath).use { input ->
                val output = resolver.openOutputStream(uri) ?: throw IOException("无法写入相册")
                output.use { input.copyTo(it) }
            }
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("无法发布相册图片")
            return uri
        } catch (error: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            throw error
        }
    }
}

object DonationPage {
    fun show(activity: Activity) = MiuixUi.showPage(activity, "打赏", content(activity))

    internal fun content(activity: Activity): LinearLayout {
        val p = MiuixUi.palette(activity)
        fun dp(value: Int) = MiuixUi.dp(activity, value)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // The shared header contributes 16dp below its title; keep the total gap at 25dp.
            setPadding(dp(16), dp(9), dp(16), dp(24))
        }
        body.addView(MiuixUi.text(activity,
            "若果+为你带来了更好的使用体验，欢迎通过打赏支持项目的持续开发、维护与版本适配，你的支持将成为项目不断完善的动力。",
            15f, p.secondary).apply {
            gravity = Gravity.START
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, 0, 0, dp(16))
        })
        val columns = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = View.MeasureSpec.getSize(heightMeasureSpec)
                val height = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    dp(420) else minOf(dp(420), available)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            }
        }.apply { orientation = LinearLayout.HORIZONTAL }
        DonationCode.entries.forEachIndexed { index, code ->
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), dp(12))
            }
            column.addView(MiuixUi.text(activity, code.title, 17f, p.text, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(25))
            })
            val image = ImageView(activity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_START
                contentDescription = "${code.title}收款二维码"
                activity.assets.open(code.assetPath).use { setImageBitmap(BitmapFactory.decodeStream(it)) }
            }
            column.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
            val save = MiuixUi.text(activity, "保存至相册", 15f, p.accent, true).apply {
                gravity = Gravity.CENTER
                background = MiuixUi.rounded(activity, p.soft, 14)
                setOnClickListener { button ->
                    button.isEnabled = false
                    (button as android.widget.TextView).text = "正在保存…"
                    // Keep gallery I/O off the UI thread and prevent duplicate taps while saving.
                    thread(name = "GuoPlus-save-qr") {
                        val saved = runCatching { DonationImages.save(activity.applicationContext, code) }.isSuccess
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                button.isEnabled = true
                                button.text = "保存至相册"
                                Toast.makeText(activity, if (saved) "已保存至相册" else "保存失败，请重试",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            column.addView(save, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
            columns.addView(column, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (index == 0) marginEnd = dp(6) else marginStart = dp(6)
            })
        }
        body.addView(columns, LinearLayout.LayoutParams(
            minOf(dp(760), activity.resources.displayMetrics.widthPixels - dp(32)), 0, 1f
        ))
        return body
    }
}
