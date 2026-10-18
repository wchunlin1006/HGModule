package com.hmodule.controls

/**
 * Allows the preferred resolution to be switched once per host process.
 *
 * The host app keeps the resolution it was switched to until it is closed, so re-applying the native
 * switch for every episode only repeats the app's own "quality changed" hint. The gate lets the first
 * video of a process switch, then reuses the host state for later videos. Changing the module setting
 * resets the gate so the new preference is applied exactly once more.
 *
 * Failed switches must not be recorded, so the next video retries them.
 */
class QualitySwitchGate {

    private var applied: Any? = null

    /** True when [target] still has to be switched in this process. */
    fun shouldApply(target: Any?): Boolean {
        if (target == null) return false
        return synchronized(this) { !sameResolution(applied, target) }
    }

    /** Records a successful native switch. */
    fun markApplied(target: Any?) {
        if (target == null) return
        synchronized(this) { applied = target }
    }

    /** Allows one more switch, e.g. after the preferred quality changed. */
    fun reset() {
        synchronized(this) { applied = null }
    }

    fun appliedTarget(): Any? = synchronized(this) { applied }

    private fun sameResolution(recorded: Any?, target: Any): Boolean {
        if (recorded == null) return false
        if (recorded === target) return true
        return try {
            recorded == target
        } catch (_: Throwable) {
            false
        }
    }
}
