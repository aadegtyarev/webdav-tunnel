package ru.adsrv.webdavtunnel

/**
 * Thread-safe ring buffer for the in-app browser debug console: console.* messages,
 * page/HTTP errors, network requests and Clean-mode fetch events. Written from WebView
 * callbacks (some off the UI thread); read by the console view.
 *
 * Capped by total size ([maxBytes], configurable). [clear] is called when the console is
 * closed so the buffer doesn't linger in memory.
 */
object DebugLog {
    @Volatile var maxBytes = 1_000_000   // ~1 MB default; set from the buffer-size setting

    private val lines = ArrayDeque<String>()
    private var bytes = 0

    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        bytes += line.length + 1
        while (bytes > maxBytes && lines.size > 1) bytes -= lines.removeFirst().length + 1
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() { lines.clear(); bytes = 0 }
}
