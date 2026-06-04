#!/usr/bin/env bash
# Простой запуск Linux-клиента webdav-tunnel.
# Поднимает локальный SOCKS5-прокси на 127.0.0.1:1080.
#
# Использование:
#   ./run-client.sh                      # креды берутся из client.env
#   WEBDAV_LOGIN=... WEBDAV_PASSWORD=... ./run-client.sh
#   ./run-client.sh 127.0.0.1:1081       # другой порт прокси
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="${DIR}/webdav-tunnel-linux-amd64"
[ -x "$BIN" ] || BIN="${DIR}/webdav-tunnel"   # fallback на одноимённый бинарник

# Подхватываем client.env, если он есть.
[ -f "${DIR}/client.env" ] && set -a && . "${DIR}/client.env" && set +a

WEBDAV_URL="${WEBDAV_URL:-https://webdav.yandex.ru}"
SOCKS_LISTEN="${1:-127.0.0.1:1080}"

: "${WEBDAV_LOGIN:?Задай WEBDAV_LOGIN (логин Яндекса) в client.env или окружении}"
: "${WEBDAV_PASSWORD:?Задай WEBDAV_PASSWORD (пароль приложения Яндекса)}"

# TUNING_ARGS разбивается на отдельные флаги по пробелам (для Mail.ru — см. client.env).
echo "SOCKS5 будет на ${SOCKS_LISTEN}, реле через ${WEBDAV_URL}"
exec "$BIN" \
  -mode client \
  -webdav "$WEBDAV_URL" \
  -login "$WEBDAV_LOGIN" \
  -password "$WEBDAV_PASSWORD" \
  -socks-listen "$SOCKS_LISTEN" \
  ${TUNING_ARGS:-}
