package labs.magi.mobilemcp

import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Routes hub actions to handlers and attaches compact post-action observations. Runs on the single action thread. */
class Dispatcher(private val svc: MobileAccessibilityService) {
    private val obs = ScreenObserver(svc)
    private val inter = Interactions(svc, obs)
    private val shots = Screenshots(svc)
    private val apps = Apps(svc, obs)
    private val sys = SystemActions(svc)

    fun handle(action: String, params: JSONObject): JSONObject {
        val deadline = Deadline.from(params)
        if (deadline.expired()) fail("DEADLINE", "Request expired while queued behind another action")
        return when (action) {
            "device.info" -> info()
            "screen.snapshot" -> snapshot(params)
            "screen.screenshot" -> shots.capture(params.optInt("maxWidth", 800), params.optInt("quality", 70))
            "screen.waitFor" -> obs.waitFor(params, deadline)
            "interact.tap" -> observed(params) { inter.tap(params) }
            "interact.type" -> observed(params) { inter.type(params) }
            "interact.swipe" -> observed(params) { inter.swipe(params) }
            "interact.drag" -> observed(params) { inter.drag(params) }
            "interact.pinch" -> observed(params) { inter.pinch(params) }
            "interact.scrollUntil" -> observed(params) { inter.scrollUntil(params, deadline).also { if (it.has("error")) throw ActionError(it.getString("error")) } }
            "screen.readText" -> obs.readText(params)
            "apps.uninstall" -> observed(params) { sys.uninstall(params.optString("package", "")) }
            "notifications.list" -> MobileNotificationListener.require().list(params.optString("package", ""), params.optString("query", ""), params.optInt("limit", 30), params.optBoolean("includeOngoing", false))
            "notifications.open" -> observed(params) { MobileNotificationListener.require().open(params.optString("key", "")) }
            "notifications.act" -> MobileNotificationListener.require().act(params.optString("key", ""), params.opt("action") ?: 0, params.optString("text", "").ifEmpty { null })
            "notifications.dismiss" -> MobileNotificationListener.require().dismiss(params.optString("key", "").ifEmpty { null }, params.optBoolean("all", false))
            "system.openSettings" -> observed(params) { sys.openSettings(params.optString("page", ""), params.optString("package", "")) }
            "system.startIntent" -> observed(params) { sys.startIntent(params) }
            "system.media" -> sys.media(params.optString("command", ""))
            "system.volume" -> sys.volume(params)
            "system.brightness" -> sys.brightness(params)
            "system.rotation" -> sys.rotation(params.optString("mode", ""))
            "system.dnd" -> sys.dnd(params.optString("mode", ""))
            "system.flashlight" -> sys.flashlight(params.optBoolean("on", true))
            "system.wake" -> observed(params) { sys.wake() }
            "interact.scroll" -> observed(params) { inter.scroll(params) }
            "interact.key" -> observed(params) { inter.key(params) }
            "interact.setClipboard" -> inter.setClipboard(params.optString("text", ""))
            "apps.list" -> apps.list(params.optString("query", ""), params.optInt("limit", 100))
            "apps.open" -> observed(params) { apps.open(params.optString("app", "")) }
            "apps.openUrl" -> observed(params) { apps.openUrl(params.optString("url", "")) }
            "device.batch" -> batch(params, deadline)
            else -> fail("UNKNOWN_ACTION", action)
        }
    }

    private fun snapshot(params: JSONObject): JSONObject {
        val result = obs.snapshot(params)
        if (params.optBoolean("screenshot", false)) result.put("screenshot", shots.capture().getString("screenshot"))
        return result
    }

    private fun observed(params: JSONObject, block: () -> JSONObject): JSONObject {
        val started = System.currentTimeMillis()
        val result = block()
        result.put("elapsedMs", System.currentTimeMillis() - started)
        if (params.optBoolean("observe", true)) {
            obs.settle(since = started)
            val opts = JSONObject().put("maxNodes", params.optInt("maxNodes", 30)).put("maxChars", params.optInt("maxChars", 1500))
            params.optString("observationScope", "").takeIf { it.isNotEmpty() }?.let { opts.put("scope", it) }
            result.put("observation", try { obs.snapshot(opts, compact = true) } catch (e: ActionError) { JSONObject().put("error", e.message) })
        }
        return result
    }

    private fun info(): JSONObject {
        val bm = svc.getSystemService(BatteryManager::class.java)
        return JSONObject()
            .put("name", Prefs.displayName(svc)).put("deviceId", Prefs.deviceId(svc))
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("screen", obs.screenInfo().put("density", svc.resources.displayMetrics.density))
            .put("package", obs.currentPackage()).put("activity", svc.foregroundActivity)
            .put("keyboard", obs.keyboardVisible())
            .put("interactive", svc.getSystemService(android.os.PowerManager::class.java).isInteractive)
            .put("locked", svc.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            .put("permissions", SystemActions.permissions(svc))
            .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging", bm.isCharging)
            .put("appVersion", BuildConfig.VERSION_NAME)
    }

    private fun batch(params: JSONObject, deadline: Deadline): JSONObject {
        val steps = params.optJSONArray("steps") ?: fail("BAD_ARGS", "steps[] required")
        if (steps.length() == 0 || steps.length() > 20) fail("BAD_ARGS", "1-20 steps")
        val results = JSONArray(); var error: String? = null; var completed = 0
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i); val kind = step.optString("action", "")
            if (deadline.remaining < 500) { error = "BATCH_TIMEOUT: budget exhausted before step $i ($kind)"; break }
            val stepStart = System.currentTimeMillis()
            try {
                val r = when (kind) {
                    "tap" -> inter.tap(step)
                    "type" -> inter.type(step)
                    "scroll" -> inter.scroll(step)
                    "swipe" -> inter.swipe(step)
                    "drag" -> inter.drag(step)
                    "pinch" -> inter.pinch(step)
                    "scrollUntil" -> inter.scrollUntil(step, deadline).also { if (it.has("error")) throw ActionError(it.getString("error")) }
                    "openSettings" -> sys.openSettings(step.optString("page", ""), step.optString("package", ""))
                    "readText" -> obs.readText(step)
                    "key" -> inter.key(step)
                    "openApp" -> apps.open(step.optString("app", ""))
                    "openUrl" -> apps.openUrl(step.optString("url", ""))
                    "wait" -> { Thread.sleep(step.optLong("ms", 500).coerceIn(50, minOf(30_000, deadline.remaining - 200))); JSONObject().put("ok", true) }
                    "waitFor" -> obs.waitFor(step, deadline).also { if (it.has("error")) throw ActionError(it.getString("error")) }
                    "snapshot" -> obs.snapshot(step)
                    else -> fail("BAD_ARGS", "Unknown step action '$kind'")
                }
                if (kind !in setOf("snapshot", "wait", "waitFor", "readText")) obs.settle(since = stepStart)
                results.put(r); completed++
            } catch (e: Exception) { error = "step $i ($kind): ${e.message}"; break }
        }
        val result = JSONObject().put("completed", completed).put("total", steps.length()).put("results", results)
        if (error != null) result.put("error", error)
        if (params.optBoolean("observe", true)) {
            val opts = JSONObject().put("maxNodes", params.optInt("maxNodes", 30)).put("maxChars", params.optInt("maxChars", 1500))
            params.optString("observationScope", "").takeIf { it.isNotEmpty() }?.let { opts.put("scope", it) }
            result.put("observation", try { obs.snapshot(opts, compact = true) } catch (e: ActionError) { JSONObject().put("error", e.message) })
        }
        return result
    }
}
