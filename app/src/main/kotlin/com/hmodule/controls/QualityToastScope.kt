package com.hmodule.controls

/** Suppress only the toast emitted inside a resolution callback, preserving its other work. */
class QualityToastScope {
    private val depth = ThreadLocal<Int>()
    val active: Boolean get() = (depth.get() ?: 0) > 0
    fun <T> during(block: () -> T): T {
        val previous = depth.get() ?: 0
        depth.set(previous + 1)
        return try { block() } finally {
            if (previous == 0) depth.remove() else depth.set(previous)
        }
    }
}
