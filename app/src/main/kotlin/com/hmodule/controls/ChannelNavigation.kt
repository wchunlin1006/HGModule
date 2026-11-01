package com.hmodule.controls

object ChannelNavigation {
    /** Keep native indices, redirecting the target before ViewPager publishes a selection. */
    fun destination(titles: List<String>, current: Int, requested: Int, policy: ControlPolicy): Int {
        if (!policy.masterEnabled || !policy.hideEnabled || policy.allChannels ||
            ControlTarget.TOP_TABS !in policy.selected || titles.isEmpty()) return requested
        fun allowed(index: Int): Boolean = ChannelTarget.entries.none {
            it.title == titles[index].trim() && it in policy.hiddenChannels
        }
        val target = requested.coerceIn(titles.indices)
        if (allowed(target)) return target
        val direction = if (target > current) 1 else -1
        var next = target + direction
        while (next in titles.indices) {
            if (allowed(next)) return next
            next += direction
        }
        if (current in titles.indices && allowed(current)) return current
        next = target - direction
        while (next in titles.indices) {
            if (allowed(next)) return next
            next -= direction
        }
        return current.coerceIn(titles.indices)
    }
}
