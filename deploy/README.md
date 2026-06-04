# Deployment: a cloud WebDAV account as the relay

Topology: `[Linux client] → SOCKS5 → files on WebDAV (e.g. Yandex.Disk) → [exit server] → internet`.

The client **and** the exit server log in to the **same** WebDAV account and exchange data
through files under a `tunnel/` folder. Access to the tunnel == knowing the WebDAV password.
The exit server needs **no inbound ports** — it only makes outbound HTTPS to the WebDAV host.

These instructions use Yandex.Disk as the relay; any WebDAV provider works (see
[Switching provider](#switching-provider)). For a relay-less setup, see *Self-hosted mode*
in the [main README](../README.md).

## 0. WebDAV app password (required with 2FA)

For Yandex: `id.yandex.ru` → **Security** → **App passwords** → create one for the
"Files (WebDAV)" type. Use it as `WEBDAV_PASSWORD` on **both** the server and the client.
(Using an app password rather than the account password is strongly recommended even
without 2FA.)

## 1. Exit server (your VPS)

Build the server binary (`go build -o webdav-tunnel .`, or cross-compile, e.g.
`CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o webdav-tunnel-linux-amd64 .`), then:

```sh
# pick the binary matching your VPS architecture (amd64 or arm64)
scp webdav-tunnel-linux-amd64 root@SERVER:/opt/webdav-tunnel/webdav-tunnel
scp deploy/webdav-tunnel-server.service root@SERVER:/etc/systemd/system/
scp deploy/server.env.example root@SERVER:/opt/webdav-tunnel/server.env   # edit afterwards

ssh root@SERVER
chmod +x /opt/webdav-tunnel/webdav-tunnel
nano /opt/webdav-tunnel/server.env        # fill in login + app password
chmod 600 /opt/webdav-tunnel/server.env
systemctl daemon-reload
systemctl enable --now webdav-tunnel-server
journalctl -u webdav-tunnel-server -f     # wait for "WebDAV: OK" and "server mode: ..."
```

### Docker alternative

`deploy/docker/` contains a `Dockerfile`, `docker-compose.yml`, and `.env.example`:

```sh
cd deploy/docker
cp .env.example .env && nano .env          # WEBDAV_URL / WEBDAV_LOGIN / WEBDAV_PASSWORD
# place the server binary next to the Dockerfile as webdav-tunnel-linux-amd64
docker compose up -d
docker compose logs -f                      # wait for "WebDAV: OK"
```

## 2. Linux client

```sh
cp deploy/client.env.example deploy/client.env   # same login/password as the server
chmod 600 deploy/client.env
./deploy/run-client.sh                            # SOCKS5 on 127.0.0.1:1080
```

Point your app/browser at SOCKS5 `127.0.0.1:1080`. Verify:

```sh
curl -x socks5h://127.0.0.1:1080 https://api.ipify.org    # should print the exit server's IP
```

For Android, see [android/README.md](../android/README.md).

## Switching provider

The tunnel is provider-agnostic — it is just a different WebDAV URL. For Mail.ru, edit the
`*.env` files:

```sh
WEBDAV_URL=https://webdav.cloud.mail.ru
WEBDAV_LOGIN=you@mail.ru
WEBDAV_PASSWORD=<external-app password>    # id.mail.ru -> Security
```

Mail.ru's WebDAV is pickier and more aggressively rate-limited. The server's first run logs
whether it is healthy: look for `WebDAV: OK`. If you see `403/405` on methods instead, the
account may have WebDAV operations restricted (then Mail.ru won't work without changing the
architecture).

## Throttling

The tunnel reacts to `429` (reads `Retry-After`, waits) but does **not** proactively
self-limit — after a pause it returns to full parallelism. For tightly rate-limited
providers (Mail.ru especially), throttle it down via `TUNING_ARGS` in the `*.env` files (use
the **same** values on the server and the client):

```sh
TUNING_ARGS=-chunk-size 524287 -puts 3 -read-min 1 -read-max 4 -poll-min 500ms -poll-max 2s
```

- larger `chunk-size` → fewer files for the same volume → fewer requests
- lower `puts` / `read-max` → fewer parallel requests
- larger `poll-min` / `poll-max` → less frequent polling while idle

You trade throughput and latency for it. If you still hit `429` in bursts, you need a real
req/s limiter (a code change) or the relay-less `selfhosted` mode.

## A note on cloud relays (Yandex / Mail.ru)

The protocol is very "chatty" (frequent polling + many small files). Cloud WebDAV is heavily
rate-limited — the tunnel survives `429` (it backs off), but **throughput stays modest**, and
under heavy load the account may be throttled. If you hit a throughput wall, the only path
without changing the architecture is `selfhosted` mode (an embedded WebDAV server on the
exit host, no cloud relay).
