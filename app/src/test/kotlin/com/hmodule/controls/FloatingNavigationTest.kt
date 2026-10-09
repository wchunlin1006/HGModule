package com.hmodule.controls

import android.app.Activity
import android.animation.ValueAnimator
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hmodule.MainActivity
import com.hmodule.UpdateChecker
import com.hmodule.config.ModuleConfig
import com.hmodule.hooks.TargetNames
import com.hmodule.ui.MiuixUi
import com.hmodule.ui.SwipePageLayout
import com.hmodule.ui.CapsuleNavigation
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingNavigationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        UpdateChecker::class.java.getDeclaredField("checking").apply { isAccessible=true }.setBoolean(null,true)
        ModuleConfig.local(app).edit().clear().commit()
        MiuixUi.preferences = null
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).invoke(null,1f)
    }
    @After fun finish() {
        UpdateChecker::class.java.getDeclaredField("checking").apply { isAccessible=true }.setBoolean(null,false)
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).invoke(null,1f)
    }
    private fun views(v: View): List<View> = listOf(v) + if(v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun labels(activity: MainActivity) = views(activity.window.decorView).filter { it.isShown }.filterIsInstance<TextView>()
    private fun install(pkg: String,version: String) {
        Shadows.shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName=pkg; versionName=version; longVersionCode=version.replace(".", "").toLong()
            applicationInfo=ApplicationInfo().apply { packageName=pkg }
        })
    }
    private fun layout(view: View,w: Int=600,h: Int=900) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
        view.layout(0,0,w,h)
    }
    private fun settle() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350)) }
    private fun touch(pager: SwipePageLayout,action: Int,x: Float,y: Float,time: Long) {
        val event=MotionEvent.obtain(1000,time,action,x,y,0)
        pager.dispatchTouchEvent(event); event.recycle()
    }
    private fun pager(): SwipePageLayout {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        return SwipePageLayout(activity).apply {
            navigationEnabled=true
            repeat(3) { addView(ScrollView(activity).apply {
                addView(TextView(activity).apply { text="page $it"; minimumHeight=1800 })
            },FrameLayout.LayoutParams(-1,-1)) }
            selectPage(0,false); layout(this)
        }
    }
    @Test fun dragMovesBothPagesBeforeReleaseAndSnapsToNeighbour() {
        val pager=pager()
        touch(pager,MotionEvent.ACTION_DOWN,500f,200f,1000)
        touch(pager,MotionEvent.ACTION_MOVE,280f,202f,1600)
        assertEquals(-220f,pager.getChildAt(0).translationX,0f)
        assertEquals(380f,pager.getChildAt(1).translationX,0f)
        assertEquals(View.VISIBLE,pager.getChildAt(1).visibility)
        assertEquals(0,pager.currentPage)
        touch(pager,MotionEvent.ACTION_UP,280f,202f,1800); settle()
        assertEquals(1,pager.currentPage)
        assertEquals(0f,pager.getChildAt(1).translationX,0f)
        assertEquals(View.INVISIBLE,pager.getChildAt(0).visibility)
        touch(pager,MotionEvent.ACTION_DOWN,100f,200f,2000)
        touch(pager,MotionEvent.ACTION_MOVE,320f,200f,2600)
        touch(pager,MotionEvent.ACTION_UP,320f,200f,2800); settle()
        assertEquals(0,pager.currentPage)
    }
    @Test fun shortDragCancelVerticalScrollAndLockedPageDoNotSwitch() {
        val pager=pager()
        touch(pager,0,400f,200f,1000); touch(pager,2,360f,200f,1600)
        assertEquals(-40f,pager.getChildAt(0).translationX,0f)
        touch(pager,1,360f,200f,2000); settle()
        assertEquals(0,pager.currentPage); assertEquals(0f,pager.getChildAt(0).translationX,0f)
        touch(pager,0,400f,200f,2200); touch(pager,2,170f,200f,2500)
        touch(pager,3,170f,200f,2700); settle()
        assertEquals(0,pager.currentPage)
        touch(pager,0,400f,200f,2800); touch(pager,2,390f,50f,3000)
        touch(pager,1,390f,50f,3200); settle()
        assertEquals(0f,pager.getChildAt(0).translationX,0f)
        pager.navigationEnabled=false
        touch(pager,0,400f,200f,3400); touch(pager,2,100f,200f,3700); touch(pager,1,100f,200f,4000)
        assertEquals(0,pager.currentPage)
    }
    @Test fun floatingBarRemainsFixedAndClickSelectionPreservesScrollPosition() {
        install(TargetNames.CN_PACKAGE,"7.3.2.32")
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get(); layout(activity.window.decorView)
        val pager=views(activity.window.decorView).filterIsInstance<SwipePageLayout>().single()
        val nav=views(activity.window.decorView).filterIsInstance<CapsuleNavigation>().single()
        assertTrue(nav.parent is FrameLayout)
        assertEquals(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,(nav.layoutParams as FrameLayout.LayoutParams).gravity)
        assertTrue((nav.layoutParams as FrameLayout.LayoutParams).bottomMargin>0)
        assertTrue(nav.elevation>0)
        val home=pager.getChildAt(0) as ScrollView
        home.scrollTo(0,80)
        val homeScroll=home.scrollY
        labels(activity).single { it.text.toString()=="功能" }.performClick(); settle()
        assertEquals(1,pager.currentPage)
        assertEquals(0f,nav.translationX,0f)
        labels(activity).single { it.text.toString()=="首页" }.performClick(); settle()
        assertEquals(homeScroll,home.scrollY)
        controller.pause().stop().destroy()
    }
    @Test fun selectionCapsuleFollowsDragCancellationAndClickAnimation() {
        install(TargetNames.CN_PACKAGE,"7.3.2.32")
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get(); layout(activity.window.decorView)
        val pager=views(activity.window.decorView).filterIsInstance<SwipePageLayout>().single()
        val nav=views(activity.window.decorView).filterIsInstance<CapsuleNavigation>().single()
        val selector=nav.getChildAt(0)
        val stride=nav.getChildAt(1).width/3f
        touch(pager,MotionEvent.ACTION_DOWN,500f,200f,1000)
        touch(pager,MotionEvent.ACTION_MOVE,380f,200f,1600)
        assertEquals(120f/pager.width*stride,selector.translationX,0.1f)
        touch(pager,MotionEvent.ACTION_CANCEL,380f,200f,1800); settle()
        assertEquals(0f,selector.translationX,0.1f)
        pager.selectPage(2)
        val animation=SwipePageLayout::class.java.getDeclaredField("animator").apply { isAccessible=true }
            .get(pager) as ValueAnimator
        animation.currentPlayTime=32
        assertEquals(-pager.getChildAt(0).translationX/pager.width*stride,selector.translationX,0.1f)
        assertTrue(selector.translationX>0f && selector.translationX<2*stride)
        settle(); assertEquals(2*stride,selector.translationX,0.1f)
        controller.pause().stop().destroy()
    }
    @Test fun settingsOrderAndHorizontalConfirmationPreserveCancelAndClearBehaviour() {
        install(TargetNames.CN_PACKAGE,"7.3.2.32")
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get(); layout(activity.window.decorView)
        val pager=views(activity.window.decorView).filterIsInstance<SwipePageLayout>().single()
        pager.selectPage(2,false)
        val text=labels(activity).map { it.text.toString() }
        assertTrue(text.indexOf("外观与配置")<text.indexOf("项目与更新"))
        val prefs=ModuleConfig.local(activity)
        prefs.edit().putBoolean("master_on",true).commit()
        (labels(activity).single { it.text.toString()=="清除配置" }.parent as View).performClick()
        var dialog=ShadowDialog.getLatestDialog()
        var buttons=views(dialog.window!!.decorView).filterIsInstance<TextView>().filter { it.text.toString() in setOf("取消","清除") }
        assertEquals(2,buttons.size)
        assertSame(buttons[0].parent,buttons[1].parent)
        assertEquals(LinearLayout.HORIZONTAL,(buttons[0].parent as LinearLayout).orientation)
        assertFalse(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString()=="关闭" })
        buttons.single { it.text.toString()=="取消" }.performClick()
        assertTrue(prefs.getBoolean("master_on",false)); assertFalse(dialog.isShowing)
        (labels(activity).single { it.text.toString()=="清除配置" }.parent as View).performClick()
        dialog=ShadowDialog.getLatestDialog()
        views(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString()=="清除" }.performClick()
        assertFalse(prefs.getBoolean("master_on",false)); assertFalse(dialog.isShowing)
        controller.pause().stop().destroy()
    }
    @Test fun unsupportedHostBadgeIsBesideVersionTitleAndUsesEmphasis() {
        install(TargetNames.CN_PACKAGE,"9.9.9")
        install(TargetNames.OVERSEA_PACKAGE,"7.3.1.32")
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        val badge=labels(activity).single { it.text.toString()=="未适配" }
        val row=badge.parent as LinearLayout
        assertEquals("红果版本 · 国版",(row.getChildAt(0) as TextView).text.toString())
        assertNotEquals(MiuixUi.palette(activity).secondary,badge.currentTextColor)
        assertNotNull(badge.background)
        controller.pause().stop().destroy()
    }
    @Test fun sharedStatusCardTextsHaveIdenticalLeftEdges() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val card=MiuixUi.statusCard(activity,"模块已激活","模块版本  1.0.1","libxposed API 102")
        layout(card,600,200)
        val texts=views(card).filterIsInstance<TextView>()
        assertEquals(3,texts.size)
        assertEquals(1,texts.map { it.left+it.paddingLeft }.distinct().size)
        assertTrue(texts.all { it.textAlignment==View.TEXT_ALIGNMENT_VIEW_START && it.layoutParams.width==ViewGroup.LayoutParams.MATCH_PARENT })
    }
}
