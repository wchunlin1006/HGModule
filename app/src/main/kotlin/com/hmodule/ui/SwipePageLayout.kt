package com.hmodule.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.SeekBar
import kotlin.math.abs

/** Native pages follow the finger; the floating navigation is a separate overlay. */
class SwipePageLayout(context: Context) : FrameLayout(context) {
    var navigationEnabled = false
    var onPageSelected: (Int) -> Unit = {}
    var onPagePositionChanged: (Float) -> Unit = {}
    var currentPage = 0
        private set
    private val config = ViewConfiguration.get(context)
    private var startX = 0f
    private var startY = 0f
    private var startOffset = 0f
    private var offset = 0f
    private var tracking = false
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    init { clipChildren = true }
    private fun touchesSlider(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE || x < 0 || y < 0 || x >= view.width || y >= view.height) return false
        if (view is SeekBar) return true
        return view is ViewGroup && (0 until view.childCount).any {
            val child = view.getChildAt(it)
            touchesSlider(child, x + view.scrollX - child.x, y + view.scrollY - child.y)
        }
    }
    private fun begin(event: MotionEvent) {
        animator?.cancel(); animator = null
        startX = event.x; startY = event.y; startOffset = offset
        tracking = navigationEnabled && !touchesSlider(this,event.x,event.y)
        dragging = false
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(event) }
    }
    private fun position(value: Float) {
        offset = value.coerceIn(0f, ((childCount - 1).coerceAtLeast(0) * width).toFloat())
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.translationX = index * width - offset
            val visible = if (width == 0) index == currentPage else abs(child.translationX) < width
            child.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            child.importantForAccessibility = if(index == currentPage) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        onPagePositionChanged(if (width == 0) currentPage.toFloat() else offset / width)
    }
    fun selectPage(index: Int, animate: Boolean = true) {
        val next = index.coerceIn(0,(childCount-1).coerceAtLeast(0))
        animator?.cancel(); animator = null
        val changed = currentPage != next
        currentPage = next
        val destination = next * width.toFloat()
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || width == 0 || abs(offset-destination) < 1f) {
            position(destination)
        } else {
            animator = ValueAnimator.ofFloat(offset,destination).apply {
                duration = 200
                interpolator = PathInterpolator(0.23f,1f,0.32f,1f)
                addUpdateListener { position(it.animatedValue as Float) }
                start()
            }
        }
        if (changed) onPageSelected(next)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed,left,top,right,bottom)
        if (changed) { animator?.cancel(); position(currentPage * width.toFloat()) } else position(offset)
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!navigationEnabled) return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event)
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (!tracking) return false
                val dx = abs(event.x-startX); val dy = abs(event.y-startY)
                if (dy > config.scaledTouchSlop && dy >= dx) tracking = false
                else if (dx > config.scaledTouchSlop && dx > dy*1.5f) {
                    dragging = true; parent?.requestDisallowInterceptTouchEvent(true)
                    position(startOffset-(event.x-startX))
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = false; velocity?.recycle(); velocity = null
                if (!dragging && abs(offset-currentPage*width)>1f) selectPage(currentPage)
            }
        }
        return dragging
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!navigationEnabled) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) begin(event)
        velocity?.addMovement(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if(tracking) {
                val dx = event.x-startX; val dy = abs(event.y-startY)
                if (!dragging && dy > config.scaledTouchSlop && dy >= abs(dx)) tracking = false
                if (tracking && (dragging || abs(dx)>config.scaledTouchSlop && abs(dx)>dy*1.5f)) {
                    dragging = true
                    position(startOffset-dx)
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000,config.scaledMaximumFlingVelocity.toFloat())
                val speed = velocity?.xVelocity ?: 0f
                val delta = offset-startOffset
                val target = if(dragging && (abs(delta)>width*0.25f ||
                    abs(delta)>config.scaledTouchSlop && abs(speed)>MiuixUi.dp(context,600) && speed*delta<0))
                    currentPage + if (delta>0) 1 else -1 else currentPage
                finishGesture(); selectPage(target)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { finishGesture(); selectPage(currentPage) }
        }
        return true
    }
    private fun finishGesture() {
        tracking = false; dragging = false
        velocity?.recycle(); velocity = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(navigationEnabled && keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
            selectPage(currentPage + if(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1,false); return true
        }
        return super.onKeyDown(keyCode,event)
    }
    override fun onDetachedFromWindow() {
        animator?.cancel(); finishGesture(); super.onDetachedFromWindow()
    }
}
