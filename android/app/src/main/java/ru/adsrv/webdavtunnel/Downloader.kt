package ru.adsrv.webdavtunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Network that always goes through the local SOCKS5 tunnel — used for file
 * downloads and for refreshing the ad blocklist. Keeping these off the system
 * DownloadManager is deliberate: DownloadManager would bypass the tunnel (direct
 * exit + real-IP leak). The page's cookies and User-Agent are forwarded so
 * authenticated downloads work.
 *
 * Note: OkHttp resolves the target hostname locally before dialing it through the
 * SOCKS proxy, so a download host triggers one local DNS lookup (the bytes still
 * travel inside the tunnel). The page itself was already resolved proxy-side.
 */
object Downloader {

    private const val CH_ID = "downloads"

    private fun parseHostPort(listen: String): Pair<String, Int> {
        val i = listen.lastIndexOf(':')
        if (i < 0) return "127.0.0.1" to 1080
        val host = listen.substring(0, i).ifBlank { "127.0.0.1" }
        val port = listen.substring(i + 1).toIntOrNull() ?: 1080
        return host to port
    }

    fun client(listen: String): OkHttpClient {
        val (h, p) = parseHostPort(listen)
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(h, p)))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Fetches a page's raw HTML through the tunnel (one request, no rendering) — used
     * by text/reader mode. Forwards the page cookies and User-Agent so logged-in pages
     * resolve correctly. Runs on a background thread (caller's responsibility).
     */
    fun fetchHtml(url: String, listen: String, cookie: String?, userAgent: String?): Pair<Int, String> {
        val rb = Request.Builder().url(url)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .header("User-Agent", userAgent?.takeIf { it.isNotBlank() }
                ?: "Mozilla/5.0 (Android) webdav-tunnel-browser")
        if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
        client(listen).newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.code to resp.body!!.string()
        }
    }

    /**
     * Refreshes the ad blocklist from [url] through the tunnel, atomically replacing
     * the cached file. Returns the number of domains, or throws on failure. Runs on
     * a background thread (caller's responsibility).
     */
    fun fetchBlocklist(ctx: Context, url: String, listen: String): Int {
        val req = Request.Builder().url(url)
            .header("User-Agent", "webdav-tunnel-browser")
            .build()
        client(listen).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val tmp = File(ctx.filesDir, "hosts.txt.tmp")
            resp.body!!.byteStream().use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            val dst = ContentBlocker.cacheFile(ctx)
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true); tmp.delete()
            }
        }
        ContentBlocker.reload(ctx)
        val count = ContentBlocker.count()
        Settings.browser(ctx).edit()
            .putInt(Settings.B.BLOCKLIST_COUNT, count)
            .apply()
        return count
    }

    /**
     * Downloads a file through the tunnel and saves it to the public Downloads
     * folder (MediaStore on Q+, app-specific dir below). Progress and result are
     * reported via a notification and the optional [status] callback (no popups).
     */
    fun download(
        ctx: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mime: String?,
        listen: String,
        status: ((String) -> Unit)?,
    ) {
        val appCtx = ctx.applicationContext
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        val nm = appCtx.getSystemService(NotificationManager::class.java)
        ensureChannel(nm)
        val notifId = (System.identityHashCode(url) and 0xFFFFFF) or 0x1000
        status?.invoke("↓ $name")
        notify(appCtx, nm, notifId, name, "Downloading…", true)

        Thread({
            try {
                val cookie = CookieManager.getInstance().getCookie(url)
                val rb = Request.Builder().url(url)
                if (!userAgent.isNullOrBlank()) rb.header("User-Agent", userAgent)
                if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                client(listen).newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body!!
                    saveToDownloads(appCtx, name, mime, body.byteStream())
                }
                notify(appCtx, nm, notifId, name, "Saved to Downloads", false)
                status?.invoke("✓ $name")
            } catch (e: Exception) {
                notify(appCtx, nm, notifId, name, "Failed: ${e.message}", false)
                status?.invoke("✗ $name: ${e.message}")
            }
        }, "download").start()
    }

    /** Saves a bitmap as a PNG into the public Downloads folder. */
    fun savePng(ctx: Context, name: String, bitmap: android.graphics.Bitmap) {
        val bytes = java.io.ByteArrayOutputStream().use { bos ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        }
        saveToDownloads(ctx.applicationContext, name, "image/png", java.io.ByteArrayInputStream(bytes))
    }

    private fun saveToDownloads(ctx: Context, name: String, mime: String?, input: java.io.InputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("cannot create Downloads entry")
            resolver.openOutputStream(uri).use { out -> input.copyTo(out!!, 64 * 1024) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IllegalStateException("no external storage")
            File(dir, name).outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notify(ctx: Context, nm: NotificationManager, id: Int, title: String, text: String, ongoing: Boolean) {
        val n = NotificationCompat.Builder(ctx, CH_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(ongoing)
            .apply { if (ongoing) setProgress(0, 0, true) }
            .build()
        nm.notify(id, n)
    }
}
