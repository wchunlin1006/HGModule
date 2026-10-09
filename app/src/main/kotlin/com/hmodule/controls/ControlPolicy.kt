package com.hmodule.controls

/** A leaf control, never the whole player overlay or bottom navigation host. */
enum class ControlTarget(val key: String, val title: String, val section: String) {
    TOP_TABS("top_tabs", "频道标签", "顶部入口"),
    SEARCH("search", "搜索入口", "顶部入口"),
    MENU("menu", "侧边菜单", "顶部入口"),
    BACK_EPISODE("back_episode", "返回与集数", "播放操作"),
    PLAYBACK_SPEED("playback_speed", "倍速入口", "播放操作"),
    PLAYER_MORE("player_more", "播放菜单", "播放操作"),
    FULLSCREEN("fullscreen", "全屏观看提示", "播放操作"),
    FULLSCREEN_SWITCH("fullscreen_switch", "全屏切换", "播放操作"),
    EPISODE_SELECTOR("episode_selector", "选集与追剧入口", "播放操作"),
    PROGRESS("progress", "播放进度条", "播放操作"),
    AUTHOR_FOLLOW("author_follow", "作者头像与关注", "互动与弹幕"),
    FAVORITE("favorite", "收藏按钮", "互动与弹幕"),
    COMMENT("comment", "评论按钮", "互动与弹幕"),
    LIKE("like", "点赞按钮", "互动与弹幕"),
    SHARE("share", "分享按钮", "互动与弹幕"),
    DANMAKU_ENTRY("danmaku_entry", "发弹幕按钮", "互动与弹幕"),
    SERIES_INFO("series_info", "标题与作品信息", "短剧信息"),
    AUTHOR_DECLARATION("author_declaration", "作者声明", "短剧信息"),
    HOT_COMMENT("hot_comment", "热门评论", "短剧信息"),
    WATCHING_COUNT("watching_count", "追剧人数", "短剧信息"),
    HOME("home", "首页标签", "底部导航"),
    THEATER("theater", "剧场标签", "底部导航"),
    MALL("mall", "商城标签", "底部导航"),
    EARN("earn", "赚钱标签", "底部导航"),
    PROFILE("profile", "我的标签", "底部导航");

    val preferenceKey: String get() = "guoplus_hide_$key"
    val isBottomTab: Boolean get() = section == "底部导航"
}

data class ControlPolicy(
    val masterEnabled: Boolean = false,
    val hideEnabled: Boolean = false,
    val selected: Set<ControlTarget> = emptySet(),
    val restoreOnPause: Boolean = false,
    val paused: Boolean = false,
    val opacityPercent: Int = 100,
    val fullClearEnabled: Boolean = false,
) {
    /** Navigation remains configurable on every main tab; video effects stay on playback pages. */
    fun forPage(hasPlayer: Boolean): ControlPolicy = if (hasPlayer) this else copy(
        selected = selected.filterTo(linkedSetOf()) { it.isBottomTab },
        fullClearEnabled = false,
        restoreOnPause = false,
        opacityPercent = 100,
    )

    val temporarilyRestored: Boolean get() = cleanScreenEnabled && restoreOnPause && paused
    val cleanScreenEnabled: Boolean get() = masterEnabled && fullClearEnabled
    val clearsAll: Boolean get() = cleanScreenEnabled && !temporarilyRestored
    val opacity: Float get() = opacityPercent.coerceIn(0, 100) / 100f

    fun hides(target: ControlTarget): Boolean =
        masterEnabled && (clearsAll || hideEnabled && target in selected)

    fun alpha(original: Float): Float = if (masterEnabled && !temporarilyRestored) original * opacity else original

    fun drawerTitle(): String = if (cleanScreenEnabled) "退出清屏(果+)" else "清屏播放(果+)"
}
