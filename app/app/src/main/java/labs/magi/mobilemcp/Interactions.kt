package labs.magi.mobilemcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Taps, typing, scrolling, swipes and system keys. Prefers semantic node actions, falls back to real gestures. */
class Interactions(private val svc: MobileAccessibilityService, private val obs: ScreenObserver) {

    fun tap(p: JSONObject): JSONObject {
        val long = p.optBoolean("longPress", false)
        val ref = p.optString("ref", "")
        if (ref.isNotEmpty()) {
            val rec = obs.resolve(ref)
            var n: AccessibilityNodeInfo? = rec.node; var hops = 0
            while (n != null && hops < 6) {
                val can = if (long) n.isLongClickable else n.isClickable
                if (can && n.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK))
                    return JSONObject().put("ok", true).put("method", if (hops == 0) "node" else "ancestor").put("target", rec.line)
                n = n.parent; hops++
            }
            val r = Rect(); rec.node.getBoundsInScreen(r)
            if (!gestureTap(r.centerX(), r.centerY(), long)) fail("TAP_FAILED", "Neither node action nor gesture succeeded on $ref")
            return JSONObject().put("ok", true).put("method", "gesture").put("target", rec.line).put("x", r.centerX()).put("y", r.centerY())
        }
        if (!p.has("x") || !p.has("y")) fail("BAD_ARGS", "Pass ref or both x and y.")
        val x = p.getInt("x"); val y = p.getInt("y")
        if (!gestureTap(x, y, long)) fail("TAP_FAILED", "Gesture at $x,$y was cancelled")
        return JSONObject().put("ok", true).put("method", "gesture").put("x", x).put("y", y)
    }

    fun type(p: JSONObject): JSONObject {
        val text = p.optString("text", ""); val clear = p.optBoolean("clear", false); val submit = p.optBoolean("submit", false)
        val ref = p.optString("ref", "")
        var node = if (ref.isNotEmpty()) obs.resolve(ref).node else focusedEditable() ?: fail("NO_INPUT", "No focused editable field; tap a field first or pass ref.")
        if (!node.isEditable) node = findEditable(node, 0) ?: fail("NOT_EDITABLE", "Target is not an editable field.")
        if (!node.isFocused) { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS); node.refresh() }
        val existing = if (clear || node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text) }
        var method = "setText"
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            setClipboard(text); method = "paste"
            if (clear) {
                val len = node.text?.length ?: 0
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len) })
            }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) fail("TYPE_FAILED", "Field rejected both set-text and paste.")
        }
        var submitted = false
        if (submit) { Thread.sleep(100); node.refresh(); submitted = node.performAction(AccessibilityAction.ACTION_IME_ENTER.id) }
        node.refresh()
        return JSONObject().put("ok", true).put("method", method).put("value", node.text?.toString()?.take(500)).put("submitted", submitted)
    }

    fun scroll(p: JSONObject): JSONObject {
        val direction = p.optString("direction", "down"); val amount = p.optInt("amount", 1).coerceIn(1, 10)
        val forward = direction == "down" || direction == "right"
        val ref = p.optString("ref", "")
        var node: AccessibilityNodeInfo? = if (ref.isNotEmpty()) obs.resolve(ref).node else largestScrollable()
        var hops = 0
        while (node != null && !node.isScrollable && hops < 8) { node = node.parent; hops++ }
        if (node != null && node.isScrollable) {
            var done = 0
            repeat(amount) { if (node!!.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) { done++; Thread.sleep(300) } else return@repeat }
            if (done > 0) return JSONObject().put("ok", true).put("method", "node").put("pages", done).put("atEnd", done < amount)
        }
        // Gesture fallback: finger moves opposite to the reveal direction, inside the target or the screen.
        val region = Rect(); if (node != null) node.getBoundsInScreen(region) else screenRect().let { region.set(it) }
        val finger = when (direction) { "down" -> "up"; "up" -> "down"; "left" -> "right"; else -> "left" }
        repeat(amount) { swipeGesture(finger, region, 0, 400); Thread.sleep(250) }
        return JSONObject().put("ok", true).put("method", "gesture").put("pages", amount)
    }

    fun swipe(p: JSONObject): JSONObject {
        val duration = p.optInt("duration", 300).coerceIn(50, 5000)
        if (p.has("fromX") && p.has("fromY") && p.has("toX") && p.has("toY")) {
            val ok = gestureLine(p.getInt("fromX"), p.getInt("fromY"), p.getInt("toX"), p.getInt("toY"), duration)
            if (!ok) fail("SWIPE_FAILED", "Gesture cancelled")
            return JSONObject().put("ok", true).put("from", "${p.getInt("fromX")},${p.getInt("fromY")}").put("to", "${p.getInt("toX")},${p.getInt("toY")}")
        }
        val direction = p.optString("direction", ""); if (direction.isEmpty()) fail("BAD_ARGS", "Pass direction or from/to coordinates.")
        val region = Rect()
        val ref = p.optString("ref", ""); if (ref.isNotEmpty()) obs.resolve(ref).node.getBoundsInScreen(region) else region.set(screenRect())
        val (from, to) = swipeGesture(direction, region, p.optInt("distance", 0), duration)
        return JSONObject().put("ok", true).put("from", from).put("to", to)
    }

    /** Long-press, drag, hover, release as one continuous touch (three chained strokes). */
    fun drag(p: JSONObject): JSONObject {
        val from = point(p, "fromRef", "fromX", "fromY"); val to = point(p, "toRef", "toX", "toY")
        val hold = p.optInt("holdMs", 700).coerceIn(100, 5000).toLong()
        val move = p.optInt("moveMs", 600).coerceIn(100, 5000).toLong()
        val hover = p.optInt("hoverMs", 500).coerceIn(0, 5000).toLong()
        val (fx, fy) = from; val (tx, ty) = to
        val press = Path().apply { moveTo(fx.toFloat(), fy.toFloat()) }
        val s1 = GestureDescription.StrokeDescription(press, 0, hold, true)
        if (!dispatch(GestureDescription.Builder().addStroke(s1).build())) fail("DRAG_FAILED", "Long press was cancelled")
        val line = Path().apply { moveTo(fx.toFloat(), fy.toFloat()); lineTo(tx.toFloat(), ty.toFloat()) }
        val s2 = s1.continueStroke(line, 0, move, hover > 0)
        if (!dispatch(GestureDescription.Builder().addStroke(s2).build())) fail("DRAG_FAILED", "Drag movement was cancelled")
        if (hover > 0) {
            val stay = Path().apply { moveTo(tx.toFloat(), ty.toFloat()) }
            val s3 = s2.continueStroke(stay, 0, hover, false)
            if (!dispatch(GestureDescription.Builder().addStroke(s3).build())) fail("DRAG_FAILED", "Release was cancelled")
        }
        return JSONObject().put("ok", true).put("from", "$fx,$fy").put("to", "$tx,$ty")
    }

    /** Resolves a ref to its center or takes explicit coordinates. */
    private fun point(p: JSONObject, refKey: String, xKey: String, yKey: String): Pair<Int, Int> {
        val ref = p.optString(refKey, "")
        if (ref.isNotEmpty()) { val r = Rect(); obs.resolve(ref).node.getBoundsInScreen(r); return r.centerX() to r.centerY() }
        if (!p.has(xKey) || !p.has(yKey)) fail("BAD_ARGS", "Pass $refKey or both $xKey and $yKey.")
        return p.getInt(xKey) to p.getInt(yKey)
    }

    fun key(p: JSONObject): JSONObject {
        val key = p.optString("key", "")
        val ok = when (key) {
            "back" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "power" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            "lock" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "dismiss_notifications" -> if (Build.VERSION.SDK_INT >= 31) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "enter" -> (focusedEditable() ?: fail("NO_INPUT", "No focused field for enter.")).performAction(AccessibilityAction.ACTION_IME_ENTER.id)
            else -> fail("BAD_ARGS", "Unknown key '$key'")
        }
        if (!ok) fail("KEY_FAILED", "System refused '$key'")
        return JSONObject().put("ok", true).put("key", key)
    }

    fun setClipboard(text: String): JSONObject {
        svc.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("mobilemcp", text))
        return JSONObject().put("ok", true).put("chars", text.length)
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    fun focusedEditable(): AccessibilityNodeInfo? {
        svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        return obs.collect().firstOrNull { it.node.isEditable && it.node.isFocused }?.node ?: obs.collect().firstOrNull { it.node.isEditable }?.node
    }

    private fun findEditable(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        if (depth > 10) return null
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; findEditable(c, depth + 1)?.let { return it } }
        return null
    }

    private fun largestScrollable(): AccessibilityNodeInfo? = obs.collect().filter { it.node.isScrollable }.maxByOrNull { val r = Rect(); it.node.getBoundsInScreen(r); r.width().toLong() * r.height() }?.node

    private fun screenRect(): Rect { val s = obs.screenInfo(); return Rect(0, 0, s.getInt("w"), s.getInt("h")) }

    /** Swipes inside `region` with the finger moving in `direction`. Returns (from, to) as "x,y" strings. */
    private fun swipeGesture(direction: String, region: Rect, distance: Int, duration: Int): Pair<String, String> {
        val screen = screenRect()
        val cx = region.centerX(); val cy = region.centerY()
        val dist = if (distance > 0) distance else ((if (direction == "up" || direction == "down") region.height() else region.width()) * 0.6).toInt().coerceAtLeast(100)
        val half = dist / 2
        var (fx, fy, tx, ty) = when (direction) {
            "up" -> listOf(cx, cy + half, cx, cy - half)
            "down" -> listOf(cx, cy - half, cx, cy + half)
            "left" -> listOf(cx + half, cy, cx - half, cy)
            "right" -> listOf(cx - half, cy, cx + half, cy)
            else -> fail("BAD_ARGS", "direction must be up, down, left or right")
        }
        fx = fx.coerceIn(1, screen.width() - 2); tx = tx.coerceIn(1, screen.width() - 2)
        fy = fy.coerceIn(1, screen.height() - 2); ty = ty.coerceIn(1, screen.height() - 2)
        if (!gestureLine(fx, fy, tx, ty, duration)) fail("SWIPE_FAILED", "Gesture cancelled")
        return "$fx,$fy" to "$tx,$ty"
    }

    private fun gestureTap(x: Int, y: Int, long: Boolean): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, if (long) 800 else 60)).build())
    }

    private fun gestureLine(fx: Int, fy: Int, tx: Int, ty: Int, duration: Int): Boolean {
        val path = Path().apply { moveTo(fx.toFloat(), fy.toFloat()); lineTo(tx.toFloat(), ty.toFloat()) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong())).build())
    }

    private fun dispatch(g: GestureDescription): Boolean {
        val latch = CountDownLatch(1); var ok = false
        val accepted = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!accepted) return false
        latch.await(6, TimeUnit.SECONDS)
        return ok
    }
}
