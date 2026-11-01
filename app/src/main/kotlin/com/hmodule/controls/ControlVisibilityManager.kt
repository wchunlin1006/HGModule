package com.hmodule.controls

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hmodule.LogUtil
import java.util.WeakHashMap

/** Tracks native control state; full clear also suppresses safe overlay hosts. */
class ControlVisibilityManager(private val mutate: (() -> Unit) -> Unit) {
    private data class State(
        var target: ControlTarget,
        var nativeVisibility: Int,
        var nativeAlpha: Float,
        var hidden: Boolean = false,
    )

    private data class ParentState(var visibility: Int, var alpha: Float, val x: Float, val y: Float)
    private data class BackdropState(val background: Drawable?, var visibility: Int, var alpha: Float)
    private data class RowShift(var nativeY: Float, var offset: Float)
    private data class ChannelState(var channel: ChannelTarget, var visibility: Int)

    private val controls = WeakHashMap<View, State>()
    private val channelViews = WeakHashMap<View, ChannelState>()
    private val clearHosts = WeakHashMap<View, Int>()
    private val opacityHosts = WeakHashMap<View, Float>()
    private val interactionHosts = WeakHashMap<View, Int>()
    private val tabLayouts = WeakHashMap<View, ViewGroup.LayoutParams>()
    private val automaticBottomTabs = WeakHashMap<View, Boolean>()
    private val pauseParents = WeakHashMap<View, ParentState>()
    private val backdrops = WeakHashMap<View, BackdropState>()
    private val rowShifts = WeakHashMap<View, RowShift>()
    private val infoHosts = WeakHashMap<View, Int>()
    private val layoutWatched = WeakHashMap<View, Boolean>()
    private val resourceNames = WeakHashMap<View, Pair<Int, String>>()
    private val unsupportedBottomLayouts = mutableSetOf<String>()
    private val interactionLayoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val host = if (view is ViewGroup && isInteractionGroup(view)) view else view.parent as? ViewGroup
        if (host != null) {
            if (isInteractionGroup(host)) reflowInteractionGroup(host)
            else for (index in 0 until host.childCount) {
                val child = host.getChildAt(index) as? ViewGroup ?: continue
                if (isInteractionGroup(child)) reflowInteractionGroup(child)
            }
        }
    }
    private val seen = mutableSetOf<ControlTarget>()
    var policy = ControlPolicy()
    var profileId: String = ""
    var resourceOverrides: Map<String, String> = emptyMap()
    var clearHostNames: Set<String> = emptySet()
    var bottomBackdropName: String = ""
    var mainNavNames: Set<String> = emptySet()
    private var applying = false
    private var lastKeepDanmaku: Boolean? = null

    fun bind(view: View, target: ControlTarget) {
        if (view in controls) return
        var parent = view.parent as? View
        while (parent != null) {
            if (controls[parent]?.target == target) return
            parent = parent.parent as? View
        }
        controls[view] = State(target, view.visibility, view.alpha)
        if (seen.add(target)) LogUtil.info("果+ 控件识别: ${target.key} id=${entryName(view)} class=${view.javaClass.name}")
    }

    fun sync(root: View) {
        if (applying) return
        applying = true
        try {
            if (!policy.clearsAll || lastKeepDanmaku != policy.keepDanmaku) {
                restoreClearHosts(); restoreBackdrops()
            }
            lastKeepDanmaku = policy.keepDanmaku
            refreshBottomTabBindings(root)
            discover(root)
            syncChannels(root)
            syncOpacityHosts(root)
            syncInteractionHosts(root)
            if (!policy.temporarilyRestored) restorePauseParents()
            for ((view, state) in controls.entries.toList()) {
                if (inside(view, root)) {
                    if (policy.temporarilyRestored && !policy.hides(state.target) && state.nativeVisibility == View.VISIBLE &&
                        state.nativeAlpha > 0f && !state.target.isBottomTab) restoreSuppressedParents(view, root)
                    apply(view, state)
                }
            }
            syncInfoHosts(root)
            reflowInfoGroups(root)
            normalizeSeriesBanners(root)
            reflowInteractions(root)
            reflowBottomTabs(root)
            if (policy.clearsAll) hideClearHosts(root)
        } finally { applying = false }
    }

    fun requestedVisibility(view: View, requested: Int): Int {
        if (applying) return requested
        backdrops[view]?.let { it.visibility = requested; return if (policy.clearsAll) View.GONE else View.VISIBLE }
        if (view in infoHosts) { infoHosts[view] = requested; return View.GONE }
        if (view in clearHosts) { clearHosts[view] = requested; return clearedVisibility(view) }
        if (view in interactionHosts) { interactionHosts[view] = requested; return View.INVISIBLE }
        channelViews[view]?.let { state ->
            state.visibility = requested
            controls[view]?.nativeVisibility = requested
            return channelVisibility(state)
        }
        pauseParents[view]?.let { it.visibility = requested; return View.VISIBLE }
        val state = controls[view] ?: return requested
        state.nativeVisibility = requested
        return when {
            policy.hides(state.target) && (view.parent as? View) !in interactionHosts -> hiddenVisibility(state.target)
            else -> requested
        }
    }

    private fun hasTopTabSiblings(view: View): Boolean {
        val parent = view.parent as? ViewGroup ?: return false
        var count = 0
        fun walk(node: View, depth: Int) {
            if (depth > 3) return
            if (node is TextView && node.text?.toString()?.trim() in TOP_TAB_LABELS) count++
            if (node is ViewGroup) for (i in 0 until node.childCount) walk(node.getChildAt(i), depth + 1)
        }
        walk(parent, 0)
        return count >= 2
    }

    fun requestedAlpha(view: View, requested: Float): Float {
        if (applying) return requested
        backdrops[view]?.let { it.alpha = requested; return 1f }
        if (view in opacityHosts) { opacityHosts[view] = requested; return policy.alpha(requested) }
        pauseParents[view]?.let { it.alpha = requested; return 1f }
        val state = controls[view] ?: return requested
        state.nativeAlpha = requested
        return desiredAlpha(view, requested)
    }

    fun requestedTranslationY(view: View, requested: Float): Float {
        if (applying) return requested
        val shift = rowShifts[view] ?: return requested
        shift.nativeY = requested
        return requested + shift.offset
    }

    fun restoreAll() {
        applying = true
        try {
            for ((view, state) in controls.entries.toList()) mutate {
                view.visibility = state.nativeVisibility
                view.alpha = state.nativeAlpha
                state.hidden = false
            }
            restorePauseParents()
            restoreClearHosts()
            restoreBackdrops()
            restoreOpacityHosts()
            for ((view, visibility) in interactionHosts.entries.toList()) mutate { view.visibility = visibility }
            interactionHosts.clear()
            restoreTabLayouts()
            restoreInfoHosts()
            restoreRowShifts()
            for ((view, state) in channelViews.entries.toList()) mutate { view.visibility = state.visibility }
            channelViews.clear()
        } finally { applying = false }
    }

    fun clear() { restoreAll(); controls.clear(); automaticBottomTabs.clear(); seen.clear(); resourceNames.clear(); unsupportedBottomLayouts.clear() }

    private fun nativeVisibility(view: View): Int = controls[view]?.nativeVisibility ?: infoHosts[view] ?:
        clearHosts[view] ?: interactionHosts[view] ?: view.visibility

    private fun isInteractionGroup(host: ViewGroup): Boolean =
        (host.javaClass.name.endsWith(".LimitHeightByChildrenConstraintLayout") ||
            host is LinearLayout && host.orientation == LinearLayout.VERTICAL) &&
            (0 until host.childCount).count { controls[host.getChildAt(it)]?.target in INTERACTION_TARGETS } >= 2

    private fun reflowInteractions(root: View) {
        val hosts = controls.entries.filter { it.value.target in INTERACTION_TARGETS && inside(it.key, root) }
            .mapNotNull { it.key.parent as? ViewGroup }.distinct()
        for (host in hosts) {
            if (!isInteractionGroup(host)) continue
            val siblings = authorSiblings(host)
            for (view in listOf(host) + (0 until host.childCount).map(host::getChildAt) + siblings +
                if (siblings.isEmpty()) emptyList() else listOfNotNull(host.parent as? View)) {
                if (layoutWatched.put(view, true) == null) view.addOnLayoutChangeListener(interactionLayoutListener)
            }
            reflowInteractionGroup(host)
        }
    }

    /** Keep the native measuring slots, but pack visible hit/draw bounds against their original bottom. */
    private fun reflowInteractionGroup(host: ViewGroup) {
        if (!isInteractionGroup(host)) return
        packInteractionRows(host)
        reflowAuthorSiblings(host)
    }

    private fun originalTranslationY(view: View) = rowShifts[view]?.nativeY ?: view.translationY

    private fun shiftRow(view: View, offset: Float) {
        val original = originalTranslationY(view)
        if (offset == 0f) rowShifts.remove(view) else rowShifts[view] = RowShift(original, offset)
        mutate { if (view.translationY != original + offset) view.translationY = original + offset }
    }

    private fun authorSiblings(host: ViewGroup): List<View> {
        val parent = host.parent as? ViewGroup ?: return emptyList()
        return (0 until parent.childCount).map(parent::getChildAt).filter {
            controls[it]?.target == ControlTarget.AUTHOR_FOLLOW && insidePlayerRightPanel(it)
        }
    }

    /** The host places its avatar/follow composition beside, rather than inside, the button stack. */
    private fun reflowAuthorSiblings(host: ViewGroup) {
        val authors = authorSiblings(host)
        if (authors.isEmpty()) return
        val rows = (0 until host.childCount).map(host::getChildAt)
            .filter { nativeVisibility(it) == View.VISIBLE && it.height > 0 }
        val hasHidden = rows.any { controls[it]?.let { state -> policy.hides(state.target) } == true }
        val nativeAuthors = authors.filter { nativeVisibility(it) == View.VISIBLE && it.height > 0 }
        if (!hasHidden || rows.isEmpty() || nativeAuthors.isEmpty() || policy.clearsAll ||
            policy.hides(ControlTarget.AUTHOR_FOLLOW)) {
            authors.forEach { shiftRow(it, 0f) }; return
        }
        val nativeBottom = nativeAuthors.maxOf { it.bottom + originalTranslationY(it) }
        val nativeHostY = host.top + originalTranslationY(host)
        val nativeFirstTop = rows.minOf { nativeHostY + it.top + originalTranslationY(it) }
        val gap = (nativeFirstTop - nativeBottom).coerceAtLeast(0f)
        val visible = rows.filter { controls[it]?.let { state -> policy.hides(state.target) } != true }
        val bottom = if (visible.isEmpty()) {
            rows.maxOf { nativeHostY + it.bottom + originalTranslationY(it) }
        } else {
            visible.minOf { host.top + host.translationY + it.top + it.translationY } - gap
        }
        for (author in authors) shiftRow(author, if (author in nativeAuthors) bottom - nativeBottom else 0f)
    }

    private fun packInteractionRows(host: ViewGroup) {
        val children = (0 until host.childCount).map(host::getChildAt)
        val hasHidden = children.any { view ->
            controls[view]?.let { it.target in INTERACTION_TARGETS && policy.hides(it.target) &&
                it.nativeVisibility == View.VISIBLE } == true
        }
        if (!hasHidden || policy.clearsAll || host.visibility != View.VISIBLE) {
            restoreRowShifts(host); return
        }
        fun originalY(view: View) = rowShifts[view]?.nativeY ?: view.translationY
        val rows = children.filter { nativeVisibility(it) == View.VISIBLE && it.height > 0 }
            .sortedBy { it.top + originalY(it) }
        if (rows.isEmpty()) return
        val visible = rows.filter { controls[it]?.let { state -> policy.hides(state.target) } != true }
        if (visible.isEmpty()) { restoreRowShifts(host); return }
        var bottom = rows.maxOf { it.bottom + originalY(it) }
        var nextTop: Float? = null
        val offsets = mutableMapOf<View, Float>()
        for (row in visible.asReversed()) {
            val index = rows.indexOf(row)
            val gap = rows.getOrNull(index + 1)?.let {
                (it.top + originalY(it) - row.bottom - originalY(row)).coerceAtLeast(0f)
            } ?: 0f
            if (nextTop != null) bottom = nextTop - gap
            offsets[row] = bottom - row.bottom - originalY(row)
            nextTop = bottom - row.height
        }
        for (row in children) {
            val offset = offsets[row] ?: 0f
            val original = originalY(row)
            if (offset == 0f) {
                rowShifts.remove(row)
                mutate { if (row.translationY != original) row.translationY = original }
            } else {
                rowShifts[row] = RowShift(original, offset)
                mutate { if (row.translationY != original + offset) row.translationY = original + offset }
            }
        }
    }

    private fun restoreRowShifts(host: ViewGroup? = null) {
        for ((view, shift) in rowShifts.entries.toList()) if (host == null || view.parent === host) {
            mutate { view.translationY = shift.nativeY }
            rowShifts.remove(view)
        }
    }

    private fun syncInfoHosts(root: View) {
        // Full clear suppresses outer hosts separately; do not save nested GONE states there.
        if (policy.clearsAll) { restoreInfoHosts(); return }
        val candidates = linkedSetOf<ViewGroup>()
        for ((view, state) in controls.entries.toList()) if (state.target in INFO_TARGETS && inside(view, root)) {
            var parent = view.parent as? ViewGroup
            repeat(4) {
                val host = parent ?: return@repeat
                if (host === root || host.javaClass.name.contains("Recycler") ||
                    host.javaClass.name.contains("Pager") || isPlaybackOverlay(host) ||
                    containsVideoSurface(host)) { parent = null; return@repeat }
                if (host !in controls) candidates.add(host)
                parent = if (host is LinearLayout && host.orientation == LinearLayout.VERTICAL) null else host.parent as? ViewGroup
            }
        }
        fun hasKeptContent(view: View): Boolean {
            if (nativeVisibility(view) != View.VISIBLE) return false
            controls[view]?.let { return !policy.hides(it.target) }
            return if (view is ViewGroup) (0 until view.childCount).any { hasKeptContent(view.getChildAt(it)) } else true
        }
        fun hasHiddenInfo(view: View): Boolean {
            if (nativeVisibility(view) != View.VISIBLE) return false
            controls[view]?.let { if (it.target in INFO_TARGETS && policy.hides(it.target)) return true }
            return view is ViewGroup && (0 until view.childCount).any { hasHiddenInfo(view.getChildAt(it)) }
        }
        val hidden = candidates.filter { nativeVisibility(it) == View.VISIBLE && hasHiddenInfo(it) && !hasKeptContent(it) }.toSet()
        for ((host, visibility) in infoHosts.entries.toList()) if (host !in hidden && inside(host, root)) {
            mutate { host.visibility = visibility }; infoHosts.remove(host)
        }
        for (host in hidden) {
            if (host !in infoHosts) infoHosts[host] = host.visibility
            mutate { if (host.visibility != View.GONE) host.visibility = View.GONE }
        }
    }

    private fun restoreInfoHosts() {
        for ((view, visibility) in infoHosts.entries.toList()) mutate { view.visibility = visibility }
        infoHosts.clear()
    }

    /**
     * Some player information containers use absolute/constraint positioning instead of
     * a measuring parent that reacts to GONE. Keep the declaration slot measurable and
     * move the content above it into that released visual space.
     */
    private fun reflowInfoGroups(root: View) {
        val parents = controls.entries.filter {
            it.value.target in INFO_TARGETS && inside(it.key, root)
        }.mapNotNull { it.key.parent as? ViewGroup }.distinct()
        for (host in parents) {
            if (host === root || host.javaClass.name.contains("Recycler") ||
                host.javaClass.name.contains("Pager") || containsVideoSurface(host)) continue
            val children = (0 until host.childCount).map(host::getChildAt)
            val hidden = children.filter { child ->
                controls[child]?.let { state -> state.target in INFO_TARGETS && policy.hides(state.target) } == true &&
                    nativeVisibility(child) == View.VISIBLE && child.height > 0
            }
            if (hidden.isEmpty()) {
                restoreRowShifts(host)
                continue
            }
            val offsets = mutableMapOf<View, Float>()
            for (child in children) {
                if (child in hidden || nativeVisibility(child) != View.VISIBLE || child.height <= 0) continue
                // Only release rows below this child. A watching-count badge can
                // share the same baseline with its series title; moving horizontal
                // siblings down clips their text outside the rounded background.
                val released = hidden.filter { it.top + originalTranslationY(it) >= child.bottom + originalTranslationY(child) - 0.5f }
                    .fold(0f) { total, item -> total + item.height.toFloat() }
                if (released > 0f && child.bottom + originalTranslationY(child) <=
                    hidden.maxOf { it.bottom + originalTranslationY(it) } + 1f) {
                    offsets[child] = released
                }
            }
            var changed = false
            for (child in children) {
                val original = originalTranslationY(child)
                val offset = offsets[child] ?: 0f
                val desired = original + offset
                if (offset == 0f) {
                    changed = changed || rowShifts.containsKey(child) || child.translationY != original
                    rowShifts.remove(child)
                    mutate { if (child.translationY != original) child.translationY = original }
                } else {
                    changed = changed || rowShifts[child]?.offset != offset || child.translationY != desired
                    rowShifts[child] = RowShift(original, offset)
                    mutate { if (child.translationY != desired) child.translationY = desired }
                }
            }
            if (changed) host.requestLayout()
        }
    }

    /** Clamp stale header translations so the series strip cannot overlap title rows. */
    private fun normalizeSeriesBanners(root: View) {
        if (policy.hides(ControlTarget.SERIES_BANNER)) return
        val banners = controls.entries.filter {
            it.value.target == ControlTarget.SERIES_BANNER && inside(it.key, root) &&
                it.key.visibility == View.VISIBLE
        }.map { it.key }
        fun walk(parent: ViewGroup) {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                val translatedTop = child.top + child.translationY
                val translatedBottom = child.bottom + child.translationY
                if ((translatedTop < 0f || translatedBottom > parent.height.toFloat()) && child.translationY != 0f) {
                    mutate { child.translationY = 0f }
                }
                if (child is ViewGroup) walk(child)
            }
        }
        banners.forEach { banner -> if (banner is ViewGroup) walk(banner) }
    }

    /** The native layout counts VISIBLE children, including inside its super measurement. */
    fun withInteractionMeasurement(host: ViewGroup, measure: () -> Any?): Any? {
        val slots = (0 until host.childCount).map(host::getChildAt).filter {
            val state = controls[it]
            state != null && state.target in INTERACTION_TARGETS && policy.hides(state.target) &&
                state.nativeVisibility == View.VISIBLE && it.visibility == View.INVISIBLE
        }
        if (slots.isEmpty()) return measure()
        mutate { slots.forEach { it.visibility = View.VISIBLE } }
        return try { measure() } finally {
            mutate { slots.forEach { it.visibility = View.INVISIBLE } }
        }
    }

    fun syncWindow(@Suppress("UNUSED_PARAMETER") window: Window) {
        // Keep the host's own bottom-bar/navigation color. The player may use different
        // letterbox colors for different videos; forcing one module color creates a seam.
    }

    private fun restoreBackdrops() {
        for ((view, state) in backdrops.entries.toList()) mutate {
            view.background = state.background; view.visibility = state.visibility; view.alpha = state.alpha
        }
        backdrops.clear()
    }

    private fun restoreSuppressedParents(view: View, root: View) {
        var parent = view.parent as? ViewGroup
        repeat(6) {
            val node = parent ?: return
            if (node === root || node.javaClass.name.contains("Recycler") || node.javaClass.name.contains("Pager")) return
            val dm = node.resources.displayMetrics
            if (node.height > dm.heightPixels * 0.75f || containsVideoSurface(node)) return
            if (node.visibility != View.VISIBLE || node.alpha == 0f || node.translationX != 0f || node.translationY != 0f) {
                if (node !in pauseParents) pauseParents[node] = ParentState(node.visibility, node.alpha, node.translationX, node.translationY)
                mutate { node.visibility = View.VISIBLE; node.alpha = 1f; node.translationX = 0f; node.translationY = 0f }
            }
            parent = node.parent as? ViewGroup
        }
    }

    private fun containsVideoSurface(view: View, depth: Int = 0): Boolean {
        if (depth > 8) return false
        if (view is android.view.SurfaceView || view is android.view.TextureView) return true
        return view is ViewGroup && (0 until view.childCount).any { containsVideoSurface(view.getChildAt(it), depth + 1) }
    }

    private fun restorePauseParents() {
        for ((view, state) in pauseParents.entries.toList()) mutate {
            view.visibility = state.visibility; view.alpha = state.alpha
            view.translationX = state.x; view.translationY = state.y
        }
        pauseParents.clear()
    }

    private fun restoreClearHosts() {
        for ((view,visibility) in clearHosts.entries.toList()) mutate { view.visibility = visibility }
        clearHosts.clear()
    }

    private fun hideClearHosts(view: View) {
        if (view.tag == MODULE_UI_TAG) return
        if (isDanmakuRenderer(view)) {
            if (!policy.keepDanmaku) {
                if (view !in clearHosts) clearHosts[view] = view.visibility
                if (view.visibility != View.INVISIBLE) mutate { view.visibility = View.INVISIBLE }
            }
            return
        }
        if (entryName(view) == "bottom_tab_mask" || bottomBackdropName.isNotBlank() && entryName(view) == bottomBackdropName || profileId == "CN-7.3.2.32" && entryName(view) == "ar8") {
            backdrops.getOrPut(view) { BackdropState(view.background, view.visibility, view.alpha) }
            // Full clear must not leave a reserve: GONE collapses the layout slot so
            // the video viewport can take the whole screen; the state is restored on exit.
            mutate { if (view.visibility != View.GONE) view.visibility = View.GONE }
            return
        }
        if (isPlaybackOverlay(view) && !containsVideoSurface(view)) {
            // Keep only the renderer branch; the publish button remains a hidden control.
            if (policy.keepDanmaku && containsPreservedDanmaku(view)) {
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    when {
                        child.tag == MODULE_UI_TAG || containsVideoSurface(child) -> Unit
                        child.visibility == View.GONE -> Unit
                        isDanmakuRenderer(child) -> Unit
                        containsPreservedDanmaku(child) -> hideAllExceptDanmaku(child)
                        else -> hideClearHost(child)
                    }
                }
                return
            }
            hideClearHost(view)
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) hideClearHosts(view.getChildAt(i))
    }

    /** Bottom bar/backdrop use GONE so the freed slot is given back to the video viewport. */
    private fun hideClearHost(view: View) {
        if (view in clearHosts) {
            if (view.visibility != clearedVisibility(view)) mutate { view.visibility = clearedVisibility(view) }
            return
        }
        clearHosts[view] = view.visibility
        mutate { view.visibility = clearedVisibility(view) }
    }

    private fun clearedVisibility(view: View): Int = if (isBottomChrome(view)) View.GONE else View.INVISIBLE

    private fun isBottomChrome(view: View): Boolean {
        val name = entryName(view)
        if (view.javaClass.name == "com.dragon.read.widget.BottomTabFrameLayout") return true
        if (name == "bottom_bar_layout" || name == "bottom_tab_mask") return true
        if (name in mainNavNames) return true
        return bottomBackdropName.isNotBlank() && name == bottomBackdropName
    }

    private fun containsPreservedDanmaku(view: View, depth: Int = 0): Boolean {
        if (depth > 10) return false
        if (isDanmakuRenderer(view)) return true
        return view is ViewGroup && (0 until view.childCount).any {
            containsPreservedDanmaku(view.getChildAt(it), depth + 1)
        }
    }

    private fun hideAllExceptDanmaku(view: View) {
        if (isDanmakuRenderer(view) || view.tag == MODULE_UI_TAG || containsVideoSurface(view)) return
        if (view is ViewGroup && containsPreservedDanmaku(view)) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (containsPreservedDanmaku(child)) hideAllExceptDanmaku(child)
                else if (child.tag != MODULE_UI_TAG && child.visibility != View.GONE && !containsVideoSurface(child)) {
                    if (child !in clearHosts) clearHosts[child] = child.visibility
                    mutate { child.visibility = View.INVISIBLE }
                }
            }
        }
    }

    private fun isDanmakuRenderer(view: View): Boolean {
        val className = view.javaClass.name
        return className.endsWith(".DanmakuView") || className.endsWith(".BarrageView") ||
            className.endsWith(".DanmakuSurfaceView")
    }

    private fun isPlaybackOverlay(view: View): Boolean {
        val name = entryName(view)
        return name in clearHostNames || name in setOf("top_header_constraint_layout", "top_header_layout", "bottom_bar_layout", "bottom_tab_mask") ||
            // The feed paints its bottom backdrop separately from the navigation host.
            profileId == "CN-7.3.2.32" && name in setOf("hq1", "hq2", "inx", "is7", "aos", "ar8", "ffh", "h8l") ||
            view.javaClass.name == "com.dragon.read.widget.BottomTabFrameLayout"
    }

    private fun syncOpacityHosts(root: View) {
        if (!policy.masterEnabled || policy.temporarilyRestored || policy.opacityPercent == 100) { restoreOpacityHosts(); return }
        fun walk(view: View) {
            if (view.tag == MODULE_UI_TAG) return
            if (view !in backdrops && (bottomBackdropName.isBlank() || entryName(view) != bottomBackdropName) && entryName(view) !in setOf("ar8", "bottom_tab_mask") && isPlaybackOverlay(view) && !containsVideoSurface(view)) {
                val original = opacityHosts.getOrPut(view) { controls[view]?.nativeAlpha ?: view.alpha }
                val desired = policy.alpha(original)
                if (view.alpha != desired) mutate { view.alpha = desired }
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
    }

    private fun restoreOpacityHosts() {
        for ((view, alpha) in opacityHosts.entries.toList()) mutate { view.alpha = alpha }
        opacityHosts.clear()
    }

    private fun apply(view: View, state: State) {
        if (view in channelViews) return
        val hide = policy.hides(state.target)
        val visibility = when {
            hide && (view.parent as? View) !in interactionHosts -> hiddenVisibility(state.target)
            else -> state.nativeVisibility
        }
        val alpha = desiredAlpha(view, state.nativeAlpha)
        mutate {
            if (view.visibility != visibility) { view.visibility = visibility; view.requestLayout() }
            if (view.alpha != alpha) view.alpha = alpha
        }
        state.hidden = hide
    }

    private fun syncInteractionHosts(root: View) {
        val candidates = controls.keys.filter { inside(it, root) }.mapNotNull { it.parent as? ViewGroup }.distinct()
        val hidden = candidates.filter { parent ->
            parent.javaClass.name.endsWith(".LimitHeightByChildrenConstraintLayout") &&
                (0 until parent.childCount).any { index ->
                    val child = parent.getChildAt(index)
                    controls[child]?.let { it.target in INTERACTION_TARGETS && policy.hides(it.target) } == true
                } && (0 until parent.childCount).none { index ->
                    val child = parent.getChildAt(index)
                    val target = controls[child]?.target
                    nativeVisibility(child) == View.VISIBLE && (target !in INTERACTION_TARGETS || !policy.hides(target!!))
                }
        }.toSet()
        for ((view, visibility) in interactionHosts.entries.toList()) if (view !in hidden) {
            mutate { view.visibility = visibility }
            interactionHosts.remove(view)
        }
        for (view in hidden) {
            if (view !in interactionHosts) interactionHosts[view] = view.visibility
            if (view.visibility != View.INVISIBLE) mutate { view.visibility = View.INVISIBLE }
        }
    }

    private fun hiddenVisibility(target: ControlTarget): Int = when (target) {
        // Keep the top row's measured height so the search/menu anchors do not jump upward.
        ControlTarget.TOP_TABS -> View.INVISIBLE
        // CN's LimitHeightByChildrenConstraintLayout cannot recover after all children
        // are GONE (it measures a zero width and an invalid height). Retain their slots.
        ControlTarget.AUTHOR_DECLARATION,
        ControlTarget.AUTHOR_FOLLOW, ControlTarget.FAVORITE, ControlTarget.COMMENT, ControlTarget.LIKE, ControlTarget.SHARE -> View.INVISIBLE
        else -> View.GONE
    }

    private fun discover(view: View) {
        if (view.tag == MODULE_UI_TAG) return
        if (view !in controls) {
            val target = identify(view)
            if (target != null) {
                val candidate = if (target.isBottomTab) bottomTabSlot(view) else controlContainer(view, target)
                if (candidate !in controls) {
                    splitConflictingAncestors(candidate, target)
                    bindWithoutHidingOtherTargets(candidate, target, automaticBottomTab = target.isBottomTab)
                }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) discover(view.getChildAt(i))
    }

    private fun channelVisibility(state: ChannelState): Int = when {
        policy.hides(ControlTarget.TOP_TABS) -> View.INVISIBLE
        policy.hidesChannel(state.channel) -> View.GONE
        else -> state.visibility
    }

    private fun insideChannelTabs(view: View): Boolean {
        if (controls[view]?.target == ControlTarget.TOP_TABS) return true
        var node = view.parent as? View
        repeat(8) {
            val parent = node ?: return false
            if (controls[parent]?.target == ControlTarget.TOP_TABS ||
                resourceOverrides[entryName(parent)] == ControlTarget.TOP_TABS.key ||
                resourceTargets[entryName(parent)] == ControlTarget.TOP_TABS ||
                profileId == "CN-7.3.2.32" && CN_73232_TARGETS[entryName(parent)] == ControlTarget.TOP_TABS) return true
            node = parent.parent as? View
        }
        return false
    }

    private fun syncChannels(root: View) {
        val discovered = mutableSetOf<View>()
        fun hasAnotherLabel(node: View, label: String): Boolean {
            if (node is TextView && node.text.toString().trim() in TOP_TAB_LABELS && node.text.toString().trim() != label) return true
            return node is ViewGroup && (0 until node.childCount).any { hasAnotherLabel(node.getChildAt(it), label) }
        }
        fun walk(view: View) {
            if (view.tag == MODULE_UI_TAG) return
            val label = (view as? TextView)?.text?.toString()?.trim()
            val channel = ChannelTarget.entries.firstOrNull { it.title == label }
            if (channel != null && insideChannelTabs(view)) {
                // Hide the individual tab's icon/hit area too, while leaving its siblings intact.
                var slot = view
                repeat(4) {
                    val parent = slot.parent as? ViewGroup ?: return@repeat
                    val name = entryName(parent)
                    val knownHost = resourceOverrides[name] == ControlTarget.TOP_TABS.key ||
                        resourceTargets[name] == ControlTarget.TOP_TABS ||
                        profileId == "CN-7.3.2.32" && CN_73232_TARGETS[name] == ControlTarget.TOP_TABS
                    if (parent === root || knownHost ||
                        hasAnotherLabel(parent, channel.title) || parent.javaClass.name.contains("Pager")) return@repeat
                    slot = parent
                }
                channelViews.getOrPut(slot) { ChannelState(channel, controls[slot]?.nativeVisibility ?: slot.visibility) }.channel = channel
                discovered.add(slot)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        val hosts = controls.entries.filter { it.value.target == ControlTarget.TOP_TABS && inside(it.key, root) }
            .map { if (it.key is TextView) it.key.parent as? View ?: it.key else it.key }.distinct()
        hosts.forEach(::walk)
        for ((view, state) in channelViews.entries.toList()) if (inside(view, root)) {
            val desired = if (view in discovered) channelVisibility(state) else state.visibility
            if (view.visibility != desired) mutate { view.visibility = desired }
            if (view !in discovered) channelViews.remove(view)
        }
    }

    /** Identify the channel pager by its sibling tab host, never a nested video pager. */
    fun blocksChannelSwipe(pager: View): Boolean {
        return policy.blocksChannelSwipe && isChannelPager(pager)
    }

    fun channelPagers(root: View): Set<View> {
        val result = linkedSetOf<View>()
        for ((tabs, state) in controls.entries.toList()) if (state.target == ControlTarget.TOP_TABS && inside(tabs, root)) {
            var node = tabs.parent as? ViewGroup
            repeat(5) {
                val parent = node ?: return@repeat
                for (index in 0 until parent.childCount) {
                    val child = parent.getChildAt(index)
                    if (isChannelPager(child)) result.add(child)
                }
                node = parent.parent as? ViewGroup
            }
        }
        return result
    }

    fun isChannelPager(pager: View): Boolean {
        if (pager.javaClass.name != "com.dragon.read.widget.viewpager.CustomScrollViewPager") return false
        val parent = pager.parent as? ViewGroup ?: return false
        return controls.entries.any { (tabs, state) ->
            if (state.target != ControlTarget.TOP_TABS || inside(tabs, pager)) false
            else {
                var node: View? = tabs.parent as? View
                var found = false
                repeat(5) {
                    if (node === parent) found = true
                    node = node?.parent as? View
                }
                found
            }
        }
    }

    private fun splitConflictingAncestors(view: View, target: ControlTarget) {
        var parent = view.parent as? ViewGroup
        while (parent != null) {
            val node = parent
            val state = controls[node]
            if (state != null && state.target != target) {
                controls.remove(node)
                val automaticBottomTab = automaticBottomTabs.remove(node) == true
                mutate { node.visibility = state.nativeVisibility; node.alpha = state.nativeAlpha }
                bindWithoutHidingOtherTargets(node, state.target, automaticBottomTab)
            }
            parent = node.parent as? ViewGroup
        }
    }

    private fun bindWithoutHidingOtherTargets(view: View, target: ControlTarget, automaticBottomTab: Boolean = false) {
        if (containsVideoSurface(view)) return
        fun hasOther(node: View, depth: Int = 0): Boolean {
            if (depth > 8) return false
            identify(node)?.let { if (it != target) return true }
            return node is ViewGroup && (0 until node.childCount).any { hasOther(node.getChildAt(it), depth + 1) }
        }
        if (view is ViewGroup && hasOther(view)) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (identify(child)?.let { it != target } == true) continue
                bindWithoutHidingOtherTargets(child, target, automaticBottomTab)
            }
        } else {
            bind(view, target)
            if (automaticBottomTab && controls[view]?.target?.isBottomTab == true) automaticBottomTabs[view] = true
        }
    }

    private fun desiredAlpha(view: View, original: Float): Float {
        if (view in opacityHosts) return policy.alpha(original)
        var node = view.parent as? View
        repeat(20) {
            val parent = node ?: return policy.alpha(original)
            if (parent in controls || parent in opacityHosts) return original
            node = parent.parent as? View
        }
        return policy.alpha(original)
    }

    private fun identify(view: View): ControlTarget? {
        if (view.javaClass.name in COIN_REWARD_CLASSES) return ControlTarget.COIN_REWARD
        if (view.javaClass.name == SERIES_BANNER_CLASS && insideSeriesInfoHeader(view))
            return ControlTarget.SERIES_BANNER
        // This Compose host owns the avatar and follow badge. The same component is
        // also used outside playback, so only bind it under the native right panel.
        if (view.javaClass.name in AUTHOR_FOLLOW_CLASSES && insidePlayerRightPanel(view))
            return ControlTarget.AUTHOR_FOLLOW
        // This native component owns both the declaration text and its leading icon.
        // Bind the row before visiting its text leaf, including before async text is filled.
        if (view.javaClass.name == "com.dragon.read.component.shortvideo.impl.infobottom.ShortSeriesInfoBottomView")
            return ControlTarget.AUTHOR_DECLARATION
        if ((view as? TextView)?.text?.toString()?.trim()?.startsWith("作者声明") == true)
            return ControlTarget.AUTHOR_DECLARATION
        // Match semantic resource names first; opaque CN ids belong in a version profile.
        val name = entryName(view)
        // This id is reused for other recommendation copy; only bind the watching count.
        resourceOverrides[name]?.let { key ->
            val target = ControlTarget.entries.firstOrNull { it.key == key }
            if (target == ControlTarget.WATCHING_COUNT && !WATCHING_COUNT_TEXT.matches((view as? TextView)?.text?.toString()?.trim().orEmpty())) return null
            return target
        }
        if (profileId == "CN-7.3.2.32" && name == "jpe") {
            return if (WATCHING_COUNT_TEXT.matches((view as? TextView)?.text?.toString()?.trim().orEmpty()))
                ControlTarget.WATCHING_COUNT else null
        }
        if (profileId == "CN-7.3.2.32") CN_73232_TARGETS[name]?.let { return it }
        when (view.javaClass.name.substringAfterLast('.')) {
            "SeriesCommentView" -> return ControlTarget.COMMENT
            "SeriesDiggView" -> return ControlTarget.LIKE
            "SeriesShareView" -> return ControlTarget.SHARE
            "InfoPanelHotCommentView", "SeriesHotCommentView" -> return ControlTarget.HOT_COMMENT
        }
        resourceTargets[name]?.let { return it }
        val text = (view as? TextView)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: view.contentDescription?.toString()?.trim().orEmpty()
        if (text == "游戏中心" && insideSidebar(view)) return ControlTarget.GAME_CENTER
        if (text.contains("领金币") || text.contains("领取金币")) return ControlTarget.COIN_REWARD
        if (text.isEmpty()) return null
        // Counts can be filled after their containing information panel is already hidden.
        if (WATCHING_COUNT_TEXT.matches(text)) return ControlTarget.WATCHING_COUNT
        if (insideBottomNav(view)) return bottomNavTarget(view)
        val positionCandidate = text in TOP_TAB_LABELS || text in POSITION_LABELS ||
            INTERACTION_LABEL_PREFIXES.any { text.startsWith(it) }
        if (!positionCandidate) return null
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect)) return null
        val dm = view.resources.displayMetrics
        val right = rect.centerX() > dm.widthPixels * 0.68f
        val top = rect.centerY() < dm.heightPixels * 0.22f
        return when {
            top && text in TOP_TAB_LABELS && hasTopTabSiblings(view) -> ControlTarget.TOP_TABS
            right && (text == "收藏" || text.startsWith("收藏，") || text.startsWith("收藏,")) -> ControlTarget.FAVORITE
            right && (text == "评论" || text.startsWith("评论，") || text.startsWith("评论,")) -> ControlTarget.COMMENT
            right && (text == "点赞" || text.startsWith("点赞，") || text.startsWith("点赞,")) -> ControlTarget.LIKE
            right && (text == "分享" || text.startsWith("分享，") || text.startsWith("分享,")) -> ControlTarget.SHARE
            top && text == "搜索" -> ControlTarget.SEARCH
            top && text in setOf("菜单", "更多") -> ControlTarget.MENU
            text in setOf("全屏观看", "全屏播放") -> ControlTarget.FULLSCREEN
            text == "热评" -> ControlTarget.HOT_COMMENT
            else -> null
        }
    }

    private fun controlContainer(view: View, target: ControlTarget): View {
        if (target == ControlTarget.GAME_CENTER) {
            val parent = view.parent as? ViewGroup
            // A sidebar title can share a host with other sections; do not hide that whole host.
            return if (parent != null && !parent.javaClass.name.contains("sidebar", true) &&
                !entryName(parent).contains("sidebar", true) && !entryName(parent).contains("side_bar")) parent else view
        }
        if (view is ViewGroup || entryName(view) in resourceTargets || entryName(view) in resourceOverrides ||
            profileId == "CN-7.3.2.32" && entryName(view) in CN_73232_TARGETS) return view
        var candidate = view
        repeat(3) {
            val parent = candidate.parent as? ViewGroup ?: return candidate
            if (controls[parent]?.target?.let { it != target } == true) return candidate
            val density = view.resources.displayMetrics.density.coerceAtLeast(0.1f)
            if (parent.width > view.resources.displayMetrics.widthPixels * 0.55f || parent.height > 130 * density) return candidate
            if (parent.childCount > 4 || parent.javaClass.name.contains("Recycler") || parent.javaClass.name.contains("Pager")) return candidate
            val different = (0 until parent.childCount).mapNotNull { identify(parent.getChildAt(it)) }.any { it != target }
            if (different) return candidate
            candidate = parent
            if (parent.isClickable) return candidate
        }
        return candidate
    }

    private fun insideSidebar(view: View): Boolean {
        var node: View? = view
        repeat(16) {
            val current = node ?: return false
            if (current.javaClass.name.contains("sidebar", ignoreCase = true) ||
                entryName(current).contains("sidebar", ignoreCase = true) ||
                entryName(current).contains("side_bar")) return true
            node = current.parent as? View
        }
        return false
    }

    private fun insidePlayerRightPanel(view: View): Boolean {
        var parent = view.parent as? View
        repeat(20) {
            val node = parent ?: return false
            if (node.javaClass.name == "com.dragon.read.component.shortvideo.impl.rightview.ShortSeriesRightView") return true
            parent = node.parent as? View
        }
        return false
    }

    private fun insideSeriesInfoHeader(view: View): Boolean {
        var parent = view.parent as? View
        repeat(12) {
            val node = parent ?: return false
            if (node.javaClass.name == SERIES_HEADER_CLASS) return true
            parent = node.parent as? View
        }
        return false
    }

    private fun isBottomNavHost(view: View): Boolean = view.javaClass.name in BOTTOM_NAV_CLASSES ||
        entryName(view) in BOTTOM_NAV_NAMES

    private fun insideBottomNav(view: View): Boolean {
        var node: View? = view
        repeat(8) {
            val current = node ?: return false
            if (isBottomNavHost(current)) return true
            node = current.parent as? View
        }
        return false
    }

    private fun bottomTabSlot(view: View): View {
        var candidate = view
        repeat(6) {
            val parent = candidate.parent as? ViewGroup ?: return candidate
            if (isBottomNavHost(parent)) return candidate
            // An icon and its text can both carry the same label. They are one slot,
            // while even two distinct sibling labels identify the actual tab row.
            var first: ControlTarget? = null
            for (index in 0 until parent.childCount) {
                val target = findBottomTabTarget(parent.getChildAt(index)) ?: continue
                if (first != null && first != target) return candidate
                first = target
            }
            candidate = parent
        }
        return view
    }

    private fun navLabelTarget(label: CharSequence?): ControlTarget? {
        val text = label?.toString()?.trim().orEmpty()
        return NAV_LABEL_TARGETS[text] ?: if (text.startsWith("福利")) ControlTarget.WELFARE else null
    }

    /** The same mappings drive binding, slot detection and width redistribution. */
    private fun bottomNavTarget(view: View): ControlTarget? {
        val name = entryName(view)
        val mapped = resourceOverrides[name]?.let { key -> ControlTarget.entries.firstOrNull { it.key == key } }
            ?: (if (profileId == "CN-7.3.2.32") CN_73232_TARGETS[name] else null)
            ?: resourceTargets[name]
        if (mapped != null) return mapped.takeIf { it.isBottomTab }
        return navLabelTarget((view as? TextView)?.text) ?: navLabelTarget(view.contentDescription)
    }

    private fun findBottomTabTarget(view: View, depth: Int = 0): ControlTarget? {
        if (depth > 4 || view.tag == MODULE_UI_TAG) return null
        bottomNavTarget(view)?.let { return it }
        if (view !is ViewGroup) return null
        var found: ControlTarget? = null
        for (index in 0 until view.childCount) {
            val target = findBottomTabTarget(view.getChildAt(index), depth + 1) ?: continue
            if (found != null && found != target) return null // Never bind a container of different tabs.
            found = target
        }
        return found
    }

    /** Recheck only auto-bound navigation slots, retaining the latest native state. */
    private fun refreshBottomTabBindings(root: View) {
        for (view in automaticBottomTabs.keys.toList()) {
            if (!inside(view, root)) continue
            val inNavigation = insideBottomNav(view)
            val state = controls[view]
            if (state == null) {
                if (!inNavigation) {
                    automaticBottomTabs.remove(view)
                    tabLayouts.remove(view)?.let { saved -> mutate { view.layoutParams = saved } }
                }
                continue
            }
            val target = if (inNavigation) findBottomTabTarget(view) else null
            if (target != null) {
                state.target = target
            } else {
                // Unknown replacement tabs still occupy a native navigation slot.
                // Include them in reflow, but never keep the previous hide policy.
                if (!inNavigation) automaticBottomTabs.remove(view)
                controls.remove(view)
                val savedLayout = tabLayouts.remove(view)
                mutate {
                    if (view.visibility != state.nativeVisibility) view.visibility = state.nativeVisibility
                    if (view.alpha != state.nativeAlpha) view.alpha = state.nativeAlpha
                    if (savedLayout != null) view.layoutParams = savedLayout
                }
            }
        }
    }

    private fun reflowBottomTabs(root: View) {
        val candidates = controls.entries.filter { it.value.target.isBottomTab }.map { it.key } +
            automaticBottomTabs.keys.toList() + tabLayouts.keys.toList()
        val parents = candidates.filter { inside(it, root) }.mapNotNull { it.parent as? ViewGroup }.distinct()
        for (parent in parents) {
            val tabs = (0 until parent.childCount).map(parent::getChildAt).filter {
                controls[it]?.target?.isBottomTab == true || it in automaticBottomTabs ||
                    it in tabLayouts || findBottomTabTarget(it) != null
            }
            val hidden = tabs.any { controls[it]?.hidden == true }
            if (!hidden) {
                for (tab in tabs) tabLayouts.remove(tab)?.let { saved -> mutate { tab.layoutParams = saved } }
                continue
            }
            val visible = tabs.filter { it.visibility != View.GONE }
            if (visible.isEmpty()) continue
            val available = parent.width - parent.paddingLeft - parent.paddingRight
            var changed = false
            for ((index, tab) in visible.withIndex()) {
                if (tab !in tabLayouts) copyLayout(tab.layoutParams)?.let { tabLayouts[tab] = it }
                mutate {
                    when (val lp = tab.layoutParams) {
                        is LinearLayout.LayoutParams -> {
                            if (lp.width != 0 || lp.weight != 1f || lp.leftMargin != 0 || lp.rightMargin != 0) {
                                lp.width = 0; lp.weight = 1f; lp.leftMargin = 0; lp.rightMargin = 0
                                tab.layoutParams = lp
                                changed = true
                            }
                        }
                        is FrameLayout.LayoutParams -> if (available > 0) {
                            val start = available * index / visible.size
                            val width = available * (index + 1) / visible.size - start
                            val gravity = Gravity.TOP or Gravity.START
                            if (lp.width != width || lp.gravity != gravity || lp.marginStart != start || lp.marginEnd != 0) {
                                lp.width = width
                                lp.gravity = gravity
                                lp.marginStart = start; lp.marginEnd = 0
                                tab.layoutParams = lp
                                changed = true
                            }
                        }
                        else -> if (unsupportedBottomLayouts.add(tab.javaClass.name)) LogUtil.incr("controlBottomUnsupportedLayout")
                    }
                }
            }
            if (changed) parent.requestLayout()
        }
    }

    private fun restoreTabLayouts() {
        for ((view, lp) in tabLayouts.entries.toList()) mutate { view.layoutParams = lp }
        tabLayouts.clear()
    }

    private fun inside(view: View, root: View): Boolean {
        var node: View? = view
        repeat(80) { if (node === root) return true; node = node?.parent as? View }
        return false
    }

    private fun entryName(view: View): String {
        val id = view.id
        resourceNames[view]?.let { if (it.first == id) return it.second }
        val name = try {
            if (id > 0) view.resources.getResourceEntryName(id) else ""
        } catch (_: Throwable) { "" }
        resourceNames[view] = id to name
        return name
    }

    private fun copyLayout(lp: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? {
        if (lp == null) return null
        return when (lp) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp)
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(lp)
            else -> null
        }
    }

    companion object {
        const val MODULE_UI_TAG = "GUOPLUS_MODULE_UI"
        private val BOTTOM_NAV_CLASSES = setOf("com.dragon.read.widget.BottomTabFrameLayout", "com.dragon.read.widget.BottomTabBarLayout")
        private val BOTTOM_NAV_NAMES = setOf("bottom_bar_layout", "aot", "bottom_tab_layout")
        private val NAV_LABEL_TARGETS = mapOf(
            "首页" to ControlTarget.HOME, "剧场" to ControlTarget.THEATER, "短剧" to ControlTarget.THEATER,
            "商城" to ControlTarget.MALL, "赚钱" to ControlTarget.EARN, "我的" to ControlTarget.PROFILE,
            "福利" to ControlTarget.WELFARE, "领福利" to ControlTarget.WELFARE,
        )
        private val TOP_TAB_LABELS = setOf("推荐", "关注", "精选", "短剧", "漫剧", "真人剧", "剧场")
        private val POSITION_LABELS = setOf("搜索", "菜单", "更多", "收藏", "评论", "点赞", "分享", "全屏观看", "全屏播放", "热评")
        private val INTERACTION_LABEL_PREFIXES = listOf("收藏，", "收藏,", "评论，", "评论,", "点赞，", "点赞,", "分享，", "分享,")
        private val WATCHING_COUNT_TEXT = Regex("共\\s*[\\d.,]+\\s*[万亿]?\\s*人在追")
        private val INTERACTION_TARGETS = setOf(ControlTarget.AUTHOR_FOLLOW, ControlTarget.FAVORITE, ControlTarget.COMMENT, ControlTarget.LIKE, ControlTarget.SHARE)
        private val AUTHOR_FOLLOW_CLASSES = setOf("com.dragon.read.compose.AvatarComposeComponent",
            "com.dragon.read.component.shortvideo.impl.rightview.PugcSubscribeView")
        private val COIN_REWARD_CLASSES = setOf("com.dragon.read.goldcoinbox.widget.c",
            "com.dragon.read.polaris.novelug.pendant.a",
            // 7.3.9.x uses a Compose pendant host outside the player right panel.
            "com.bytedance.ug.sdk.kmp.novel.pendant.w")
        private const val SERIES_BANNER_CLASS = "com.dragon.read.component.shortvideo.impl.infoheader.WrapWidthLinearLayout"
        private const val SERIES_HEADER_CLASS = "com.dragon.read.component.shortvideo.impl.infoheader.ShortSeriesInfoHeaderView"
        private val INFO_TARGETS = setOf(ControlTarget.SERIES_BANNER, ControlTarget.SERIES_INFO,
            ControlTarget.AUTHOR_DECLARATION, ControlTarget.HOT_COMMENT, ControlTarget.WATCHING_COUNT)
        // Confirmed against the installed CN 7.3.2.32 APK and live player trees.
        private val CN_73232_TARGETS = mapOf(
            "hqm" to ControlTarget.TOP_TABS,
            "hjh" to ControlTarget.SEARCH,
            "ema" to ControlTarget.MENU,
            "hue" to ControlTarget.FAVORITE,
            "cei" to ControlTarget.COMMENT,
            "fgs" to ControlTarget.LIKE,
            "hsn" to ControlTarget.SHARE,
            "jc2" to ControlTarget.FULLSCREEN,
            "eb9" to ControlTarget.SERIES_INFO,
            "fxi" to ControlTarget.SERIES_INFO,
            "hmv" to ControlTarget.SERIES_INFO,
            "m5" to ControlTarget.SERIES_INFO,
            "eb4" to ControlTarget.SERIES_INFO,
            "ij1" to ControlTarget.AUTHOR_DECLARATION,
            "e2c" to ControlTarget.HOT_COMMENT,
            "fut" to ControlTarget.HOT_COMMENT,
            "jpe" to ControlTarget.WATCHING_COUNT,
            "ey_" to ControlTarget.BACK_EPISODE, "jzl" to ControlTarget.BACK_EPISODE,
            "ke8" to ControlTarget.PLAYBACK_SPEED,
            "eyb" to ControlTarget.PLAYER_MORE,
            "gq_" to ControlTarget.DANMAKU_ENTRY,
            "aoq" to ControlTarget.EPISODE_SELECTOR,
            "g39" to ControlTarget.FULLSCREEN_SWITCH,
            "hus" to ControlTarget.PROGRESS,
        )
        private val resourceTargets = mapOf(
            "top_tab_layout" to ControlTarget.TOP_TABS, "tab_layout" to ControlTarget.TOP_TABS,
            "top_header_constraint_layout" to ControlTarget.TOP_TABS,
            "top_tabs" to ControlTarget.TOP_TABS, "search_icon" to ControlTarget.SEARCH,
            "iv_search" to ControlTarget.SEARCH, "search_button" to ControlTarget.SEARCH,
            "menu_icon" to ControlTarget.MENU, "iv_menu" to ControlTarget.MENU,
            "collect_layout" to ControlTarget.FAVORITE, "favorite_layout" to ControlTarget.FAVORITE,
            "comment_layout" to ControlTarget.COMMENT, "comment_container" to ControlTarget.COMMENT,
            "digg_layout" to ControlTarget.LIKE, "like_layout" to ControlTarget.LIKE,
            "share_layout" to ControlTarget.SHARE, "share_container" to ControlTarget.SHARE,
            "full_screen_watch" to ControlTarget.FULLSCREEN,
            "series_info_panel_container" to ControlTarget.SERIES_INFO,
            "book_container" to ControlTarget.SERIES_INFO,
            "hot_comment_container" to ControlTarget.HOT_COMMENT,
            "hot_comment_layout" to ControlTarget.HOT_COMMENT,
            "watching_count" to ControlTarget.WATCHING_COUNT,
            "bottom_relate_series_container" to ControlTarget.EPISODE_SELECTOR,
        )
    }
}
