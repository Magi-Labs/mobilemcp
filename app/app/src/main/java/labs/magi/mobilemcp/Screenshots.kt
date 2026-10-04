package labs.magi.mobilemcp

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import android.view.Display
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Scaled JPEG screenshots through the accessibility screenshot API (no MediaProjection prompt). */
class Screenshots(private val svc: MobileAccessibilityService) {
    fun capture(maxWidth: Int = 800, quality: Int = 70, marks: List<Pair<Int, Rect>> = emptyList()): JSONObject {
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
        var scaled = if (bitmap.width > w) Bitmap.createScaledBitmap(bitmap, w, bitmap.height * w / bitmap.width, true) else bitmap
        if (marks.isNotEmpty()) scaled = drawMarks(scaled, marks, scaled.width.toFloat() / bitmap.width)
        val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)
        return JSONObject()
            .put("screenshot", "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            .put("width", scaled.width).put("height", scaled.height)
            .put("scale", scaled.width.toDouble() / bitmap.width)
    }

    /** Set-of-Mark overlay: outline each interactive node and label it with its @ref so a vision model can name targets. */
    private fun drawMarks(src: Bitmap, marks: List<Pair<Int, Rect>>, scale: Float): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true); val c = Canvas(bmp)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(220, 255, 64, 64) }
        val fill = Paint().apply { style = Paint.Style.FILL; color = Color.argb(230, 255, 64, 64) }
        val textPaint = Paint().apply { color = Color.WHITE; textSize = (bmp.width / 34f).coerceAtLeast(11f); isAntiAlias = true; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        for ((ref, r) in marks) {
            val l = r.left * scale; val t = r.top * scale; val rr = r.right * scale; val b = r.bottom * scale
            c.drawRect(l, t, rr, b, box)
            val label = "@$ref"; val tw = textPaint.measureText(label); val th = textPaint.textSize
            val lx = l.coerceAtLeast(0f); val ly = (t - th - 2).coerceAtLeast(0f)
            c.drawRoundRect(RectF(lx, ly, lx + tw + 6, ly + th + 2), 3f, 3f, fill)
            c.drawText(label, lx + 3, ly + th - 2, textPaint)
        }
        return bmp
    }

    private fun describe(code: Int) = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate limited; wait a moment"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure window; this app blocks capture"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
        else -> "unknown"
    }
}
