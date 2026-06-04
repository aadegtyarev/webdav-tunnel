# Backlog — ideas / deferred work

Context: the tunnel's real bottleneck is **relay latency** (cloud WebDAV ≈ 1.5–8 s per
round-trip), not bandwidth. So the metric that matters is the **number of sequential
round-trips**, not bytes. Most ideas below aim at collapsing round-trips.

## Next up (agreed)

- [ ] **Code syntax highlighting in Clean mode** — bundle highlight.js (+ a warm theme) as a
      local asset, inject into the clean document, run over `<pre><code>`. Must stay offline
      (no CDN through the tunnel).
- [ ] **"Copy" button on code blocks** — small button per `<pre>`, copies the block text.

## Product direction

- [ ] **Reframe the app: the browser is the product, the relay is an option.** The Clean
      mode / reader / lazy images / debug console have grown into a genuinely useful
      latency-friendly browser. Make the WebDAV-relay tunnel an *optional* connection mode
      (direct / system-proxy / WebDAV-relay), with normal web surfing as the primary feature.
      Rework onboarding/UX around "a browser" rather than "a tunnel client".
  - [ ] **Proxy mode selector: Direct / HTTP / SOCKS / WebDAV-Relay.** WebDAV-Relay as a
        browser transport is genuinely novel. Per-mode settings.
  - [ ] **Hide-address-bar button** (immersive reading). Toggle the toolbar away.
  - Note: the bundle of handy, one-tap power features (Clean, reader, lazy images, content
    controls, debug console) is a real differentiator — lean into it.

## Bigger ideas

- [ ] **Server-side resource bundler** — the exit node has fast internet; let the client send
      a list of image (and maybe CSS) URLs in ONE request through the slow relay, have the
      server fetch them all and return a single bundled response. Collapses N image
      round-trips into 1 — the proper fix for image loading on a latency-bound relay.
      Cost: new app-level service on the server (today it is a pure SOCKS passthrough) +
      bundle format + client logic. Architecturally the biggest latency win available.
      - Open question: a separate tunnel control-stream vs. a magic SOCKS target the server
        intercepts; bundle encoding (multipart / length-prefixed / JSON+base64).

## Freeze background streams (latency)

- [ ] **Freeze background activity** (normal mode) — pages with WebSocket / MQTT-over-WS /
      SSE / interval-polling keep many streams open and thrash the high-latency relay.
      - Cheap first step: `WebView.pauseTimers()` / `resumeTimers()` toggle — kills all JS
        timers (interval polling). Does NOT stop event-driven WS/SSE.
      - Full version: inject a JS shim at document-start (rewrite the main HTML, as Clean
        already fetches it) that wraps `WebSocket`/`EventSource`/`fetch`/`XHR` into a frozen
        state. A "Thaw/Refresh" button resumes for N seconds (let the app reconnect + poll),
        then re-freezes — instead of a full page reload.
      - Note: "do exactly one poll cycle" has no standard web hook → realistic UX is
        Freeze + timed Thaw, not a guaranteed single cycle.
      - Clean mode already eliminates this entirely (all scripts stripped) — this is for
        full-JS browsing.

## Clean mode polish (iterate from screenshots)

- [ ] Reflow artifacts: keep tuning the jsoup pass (short wrapper boxes → one line, link
      run-together, empty list bullets, duplicate headings). Already partially done; revisit
      against live pages.
- [ ] Consider whether to default Clean's images to OFF (placeholders) given each image is a
      round-trip on the relay — currently default ON per user preference.

## Notes

- Reader/Clean = single jsoup fetch for server-rendered pages (1 round-trip); JS fallback
  only for SPAs (no cheaper path exists — HTML must be fetched before its JS is even known).
