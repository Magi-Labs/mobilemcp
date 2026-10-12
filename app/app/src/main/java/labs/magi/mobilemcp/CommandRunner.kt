package labs.magi.mobilemcp

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** No Android dependency: used unchanged in the privileged service and JVM verification. */
class CommandRunner {
    data class Result(val exitCode: Int?, val timedOut: Boolean, val output: String, val truncated: Boolean)
    @Volatile private var active: Process? = null
    fun stop() { active?.destroyForcibly() }
    @Synchronized fun run(argv: Array<String>, timeoutMs: Int): Result {
        require(argv.isNotEmpty() && argv.size <= 64 && argv.all { it.length <= 4096 && !it.contains('\u0000') })
        require(timeoutMs in 1000..30000)
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        active = process; process.outputStream.close()
        val reader = Executors.newSingleThreadExecutor { r -> Thread(r, "command-output").apply { isDaemon = true } }
        val kept = java.io.ByteArrayOutputStream()
        var truncated = false
        val output = reader.submit {
            val buffer = ByteArray(4096)
            process.inputStream.use { stream ->
                while (true) {
                    val n = stream.read(buffer); if (n < 0) break
                    synchronized(kept) {
                        val count = minOf(n, 65536 - kept.size())
                        if (count > 0) kept.write(buffer, 0, count)
                        if (count < n) truncated = true
                    }
                }
            }
        }
        try {
            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            try { output.get(1000, TimeUnit.MILLISECONDS) } catch (_: Exception) { synchronized(kept) { truncated = true } }
            return synchronized(kept) { Result(if (finished) process.exitValue() else null, !finished, kept.toString("UTF-8"), truncated) }
        } finally {
            process.destroy(); try { process.inputStream.close() } catch (_: Exception) {}
            active = null; reader.shutdownNow()
        }
    }
}
