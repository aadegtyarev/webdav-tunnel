package ru.adsrv.webdavtunnel

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import ru.adsrv.webdavtunnel.databinding.ActivityBrowserBinding
import java.util.concurrent.Executor

/**
 * A WebView-based browser pinned to the local SOCKS5 tunnel. All traffic (and DNS,
 * since SOCKS5 resolves proxy-side) goes through 127.0.0.1:<port>. Content toggles
 * live on a slide-down panel; errors/downloads show on an unobtrusive status line.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var b: ActivityBrowserBinding
    private lateinit var web: WebView
    private var listen = "127.0.0.1:1080"

    @Volatile private var topHost: String? = null
    @Volatile private var opts = ContentBlocker.Opts(true, false, false, false, false)

    private var pageUrl: String? = null        // logical current URL (both modes)
    private var loadingTextDoc = false         // next onPageStarted is our loadDataWithBaseURL
    private var fallbackUrl: String? = null     // page being loaded with JS to text-ify on finish
    private var fallbackImages = true           // images preference for the fallback DOM pass
    private var fallbackLazy = true             // lazy-images preference for the fallback DOM pass
    @Volatile private var consoleEnabled = false // capture debug logs only when the console is on
    private var consoleFilter = "all"            // all | net | log
    private var frozen = false                   // pause JS timers + block new WS/SSE

    private val ui = Handler(Looper.getMainLooper())
    private var lastErrLine = ""
    private val watchdog = Runnable {
        if (web.progress < 100) {
            web.stopLoading()
            val s = Settings.tuneInt(this, Settings.Tune.WATCHDOG, Settings.Tune.DEF_WATCHDOG)
            setStatusTransient("⏱ stopped after ${s}s — page too slow (try Text only / check tunnel)", 8000)
        }
    }

    private fun armWatchdog() {
        ui.removeCallbacks(watchdog)
        val s = Settings.tuneInt(this, Settings.Tune.WATCHDOG, Settings.Tune.DEF_WATCHDOG)
        if (s > 0) ui.postDelayed(watchdog, s * 1000L)
    }
    private val logPoller = object : Runnable {
        override fun run() {
            pollTunnelErrors()
            ui.postDelayed(this, 3000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        web = b.web
        WebView.setWebContentsDebuggingEnabled(true)   // enables chrome://inspect over USB

        listen = Settings.tune(this).getString("listen", "127.0.0.1:1080") ?: "127.0.0.1:1080"
        ContentBlocker.ensureLoaded(this)

        setupCookies()
        setupWebView()
        bindPanel()

        applyWebSettings(reload = false)
        applyProxyThen { openStartupPage() }
    }

    /** First page: restored session (if the toggle is on) else the home URL; empty home = blank tab. */
    private fun openStartupPage() {
        val restore = Settings.bGet(this, Settings.B.RESTORE_SESSION, Settings.B.DEF_RESTORE_SESSION)
        val last = Settings.browser(this).getString("last_url", null)
        val target = if (restore && !last.isNullOrBlank()) last else Settings.homeUrl(this)
        if (target.isBlank()) showStartPage() else navigate(target)
    }

    /** Built-in instant start page: a search box (→ configured engine) + bookmark chips. */
    private fun showStartPage() {
        b.editUrl.setText("")
        pageUrl = null
        web.settings.javaScriptEnabled = true
        loadingTextDoc = true
        web.loadDataWithBaseURL("about:start", buildStartHtml(), "text/html", "utf-8", null)
        hideSpinner()
    }

    private fun buildStartHtml(): String {
        val tmpl = Settings.searchUrl(this).replace("\\", "\\\\").replace("'", "\\'")
        val chips = Settings.bookmarks(this).joinToString("") {
            "<a class=\"qlink\" href=\"${htmlEscape(it.url)}\">${htmlEscape(it.title.ifBlank { it.url })}</a>"
        }
        val quick = if (chips.isNotBlank()) "<div class=\"quick\">$chips</div>" else ""
        return "<!doctype html><html><head><meta charset=utf-8>" +
            "<meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<style>$START_CSS</style></head><body><main>" +
            "<form class=\"sb\" onsubmit=\"return go()\"><input id=\"q\" type=\"text\" " +
            "autocomplete=\"off\" autofocus placeholder=\"Search or enter URL\"></form>$quick</main>" +
            "<script>var T='$tmpl';function go(){var q=document.getElementById('q').value.trim();" +
            "if(!q)return false;var u;if(/^https?:\\/\\//.test(q))u=q;" +
            "else if(q.indexOf(' ')<0&&q.indexOf('.')>0)u='https://'+q;" +
            "else u=T.replace('%s',encodeURIComponent(q));location.href=u;return false;}</script></body></html>"
    }

    private fun htmlEscape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The single entry point for every navigation: text-browser render or a normal load. */
    private fun navigate(url: String) {
        pageUrl = url
        showSpinner()
        if (textOnly()) renderText(url) else loadAndShow(url)
    }

    /** Normal load; reflect the URL in the address bar at once (don't wait for onPageStarted). */
    private fun loadAndShow(url: String) {
        b.editUrl.setText(url)
        web.loadUrl(url)
    }

    private fun currentUrl(): String? =
        pageUrl ?: web.url?.takeIf { it != "about:blank" && !it.startsWith("data:") }

    // ── text browser mode ─────────────────────────────────────────────────────────
    // "Text only" fetches the raw HTML in ONE request through the tunnel and renders a
    // clean text+links document (jsoup). Big images become click-to-load placeholders.
    // If the page is JS-rendered (little static text), we load it for real and text-ify
    // the resulting DOM instead.

    private fun renderText(url: String) {
        b.editUrl.setText(url)
        showSpinner()
        setStatus("Loading…")
        val images = effImages()
        val lazy = Settings.bGet(this, Settings.B.LAZY_IMG, Settings.B.DEF_LAZY_IMG)
        val allowJs = effJs()
        val ua = web.settings.userAgentString
        if (consoleEnabled) DebugLog.add("[net] → GET $url")
        Thread({
            try {
                val t0 = System.currentTimeMillis()
                val cookie = CookieManager.getInstance().getCookie(url)
                val (code, html) = Downloader.fetchHtml(url, listen, cookie, ua)
                if (consoleEnabled) {
                    val ms = System.currentTimeMillis() - t0
                    val kbps = if (ms > 0) html.length.toLong() * 1000 / ms / 1024 else 0
                    DebugLog.add("[net] ←  $code  ${html.length} B  ${ms}ms  $kbps KB/s")
                }
                val res = Reader.fromHtml(html, url, images, lazy)
                if (consoleEnabled) DebugLog.add("[clean] parse ${if (res.ok) "ok" else "thin"} (${res.bodyHtml.length} B)")
                ui.post {
                    if (pageUrl != url) return@post           // navigated away while fetching
                    when {
                        res.ok -> showTextDoc(url, res.bodyHtml)
                        allowJs -> fallbackToJs(url, images, lazy)  // SPA: only pay the full render when needed
                        else -> { hideSpinner(); setStatusTransient("Clean: page needs JavaScript — turn on JS in ⚙", 8000) }
                    }
                }
            } catch (e: Exception) {
                if (consoleEnabled) DebugLog.add("[net] ✗ $url: ${e.message}")
                ui.post {
                    if (pageUrl != url) return@post
                    hideSpinner(); b.swipe.isRefreshing = false
                    setStatusTransient("⚠ ${e.message ?: "load failed"} — check the tunnel", 8000)
                }
            }
        }, "clean").start()
    }

    /** Render the cleaned document; our inline JS handles image expand + full-screen. */
    private fun showTextDoc(url: String, body: String) {
        web.settings.javaScriptEnabled = true        // only our injected img script runs (page scripts were stripped)
        web.settings.blockNetworkImage = false
        web.settings.loadsImagesAutomatically = true
        web.settings.textZoom = Settings.zoom(this)
        loadingTextDoc = true
        web.loadDataWithBaseURL(url, buildTextHtml(body), "text/html", "utf-8", url)
        hideSpinner()
        if (statusIsLoading()) hideStatus()
    }

    /** Page had no static content (JS-rendered): load it for real, text-ify the DOM on finish. */
    private fun fallbackToJs(url: String, images: Boolean, lazy: Boolean) {
        setStatus("Rendering (JavaScript)…")
        fallbackUrl = url
        fallbackImages = images
        fallbackLazy = lazy
        web.settings.javaScriptEnabled = true
        web.settings.blockNetworkImage = !images
        web.settings.loadsImagesAutomatically = images
        web.loadUrl(url)
    }

    private var hljsJsCache: String? = null
    private var hljsCssCache: String? = null
    private fun asset(name: String): String =
        runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
    private fun hljsJs(): String = hljsJsCache ?: asset("highlight.min.js").also { hljsJsCache = it }
    private fun hljsCss(): String = hljsCssCache ?: asset("hljs-theme.css").also { hljsCssCache = it }

    private fun buildTextHtml(body: String): String {
        // bundle highlight.js (offline asset) only when the page actually has code blocks
        val hasCode = body.contains("<pre") || body.contains("<code")
        val headExtra = if (hasCode) "<style>${hljsCss()}</style>" else ""
        val tailExtra = if (hasCode) "<script>${hljsJs()}</script><script>$CODE_JS</script>" else ""
        return "<!doctype html><html><head><meta charset=utf-8>" +
            "<meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<style>$TEXT_CSS</style>$headExtra</head><body><main>$body</main>" +
            "<script>$IMG_JS</script>$tailExtra</body></html>"
    }

    // Bar is permanently indeterminate (XML); show()/hide() start/stop its animation reliably.
    private fun showSpinner() { b.progress.show() }
    private fun hideSpinner() { b.progress.hide() }

    private fun adjustZoom(delta: Int) {
        val z = (Settings.zoom(this) + delta).coerceIn(60, 220)
        Settings.setZoom(this, z)
        b.txtZoom.text = "$z%"
        web.settings.textZoom = z
    }

    // ── debug console ─────────────────────────────────────────────────────────────

    private fun adjustConsoleMb(delta: Int) {
        val m = (Settings.consoleMb(this) + delta).coerceIn(1, 10)
        Settings.setConsoleMb(this, m)
        b.txtConsoleMb.text = "$m MB"
        if (consoleEnabled) DebugLog.maxBytes = m * 1_000_000
    }

    private val consolePoller = object : Runnable {
        override fun run() { refreshConsole(); ui.postDelayed(this, 1000) }
    }

    private fun setFrozen(on: Boolean) {
        frozen = on
        Settings.bSet(this, Settings.B.FREEZE, on)
        updateFreezeIcon()
        if (on) { applyFreeze(); showFrozenIndicator() }
        else { web.evaluateJavascript("window.__frozen=false;", null); setStatusTransient("Background resumed — pull down to reconnect") }
    }

    private fun updateFreezeIcon() {
        b.btnFreeze.setColorFilter(themeColor(
            if (frozen) com.google.android.material.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        ))
    }

    /** Persistent chip while frozen: shows state + how to update / resume without the menu. */
    private fun showFrozenIndicator() = setStatus("Frozen — pull down to refresh · tap to resume")

    private fun applyFreeze() = web.evaluateJavascript(FREEZE_APPLY_JS, null)

    private fun setConsole(on: Boolean) {
        consoleEnabled = on
        Settings.bSet(this, Settings.B.CONSOLE_ON, on)
        if (on) DebugLog.maxBytes = Settings.consoleMb(this) * 1_000_000 else DebugLog.clear()
        showConsoleOverlay(on)
    }

    private fun showConsoleOverlay(show: Boolean) {
        if (show) {
            b.consolePanel.post {
                val parentH = (b.consolePanel.parent as? android.view.View)?.height ?: 0
                val lp = b.consolePanel.layoutParams
                lp.height = (parentH / 5).coerceAtLeast(dp(120))
                b.consolePanel.layoutParams = lp
            }
            b.consolePanel.visibility = android.view.View.VISIBLE
            refreshConsole()
            ui.removeCallbacks(consolePoller); ui.post(consolePoller)
        } else {
            b.consolePanel.visibility = android.view.View.GONE
            ui.removeCallbacks(consolePoller)
        }
    }

    private fun refreshConsole() {
        if (b.consolePanel.visibility != android.view.View.VISIBLE) return
        val all = DebugLog.text()
        val text = when (consoleFilter) {
            "net" -> all.lineSequence().filter { it.startsWith("[net") }.joinToString("\n")
            "log" -> all.lineSequence().filter { !it.startsWith("[net") }.joinToString("\n")
            else -> all
        }
        if (b.txtConsole.text?.toString() != text) {
            b.txtConsole.text = text
            b.consoleScroll.post { b.consoleScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupConsoleDrag() {
        var startY = 0f; var startH = 0
        b.consoleHandle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { startY = e.rawY; startH = b.consolePanel.height; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val maxH = (b.consolePanel.parent as? android.view.View)?.height ?: 2000
                    val newH = (startH + (startY - e.rawY)).toInt().coerceIn(dp(72), maxH)
                    b.consolePanel.layoutParams = b.consolePanel.layoutParams.also { it.height = newH }
                    true
                }
                else -> false
            }
        }
    }

    // ── full-page screenshot ──────────────────────────────────────────────────────

    // web.draw() on a software canvas yields a blank page on modern WebView (Chromium renders
    // async, off-canvas). Instead scroll through the page and PixelCopy the real rendered pixels
    // of each screenful, stitching into one tall PNG.
    @Suppress("DEPRECATION")
    private fun captureFullPage() {
        val viewW = web.width
        val viewH = web.height
        // getScale() is 0 on modern WebView — use display density (1 CSS px ≈ 1 dp here)
        val full = (web.contentHeight * resources.displayMetrics.density).toInt()
            .coerceIn(viewH.coerceAtLeast(1), 20000)
        if (viewW <= 0 || viewH <= 0) { setStatus("Nothing to capture (w=$viewW h=$viewH)"); return }
        val out = try {
            android.graphics.Bitmap.createBitmap(viewW, full, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) { setStatus("Page too large to capture"); return }
        val canvas = android.graphics.Canvas(out)
        val seg = android.graphics.Bitmap.createBitmap(viewW, viewH, android.graphics.Bitmap.Config.ARGB_8888)
        val loc = IntArray(2); web.getLocationInWindow(loc)
        val src = android.graphics.Rect(loc[0], loc[1], loc[0] + viewW, loc[1] + viewH)
        val origScroll = web.scrollY
        val maxScroll = (full - viewH).coerceAtLeast(0)
        closePanel(); setStatus("Capturing…")

        fun finish() {
            seg.recycle()
            web.scrollTo(0, origScroll)
            Thread({
                try {
                    val host = runCatching { android.net.Uri.parse(currentUrl() ?: "").host }.getOrNull() ?: "page"
                    val name = "shot-$host-${System.currentTimeMillis()}.png"
                    Downloader.savePng(this@BrowserActivity, name, out)
                    ui.post { setStatusTransient("Saved to Downloads: $name") }
                } catch (e: Exception) {
                    ui.post { setStatus("Screenshot failed: ${e.message}") }
                } finally { out.recycle() }
            }, "shot").start()
        }

        fun step(targetY: Int) {
            web.scrollTo(0, targetY)
            web.postDelayed({
                val y = web.scrollY
                android.view.PixelCopy.request(window, src, seg, { res ->
                    if (res == android.view.PixelCopy.SUCCESS) {
                        val h = minOf(viewH, full - y)
                        canvas.drawBitmap(seg, android.graphics.Rect(0, 0, viewW, h),
                            android.graphics.Rect(0, y, viewW, y + h), null)
                    }
                    if (y >= maxScroll) finish() else step(targetY + viewH)
                }, ui)
            }, 180)
        }
        step(0)
    }

    /** evaluateJavascript hands back a JSON-encoded JS value; unwrap the outer string. */
    private fun decodeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return runCatching { org.json.JSONTokener(raw).nextValue() as? String ?: "" }.getOrDefault("")
    }

    // ── proxy ───────────────────────────────────────────────────────────────────

    private fun applyProxyThen(onReady: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            setStatus("⚠ This system WebView has no proxy support — update Android System WebView")
            onReady()
            return
        }
        val cfg = ProxyConfig.Builder()
            .addProxyRule("socks5://$listen")
            .removeImplicitRules()
            .build()
        val exec = Executor { it.run() }
        ProxyController.getInstance().setProxyOverride(cfg, exec) { onReady() }
    }

    // ── cookies (persist logins across restarts) ──────────────────────────────────

    private fun setupCookies() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
    }

    // ── webview ───────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                val r = ContentBlocker.intercept(request, topHost, opts)
                if (consoleEnabled) {
                    val u = request.url?.toString().orEmpty()
                    if (u.startsWith("http")) DebugLog.add("[net${if (r != null) " BLOCK" else ""}] ${request.method} $u")
                }
                return r
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                topHost = url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
                if (!loadingTextDoc) b.editUrl.setText(url)
                loadingTextDoc = false
                armWatchdog()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                ui.removeCallbacks(watchdog)
                b.swipe.isRefreshing = false
                // fallback path: a JS-rendered page finished → text-ify its DOM now
                val fb = fallbackUrl
                if (fb != null && url == fb) {
                    fallbackUrl = null
                    val js = DOM_CLEAN_JS
                        .replace("__IMAGES__", if (fallbackImages) "true" else "false")
                        .replace("__LAZY__", if (fallbackLazy) "true" else "false")
                    web.evaluateJavascript(js) { raw ->
                        val obj = runCatching { org.json.JSONObject(decodeJsString(raw)) }.getOrNull()
                        hideSpinner()
                        if (obj != null && obj.optBoolean("ok", false)) {
                            showTextDoc(fb, obj.optString("html", ""))
                        } else {
                            hideStatus() // give up text-ifying; leave the rendered page visible
                        }
                    }
                    return
                }
                hideSpinner()
                if (statusIsLoading()) hideStatus()
                if (effWebrtc() && effJs()) injectWebRtcGuard()
                Settings.browser(this@BrowserActivity).edit().putString("last_url", currentUrl() ?: url).apply()
                if (frozen) { showFrozenIndicator(); ui.postDelayed({ if (frozen) applyFreeze() }, 1500) }   // let snapshot arrive, then re-freeze
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (consoleEnabled) DebugLog.add("[err] ${request?.url} code=${error?.errorCode} ${error?.description}")
                if (request?.isForMainFrame == true) {
                    setStatusTransient("⚠ ${error?.description ?: "load error"} — check the tunnel", 8000)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
            ) {
                if (consoleEnabled) DebugLog.add("[http ${errorResponse?.statusCode}] ${request?.url}")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                // same-page #fragment → let the WebView scroll in-page, don't re-navigate
                val cur = pageUrl
                if (u.contains("#") && cur != null && u.substringBefore("#") == cur.substringBefore("#")) return false
                // in text mode, intercept link taps and render them as text too
                if (textOnly() && (u.startsWith("http://") || u.startsWith("https://"))) {
                    navigate(u); return true
                }
                return false
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (consoleEnabled) {
                    val src = m.sourceId()?.substringAfterLast('/').orEmpty()
                    DebugLog.add("[js:${m.messageLevel()}] ${m.message()} ($src:${m.lineNumber()})")
                }
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val loading = newProgress in 1..99
                if (loading) b.progress.show()   // hidden by onPageFinished, not the 100% tick
                if (loading) {
                    // progress feedback on the status line — but never clobber an active error
                    if (!(b.txtStatus.text?.toString() ?: "").startsWith("⚠")) {
                        setStatus(getString(R.string.loading_fmt, newProgress))
                    }
                } else if (newProgress >= 100) {
                    b.swipe.isRefreshing = false
                }
            }
        }

        web.setDownloadListener { url, ua, disposition, mime, _ ->
            Downloader.download(this, url, ua, disposition, mime, listen) { s -> ui.post { setStatus(s) } }
        }

        b.swipe.setColorSchemeColors(themeColor(com.google.android.material.R.attr.colorPrimary))
        b.swipe.setProgressBackgroundColorSchemeColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainer))
        b.swipe.setOnRefreshListener {
            val u = currentUrl()
            if (textOnly() && u != null) renderText(u) else web.reload()
        }

        b.editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { go(b.editUrl.text.toString()); true } else false
        }
        b.btnPanel.setOnClickListener { togglePanel() }
        b.btnFreeze.setOnClickListener { setFrozen(!frozen) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, STREAM_TRACKER_JS, setOf("*"))
        }
        // tap the status line to copy it (handy for errors); when frozen, tap = resume
        b.txtStatus.setOnClickListener {
            if (frozen) { setFrozen(false); return@setOnClickListener }  // tap chip to resume
            val t = b.txtStatus.text?.toString().orEmpty()
            if (t.isNotBlank() && !t.startsWith("Copied")) { copyToClipboard(t); setStatusTransient("Copied", 1500) }
        }
    }

    private fun togglePanel() {
        val show = b.panel.visibility != android.view.View.VISIBLE
        b.panel.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        if (show) { updateNavState(); renderBookmarks() }
    }

    private fun closePanel() { b.panel.visibility = android.view.View.GONE }

    private fun updateNavState() {
        val back = web.canGoBack(); val fwd = web.canGoForward()
        b.btnNavBack.isEnabled = back; b.btnNavBack.alpha = if (back) 1f else 0.35f
        b.btnNavFwd.isEnabled = fwd; b.btnNavFwd.alpha = if (fwd) 1f else 0.35f
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun go(raw: String) {
        val q = raw.trim()
        val url = when {
            q.isEmpty() -> return
            q.startsWith("http://") || q.startsWith("https://") -> q
            !q.contains('.') || q.contains(' ') ->
                Settings.searchUrl(this).replace("%s", android.net.Uri.encode(q))
            else -> "https://$q"
        }
        navigate(url)
    }

    private fun injectWebRtcGuard() {
        web.evaluateJavascript(
            "(function(){try{['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection','RTCDataChannel']" +
                ".forEach(function(k){try{Object.defineProperty(window,k,{value:undefined,configurable:false});}" +
                "catch(e){window[k]=undefined;}});}catch(e){}})();", null
        )
    }

    // ── settings / toggles ──────────────────────────────────────────────────────

    // "Clean" reformat mode. Images/JS toggles stay active in it: images = inline vs
    // click-to-load placeholders; JS = allow the SPA fallback (jsoup is always tried first).
    private fun cleanMode() = Settings.bGet(this, Settings.B.TEXTONLY, Settings.B.DEF_TEXTONLY)
    private fun textOnly() = cleanMode()
    private fun effImages() = Settings.bGet(this, Settings.B.IMAGES, Settings.B.DEF_IMAGES)
    private fun effJs() = Settings.bGet(this, Settings.B.JS, Settings.B.DEF_JS)
    private fun effFonts() = textOnly() || Settings.bGet(this, Settings.B.FONTS, Settings.B.DEF_FONTS)
    private fun effMedia() = textOnly() || Settings.bGet(this, Settings.B.MEDIA, Settings.B.DEF_MEDIA)
    private fun effFrames() = textOnly() || Settings.bGet(this, Settings.B.FRAMES, Settings.B.DEF_FRAMES)
    private fun effAdblock() = Settings.bGet(this, Settings.B.ADBLOCK, Settings.B.DEF_ADBLOCK)
    private fun effSpeculative() = textOnly() || Settings.bGet(this, Settings.B.SPECULATIVE, Settings.B.DEF_SPECULATIVE)
    private fun effWebrtc() = Settings.bGet(this, Settings.B.WEBRTC, Settings.B.DEF_WEBRTC)

    private fun applyWebSettings(reload: Boolean) {
        web.settings.javaScriptEnabled = effJs()
        web.settings.blockNetworkImage = !effImages()
        web.settings.loadsImagesAutomatically = effImages()
        web.settings.textZoom = Settings.zoom(this)
        opts = ContentBlocker.Opts(
            adblock = effAdblock(),
            fonts = effFonts(),
            media = effMedia(),
            frames = effFrames(),
            speculative = effSpeculative(),
        )
        if (reload) web.reload()
    }

    private fun bindPanel() {
        // reflect current state. "This page" toggles read ON = show/allow.
        b.swImages.isChecked = Settings.bGet(this, Settings.B.IMAGES, Settings.B.DEF_IMAGES)
        b.swLazy.isChecked = Settings.bGet(this, Settings.B.LAZY_IMG, Settings.B.DEF_LAZY_IMG)
        b.swJs.isChecked = Settings.bGet(this, Settings.B.JS, Settings.B.DEF_JS)
        // fonts/media/frames/speculative are stored as "block" flags — show the inverse
        b.swFonts.isChecked = !Settings.bGet(this, Settings.B.FONTS, Settings.B.DEF_FONTS)
        b.swMedia.isChecked = !Settings.bGet(this, Settings.B.MEDIA, Settings.B.DEF_MEDIA)
        b.swFrames.isChecked = !Settings.bGet(this, Settings.B.FRAMES, Settings.B.DEF_FRAMES)
        b.swSpeculative.isChecked = !Settings.bGet(this, Settings.B.SPECULATIVE, Settings.B.DEF_SPECULATIVE)
        b.swText.isChecked = Settings.bGet(this, Settings.B.TEXTONLY, Settings.B.DEF_TEXTONLY)
        // "Protection" toggles read ON = protect (stored value used directly)
        b.swAdblock.isChecked = Settings.bGet(this, Settings.B.ADBLOCK, Settings.B.DEF_ADBLOCK)
        b.swWebrtc.isChecked = Settings.bGet(this, Settings.B.WEBRTC, Settings.B.DEF_WEBRTC)
        updateTextOnlyDimming()

        // images apply live (no reload); everything else reloads to take effect now.
        // the inverted toggles store !checked because the pref is a "block" flag.
        b.swImages.setOnCheckedChangeListener { _, v ->
            Settings.bSet(this, Settings.B.IMAGES, v); applyWebSettings(false)
            if (textOnly()) currentUrl()?.let { renderText(it) }   // re-render clean doc with/without images
        }
        b.swLazy.setOnCheckedChangeListener { _, v ->
            Settings.bSet(this, Settings.B.LAZY_IMG, v)
            if (textOnly()) currentUrl()?.let { renderText(it) }
        }
        b.swJs.setOnCheckedChangeListener { _, v -> Settings.bSet(this, Settings.B.JS, v); applyWebSettings(true) }
        b.swFonts.setOnCheckedChangeListener { _, v -> Settings.bSet(this, Settings.B.FONTS, !v); applyWebSettings(true) }
        b.swMedia.setOnCheckedChangeListener { _, v -> Settings.bSet(this, Settings.B.MEDIA, !v); applyWebSettings(true) }
        b.swFrames.setOnCheckedChangeListener { _, v -> Settings.bSet(this, Settings.B.FRAMES, !v); applyWebSettings(true) }
        b.swSpeculative.setOnCheckedChangeListener { _, v -> Settings.bSet(this, Settings.B.SPECULATIVE, !v); applyWebSettings(true) }
        b.swText.setOnCheckedChangeListener { _, v ->
            Settings.bSet(this, Settings.B.TEXTONLY, v); updateTextOnlyDimming(); applyWebSettings(false)
            val u = currentUrl(); if (u != null) navigate(u)
        }

        // text size (applies live to the current page, no reload)
        b.txtZoom.text = "${Settings.zoom(this)}%"
        b.btnZoomOut.setOnClickListener { adjustZoom(-10) }
        b.btnZoomIn.setOnClickListener { adjustZoom(10) }
        b.swAdblock.setOnCheckedChangeListener { _, v -> Settings.bSet(this, Settings.B.ADBLOCK, v); applyWebSettings(true) }
        b.swWebrtc.setOnCheckedChangeListener { _, v -> Settings.bSet(this, Settings.B.WEBRTC, v); applyWebSettings(true) }

        // navigation
        b.btnNavBack.setOnClickListener { if (web.canGoBack()) web.goBack(); closePanel() }
        b.btnNavFwd.setOnClickListener { if (web.canGoForward()) web.goForward(); closePanel() }
        b.btnConnection.setOnClickListener { finish() }
        b.btnHome.setOnClickListener {
            val u = currentUrl() ?: web.url
            val root = u?.let {
                runCatching {
                    val uri = android.net.Uri.parse(it)
                    if (uri.host != null) "${uri.scheme}://${uri.host}/" else null
                }.getOrNull()
            }
            closePanel()
            if (root != null) navigate(root)
        }
        b.btnBookmarkAdd.setOnClickListener {
            val u = web.url ?: b.editUrl.text.toString()
            if (u.isNotBlank() && u != "about:blank") {
                Settings.addBookmark(this, web.title ?: u, u)
                renderBookmarks()
                setStatusTransient(getString(R.string.bookmark_saved))
            }
        }

        // startup: home page + session restore
        b.editHome.setText(Settings.homeUrl(this))
        b.editHome.setOnFocusChangeListener { _, has -> if (!has) Settings.setHomeUrl(this, b.editHome.text.toString()) }
        b.editSearch.setText(Settings.searchUrl(this))
        b.editSearch.setOnFocusChangeListener { _, has -> if (!has) Settings.setSearchUrl(this, b.editSearch.text.toString()) }
        b.swRestore.isChecked = Settings.bGet(this, Settings.B.RESTORE_SESSION, Settings.B.DEF_RESTORE_SESSION)
        b.swRestore.setOnCheckedChangeListener { _, v -> Settings.bSet(this, Settings.B.RESTORE_SESSION, v) }

        renderBookmarks()

        // blocklist source + update (inside the collapsible setup section)
        b.editBlocklistUrl.setText(Settings.blocklistUrl(this))
        renderListInfo()
        b.btnUpdateList.setOnClickListener { updateBlocklist() }

        // collapsible "More / setup" — set-and-forget config, hidden by default
        b.moreHeader.text = "▸ " + getString(R.string.more_title)
        b.moreHeader.setOnClickListener {
            val show = b.moreBody.visibility != android.view.View.VISIBLE
            b.moreBody.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            b.moreHeader.text = (if (show) "▾ " else "▸ ") + getString(R.string.more_title)
        }

        b.btnClearCache.setOnClickListener {
            web.clearCache(true)
            web.clearFormData()
            setStatusTransient(getString(R.string.cache_cleared))
            closePanel()
            val u = currentUrl()
            if (textOnly() && u != null) renderText(u) else web.reload()
        }

        // tools: screenshot
        // the open panel squeezes the WebView to height 0 — close it and let the layout
        // settle (a couple of frames) before measuring/capturing
        b.btnShot.setOnClickListener { closePanel(); web.postDelayed({ captureFullPage() }, 200) }

        // tools: debug console (works in any mode; off by default)
        // freeze state (toggled via the toolbar icon)
        frozen = Settings.bGet(this, Settings.B.FREEZE, Settings.B.DEF_FREEZE)
        updateFreezeIcon()
        if (frozen) showFrozenIndicator()

        consoleEnabled = Settings.bGet(this, Settings.B.CONSOLE_ON, Settings.B.DEF_CONSOLE_ON)
        b.swConsole.isChecked = consoleEnabled
        b.swConsole.setOnCheckedChangeListener { _, v -> setConsole(v); if (v) closePanel() }
        b.btnConsoleClose.setOnClickListener { b.swConsole.isChecked = false }
        b.btnConsoleClear.setOnClickListener { DebugLog.clear(); b.txtConsole.text = "" }
        b.btnConsoleCopy.setOnClickListener {
            getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("console", b.txtConsole.text))
            setStatusTransient("Console copied")
        }
        b.btnTabAll.setOnClickListener { consoleFilter = "all"; refreshConsole() }
        b.btnTabNet.setOnClickListener { consoleFilter = "net"; refreshConsole() }
        b.btnTabLog.setOnClickListener { consoleFilter = "log"; refreshConsole() }
        setupConsoleDrag()
        b.txtConsoleMb.text = "${Settings.consoleMb(this)} MB"
        b.btnMbMinus.setOnClickListener { adjustConsoleMb(-1) }
        b.btnMbPlus.setOnClickListener { adjustConsoleMb(1) }
        if (consoleEnabled) showConsoleOverlay(true)
    }

    private fun hideStatus() {
        b.txtStatus.text = ""
        b.txtStatus.visibility = android.view.View.GONE
    }

    private fun statusIsLoading(): Boolean =
        (b.txtStatus.text?.toString() ?: "").let { it.startsWith("Loading") || it.startsWith("Rendering") }

    private fun themeDrawable(attr: Int): android.graphics.drawable.Drawable? {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val d = ta.getDrawable(0)
        ta.recycle()
        return d
    }

    /** Rebuild the bookmark rows in the panel: tap a title to open, ✕ to delete. */
    private fun renderBookmarks() {
        val list = Settings.bookmarks(this)
        b.bmList.removeAllViews()
        b.txtBookmarksEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        for (bm in list) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val title = android.widget.TextView(this).apply {
                text = bm.title.ifBlank { bm.url }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 14f
                setPadding(0, dp(10), dp(8), dp(10))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                isClickable = true
                background = themeDrawable(android.R.attr.selectableItemBackground)
                setOnClickListener { closePanel(); navigate(bm.url) }
            }
            val del = android.widget.TextView(this).apply {
                text = "✕"
                textSize = 16f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true
                background = themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
                setOnClickListener {
                    Settings.removeBookmark(this@BrowserActivity, bm.url)
                    renderBookmarks()
                    setStatusTransient(getString(R.string.bookmark_removed))
                }
            }
            row.addView(title); row.addView(del)
            b.bmList.addView(row)
        }
    }

    /** In Clean mode, fonts/media/frames/speculative don't apply (we render our own doc) — dim them.
     *  Images and JS stay active: they control inline-vs-placeholder and the SPA fallback. */
    private fun updateTextOnlyDimming() {
        val on = b.swText.isChecked
        for (v in listOf(b.swFrames, b.swFonts, b.swMedia, b.swSpeculative)) {
            v.isEnabled = !on
            v.alpha = if (on) 0.4f else 1f
        }
    }

    private fun renderListInfo() {
        val count = Settings.browser(this).getInt(Settings.B.BLOCKLIST_COUNT, ContentBlocker.count())
        b.txtListInfo.text = if (count > 0) "$count domains" else "bundled list"
    }

    private fun updateBlocklist() {
        val url = b.editBlocklistUrl.text.toString().trim().ifBlank { Settings.B.DEF_BLOCKLIST_URL }
        Settings.browser(this).edit().putString(Settings.B.BLOCKLIST_URL, url).apply()
        b.btnUpdateList.isEnabled = false
        b.txtListInfo.text = "updating…"
        Thread({
            try {
                val n = Downloader.fetchBlocklist(this, url, listen)
                Settings.browser(this).edit().putInt(Settings.B.BLOCKLIST_COUNT, n).apply()
                ui.post { b.txtListInfo.text = "$n domains"; b.btnUpdateList.isEnabled = true }
            } catch (e: Exception) {
                ui.post { setStatus("✗ blocklist update: ${e.message}"); b.txtListInfo.text = "update failed"; b.btnUpdateList.isEnabled = true }
            }
        }, "blocklist").start()
    }

    // ── status line (no popups) ───────────────────────────────────────────────────

    private val hideStatusRunnable = Runnable { hideStatus() }

    private fun copyToClipboard(text: String) {
        getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
    }

    private fun setStatus(text: String) {
        ui.removeCallbacks(hideStatusRunnable)   // a newer message cancels a pending auto-hide
        b.txtStatus.text = text
        b.txtStatus.visibility = android.view.View.VISIBLE
    }

    /** Show a confirmation that auto-hides after [ms] (used for one-off results, not errors). */
    private fun setStatusTransient(text: String, ms: Long = 4000) {
        setStatus(text)
        ui.postDelayed(hideStatusRunnable, ms)
    }

    private fun pollTunnelErrors() {
        val logs = runCatching { mobile.Mobile.recentLogs() }.getOrNull() ?: return
        val line = logs.lineSequence().lastOrNull { l ->
            val s = l.lowercase()
            s.contains("429") || s.contains("rate limited") || s.contains("error") ||
                s.contains("fail") || s.contains("timeout")
        } ?: return
        if (line != lastErrLine) {
            lastErrLine = line
            setStatusTransient("⚠ ${line.substringAfter("] ", line)}", 8000)
        }
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        ui.post(logPoller)
        if (consoleEnabled && b.consolePanel.visibility == android.view.View.VISIBLE) ui.post(consolePoller)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(logPoller)
        ui.removeCallbacks(consolePoller)
        Settings.setHomeUrl(this, b.editHome.text.toString())
        Settings.setSearchUrl(this, b.editSearch.text.toString())
        CookieManager.getInstance().flush()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (b.consolePanel.visibility == android.view.View.VISIBLE) {
            b.swConsole.isChecked = false   // dismiss console
        } else if (b.panel.visibility == android.view.View.VISIBLE) {
            b.panel.visibility = android.view.View.GONE
        } else if (web.canGoBack()) {
            web.goBack()
        } else {
            @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        DebugLog.clear()
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        val START_CSS = """
:root{color-scheme:light dark}
html,body{margin:0;height:100%;background:#fff8f5;color:#241b15}
main{min-height:100%;box-sizing:border-box;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:24px;font-family:system-ui,sans-serif}
.sb{width:100%;max-width:34rem}
#q{width:100%;box-sizing:border-box;padding:14px 18px;font-size:17px;border:1px solid #d7c3b8;border-radius:24px;background:#fff;color:#241b15;outline:none}
.quick{margin-top:22px;max-width:34rem;width:100%;display:flex;flex-wrap:wrap;gap:8px;justify-content:center}
.qlink{padding:6px 12px;border:1px solid #d7c3b8;border-radius:16px;font-size:.85em;text-decoration:none;color:#9a4a1b;max-width:14rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
@media(prefers-color-scheme:dark){html,body{background:#1a120d;color:#ece0d9}#q{background:#271d17;color:#ece0d9;border-color:#52443b}.qlink{color:#ffb68e;border-color:#52443b}}
""".trim()

        val TEXT_CSS = """
:root{color-scheme:light dark}
html,body{margin:0;background:#fff8f5;color:#241b15}
main{margin:0 auto;max-width:42rem;padding:20px 18px 64px;line-height:1.7;font-size:18px;font-family:Georgia,'Noto Serif',serif}
h1{font-size:1.6em;line-height:1.25;margin:0 0 .6em;font-family:system-ui,sans-serif}
h2,h3,h4{line-height:1.3;font-family:system-ui,sans-serif}
a{color:#9a4a1b}
p{margin:0 0 1em}
ul,ol{padding-left:1.3em}
li{margin:.2em 0}
code,pre{font-family:monospace;font-size:.9em}
pre{white-space:pre-wrap;background:rgba(0,0,0,.05);padding:10px;border-radius:6px;overflow-x:auto;position:relative}
.copybtn{position:absolute;top:6px;right:6px;font:600 11px system-ui,sans-serif;padding:3px 9px;border:none;border-radius:5px;background:rgba(255,255,255,.16);color:#dde;cursor:pointer}
.copybtn:active{background:rgba(255,255,255,.3)}
blockquote{margin:1em 0;padding-left:1em;border-left:3px solid #d7c3b8;color:#52443b}
hr{border:none;border-top:1px solid #d7c3b8;margin:1.5em 0}
.inl{display:inline}
a.imgph{display:block;margin:.7em auto;max-width:22rem;padding:16px 12px;border:1px dashed currentColor;border-radius:10px;text-align:center;font-family:system-ui,sans-serif;font-size:.85em;text-decoration:none;opacity:.7}
a.imgph::before{content:"▦";display:block;font-size:1.7em;line-height:1;margin-bottom:6px;opacity:.85}
a.imgph.loading{animation:imgpulse 1.1s ease-in-out infinite}
@keyframes imgpulse{0%,100%{opacity:.4}50%{opacity:.85}}
img.rimg{max-width:100%;height:auto;display:block;margin:.6em auto;border-radius:6px;cursor:zoom-in}
img.zoom{position:fixed;inset:0;width:100vw;height:100vh;object-fit:contain;background:#000;margin:0;max-width:none;z-index:9999;cursor:zoom-out}
@media(prefers-color-scheme:dark){
 html,body{background:#1a120d;color:#ece0d9}
 a{color:#ffb68e}
 blockquote{border-color:#52443b;color:#b3a79d}
 hr{border-color:#52443b}
 pre{background:rgba(255,255,255,.06)}
}
""".trim()

        // Injected into the clean document: tap a placeholder → show "loading…", preload the
        // image through the tunnel, swap it in on load (or "image failed"); tap a loaded image
        // → full-screen; tap again → back.
        val IMG_JS = """
(function(){
 function loadPh(ph){
  if(ph.getAttribute('data-loading'))return; ph.setAttribute('data-loading','1');
  ph.classList.add('loading'); ph.textContent='loading…';
  var im=new Image(); im.className='rimg';
  im.onload=function(){ if(ph.parentNode)ph.parentNode.replaceChild(im,ph); };
  im.onerror=function(){ ph.classList.remove('loading'); ph.textContent='image failed'; ph.removeAttribute('data-loading'); };
  im.src=ph.getAttribute('data-src');
 }
 if('IntersectionObserver' in window){
  var obs=new IntersectionObserver(function(es){es.forEach(function(en){
   if(en.isIntersecting){loadPh(en.target);obs.unobserve(en.target);}
  });},{rootMargin:'600px 0px'});
  document.querySelectorAll('a.imgph.lazy').forEach(function(ph){obs.observe(ph);});
 }else{
  document.querySelectorAll('a.imgph.lazy').forEach(loadPh);
 }
 document.addEventListener('click',function(e){
  var ph=e.target.closest&&e.target.closest('a.imgph');
  if(ph){e.preventDefault();loadPh(ph);return;}
  var z=e.target.closest&&e.target.closest('img');
  if(z&&z.getAttribute('src')){e.preventDefault();z.classList.toggle('zoom');}
 },false);
})();
""".trim()

        // Document-start: wrap WebSocket/EventSource BEFORE the page's scripts so we can
        // track instances and, while frozen, close them + block reconnects. Navigation,
        // fetch/XHR, forms and clicks are untouched (user actions still go through).
        val STREAM_TRACKER_JS = """
(function(){try{
 if(window.__wbWrapped)return; window.__wbWrapped=true;
 window.__frozen=false; window.__streams=[];
 var deadWS={send:function(){},close:function(){},addEventListener:function(){},removeEventListener:function(){},readyState:3,onopen:null,onmessage:null,onerror:null,onclose:null};
 var OW=window.WebSocket;
 if(OW){window.WebSocket=function(u,p){ if(window.__frozen)return deadWS; var w=(p!==undefined)?new OW(u,p):new OW(u); try{window.__streams.push(w);}catch(e){} return w; }; try{window.WebSocket.prototype=OW.prototype;}catch(e){}}
 var deadES={close:function(){},addEventListener:function(){},removeEventListener:function(){},readyState:2,onmessage:null,onerror:null,onopen:null};
 var OE=window.EventSource;
 if(OE){window.EventSource=function(u,o){ if(window.__frozen)return deadES; var s=new OE(u,o); try{window.__streams.push(s);}catch(e){} return s; }; try{window.EventSource.prototype=OE.prototype;}catch(e){}}
}catch(e){}})();
""".trim()

        // Apply freeze in the current page: block new streams, close open ones, kill polling.
        val FREEZE_APPLY_JS = """
(function(){try{
 window.__frozen=true;
 (window.__streams||[]).forEach(function(s){try{s.close();}catch(e){}}); window.__streams=[];
 try{var hi=setInterval(function(){},999999);for(var i=0;i<=hi;i++)clearInterval(i);clearInterval(hi);}catch(e){}
 try{window.setInterval=function(){return 0;};}catch(e){}
}catch(e){}})();
""".trim()

        // Highlight code blocks (offline highlight.js) and add a copy button to each <pre>.
        val CODE_JS = """
(function(){try{
 document.querySelectorAll('pre').forEach(function(pre){
  var code=pre.querySelector('code')||pre;
  if(window.hljs){try{hljs.highlightElement(code);}catch(e){}}
  var btn=document.createElement('button');btn.className='copybtn';btn.textContent='copy';
  btn.addEventListener('click',function(ev){ev.preventDefault();ev.stopPropagation();
   var t=code.innerText||pre.innerText;
   var ta=document.createElement('textarea');ta.value=t;ta.style.position='fixed';ta.style.opacity='0';
   document.body.appendChild(ta);ta.focus();ta.select();var ok=false;try{ok=document.execCommand('copy');}catch(e){}
   ta.remove();btn.textContent=ok?'copied':'failed';setTimeout(function(){btn.textContent='copy';},1500);
  });
  pre.appendChild(btn);
 });
}catch(e){}})();
""".trim()

        // Fallback for JS-rendered pages: clean the live DOM the same way Reader does.
        // __IMAGES__ is substituted with true/false before evaluation.
        val DOM_CLEAN_JS = """
(function(){try{
 var IMAGES=__IMAGES__;var LAZY=__LAZY__;
 var b=document.body.cloneNode(true);
 b.querySelectorAll('script,style,noscript,template,iframe,svg,canvas,video,audio,object,embed,form,button,input,select,textarea,source,[aria-hidden=true],[hidden]').forEach(function(n){n.remove();});
 b.querySelectorAll('img').forEach(function(im){
  var s=im.currentSrc||im.getAttribute('src')||im.getAttribute('data-src')||im.getAttribute('data-original')||'';
  try{if(s)s=new URL(s,location.href).href;}catch(e){}
  var w=parseInt(im.getAttribute('width'))||im.naturalWidth||0;
  var h=parseInt(im.getAttribute('height'))||im.naturalHeight||0;
  var hint=((im.className||'')+' '+(im.alt||'')+' '+s);
  if(!s||s.indexOf('data:')===0||(w&&w<150)||(h&&h<150)||/icon|logo|sprite|avatar|emoji|favicon|badge|spacer|pixel/i.test(hint)){im.remove();return;}
  var el;
  if(IMAGES&&!LAZY){el=document.createElement('img');el.className='rimg';el.setAttribute('src',s);if(im.alt)el.setAttribute('alt',im.alt);}
  else{el=document.createElement('a');el.className=IMAGES?'imgph lazy':'imgph';el.setAttribute('data-src',s);el.textContent=(im.alt&&im.alt.trim())?('image: '+im.alt.trim()):'image';}
  im.parentNode&&im.parentNode.replaceChild(el,im);
 });
 b.querySelectorAll('a,b,i,em,strong,code,span,font,small,time,abbr,label').forEach(function(el){var p=el.parentNode;if(!p)return;var n=el.nextSibling;if(n){if(n.nodeType===1){p.insertBefore(document.createTextNode(' '),n);}else if(n.nodeType===3&&n.nodeValue&&/^[0-9A-Za-zЀ-ӿ]/.test(n.nodeValue)){p.insertBefore(document.createTextNode(' '),n);}}var q=el.previousSibling;if(q&&q.nodeType===3&&q.nodeValue&&/[0-9A-Za-zЀ-ӿ]$/.test(q.nodeValue)){p.insertBefore(document.createTextNode(' '),el);}});
 b.querySelectorAll('*').forEach(function(e){
  if(e.classList&&(e.classList.contains('imgph')||e.classList.contains('rimg')||e.classList.contains('lazy')))return;
  var id=e.id;
  if(e.tagName==='A'){var raw=e.getAttribute('href')||'';var h=raw.charAt(0)==='#'?raw:e.href;while(e.attributes.length)e.removeAttribute(e.attributes[0].name);if(h)e.setAttribute('href',h);if(id)e.setAttribute('id',id);return;}
  while(e.attributes.length)e.removeAttribute(e.attributes[0].name);if(id)e.setAttribute('id',id);
 });
 return JSON.stringify({html:b.innerHTML,ok:(b.innerText||'').trim().length>60});
}catch(e){return JSON.stringify({ok:false,error:String(e)});}})();
""".trim()
    }
}
