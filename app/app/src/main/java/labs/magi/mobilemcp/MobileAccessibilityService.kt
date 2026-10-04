package labs.magi.mobilemcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent

/**
 * The only privileged component: reads the screen tree, performs node actions and gestures, takes screenshots.
 * It also owns the hub connection, so nothing needs a foreground service - the system keeps it alive while enabled.
 */
class MobileAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: MobileAccessibilityService? = null
            private set
    }

    lateinit var hub: HubConnection; private set
    @Volatile var foregroundPackage: String? = null
    @Volatile var foregroundActivity: String? = null
    @Volatile var lastContentChange: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        instance = this
        hub = HubConnection(this, Dispatcher(this))
        Status.set("Service on")
        if (Prefs.autoConnect(this) && Prefs.hubUrl(this).isNotBlank()) hub.connect()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString(); val cls = event.className?.toString()
                // Activities report their class here; widgets (dialogs, menus) report android.widget.* and are ignored.
                if (pkg != null && cls != null && !cls.startsWith("android.widget.") && !cls.startsWith("android.view.")) {
                    foregroundPackage = pkg; foregroundActivity = cls
                }
                lastContentChange = System.currentTimeMillis()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED -> lastContentChange = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (this::hub.isInitialized) hub.disconnect()
        instance = null
        Status.set("Service off")
        return super.onUnbind(intent)
    }
}
