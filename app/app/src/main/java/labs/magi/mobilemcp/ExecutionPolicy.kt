package labs.magi.mobilemcp

/** These reads never touch ScreenObserver or occupy the user's display. */
object ExecutionPolicy {
    val readActions = setOf("device.status", "device.executionPolicy", "apps.list", "notifications.list")
    val backgroundActions = readActions + setOf(
        "device.info", "notifications.dismiss", "system.media", "system.volume", "system.brightness",
        "system.rotation", "system.dnd", "system.flashlight"
    )
    fun allowed(action: String, backgroundOnly: Boolean) = !backgroundOnly || action in backgroundActions
}
