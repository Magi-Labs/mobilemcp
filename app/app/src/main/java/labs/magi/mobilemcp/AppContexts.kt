package labs.magi.mobilemcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Metadata only: never launches an app, reads private app data or probes exported components. */
class AppContexts(private val ctx: Context) {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val file = File(ctx.filesDir, "app-contexts.json")
    private val profiles = linkedMapOf<String, JSONObject>()
    private var revision = 0L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Coalesce install/replaced/removed sequences through reconciliation, off the main thread.
            worker.execute { reconcile() }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(ctx, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        worker.execute {
            try {
                val saved = JSONObject(file.readText())
                revision = saved.optLong("revision", 0)
                val items = saved.optJSONArray("profiles") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i); profiles[item.getString("package")] = item
                }
            } catch (_: Exception) { /* First run or interrupted old cache: reconstruct metadata. */ }
            reconcile()
        }
        worker.scheduleWithFixedDelay({ try { reconcile() } catch (_: Exception) { Status.log("App registry reconciliation failed") } }, 60, 60, TimeUnit.SECONDS)
    }

    private fun reconcile() {
        val pm = ctx.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        val targets = pm.getInstalledPackages(0).filter {
            it.packageName in launchable || (it.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
        }.map { it.packageName }.toSet()
        var changed = false
        for (pkg in targets) {
            try {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS)
                val fingerprint = "${info.longVersionCode}:${info.lastUpdateTime}"
                if (profiles[pkg]?.optString("fingerprint") == fingerprint && profiles[pkg]?.optInt("schemaVersion") == 1) continue
                val exported = JSONArray()
                info.activities?.filter { it.exported }?.forEach { exported.put(JSONObject().put("kind", "activity").put("component", it.name).put("permission", it.permission ?: JSONObject.NULL).put("verified", false)) }
                info.services?.filter { it.exported }?.forEach { exported.put(JSONObject().put("kind", "service").put("component", it.name).put("permission", it.permission ?: JSONObject.NULL).put("verified", false)) }
                info.providers?.filter { it.exported }?.forEach { exported.put(JSONObject().put("kind", "provider").put("component", it.name).put("authority", it.authority).put("readPermission", it.readPermission ?: JSONObject.NULL).put("writePermission", it.writePermission ?: JSONObject.NULL).put("verified", false)) }
                val shortcuts = JSONArray()
                    .put(JSONObject().put("name", "context").put("mode", "background").put("action", "apps.list"))
                    .put(JSONObject().put("name", "notifications").put("mode", "background").put("action", "notifications.list"))
                if (pkg in launchable) shortcuts.put(JSONObject().put("name", "open").put("mode", "opens_app").put("action", "apps.open"))
                profiles[pkg] = JSONObject().put("schemaVersion", 1).put("package", pkg)
                    .put("label", pm.getApplicationLabel(info.applicationInfo!!).toString())
                    .put("versionCode", info.longVersionCode).put("versionName", info.versionName ?: "")
                    .put("lastUpdateTime", info.lastUpdateTime).put("fingerprint", fingerprint)
                    .put("rebuiltAt", System.currentTimeMillis()).put("permissions", JSONArray(info.requestedPermissions?.toList() ?: emptyList<String>()))
                    .put("exportedCandidates", exported).put("shortcuts", shortcuts)
                    .put("workflowStatus", "needs_learning").put("provenance", "Android PackageManager")
                changed = true
            } catch (_: PackageManager.NameNotFoundException) { /* Package changed during scan; next event reconciles. */ }
        }
        val removed = profiles.keys.filter { it !in targets }
        removed.forEach { profiles.remove(it); changed = true }
        if (changed) {
            revision++
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("revision", revision).put("profiles", JSONArray(profiles.values.toList())).toString())
            if (!tmp.renameTo(file)) throw IllegalStateException("Cannot persist app registry")
        }
    }

    fun list(query: String, limit: Int): JSONObject = worker.submit<JSONObject> {
        reconcile() // Also catches changes missed while the service was disconnected/asleep.
        val q = query.trim().lowercase()
        val found = profiles.values.filter { q.isEmpty() || it.getString("package").lowercase().contains(q) || it.getString("label").lowercase().contains(q) }.sortedBy { it.getString("label").lowercase() }
        JSONObject().put("revision", revision).put("total", found.size)
            .put("profiles", JSONArray(found.take(limit.coerceIn(1, 500)).map { JSONObject(it.toString()) }))
    }.get()

    fun close() { ctx.unregisterReceiver(receiver); worker.shutdownNow() }
}
