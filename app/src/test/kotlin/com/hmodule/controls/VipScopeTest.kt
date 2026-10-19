package com.hmodule.controls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VipScopeTest {
    @Test fun remoteContentPagesKeepTheRealMembershipState() {
        // 我的-消息 runs in this Lynx container; a spoofed isVip answer left it spinning.
        assertFalse(VipScope.spoofAllowed("com.dragon.read.pages.bullet.BulletActivity"))
        assertFalse(VipScope.spoofAllowed("com.dragon.read.pages.bullet.AnotherPage"))
        // 我的-消息-系统通知 / 粉丝详情 open inside the H5 container.
        assertFalse(VipScope.spoofAllowed("com.dragon.read.hybrid.webview.WebViewActivity"))
        // 我的-消息-粉丝 opens the IM session list.
        assertFalse(VipScope.spoofAllowed("com.ss.android.ugc.aweme.im.saas.ui.SessionListActivity"))
    }

    @Test fun everyOtherPageKeepsTheSpoofedValue() {
        assertTrue(VipScope.spoofAllowed("com.dragon.read.pages.main.MainFragmentActivity"))
        assertTrue(VipScope.spoofAllowed("com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"))
        assertTrue(VipScope.spoofAllowed("com.dragon.read.component.biz.impl.mine.settings.SettingsActivity"))
        // A page that does not exist yet keeps the previous behaviour.
        assertTrue(VipScope.spoofAllowed(null))
        // Only the exact package prefixes are excluded.
        assertTrue(VipScope.spoofAllowed("com.dragon.read.pages.bulletproof.Activity"))
        assertTrue(VipScope.spoofAllowed("com.dragon.read.hybrid.webviewlite.Activity"))
    }
}
