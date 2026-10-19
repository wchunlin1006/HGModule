package com.hmodule.controls

/**
 * Decides where a spoofed membership state is safe to report.
 *
 * Pages that render or synchronise remote content validate the account state with the server, so a
 * spoofed "isVip" answer there leaves them stuck on their loader (white screen). The 我的 message
 * center and its 系统通知 / 粉丝 pages are exactly this kind of page: they ask
 * `NsUserInfoDependImpl.isVip()` while loading, then render a template that expects the matching
 * account data, which a local answer cannot provide. Every other page (player, series, reader,
 * profile) keeps the spoofed value.
 */
object VipScope {
    private val REMOTE_PAGE_PREFIXES = listOf(
        // 消息中心（Lynx 容器）：我的-消息
        "com.dragon.read.pages.bullet.",
        // H5 容器：我的-消息-系统通知、粉丝详情等
        "com.dragon.read.hybrid.webview.",
        // 私信/粉丝会话列表（aweme IM SDK）
        "com.ss.android.ugc.aweme.im.",
    )

    fun spoofAllowed(activityName: String?): Boolean {
        val name = activityName ?: return true
        return REMOTE_PAGE_PREFIXES.none { name.startsWith(it) }
    }
}
