package labs.magi.mobilemcp

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import android.view.Display
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Scaled JPEG screenshots through the accessibility screenshot API (no MediaProjection prompt). */
class Screenshots(private val svc: MobileAccessibilityService) {
    fun capture(maxWidth: Int = 800, quality: Int = 70): JSONObject {
        val latch = CountDownLatch(1)
        var shot: AccessibilityService.ScreenshotResult? = null; var errorCode = -1
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) { shot = screenshot; latch.countDown() }
            override fun onFailure(code: Int) { errorCode = code; latch.countDown() }
        })
        if (!latch.await(8, TimeUnit.SECONDS)) fail("SCREENSHOT_FAILED", "Timed out waiting for the system")
        val result = shot ?: fail("SCREENSHOT_FAILED", "System error $errorCode (${describe(errorCode)})")
        val buffer = result.hardwareBuffer
        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: run { buffer.close(); fail("SCREENSHOT_FAILED", "Could not wrap buffer") }
        val bitmap = hw.copy(Bitmap.Config.ARGB_8888, false); buffer.close()
        val w = maxWidth.coerceIn(100, 2000)
        val scaled = if (bitmap.width > w) Bitmap.createScaledBitmap(bitmap, w, bitmap.height * w / bitmap.width, true) else bitmap
        val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)
        return JSONObject()
            .put("screenshot", "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            .put("width", scaled.width).put("height", scaled.height)
            .put("scale", scaled.width.toDouble() / bitmap.width)
    }

    private fun describe(code: Int) = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate limited; wait a moment"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure window; this app blocks capture"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
        else -> "unknown"
    }
}
