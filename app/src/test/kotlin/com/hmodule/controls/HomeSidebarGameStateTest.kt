package com.hmodule.controls

import org.junit.Assert.*
import org.junit.Test

class HomeSidebarGameStateTest {
    interface State {
        fun getValue(): Any?
        fun setValue(value: Any?)
    }
    class ObservableState(var stored: Any?) : State {
        var reads = 0
        override fun getValue(): Any? { reads++; return stored }
        override fun setValue(value: Any?) { this.stored = value }
    }

    @Test fun hidingObservesBothStatesAndRestoresTheLatestNativeData() {
        val flag = ObservableState(false)
        val original = Any()
        val empty = Any()
        val native = ObservableState(original)
        val mask = HomeSidebarGameState(State::class.java, flag, empty)
        val presented = mask.wrap(native) as State
        assertSame(original, presented.getValue())
        mask.setEnabled(true)
        assertSame(empty, presented.getValue())
        assertSame(original, native.stored)
        assertEquals(2, flag.reads)
        assertEquals(2, native.reads)
        val latest = Any()
        presented.setValue(latest)
        assertSame(latest, native.stored)
        assertSame(empty, presented.getValue())
        mask.setEnabled(false)
        assertSame(latest, presented.getValue())
    }

    @Test fun multipleSidebarsShareTheSwitchWithoutMixingTheirNativeData() {
        val flag = ObservableState(true)
        val empty = Any()
        val mask = HomeSidebarGameState(State::class.java, flag, empty)
        val first = mask.wrap(ObservableState("first")) as State
        val second = mask.wrap(ObservableState("second")) as State
        assertSame(empty, first.getValue())
        assertSame(empty, second.getValue())
        assertEquals(first, first)
        assertNotEquals(first, second)
        mask.setEnabled(false)
        assertEquals("first", first.getValue())
        assertEquals("second", second.getValue())
    }
}
