package labs.magi.mobilemcp

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.Surface
import org.json.JSONObject
import kotlin.math.roundToInt

/** Settings pages, arbitrary intents, media/volume/brightness/rotation/DND/torch/wake. */
class SystemActions(private val svc: MobileAccessibilityService) {
    private val pages = mapOf(
        "main" to Settings.ACTION_SETTINGS, "wifi" to Settings.ACTION_WIFI_SETTINGS, "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "internet_panel" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "wifi_panel" to Settings.Panel.ACTION_WIFI, "volume_panel" to Settings.Panel.ACTION_VOLUME,
        "display" to Settings.ACTION_DISPLAY_SETTINGS, "sound" to Settings.ACTION_SOUND_SETTINGS, "notifications" to "android.settings.NOTIFICATION_SETTINGS",
        "apps" to Settings.ACTION_APPLICATION_SETTINGS, "app_info" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "app_notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS, "date" to Settings.ACTION_DATE_SETTINGS, "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "security" to Settings.ACTION_SECURITY_SETTINGS, "battery" to "android.settings.BATTERY_SAVER_SETTINGS", "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS, "mobile_data" to Settings.ACTION_DATA_ROAMING_SETTINGS, "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "home" to Settings.ACTION_HOME_SETTINGS, "lock_screen" to "android.settings.LOCK_SCREEN_SETTINGS", "language" to Settings.ACTION_LOCALE_SETTINGS,
        "input_method" to Settings.ACTION_INPUT_METHOD_SETTINGS, "nfc" to Settings.ACTION_NFC_SETTINGS, "vpn" to "android.settings.VPN_SETTINGS",
        "print" to Settings.ACTION_PRINT_SETTINGS, "about" to Settings.ACTION_DEVICE_INFO_SETTINGS,
    )

    fun openSettings(page: String, pkg: String): JSONObject {
        val action = pages[page] ?: fail("BAD_ARGS", "Unknown page '$page'. Known: ${pages.keys.sorted()}")
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (page == "app_info") intent.data = Uri.parse("package:" + pkg.ifEmpty { fail("BAD_ARGS", "app_info needs package") })
        if (page == "app_notifications") intent.putExtra(Settings.EXTRA_APP_PACKAGE, pkg.ifEmpty { fail("BAD_ARGS", "app_notifications needs package") })
        try { svc.startActivity(intent) } catch (e: Exception) { fail("NO_HANDLER", "This device has no '$page' page (${e.javaClass.simpleName})") }
        return JSONObject().put("ok", true).put("page", page)
    }

    fun startIntent(p: JSONObject): JSONObject {
        val intent = Intent(p.getString("action")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val data = p.optString("data", ""); val type = p.optString("type", "")
        if (data.isNotEmpty() && type.isNotEmpty()) intent.setDataAndType(Uri.parse(data), type) else if (data.isNotEmpty()) intent.data = Uri.parse(data) else if (type.isNotEmpty()) intent.type = type
        p.optString("package", "").takeIf { it.isNotEmpty() }?.let { intent.setPackage(it) }
        p.optString("component", "").takeIf { it.isNotEmpty() }?.let { intent.component = ComponentName.unflattenFromString(it) ?: fail("BAD_ARGS", "component must be package/Class") }
        p.optJSONArray("categories")?.let { for (i in 0 until it.length()) intent.addCategory(it.getString(i)) }
        p.optJSONObject("extras")?.let { ex -> for (k in ex.keys()) when (val v = ex.get(k)) { is Boolean -> intent.putExtra(k, v); is Int -> intent.putExtra(k, v); is Long -> intent.putExtra(k, v); is Double -> intent.putExtra(k, v); else -> intent.putExtra(k, v.toString()) } }
        try { svc.startActivity(intent) } catch (e: Exception) { fail("INTENT_FAILED", "${e.javaClass.simpleName}: ${e.message}") }
        return JSONObject().put("ok", true).put("action", intent.action)
    }

    fun uninstall(pkg: String): JSONObject {
        try { svc.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { fail("INTENT_FAILED", e.message ?: "failed") }
        return JSONObject().put("ok", true).put("package", pkg)
    }

    fun media(command: String): JSONObject {
        val code = when (command) { "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; "stop" -> KeyEvent.KEYCODE_MEDIA_STOP; else -> fail("BAD_ARGS", "Unknown media command") }
        val am = svc.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return JSONObject().put("ok", true).put("command", command).put("musicActive", am.isMusicActive)
    }

    private val streams = mapOf("music" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING, "alarm" to AudioManager.STREAM_ALARM, "notification" to AudioManager.STREAM_NOTIFICATION, "call" to AudioManager.STREAM_VOICE_CALL, "system" to AudioManager.STREAM_SYSTEM)
    fun volume(p: JSONObject): JSONObject {
        val am = svc.getSystemService(AudioManager::class.java)
        val name = p.optString("stream", "music"); val stream = streams[name] ?: fail("BAD_ARGS", "Unknown stream")
        try {
            if (p.has("level")) am.setStreamVolume(stream, (p.getInt("level") / 100.0 * am.getStreamMaxVolume(stream)).roundToInt(), AudioManager.FLAG_SHOW_UI)
            else when (p.optString("direction", "")) {
                "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            }
        } catch (e: SecurityException) { fail("VOLUME_DENIED", "Blocked by Do Not Disturb policy; grant notification access or change DND first.") }
        val levels = JSONObject(); for ((n, s) in streams) levels.put(n, (am.getStreamVolume(s) * 100.0 / am.getStreamMaxVolume(s)).roundToInt())
        return JSONObject().put("ok", true).put("levels", levels).put("ringerMode", when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" })
    }

    private fun requireWriteSettings() { if (!Settings.System.canWrite(svc)) fail("NO_WRITE_SETTINGS", "Grant \"Modify system settings\" from the MobileMCP app first.") }

    fun brightness(p: JSONObject): JSONObject {
        requireWriteSettings(); val cr = svc.contentResolver
        if (p.has("auto")) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, if (p.getBoolean("auto")) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        if (p.has("level")) { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL); Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (p.getInt("level") / 100.0 * 255).roundToInt().coerceIn(1, 255)) }
        val level = (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100.0 / 255).roundToInt()
        val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        return JSONObject().put("ok", true).put("level", level).put("auto", auto)
    }

    fun rotation(mode: String): JSONObject {
        requireWriteSettings(); val cr = svc.contentResolver
        when (mode) {
            "auto" -> Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
            "portrait" -> { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0); Settings.System.putInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_0) }
            "landscape" -> { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0); Settings.System.putInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_90) }
            else -> fail("BAD_ARGS", "mode must be auto, portrait or landscape")
        }
        return JSONObject().put("ok", true).put("mode", mode)
    }

    fun dnd(mode: String): JSONObject {
        val nm = svc.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) fail("NO_NOTIFICATION_ACCESS", "Grant notification access from the MobileMCP app first.")
        nm.setInterruptionFilter(when (mode) { "off" -> NotificationManager.INTERRUPTION_FILTER_ALL; "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY; "alarms_only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS; "total_silence" -> NotificationManager.INTERRUPTION_FILTER_NONE; else -> fail("BAD_ARGS", "Unknown mode") })
        return JSONObject().put("ok", true).put("mode", mode)
    }

    fun flashlight(on: Boolean): JSONObject {
        val cm = svc.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { val c = cm.getCameraCharacteristics(it); c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: fail("NO_FLASH", "No back camera flash")
        try { cm.setTorchMode(id, on) } catch (e: Exception) { fail("FLASH_FAILED", e.message ?: "camera in use") }
        return JSONObject().put("ok", true).put("on", on)
    }

    @Suppress("DEPRECATION")
    fun wake(): JSONObject {
        val pm = svc.getSystemService(PowerManager::class.java)
        val wasInteractive = pm.isInteractive
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "mobilemcp:wake").acquire(5000)
        Thread.sleep(400)
        return JSONObject().put("ok", true).put("wasInteractive", wasInteractive).put("interactive", pm.isInteractive).put("locked", svc.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
    }

    companion object {
        fun permissions(ctx: Context): JSONObject = JSONObject()
            .put("notifications", MobileNotificationListener.instance != null)
            .put("writeSettings", Settings.System.canWrite(ctx))
            .put("ignoreBatteryOptimizations", ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName))
    }
}
