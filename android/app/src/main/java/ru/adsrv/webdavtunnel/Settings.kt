package ru.adsrv.webdavtunnel

import android.content.Context
import android.content.SharedPreferences

/**
 * Central place for persisted settings, split by how often the user touches them.
 *
 *  - [tune]    — transport tuning + timeouts (level 3, on the connection screen,
 *                requires reconnect). Defaults = the fast throttling profile.
 *  - [browser] — content toggles (levels 1 & 2, live in the browser).
 */
object Settings {

    fun tune(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun browser(ctx: Context): SharedPreferences = ctx.getSharedPreferences("browser", Context.MODE_PRIVATE)

    // ── level 3: transport tuning (defaults mirror the fast profile) ───────────────
    object Tune {
        // gentle profile — backed off slightly from the aggressive one to ease Yandex 429s
        const val CHUNK = "chunk";        const val DEF_CHUNK = 131071
        const val COALESCE = "coalesce";  const val DEF_COALESCE = 20      // ms (was 10): fewer, fuller writes
        const val POLL_MIN = "poll_min";  const val DEF_POLL_MIN = 300     // ms (was 200)
        const val POLL_MAX = "poll_max";  const val DEF_POLL_MAX = 1500    // ms (was 800): poll idle less often
        const val PUTS = "puts";          const val DEF_PUTS = 6           // was 8
        const val READ_MIN = "read_min";  const val DEF_READ_MIN = 2       // was 3
        const val READ_MAX = "read_max";  const val DEF_READ_MAX = 6       // was 8
        const val DIAL = "dial_sec";      const val DEF_DIAL = 15          // s
        const val IDLE = "idle_sec";      const val DEF_IDLE = 90          // s
        const val WATCHDOG = "watchdog";  const val DEF_WATCHDOG = 120     // s, page-load watchdog (UI); high so JS-heavy SPAs (GitHub) survive the multi-second/chunk tunnel RTT
    }

    fun tuneInt(ctx: Context, key: String, def: Int): Int = tune(ctx).getInt(key, def)

    fun resetTune(ctx: Context) {
        tune(ctx).edit()
            .putInt(Tune.CHUNK, Tune.DEF_CHUNK)
            .putInt(Tune.COALESCE, Tune.DEF_COALESCE)
            .putInt(Tune.POLL_MIN, Tune.DEF_POLL_MIN)
            .putInt(Tune.POLL_MAX, Tune.DEF_POLL_MAX)
            .putInt(Tune.PUTS, Tune.DEF_PUTS)
            .putInt(Tune.READ_MIN, Tune.DEF_READ_MIN)
            .putInt(Tune.READ_MAX, Tune.DEF_READ_MAX)
            .putInt(Tune.DIAL, Tune.DEF_DIAL)
            .putInt(Tune.IDLE, Tune.DEF_IDLE)
            .putInt(Tune.WATCHDOG, Tune.DEF_WATCHDOG)
            .apply()
    }

    // ── levels 1 & 2: browser content toggles ──────────────────────────────────────
    object B {
        // level 1 (quick drawer) — the toggles flipped most while surfing
        const val IMAGES = "images";   const val DEF_IMAGES = true    // on: CSS icons (e.g. DDG search button) need images
        const val LAZY_IMG = "lazy_img"; const val DEF_LAZY_IMG = true // Clean: load images on scroll vs all at once
        const val JS = "js";           const val DEF_JS = true        // on: full browsing by default
        const val TEXTONLY = "textonly"; const val DEF_TEXTONLY = false // "Clean" reformat mode
        const val ZOOM = "zoom";         const val DEF_ZOOM = 100        // text size %, applied via WebView textZoom
        const val FRAMES = "frames";   const val DEF_FRAMES = false   // block 3rd-party iframes/widgets
        // level 2 (content settings panel — set-and-forget)
        const val ADBLOCK = "adblock"; const val DEF_ADBLOCK = true   // moved out of the quick drawer
        const val FONTS = "fonts";     const val DEF_FONTS = false    // block web fonts
        const val MEDIA = "media";     const val DEF_MEDIA = true     // block media by default: no video/audio preload/autoplay over the tunnel
        const val SPECULATIVE = "spec"; const val DEF_SPECULATIVE = true // block prefetch/prerender by default: don't waste tunnel round-trips on guesses
        const val WEBRTC = "webrtc";   const val DEF_WEBRTC = true    // block WebRTC by default (IP-leak guard for the tunnel)
        // startup behaviour
        const val HOME_URL = "home_url"
        const val DEF_HOME_URL = ""          // empty = built-in instant start page (search box)
        const val SEARCH_URL = "search_url"  // %s is replaced with the query
        const val DEF_SEARCH_URL = "https://html.duckduckgo.com/html/?q=%s"
        const val RESTORE_SESSION = "restore_session"; const val DEF_RESTORE_SESSION = false
        const val CONSOLE_ON = "console_on"; const val DEF_CONSOLE_ON = false // in-app debug console (off by default)
        const val FREEZE = "freeze"; const val DEF_FREEZE = false           // pause JS timers + block new WS/SSE
        const val CONSOLE_MB = "console_mb"; const val DEF_CONSOLE_MB = 1     // debug-buffer size, MB (1..10)
        // downloadable blocklist (fetched through the tunnel, cached in filesDir)
        const val BLOCKLIST_URL = "blocklist_url"
        const val DEF_BLOCKLIST_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        const val BLOCKLIST_UPDATED = "blocklist_updated" // epoch millis, 0 = never
        const val BLOCKLIST_COUNT = "blocklist_count"      // domains loaded
    }

    fun blocklistUrl(ctx: Context): String =
        browser(ctx).getString(B.BLOCKLIST_URL, B.DEF_BLOCKLIST_URL) ?: B.DEF_BLOCKLIST_URL

    /** Home page URL; empty string means "show the built-in start page (search box)". */
    fun homeUrl(ctx: Context): String {
        val v = browser(ctx).getString(B.HOME_URL, B.DEF_HOME_URL) ?: B.DEF_HOME_URL
        // a DuckDuckGo home loads slowly over the tunnel — migrate it to the instant start page
        if (v == "https://duckduckgo.com/html/" || v == "https://html.duckduckgo.com/html/") {
            setHomeUrl(ctx, ""); return ""
        }
        return v
    }

    fun setHomeUrl(ctx: Context, url: String) =
        browser(ctx).edit().putString(B.HOME_URL, url.trim()).apply()

    /** Search URL template ("%s" = query). */
    fun searchUrl(ctx: Context): String =
        browser(ctx).getString(B.SEARCH_URL, B.DEF_SEARCH_URL)?.ifBlank { B.DEF_SEARCH_URL } ?: B.DEF_SEARCH_URL

    fun setSearchUrl(ctx: Context, u: String) =
        browser(ctx).edit().putString(B.SEARCH_URL, u.trim().ifBlank { B.DEF_SEARCH_URL }).apply()

    fun bGet(ctx: Context, key: String, def: Boolean): Boolean = browser(ctx).getBoolean(key, def)
    fun bSet(ctx: Context, key: String, v: Boolean) = browser(ctx).edit().putBoolean(key, v).apply()

    /** Text size as a WebView textZoom percentage (clamped 60–220). */
    fun zoom(ctx: Context): Int = browser(ctx).getInt(B.ZOOM, B.DEF_ZOOM).coerceIn(60, 220)
    fun setZoom(ctx: Context, z: Int) = browser(ctx).edit().putInt(B.ZOOM, z.coerceIn(60, 220)).apply()

    /** Debug-console buffer size in MB (1..10). */
    fun consoleMb(ctx: Context): Int = browser(ctx).getInt(B.CONSOLE_MB, B.DEF_CONSOLE_MB).coerceIn(1, 10)
    fun setConsoleMb(ctx: Context, m: Int) = browser(ctx).edit().putInt(B.CONSOLE_MB, m.coerceIn(1, 10)).apply()

    // ── bookmarks (stored in browser prefs as "title\turl" lines) ──────────────────
    private const val BOOKMARKS = "bookmarks"
    data class Bookmark(val title: String, val url: String)

    fun bookmarks(ctx: Context): List<Bookmark> {
        val raw = browser(ctx).getString(BOOKMARKS, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i < 0) null else Bookmark(line.substring(0, i), line.substring(i + 1))
        }
    }

    private fun saveBookmarks(ctx: Context, list: List<Bookmark>) {
        val raw = list.joinToString("\n") {
            it.title.replace('\n', ' ').replace('\t', ' ') + "\t" + it.url
        }
        browser(ctx).edit().putString(BOOKMARKS, raw).apply()
    }

    /** Add (or move-to-top) a bookmark, de-duplicated by URL. */
    fun addBookmark(ctx: Context, title: String, url: String) {
        if (url.isBlank()) return
        val list = bookmarks(ctx).filterNot { it.url == url }.toMutableList()
        list.add(0, Bookmark(title.ifBlank { url }, url))
        saveBookmarks(ctx, list)
    }

    fun removeBookmark(ctx: Context, url: String) {
        saveBookmarks(ctx, bookmarks(ctx).filterNot { it.url == url })
    }
}
