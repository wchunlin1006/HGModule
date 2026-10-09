package com.dragon.read.widget

import android.content.Context
import android.widget.LinearLayout
import android.view.View

/** Target layout counts VISIBLE children, so suppress the host when hiding all four. */
class LimitHeightByChildrenConstraintLayout(context: Context) : LinearLayout(context) {
    var measurementGuard: ((() -> Unit) -> Unit) = { it() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measurementGuard {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            // Mirrors the target's VISIBLE-only counting branch with a 50px slot.
            val count = (0 until childCount).count { getChildAt(it).visibility == View.VISIBLE }
            setMeasuredDimension(measuredWidth, count * 50)
        }
    }
}
