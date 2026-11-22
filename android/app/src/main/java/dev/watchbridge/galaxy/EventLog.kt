package dev.watchbridge.galaxy

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 화면에 보여 줄 짧은 동작 기록. 메시지 내용은 넣지 않는다(어깨너머로 보일 수 있으므로).
 */
object EventLog {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(message: String) {
        Log.d("WatchBridge", message)
        synchronized(this) {
            lines.addLast("${format.format(Date())}  $message")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        main.post { listeners.forEach { it() } }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}
