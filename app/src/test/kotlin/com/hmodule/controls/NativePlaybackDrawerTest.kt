package com.hmodule.controls

import android.app.Activity
import android.app.Dialog
import com.dragon.read.component.shortvideo.impl.moredialog.action.a
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativePlaybackDrawerTest {
    class SourceDialog(activity: Activity, var items: List<Any>) : Dialog(activity)

    @Test fun actionIsPreparedBeforeShowWithoutMutatingSourceAndRepeatedPreparationDoesNotDuplicate() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val native = a(Any())
        val source = listOf<Any>(native, "其他操作")
        val dialog = SourceDialog(activity, source)
        var policy = ControlPolicy()
        val drawer = NativePlaybackDrawer({ policy }) {}
        drawer.prepare(dialog)
        assertTrue(drawer.isPrepared(dialog))
        assertFalse(dialog.isShowing)
        assertEquals(2, source.size)
        assertEquals(3, dialog.items.size)
        val added = dialog.items[1] as a
        assertFalse(drawer.owns(native))
        assertTrue(drawer.owns(added))
        assertSame(native.k, added.k)
        assertEquals("清屏播放(果+)", added.title)
        policy = policy.copy(masterEnabled = true, fullClearEnabled = true)
        drawer.prepare(dialog)
        assertEquals(3, dialog.items.size)
        assertSame(added, dialog.items[1])
        assertEquals("退出清屏(果+)", added.title)
    }
}
