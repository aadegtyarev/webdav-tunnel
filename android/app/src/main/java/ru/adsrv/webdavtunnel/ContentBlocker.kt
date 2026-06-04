package ru.adsrv.webdavtunnel

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

/**
 * Request-level content blocker for the WebView. Runs on a background thread from
 * shouldInterceptRequest. Image/JS blocking is handled cheaper via WebSettings;
 * this handles ads/trackers, fonts, media, third-party frames and speculative loads.
 */
object ContentBlocker {

    /** Toggle snapshot taken per page load (read from [Settings]). */
    data class Opts(
        val adblock: Boolean,
        val fonts: Boolean,
        val media: Boolean,
        val frames: Boolean,
        val speculative: Boolean,
    )

    private val fontExts = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val mediaExts = setOf("mp4", "webm", "m3u8", "mp3", "ogg", "oga", "m4a", "aac", "mov", "avi", "mkv", "flv")

    @Volatile private var adHosts: Set<String> = emptySet()

    /** Cached domain file fetched through the tunnel (preferred over the bundled asset). */
    fun cacheFile(ctx: Context): File = File(ctx.filesDir, "hosts.txt")

    /** Number of domains currently loaded. */
    fun count(): Int = adHosts.size

    /** Loads the blocklist once (downloaded cache if present, else bundled asset). */
    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (adHosts.isNotEmpty()) return
        reload(ctx)
    }

    /** Force a re-read, e.g. after the blocklist was updated from the network. */
    @Synchronized
    fun reload(ctx: Context) {
        val cache = cacheFile(ctx)
        val set = if (cache.exists() && cache.length() > 0)
            parse(cache.bufferedReader())
        else
            try { parse(ctx.assets.open("hosts.txt").bufferedReader()) } catch (_: Exception) { emptySet() }
        adHosts = set
    }

    private fun parse(reader: BufferedReader): Set<String> {
        val set = HashSet<String>(65536)
        reader.useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                // Accept "0.0.0.0 domain", "127.0.0.1 domain" or a bare domain.
                val parts = line.split(Regex("\\s+"))
                val domain = when {
                    parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> parts[1]
                    parts.size == 1 -> parts[0]
                    else -> continue
                }.lowercase(Locale.ROOT)
                if (domain.isNotEmpty() && domain != "localhost") set.add(domain)
            }
        }
        return set
    }

    private fun isAd(host: String): Boolean {
        if (adHosts.isEmpty()) return false
        var h = host.lowercase(Locale.ROOT)
        // Match the host and every parent domain (sub.ads.example.com → ads.example.com → ...).
        while (true) {
            if (adHosts.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return adHosts.contains(h)
        }
    }

    /** Crude registrable-domain compare (last two labels). Good enough for 1st/3rd-party. */
    private fun sameSite(a: String, b: String): Boolean {
        fun reg(host: String): String {
            val p = host.lowercase(Locale.ROOT).split('.')
            return if (p.size >= 2) p[p.size - 2] + "." + p[p.size - 1] else host
        }
        return reg(a) == reg(b)
    }

    private fun ext(path: String): String {
        val q = path.substringBefore('?').substringBefore('#')
        val dot = q.lastIndexOf('.')
        return if (dot in 0 until q.length - 1) q.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    private val blocked: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Returns a blocking response, or null to let the request through. */
    fun intercept(req: WebResourceRequest, topHost: String?, o: Opts): WebResourceResponse? {
        val url = req.url ?: return null
        val host = url.host ?: return null
        val headers = req.requestHeaders ?: emptyMap()
        val dest = headers["Sec-Fetch-Dest"]?.lowercase(Locale.ROOT) ?: ""
        val e = ext(url.path ?: "")

        if (o.adblock && isAd(host)) return blocked

        if (o.speculative) {
            val purpose = (headers["Sec-Purpose"] ?: headers["Purpose"] ?: headers["X-moz"] ?: "").lowercase(Locale.ROOT)
            if (purpose.contains("prefetch") || purpose.contains("prerender")) return blocked
        }
        if (o.fonts && (dest == "font" || e in fontExts)) return blocked
        if (o.media && (dest == "audio" || dest == "video" || dest == "track" || e in mediaExts)) return blocked
        if (o.frames && (dest == "iframe" || dest == "frame")) {
            if (topHost == null || !sameSite(host, topHost)) return blocked
        }
        return null
    }
}
