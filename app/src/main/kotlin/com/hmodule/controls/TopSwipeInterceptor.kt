package com.hmodule.controls

/** Consume only a downward gesture that began in the top strip. */
class TopSwipeInterceptor {
    private var tracking = false
    private var active = false
    private var startX = 0f
    private var startY = 0f
    var justIntercepted = false
        private set
    fun reset() { tracking = false; active = false; justIntercepted = false }
    fun onEvent(action: Int, x: Float, y: Float, topHeight: Float, slop: Float): Boolean {
        justIntercepted = false
        if (action == 0) {
            reset(); tracking = y in 0f..topHeight; startX = x; startY = y
            return false
        }
        if (tracking && action == 2 && !active) {
            val dy = y - startY
            val dx = kotlin.math.abs(x - startX)
            if (dy > slop && dy > dx) { active = true; justIntercepted = true }
            else if (dx > slop || dy < -slop) tracking = false
        }
        val consumed = active
        if (action == 1 || action == 3) reset()
        return consumed
    }
}
