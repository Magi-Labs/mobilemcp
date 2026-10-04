package labs.magi.mobilemcp

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Optional second service: reads the notification shade and triggers actions/replies. Enabled by the user in Settings → Notification access. */
class MobileNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: MobileNotificationListener? = null
            private set
        fun require(): MobileNotificationListener = instance ?: fail("NO_NOTIFICATION_ACCESS", "Notification access is not granted. In the MobileMCP app tap \"Notification access\" and enable MobileMCP.")
    }

    override fun onListenerConnected() { instance = this; Status.log("Notification access on") }
    override fun onListenerDisconnected() { instance = null; Status.log("Notification access off") }

    private fun find(key: String): StatusBarNotification = activeNotifications.firstOrNull { it.key == key } ?: fail("NOTIFICATION_GONE", "No active notification with key $key; call get_notifications again.")

    fun list(pkg: String, query: String, limit: Int, includeOngoing: Boolean): JSONObject {
        val pm = packageManager
        val all = (activeNotifications ?: emptyArray()).filter { sbn ->
            (pkg.isEmpty() || sbn.packageName == pkg) && (includeOngoing || !sbn.isOngoing) && (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        }.sortedByDescending { it.postTime }
        val q = query.lowercase()
        val arr = JSONArray()
        for (sbn in all) {
            val n = sbn.notification; val e = n.extras
            val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() } ?: ""
            val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            if (q.isNotEmpty() && !"$title $text $lines $sub".lowercase().contains(q)) continue
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
            val actions = JSONArray()
            n.actions?.forEachIndexed { i, a -> actions.put(JSONObject().put("index", i).put("title", a.title?.toString() ?: "").put("reply", a.remoteInputs?.isNotEmpty() == true)) }
            val o = JSONObject().put("key", sbn.key).put("package", sbn.packageName).put("app", label).put("time", sbn.postTime).put("title", title).put("text", text.take(2000))
            if (lines.isNotEmpty()) o.put("lines", lines.take(2000)); if (sub.isNotEmpty()) o.put("subText", sub)
            if (sbn.isOngoing) o.put("ongoing", true); if (n.category != null) o.put("category", n.category)
            if (actions.length() > 0) o.put("actions", actions)
            arr.put(o); if (arr.length() >= limit) break
        }
        return JSONObject().put("notifications", arr).put("total", all.size)
    }

    fun open(key: String): JSONObject {
        val sbn = find(key)
        val pi = sbn.notification.contentIntent ?: fail("NO_CONTENT_INTENT", "This notification has nothing to open.")
        pi.send()
        return JSONObject().put("ok", true).put("package", sbn.packageName)
    }

    fun act(key: String, which: Any, text: String?): JSONObject {
        val sbn = find(key); val actions = sbn.notification.actions ?: fail("NO_ACTIONS", "This notification has no actions.")
        val action = when (which) {
            is Int -> actions.getOrNull(which)
            else -> actions.firstOrNull { it.title?.toString().equals(which.toString(), true) } ?: actions.firstOrNull { it.title?.toString()?.contains(which.toString(), true) == true }
        } ?: fail("NO_SUCH_ACTION", "Actions: ${actions.mapIndexed { i, a -> "$i:${a.title}" }}")
        val inputs = action.remoteInputs
        if (!inputs.isNullOrEmpty()) {
            if (text.isNullOrEmpty()) fail("REPLY_TEXT_REQUIRED", "Action '${action.title}' expects text.")
            val intent = Intent(); val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(this, 0, intent)
            return JSONObject().put("ok", true).put("action", action.title?.toString()).put("replied", true)
        }
        action.actionIntent.send()
        return JSONObject().put("ok", true).put("action", action.title?.toString())
    }

    fun dismiss(key: String?, all: Boolean): JSONObject {
        if (all) { cancelAllNotifications(); return JSONObject().put("ok", true).put("all", true) }
        find(key ?: fail("BAD_ARGS", "key or all:true required")); cancelNotification(key)
        return JSONObject().put("ok", true).put("key", key)
    }
}
