package labs.magi.mobilemcp

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object PrivilegedCommands {
    @Volatile private var service: ICommandService? = null
    private fun args(ctx: Context) = Shizuku.UserServiceArgs(ComponentName(ctx, CommandService::class.java)).daemon(false).processNameSuffix("commands").version(BuildConfig.VERSION_CODE)
    private var connection: ServiceConnection? = null
    fun status(ctx: Context): JSONObject {
        val running = Shizuku.pingBinder()
        val granted = running && try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }
        return JSONObject().put("enabled", Prefs.commandsAllowed(ctx)).put("shizukuRunning", running).put("shizukuGranted", granted)
    }
    fun disconnect(ctx: Context) {
        service = null
        connection?.let { try { Shizuku.unbindUserService(args(ctx), it, true) } catch (_: Exception) {} }
        connection = null
    }
    fun run(ctx: Context, params: JSONObject): JSONObject {
        if (!Prefs.commandsAllowed(ctx)) fail("COMMAND_PERMISSION_REQUIRED", "Enable privileged commands in the phone app.")
        val state = status(ctx)
        if (!state.getBoolean("shizukuRunning")) fail("SHIZUKU_UNAVAILABLE", "Start Shizuku on the phone.")
        if (!state.getBoolean("shizukuGranted")) fail("SHIZUKU_PERMISSION_REQUIRED", "Grant Shizuku access in the phone app.")
        val list = params.optJSONArray("argv") ?: fail("BAD_ARGS", "argv is required")
        val argv = Array(list.length()) { list.getString(it) }
        if (argv.isEmpty() || argv.size > 64 || argv.any { it.length > 4096 || it.contains('\u0000') }) fail("BAD_ARGS", "Invalid command arguments")
        val timeout = params.optInt("timeout", 10000).coerceIn(1000,30000)
        if (service == null) {
            val ready = CountDownLatch(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (Prefs.commandsAllowed(ctx)) service = ICommandService.Stub.asInterface(binder)
                    else try { Shizuku.unbindUserService(args(ctx), this, true) } catch (_: Exception) {}
                    ready.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) { service = null }
            }
            connection = conn
            Handler(Looper.getMainLooper()).post { try { Shizuku.bindUserService(args(ctx), conn) } catch (_: Exception) { ready.countDown() } }
            if (!ready.await(5, TimeUnit.SECONDS) || service == null) fail("SHIZUKU_UNAVAILABLE", "Command service did not connect; no command executed.")
        }
        if (!Prefs.commandsAllowed(ctx)) fail("COMMAND_PERMISSION_REQUIRED", "Command access was revoked.")
        return try { JSONObject(service!!.execute(argv, timeout)) } catch (_: Exception) { fail("COMMAND_OUTCOME_UNKNOWN", "Command service disconnected; do not replay mutations automatically.") }
    }
}
