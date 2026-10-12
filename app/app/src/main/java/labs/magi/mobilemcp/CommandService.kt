package labs.magi.mobilemcp

import android.content.Context
import android.os.Binder
import android.os.Process
import org.json.JSONObject

/** Runs only in Shizuku's shell/root user-service process; never in the app sandbox. */
class CommandService(context: Context) : ICommandService.Stub() {
    private val owner = context.applicationInfo.uid
    private val runner = CommandRunner()
    @Synchronized override fun execute(argv: Array<String>, timeoutMs: Int): String {
        check(Binder.getCallingUid() == owner) { "Unauthorized caller" }
        val result = runner.run(argv, timeoutMs)
        return JSONObject().put("uid", Process.myUid()).put("exitCode", result.exitCode ?: JSONObject.NULL)
            .put("timedOut", result.timedOut).put("outcome", if (result.timedOut) "unknown" else "finished")
            .put("output", result.output).put("outputLimitBytes", 65536).put("mayBeTruncated", result.truncated).toString()
    }
    override fun destroy() { runner.stop(); System.exit(0) }
}
