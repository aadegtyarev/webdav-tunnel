package ru.adsrv.webdavtunnel

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Persistent on-disk cache for images, served from [BrowserActivity.shouldInterceptRequest].
 *
 * Why this exists: images are the heaviest thing to pull over the WebDAV tunnel (~1.5s/request),
 * and WebView's built-in HTTP cache is small, non-configurable, and refuses to store entries larger
 * than ~1/8 of its size — so big photos re-download on every back/forward. Here we intercept image
 * requests, fetch the bytes once through the tunnel ourselves, and keep them in our own LRU. A cached
 * image then loads with zero tunnel round-trips and survives back/forward and app restarts, regardless
 * of the origin's `Cache-Control`.
 *
 * Eviction is size-bound LRU: oldest-touched files are dropped once the directory exceeds [maxBytes].
 */
object ImageCache {

    private const val DIR = "imgcache"
    private val maxBytes = 100L * 1024 * 1024            // ~100 MB on disk
    private const val MAX_ENTRY = 16L * 1024 * 1024      // skip absurd single images (keeps one entry from dominating)

    private val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "ico", "svg", "avif", "apng", "jfif")

    /** A request we should serve from / store into the image cache. */
    fun handles(req: WebResourceRequest): Boolean {
        if (!"GET".equals(req.method, ignoreCase = true)) return false
        val url = req.url ?: return false
        val scheme = url.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        if (req.requestHeaders?.keys?.any { it.equals("Range", ignoreCase = true) } == true) return false
        val dest = req.requestHeaders?.get("Sec-Fetch-Dest")?.lowercase(Locale.ROOT)
        if (dest == "image") return true
        // some engines omit Sec-Fetch-Dest — fall back to the path extension
        val path = url.path?.substringBefore('?')?.substringBefore('#').orEmpty()
        val dot = path.lastIndexOf('.')
        val ext = if (dot in 0 until path.length - 1) path.substring(dot + 1).lowercase(Locale.ROOT) else ""
        return ext in imageExts
    }

    private fun dir(ctx: Context): File = File(ctx.cacheDir, DIR).apply { if (!exists()) mkdirs() }

    private fun keyFor(url: String): String {
        val md = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns a response for [req]: served from disk on a hit, otherwise fetched through the tunnel,
     * stored, and returned. Runs on the WebView's IO thread (shouldInterceptRequest is already off the
     * UI thread). Returns null on any failure so the WebView falls back to loading the image itself.
     */
    fun get(ctx: Context, req: WebResourceRequest, listen: String, userAgent: String?): WebResourceResponse? {
        val url = req.url?.toString() ?: return null
        val f = File(dir(ctx), keyFor(url))
        if (f.exists() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis())   // touch for LRU
            val (mime, enc) = readMeta(f)
            return WebResourceResponse(mime, enc, ByteArrayInputStream(f.readBytes()))
        }
        return fetchAndStore(ctx, url, f, listen, userAgent)
    }

    private fun fetchAndStore(ctx: Context, url: String, f: File, listen: String, userAgent: String?): WebResourceResponse? {
        return try {
            val cookie = CookieManager.getInstance().getCookie(url)
            val rb = Request.Builder().url(url)
                .header("Accept", "image/avif,image/webp,image/*,*/*")
                .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: "webdav-tunnel-browser")
            if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
            Downloader.client(listen).newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val bytes = body.bytes()
                val ct = resp.header("Content-Type") ?: "image/*"
                val mime = ct.substringBefore(';').trim().ifBlank { "image/*" }
                if (!mime.startsWith("image/")) return null         // not actually an image (error page etc.)
                if (bytes.size <= MAX_ENTRY) {
                    runCatching {
                        File(f.parentFile, f.name + ".tmp").let { tmp ->
                            tmp.writeBytes(bytes); writeMeta(tmp, mime)
                            tmp.renameTo(f) || run { tmp.copyTo(f, overwrite = true); tmp.delete() }
                        }
                    }
                    trim(ctx)
                }
                WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
            }
        } catch (_: Exception) {
            null   // let WebView load it the normal way
        }
    }

    // Mime type is stored in a tiny sidecar so a hit can rebuild the response header.
    private fun metaFile(f: File) = File(f.parentFile, f.name + ".m")
    private fun writeMeta(tmp: File, mime: String) =
        runCatching { File(tmp.parentFile, tmp.name.removeSuffix(".tmp") + ".m").writeText(mime) }
    private fun readMeta(f: File): Pair<String, String?> {
        val mime = runCatching { metaFile(f).readText().trim() }.getOrNull()?.ifBlank { null } ?: "image/*"
        return mime to null
    }

    /** Drop oldest-touched entries until the directory is back under [maxBytes]. */
    @Synchronized
    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles()?.filter { it.isFile && !it.name.endsWith(".m") && !it.name.endsWith(".tmp") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete(); metaFile(f).delete()
        }
    }

    /** Wipe the whole image cache (wired to the existing "Clear cache" action). */
    fun clear(ctx: Context) {
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
    }
}
