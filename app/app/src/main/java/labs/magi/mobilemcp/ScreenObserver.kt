package labs.magi.mobilemcp

import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded, token-cheap view of the accessibility tree.
 *
 * Each interesting node (has text, or is interactive) becomes one line: `@ref role "text" (desc) #id [states] (x,y wxh)`.
 * Refs are keyed by a content identity (class, view id, text, description, occurrence) rather than tree position, so
 * they survive list scrolling and re-layout; they reset when the foreground package changes.
 * Snapshots are versioned so a later call with `since` can return only changed/removed lines.
 */
class ScreenObserver(private val svc: MobileAccessibilityService) {
    class Rec(val key: String, val node: AccessibilityNodeInfo, val depth: Int, val parentKey: String?, val interactive: Boolean, val text: String, val line: String, val rect: Rect, val label: String, val derived: Boolean)
    /** Refs and screen rects of the lines returned by the latest snapshot, for Set-of-Mark screenshots. */
    @Volatile var lastMarks: List<Pair<Int, Rect>> = emptyList()
    @Volatile var fullBounds = false

    /** Ref numbering and snapshot baselines, kept per foreground package so refs survive a detour through another app. */
    private class RefState { val refByKey = HashMap<String, Int>(); val keyByRef = HashMap<Int, String>(); var nextRef = 1; val history = ArrayDeque<Pair<String, LinkedHashMap<Int, String>>>() }
    private val states = object : LinkedHashMap<String, RefState>(8, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RefState>?) = size > 6 }
    private var state = RefState()
    private var refPackage: String? = null
    private val refByKey get() = state.refByKey
    private val keyByRef get() = state.keyByRef
    private val history get() = state.history
    private var versionCounter = 0

    // ---- collection -------------------------------------------------------------------------------------------

    /** Walks every relevant window (app, dialogs, system prompts; not the keyboard) and returns nodes in pre-order. */
    @Synchronized
    fun collect(includeKeyboard: Boolean = false): List<Rec> {
        val windows = try { svc.windows } catch (_: Exception) { emptyList<AccessibilityWindowInfo>() }
        val roots = ArrayList<Pair<String, AccessibilityNodeInfo>>()
        for (w in windows.sortedByDescending { it.layer }) {
            when (w.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> if (!includeKeyboard) continue
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> continue
            }
            val root = w.root ?: continue
            // Status/navigation bars are SystemUI windows that are never active; the shade and system dialogs are.
            if (!w.isActive && root.packageName == "com.android.systemui") continue
            // Keys are prefixed by the window's package, not its id: ids change on rotation and relaunch, packages do not.
            roots.add((root.packageName?.toString() ?: "w${w.id}") to root)
        }
        if (roots.isEmpty() && windows.isEmpty()) svc.rootInActiveWindow?.let { roots.add("w0" to it) }
        val pkg = currentPackage()
        if (pkg != refPackage) { refPackage = pkg; state = states.getOrPut(pkg ?: "") { RefState() } }
        val out = ArrayList<Rec>(256); val occurrence = HashMap<String, Int>(); keyParent.clear()
        for ((prefix, root) in roots) walk(root, 0, null, out, occurrence, prefix)
        return out
    }

    /** Package of the active window (the one receiving input), falling back to the active root and the last activity event. */
    fun currentPackage(): String? {
        val windows = try { svc.windows } catch (_: Exception) { emptyList<AccessibilityWindowInfo>() }
        val active = windows.firstOrNull { it.isActive }
        // An active window without a retrievable tree (some system pages) must not fall back to a stale root: trust the last activity event.
        if (active != null && active.root == null) return svc.foregroundPackage ?: svc.rootInActiveWindow?.packageName?.toString()
        return active?.root?.packageName?.toString() ?: svc.rootInActiveWindow?.packageName?.toString() ?: svc.foregroundPackage
    }

    /** Title of the active window when it exposes no tree, else null. */
    fun activeWindowWithoutTree(): String? = try { svc.windows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root == null }?.let { it.title?.toString() ?: "untitled" } } catch (_: Exception) { null }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, parentKey: String?, out: MutableList<Rec>, occ: MutableMap<String, Int>, prefix: String) {
        if (depth > 60 || out.size > 5000) return
        val cls = node.className?.toString() ?: ""
        val text = node.text?.takeIf { !node.isShowingHintText }?.toString()?.take(300) ?: ""
        val desc = node.contentDescription?.toString()?.take(300) ?: ""
        val hint = node.hintText?.toString()?.take(100) ?: ""
        val id = node.viewIdResourceName ?: ""
        val interactive = node.isClickable || node.isLongClickable || node.isEditable || node.isCheckable || node.isScrollable
        val include = node.isVisibleToUser && (interactive || text.isNotEmpty() || desc.isNotEmpty() || (hint.isNotEmpty() && cls.contains("EditText")))
        var key: String? = null
        if (include) {
            val base = "$prefix|$cls|$id|${if (node.isEditable) "" else text}|$desc"
            val n = occ.merge(base, 1, Int::plus)!!
            key = "$base#$n"; keyParent[key] = parentKey
            // Clickable containers (list rows, cards) usually carry their label in children; surface it as ~"…".
            val derived = if ((node.isClickable || node.isLongClickable) && !node.isScrollable && text.isEmpty() && desc.isEmpty() && !node.isEditable) derivedLabel(node) else ""
            val r = Rect(); node.getBoundsInScreen(r)
            out.add(Rec(key, node, depth, parentKey, interactive, "$text $desc $hint $id $derived", formatLine(node, cls, text, desc, hint, id, derived), r, text.ifEmpty { desc.ifEmpty { derived } }, derived.isNotEmpty()))
        }
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; walk(child, depth + 1, key ?: parentKey, out, occ, prefix) }
    }

    private fun derivedLabel(n: AccessibilityNodeInfo): String {
        val parts = ArrayList<String>(3)
        fun visit(x: AccessibilityNodeInfo, depth: Int) {
            if (parts.size >= 3 || depth > 5) return
            for (i in 0 until x.childCount) {
                val c = x.getChild(i) ?: continue
                val t = c.text?.takeIf { !c.isShowingHintText }?.toString()?.trim().orEmpty().ifEmpty { c.contentDescription?.toString()?.trim().orEmpty() }
                if (t.isNotEmpty()) parts.add(t.take(60)) else visit(c, depth + 1)
                if (parts.size >= 3) return
            }
        }
        visit(n, 0)
        return parts.joinToString(" · ")
    }

    private val clickableRoles = setOf("button", "link", "imgbtn", "checkbox", "radio", "switch", "tab", "dropdown", "input")
    /**
     * Compact line: `role "text" (desc) hint:"…" #id [flags] (cx,cy)`. Clickability is implied by the role, ids are shown
     * only for nodes without a label (icon buttons), bounds are the center unless `fullBounds` is set.
     */
    private fun formatLine(n: AccessibilityNodeInfo, cls: String, text: String, desc: String, hint: String, id: String, derived: String): String {
        val role = roleOf(cls, n)
        val sb = StringBuilder(role)
        if (text.isNotEmpty()) sb.append(" \"").append(text.replace("\n", "⏎")).append('"')
        else if (derived.isNotEmpty()) sb.append(" ~\"").append(derived.replace("\n", " ")).append('"')
        if (desc.isNotEmpty() && desc != text) sb.append(" (").append(desc.replace("\n", " ")).append(')')
        if (n.isEditable && text.isEmpty() && hint.isNotEmpty()) sb.append(" hint:\"").append(hint).append('"')
        if (id.isNotEmpty() && text.isEmpty() && desc.isEmpty() && derived.isEmpty()) sb.append(" #").append(id.substringAfter('/'))
        val flags = ArrayList<String>(4)
        if (n.isClickable && role !in clickableRoles) flags.add("clk"); if (n.isLongClickable && !n.isClickable) flags.add("long")
        if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "unchecked")
        if (n.isSelected) flags.add("sel"); if (n.isFocused) flags.add("focus"); if (n.isScrollable && role != "list") flags.add("scroll")
        if (!n.isEnabled) flags.add("disabled"); if (n.isPassword) flags.add("pwd")
        if (flags.isNotEmpty()) sb.append(" [").append(flags.joinToString(" ")).append(']')
        val r = Rect(); n.getBoundsInScreen(r)
        if (fullBounds) sb.append(" (").append(r.left).append(',').append(r.top).append(' ').append(r.width()).append('x').append(r.height()).append(')')
        else sb.append(" (").append(r.centerX()).append(',').append(r.centerY()).append(')')
        return sb.toString()
    }

    private fun roleOf(cls: String, n: AccessibilityNodeInfo): String = when {
        n.isEditable || cls.endsWith("EditText") -> "input"
        cls.endsWith("CheckBox") -> "checkbox"
        cls.endsWith("RadioButton") -> "radio"
        cls.endsWith("Switch") || cls.endsWith("SwitchCompat") || cls.endsWith("ToggleButton") -> "switch"
        cls.endsWith("Button") -> "button"
        cls.endsWith("TextView") -> if (n.isClickable) "link" else "text"
        cls.endsWith("ImageView") -> if (n.isClickable) "imgbtn" else "image"
        cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView") || cls.endsWith("ScrollView") || cls.endsWith("ViewPager") || cls.endsWith("ViewPager2") -> "list"
        cls.endsWith("WebView") -> "webview"
        cls.endsWith("Spinner") -> "dropdown"
        cls.endsWith("SeekBar") -> "slider"
        cls.endsWith("ProgressBar") -> "progress"
        cls.endsWith("Tab") || cls.endsWith("TabView") -> "tab"
        n.isScrollable -> "list"
        n.isCheckable -> "checkbox"
        n.isClickable -> "button"
        else -> "group"
    }

    // ---- snapshots --------------------------------------------------------------------------------------------

    /** Full or delta snapshot per the params documented on get_screen_snapshot. */
    @Synchronized
    fun snapshot(params: JSONObject, compact: Boolean = false): JSONObject {
        val maxNodes = params.optInt("maxNodes", if (compact) 30 else 120).coerceIn(1, 500)
        val maxChars = params.optInt("maxChars", if (compact) 1500 else 12000).coerceIn(500, 50000)
        val offset = params.optInt("offset", 0).coerceAtLeast(0)
        val query = params.optString("query", "").trim().lowercase()
        val scope = params.optString("scope", "")
        val interactiveOnly = params.optBoolean("interactiveOnly", false)
        val verbose = params.optBoolean("verbose", false)
        fullBounds = params.optString("bounds", "center") == "full"

        var recs: List<Rec> = collect()
        // Row collapsing: a clickable row that already carries a derived label hides its plain-text children (they repeat the label).
        if (!verbose) {
            val collapsed = HashSet<String>()
            recs = recs.filter { rec ->
                val under = rec.parentKey != null && rec.parentKey in collapsed
                if (rec.derived) collapsed.add(rec.key)
                if (under && !rec.interactive) { collapsed.add(rec.key); false } else true
            }
        }
        if (scope.isNotEmpty()) {
            val scopeKey = keyByRef[parseRef(scope)] ?: fail("STALE_REF", "Scope $scope is unknown; take a new snapshot.")
            val included = HashSet<String>(); included.add(scopeKey)
            recs = recs.filter { (it.key == scopeKey || (it.parentKey != null && it.parentKey in included)).also { ok -> if (ok) included.add(it.key) } }
            if (recs.isEmpty()) fail("STALE_REF", "Scope $scope is no longer on screen; take a new snapshot.")
        }
        if (interactiveOnly) recs = recs.filter { it.interactive }
        if (query.isNotEmpty()) recs = recs.filter { it.text.lowercase().contains(query) }

        val minDepth = recs.minOfOrNull { it.depth } ?: 0
        val lines = LinkedHashMap<Int, String>(); var chars = 0; var truncated = false; var index = offset
        val marks = ArrayList<Pair<Int, Rect>>()
        while (index < recs.size) {
            val rec = recs[index]
            val ref = refByKey.getOrPut(rec.key) { state.nextRef++ }; keyByRef[ref] = rec.key
            val line = "@$ref ${" ".repeat((rec.depth - minDepth).coerceIn(0, 6))}${rec.line}"
            if (lines.size >= maxNodes || chars + line.length > maxChars) { truncated = true; break }
            lines[ref] = line; chars += line.length; index++
            if (rec.interactive) marks.add(ref to rec.rect)
        }
        lastMarks = marks

        val version = "v${++versionCounter}"
        val result = JSONObject()
            .put("version", version)
            .put("package", currentPackage())
            .put("activity", svc.foregroundActivity?.takeIf { svc.foregroundPackage == currentPackage() }?.substringAfterLast('.'))
            .put("screen", screenInfo())
            .put("total", recs.size)
        if (keyboardVisible()) result.put("keyboard", true)
        activeWindowWithoutTree()?.let { result.put("noTree", true).put("window", it).put("hint", "The foreground window exposes no accessibility tree; use take_screenshot and coordinate taps, or press back.") }
        if (truncated) result.put("truncated", true).put("nextOffset", index)
        if (offset == 0) { history.addLast(version to lines); while (history.size > 6) history.removeFirst() }

        val since = params.optString("since", "")
        val baseline = if (since.isNotEmpty() && offset == 0) history.firstOrNull { it.first == since }?.second else null
        if (since.isNotEmpty() && baseline == null) result.put("reset", true)
        if (baseline != null) {
            val changed = JSONArray(); val removed = JSONArray()
            for ((ref, line) in lines) if (baseline[ref] != line) changed.put(line)
            for (ref in baseline.keys) if (ref !in lines) removed.put("@$ref")
            result.put("since", since).put("changed", changed).put("removed", removed)
        } else {
            result.put("nodes", JSONArray(lines.values.toList()))
        }
        return result
    }

    /** Assigns (or reuses) the ref for a collected node and returns its snapshot line. */
    @Synchronized
    fun refFor(rec: Rec): String { val ref = refByKey.getOrPut(rec.key) { state.nextRef++ }; keyByRef[ref] = rec.key; return "@$ref ${rec.line}" }

    /** Full text under a ref (or the whole screen), one node per line, untruncated. */
    @Synchronized
    fun readText(params: JSONObject): JSONObject {
        val maxChars = params.optInt("maxChars", 50_000).coerceIn(500, 200_000); val offset = params.optInt("offset", 0).coerceAtLeast(0)
        val ref = params.optString("ref", "")
        val root: AccessibilityNodeInfo? = if (ref.isNotEmpty()) resolve(ref).node else null
        val sb = StringBuilder()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60 || sb.length > maxChars + offset + 1000) return
            if (n.isVisibleToUser) {
                val t = n.text?.takeIf { !n.isShowingHintText }?.toString()?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) sb.append(t).append('\n') else if (d.isNotEmpty() && (n.childCount == 0 || n.isClickable)) sb.append(d).append('\n')
            }
            for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; visit(c, depth + 1) }
        }
        if (root != null) visit(root, 0) else {
            val roots = collect().filter { it.depth == 0 }.map { it.node }.ifEmpty { listOfNotNull(svc.rootInActiveWindow) }
            for (r in roots) visit(r, 0)
        }
        val all = sb.toString()
        val slice = all.substring(minOf(offset, all.length)).take(maxChars)
        val r = JSONObject().put("text", slice).put("chars", all.length).put("package", currentPackage())
        if (offset + slice.length < all.length) r.put("truncated", true).put("nextOffset", offset + slice.length)
        return r
    }

    /**
     * Finds the node an agent means by `text`: exact label match first, then prefix, then substring, over text /
     * description / derived row label. Interactive nodes win over plain text; ambiguity is an error listing refs.
     */
    @Synchronized
    fun findByText(text: String, recs: List<Rec> = collect()): Rec {
        val q = text.trim().lowercase(); if (q.isEmpty()) fail("BAD_ARGS", "text is empty")
        fun label(r: Rec) = r.label.lowercase()
        val tiers = listOf<(Rec) -> Boolean>({ label(it) == q }, { label(it).startsWith(q) }, { label(it).contains(q) }, { it.text.lowercase().contains(q) })
        for (tier in tiers) {
            val hits = recs.filter(tier)
            if (hits.isEmpty()) continue
            val interactive = hits.filter { it.interactive }
            val pick = interactive.ifEmpty { hits }
            if (pick.size == 1) return pick[0]
            // Same label repeated inside one row (e.g. title + container): prefer the outermost interactive one.
            val outer = pick.filter { p -> pick.none { o -> o !== p && isAncestor(o, p) } }
            if (outer.size == 1) return outer[0]
            fail("AMBIGUOUS_TEXT", "'$text' matches ${outer.size} elements: ${outer.take(6).joinToString(" | ") { refFor(it) }}. Use a ref.")
        }
        fail("TEXT_NOT_FOUND", "No visible element with text '$text'. Take a snapshot or use scroll_until.")
    }

    private fun isAncestor(a: Rec, b: Rec): Boolean { var k = b.parentKey; while (k != null) { if (k == a.key) return true; k = keyParent[k] } ; return false }
    private val keyParent = HashMap<String, String?>()

    fun parseRef(s: String): Int = s.trim().removePrefix("@").toIntOrNull() ?: fail("BAD_REF", "Expected an @ref like @12, got '$s'.")

    /** Finds the live node for an @ref by re-collecting and matching its identity key. */
    @Synchronized
    fun resolve(refStr: String): Rec {
        val ref = parseRef(refStr)
        val recs = collect()
        val key = keyByRef[ref] ?: fail("STALE_REF", "@$ref is unknown in this app; take a new snapshot.")
        return recs.firstOrNull { it.key == key } ?: fail("STALE_REF", "@$ref is no longer on screen; take a new snapshot.")
    }

    fun screenInfo(): JSONObject {
        val b = svc.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val orientation = if (svc.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        return JSONObject().put("w", b.width()).put("h", b.height()).put("orientation", orientation)
    }

    /** One entry per accessibility window: what the service can see and whether the window exposes a tree. */
    fun windowSummary(): JSONArray {
        val arr = JSONArray()
        val windows = try { svc.windows } catch (_: Exception) { return arr }
        for (w in windows.sortedByDescending { it.layer }) {
            val root = w.root
            val type = when (w.type) { AccessibilityWindowInfo.TYPE_APPLICATION -> "app"; AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"; AccessibilityWindowInfo.TYPE_SYSTEM -> "system"; AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y"; else -> w.type.toString() }
            arr.put(JSONObject().put("id", w.id).put("type", type).put("title", w.title?.toString()).put("package", root?.packageName?.toString())
                .put("active", w.isActive).put("focused", w.isFocused).put("layer", w.layer).put("tree", root != null).put("children", root?.childCount ?: -1))
        }
        return arr
    }

    fun keyboardVisible(): Boolean = try { svc.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } } catch (_: Exception) { false }

    // ---- waiting ----------------------------------------------------------------------------------------------

    /**
     * Waits for the UI to react to an action and then go quiet. First waits up to `reactMs` for any accessibility event
     * newer than `since` (an action with no visible effect returns after that), then until 250ms pass without events,
     * bounded by `maxMs`.
     */
    fun settle(since: Long = System.currentTimeMillis(), reactMs: Long = 700, maxMs: Long = 2000, window: Boolean = false) {
        val start = System.currentTimeMillis()
        // Launch-type actions: wait for a new window (activity/dialog) rather than the first content tick of the old one.
        if (window) { while (svc.lastWindowChange < since && System.currentTimeMillis() - start < 2500) Thread.sleep(40) }
        while (svc.lastContentChange < since && System.currentTimeMillis() - start < reactMs) Thread.sleep(30)
        if (svc.lastContentChange < since) return
        while (System.currentTimeMillis() - svc.lastContentChange < 250 && System.currentTimeMillis() - start < maxMs) Thread.sleep(40)
    }

    fun waitFor(params: JSONObject, deadline: Deadline): JSONObject {
        val text = params.optString("text", "").trim().lowercase()
        val pkg = params.optString("package", "").trim()
        val gone = params.optBoolean("gone", false)
        if (text.isEmpty() && pkg.isEmpty()) fail("BAD_ARGS", "Provide text or package to wait for.")
        val timeout = params.optLong("timeout", 10_000).coerceIn(1000, 60_000)
        val end = minOf(System.currentTimeMillis() + timeout, deadline.at - 500)
        val started = System.currentTimeMillis()
        while (true) {
            val pkgOk = pkg.isEmpty() || currentPackage() == pkg
            var match: Rec? = null
            val textOk = if (text.isEmpty()) true else {
                match = collect().firstOrNull { it.text.lowercase().contains(text) }
                if (gone) match == null else match != null
            }
            if (pkgOk && textOk) {
                val r = JSONObject().put("found", true).put("elapsedMs", System.currentTimeMillis() - started).put("package", currentPackage())
                match?.let { val ref = refByKey.getOrPut(it.key) { state.nextRef++ }; keyByRef[ref] = it.key; r.put("match", "@$ref ${it.line}") }
                return r
            }
            if (System.currentTimeMillis() >= end) return JSONObject().put("found", false).put("elapsedMs", System.currentTimeMillis() - started).put("package", currentPackage()).put("error", "WAIT_TIMEOUT: condition not met within ${timeout}ms")
            Thread.sleep(250)
        }
    }
}
