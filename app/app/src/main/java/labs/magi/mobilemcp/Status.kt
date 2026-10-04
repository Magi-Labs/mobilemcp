package labs.magi.mobilemcp

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** Process-wide status and a small log ring shown by MainActivity. */
object Status {
    @Volatile var state: String = "Service off"
        private set
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun set(s: String) { state = s; log(s) }
    fun log(line: String) {
        synchronized(lines) { lines.addLast("${fmt.format(Date())} $line"); while (lines.size > 80) lines.removeFirst() }
        main.post { listeners.forEach { it() } }
    }
    fun lines(): List<String> = synchronized(lines) { lines.toList() }
    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
}
