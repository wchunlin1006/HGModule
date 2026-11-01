package com.hmodule.controls

import org.junit.Assert.*
import org.junit.Test

class SidebarGameCenterTest {
    class Item(@JvmField val a: Int, @JvmField val b: String)

    @Test fun gameCenterIsFilteredAsOneItemWithoutDeletingTheNativeSource() {
        val game = Item(123, "游戏中心")
        val localizedGame = Item(123, "Games")
        val settings = Item(456, "设置")
        val download = Item(789, "我的下载")
        val native = arrayListOf(settings, game, download, localizedGame)
        val filter = SidebarGameCenter(123)
        assertEquals(listOf(settings, download), filter.filter(native, true))
        assertEquals(4, native.size)
        assertSame(native, filter.filter(native, false))
        assertEquals(listOf(settings, download), filter.filter(native, true))
    }

    @Test fun unrelatedItemsAndNullRowsArePreserved() {
        val native = listOf(Item(456, "设置"), null, "游戏中心")
        assertSame(native, SidebarGameCenter(123).filter(native, true))
        val controller = Any()
        val filter = SidebarGameCenter(123)
        filter.remember(controller); filter.remember(controller)
        assertEquals(listOf(controller), filter.controllers())
    }
}
