package com.hmodule.controls

/** Native page indices stay unchanged; only the visible tab animation is compressed. */
data class ChannelTabProgress(val start: Int, val end: Int, val fraction: Float) {
    companion object {
        fun at(visibleIndices: List<Int>, position: Int, offset: Float): ChannelTabProgress? {
            if (visibleIndices.isEmpty()) return null
            if (visibleIndices.size == 1) return ChannelTabProgress(visibleIndices[0], visibleIndices[0], 0f)
            val page = position + (if (offset.isFinite()) offset.coerceIn(0f, 1f) else 0f)
            val endSlot = visibleIndices.indexOfFirst { it > page }
                .let { if (it == -1) visibleIndices.lastIndex else it.coerceAtLeast(1) }
            val start = visibleIndices[endSlot - 1]
            val end = visibleIndices[endSlot]
            return ChannelTabProgress(start, end, ((page - start) / (end - start)).coerceIn(0f, 1f))
        }
    }
}
