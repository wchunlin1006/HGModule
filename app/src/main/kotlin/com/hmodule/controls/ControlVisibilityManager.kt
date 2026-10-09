package com.hmodule.controls

import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
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
        val target: ControlTarget,
        var nativeVisibility: Int,
        var nativeAlpha: Float,
        var hidden: Boolean = false,
    )

    private data class ParentState(var visibility: Int, var alpha: Float, val x: Float, val y: Float)
    private data class BackdropState(val background: Drawable?, var visibility: Int, var alpha: Float)
    private data class WindowState(val color: Int, val contrast: Boolean)
    private data class RowShift(var nativeY: Float, var offset: Float)

    private val controls = WeakHashMap<View, State>()
    private val clearHosts = WeakHashMap<View, Int>()
    private val opacityHosts = WeakHashMap<View, Float>()
    private val interactionHosts = WeakHashMap<View, Int>()
    private val tabLayouts = WeakHashMap<View, ViewGroup.LayoutParams>()
    private val pauseParents = WeakHashMap<View, ParentState>()
    private val backdrops = WeakHashMap<View, BackdropState>()
    private val windows = WeakHashMap<Window, WindowState>()
    private val rowShifts = WeakHashMap<View, RowShift>()
    private val infoHosts = WeakHashMap<View, Int>()
    private val layoutWatched = WeakHashMap<View, Boolean>()
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
    private var applying = false

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
            if (!policy.clearsAll) { restoreClearHosts(); restoreBackdrops() }
            discover(root)
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
            reflowInteractions(root)
            reflowBottomTabs(root)
            if (policy.clearsAll) hideClearHosts(root)
        } finally { applying = false }
    }

    fun requestedVisibility(view: View, requested: Int): Int {
        if (applying) return requested
        backdrops[view]?.let { it.visibility = requested; return View.VISIBLE }
        if (view in infoHosts) { infoHosts[view] = requested; return View.GONE }
        if (view in clearHosts) { clearHosts[view] = requested; return View.INVISIBLE }
        if (view in interactionHosts) { interactionHosts[view] = requested; return View.INVISIBLE }
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
            restoreWindows()
            restoreOpacityHosts()
            for ((view, visibility) in interactionHosts.entries.toList()) mutate { view.visibility = visibility }
            interactionHosts.clear()
            restoreTabLayouts()
            restoreInfoHosts()
            restoreRowShifts()
        } finally { applying = false }
    }

    fun clear() { restoreAll(); controls.clear(); seen.clear() }

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

    fun syncWindow(window: Window) {
        if (!policy.clearsAll) { restoreWindows(); return }
        windows.getOrPut(window) {
            WindowState(window.navigationBarColor, Build.VERSION.SDK_INT >= 29 && window.isNavigationBarContrastEnforced)
        }
        window.navigationBarColor = CLEAR_BACKGROUND_COLOR
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }

    private fun restoreWindows() {
        for ((window, state) in windows.entries.toList()) {
            window.navigationBarColor = state.color
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = state.contrast
        }
        windows.clear()
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
        if (entryName(view) == "bottom_tab_mask" || bottomBackdropName.isNotBlank() && entryName(view) == bottomBackdropName || profileId == "CN-7.3.2.32" && entryName(view) == "ar8") {
            backdrops.getOrPut(view) { BackdropState(view.background, view.visibility, view.alpha) }
            mutate { view.background = ColorDrawable(CLEAR_BACKGROUND_COLOR); view.visibility = View.VISIBLE; view.alpha = 1f }
            return
        }
        if (isPlaybackOverlay(view) && !containsVideoSurface(view)) {
            if (view !in clearHosts) clearHosts[view] = view.visibility
            mutate { view.visibility = View.INVISIBLE }
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) hideClearHosts(view.getChildAt(i))
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
                mutate { view.alpha = policy.alpha(original) }
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
            mutate { view.visibility = View.INVISIBLE }
        }
    }

    private fun hiddenVisibility(target: ControlTarget): Int = when (target) {
        // CN's LimitHeightByChildrenConstraintLayout cannot recover after all children
        // are GONE (it measures a zero width and an invalid height). Retain their slots.
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
                    bindWithoutHidingOtherTargets(candidate, target)
                }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) discover(view.getChildAt(i))
    }

    private fun splitConflictingAncestors(view: View, target: ControlTarget) {
        var parent = view.parent as? ViewGroup
        while (parent != null) {
            val node = parent
            val state = controls[node]
            if (state != null && state.target != target) {
                controls.remove(node)
                mutate { node.visibility = state.nativeVisibility; node.alpha = state.nativeAlpha }
                bindWithoutHidingOtherTargets(node, state.target)
            }
            parent = node.parent as? ViewGroup
        }
    }

    private fun bindWithoutHidingOtherTargets(view: View, target: ControlTarget) {
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
                bindWithoutHidingOtherTargets(child, target)
            }
        } else bind(view, target)
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
        val text = ((view as? TextView)?.text?.toString() ?: view.contentDescription?.toString().orEmpty()).trim()
        if (text.isEmpty()) return null
        // Counts can be filled after their containing information panel is already hidden.
        if (WATCHING_COUNT_TEXT.matches(text)) return ControlTarget.WATCHING_COUNT
        if (insideBottomNav(view)) return when (text) {
            "首页" -> ControlTarget.HOME
            "剧场", "短剧" -> ControlTarget.THEATER
            "商城" -> ControlTarget.MALL
            "赚钱" -> ControlTarget.EARN
            "我的" -> ControlTarget.PROFILE
            else -> null
        }
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

    private fun insidePlayerRightPanel(view: View): Boolean {
        var parent = view.parent as? View
        repeat(20) {
            val node = parent ?: return false
            if (node.javaClass.name == "com.dragon.read.component.shortvideo.impl.rightview.ShortSeriesRightView") return true
            parent = node.parent as? View
        }
        return false
    }

    private fun insideBottomNav(view: View): Boolean {
        var node: View? = view
        repeat(8) {
            val current = node ?: return false
            if (current.javaClass.name in setOf("com.dragon.read.widget.BottomTabFrameLayout", "com.dragon.read.widget.BottomTabBarLayout") ||
                entryName(current) in setOf("bottom_bar_layout", "aot", "bottom_tab_layout")) return true
            node = current.parent as? View
        }
        return false
    }

    private fun bottomTabSlot(view: View): View {
        var candidate = view
        repeat(6) {
            val parent = candidate.parent as? ViewGroup ?: return candidate
            val slots = (0 until parent.childCount).count { hasNavLabel(parent.getChildAt(it)) }
            if (slots >= 3) return candidate
            if (parent.javaClass.name == "com.dragon.read.widget.BottomTabFrameLayout") return candidate
            candidate = parent
        }
        return view
    }

    private fun hasNavLabel(view: View, depth: Int = 0): Boolean {
        if (depth > 4) return false
        if (view is TextView && view.text?.toString()?.trim() in NAV_LABELS) return true
        return view is ViewGroup && (0 until view.childCount).any { hasNavLabel(view.getChildAt(it), depth + 1) }
    }

    private fun reflowBottomTabs(root: View) {
        val parents = controls.entries.filter { it.value.target.isBottomTab && inside(it.key, root) }
            .mapNotNull { it.key.parent as? ViewGroup }.distinct()
        for (parent in parents) {
            val tabs = (0 until parent.childCount).map(parent::getChildAt).filter { hasNavLabel(it) }
            val hidden = tabs.any { controls[it]?.hidden == true }
            if (!hidden) {
                for (tab in tabs) tabLayouts.remove(tab)?.let { saved -> mutate { tab.layoutParams = saved } }
                continue
            }
            val visible = tabs.filter { it.visibility != View.GONE }
            if (visible.isEmpty()) continue
            val available = parent.width - parent.paddingLeft - parent.paddingRight
            for ((index, tab) in visible.withIndex()) {
                if (tab !in tabLayouts) copyLayout(tab.layoutParams)?.let { tabLayouts[tab] = it }
                mutate {
                    when (val lp = tab.layoutParams) {
                        is LinearLayout.LayoutParams -> {
                            lp.width = 0; lp.weight = 1f; lp.leftMargin = 0; lp.rightMargin = 0
                            tab.layoutParams = lp
                        }
                        is FrameLayout.LayoutParams -> if (available > 0) {
                            val start = available * index / visible.size
                            lp.width = available * (index + 1) / visible.size - start
                            lp.gravity = Gravity.TOP or Gravity.START
                            lp.marginStart = start; lp.marginEnd = 0
                            tab.layoutParams = lp
                        }
                        else -> LogUtil.incr("controlBottomUnsupportedLayout")
                    }
                }
            }
            parent.requestLayout()
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

    private fun entryName(view: View): String = try {
        if (view.id > 0) view.resources.getResourceEntryName(view.id) else ""
    } catch (_: Throwable) { "" }

    private fun copyLayout(lp: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? {
        if (lp == null) return null
        return when (lp) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp)
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(lp)
            else -> null
        }
    }

    companion object {
        val CLEAR_BACKGROUND_COLOR: Int = Color.rgb(0x1C, 0x1A, 0x17)
        const val MODULE_UI_TAG = "GUOPLUS_MODULE_UI"
        private val NAV_LABELS = setOf("首页", "剧场", "商城", "赚钱", "我的", "短剧", "福利")
        private val TOP_TAB_LABELS = setOf("推荐", "关注", "精选", "短剧", "漫剧", "真人剧", "剧场")
        private val WATCHING_COUNT_TEXT = Regex("共\\s*[\\d.,]+\\s*[万亿]?\\s*人在追")
        private val INTERACTION_TARGETS = setOf(ControlTarget.AUTHOR_FOLLOW, ControlTarget.FAVORITE, ControlTarget.COMMENT, ControlTarget.LIKE, ControlTarget.SHARE)
        private val AUTHOR_FOLLOW_CLASSES = setOf("com.dragon.read.compose.AvatarComposeComponent",
            "com.dragon.read.component.shortvideo.impl.rightview.PugcSubscribeView")
        private val INFO_TARGETS = setOf(ControlTarget.SERIES_INFO, ControlTarget.AUTHOR_DECLARATION, ControlTarget.HOT_COMMENT, ControlTarget.WATCHING_COUNT)
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
