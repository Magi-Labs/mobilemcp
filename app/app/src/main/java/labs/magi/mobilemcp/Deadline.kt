package labs.magi.mobilemcp

import org.json.JSONObject

/** The hub stamps every request with `__deadline` (epoch ms). Waits and polls stop there instead of running on. */
class Deadline(val at: Long) {
    val remaining: Long get() = at - System.currentTimeMillis()
    fun expired() = remaining <= 0

    companion object {
        fun from(params: JSONObject, fallbackMs: Long = 20_000): Deadline =
            Deadline(if (params.has("__deadline")) params.optLong("__deadline") else System.currentTimeMillis() + fallbackMs)
    }
}
