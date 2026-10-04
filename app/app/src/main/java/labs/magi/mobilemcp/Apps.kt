package labs.magi.mobilemcp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Launchable app discovery and launching, so agents never have to navigate the launcher. */
class Apps(private val svc: MobileAccessibilityService, private val obs: ScreenObserver) {
    private data class App(val pkg: String, val label: String)

    private fun launchable(): List<App> {
        val pm = svc.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    fun list(query: String, limit: Int): JSONObject {
        val q = query.trim().lowercase()
        val all = launchable().filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        val arr = JSONArray(); all.take(limit.coerceIn(1, 500)).forEach { arr.put(JSONObject().put("package", it.pkg).put("label", it.label)) }
        return JSONObject().put("apps", arr).put("total", all.size)
    }

    fun open(app: String): JSONObject {
        val apps = launchable(); val q = app.trim()
        val target = apps.firstOrNull { it.pkg.equals(q, true) } ?: apps.firstOrNull { it.label.equals(q, true) } ?: run {
            val matches = apps.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }
            when (matches.size) {
                1 -> matches[0]
                0 -> fail("APP_NOT_FOUND", "No launchable app matches '$q'. Use list_apps.")
                else -> fail("APP_AMBIGUOUS", "'$q' matches: ${matches.take(8).joinToString { "${it.label} (${it.pkg})" }}")
            }
        }
        val intent = svc.packageManager.getLaunchIntentForPackage(target.pkg) ?: fail("APP_NOT_FOUND", "${target.pkg} has no launch intent")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        svc.startActivity(intent)
        val foreground = awaitForeground(target.pkg, 5000)
        return JSONObject().put("ok", true).put("package", target.pkg).put("label", target.label).put("foreground", foreground)
    }

    fun openUrl(url: String): JSONObject {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { svc.startActivity(intent) } catch (_: ActivityNotFoundException) { fail("NO_HANDLER", "No app handles $url") }
        obs.settle(since = System.currentTimeMillis() - 50, reactMs = 3000, maxMs = 4000)
        return JSONObject().put("ok", true).put("url", url).put("package", obs.currentPackage())
    }

    private fun awaitForeground(pkg: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (obs.currentPackage() == pkg) { obs.settle(since = System.currentTimeMillis() - 50, reactMs = 300, maxMs = 1500); return true }; Thread.sleep(150) }
        return false
    }
}
