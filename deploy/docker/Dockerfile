# Минимальный образ: статический бинарник + корневые сертификаты для TLS к Яндексу.
FROM alpine:3.21
RUN apk add --no-cache ca-certificates
COPY webdav-tunnel-linux-amd64 /usr/local/bin/webdav-tunnel
ENTRYPOINT ["/usr/local/bin/webdav-tunnel"]
