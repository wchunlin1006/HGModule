package com.hmodule.controls

import org.junit.Assert.*
import org.junit.Test

class TopSwipeInterceptorTest {
    @Test fun downwardSwipeConsumesUntilUpAndResets() {
        val swipe = TopSwipeInterceptor()
        assertFalse(swipe.onEvent(0,50f,20f,80f,8f))
        assertFalse(swipe.onEvent(2,50f,24f,80f,8f))
        assertTrue(swipe.onEvent(2,52f,50f,80f,8f))
        assertTrue(swipe.justIntercepted)
        assertTrue(swipe.onEvent(2,53f,60f,80f,8f))
        assertFalse(swipe.justIntercepted)
        assertTrue(swipe.onEvent(1,53f,60f,80f,8f))
        assertFalse(swipe.onEvent(2,50f,70f,80f,8f))
    }
    @Test fun clicksHorizontalUpwardAndOutsideStripAreNotConsumed() {
        for ((x,y) in listOf(80f to 22f,50f to 0f,50f to 23f)) {
            val swipe = TopSwipeInterceptor()
            swipe.onEvent(0,50f,20f,80f,8f)
            assertFalse(swipe.onEvent(2,x,y,80f,8f))
            assertFalse(swipe.onEvent(1,x,y,80f,8f))
        }
        val swipe = TopSwipeInterceptor()
        swipe.onEvent(0,50f,120f,80f,8f)
        assertFalse(swipe.onEvent(2,50f,200f,80f,8f))
        swipe.onEvent(0,50f,20f,80f,8f)
        swipe.reset()
        assertFalse(swipe.onEvent(2,50f,100f,80f,8f))
    }
}
