package labs.magi.mobilemcp

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Outbound WebSocket to the hub. Requests `{id, action, params}` are executed one at a time on a worker
 * thread (one screen, one finger) and answered with `{id, result|error}`. Reconnects with backoff while enabled.
 */
class HubConnection(private val ctx: Context, private val dispatcher: Dispatcher) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mobilemcp-actions") }
    private val main = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { open() }

    @Volatile private var ws: WebSocket? = null
    @Volatile var enabled = false; private set
    @Volatile var connected = false; private set
    private var attempt = 0

    @Volatile private var currentUrl: String? = null

    /** Idempotent: an established connection to the same URL is kept; a changed URL or a dead socket reconnects. */
    fun connect() {
        val url = normalize(Prefs.hubUrl(ctx))
        if (enabled && connected && url == currentUrl) { Status.log("Already connected"); return }
        enabled = true; attempt = 0; main.removeCallbacks(reconnectRunnable); open()
    }

    fun disconnect() {
        enabled = false; main.removeCallbacks(reconnectRunnable)
        ws?.close(1000, "disconnect"); ws = null; connected = false
        Status.set("Disconnected")
    }

    private fun open() {
        if (!enabled) return
        val url = normalize(Prefs.hubUrl(ctx)) ?: run { Status.set("Set a hub URL first"); enabled = false; return }
        ws?.cancel(); currentUrl = url
        Status.set("Connecting to $url")
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    /** Accepts http(s)/ws(s) origins with or without the /device path, as livemcp's popup does. */
    private fun normalize(raw: String): String? {
        var u = raw.trim(); if (u.isEmpty()) return null
        u = u.replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://").replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
        if (!u.startsWith("ws://") && !u.startsWith("wss://")) u = "ws://$u"
        val schemeEnd = u.indexOf("://") + 3
        val pathStart = u.indexOf('/', schemeEnd)
        if (pathStart < 0) return "$u/device"
        if (u.substring(pathStart) == "/") return "${u.substring(0, pathStart)}/device"
        return u
    }

    private fun scheduleReconnect(reason: String) {
        connected = false
        if (!enabled) return
        val delay = min(30_000L, 1000L * (1 shl min(attempt, 5))); attempt++
        Status.set("$reason; retrying in ${delay / 1000}s")
        main.removeCallbacks(reconnectRunnable); main.postDelayed(reconnectRunnable, delay)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = JSONObject()
                .put("type", "hello")
                .put("deviceId", Prefs.deviceId(ctx))
                .put("name", Prefs.displayName(ctx))
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            Prefs.token(ctx).takeIf { it.isNotEmpty() }?.let { hello.put("token", it) }
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try { JSONObject(text) } catch (_: Exception) { return }
            when (msg.optString("type")) {
                "hello_ack" -> { attempt = 0; connected = true; Status.set("Connected as ${Prefs.displayName(ctx)}"); return }
                "pong" -> return
            }
            val id = msg.optString("id", ""); val action = msg.optString("action", "")
            if (id.isEmpty() || action.isEmpty()) return
            val params = msg.optJSONObject("params") ?: JSONObject()
            executor.execute {
                val started = System.currentTimeMillis()
                val response = JSONObject().put("id", id)
                try {
                    response.put("result", dispatcher.handle(action, params))
                } catch (e: Throwable) {
                    response.put("error", e.message ?: e.toString())
                }
                Status.log("$action ${System.currentTimeMillis() - started}ms${if (response.has("error")) " ✗ " + response.optString("error").take(80) else ""}")
                webSocket.send(response.toString())
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (ws !== webSocket) return
            // 1008 = the hub rejected our hello (bad token); retrying cannot fix that.
            if (code == 1008) { enabled = false; connected = false; Status.set("Rejected by hub: $reason. Check the device token, then Connect again."); return }
            scheduleReconnect("Closed ($code ${reason.ifEmpty { "no reason" }})")
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (ws === webSocket) scheduleReconnect("Connection failed: ${t.message ?: t.javaClass.simpleName}") }
    }
}
