# WebDAV Tunnel — Android client

A small Android app that runs the WebDAV tunnel **SOCKS5 client** on the phone, plus a
built-in browser pinned to that proxy. Traffic leaves through your exit server's IP.

```
app on phone → SOCKS5 127.0.0.1:1080 (this app) → WebDAV relay → exit server → internet
```

It uses a foreground service (a persistent notification), **not** a system VPN — so you
point individual SOCKS5-aware apps at the proxy, or use the bundled browser which is wired
to it automatically.

## Features

- **SOCKS5 client** as a foreground service — survives backgrounding; status on the main screen.
- **In-app browser** locked to the tunnel (DNS resolved proxy-side, so no DNS leak):
  - pull-to-refresh, history back/forward, bookmarks, a one-tap return to the connection screen;
  - **reader mode** (📖) — strips a page to clean article typography;
  - **content controls** (in the ⚙ panel): images, JavaScript, web fonts, media, third-party
    frames, speculative loads, plus an **ad-block** host list and a **WebRTC leak guard**;
  - configurable **home page** and optional last-session restore;
  - a visible loading indicator and a **clear-cache** button.
- **Warm Material 3 theme** that follows the system light/dark setting.
- Credentials are **never hard-coded** — you enter them in the app; they stay in the app's
  private storage only.

## Install

### Option A — prebuilt APK (recommended)

Download `webdav-tunnel-android.apk` from the repository **Releases**, copy it to the phone,
allow "install from unknown sources", and install. It is a debug-signed APK — for a clean
distribution build your own release (see below).

### Option B — build from source

See [Build from source](#build-from-source).

After installing, open **WebDAV Tunnel** and allow notifications (required for the
foreground service).

## Configure

You need a working WebDAV relay and an exit server first — see the repository
[deployment guide](../deploy/README.md). Then, on the main screen:

- **WebDAV URL** — e.g. `https://webdav.yandex.ru` (the default).
- **Login** — your WebDAV username (for Yandex, the part before `@`, or the full email).
- **App password** — the WebDAV/app password (with 2FA you must use an *app password*, not
  the account password — see the deployment guide).
- **SOCKS5 listen** — leave `127.0.0.1:1080`.

Tap **Connect**. A green status means the tunnel is up (the app pings WebDAV on start, up to
~15 s). Speed/timeout knobs live under **Advanced: speed & timeouts**.

## Use it

- **Built-in browser** — tap **Open browser**. Everything (including DNS) goes through the
  tunnel. Type a query in the address bar to search; tap ⚙ for content/bookmarks/setup; tap
  📖 for reader mode.
- **Other apps** — point any SOCKS5-aware app at `127.0.0.1:1080`. Example (Telegram):
  Settings → Data and Storage → Proxy → Add → SOCKS5, host `127.0.0.1`, port `1080`, no
  login/password.
- **Check the exit IP** — open `https://api.ipify.org` in a proxied app; it should show your
  exit server's address.

> ⚠️ Throughput is bounded by the cloud WebDAV round-trip (Yandex.Disk ≈ several seconds per
> round). Web browsing is usable; latency-sensitive apps (e.g. real-time chat handshakes) are
> marginal. The default content profile (media and speculative loads off) keeps pages light.

## Build from source

Prerequisites: Go 1.22+, Android SDK, Android NDK, JDK 17.

```sh
# 1) (re)build the gomobile AAR — needed whenever the Go tunnel code changes
go install golang.org/x/mobile/cmd/gomobile@latest
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<your-ndk-version>
gomobile bind -target=android/arm64,android/arm -androidapi 24 \
  -o android/app/libs/webdavtunnel.aar -trimpath -ldflags="-s -w" ./mobile

# 2) build the APK (JDK 17 required)
cd android
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Install over USB (with USB debugging enabled):

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

For a distributable build, configure a release `signingConfig` and run `./gradlew
assembleRelease`.

## Stack

- The Go tunnel is wrapped as a gomobile library (`mobile/mobile.go`) → AAR.
- Kotlin app: `MainActivity` (setup) + foreground `TunnelService` calling
  `mobile.Mobile.start/stop`, and `BrowserActivity` (WebView pinned to the SOCKS5 proxy via
  `androidx.webkit` ProxyController). Namespace `ru.adsrv.webdavtunnel`, `minSdk 24`.
